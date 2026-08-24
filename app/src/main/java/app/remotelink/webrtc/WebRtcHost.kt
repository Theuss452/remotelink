package app.remotelink.webrtc

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Display
import app.remotelink.control.RemoteAccessibilityService
import app.remotelink.transfer.IncomingFileReceiver
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcHost(
    private val context: Context,
    private val projectionPermissionData: Intent,
    private val onProjectionStopped: () -> Unit
) {
    data class SignalCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)
    private data class CaptureSpec(
        val width: Int,
        val height: Int,
        val fps: Int,
        val minBitrateBps: Int,
        val maxBitrateBps: Int
    )

    private val lock = Any()
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private val capturer: ScreenCapturerAndroid
    private val textureHelper: SurfaceTextureHelper
    private val localCandidates = ConcurrentLinkedQueue<SignalCandidate>()
    private val fileReceiver = IncomingFileReceiver(context.applicationContext)
    private val watchdogHandler = Handler(Looper.getMainLooper())

    @Volatile private var peer: PeerConnection? = null
    @Volatile private var videoSender: RtpSender? = null
    @Volatile private var controlChannel: DataChannel? = null
    @Volatile private var fileChannel: DataChannel? = null
    @Volatile private var expectedSessionHash: String? = null
    @Volatile private var controlAuthenticated = false
    @Volatile private var fileAuthenticated = false
    @Volatile private var lastCommandSeq = 0L
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var captureStarted = false
    @Volatile private var disposed = false
    @Volatile private var captureProfile = "auto"
    @Volatile private var activeSpec: CaptureSpec? = null
    @Volatile private var peerState = "new"
    @Volatile private var iceState = "new"
    @Volatile private var gatheringState = "new"
    @Volatile private var localCandidateCount = 0

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!disposed) {
                val now = System.currentTimeMillis()
                if (
                    expectedSessionHash != null &&
                    controlAuthenticated &&
                    lastHeartbeatAt > 0L &&
                    now - lastHeartbeatAt > WATCHDOG_TIMEOUT_MS
                ) {
                    synchronized(lock) {
                        if (
                            expectedSessionHash != null &&
                            controlAuthenticated &&
                            System.currentTimeMillis() - lastHeartbeatAt > WATCHDOG_TIMEOUT_MS
                        ) closePeerLocked()
                    }
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    init {
        if (factoryInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
        }
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        capturer = ScreenCapturerAndroid(
            projectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    captureStarted = false
                    activeSpec = null
                    onProjectionStopped()
                }
            }
        )
        videoSource = factory.createVideoSource(true)
        textureHelper = SurfaceTextureHelper.create("RemoteLink-Capture", eglBase.eglBaseContext)
        capturer.initialize(textureHelper, context.applicationContext, videoSource.capturerObserver)
        videoTrack = factory.createVideoTrack("remotelink-screen", videoSource).apply { setEnabled(true) }
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    fun isCaptureStarted(): Boolean = captureStarted

    fun createAnswer(sessionHash: String, offerSdp: String): String {
        synchronized(lock) {
            check(!disposed) { "WebRTC host encerrado" }
            closePeerLocked()
            ensureCaptureStartedLocked()
            expectedSessionHash = sessionHash
            controlAuthenticated = false
            fileAuthenticated = false
            lastCommandSeq = 0L
            lastHeartbeatAt = 0L
            localCandidates.clear(); localCandidateCount = 0
            peerState = "new"; iceState = "new"; gatheringState = "new"

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }
            val created = factory.createPeerConnection(config, observer())
                ?: error("Não foi possível criar RTCPeerConnection")
            peer = created
            videoSender = created.addTrack(videoTrack, listOf("remotelink-screen"))

            setRemoteDescriptionBlocking(created, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            val answer = createAnswerBlocking(created)
            setLocalDescriptionBlocking(created, answer)
            applySenderPolicy()
            return answer.description
        }
    }

    fun addRemoteCandidate(sessionHash: String, candidate: SignalCandidate): Boolean {
        if (!isCurrentSession(sessionHash)) return false
        return peer?.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)) == true
    }

    fun drainLocalCandidates(sessionHash: String): List<SignalCandidate> {
        if (!isCurrentSession(sessionHash)) return emptyList()
        val out = ArrayList<SignalCandidate>()
        while (true) out += localCandidates.poll() ?: break
        return out
    }

    fun connectionState(sessionHash: String): String {
        if (!isCurrentSession(sessionHash)) return "closed"
        return peer?.connectionState()?.name?.lowercase() ?: peerState
    }

    fun diagnosticState(sessionHash: String): JSONObject {
        if (!isCurrentSession(sessionHash)) return JSONObject().put("peer", "closed").put("ice", "closed")
        val spec = activeSpec
        val heartbeatAge = if (lastHeartbeatAt > 0L) System.currentTimeMillis() - lastHeartbeatAt else -1L
        return JSONObject().put("peer", peerState).put("ice", iceState).put("gathering", gatheringState)
            .put("localCandidates", localCandidateCount).put("captureStarted", captureStarted)
            .put("profile", captureProfile).put("width", spec?.width ?: 0).put("height", spec?.height ?: 0)
            .put("fps", spec?.fps ?: 0).put("minBitrateBps", spec?.minBitrateBps ?: 0)
            .put("maxBitrateBps", spec?.maxBitrateBps ?: 0)
            .put("fileChannelReady", fileAuthenticated)
            .put("heartbeatAgeMs", heartbeatAge)
            .put("watchdogTimeoutMs", WATCHDOG_TIMEOUT_MS)
    }

    fun setCaptureProfile(sessionHash: String, profile: String): Boolean {
        if (!isCurrentSession(sessionHash)) return false
        return setCaptureProfileInternal(profile)
    }

    fun endSession(sessionHash: String) { synchronized(lock) { if (isCurrentSession(sessionHash)) closePeerLocked() } }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            closePeerLocked()
            try { if (captureStarted) capturer.stopCapture() } catch (_: Exception) {}
            captureStarted = false; activeSpec = null
            try { capturer.dispose() } catch (_: Exception) {}
            try { textureHelper.dispose() } catch (_: Exception) {}
            try { videoTrack.dispose() } catch (_: Exception) {}
            try { videoSource.dispose() } catch (_: Exception) {}
            try { factory.dispose() } catch (_: Exception) {}
            try { eglBase.release() } catch (_: Exception) {}
        }
    }

    private fun physicalDisplaySize(): Point {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) return Point(metrics.widthPixels, metrics.heightPixels)
        }
        val dm = context.resources.displayMetrics
        return Point(dm.widthPixels.coerceAtLeast(2), dm.heightPixels.coerceAtLeast(2))
    }

    private fun scaledSize(maxEdge: Int): Pair<Int, Int> {
        val physical = physicalDisplaySize()
        var width = physical.x.coerceAtLeast(2)
        var height = physical.y.coerceAtLeast(2)
        val longest = maxOf(width, height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest.toFloat()
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        width = (width / 2 * 2).coerceAtLeast(2)
        height = (height / 2 * 2).coerceAtLeast(2)
        return width to height
    }

    private fun ensureCaptureStartedLocked() {
        if (captureStarted) {
            applyOutputProfile()
            return
        }
        // Keep MediaProjection capture dimensions stable for the whole permission session.
        // Quality changes only adapt VideoSource output; this avoids VirtualDisplay churn
        // and keeps the input coordinate system completely independent of stream quality.
        val (baseWidth, baseHeight) = scaledSize(BASE_CAPTURE_MAX_EDGE)
        capturer.startCapture(baseWidth, baseHeight, BASE_CAPTURE_FPS)
        captureStarted = true
        applyOutputProfile()
    }

    private fun setCaptureProfileInternal(profile: String): Boolean {
        val normalized = profile.lowercase().takeIf { it in setOf("auto", "economy", "balanced", "high", "fluid") }
            ?: return false
        captureProfile = normalized
        applyOutputProfile()
        applySenderPolicy()
        return true
    }

    private fun desiredCaptureSpec(): CaptureSpec {
        val settings = when (captureProfile) {
            "economy" -> Triple(800, 20, 500_000 to 1_400_000)
            "balanced" -> Triple(1280, 30, 1_000_000 to 3_200_000)
            // Slightly lower than the old 1600/6 Mbps profile: several mobile
            // encoders accumulated frames or produced visual instability there.
            "high" -> Triple(1440, 30, 1_600_000 to 4_800_000)
            "fluid" -> Triple(1080, 60, 1_400_000 to 4_500_000)
            else -> Triple(1280, 30, 900_000 to 3_600_000)
        }
        val (width, height) = scaledSize(settings.first)
        return CaptureSpec(width, height, settings.second, settings.third.first, settings.third.second)
    }

    private fun applyOutputProfile() {
        if (!captureStarted || disposed) return
        val wanted = desiredCaptureSpec()
        try {
            videoSource.adaptOutputFormat(wanted.width, wanted.height, wanted.fps)
            activeSpec = wanted
        } catch (_: Exception) {
            // Do not recreate the MediaProjection capture on profile failure.
            // Keeping the last good format is safer than causing visible churn.
        }
    }

    private fun applySenderPolicy() {
        val sender = videoSender ?: return
        val spec = activeSpec ?: return
        try {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            params.encodings.forEach { encoding ->
                encoding.minBitrateBps = spec.minBitrateBps
                encoding.maxBitrateBps = spec.maxBitrateBps
                encoding.maxFramerate = spec.fps
                encoding.bitratePriority = if (captureProfile == "fluid") 2.2 else 2.0
            }
            sender.parameters = params
        } catch (_: Exception) {}
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) { iceState = newState?.name?.lowercase() ?: "unknown" }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) { gatheringState = newState?.name?.lowercase() ?: "unknown" }
        override fun onIceCandidate(candidate: IceCandidate?) { candidate ?: return; localCandidateCount += 1; localCandidates.add(SignalCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)) }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) {
            channel ?: return
            when (channel.label()) {
                "file" -> {
                    fileChannel = channel
                    fileAuthenticated = false
                    channel.registerObserver(fileObserver(channel))
                }
                else -> {
                    controlChannel = channel
                    controlAuthenticated = false
                    channel.registerObserver(controlObserver(channel))
                }
            }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            peerState = newState?.name?.lowercase() ?: "unknown"
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) applySenderPolicy()
            if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                controlAuthenticated = false
                fileAuthenticated = false
                fileReceiver.cancel()
            }
        }
    }

    private fun controlObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (channel.state() == DataChannel.State.OPEN) {
                sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true))
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary || buffer.data.remaining() > MAX_CONTROL_MESSAGE_BYTES) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
            handleControlMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun fileObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (channel.state() == DataChannel.State.OPEN) sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true).put("maxFileBytes", IncomingFileReceiver.MAX_FILE_BYTES))
            if (channel.state() == DataChannel.State.CLOSED) { fileAuthenticated = false; fileReceiver.cancel() }
        }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer ?: return
            if (buffer.binary) {
                if (!fileAuthenticated || buffer.data.remaining() > IncomingFileReceiver.MAX_CHUNK_BYTES) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val error = fileReceiver.append(bytes)
                if (error != null) {
                    fileReceiver.cancel(); sendJson(channel, JSONObject().put("type", "file_error").put("error", error))
                }
                return
            }
            if (buffer.data.remaining() > MAX_CONTROL_MESSAGE_BYTES) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
            handleFileMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun authenticate(supplied: String): Boolean {
        val expected = expectedSessionHash
        return supplied.length in 32..128 && expected != null && constantTimeEquals(expected, sha256Hex(supplied))
    }

    private fun touchHeartbeat() {
        lastHeartbeatAt = System.currentTimeMillis()
    }

    private fun handleControlMessage(channel: DataChannel, raw: String) {
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        when (obj.optString("type")) {
            "auth" -> {
                val ok = authenticate(obj.optString("token"))
                controlAuthenticated = ok; lastCommandSeq = 0L
                if (ok) touchHeartbeat()
                sendJson(channel, JSONObject().put("type", if (ok) "auth_ok" else "auth_failed").put("controlReady", RemoteAccessibilityService.instance != null))
                if (!ok) channel.close()
            }
            "ping" -> if (controlAuthenticated) {
                touchHeartbeat()
                sendJson(channel, JSONObject().put("type", "pong").put("id", obj.optLong("id")))
            }
            else -> {
                if (!controlAuthenticated) return
                touchHeartbeat()
                val seq = obj.optLong("seq", -1L); if (seq <= 0L || seq <= lastCommandSeq) return; lastCommandSeq = seq
                if (obj.optString("type") == "capture_profile") {
                    val ok = setCaptureProfileInternal(obj.optString("profile"))
                    sendJson(channel, JSONObject().put("type", "capture_profile_result").put("ok", ok).put("profile", captureProfile))
                } else dispatchControl(obj, channel)
            }
        }
    }

    private fun handleFileMessage(channel: DataChannel, raw: String) {
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        when (obj.optString("type")) {
            "auth" -> {
                val ok = authenticate(obj.optString("token"))
                fileAuthenticated = ok
                sendJson(channel, JSONObject().put("type", if (ok) "auth_ok" else "auth_failed").put("maxFileBytes", IncomingFileReceiver.MAX_FILE_BYTES))
                if (!ok) channel.close()
            }
            "file_begin" -> {
                if (!fileAuthenticated) return
                val id = obj.optString("id")
                val error = fileReceiver.start(id, obj.optString("name"), obj.optString("mime"), obj.optLong("size", -1L))
                if (error == null) sendJson(channel, JSONObject().put("type", "file_ready").put("id", id))
                else sendJson(channel, JSONObject().put("type", "file_error").put("id", id).put("error", error))
            }
            "file_end" -> {
                if (!fileAuthenticated) return
                val id = obj.optString("id")
                val result = fileReceiver.finish(id)
                result.onSuccess { saved ->
                    sendJson(channel, JSONObject().put("type", "file_saved").put("id", id).put("name", saved.name).put("location", saved.location).put("size", saved.size))
                }.onFailure { error ->
                    fileReceiver.cancel(); sendJson(channel, JSONObject().put("type", "file_error").put("id", id).put("error", error.message ?: "save_failed"))
                }
            }
            "file_cancel" -> { fileReceiver.cancel(); sendJson(channel, JSONObject().put("type", "file_cancelled")) }
        }
    }

    private fun dispatchControl(obj: JSONObject, channel: DataChannel) {
        val service = RemoteAccessibilityService.instance ?: run {
            sendJson(channel, JSONObject().put("type", "control_error").put("error", "accessibility_disabled")); return
        }
        val ok = when (obj.optString("type")) {
            "tap" -> service.tapNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "swipe" -> service.swipeNormalized(obj.optDouble("x1").toFloat(), obj.optDouble("y1").toFloat(), obj.optDouble("x2").toFloat(), obj.optDouble("y2").toFloat(), obj.optLong("duration", 180L))
            "drag_start" -> service.dragStartNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "drag_move" -> service.dragMoveNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "drag_end" -> service.dragEndNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "drag_cancel" -> { service.cancelRemoteDrag(); true }
            "back" -> service.back(); "home" -> service.home(); "recents" -> service.recents()
            "text" -> service.setFocusedText(obj.optString("text"))
            "key_text" -> service.insertFocusedText(obj.optString("text"))
            "key_backspace" -> service.deleteFocusedText(backward = true)
            "key_delete" -> service.deleteFocusedText(backward = false)
            "key_cursor" -> service.moveFocusedCursor(obj.optString("direction"))
            "key_select_all" -> service.selectAllFocusedText()
            "key_enter" -> service.pressFocusedEnter()
            else -> false
        }
        if (!ok) sendJson(channel, JSONObject().put("type", "control_error").put("error", "action_failed"))
    }

    private fun sendJson(channel: DataChannel, obj: JSONObject) {
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray(StandardCharsets.UTF_8)), false))
    }

    private fun closePeerLocked() {
        RemoteAccessibilityService.instance?.cancelRemoteDrag(); fileReceiver.cancel()
        controlAuthenticated = false; fileAuthenticated = false; lastCommandSeq = 0L; lastHeartbeatAt = 0L
        peerState = "closed"; iceState = "closed"; gatheringState = "complete"
        listOf(controlChannel, fileChannel).forEach { channel ->
            try { channel?.unregisterObserver() } catch (_: Exception) {}
            try { channel?.close() } catch (_: Exception) {}
            try { channel?.dispose() } catch (_: Exception) {}
        }
        controlChannel = null; fileChannel = null; videoSender = null
        try { peer?.close() } catch (_: Exception) {}; try { peer?.dispose() } catch (_: Exception) {}; peer = null
        expectedSessionHash = null; localCandidates.clear(); localCandidateCount = 0
    }

    private fun isCurrentSession(hash: String): Boolean = expectedSessionHash?.let { constantTimeEquals(it, hash) } == true
    private fun setRemoteDescriptionBlocking(pc: PeerConnection, description: SessionDescription) { val o=BlockingSdpObserver(); pc.setRemoteDescription(o,description); o.await("setRemoteDescription") }
    private fun createAnswerBlocking(pc: PeerConnection): SessionDescription { val o=BlockingSdpObserver(); pc.createAnswer(o,MediaConstraints()); return o.awaitDescription("createAnswer") }
    private fun setLocalDescriptionBlocking(pc: PeerConnection, description: SessionDescription) { val o=BlockingSdpObserver(); pc.setLocalDescription(o,description); o.await("setLocalDescription") }

    private class BlockingSdpObserver : SdpObserver {
        private val latch=CountDownLatch(1); @Volatile private var description:SessionDescription?=null; @Volatile private var failure:String?=null
        override fun onCreateSuccess(desc: SessionDescription?){description=desc;latch.countDown()}; override fun onSetSuccess(){latch.countDown()}
        override fun onCreateFailure(message:String?){failure=message?:"SDP create failure";latch.countDown()}; override fun onSetFailure(message:String?){failure=message?:"SDP set failure";latch.countDown()}
        fun await(operation:String){if(!latch.await(10,TimeUnit.SECONDS))error("$operation excedeu o tempo limite");failure?.let{error("$operation: $it")}}
        fun awaitDescription(operation:String):SessionDescription{await(operation);return description?:error("$operation não retornou SDP")}
    }

    private fun sha256Hex(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun constantTimeEquals(a:String,b:String):Boolean=MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8),b.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val MAX_CONTROL_MESSAGE_BYTES = 16 * 1024
        private const val BASE_CAPTURE_MAX_EDGE = 1440
        private const val BASE_CAPTURE_FPS = 60
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val WATCHDOG_TIMEOUT_MS = 30_000L
        private val factoryInitialized = AtomicBoolean(false)
    }
}
