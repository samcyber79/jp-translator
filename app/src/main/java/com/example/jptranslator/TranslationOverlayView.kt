package com.example.jptranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 번역 결과를 원문 위치에 덮어 그리는 전체화면 뷰 (터치는 통과).
 * - 가로쓰기 원문: 같은 상자 안에 가로로
 * - 세로쓰기 원문: 오른쪽 열부터 세로로 (설정에서 가로 표시로 바꿀 수 있음)
 * 글자 크기는 상자에 맞게 자동 조절하고, 최소 크기로도 안 들어가면 상자를 늘린다.
 */
class TranslationOverlayView(context: Context) : View(context) {

    var verticalRender = true

    private var items: List<OverlayItem> = emptyList()
    private val loc = IntArray(2)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(250, 255, 255, 250) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(110, 224, 83, 61)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20) }

    private val minText = sp(9f)
    private val maxText = sp(26f)
    private val pad = dp(2f)
    private val radius = dp(4f)

    fun setItems(list: List<OverlayItem>) {
        items = list
        invalidate()
    }

    fun clear() = setItems(emptyList())

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        // 결과 좌표는 화면 절대좌표 → 이 뷰의 좌표로 변환
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val screenW = (width + loc[0]).toFloat()
        val screenH = (height + loc[1]).toFloat()
        for (item in items) {
            if (item.vertical && verticalRender) drawVertical(canvas, item, screenW, screenH)
            else drawHorizontal(canvas, item, screenW, screenH)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- 가로

    private fun buildLayout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

    private fun drawHorizontal(canvas: Canvas, item: OverlayItem, screenW: Float, screenH: Float) {
        val r = RectF(item.rect)
        // 너무 좁은 상자(짧은 단어)는 약간 넓힌다
        val minW = dp(40f)
        if (r.width() < minW) {
            val grow = (minW - r.width()) / 2
            r.left -= grow; r.right += grow
        }
        // 세로쓰기 원문을 가로로 표시하는 경우: 폭을 넉넉하게
        if (item.vertical) {
            val wanted = max(r.width(), min(dp(160f), screenW * 0.45f))
            val cx = r.centerX()
            r.left = cx - wanted / 2; r.right = cx + wanted / 2
        }
        clampX(r, screenW)

        val innerW = (r.width() - pad * 2).toInt()
        val innerH = r.height() - pad * 2

        // 상자 높이에 들어가는 가장 큰 글자 크기를 이분 탐색
        var lo = minText
        var hi = max(minText, min(maxText, innerH))
        var best = minText
        repeat(10) {
            val mid = (lo + hi) / 2
            textPaint.textSize = mid
            if (buildLayout(item.text, innerW).height <= innerH) { best = mid; lo = mid } else hi = mid
        }
        textPaint.textSize = best
        val layout = buildLayout(item.text, innerW)
        if (layout.height > innerH) r.bottom = r.top + layout.height + pad * 2
        clampY(r, screenH)

        canvas.drawRoundRect(r, radius, radius, bgPaint)
        canvas.drawRoundRect(r, radius, radius, borderPaint)
        canvas.save()
        canvas.translate(r.left + pad, r.top + pad + max(0f, (r.height() - pad * 2 - layout.height) / 2))
        layout.draw(canvas)
        canvas.restore()
    }

    // ---------------------------------------------------------------- 세로

    /** 세로쓰기에서 90° 돌려 그릴 문자들 */
    private val rotateChars = "ー—–-~〜…‥()（）「」『』[]<>〈〉《》=＝:："

    /** 글자 크기 s로 높이 h 안에 세로로 쓸 때 필요한 열 수 */
    private fun columnsNeeded(chars: List<String>, s: Float, h: Float): Int {
        val adv = s * 1.05f
        var cols = 1
        var y = 0f
        for (c in chars) {
            val a = if (c == " ") adv * 0.5f else adv
            if (y + a > h && y > 0f) { cols++; y = 0f }
            if (c == " " && y == 0f) continue
            y += a
        }
        return cols
    }

    private fun drawVertical(canvas: Canvas, item: OverlayItem, screenW: Float, screenH: Float) {
        val text = item.text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return
        val chars = text.map { it.toString() }
        val r = RectF(item.rect)
        val innerH = r.height() - pad * 2
        val colFactor = 1.2f

        var lo = minText
        var hi = max(minText, min(maxText, (r.width() - pad * 2) / colFactor))
        var best = minText
        repeat(10) {
            val mid = (lo + hi) / 2
            val need = columnsNeeded(chars, mid, innerH) * mid * colFactor
            if (need <= r.width() - pad * 2) { best = mid; lo = mid } else hi = mid
        }
        val s = best
        var cols = columnsNeeded(chars, s, innerH)
        // 화면 폭을 넘어가면 아래로 늘려서 열 수를 줄인다
        var needW = cols * s * colFactor + pad * 2
        val maxW = screenW * 0.6f
        if (needW > maxW) {
            val total = chars.size * s * 1.05f
            val maxCols = max(1, (maxW / (s * colFactor)).toInt())
            r.bottom = r.top + ceil(total / maxCols) + s + pad * 2
            clampY(r, screenH)
            cols = columnsNeeded(chars, s, r.height() - pad * 2)
            needW = cols * s * colFactor + pad * 2
        }
        if (needW > r.width()) r.left = r.right - needW      // 원문이 오른쪽부터 시작하므로 왼쪽으로 확장
        clampX(r, screenW)

        canvas.drawRoundRect(r, radius, radius, bgPaint)
        canvas.drawRoundRect(r, radius, radius, borderPaint)

        textPaint.textSize = s
        textPaint.textAlign = Paint.Align.CENTER
        val fm = textPaint.fontMetrics
        val adv = s * 1.05f
        val h = r.height() - pad * 2
        var col = 0
        var y = 0f
        for (c in chars) {
            val a = if (c == " ") adv * 0.5f else adv
            if (y + a > h && y > 0f) { col++; y = 0f }
            if (c == " ") { if (y > 0f) y += a; continue }
            val cx = r.right - pad - s * colFactor * (col + 0.5f)
            val cellTop = r.top + pad + y
            val cy = cellTop + adv / 2
            val baseline = cy - (fm.ascent + fm.descent) / 2
            if (c in rotateChars) {
                canvas.save()
                canvas.rotate(90f, cx, cy)
                canvas.drawText(c, cx, baseline, textPaint)
                canvas.restore()
            } else {
                canvas.drawText(c, cx, baseline, textPaint)
            }
            y += a
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- util

    private fun clampX(r: RectF, screenW: Float) {
        if (r.width() > screenW) { r.left = 0f; r.right = screenW; return }
        if (r.left < 0f) r.offset(-r.left, 0f)
        if (r.right > screenW) r.offset(screenW - r.right, 0f)
    }

    private fun clampY(r: RectF, screenH: Float) {
        if (r.height() > screenH) { r.top = 0f; r.bottom = screenH; return }
        if (r.bottom > screenH) r.offset(0f, screenH - r.bottom)
        if (r.top < 0f) r.offset(0f, -r.top)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
