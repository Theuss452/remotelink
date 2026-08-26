package app.remotelink.webrtc

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import app.remotelink.control.RemoteAccessibilityService
import app.remotelink.security.SessionCapabilities
import app.remotelink.transfer.IncomingFileReceiver
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcHost(
    private val context: Context,
    private val projectionPermissionData: Intent,
    private val onProjectionStopped: () -> Unit,
    private val onFileApprovalRequested: (FileApprovalRequest) -> Unit = {}
) {
    data class SignalCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)
    data class FileApprovalRequest(val id: String, val name: String, val mime: String, val size: Long)

    private data class PendingFileApproval(
        val request: FileApprovalRequest,
        val channel: DataChannel,
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class CaptureSpec(
        val width: Int,
        val height: Int,
        val fps: Int,
        val minBitrateBps: Int,
        val maxBitrateBps: Int
    )

    private data class CustomCaptureConfig(
        val maxEdge: Int,
        val fps: Int,
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
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val captureGeometryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteLink-CaptureGeometry").apply { isDaemon = true }
    }

    @Volatile private var peer: PeerConnection? = null
    @Volatile private var videoSender: RtpSender? = null
    @Volatile private var controlChannel: DataChannel? = null
    @Volatile private var motionChannel: DataChannel? = null
    @Volatile private var fileChannel: DataChannel? = null
    @Volatile private var expectedSessionHash: String? = null
    @Volatile private var controlAuthenticated = false
    @Volatile private var motionAuthenticated = false
    @Volatile private var fileAuthenticated = false
    @Volatile private var lastCommandSeq = 0L
    @Volatile private var lastMotionSeq = 0L
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var captureStarted = false
    @Volatile private var disposed = false
    @Volatile private var captureProfile = "auto"
    @Volatile private var autoTier = "balanced"
    @Volatile private var customCapture = CustomCaptureConfig(1280, 30, 4_800_000)
    @Volatile private var activeSpec: CaptureSpec? = null
    @Volatile private var peerState = "new"
    @Volatile private var iceState = "new"
    @Volatile private var gatheringState = "new"
    @Volatile private var localCandidateCount = 0
    @Volatile private var pendingFileApproval: PendingFileApproval? = null
    @Volatile private var baseCaptureWidth = 0
    @Volatile private var baseCaptureHeight = 0
    @Volatile private var lastDisplayWidth = 0
    @Volatile private var lastDisplayHeight = 0
    @Volatile private var displayRevision = 0L
    @Volatile private var forceGeometryRefresh = false

    private val geometryRefreshRunnable = Runnable { refreshCaptureGeometryAsync() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            forceGeometryRefresh = true
            scheduleGeometryRefresh(0L)
            watchdogHandler.postDelayed({
                if (!disposed && captureStarted) {
                    forceGeometryRefresh = true
                    scheduleGeometryRefresh(0L)
                }
            }, ROTATION_VERIFY_DELAY_MS)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!disposed) {
                val now = System.currentTimeMillis()
                val pending = pendingFileApproval
                if (pending != null && now - pending.createdAt > FILE_APPROVAL_TIMEOUT_MS) {
                    resolveFileApproval(pending.request.id, false, "approval_timeout")
                }
                if (
                    expectedSessionHash != null && controlAuthenticated && lastHeartbeatAt > 0L &&
                    now - lastHeartbeatAt > WATCHDOG_TIMEOUT_MS
                ) {
                    synchronized(lock) {
                        if (
                            expectedSessionHash != null && controlAuthenticated &&
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

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        capturer = ScreenCapturerAndroid(
            projectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width <= 1 || height <= 1) return
                    forceGeometryRefresh = true
                    scheduleGeometryRefresh(0L)
                }

                override fun onStop() {
                    captureStarted = false
                    activeSpec = null
                    baseCaptureWidth = 0
                    baseCaptureHeight = 0
                    onProjectionStopped()
                }
            }
        )

        videoSource = factory.createVideoSource(true)
        textureHelper = SurfaceTextureHelper.create("RemoteLink-Capture", eglBase.eglBaseContext)
        capturer.initialize(textureHelper, context.applicationContext, videoSource.capturerObserver)
        videoTrack = factory.createVideoTrack("remotelink-screen", videoSource).apply {
            setEnabled(SessionCapabilities.canViewScreen())
        }

        try { displayManager?.registerDisplayListener(displayListener, watchdogHandler) }
        catch (_: Exception) {}
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    fun isCaptureStarted(): Boolean = captureStarted
    fun hasActiveSession(): Boolean = expectedSessionHash != null && peerState == "connected"

    fun createAnswer(sessionHash: String, offerSdp: String): String {
        synchronized(lock) {
            check(!disposed) { "WebRTC host encerrado" }
            closePeerLocked()
            ensureCaptureStartedLocked()
            expectedSessionHash = sessionHash
            controlAuthenticated = false
            motionAuthenticated = false
            fileAuthenticated = false
            lastCommandSeq = 0L
            lastMotionSeq = 0L
            lastHeartbeatAt = 0L
            localCandidates.clear()
            localCandidateCount = 0
            peerState = "new"
            iceState = "new"
            gatheringState = "new"

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
            applyCapabilities()
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
        val display = physicalDisplaySize()
        val caps = SessionCapabilities.snapshot()
        val custom = customCapture
        val heartbeatAge = if (lastHeartbeatAt > 0L) System.currentTimeMillis() - lastHeartbeatAt else -1L
        return JSONObject()
            .put("peer", peerState)
            .put("ice", iceState)
            .put("gathering", gatheringState)
            .put("localCandidates", localCandidateCount)
            .put("captureStarted", captureStarted)
            .put("profile", captureProfile)
            .put("autoTier", autoTier)
            .put("width", spec?.width ?: 0)
            .put("height", spec?.height ?: 0)
            .put("fps", spec?.fps ?: 0)
            .put("minBitrateBps", spec?.minBitrateBps ?: 0)
            .put("maxBitrateBps", spec?.maxBitrateBps ?: 0)
            .put("displayWidth", display.x)
            .put("displayHeight", display.y)
            .put("captureContentWidth", capturer.currentWidth())
            .put("captureContentHeight", capturer.currentHeight())
            .put("orientation", orientationLabel(display))
            .put("baseCaptureWidth", baseCaptureWidth)
            .put("baseCaptureHeight", baseCaptureHeight)
            .put("displayRevision", displayRevision)
            .put("motionChannelReady", motionAuthenticated)
            .put("fileChannelReady", fileAuthenticated)
            .put("fileApprovalPending", pendingFileApproval != null)
            .put("customMaxEdge", custom.maxEdge)
            .put("customFps", custom.fps)
            .put("customMaxBitrateBps", custom.maxBitrateBps)
            .put("capScreen", caps.screen)
            .put("capTouch", caps.touch)
            .put("capKeyboard", caps.keyboard)
            .put("capFiles", caps.files)
            .put("heartbeatAgeMs", heartbeatAge)
            .put("watchdogTimeoutMs", WATCHDOG_TIMEOUT_MS)
    }

    fun setCaptureProfile(sessionHash: String, profile: String): Boolean {
        if (!isCurrentSession(sessionHash)) return false
        return setCaptureProfileInternal(profile)
    }

    fun applyCapabilities() {
        val caps = SessionCapabilities.snapshot()
        try { videoTrack.setEnabled(caps.screen) } catch (_: Exception) {}
        if (!caps.touch) RemoteAccessibilityService.instance?.cancelRemoteDrag()
        if (!caps.files) cancelPendingFileTransfer("capability_revoked")
        sendCapabilities()
    }

    fun cancelPendingFileTransfer(reason: String = "capability_revoked") {
        synchronized(lock) {
            val pending = pendingFileApproval
            pendingFileApproval = null
            fileReceiver.cancel()
            if (pending != null) {
                sendJson(pending.channel, JSONObject().put("type", "file_error").put("id", pending.request.id).put("error", reason))
            }
        }
    }

    fun resolveFileApproval(id: String, approved: Boolean) {
        resolveFileApproval(id, approved, if (approved) null else "approval_denied")
    }

    private fun resolveFileApproval(id: String, approved: Boolean, denialReason: String?) {
        synchronized(lock) {
            val pending = pendingFileApproval ?: return
            if (pending.request.id != id) return
            pendingFileApproval = null
            if (!approved || !SessionCapabilities.canReceiveFiles()) {
                sendJson(pending.channel, JSONObject().put("type", "file_error").put("id", id).put("error", denialReason ?: if (SessionCapabilities.canReceiveFiles()) "approval_denied" else "capability_denied"))
                return
            }
            if (!fileAuthenticated || pending.channel !== fileChannel || pending.channel.state() != DataChannel.State.OPEN) {
                sendJson(pending.channel, JSONObject().put("type", "file_error").put("id", id).put("error", "session_closed"))
                return
            }
            val request = pending.request
            val error = fileReceiver.start(request.id, request.name, request.mime, request.size)
            if (error == null) sendJson(pending.channel, JSONObject().put("type", "file_ready").put("id", id))
            else sendJson(pending.channel, JSONObject().put("type", "file_error").put("id", id).put("error", error))
        }
    }

    fun endSession(sessionHash: String) {
        synchronized(lock) { if (isCurrentSession(sessionHash)) closePeerLocked() }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.removeCallbacks(geometryRefreshRunnable)
            try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
            closePeerLocked()
            try { if (captureStarted) capturer.stopCapture() } catch (_: Exception) {}
            captureStarted = false
            activeSpec = null
            baseCaptureWidth = 0
            baseCaptureHeight = 0
            try { capturer.dispose() } catch (_: Exception) {}
            try { textureHelper.dispose() } catch (_: Exception) {}
            try { videoTrack.dispose() } catch (_: Exception) {}
            try { videoSource.dispose() } catch (_: Exception) {}
            try { factory.dispose() } catch (_: Exception) {}
            try { eglBase.release() } catch (_: Exception) {}
            captureGeometryExecutor.shutdownNow()
        }
    }

    private fun physicalDisplaySize(): Point {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            var width = metrics.widthPixels.coerceAtLeast(2)
            var height = metrics.heightPixels.coerceAtLeast(2)
            @Suppress("DEPRECATION")
            val rotation = display.rotation
            val rotatedLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            if (rotatedLandscape && width < height) {
                val tmp = width; width = height; height = tmp
            } else if (!rotatedLandscape && width > height && rotation == Surface.ROTATION_0) {
                val tmp = width; width = height; height = tmp
            }
            return Point(width, height)
        }
        val dm = context.resources.displayMetrics
        return Point(dm.widthPixels.coerceAtLeast(2), dm.heightPixels.coerceAtLeast(2))
    }

    private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2

    private fun scaledSizeFor(source: Point, maxEdge: Int): Pair<Int, Int> {
        var width = source.x.coerceAtLeast(2)
        var height = source.y.coerceAtLeast(2)
        val longest = maxOf(width, height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest.toFloat()
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        return even(width) to even(height)
    }

    private fun orientationLabel(size: Point): String = if (size.x > size.y) "landscape" else "portrait"

    private fun ensureCaptureStartedLocked() {
        if (captureStarted) {
            forceGeometryRefresh = true
            scheduleGeometryRefresh(0L)
            applyOutputProfile()
            return
        }
        val display = physicalDisplaySize()
        val baseWidth = even(display.x)
        val baseHeight = even(display.y)
        capturer.startCapture(baseWidth, baseHeight, BASE_CAPTURE_FPS)
        baseCaptureWidth = baseWidth
        baseCaptureHeight = baseHeight
        lastDisplayWidth = display.x
        lastDisplayHeight = display.y
        displayRevision += 1L
        captureStarted = true
        applyOutputProfile(display)
        applyCapabilities()
    }

    private fun scheduleGeometryRefresh(delayMs: Long = DISPLAY_CHANGE_DEBOUNCE_MS) {
        if (disposed) return
        watchdogHandler.removeCallbacks(geometryRefreshRunnable)
        watchdogHandler.postDelayed(geometryRefreshRunnable, delayMs)
    }

    private fun requestGeometryRepair() {
        forceGeometryRefresh = true
        scheduleGeometryRefresh(0L)
    }

    private fun refreshCaptureGeometryAsync() {
        if (disposed || !captureStarted) return
        val display = physicalDisplaySize()
        val forced = forceGeometryRefresh
        val changed = forced || display.x != lastDisplayWidth || display.y != lastDisplayHeight
        if (!changed) return
        forceGeometryRefresh = false

        val wantedBaseWidth = even(display.x)
        val wantedBaseHeight = even(display.y)
        captureGeometryExecutor.execute {
            if (disposed || !captureStarted) return@execute
            try {
                if (forced || wantedBaseWidth != baseCaptureWidth || wantedBaseHeight != baseCaptureHeight) {
                    capturer.changeCaptureFormat(wantedBaseWidth, wantedBaseHeight, BASE_CAPTURE_FPS)
                }
                if (disposed || !captureStarted) return@execute
                baseCaptureWidth = wantedBaseWidth
                baseCaptureHeight = wantedBaseHeight
                lastDisplayWidth = display.x
                lastDisplayHeight = display.y
                displayRevision += 1L
                applyOutputProfile(display)
                applySenderPolicy()
                sendDisplayGeometry()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao adaptar captura à geometria ${display.x}x${display.y}", e)
            }
        }
    }

    private fun setCaptureProfileInternal(profile: String): Boolean {
        val normalized = profile.lowercase().takeIf { it in setOf("auto", "economy", "balanced", "high", "fluid", "fluid60", "custom") } ?: return false
        captureProfile = normalized
        if (normalized == "auto") autoTier = "balanced"
        applyOutputProfile()
        applySenderPolicy()
        sendDisplayGeometry()
        return true
    }

    private fun setAutoTierInternal(tier: String): Boolean {
        if (captureProfile != "auto") return false
        val normalized = tier.lowercase().takeIf { it in setOf("economy", "balanced", "fluid") } ?: return false
        if (autoTier == normalized) return true
        autoTier = normalized
        applyOutputProfile()
        applySenderPolicy()
        sendDisplayGeometry()
        return true
    }

    private fun setCustomCaptureInternal(maxEdge: Int, fps: Int, maxBitrateBps: Int): Boolean {
        if (maxEdge !in CUSTOM_MIN_EDGE..CUSTOM_MAX_EDGE) return false
        if (fps !in CUSTOM_MIN_FPS..CUSTOM_MAX_FPS) return false
        if (maxBitrateBps !in CUSTOM_MIN_BITRATE_BPS..CUSTOM_MAX_BITRATE_BPS) return false
        customCapture = CustomCaptureConfig(even(maxEdge), fps, maxBitrateBps)
        captureProfile = "custom"
        applyOutputProfile()
        applySenderPolicy()
        sendDisplayGeometry()
        return true
    }

    private fun desiredCaptureSpec(source: Point = physicalDisplaySize()): CaptureSpec {
        val landscape = source.x > source.y
        val settings = when (captureProfile) {
            "economy" -> Triple(if (landscape) 960 else 720, 24, 250_000 to 1_800_000)
            "high" -> Triple(1600, 30, 750_000 to 8_000_000)
            "fluid" -> Triple(if (landscape) 1280 else 1080, 45, 500_000 to 6_500_000)
            "fluid60" -> Triple(if (landscape) 1280 else 1080, 60, 700_000 to 8_500_000)
            "custom" -> {
                val c = customCapture
                val min = (c.maxBitrateBps / 8).coerceIn(250_000, 1_500_000)
                Triple(c.maxEdge, c.fps, min to c.maxBitrateBps)
            }
            "auto" -> when (autoTier) {
                "economy" -> Triple(if (landscape) 960 else 720, 24, 250_000 to 1_800_000)
                // Auto tops out at 45 FPS for frame integrity. 60 FPS remains available as the
                // explicit Fluid 60/custom mode when the user knowingly wants the extra load.
                "fluid" -> Triple(if (landscape) 1280 else 1080, 45, 600_000 to 6_500_000)
                else -> Triple(if (landscape) 1280 else 1080, 30, 400_000 to 4_800_000)
            }
            "balanced" -> Triple(if (landscape) 1280 else 1080, 30, 400_000 to 4_800_000)
            else -> Triple(if (landscape) 1280 else 1080, 30, 400_000 to 4_800_000)
        }
        val (width, height) = scaledSizeFor(source, settings.first)
        return CaptureSpec(width, height, settings.second, settings.third.first, settings.third.second)
    }

    private fun applyOutputProfile(source: Point = physicalDisplaySize()) {
        if (!captureStarted || disposed) return
        val wanted = desiredCaptureSpec(source)
        try {
            videoSource.adaptOutputFormat(wanted.width, wanted.height, wanted.fps)
            activeSpec = wanted
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao adaptar formato de saída", e)
        }
    }

    private fun applySenderPolicy() {
        val sender = videoSender ?: return
        val spec = activeSpec ?: return
        try {
            val params = sender.parameters
            val favorFps = captureProfile == "fluid60" || captureProfile == "fluid" ||
                (captureProfile == "auto" && autoTier == "fluid") ||
                (captureProfile == "custom" && spec.fps >= 50)
            params.degradationPreference = if (favorFps) RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE else RtpParameters.DegradationPreference.BALANCED
            params.encodings.forEach { encoding ->
                encoding.minBitrateBps = spec.minBitrateBps
                encoding.maxBitrateBps = spec.maxBitrateBps
                encoding.maxFramerate = spec.fps
                encoding.scaleResolutionDownBy = 1.0
                encoding.bitratePriority = if (favorFps) 2.0 else 1.5
                encoding.networkPriority = Priority.HIGH
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao aplicar política do sender", e)
        }
    }

    private fun capabilitiesJson(): JSONObject {
        val caps = SessionCapabilities.snapshot()
        return JSONObject().put("screen", caps.screen).put("touch", caps.touch).put("keyboard", caps.keyboard).put("files", caps.files)
    }

    private fun sendCapabilities(channel: DataChannel? = controlChannel) {
        val target = channel ?: return
        if (!controlAuthenticated || target.state() != DataChannel.State.OPEN) return
        sendJson(target, JSONObject().put("type", "capabilities").put("capabilities", capabilitiesJson()))
    }

    private fun sendDisplayGeometry(channel: DataChannel? = controlChannel) {
        val target = channel ?: return
        if (!controlAuthenticated || target.state() != DataChannel.State.OPEN) return
        val display = physicalDisplaySize()
        val spec = activeSpec
        sendJson(target, JSONObject().put("type", "display_geometry")
            .put("displayWidth", display.x).put("displayHeight", display.y)
            .put("captureContentWidth", capturer.currentWidth()).put("captureContentHeight", capturer.currentHeight())
            .put("orientation", orientationLabel(display)).put("streamWidth", spec?.width ?: 0).put("streamHeight", spec?.height ?: 0)
            .put("streamFps", spec?.fps ?: 0).put("profile", captureProfile).put("autoTier", autoTier).put("revision", displayRevision))
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) { iceState = newState?.name?.lowercase() ?: "unknown" }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) { gatheringState = newState?.name?.lowercase() ?: "unknown" }
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            localCandidateCount += 1
            localCandidates.add(SignalCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) {
            channel ?: return
            when (channel.label()) {
                "control" -> { controlChannel = channel; controlAuthenticated = false; channel.registerObserver(controlObserver(channel)) }
                "motion" -> { motionChannel = channel; motionAuthenticated = false; lastMotionSeq = 0L; channel.registerObserver(motionObserver(channel)) }
                "file" -> { fileChannel = channel; fileAuthenticated = false; channel.registerObserver(fileObserver(channel)) }
                else -> { try { channel.close() } catch (_: Exception) {}; try { channel.dispose() } catch (_: Exception) {} }
            }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            peerState = newState?.name?.lowercase() ?: "unknown"
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                applyCapabilities(); applySenderPolicy(); sendDisplayGeometry(); sendCapabilities()
            }
            if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                controlAuthenticated = false; motionAuthenticated = false; fileAuthenticated = false; pendingFileApproval = null; fileReceiver.cancel()
            }
        }
    }

    private fun controlObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() { if (channel.state() == DataChannel.State.OPEN) sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true)) }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary || buffer.data.remaining() > MAX_CONTROL_MESSAGE_BYTES) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes); handleControlMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun motionObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() { if (channel.state() == DataChannel.State.OPEN) sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true).put("channel", "motion")) }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary || buffer.data.remaining() > MAX_MOTION_MESSAGE_BYTES) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes); handleMotionMessage(channel, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun fileObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() { if (channel.state() == DataChannel.State.OPEN) sendJson(channel, JSONObject().put("type", "hello").put("authRequired", true).put("channel", "file")) }
        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.data.remaining() > MAX_FILE_CHANNEL_MESSAGE_BYTES) return
            if (buffer.binary) handleFileBinary(channel, buffer.data) else {
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes); handleFileControl(channel, bytes.toString(StandardCharsets.UTF_8))
            }
        }
    }

    private fun handleControlMessage(channel: DataChannel, text: String) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        val type = obj.optString("type")
        if (!controlAuthenticated) {
            if (type != "auth") return
            val provided = obj.optString("tokenHash")
            val expected = expectedSessionHash
            if (expected != null && constantTimeEquals(provided, expected)) {
                controlAuthenticated = true; lastHeartbeatAt = System.currentTimeMillis()
                sendJson(channel, JSONObject().put("type", "auth_ok").put("capabilities", capabilitiesJson()))
                sendCapabilities(channel); sendDisplayGeometry(channel)
            } else sendJson(channel, JSONObject().put("type", "auth_error"))
            return
        }
        val seq = obj.optLong("seq", -1L)
        if (seq <= lastCommandSeq) return
        lastCommandSeq = seq
        when (type) {
            "ping" -> { lastHeartbeatAt = System.currentTimeMillis(); sendJson(channel, JSONObject().put("type", "pong").put("t", obj.optLong("t"))) }
            "tap" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.tapNormalized(obj.optDouble("x"), obj.optDouble("y"))
            "drag_start" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.dragStartNormalized(obj.optDouble("x"), obj.optDouble("y"))
            "drag_move" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.dragMoveNormalized(obj.optDouble("x"), obj.optDouble("y"))
            "drag_end" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.dragEndNormalized(obj.optDouble("x"), obj.optDouble("y"))
            "drag_cancel" -> RemoteAccessibilityService.instance?.cancelRemoteDrag()
            "swipe" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.swipeNormalized(obj.optDouble("x1"), obj.optDouble("y1"), obj.optDouble("x2"), obj.optDouble("y2"), obj.optLong("durationMs", 220L))
            "nav_back" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.globalBack()
            "nav_home" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.globalHome()
            "nav_recents" -> if (SessionCapabilities.canTouch()) RemoteAccessibilityService.instance?.globalRecents()
            "input_text" -> if (SessionCapabilities.canKeyboard()) RemoteAccessibilityService.instance?.inputText(obj.optString("text").take(MAX_TEXT_LENGTH))
            "key" -> if (SessionCapabilities.canKeyboard()) RemoteAccessibilityService.instance?.sendKey(obj.optString("key"), obj.optBoolean("ctrl", false), obj.optBoolean("shift", false), obj.optBoolean("alt", false))
            "capture_geometry_refresh" -> { requestGeometryRepair(); sendJson(channel, JSONObject().put("type", "capture_geometry_refresh_result").put("ok", true)) }
            "capture_profile" -> {
                val ok = setCaptureProfileInternal(obj.optString("profile"))
                sendJson(channel, JSONObject().put("type", "capture_profile_result").put("ok", ok).put("profile", captureProfile).put("autoTier", autoTier))
            }
            "capture_auto_tier" -> {
                val ok = setAutoTierInternal(obj.optString("tier"))
                sendJson(channel, JSONObject().put("type", "capture_auto_tier_result").put("ok", ok).put("profile", captureProfile).put("autoTier", autoTier))
            }
            "capture_custom" -> {
                val ok = setCustomCaptureInternal(obj.optInt("maxEdge", -1), obj.optInt("fps", -1), obj.optInt("maxBitrateBps", -1))
                sendJson(channel, JSONObject().put("type", "capture_custom_result").put("ok", ok).put("profile", captureProfile))
            }
        }
    }

    private fun handleMotionMessage(channel: DataChannel, text: String) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        val type = obj.optString("type")
        if (!motionAuthenticated) {
            if (type != "auth") return
            val provided = obj.optString("tokenHash")
            val expected = expectedSessionHash
            if (expected != null && constantTimeEquals(provided, expected)) { motionAuthenticated = true; sendJson(channel, JSONObject().put("type", "auth_ok").put("channel", "motion")) }
            else sendJson(channel, JSONObject().put("type", "auth_error").put("channel", "motion"))
            return
        }
        if (type != "drag_move" || !SessionCapabilities.canTouch()) return
        val seq = obj.optLong("seq", -1L)
        if (seq <= lastMotionSeq) return
        lastMotionSeq = seq
        RemoteAccessibilityService.instance?.dragMoveNormalized(obj.optDouble("x"), obj.optDouble("y"))
    }

    private fun handleFileControl(channel: DataChannel, text: String) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        val type = obj.optString("type")
        if (!fileAuthenticated) {
            if (type != "auth") return
            val provided = obj.optString("tokenHash")
            val expected = expectedSessionHash
            if (expected != null && constantTimeEquals(provided, expected)) { fileAuthenticated = true; sendJson(channel, JSONObject().put("type", "auth_ok").put("channel", "file")) }
            else sendJson(channel, JSONObject().put("type", "auth_error").put("channel", "file"))
            return
        }
        if (!SessionCapabilities.canReceiveFiles()) { sendJson(channel, JSONObject().put("type", "file_error").put("error", "capability_denied")); return }
        when (type) {
            "file_start" -> {
                val request = FileApprovalRequest(obj.optString("id"), obj.optString("name"), obj.optString("mime", "application/octet-stream"), obj.optLong("size", -1L))
                if (!validFileRequest(request)) { sendJson(channel, JSONObject().put("type", "file_error").put("id", request.id).put("error", "invalid_request")); return }
                synchronized(lock) {
                    if (pendingFileApproval != null || fileReceiver.isActive()) { sendJson(channel, JSONObject().put("type", "file_error").put("id", request.id).put("error", "busy")); return }
                    pendingFileApproval = PendingFileApproval(request, channel)
                }
                onFileApprovalRequested(request); sendJson(channel, JSONObject().put("type", "file_pending_approval").put("id", request.id))
            }
            "file_end" -> {
                val id = obj.optString("id")
                val result = fileReceiver.finish(id)
                if (result != null) sendJson(channel, JSONObject().put("type", "file_complete").put("id", id).put("uri", result))
                else sendJson(channel, JSONObject().put("type", "file_error").put("id", id).put("error", "finish_failed"))
            }
            "file_cancel" -> { fileReceiver.cancel(); sendJson(channel, JSONObject().put("type", "file_cancelled").put("id", obj.optString("id"))) }
        }
    }

    private fun handleFileBinary(channel: DataChannel, buffer: ByteBuffer) {
        if (!fileAuthenticated || !SessionCapabilities.canReceiveFiles()) return
        if (buffer.remaining() <= 0 || buffer.remaining() > MAX_FILE_CHUNK_BYTES) return
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        val error = fileReceiver.append(bytes)
        if (error != null) sendJson(channel, JSONObject().put("type", "file_error").put("error", error))
    }

    private fun validFileRequest(request: FileApprovalRequest): Boolean {
        if (!request.id.matches(Regex("^[A-Za-z0-9._:-]{8,80}$"))) return false
        if (request.name.isBlank() || request.name.length > 180 || request.name.contains('/') || request.name.contains('\\')) return false
        if (request.size !in 1..MAX_FILE_BYTES) return false
        return true
    }

    private fun isCurrentSession(hash: String): Boolean = expectedSessionHash != null && constantTimeEquals(expectedSessionHash!!, hash)

    private fun closePeerLocked() {
        controlAuthenticated = false; motionAuthenticated = false; fileAuthenticated = false; lastCommandSeq = 0L; lastMotionSeq = 0L; lastHeartbeatAt = 0L
        pendingFileApproval = null; fileReceiver.cancel(); RemoteAccessibilityService.instance?.cancelRemoteDrag()
        try { controlChannel?.close() } catch (_: Exception) {}; try { controlChannel?.dispose() } catch (_: Exception) {}; controlChannel = null
        try { motionChannel?.close() } catch (_: Exception) {}; try { motionChannel?.dispose() } catch (_: Exception) {}; motionChannel = null
        try { fileChannel?.close() } catch (_: Exception) {}; try { fileChannel?.dispose() } catch (_: Exception) {}; fileChannel = null
        try { peer?.close() } catch (_: Exception) {}; try { peer?.dispose() } catch (_: Exception) {}; peer = null; videoSender = null
        expectedSessionHash = null; localCandidates.clear(); peerState = "closed"; iceState = "closed"; gatheringState = "closed"
    }

    private fun createAnswerBlocking(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1); var result: SessionDescription? = null; var failure: String? = null
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) { result = desc; latch.countDown() }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { failure = error; latch.countDown() }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
        check(latch.await(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timeout ao criar answer" }
        failure?.let { error(it) }; return result ?: error("Answer WebRTC ausente")
    }

    private fun setRemoteDescriptionBlocking(pc: PeerConnection, desc: SessionDescription) = setDescriptionBlocking { observer -> pc.setRemoteDescription(observer, desc) }
    private fun setLocalDescriptionBlocking(pc: PeerConnection, desc: SessionDescription) = setDescriptionBlocking { observer -> pc.setLocalDescription(observer, desc) }
    private fun setDescriptionBlocking(action: (SdpObserver) -> Unit) {
        val latch = CountDownLatch(1); var failure: String? = null
        action(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() { latch.countDown() }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) { failure = error; latch.countDown() }
        })
        check(latch.await(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timeout ao aplicar SDP" }; failure?.let { error(it) }
    }

    private fun sendJson(channel: DataChannel, obj: JSONObject) {
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = obj.toString().toByteArray(StandardCharsets.UTF_8)
        try { channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) } catch (_: Exception) {}
    }

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val TAG = "RemoteLink-WebRTC"
        private const val BASE_CAPTURE_FPS = 60
        private const val MAX_CONTROL_MESSAGE_BYTES = 16 * 1024
        private const val MAX_MOTION_MESSAGE_BYTES = 2 * 1024
        private const val MAX_FILE_CHANNEL_MESSAGE_BYTES = 96 * 1024
        private const val MAX_FILE_CHUNK_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = 100L * 1024L * 1024L
        private const val MAX_TEXT_LENGTH = 4000
        private const val FILE_APPROVAL_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val WATCHDOG_TIMEOUT_MS = 20_000L
        private const val SDP_TIMEOUT_SECONDS = 8L
        private const val DISPLAY_CHANGE_DEBOUNCE_MS = 90L
        private const val ROTATION_VERIFY_DELAY_MS = 550L
        private const val CUSTOM_MIN_EDGE = 480
        private const val CUSTOM_MAX_EDGE = 2560
        private const val CUSTOM_MIN_FPS = 15
        private const val CUSTOM_MAX_FPS = 60
        private const val CUSTOM_MIN_BITRATE_BPS = 600_000
        private const val CUSTOM_MAX_BITRATE_BPS = 20_000_000
        private val factoryInitialized = AtomicBoolean(false)
    }
}
