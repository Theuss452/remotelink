package app.remotelink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import app.remotelink.capture.ScreenCaptureService
import app.remotelink.control.RemoteAccessibilityService
import app.remotelink.network.LanPolicy
import app.remotelink.network.LocalControlServer
import app.remotelink.security.PairingManager

class MainActivity : Activity() {
    private val pairing = PairingManager()
    private var server: LocalControlServer? = null
    private var captureManager: MediaProjectionManager? = null

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var codeText: TextView
    private lateinit var captureStatus: TextView
    private lateinit var serverButton: Button
    private lateinit var newCodeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        codeText = findViewById(R.id.codeText)
        captureStatus = findViewById(R.id.captureStatusText)
        serverButton = findViewById(R.id.serverButton)
        newCodeButton = findViewById(R.id.newCodeButton)
        captureManager = getSystemService(MediaProjectionManager::class.java)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        serverButton.setOnClickListener { if (server == null) startServer() else stopServer() }
        newCodeButton.setOnClickListener { refreshCode() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.captureButton).setOnClickListener {
            val intent = captureManager?.createScreenCaptureIntent() ?: return@setOnClickListener
            startActivityForResult(intent, REQUEST_CAPTURE)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun startServer() {
        val binding = LanPolicy.findWifiBinding(this)
        if (binding == null) {
            AlertDialog.Builder(this)
                .setTitle("Wi‑Fi privado necessário")
                .setMessage("Conecte o celular a uma rede Wi‑Fi com IPv4 privado. A v0.2 alpha não abre o servidor em dados móveis nem em uma interface com IPv4 público.")
                .setPositiveButton("OK", null).show()
            return
        }
        val s = LocalControlServer(this, binding, pairing) { request, finish ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Permitir conexão?")
                    .setMessage(
                        "Um navegador em ${request.remoteIp} informou o código correto.\n\n" +
                        "Se você aprovar, ele poderá iniciar uma sessão WebRTC. O controle só funcionará se a permissão de Acessibilidade também estiver ativada."
                    )
                    .setNegativeButton("Recusar") { _, _ -> finish(false) }
                    .setPositiveButton("Permitir") { _, _ -> finish(true) }
                    .setOnCancelListener { finish(false) }
                    .show()
            }
        }
        try {
            s.start(); server = s
            statusText.text = "● LAN ativa"
            addressText.text = "http://${binding.address.hostAddress}:${s.port}"
            serverButton.text = "Parar acesso local"
            newCodeButton.isEnabled = true
            refreshCode()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Falha ao iniciar").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show()
            s.stop()
        }
    }

    private fun stopServer() {
        server?.stop(); server = null
        pairing.invalidate()
        statusText.text = "● Desligado"
        addressText.text = "Endereço aparecerá aqui"
        codeText.text = "Código: —"
        serverButton.text = "Iniciar acesso local"
        newCodeButton.isEnabled = false
    }

    private fun refreshCode() {
        if (server == null) return
        val w = pairing.newCode()
        codeText.text = "Código: ${w.code.substring(0,3)} ${w.code.substring(3)}"
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
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 7001
        private const val REQUEST_NOTIFICATIONS = 7002
    }
}
