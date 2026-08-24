package app.remotelink.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /**
     * MediaProjection captures the complete physical display, including the
     * portions behind system bars. resources.displayMetrics may describe only
     * the app's usable area on some devices, which caused Y coordinates sent
     * from the browser to land progressively above the intended target.
     */
    private fun realDisplaySize(): Point {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Point(metrics.widthPixels, metrics.heightPixels)
            }
        }
        val dm = resources.displayMetrics
        return Point(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
    }

    fun tapNormalized(x: Float, y: Float): Boolean {
        val size = realDisplaySize()
        val px = x.coerceIn(0f, 1f) * (size.x - 1).coerceAtLeast(1)
        val py = y.coerceIn(0f, 1f) * (size.y - 1).coerceAtLeast(1)
        val path = Path().apply { moveTo(px, py) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 38))
                .build(),
            null,
            null
        )
    }

    fun swipeNormalized(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 220
    ): Boolean {
        val size = realDisplaySize()
        val width = (size.x - 1).coerceAtLeast(1)
        val height = (size.y - 1).coerceAtLeast(1)
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, 1f) * width, y1.coerceIn(0f, 1f) * height)
            lineTo(x2.coerceIn(0f, 1f) * width, y2.coerceIn(0f, 1f) * height)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(70, 700))
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
