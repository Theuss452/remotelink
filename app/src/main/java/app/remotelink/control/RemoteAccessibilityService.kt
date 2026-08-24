package app.remotelink.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun tapNormalized(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        val path = Path().apply { moveTo(x.coerceIn(0f, 1f) * dm.widthPixels, y.coerceIn(0f, 1f) * dm.heightPixels) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null, null
        )
    }

    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1.coerceIn(0f,1f) * dm.widthPixels, y1.coerceIn(0f,1f) * dm.heightPixels)
            lineTo(x2.coerceIn(0f,1f) * dm.widthPixels, y2.coerceIn(0f,1f) * dm.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80, 1500))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun setFocusedText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.take(4000))
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
    }
}
