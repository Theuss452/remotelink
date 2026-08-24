package app.remotelink.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RemoteAccessibilityService : AccessibilityService() {
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val dragLock = Any()
    private var dragGeneration = 0L
    private var dragState: DragState? = null

    private data class DragState(
        val generation: Long,
        var stroke: GestureDescription.StrokeDescription,
        var currentX: Float,
        var currentY: Float,
        var pendingX: Float,
        var pendingY: Float,
        var flightX: Float,
        var flightY: Float,
        var inFlight: Boolean,
        var ending: Boolean,
        var finalInFlight: Boolean
    )

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelRemoteDrag()
        if (instance === this) instance = null
        super.onDestroy()
    }

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

    private fun normalizedToPixels(x: Float, y: Float): Pair<Float, Float> {
        val size = realDisplaySize()
        val width = (size.x - 1).coerceAtLeast(1)
        val height = (size.y - 1).coerceAtLeast(1)
        return (x.coerceIn(0f, 1f) * width) to (y.coerceIn(0f, 1f) * height)
    }

    fun tapNormalized(x: Float, y: Float): Boolean {
        val (px, py) = normalizedToPixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 38))
                .build(),
            null,
            gestureHandler
        )
    }

    fun swipeNormalized(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 220
    ): Boolean {
        val (px1, py1) = normalizedToPixels(x1, y1)
        val (px2, py2) = normalizedToPixels(x2, y2)
        val path = Path().apply {
            moveTo(px1, py1)
            lineTo(px2, py2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(70, 700))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, gestureHandler)
    }

    fun dragStartNormalized(x: Float, y: Float): Boolean {
        val (px, py) = normalizedToPixels(x, y)
        gestureHandler.post { startDragInternal(px, py) }
        return true
    }

    fun dragMoveNormalized(x: Float, y: Float): Boolean {
        val (px, py) = normalizedToPixels(x, y)
        gestureHandler.post {
            synchronized(dragLock) {
                val state = dragState ?: return@post
                state.pendingX = px
                state.pendingY = py
                if (!state.inFlight && !state.ending) dispatchNextDragSegmentLocked(state)
            }
        }
        return true
    }

    fun dragEndNormalized(x: Float, y: Float): Boolean {
        val (px, py) = normalizedToPixels(x, y)
        gestureHandler.post {
            synchronized(dragLock) {
                val state = dragState ?: return@post
                state.pendingX = px
                state.pendingY = py
                state.ending = true
                if (!state.inFlight) dispatchNextDragSegmentLocked(state)
            }
        }
        return true
    }

    fun cancelRemoteDrag() {
        gestureHandler.post {
            synchronized(dragLock) {
                val state = dragState ?: return@post
                state.pendingX = state.currentX
                state.pendingY = state.currentY
                state.ending = true
                if (!state.inFlight) dispatchNextDragSegmentLocked(state)
            }
        }
    }

    private fun startDragInternal(px: Float, py: Float) {
        synchronized(dragLock) {
            if (dragState != null) {
                dragState?.ending = true
                dragState?.let { if (!it.inFlight) dispatchNextDragSegmentLocked(it) }
                gestureHandler.postDelayed({ startDragInternal(px, py) }, 90)
                return
            }

            val generation = ++dragGeneration
            val path = Path().apply { moveTo(px, py) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 55, true)
            val state = DragState(
                generation = generation,
                stroke = stroke,
                currentX = px,
                currentY = py,
                pendingX = px,
                pendingY = py,
                flightX = px,
                flightY = py,
                inFlight = true,
                ending = false,
                finalInFlight = false
            )
            dragState = state
            dispatchDragStrokeLocked(state, stroke, px, py, final = false)
        }
    }

    private fun dispatchNextDragSegmentLocked(state: DragState) {
        if (state.inFlight || dragState?.generation != state.generation) return

        val targetX = state.pendingX
        val targetY = state.pendingY
        val final = state.ending
        val distance = hypot(
            (targetX - state.currentX).toDouble(),
            (targetY - state.currentY).toDouble()
        )
        val duration = if (distance < 1.0) 35L else (35L + (distance / 18.0).toLong()).coerceIn(35L, 95L)

        val path = Path().apply {
            moveTo(state.currentX, state.currentY)
            if (distance >= 1.0) lineTo(targetX, targetY)
        }
        val nextStroke = try {
            state.stroke.continueStroke(path, 0, duration, !final)
        } catch (_: Exception) {
            dragState = null
            return
        }

        state.stroke = nextStroke
        state.inFlight = true
        state.finalInFlight = final
        state.flightX = targetX
        state.flightY = targetY
        dispatchDragStrokeLocked(state, nextStroke, targetX, targetY, final)
    }

    private fun dispatchDragStrokeLocked(
        state: DragState,
        stroke: GestureDescription.StrokeDescription,
        targetX: Float,
        targetY: Float,
        final: Boolean
    ) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureHandler.post {
                        synchronized(dragLock) {
                            val active = dragState
                            if (active == null || active.generation != state.generation) return@synchronized
                            active.currentX = targetX
                            active.currentY = targetY
                            active.inFlight = false
                            if (final || active.finalInFlight) {
                                dragState = null
                            } else if (
                                active.ending ||
                                hypot(
                                    (active.pendingX - active.currentX).toDouble(),
                                    (active.pendingY - active.currentY).toDouble()
                                ) >= 1.0
                            ) {
                                dispatchNextDragSegmentLocked(active)
                            }
                        }
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureHandler.post {
                        synchronized(dragLock) {
                            if (dragState?.generation == state.generation) dragState = null
                        }
                    }
                }
            },
            gestureHandler
        )
        if (!accepted && dragState?.generation == state.generation) dragState = null
    }

    fun setFocusedText(text: String): Boolean {
        val focused = focusedEditable() ?: return false
        val value = text.take(MAX_TEXT_LENGTH)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) setSelection(focused, value.length, value.length)
        return ok
    }

    fun insertFocusedText(text: String): Boolean {
        if (text.isEmpty() || text.length > 16) return false
        val node = focusedEditable() ?: return false
        if (node.isPassword) return false
        val current = node.text?.toString().orEmpty()
        val (start, end) = selectionRange(node, current.length)
        val updated = (current.substring(0, start) + text + current.substring(end))
            .take(MAX_TEXT_LENGTH)
        val cursor = min(start + text.length, updated.length)
        return replaceText(node, updated, cursor)
    }

    fun deleteFocusedText(backward: Boolean): Boolean {
        val node = focusedEditable() ?: return false
        if (node.isPassword) return false
        val current = node.text?.toString().orEmpty()
        if (current.isEmpty()) return true
        val (start, end) = selectionRange(node, current.length)

        val from: Int
        val to: Int
        if (start != end) {
            from = start
            to = end
        } else if (backward && start > 0) {
            from = current.offsetByCodePoints(start, -1)
            to = start
        } else if (!backward && end < current.length) {
            from = end
            to = current.offsetByCodePoints(end, 1)
        } else {
            return true
        }

        val updated = current.removeRange(from, to)
        return replaceText(node, updated, from)
    }

    fun moveFocusedCursor(action: String): Boolean {
        val node = focusedEditable() ?: return false
        val current = node.text?.toString().orEmpty()
        val (start, end) = selectionRange(node, current.length)
        val cursor = when (action) {
            "left" -> if (start != end) start else if (start > 0) current.offsetByCodePoints(start, -1) else 0
            "right" -> if (start != end) end else if (end < current.length) current.offsetByCodePoints(end, 1) else current.length
            "home" -> 0
            "end" -> current.length
            else -> return false
        }
        return setSelection(node, cursor, cursor)
    }

    fun selectAllFocusedText(): Boolean {
        val node = focusedEditable() ?: return false
        val length = node.text?.length ?: 0
        return setSelection(node, 0, length)
    }

    fun pressFocusedEnter(): Boolean {
        val node = focusedEditable() ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (node.actionList.any { it.id == imeEnter.id } && node.performAction(imeEnter.id)) {
                return true
            }
        }
        val multiline = node.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return if (multiline) insertFocusedText("\n") else false
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return focused.takeIf { it.isEditable }
    }

    private fun selectionRange(node: AccessibilityNodeInfo, length: Int): Pair<Int, Int> {
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..length || end !in 0..length) {
            start = length
            end = length
        }
        return min(start, end) to max(start, end)
    }

    private fun replaceText(node: AccessibilityNodeInfo, value: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) setSelection(node, cursor.coerceIn(0, value.length), cursor.coerceIn(0, value.length))
        return ok
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        private const val MAX_TEXT_LENGTH = 4000
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
    }
}
