package app.remotelink.webrtc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
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
 * Important rule: once capture is running, onCapturedContentResize() is the primary authority for
 * the projection Surface/VirtualDisplay geometry. WebRtcHost may request a fallback resize, but it
 * is delayed and discarded whenever MediaProjection has already supplied a newer size. This avoids
 * two independent rotation paths racing and leaving the texture with a landscape buffer containing
 * a portrait/cropped image.
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
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    @Volatile private var capturing = false
    @Volatile private var disposed = false
    @Volatile private var lastProjectionResizeAt = 0L
    @Volatile private var resizeRevision = 0L

    private val internalProjectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (disposed || width <= 1 || height <= 1) return
            lastProjectionResizeAt = SystemClock.elapsedRealtime()
            resizeRevision += 1L
            resizeProjection(width, height)
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
     * WebRtcHost historically used changeCaptureFormat() for rotation. Keep it only as a delayed
     * OEM fallback. If MediaProjection reports a resize before the fallback fires, its callback
     * wins and this request is discarded.
     */
    @Synchronized
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (disposed || !capturing) return
        val helper = textureHelper ?: return
        val requestedWidth = even(width)
        val requestedHeight = even(height)
        val requestTime = SystemClock.elapsedRealtime()
        val revisionAtRequest = resizeRevision

        helper.handler.postDelayed({
            if (disposed || !capturing) return@postDelayed
            if (resizeRevision != revisionAtRequest || lastProjectionResizeAt >= requestTime) {
                return@postDelayed
            }
            if (this.width == requestedWidth && this.height == requestedHeight) return@postDelayed
            resizeProjection(requestedWidth, requestedHeight)
        }, EXTERNAL_RESIZE_FALLBACK_MS)
    }

    @Synchronized
    private fun resizeProjection(requestedWidth: Int, requestedHeight: Int) {
        val newWidth = even(requestedWidth)
        val newHeight = even(requestedHeight)
        if (newWidth <= 1 || newHeight <= 1) return
        if (newWidth == width && newHeight == height) return

        width = newWidth
        height = newHeight
        val helper = textureHelper ?: return

        runOnCaptureThread(helper) {
            if (disposed || !capturing) return@runOnCaptureThread

            // The texture buffer and VirtualDisplay are resized as one operation. Keeping these
            // dimensions identical is critical: a mismatch produces the exact zoom/crop symptom
            // where only half of a rotated phone is visible.
            helper.setTextureSize(newWidth, newHeight)
            val display = virtualDisplay
            if (display != null) {
                display.resize(newWidth, newHeight, densityDpi)
                // Keep the same Surface object; Android 14 permits one projection/VirtualDisplay
                // instance and resizing it is the supported rotation path.
                surface?.let { display.setSurface(it) }
            }
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

    companion object {
        private const val EXTERNAL_RESIZE_FALLBACK_MS = 850L
    }
}
