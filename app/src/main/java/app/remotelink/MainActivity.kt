package app.remotelink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import app.remotelink.capture.ScreenCaptureService
import app.remotelink.control.RemoteAccessibilityService
import app.remotelink.network.LanPolicy
import app.remotelink.network.LocalControlServer
import app.remotelink.security.PairingManager
import app.remotelink.update.UpdateManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private val pairing = PairingManager()
    private var server: LocalControlServer? = null
    private var serverBinding: LanPolicy.WifiBinding? = null
    private var captureManager: MediaProjectionManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var updateManager: UpdateManager? = null
    private var pendingUpdate: UpdateManager.DownloadResult.Ready? = null
    private var updateCheckRunning = false

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var codeText: TextView
    private lateinit var captureStatus: TextView
    private lateinit var serverButton: Button
    private lateinit var newCodeButton: Button
    private lateinit var updateTitle: TextView
    private lateinit var updateStatus: TextView
    private lateinit var checkUpdateButton: Button
    private lateinit var autoUpdateSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        codeText = findViewById(R.id.codeText)
        captureStatus = findViewById(R.id.captureStatusText)
        serverButton = findViewById(R.id.serverButton)
        newCodeButton = findViewById(R.id.newCodeButton)
        updateTitle = findViewById(R.id.updateTitle)
        updateStatus = findViewById(R.id.updateStatusText)
        checkUpdateButton = findViewById(R.id.checkUpdateButton)
        autoUpdateSwitch = findViewById(R.id.autoUpdateSwitch)
        captureManager = getSystemService(MediaProjectionManager::class.java)
        updateManager = UpdateManager(applicationContext)

        findViewById<TextView>(R.id.versionSubtitle).text =
            "Controle Android pelo navegador • v${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.footerText).text =
            "LAN-only • QR/HMAC/SAS • uma sessão por vez • v${BuildConfig.VERSION_NAME}"

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        serverButton.setOnClickListener { if (server == null) startServer() else stopServer() }
        newCodeButton.setOnClickListener { refreshCode() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { showAccessibilityDisclosure() }
        findViewById<Button>(R.id.captureButton).setOnClickListener {
            val intent = captureManager?.createScreenCaptureIntent() ?: return@setOnClickListener
            startActivityForResult(intent, REQUEST_CAPTURE)
        }

        configureUpdates()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        verifyActiveNetwork()
        val ready = pendingUpdate
        if (ready != null && updateManager?.canRequestInstallPackages() == true) {
            pendingUpdate = null
            showInstallConfirmation(ready)
        }
    }

    private fun configureUpdates() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val directConfigured = BuildConfig.DIRECT_UPDATES && BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()

        if (!BuildConfig.DIRECT_UPDATES) {
            updateTitle.text = "Atualizações pelo Google Play"
            updateStatus.text = "Esta distribuição usa o canal oficial do Google Play para verificar e instalar atualizações."
            checkUpdateButton.visibility = View.GONE
            autoUpdateSwitch.visibility = View.GONE
            return
        }

        autoUpdateSwitch.isChecked = prefs.getBoolean(PREF_AUTO_UPDATE, true)
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_UPDATE, checked).apply()
        }
        checkUpdateButton.setOnClickListener { checkForUpdates(userInitiated = true) }

        if (!directConfigured) {
            updateTitle.text = "Canal direto ainda não configurado"
            updateStatus.text = "A build suporta atualização assinada, mas precisa de um endpoint HTTPS de releases configurado na compilação."
            checkUpdateButton.isEnabled = false
            autoUpdateSwitch.isEnabled = false
            return
        }

        updateTitle.text = "Canal direto protegido"
        updateStatus.text = "HTTPS + SHA‑256 + conferência do certificado da APK antes da instalação."
        val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
        if (autoUpdateSwitch.isChecked && System.currentTimeMillis() - lastCheck >= AUTO_CHECK_INTERVAL_MS) {
            checkForUpdates(userInitiated = false)
        }
    }

    private fun checkForUpdates(userInitiated: Boolean) {
        if (updateCheckRunning) return
        val manager = updateManager ?: return
        updateCheckRunning = true
        checkUpdateButton.isEnabled = false
        updateTitle.text = "Verificando atualização…"
        updateStatus.text = "Conectando ao canal HTTPS configurado."

        manager.check { result ->
            updateCheckRunning = false
            checkUpdateButton.isEnabled = true
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()

            when (result) {
                UpdateManager.CheckResult.Disabled -> {
                    updateTitle.text = "Atualizador indisponível"
                    updateStatus.text = "O canal de atualização direta não está configurado nesta build."
                }
                UpdateManager.CheckResult.UpToDate -> {
                    updateTitle.text = "RemoteLink atualizado"
                    updateStatus.text = "Você já está usando a versão mais recente do canal configurado."
                    if (userInitiated) showSimpleDialog("Sem atualização", "O RemoteLink já está atualizado.")
                }
                is UpdateManager.CheckResult.Error -> {
                    updateTitle.text = "Não foi possível verificar"
                    updateStatus.text = humanUpdateError(result.message)
                    if (userInitiated) showSimpleDialog("Falha ao verificar", humanUpdateError(result.message))
                }
                is UpdateManager.CheckResult.Available -> {
                    updateTitle.text = "Nova versão: ${result.info.versionName}"
                    updateStatus.text = result.info.notes.ifBlank { "Atualização assinada disponível para download." }
                    val auto = autoUpdateSwitch.isChecked && !userInitiated
                    if (auto) downloadUpdate(result.info, automatic = true)
                    else showUpdateAvailable(result.info)
                }
            }
        }
    }

    private fun showUpdateAvailable(info: UpdateManager.UpdateInfo) {
        val message = buildString {
            append("Versão ${info.versionName} está disponível.\n\n")
            if (info.notes.isNotBlank()) append(info.notes).append("\n\n")
            append("A APK será aceita somente se o SHA‑256 e o certificado de assinatura forem válidos.")
        }
        AlertDialog.Builder(this)
            .setTitle("Atualização disponível")
            .setMessage(message)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Baixar") { _, _ -> downloadUpdate(info, automatic = false) }
            .show()
    }

    private fun downloadUpdate(info: UpdateManager.UpdateInfo, automatic: Boolean) {
        val manager = updateManager ?: return
        updateTitle.text = "Baixando ${info.versionName}…"
        updateStatus.text = "O arquivo será validado antes de abrir o instalador do Android."
        checkUpdateButton.isEnabled = false
        manager.download(info) { result ->
            checkUpdateButton.isEnabled = true
            when (result) {
                is UpdateManager.DownloadResult.Error -> {
                    updateTitle.text = "Download recusado ou falhou"
                    updateStatus.text = humanUpdateError(result.message)
                    if (!automatic) showSimpleDialog("Atualização não instalada", humanUpdateError(result.message))
                }
                is UpdateManager.DownloadResult.Ready -> {
                    updateTitle.text = "Atualização verificada"
                    updateStatus.text = "SHA‑256, pacote, versão e assinatura conferidos. Falta apenas a confirmação do Android."
                    handleVerifiedUpdate(result)
                }
            }
        }
    }

    private fun handleVerifiedUpdate(ready: UpdateManager.DownloadResult.Ready) {
        val manager = updateManager ?: return
        if (manager.canRequestInstallPackages()) {
            showInstallConfirmation(ready)
        } else {
            pendingUpdate = ready
            AlertDialog.Builder(this)
                .setTitle("Permitir instalação de atualização")
                .setMessage(
                    "O Android exige que você permita ao RemoteLink abrir APKs de atualização deste canal. " +
                        "Essa permissão é usada somente depois que a nova APK passa pelas verificações de assinatura e SHA‑256."
                )
                .setNegativeButton("Cancelar") { _, _ -> pendingUpdate = null }
                .setPositiveButton("Abrir configuração") { _, _ -> manager.openUnknownSourcesSettings() }
                .show()
        }
    }

    private fun showInstallConfirmation(ready: UpdateManager.DownloadResult.Ready) {
        AlertDialog.Builder(this)
            .setTitle("Instalar ${ready.info.versionName}?")
            .setMessage(
                "A atualização foi baixada e verificada. O instalador oficial do Android será aberto; " +
                    "confirme a instalação na próxima tela."
            )
            .setNegativeButton("Depois", null)
            .setPositiveButton("Abrir instalador") { _, _ ->
                try { updateManager?.install(ready.file) }
                catch (e: Exception) { showSimpleDialog("Falha ao abrir instalador", e.message ?: "Erro desconhecido") }
            }
            .show()
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Controle remoto por Acessibilidade")
            .setMessage(
                "Ao ativar o serviço de Acessibilidade do RemoteLink, um navegador que você parear e aprovar fisicamente poderá " +
                    "executar toques, arrastes e ações de navegação no Android.\n\n" +
                    "Para permitir o teclado remoto, o serviço pode ler o conteúdo do campo editável que estiver focado somente para " +
                    "inserir, apagar e mover o cursor. Esse conteúdo não é enviado a um servidor externo pelo RemoteLink.\n\n" +
                    "O controle só deve ser ativado quando você pretende usar acesso remoto e pode ser desativado nas configurações do Android a qualquer momento."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entendi e continuar") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun humanUpdateError(raw: String): String = when (raw) {
        "https_required" -> "O canal de atualização não usa HTTPS e foi bloqueado."
        "sha256_mismatch" -> "O arquivo baixado não corresponde ao SHA‑256 publicado e foi descartado."
        "signer_mismatch" -> "A APK não foi assinada pela mesma chave do RemoteLink instalado e foi bloqueada."
        "package_mismatch" -> "A APK pertence a outro pacote e foi bloqueada."
        "version_mismatch" -> "A versão da APK não corresponde ao manifesto de atualização."
        "invalid_apk" -> "O arquivo recebido não é uma APK Android válida."
        "apk_too_large" -> "A APK excede o limite de segurança do atualizador."
        else -> "Falha no canal de atualização: ${raw.take(160)}"
    }

    private fun showSimpleDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun startServer() {
        val binding = LanPolicy.findWifiBinding(this)
        if (binding == null) {
            AlertDialog.Builder(this)
                .setTitle("Wi‑Fi privado necessário")
                .setMessage("Conecte o celular a uma rede Wi‑Fi com IPv4 privado. O RemoteLink não abre o servidor em dados móveis nem em uma interface com IPv4 público.")
                .setPositiveButton("OK", null).show()
            return
        }
        val s = LocalControlServer(this, binding, pairing) { request, finish ->
            runOnUiThread {
                val message = if (request.strongPairing && request.sas != null) {
                    val sas = request.sas
                    val pretty = "${sas.substring(0, 3)} ${sas.substring(3)}"
                    "Pareamento forte solicitado por ${request.remoteIp}.\n\n" +
                        "SAS: $pretty\n\n" +
                        "CONFIRA se estes mesmos 6 dígitos aparecem no navegador. Se forem diferentes, recuse.\n\n" +
                        "Ao aprovar, o navegador poderá iniciar uma sessão WebRTC; o controle ainda depende da Acessibilidade."
                } else {
                    "Um navegador em ${request.remoteIp} informou o código temporário correto.\n\n" +
                        "Este é o modo fallback sem SAS. Se você aprovar, ele poderá iniciar uma sessão WebRTC."
                }
                AlertDialog.Builder(this)
                    .setTitle(if (request.strongPairing) "Comparar SAS e permitir?" else "Permitir conexão?")
                    .setMessage(message)
                    .setNegativeButton("Recusar") { _, _ -> finish(false) }
                    .setPositiveButton("SAS confere • Permitir") { _, _ -> finish(true) }
                    .setOnCancelListener { finish(false) }
                    .show()
            }
        }
        try {
            s.start(); server = s; serverBinding = binding; registerNetworkGuard()
            statusText.text = "● LAN ativa"
            addressText.text = "http://${binding.address.hostAddress}:${s.port}"
            serverButton.text = "Parar acesso local"
            newCodeButton.isEnabled = true
            refreshCode()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Falha ao iniciar").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show()
            s.stop(); serverBinding = null; unregisterNetworkGuard()
        }
    }

    private fun stopServer(reason: String? = null) {
        server?.stop(); server = null; serverBinding = null
        unregisterNetworkGuard(); pairing.invalidate()
        statusText.text = "● Desligado"
        addressText.text = "Endereço aparecerá aqui"
        codeText.text = "Código: —"
        codeText.setCompoundDrawables(null, null, null, null)
        serverButton.text = "Iniciar acesso local"
        newCodeButton.isEnabled = false
        if (reason != null && !isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("Acesso local encerrado")
                .setMessage(reason)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun registerNetworkGuard() {
        unregisterNetworkGuard()
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = verifyActiveNetworkAsync()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = verifyActiveNetworkAsync()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = verifyActiveNetworkAsync()
        }
        networkCallback = callback
        try { cm.registerDefaultNetworkCallback(callback) }
        catch (_: Exception) { networkCallback = null }
    }

    private fun unregisterNetworkGuard() {
        val callback = networkCallback ?: return
        networkCallback = null
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    private fun verifyActiveNetworkAsync() {
        runOnUiThread { verifyActiveNetwork() }
    }

    private fun verifyActiveNetwork() {
        val expected = serverBinding ?: return
        if (server == null) return
        val current = LanPolicy.findWifiBinding(this)
        val unchanged = current != null &&
            current.address.hostAddress == expected.address.hostAddress &&
            current.prefixLength == expected.prefixLength
        if (!unchanged) {
            stopServer("A rede Wi‑Fi ou o endereço IP do celular mudou. Por segurança, a sessão foi revogada. Inicie o acesso novamente na rede atual.")
        }
    }

    private fun refreshCode() {
        val activeServer = server ?: return
        val binding = serverBinding ?: return
        val w = pairing.newCode()
        val baseUrl = "http://${binding.address.hostAddress}:${activeServer.port}/"
        val strongUrl = "${baseUrl}#pair=${w.strongPairId}.${w.strongSecretB64}"
        codeText.text = "Pareamento forte por QR • válido por 5 min\nCódigo fallback: ${w.code.substring(0,3)} ${w.code.substring(3)}"
        try {
            val size = (240 * resources.displayMetrics.density).toInt().coerceIn(480, 900)
            val matrix = QRCodeWriter().encode(strongUrl, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) {
                for (x in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            val drawable = BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, size, size) }
            codeText.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
            codeText.setCompoundDrawables(null, drawable, null, null)
        } catch (_: Exception) {
            codeText.setCompoundDrawables(null, null, null, null)
            codeText.append("\nQR indisponível; use o código fallback.")
        }
    }

    private fun updatePermissionState() {
        val capture = ScreenCaptureService.instance?.isReady() == true
        val accessibility = RemoteAccessibilityService.instance != null
        captureStatus.text = when {
            capture && accessibility -> "Pronto: transmissão + controle autorizados"
            capture -> "Transmissão autorizada • controle ainda desativado"
            accessibility -> "Controle autorizado • falta ativar transmissão"
            else -> "Ative transmissão e controle para usar a sessão completa"
        }
    }

    @Deprecated("startActivityForResult remains sufficient for this alpha")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(ScreenCaptureService.intent(this, resultCode, data))
            captureStatus.text = "Transmissão autorizada • WebRTC pronto para parear"
        } else captureStatus.text = "Transmissão recusada"
    }

    override fun onDestroy() {
        stopServer()
        updateManager?.close()
        updateManager = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 7001
        private const val REQUEST_NOTIFICATIONS = 7002
        private const val PREFS_NAME = "remotelink_preferences"
        private const val PREF_AUTO_UPDATE = "auto_update_download"
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check"
        private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
