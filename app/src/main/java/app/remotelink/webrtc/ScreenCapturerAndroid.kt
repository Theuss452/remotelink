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
 * MediaProjection.onCapturedContentResize() is the preferred geometry authority because it
 * describes the content Android is actually projecting. WebRtcHost may also request the current
 * physical display geometry through changeCaptureFormat() as an idempotent repair fallback for
 * devices/OEMs that delay or miss a resize callback. Both paths converge on the same resize
 * routine, so the SurfaceTexture and VirtualDisplay can never intentionally diverge.
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
            resizeCaptureSurface(width, height)
            clientProjectionCallback.onCapturedContentResize(
                this@ScreenCapturerAndroid.width,
                this@ScreenCapturerAndroid.height
            )
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
     * Repair path used by WebRtcHost after a display-change notification. This must not resize
     * the encoder profile; it only makes the MediaProjection surface match the real display.
     * resizeCaptureSurface() is idempotent, so a late duplicate callback is harmless.
     */
    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (disposed || !capturing || width <= 1 || height <= 1) return
        resizeCaptureSurface(width, height)
    }

    @Synchronized
    private fun resizeCaptureSurface(requestedWidth: Int, requestedHeight: Int) {
        val newWidth = even(requestedWidth)
        val newHeight = even(requestedHeight)
        if (newWidth <= 1 || newHeight <= 1) return
        if (newWidth == width && newHeight == height) return

        val helper = textureHelper ?: return
        runOnCaptureThread(helper) {
            if (disposed || !capturing) return@runOnCaptureThread
            if (newWidth == width && newHeight == height) return@runOnCaptureThread

            // Resize the consumer buffer first, then its producer. Keeping these dimensions
            // identical prevents Android from centering a landscape image inside a portrait
            // VirtualDisplay (the small-screen-in-the-middle symptom seen after rotation).
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
