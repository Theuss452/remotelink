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

/**
 * Full-display MediaProjection capturer used by RemoteLink.
 *
 * The VirtualDisplay always follows the physical display geometry supplied by WebRtcHost.
 * MediaProjection.onCapturedContentResize() is treated as a change signal only: on some Android
 * builds it can report a scaled content size (for example 1200x540 for a 2400x1080 display).
 * Resizing the VirtualDisplay to that scaled size changes the virtual display viewport/density
 * relationship and makes the remote screen look zoomed/cropped. Stream downscaling belongs only
 * to VideoSource.adaptOutputFormat(), never to the MediaProjection surface.
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
    private var densityDpi = 0
    @Volatile private var capturing = false
    @Volatile private var disposed = false

    private val internalProjectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (disposed || !capturing || width <= 1 || height <= 1) return

            // Do not resize the VirtualDisplay from this callback. Android may report the
            // scaled capture-content size rather than the physical screen size. WebRtcHost is
            // notified immediately and re-reads the real display geometry through DisplayManager,
            // then calls changeCaptureFormat() with that verified physical size.
            clientProjectionCallback.onCapturedContentResize(width, height)
        }

        override fun onStop() {
            val wasCapturing = capturing
            capturing = false
            try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { surface?.release() } catch (_: Exception) {}
            surface = null
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
        densityDpi = applicationContext.resources.configuration.densityDpi.coerceAtLeast(1)
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
     * Verified physical-display repair path. This changes only the MediaProjection surface;
     * encoder resolution/FPS are controlled separately by VideoSource.adaptOutputFormat().
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

            // Keep producer and consumer at the same verified physical geometry. Downscaled
            // stream profiles must never leak into these dimensions.
            helper.setTextureSize(newWidth, newHeight)
            virtualDisplay?.resize(newWidth, newHeight, densityDpi)

            width = newWidth
            height = newHeight
        }
    }

    fun currentWidth(): Int = width
    fun currentHeight(): Int = height

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
            try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { surface?.release() } catch (_: Exception) {}
            surface = null

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
}
