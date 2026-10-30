package com.example.jptranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 번역할 영역(게임 대사창 등)을 손가락으로 지정하는 전체화면 창.
 *  - 빈 곳 드래그: 새 사각형 그리기
 *  - 사각형 안 드래그: 이동
 *  - 모서리 드래그: 크기 조절
 *  - [이 영역 번역] / [취소] 버튼
 * 결과는 화면 절대좌표 Rect로 돌려준다.
 */
class RegionSelector(
    private val context: Context,
    private val wm: WindowManager,
    initial: Rect?,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val view = SelectView(context, initial)
    private var shown = false

    fun show() {
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, lp)
        shown = true
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        try { wm.removeView(view) } catch (_: Exception) { }
    }

    @SuppressLint("ViewConstructor")
    private inner class SelectView(ctx: Context, initialScreen: Rect?) : View(ctx) {

        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        /** 뷰 좌표 기준 선택 영역 (없으면 null) */
        private var sel: RectF? = null
        private var pendingInitial: Rect? = initialScreen
        private val loc = IntArray(2)

        private val dim = Paint().apply { color = Color.argb(130, 0, 0, 0) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.rgb(255, 196, 0)
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 0) }
        private val btnBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(15f); isFakeBoldText = true
        }
        private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(16f)
        }

        private val handleR = dp(9f)
        private val touchR = dp(30f)
        private val minSize = dp(40f)

        private val cancelBtn = RectF()
        private val okBtn = RectF()

        // 드래그 상태
        private var mode = 0          // 0 없음, 1 새로 그리기, 2 이동, 3 모서리
        private var corner = 0        // 0 좌상, 1 우상, 2 좌하, 3 우하
        private var downX = 0f
        private var downY = 0f
        private var startRect = RectF()
        private var pressedBtn = 0    // 1 취소, 2 확인
        private var drew = false

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val init = pendingInitial ?: return
            pendingInitial = null
            getLocationOnScreen(loc)
            sel = RectF(
                (init.left - loc[0]).toFloat(), (init.top - loc[1]).toFloat(),
                (init.right - loc[0]).toFloat(), (init.bottom - loc[1]).toFloat(),
            )
        }

        private fun layoutButtons() {
            val bw = dp(130f)
            val bh = dp(48f)
            val gap = dp(12f)
            val total = bw * 2 + gap
            val left = (width - total) / 2
            // 기본은 위쪽, 선택 영역이 위쪽에 있으면 아래쪽에 배치
            val s = sel
            val topY = dp(56f)
            val bottomY = height - dp(56f) - bh
            val y = if (s != null && s.top < topY + bh + dp(24f)) bottomY else topY
            cancelBtn.set(left, y, left + bw, y + bh)
            okBtn.set(left + bw + gap, y, left + total, y + bh)
        }

        override fun onDraw(canvas: Canvas) {
            layoutButtons()
            val s = sel
            if (s == null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                canvas.drawText("번역할 대사창 부분을 손가락으로 드래그하세요", width / 2f, height / 2f, hintText)
            } else {
                // 선택 영역만 밝게
                canvas.drawRect(0f, 0f, width.toFloat(), s.top, dim)
                canvas.drawRect(0f, s.bottom, width.toFloat(), height.toFloat(), dim)
                canvas.drawRect(0f, s.top, s.left, s.bottom, dim)
                canvas.drawRect(s.right, s.top, width.toFloat(), s.bottom, dim)
                canvas.drawRect(s, border)
                for (p in corners(s)) canvas.drawCircle(p.first, p.second, handleR, handle)
                canvas.drawText(
                    "모서리: 크기 조절 · 안쪽: 이동",
                    width / 2f, if (s.bottom + dp(28f) < height) s.bottom + dp(28f) else s.top - dp(14f), hintText,
                )
            }
            drawButton(canvas, cancelBtn, "취소", Color.argb(230, 90, 90, 96), pressedBtn == 1)
            val okColor = if (s != null && valid(s)) Color.rgb(224, 83, 61) else Color.argb(200, 120, 120, 120)
            drawButton(canvas, okBtn, "이 영역 번역 ▶", okColor, pressedBtn == 2)
        }

        private fun drawButton(c: Canvas, r: RectF, label: String, color: Int, pressed: Boolean) {
            btnBg.color = color
            btnBg.alpha = if (pressed) 170 else Color.alpha(color)
            c.drawRoundRect(r, dp(24f), dp(24f), btnBg)
            c.drawText(label, r.centerX(), r.centerY() - (btnText.ascent() + btnText.descent()) / 2, btnText)
        }

        private fun corners(s: RectF) = listOf(
            s.left to s.top, s.right to s.top, s.left to s.bottom, s.right to s.bottom,
        )

        private fun valid(s: RectF) = s.width() >= minSize && s.height() >= minSize * 0.6f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y
                    pressedBtn = when {
                        cancelBtn.contains(x, y) -> 1
                        okBtn.contains(x, y) -> 2
                        else -> 0
                    }
                    if (pressedBtn != 0) { invalidate(); return true }
                    val s = sel
                    mode = 1
                    if (s != null) {
                        val idx = corners(s).indexOfFirst { abs(it.first - x) < touchR && abs(it.second - y) < touchR }
                        if (idx >= 0) { mode = 3; corner = idx }
                        else if (s.contains(x, y)) mode = 2
                        startRect = RectF(s)
                    }
                    drew = false
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressedBtn != 0) return true
                    val dx = x - downX
                    val dy = y - downY
                    val w = width.toFloat()
                    val h = height.toFloat()
                    when (mode) {
                        1 -> if (drew || abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                            // 살짝 터치만 한 경우엔 기존 영역을 지우지 않음
                            drew = true
                            sel = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                        }
                        2 -> {
                            val r = RectF(startRect)
                            r.offset(
                                dx.coerceIn(-r.left, w - r.right),
                                dy.coerceIn(-r.top, h - r.bottom),
                            )
                            sel = r
                        }
                        3 -> {
                            val r = RectF(startRect)
                            when (corner) {
                                0 -> { r.left = min(r.left + dx, r.right - minSize); r.top = min(r.top + dy, r.bottom - minSize * 0.6f) }
                                1 -> { r.right = max(r.right + dx, r.left + minSize); r.top = min(r.top + dy, r.bottom - minSize * 0.6f) }
                                2 -> { r.left = min(r.left + dx, r.right - minSize); r.bottom = max(r.bottom + dy, r.top + minSize * 0.6f) }
                                3 -> { r.right = max(r.right + dx, r.left + minSize); r.bottom = max(r.bottom + dy, r.top + minSize * 0.6f) }
                            }
                            r.left = max(0f, r.left); r.top = max(0f, r.top)
                            r.right = min(w, r.right); r.bottom = min(h, r.bottom)
                            sel = r
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val btn = pressedBtn
                    pressedBtn = 0
                    mode = 0
                    invalidate()
                    if (btn == 1 && cancelBtn.contains(x, y)) onCancel()
                    if (btn == 2 && okBtn.contains(x, y)) {
                        val s = sel
                        if (s != null && valid(s)) {
                            getLocationOnScreen(loc)
                            onConfirm(
                                Rect(
                                    (s.left + loc[0]).toInt(), (s.top + loc[1]).toInt(),
                                    (s.right + loc[0]).toInt(), (s.bottom + loc[1]).toInt(),
                                )
                            )
                        }
                    }
                    // 너무 작게 그려진 건 취소
                    val s = sel
                    if (drew && s != null && !valid(s)) { sel = null; invalidate() }
                    drew = false
                }
                MotionEvent.ACTION_CANCEL -> { pressedBtn = 0; mode = 0; invalidate() }
            }
            return true
        }
    }
}
