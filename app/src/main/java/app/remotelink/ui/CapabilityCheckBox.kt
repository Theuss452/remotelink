package app.remotelink.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.CheckBox
import app.remotelink.capture.ScreenCaptureService
import app.remotelink.security.SessionCapabilities

class CapabilityCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.checkboxStyle
) : CheckBox(context, attrs, defStyleAttr) {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnCheckedChangeListener(null)
        val capability = tag?.toString().orEmpty()
        isChecked = when (capability) {
            "screen" -> SessionCapabilities.canViewScreen()
            "touch" -> SessionCapabilities.canTouch()
            "keyboard" -> SessionCapabilities.canUseKeyboard()
            "files" -> SessionCapabilities.canReceiveFiles()
            else -> false
        }
        setOnCheckedChangeListener { _, enabled ->
            when (capability) {
                "screen" -> {
                    SessionCapabilities.setScreen(enabled)
                    ScreenCaptureService.instance?.applyCapabilities()
                }
                "touch" -> SessionCapabilities.setTouch(enabled)
                "keyboard" -> SessionCapabilities.setKeyboard(enabled)
                "files" -> {
                    SessionCapabilities.setFiles(enabled)
                    if (!enabled) ScreenCaptureService.instance?.cancelPendingFileTransfer()
                }
            }
        }
    }
}
