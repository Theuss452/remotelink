package app.remotelink.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.remotelink.security.SessionCapabilities
import app.remotelink.webrtc.WebRtcHost
import org.json.JSONObject

class ScreenCaptureService : Service() {
    private var webRtcHost: WebRtcHost? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createTransferChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProjection()
                return START_NOT_STICKY
            }
            ACTION_FILE_APPROVE -> {
                val id = intent.getStringExtra(EXTRA_TRANSFER_ID).orEmpty()
                if (SessionCapabilities.canReceiveFiles()) {
                    webRtcHost?.resolveFileApproval(id, true)
                } else {
                    webRtcHost?.resolveFileApproval(id, false)
                }
                getSystemService(NotificationManager::class.java).cancel(FILE_NOTIFICATION_ID)
                return START_NOT_STICKY
            }
            ACTION_FILE_DENY -> {
                val id = intent.getStringExtra(EXTRA_TRANSFER_ID).orEmpty()
                webRtcHost?.resolveFileApproval(id, false)
                getSystemService(NotificationManager::class.java).cancel(FILE_NOTIFICATION_ID)
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val previous = webRtcHost
        webRtcHost = null
        try { previous?.dispose() } catch (_: Exception) {}

        try {
            webRtcHost = WebRtcHost(
                applicationContext,
                data,
                onProjectionStopped = {
                    Handler(Looper.getMainLooper()).post { stopProjection() }
                },
                onFileApprovalRequested = { request ->
                    Handler(Looper.getMainLooper()).post {
                        if (SessionCapabilities.canReceiveFiles()) {
                            showFileApprovalNotification(request)
                        } else {
                            webRtcHost?.resolveFileApproval(request.id, false)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao inicializar WebRTC/MediaProjection", t)
            webRtcHost = null
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    fun isReady(): Boolean = webRtcHost != null
    fun isCaptureStarted(): Boolean = webRtcHost?.isCaptureStarted() == true
    fun hasActiveSession(): Boolean = webRtcHost?.hasActiveSession() == true

    fun createAnswer(sessionHash: String, offerSdp: String): String =
        webRtcHost?.createAnswer(sessionHash, offerSdp) ?: error("capture_not_ready")

    fun addRemoteCandidate(sessionHash: String, candidate: WebRtcHost.SignalCandidate): Boolean =
        webRtcHost?.addRemoteCandidate(sessionHash, candidate) == true

    fun drainLocalCandidates(sessionHash: String): List<WebRtcHost.SignalCandidate> =
        webRtcHost?.drainLocalCandidates(sessionHash) ?: emptyList()

    fun connectionState(sessionHash: String): String =
        webRtcHost?.connectionState(sessionHash) ?: "closed"

    fun diagnosticState(sessionHash: String): JSONObject =
        (webRtcHost?.diagnosticState(sessionHash)
            ?: JSONObject().put("peer", "closed").put("ice", "closed"))
            .put("capScreen", SessionCapabilities.canViewScreen())
            .put("capTouch", SessionCapabilities.canTouch())
            .put("capKeyboard", SessionCapabilities.canUseKeyboard())
            .put("capFiles", SessionCapabilities.canReceiveFiles())

    fun setCaptureProfile(sessionHash: String, profile: String): Boolean =
        webRtcHost?.setCaptureProfile(sessionHash, profile) == true

    fun endSession(sessionHash: String) {
        webRtcHost?.endSession(sessionHash)
    }

    fun applyCapabilities() {
        webRtcHost?.applyCapabilities()
    }

    fun cancelPendingFileTransfer() {
        getSystemService(NotificationManager::class.java).cancel(FILE_NOTIFICATION_ID)
        webRtcHost?.cancelPendingFileTransfer()
    }

    fun stopAll() {
        stopProjection()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProjection() {
        getSystemService(NotificationManager::class.java).cancel(FILE_NOTIFICATION_ID)
        val host = webRtcHost
        webRtcHost = null
        try { host?.dispose() } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(FILE_NOTIFICATION_ID)
        if (instance === this) instance = null
        val host = webRtcHost
        webRtcHost = null
        try { host?.dispose() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Transmissão de tela",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun createTransferChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    FILE_CHANNEL_ID,
                    "Transferências de arquivos",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Solicitações para receber arquivos pelo RemoteLink"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("RemoteLink • transmissão autorizada")
            .setContentText("Sessão local protegida. Você pode desconectar imediatamente.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desconectar", pi).build())
            .build()
    }

    private fun showFileApprovalNotification(request: WebRtcHost.FileApprovalRequest) {
        if (!SessionCapabilities.canReceiveFiles()) {
            webRtcHost?.resolveFileApproval(request.id, false)
            return
        }
        val approveIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_FILE_APPROVE)
            .putExtra(EXTRA_TRANSFER_ID, request.id)
        val denyIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_FILE_DENY)
            .putExtra(EXTRA_TRANSFER_ID, request.id)
        val approve = PendingIntent.getService(
            this,
            2001,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deny = PendingIntent.getService(
            this,
            2002,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sizeMb = request.size.toDouble() / (1024.0 * 1024.0)
        val sizeText = if (request.size < 1024 * 1024) {
            "${request.size / 1024} KB"
        } else {
            "%.1f MB".format(sizeMb)
        }
        val notification = Notification.Builder(this, FILE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Receber arquivo pelo RemoteLink?")
            .setContentText("${request.name} • $sizeText")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Um navegador já pareado quer enviar '${request.name}' ($sizeText). " +
                        "Nenhum byte do arquivo será aceito até você permitir."
                )
            )
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(FILE_APPROVAL_TIMEOUT_MS)
            .addAction(Notification.Action.Builder(null, "Recusar", deny).build())
            .addAction(Notification.Action.Builder(null, "Permitir", approve).build())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(FILE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "RemoteLinkCapture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_TRANSFER_ID = "transferId"
        const val ACTION_STOP = "app.remotelink.STOP_CAPTURE"
        const val ACTION_FILE_APPROVE = "app.remotelink.FILE_APPROVE"
        const val ACTION_FILE_DENY = "app.remotelink.FILE_DENY"
        private const val CHANNEL_ID = "remotelink_capture"
        private const val FILE_CHANNEL_ID = "remotelink_file_approval"
        private const val NOTIFICATION_ID = 4101
        private const val FILE_NOTIFICATION_ID = 4102
        private const val FILE_APPROVAL_TIMEOUT_MS = 30_000L

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        fun intent(context: Context, resultCode: Int, data: Intent) =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
    }
}
