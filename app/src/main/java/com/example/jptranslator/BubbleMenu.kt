package com.example.jptranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 「訳」 버튼을 길게 누르면 나오는 메뉴 (바깥을 터치하면 닫힘) */
class BubbleMenu(private val context: Context, private val wm: WindowManager) {

    data class Item(val label: String, val action: () -> Unit)

    private var root: View? = null

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    val isShowing get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchor: Rect, items: List<Item>) {
        dismiss()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.argb(245, 32, 32, 36))
            }
        }
        for (item in items) {
            list.addView(TextView(context).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16f), dp(12f), dp(20f), dp(12f))
                minWidth = dp(200f)
                setOnClickListener {
                    dismiss()
                    item.action()
                }
            })
        }
        list.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false
        }

        val screen = wm.maximumWindowMetrics.bounds
        list.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.height(), View.MeasureSpec.AT_MOST),
        )
        val mw = list.measuredWidth
        val mh = list.measuredHeight
        // 버튼이 오른쪽에 있으면 왼쪽으로, 왼쪽에 있으면 오른쪽으로 펼침
        val x = if (anchor.centerX() > screen.width() / 2) anchor.left - mw - dp(8f) else anchor.right + dp(8f)
        val y = (anchor.centerY() - mh / 2).coerceIn(dp(24f), (screen.height() - mh - dp(24f)).coerceAtLeast(0))

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, (screen.width() - mw).coerceAtLeast(0))
            this.y = y
        }
        wm.addView(list, lp)
        root = list
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) { }
    }
}
