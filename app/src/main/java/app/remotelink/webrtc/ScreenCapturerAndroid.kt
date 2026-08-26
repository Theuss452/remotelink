package app.remotelink.webrtc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-display MediaProjection capturer used by RemoteLink.
 *
 * Important rules:
 * 1. Encoder/downscale resolution is independent from this VirtualDisplay surface.
 * 2. A configuration/rotation change updates the existing VirtualDisplay with resize() and a
 *    freshly attached Surface. Android 14+ allows one createVirtualDisplay() call per projection
 *    session, and the documented resize + new Surface flow also clears stale buffer geometry that
 *    can otherwise look like a zoomed/half-screen capture on some devices.
 * 3. If Android exposes a uniformly scaled geometry (for example 2400x1080 -> 1200x540), density
 *    is scaled by the same factor so the logical viewport stays constant instead of making UI
 *    elements twice as large.
 */
class ScreenCapturerAndroid(
    private val permissionData: Intent,
    private val clientProjectionCallback: MediaProjection.Callback
) : VideoCapturer, VideoSink {

    private var textureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var appContext: Context? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var densityDpi = 0
    private var baseDensityDpi = 0
    private var referenceLongEdge = 0
    private var referenceShortEdge = 0
    @Volatile private var capturing = false
    @Volatile private var disposed = false

    private val internalProjectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (disposed || !capturing || width <= 1 || height <= 1) return

            // Treat callback dimensions as a change signal. WebRtcHost decides whether Auto or a
            // user-selected custom surface size should be applied, then calls changeCaptureFormat.
            clientProjectionCallback.onCapturedContentResize(width, height)
        }

        override fun onStop() {
            val wasCapturing = capturing
            capturing = false
            releaseVirtualDisplay()
            releaseSurface()
            mediaProjection = null
            if (wasCapturing) capturerObserver?.onCapturerStopped()
            clientProjectionCallback.onStop()
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        check(!disposed) { "Capturador encerrado" }
        textureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        appContext = applicationContext.applicationContext
        baseDensityDpi = applicationContext.resources.configuration.densityDpi.coerceAtLeast(1)
        densityDpi = baseDensityDpi
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(!disposed) { "Capturador encerrado" }
        check(!capturing) { "Captura já iniciada" }

        val helper = textureHelper ?: error("Capturador não inicializado")
        val observer = capturerObserver ?: error("Observer indisponível")
        val context = appContext ?: error("Contexto indisponível")

        this.width = even(width)
        this.height = even(height)
        referenceLongEdge = maxOf(this.width, this.height)
        referenceShortEdge = minOf(this.width, this.height)
        densityDpi = baseDensityDpi
        helper.setTextureSize(this.width, this.height)
        helper.setFrameRotation(0)

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: error("MediaProjectionManager indisponível")
        val projection = manager.getMediaProjection(Activity.RESULT_OK, permissionData)
            ?: error("Não foi possível obter MediaProjection")
        mediaProjection = projection
        projection.registerCallback(internalProjectionCallback, helper.handler)

        val outputSurface = Surface(helper.surfaceTexture)
        surface = outputSurface
        virtualDisplay = projection.createVirtualDisplay(
            "RemoteLink_FullDisplay",
            this.width,
            this.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            outputSurface,
            null,
            helper.handler
        ) ?: error("Não foi possível criar VirtualDisplay")

        capturing = true
        observer.onCapturerStarted(true)
        helper.startListening(this)
    }

    /**
     * Changes the MediaProjection surface only. Encoder resolution/FPS are controlled separately
     * by VideoSource.adaptOutputFormat().
     */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (disposed || !capturing || width <= 1 || height <= 1) return
        resizeCaptureSurface(width, height)
    }

    private fun resizeCaptureSurface(requestedWidth: Int, requestedHeight: Int) {
        val newWidth = even(requestedWidth)
        val newHeight = even(requestedHeight)
        if (newWidth <= 1 || newHeight <= 1) return
        if (newWidth == width && newHeight == height) return

        val helper = textureHelper ?: return
        runOnCaptureThread(helper) {
            if (disposed || !capturing) return@runOnCaptureThread
            if (newWidth == width && newHeight == height) return@runOnCaptureThread

            val display = virtualDisplay ?: return@runOnCaptureThread
            val newDensity = densityForGeometry(newWidth, newHeight)

            // Android's supported configuration-change flow is resize() + a Surface whose buffer
            // size matches the new geometry. Reusing only the old Surface is what can leave an OEM
            // compositor with portrait viewport state after switching to landscape.
            try { display.setSurface(null) } catch (_: Exception) {}
            releaseSurface()

            helper.setTextureSize(newWidth, newHeight)
            display.resize(newWidth, newHeight, newDensity)

            val newSurface = Surface(helper.surfaceTexture)
            surface = newSurface
            display.setSurface(newSurface)

            width = newWidth
            height = newHeight
            densityDpi = newDensity
        }
    }

    private fun densityForGeometry(newWidth: Int, newHeight: Int): Int {
        val refLong = referenceLongEdge
        val refShort = referenceShortEdge
        if (refLong <= 1 || refShort <= 1 || baseDensityDpi <= 0) return baseDensityDpi.coerceAtLeast(1)

        val longScale = maxOf(newWidth, newHeight).toDouble() / refLong.toDouble()
        val shortScale = minOf(newWidth, newHeight).toDouble() / refShort.toDouble()

        // Only compensate DPI for an approximately uniform resolution scale. Rotation itself has
        // scale ~= 1 because long/short edges are compared independently of orientation.
        val uniform = abs(longScale - shortScale) <= 0.08
        if (!uniform) return baseDensityDpi

        val scale = ((longScale + shortScale) / 2.0).coerceIn(0.25, 2.0)
        return (baseDensityDpi * scale).roundToInt().coerceIn(MIN_DENSITY_DPI, MAX_DENSITY_DPI)
    }

    private fun releaseVirtualDisplay() {
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
    }

    private fun releaseSurface() {
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    fun currentWidth(): Int = width
    fun currentHeight(): Int = height
    fun currentDensityDpi(): Int = densityDpi

    @Synchronized
    override fun stopCapture() {
        val helper = textureHelper
        if (helper == null) {
            capturing = false
            return
        }

        runOnCaptureThread(helper) {
            val wasCapturing = capturing
            capturing = false
            try { helper.stopListening() } catch (_: Exception) {}
            releaseVirtualDisplay()
            releaseSurface()

            val projection = mediaProjection
            mediaProjection = null
            if (projection != null) {
                try { projection.unregisterCallback(internalProjectionCallback) } catch (_: Exception) {}
                try { projection.stop() } catch (_: Exception) {}
            }
            if (wasCapturing) capturerObserver?.onCapturerStopped()
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (capturing && !disposed) capturerObserver?.onFrameCaptured(frame)
    }

    override fun isScreencast(): Boolean = true

    fun isCapturing(): Boolean = capturing

    @Synchronized
    override fun dispose() {
        if (disposed) return
        try { stopCapture() } catch (_: Exception) {}
        disposed = true
        textureHelper = null
        capturerObserver = null
        appContext = null
    }

    private fun runOnCaptureThread(helper: SurfaceTextureHelper, block: () -> Unit) {
        if (Thread.currentThread() === helper.handler.looper.thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        helper.handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2

    companion object {
        private const val MIN_DENSITY_DPI = 72
        private const val MAX_DENSITY_DPI = 1000
    }
}
