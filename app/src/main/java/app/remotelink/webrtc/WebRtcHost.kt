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

/**
 * Single-viewer WebRTC host for the LAN-only alpha.
 * HTTP is used only for signaling; video and controls travel through WebRTC.
 */
class WebRtcHost(
    private val context: Context,
    private val projectionPermissionData: Intent,
    private val onProjectionStopped: () -> Unit
) {
    data class SignalCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

    private val lock = Any()
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack
    private val capturer: ScreenCapturerAndroid
    private val textureHelper: SurfaceTextureHelper
    private val localCandidates = ConcurrentLinkedQueue<SignalCandidate>()

    @Volatile private var peer: PeerConnection? = null
    @Volatile private var controlChannel: DataChannel? = null
    @Volatile private var expectedSessionHash: String? = null
    @Volatile private var controlAuthenticated = false
    @Volatile private var lastCommandSeq = 0L
    @Volatile private var captureStarted = false
    @Volatile private var disposed = false

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
            localCandidates.clear()

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }
            val created = factory.createPeerConnection(config, observer())
                ?: error("Não foi possível criar RTCPeerConnection")
            peer = created
            created.addTrack(videoTrack, listOf("remotelink-screen"))

            setRemoteDescriptionBlocking(created, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            val answer = createAnswerBlocking(created)
            setLocalDescriptionBlocking(created, answer)
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
        return peer?.connectionState()?.name?.lowercase() ?: "new"
    }

    fun endSession(sessionHash: String) {
        synchronized(lock) {
            if (!isCurrentSession(sessionHash)) return
            closePeerLocked()
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            closePeerLocked()
            try { if (captureStarted) capturer.stopCapture() } catch (_: Exception) {}
            captureStarted = false
            try { capturer.dispose() } catch (_: Exception) {}
            try { textureHelper.dispose() } catch (_: Exception) {}
            try { videoTrack.dispose() } catch (_: Exception) {}
            try { videoSource.dispose() } catch (_: Exception) {}
            try { factory.dispose() } catch (_: Exception) {}
            try { eglBase.release() } catch (_: Exception) {}
        }
    }

    private fun ensureCaptureStartedLocked() {
        if (captureStarted) return
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(360)
        var height = dm.heightPixels.coerceAtLeast(640)
        val longest = maxOf(width, height)
        if (longest > 1920) {
            val scale = 1920f / longest.toFloat()
            width = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            height = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        }
        capturer.startCapture(width, height, 30)
        captureStarted = true
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            localCandidates.add(SignalCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) {
            channel ?: return
            controlChannel = channel
            controlAuthenticated = false
            channel.registerObserver(dataObserver(channel))
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED) {
                controlAuthenticated = false
            }
        }
    }

    private fun dataObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (channel.state() == DataChannel.State.OPEN) {
                sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true))
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            handleControlMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun handleControlMessage(channel: DataChannel, raw: String) {
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        when (obj.optString("type")) {
            "auth" -> {
                val expected = expectedSessionHash
                val ok = expected != null && constantTimeEquals(expected, sha256Hex(obj.optString("token")))
                controlAuthenticated = ok
                lastCommandSeq = 0L
                sendJson(
                    channel,
                    JSONObject().put("type", if (ok) "auth_ok" else "auth_failed")
                        .put("controlReady", RemoteAccessibilityService.instance != null)
                )
                if (!ok) channel.close()
            }
            "ping" -> {
                if (!controlAuthenticated) return
                sendJson(channel, JSONObject().put("type", "pong").put("id", obj.optLong("id")))
            }
            else -> {
                if (!controlAuthenticated) return
                val seq = obj.optLong("seq", -1L)
                if (seq <= 0L || seq <= lastCommandSeq) return
                lastCommandSeq = seq
                dispatchControl(obj, channel)
            }
        }
    }

    private fun dispatchControl(obj: JSONObject, channel: DataChannel) {
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            sendJson(channel, JSONObject().put("type", "control_error").put("error", "accessibility_disabled"))
            return
        }
        val ok = when (obj.optString("type")) {
            "tap" -> service.tapNormalized(obj.optDouble("x").toFloat(), obj.optDouble("y").toFloat())
            "swipe" -> service.swipeNormalized(
                obj.optDouble("x1").toFloat(), obj.optDouble("y1").toFloat(),
                obj.optDouble("x2").toFloat(), obj.optDouble("y2").toFloat(),
                obj.optLong("duration", 300L)
            )
            "back" -> service.back()
            "home" -> service.home()
            "recents" -> service.recents()
            "text" -> service.setFocusedText(obj.optString("text"))
            else -> false
        }
        if (!ok) sendJson(channel, JSONObject().put("type", "control_error").put("error", "action_failed"))
    }

    private fun sendJson(channel: DataChannel, obj: JSONObject) {
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray(StandardCharsets.UTF_8)), false))
    }

    private fun closePeerLocked() {
        controlAuthenticated = false
        lastCommandSeq = 0L
        try { controlChannel?.unregisterObserver() } catch (_: Exception) {}
        try { controlChannel?.close() } catch (_: Exception) {}
        try { controlChannel?.dispose() } catch (_: Exception) {}
        controlChannel = null
        try { peer?.close() } catch (_: Exception) {}
        try { peer?.dispose() } catch (_: Exception) {}
        peer = null
        expectedSessionHash = null
        localCandidates.clear()
    }

    private fun isCurrentSession(hash: String): Boolean =
        expectedSessionHash?.let { constantTimeEquals(it, hash) } == true

    private fun setRemoteDescriptionBlocking(pc: PeerConnection, description: SessionDescription) {
        val observer = BlockingSdpObserver()
        pc.setRemoteDescription(observer, description)
        observer.await("setRemoteDescription")
    }

    private fun createAnswerBlocking(pc: PeerConnection): SessionDescription {
        val observer = BlockingSdpObserver()
        pc.createAnswer(observer, MediaConstraints())
        return observer.awaitDescription("createAnswer")
    }

    private fun setLocalDescriptionBlocking(pc: PeerConnection, description: SessionDescription) {
        val observer = BlockingSdpObserver()
        pc.setLocalDescription(observer, description)
        observer.await("setLocalDescription")
    }

    private class BlockingSdpObserver : SdpObserver {
        private val latch = CountDownLatch(1)
        @Volatile private var description: SessionDescription? = null
        @Volatile private var failure: String? = null

        override fun onCreateSuccess(desc: SessionDescription?) { description = desc; latch.countDown() }
        override fun onSetSuccess() { latch.countDown() }
        override fun onCreateFailure(message: String?) { failure = message ?: "SDP create failure"; latch.countDown() }
        override fun onSetFailure(message: String?) { failure = message ?: "SDP set failure"; latch.countDown() }

        fun await(operation: String) {
            if (!latch.await(10, TimeUnit.SECONDS)) error("$operation excedeu o tempo limite")
            failure?.let { error("$operation: $it") }
        }
        fun awaitDescription(operation: String): SessionDescription {
            await(operation)
            return description ?: error("$operation não retornou SDP")
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    companion object { private val factoryInitialized = AtomicBoolean(false) }
}
