package app.remotelink.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RemoteAccessibilityService : AccessibilityService() {
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val dragLock = Any()
    private var dragGeneration = 0L
    private var dragState: DragState? = null
    private var multiTouchGeneration = 0L
    private var multiTouchState: MultiTouchState? = null

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

    private data class MultiTouchState(
        val generation: Long,
        var stroke1: GestureDescription.StrokeDescription,
        var stroke2: GestureDescription.StrokeDescription,
        var currentX1: Float,
        var currentY1: Float,
        var currentX2: Float,
        var currentY2: Float,
        var pendingX1: Float,
        var pendingY1: Float,
        var pendingX2: Float,
        var pendingY2: Float,
        var inFlight: Boolean,
        var ending: Boolean,
        var finalInFlight: Boolean
    )

    override fun onServiceConnected() {
        instance = this
    }

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

    private fun gestureBounds(): Rect {
        val root = rootInActiveWindow
        if (root != null) {
            val bounds = Rect()
            try {
                root.getBoundsInScreen(bounds)
            } catch (_: Exception) {
            }
            if (bounds.width() > 1 && bounds.height() > 1) return bounds
        }
        val size = realDisplaySize()
        return Rect(0, 0, size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
    }

    private fun normalizedToPixels(x: Float, y: Float): Pair<Float, Float> {
        val bounds = gestureBounds()
        val width = (bounds.width() - 1).coerceAtLeast(1)
        val height = (bounds.height() - 1).coerceAtLeast(1)
        return (bounds.left + x.coerceIn(0f, 1f) * width) to
            (bounds.top + y.coerceIn(0f, 1f) * height)
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
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs.coerceIn(70, 700)
        )
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            gestureHandler
        )
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
                if (!state.inFlight && !state.ending) {
                    dispatchNextDragSegmentLocked(state)
                }
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

    fun multiTouchStartNormalized(
        x1: Float, y1: Float, x2: Float, y2: Float
    ): Boolean {
        val p1 = normalizedToPixels(x1, y1)
        val p2 = normalizedToPixels(x2, y2)
        gestureHandler.post { startMultiTouchInternal(p1.first, p1.second, p2.first, p2.second) }
        return true
    }

    fun multiTouchMoveNormalized(
        x1: Float, y1: Float, x2: Float, y2: Float
    ): Boolean {
        val p1 = normalizedToPixels(x1, y1)
        val p2 = normalizedToPixels(x2, y2)
        gestureHandler.post {
            synchronized(dragLock) {
                val state = multiTouchState ?: return@post
                state.pendingX1 = p1.first
                state.pendingY1 = p1.second
                state.pendingX2 = p2.first
                state.pendingY2 = p2.second
                if (!state.inFlight && !state.ending) dispatchNextMultiTouchSegmentLocked(state)
            }
        }
        return true
    }

    fun multiTouchEndNormalized(
        x1: Float, y1: Float, x2: Float, y2: Float
    ): Boolean {
        val p1 = normalizedToPixels(x1, y1)
        val p2 = normalizedToPixels(x2, y2)
        gestureHandler.post {
            synchronized(dragLock) {
                val state = multiTouchState ?: return@post
                state.pendingX1 = p1.first
                state.pendingY1 = p1.second
                state.pendingX2 = p2.first
                state.pendingY2 = p2.second
                state.ending = true
                if (!state.inFlight) dispatchNextMultiTouchSegmentLocked(state)
            }
        }
        return true
    }

    fun pinchNormalized(centerX: Float, centerY: Float, scale: Float): Boolean {
        val safeScale = scale.coerceIn(0.5f, 2.0f)
        val outer = (0.15f + abs(safeScale - 1f) * 0.08f).coerceIn(0.14f, 0.22f)
        val inner = 0.055f
        val zoomIn = safeScale >= 1f
        val startSpan = if (zoomIn) inner else outer
        val endSpan = if (zoomIn) outer else inner

        val sx1 = (centerX - startSpan).coerceIn(0.02f, 0.98f)
        val sx2 = (centerX + startSpan).coerceIn(0.02f, 0.98f)
        val ex1 = (centerX - endSpan).coerceIn(0.02f, 0.98f)
        val ex2 = (centerX + endSpan).coerceIn(0.02f, 0.98f)
        val y = centerY.coerceIn(0.03f, 0.97f)

        val s1 = normalizedToPixels(sx1, y)
        val s2 = normalizedToPixels(sx2, y)
        val e1 = normalizedToPixels(ex1, y)
        val e2 = normalizedToPixels(ex2, y)

        gestureHandler.post {
            synchronized(dragLock) {
                if (dragState != null || multiTouchState != null) return@post
                val path1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
                val path2 = Path().apply { moveTo(s2.first, s2.second); lineTo(e2.first, e2.second) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path1, 0, 130))
                    .addStroke(GestureDescription.StrokeDescription(path2, 0, 130))
                    .build()
                dispatchGesture(gesture, null, gestureHandler)
            }
        }
        return true
    }

    fun cancelRemoteDrag() {
        gestureHandler.post {
            synchronized(dragLock) {
                dragState?.let { state ->
                    state.pendingX = state.currentX
                    state.pendingY = state.currentY
                    state.ending = true
                    if (!state.inFlight) dispatchNextDragSegmentLocked(state)
                }
                multiTouchState?.let { state ->
                    state.pendingX1 = state.currentX1
                    state.pendingY1 = state.currentY1
                    state.pendingX2 = state.currentX2
                    state.pendingY2 = state.currentY2
                    state.ending = true
                    if (!state.inFlight) dispatchNextMultiTouchSegmentLocked(state)
                }
            }
        }
    }

    private fun startDragInternal(px: Float, py: Float) {
        synchronized(dragLock) {
            if (multiTouchState != null) {
                multiTouchState?.ending = true
                multiTouchState?.let { if (!it.inFlight) dispatchNextMultiTouchSegmentLocked(it) }
                gestureHandler.postDelayed({ startDragInternal(px, py) }, 90)
                return
            }
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
                generation, stroke, px, py, px, py, px, py,
                inFlight = true, ending = false, finalInFlight = false
            )
            dragState = state
            dispatchDragStrokeLocked(state, stroke, px, py, false)
        }
    }

    private fun startMultiTouchInternal(x1: Float, y1: Float, x2: Float, y2: Float) {
        synchronized(dragLock) {
            if (dragState != null) {
                dragState?.ending = true
                dragState?.let { if (!it.inFlight) dispatchNextDragSegmentLocked(it) }
                gestureHandler.postDelayed({ startMultiTouchInternal(x1, y1, x2, y2) }, 95)
                return
            }
            if (multiTouchState != null) {
                multiTouchState?.ending = true
                multiTouchState?.let { if (!it.inFlight) dispatchNextMultiTouchSegmentLocked(it) }
                gestureHandler.postDelayed({ startMultiTouchInternal(x1, y1, x2, y2) }, 95)
                return
            }

            val generation = ++multiTouchGeneration
            val path1 = Path().apply { moveTo(x1, y1) }
            val path2 = Path().apply { moveTo(x2, y2) }
            val stroke1 = GestureDescription.StrokeDescription(path1, 0, 55, true)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, 55, true)
            val state = MultiTouchState(
                generation, stroke1, stroke2,
                x1, y1, x2, y2,
                x1, y1, x2, y2,
                inFlight = true, ending = false, finalInFlight = false
            )
            multiTouchState = state
            dispatchMultiTouchStrokeLocked(state, stroke1, stroke2, x1, y1, x2, y2, false)
        }
    }

    private fun dispatchNextDragSegmentLocked(state: DragState) {
        if (state.inFlight || dragState?.generation != state.generation) return

        val targetX = state.pendingX
        val targetY = state.pendingY
        val final = state.ending
        val distance = hypot((targetX - state.currentX).toDouble(), (targetY - state.currentY).toDouble())
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

    private fun dispatchNextMultiTouchSegmentLocked(state: MultiTouchState) {
        if (state.inFlight || multiTouchState?.generation != state.generation) return

        val d1 = hypot((state.pendingX1 - state.currentX1).toDouble(), (state.pendingY1 - state.currentY1).toDouble())
        val d2 = hypot((state.pendingX2 - state.currentX2).toDouble(), (state.pendingY2 - state.currentY2).toDouble())
        val distance = max(d1, d2)
        val duration = if (distance < 1.0) 35L else (35L + (distance / 18.0).toLong()).coerceIn(35L, 90L)
        val final = state.ending

        val path1 = Path().apply {
            moveTo(state.currentX1, state.currentY1)
            if (d1 >= 1.0) lineTo(state.pendingX1, state.pendingY1)
        }
        val path2 = Path().apply {
            moveTo(state.currentX2, state.currentY2)
            if (d2 >= 1.0) lineTo(state.pendingX2, state.pendingY2)
        }

        val next1 = try { state.stroke1.continueStroke(path1, 0, duration, !final) }
        catch (_: Exception) { multiTouchState = null; return }
        val next2 = try { state.stroke2.continueStroke(path2, 0, duration, !final) }
        catch (_: Exception) { multiTouchState = null; return }

        state.stroke1 = next1
        state.stroke2 = next2
        state.inFlight = true
        state.finalInFlight = final
        dispatchMultiTouchStrokeLocked(
            state, next1, next2,
            state.pendingX1, state.pendingY1,
            state.pendingX2, state.pendingY2,
            final
        )
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
                                hypot((active.pendingX - active.currentX).toDouble(), (active.pendingY - active.currentY).toDouble()) >= 1.0
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

    private fun dispatchMultiTouchStrokeLocked(
        state: MultiTouchState,
        stroke1: GestureDescription.StrokeDescription,
        stroke2: GestureDescription.StrokeDescription,
        targetX1: Float,
        targetY1: Float,
        targetX2: Float,
        targetY2: Float,
        final: Boolean
    ) {
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureHandler.post {
                        synchronized(dragLock) {
                            val active = multiTouchState
                            if (active == null || active.generation != state.generation) return@synchronized
                            active.currentX1 = targetX1
                            active.currentY1 = targetY1
                            active.currentX2 = targetX2
                            active.currentY2 = targetY2
                            active.inFlight = false

                            val remaining = max(
                                hypot((active.pendingX1 - active.currentX1).toDouble(), (active.pendingY1 - active.currentY1).toDouble()),
                                hypot((active.pendingX2 - active.currentX2).toDouble(), (active.pendingY2 - active.currentY2).toDouble())
                            )
                            if (final || active.finalInFlight) {
                                multiTouchState = null
                            } else if (active.ending || remaining >= 1.0) {
                                dispatchNextMultiTouchSegmentLocked(active)
                            }
                        }
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureHandler.post {
                        synchronized(dragLock) {
                            if (multiTouchState?.generation == state.generation) multiTouchState = null
                        }
                    }
                }
            },
            gestureHandler
        )

        if (!accepted && multiTouchState?.generation == state.generation) multiTouchState = null
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

        val current = editableText(node)
        val (start, end) = selectionRange(node, current.length)
        val updated = (current.substring(0, start) + text + current.substring(end)).take(MAX_TEXT_LENGTH)
        val cursor = min(start + text.length, updated.length)
        return replaceText(node, updated, cursor)
    }

    fun deleteFocusedText(backward: Boolean): Boolean {
        val node = focusedEditable() ?: return false
        if (node.isPassword) return false

        val current = editableText(node)
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

        return replaceText(node, current.removeRange(from, to), from)
    }

    fun moveFocusedCursor(action: String): Boolean {
        val node = focusedEditable() ?: return false
        val current = editableText(node)
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
        val length = editableText(node).length
        return setSelection(node, 0, length)
    }

    fun pressFocusedEnter(): Boolean {
        val node = focusedEditable() ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (node.actionList.any { it.id == imeEnter.id } && node.performAction(imeEnter.id)) return true
        }
        val multiline = node.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return if (multiline) insertFocusedText("\n") else false
    }

    private fun editableText(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString().orEmpty()
        if (node.isShowingHintText) return ""

        val hint = node.hintText?.toString().orEmpty()
        if (raw.isNotEmpty() && hint.isNotEmpty() && raw == hint) {
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start <= 0 && end <= 0) return ""
        }
        return raw
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
        if (ok) {
            val safeCursor = cursor.coerceIn(0, value.length)
            setSelection(node, safeCursor, safeCursor)
        }
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

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }
}
