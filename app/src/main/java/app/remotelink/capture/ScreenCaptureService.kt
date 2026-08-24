package app.remotelink.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.remotelink.webrtc.WebRtcHost

class ScreenCaptureService : Service() {
    private var webRtcHost: WebRtcHost? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjection(); return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        webRtcHost?.dispose()
        webRtcHost = WebRtcHost(applicationContext, data) {
            Handler(Looper.getMainLooper()).post { stopProjection() }
        }
        return START_NOT_STICKY
    }

    fun isReady(): Boolean = webRtcHost != null
    fun createAnswer(sessionHash: String, offerSdp: String): String =
        webRtcHost?.createAnswer(sessionHash, offerSdp) ?: error("capture_not_ready")
    fun addRemoteCandidate(sessionHash: String, candidate: WebRtcHost.SignalCandidate): Boolean =
        webRtcHost?.addRemoteCandidate(sessionHash, candidate) == true
    fun drainLocalCandidates(sessionHash: String): List<WebRtcHost.SignalCandidate> =
        webRtcHost?.drainLocalCandidates(sessionHash) ?: emptyList()
    fun connectionState(sessionHash: String): String = webRtcHost?.connectionState(sessionHash) ?: "closed"

    /**
     * Ends only the current WebRTC peer. The MediaProjection authorization and
     * foreground service stay alive so the user can reconnect without being
     * forced through the Android capture permission dialog again.
     */
    fun endSession(sessionHash: String) {
        webRtcHost?.endSession(sessionHash)
    }

    /** Fully revokes screen capture and tears down every WebRTC resource. */
    fun stopAll() {
        stopProjection()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProjection() {
        val host = webRtcHost
        webRtcHost = null
        try { host?.dispose() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        val host = webRtcHost
        webRtcHost = null
        try { host?.dispose() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmissão de tela", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("RemoteLink")
            .setContentText("Transmissão autorizada. Nenhuma tela é enviada sem uma sessão aprovada.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Revogar transmissão", pi).build())
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "app.remotelink.STOP_CAPTURE"
        private const val CHANNEL_ID = "remotelink_capture"
        private const val NOTIFICATION_ID = 4101
        @Volatile var instance: ScreenCaptureService? = null
            private set
        fun intent(context: Context, resultCode: Int, data: Intent) = Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_DATA, data)
    }
}
