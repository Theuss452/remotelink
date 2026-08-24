package app.remotelink.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import app.remotelink.control.RemoteAccessibilityService
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
    private data class CaptureSpec(val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int)

    private val lock = Any()
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private val capturer: ScreenCapturerAndroid
    private val textureHelper: SurfaceTextureHelper
    private val localCandidates = ConcurrentLinkedQueue<SignalCandidate>()

    @Volatile private var peer: PeerConnection? = null
    @Volatile private var videoSender: RtpSender? = null
    @Volatile private var controlChannel: DataChannel? = null
    @Volatile private var expectedSessionHash: String? = null
    @Volatile private var controlAuthenticated = false
    @Volatile private var lastCommandSeq = 0L
    @Volatile private var captureStarted = false
    @Volatile private var disposed = false
    @Volatile private var captureProfile = "auto"
    @Volatile private var activeSpec: CaptureSpec? = null
    @Volatile private var peerState = "new"
    @Volatile private var iceState = "new"
    @Volatile private var gatheringState = "new"
    @Volatile private var localCandidateCount = 0

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
    }

    fun isCaptureStarted(): Boolean = captureStarted

    fun createAnswer(sessionHash: String, offerSdp: String): String {
        synchronized(lock) {
            check(!disposed) { "WebRTC host encerrado" }
            closePeerLocked()
            ensureCaptureStartedLocked()
            expectedSessionHash = sessionHash
            controlAuthenticated = false
            lastCommandSeq = 0L
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
        refreshCaptureFormatIfNeeded()
        return peer?.connectionState()?.name?.lowercase() ?: peerState
    }

    fun diagnosticState(sessionHash: String): JSONObject {
        if (!isCurrentSession(sessionHash)) return JSONObject().put("peer", "closed").put("ice", "closed")
        refreshCaptureFormatIfNeeded()
        val spec = activeSpec
        return JSONObject().put("peer", peerState).put("ice", iceState).put("gathering", gatheringState)
            .put("localCandidates", localCandidateCount).put("captureStarted", captureStarted)
            .put("profile", captureProfile).put("width", spec?.width ?: 0).put("height", spec?.height ?: 0)
            .put("fps", spec?.fps ?: 0).put("maxBitrateBps", spec?.maxBitrateBps ?: 0)
    }

    fun setCaptureProfile(sessionHash: String, profile: String): Boolean {
        if (!isCurrentSession(sessionHash)) return false
        val normalized = profile.lowercase().takeIf { it in setOf("auto", "economy", "balanced", "high") } ?: return false
        captureProfile = normalized
        refreshCaptureFormatIfNeeded(force = true)
        applySenderPolicy()
        return true
    }

    fun endSession(sessionHash: String) { synchronized(lock) { if (isCurrentSession(sessionHash)) closePeerLocked() } }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true; closePeerLocked()
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

    private fun ensureCaptureStartedLocked() {
        if (captureStarted) { refreshCaptureFormatIfNeeded(); return }
        val spec = desiredCaptureSpec()
        capturer.startCapture(spec.width, spec.height, spec.fps)
        activeSpec = spec; captureStarted = true
    }

    private fun refreshCaptureFormatIfNeeded(force: Boolean = false) {
        if (!captureStarted || disposed) return
        val wanted = desiredCaptureSpec(); val current = activeSpec
        if (!force && current == wanted) return
        try {
            capturer.changeCaptureFormat(wanted.width, wanted.height, wanted.fps)
            activeSpec = wanted
            applySenderPolicy()
        } catch (_: Exception) {}
    }

    private fun desiredCaptureSpec(): CaptureSpec {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(2); var height = dm.heightPixels.coerceAtLeast(2)
        // Remote control values smoothness over native resolution. The previous
        // 1600px default could collapse to ~2 FPS on lower-end Android encoders.
        val (maxEdge, fps, bitrate) = when (captureProfile) {
            "economy" -> Triple(800, 20, 1_200_000)
            "balanced" -> Triple(1280, 30, 3_000_000)
            "high" -> Triple(1600, 30, 5_000_000)
            else -> Triple(1200, 30, 2_500_000)
        }
        val longest = maxOf(width, height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest.toFloat(); width = (width * scale).toInt(); height = (height * scale).toInt()
        }
        width = (width / 2 * 2).coerceAtLeast(2); height = (height / 2 * 2).coerceAtLeast(2)
        return CaptureSpec(width, height, fps, bitrate)
    }

    private fun applySenderPolicy() {
        val sender = videoSender ?: return
        val spec = activeSpec ?: return
        try {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = spec.maxBitrateBps
                encoding.maxFramerate = spec.fps
            }
            sender.parameters = params
        } catch (_: Exception) {
            // Some vendor WebRTC builds expose fewer RTP knobs. Capture format
            // reduction above remains the safe fallback.
        }
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
        override fun onDataChannel(channel: DataChannel?) { channel ?: return; controlChannel = channel; controlAuthenticated = false; channel.registerObserver(dataObserver(channel)) }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            peerState = newState?.name?.lowercase() ?: "unknown"
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) applySenderPolicy()
            if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) controlAuthenticated = false
        }
    }

    private fun dataObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() { if (channel.state() == DataChannel.State.OPEN) sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true)) }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary || buffer.data.remaining() > MAX_CONTROL_MESSAGE_BYTES) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes); handleControlMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun handleControlMessage(channel: DataChannel, raw: String) {
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        when (obj.optString("type")) {
            "auth" -> {
                val expected = expectedSessionHash; val supplied = obj.optString("token")
                val ok = supplied.length in 32..128 && expected != null && constantTimeEquals(expected, sha256Hex(supplied))
                controlAuthenticated = ok; lastCommandSeq = 0L
                sendJson(channel, JSONObject().put("type", if (ok) "auth_ok" else "auth_failed").put("controlReady", RemoteAccessibilityService.instance != null))
                if (!ok) channel.close()
            }
            "ping" -> if (controlAuthenticated) sendJson(channel, JSONObject().put("type", "pong").put("id", obj.optLong("id")))
            else -> {
                if (!controlAuthenticated) return
                val seq = obj.optLong("seq", -1L); if (seq <= 0L || seq <= lastCommandSeq) return; lastCommandSeq = seq
                dispatchControl(obj, channel)
            }
        }
    }

    private fun dispatchControl(obj: JSONObject, channel: DataChannel) {
        val service = RemoteAccessibilityService.instance ?: run { sendJson(channel, JSONObject().put("type", "control_error").put("error", "accessibility_disabled")); return }
        val ok = when (obj.optString("type")) {
            "tap" -> service.tapNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "swipe" -> service.swipeNormalized(obj.optDouble("x1").toFloat(), obj.optDouble("y1").toFloat(), obj.optDouble("x2").toFloat(), obj.optDouble("y2").toFloat(), obj.optLong("duration", 220L))
            "back" -> service.back(); "home" -> service.home(); "recents" -> service.recents()
            "text" -> service.setFocusedText(obj.optString("text")); else -> false
        }
        if (!ok) sendJson(channel, JSONObject().put("type", "control_error").put("error", "action_failed"))
    }

    private fun sendJson(channel: DataChannel, obj: JSONObject) {
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray(StandardCharsets.UTF_8)), false))
    }

    private fun closePeerLocked() {
        controlAuthenticated = false; lastCommandSeq = 0L; peerState = "closed"; iceState = "closed"; gatheringState = "complete"
        try { controlChannel?.unregisterObserver() } catch (_: Exception) {}; try { controlChannel?.close() } catch (_: Exception) {}; try { controlChannel?.dispose() } catch (_: Exception) {}
        controlChannel = null; videoSender = null
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

    companion object { private const val MAX_CONTROL_MESSAGE_BYTES=16*1024; private val factoryInitialized=AtomicBoolean(false) }
}
