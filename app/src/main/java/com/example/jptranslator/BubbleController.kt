package com.example.jptranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * 화면 위에 떠 있는 동그란 번역 버튼.
 * - 탭: 지금 화면 번역 / 번역 지우기
 * - 길게 누르기: 실시간 모드 켜기·끄기
 * - 드래그: 위치 이동 (놓으면 가까운 가장자리로 붙음)
 */
@SuppressLint("ViewConstructor")
class BubbleController(
    private val context: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val size = (54 * density).toInt()
    private val view = BubbleView(context)

    /** 화면 좌표 기준 버튼 영역 (다른 스레드에서 읽음) */
    @Volatile var screenRect: Rect = Rect()
        private set

    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        val b = wm.maximumWindowMetrics.bounds
        params.x = b.width() - size - (8 * density).toInt()
        params.y = (b.height() * 0.4f).toInt()
        view.setOnTouchListener(TouchHandler())
        wm.addView(view, params)
        view.post { updateRect() }
    }

    fun remove() {
        try { wm.removeView(view) } catch (_: Exception) { }
    }

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun setBusy(busy: Boolean) { view.busy = busy; view.invalidate() }
    fun setLive(live: Boolean) { view.live = live; view.invalidate() }

    /** 회전 후 화면 밖으로 나가지 않게 */
    fun clampToScreen() {
        val b = wm.maximumWindowMetrics.bounds
        params.x = params.x.coerceIn(0, (b.width() - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (b.height() - size).coerceAtLeast(0))
        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
        view.post { updateRect() }
    }

    private fun updateRect() {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        screenRect = Rect(loc[0], loc[1], loc[0] + size, loc[1] + size)
    }

    private fun snapToEdge() {
        val b = wm.maximumWindowMetrics.bounds
        val margin = (8 * density).toInt()
        params.x = if (params.x + size / 2 < b.width() / 2) margin else b.width() - size - margin
        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
        view.post { updateRect(); onMoved() }
    }

    private inner class TouchHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPressRunnable = Runnable {
            if (!dragging) {
                longPressed = true
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    v.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop) && !longPressed) {
                        dragging = true
                        v.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    when {
                        dragging -> snapToEdge()
                        !longPressed -> { v.performClick(); onTap() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    if (dragging) snapToEdge()
                }
            }
            return true
        }
    }

    /** 원형 버튼 그리기: 기본=짙은 원 + "訳", 실시간=초록 테두리, 처리 중=흐리게 */
    private class BubbleView(context: Context) : View(context) {
        var live = false
        var busy = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 30, 30, 34) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * context.resources.displayMetrics.density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - ring.strokeWidth
            fill.alpha = if (busy) 140 else 225
            canvas.drawCircle(cx, cy, r, fill)
            ring.color = if (live) Color.rgb(60, 200, 110) else Color.rgb(224, 83, 61)
            canvas.drawCircle(cx, cy, r, ring)
            text.textSize = width * 0.38f
            val label = if (busy) "…" else "訳"
            canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2, text)
        }
    }
}
