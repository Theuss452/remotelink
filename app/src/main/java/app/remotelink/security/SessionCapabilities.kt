package app.remotelink.security

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local capability policy controlled only from the Android UI.
 * Remote peers can observe capability changes, but can never grant themselves a capability.
 */
object SessionCapabilities {
    private val screen = AtomicBoolean(true)
    private val touch = AtomicBoolean(true)
    private val keyboard = AtomicBoolean(true)
    private val files = AtomicBoolean(false)

    fun canViewScreen(): Boolean = screen.get()
    fun canTouch(): Boolean = touch.get()
    fun canUseKeyboard(): Boolean = keyboard.get()
    fun canReceiveFiles(): Boolean = files.get()

    fun setScreen(enabled: Boolean) { screen.set(enabled) }
    fun setTouch(enabled: Boolean) { touch.set(enabled) }
    fun setKeyboard(enabled: Boolean) { keyboard.set(enabled) }
    fun setFiles(enabled: Boolean) { files.set(enabled) }

    fun snapshot(): Snapshot = Snapshot(
        screen = canViewScreen(),
        touch = canTouch(),
        keyboard = canUseKeyboard(),
        files = canReceiveFiles()
    )

    data class Snapshot(
        val screen: Boolean,
        val touch: Boolean,
        val keyboard: Boolean,
        val files: Boolean
    )
}
