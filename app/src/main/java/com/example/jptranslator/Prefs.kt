package com.example.jptranslator

import android.content.Context
import android.graphics.Rect

/** 앱 설정 (SharedPreferences) */
object Prefs {
    private const val NAME = "settings"
    private const val KEY_VERTICAL_RENDER = "vertical_render"
    private const val KEY_LIVE_ON_START = "live_on_start"
    private const val KEY_PANEL_POS = "panel_pos"
    private const val KEY_SHOW_ORIGINAL = "show_original"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 세로쓰기 원문은 번역도 세로로 표시할지 (false면 같은 자리에 가로로 표시) */
    fun verticalRender(ctx: Context) = sp(ctx).getBoolean(KEY_VERTICAL_RENDER, true)
    fun setVerticalRender(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_VERTICAL_RENDER, v).apply()

    /** 시작하자마자 실시간(전체 화면) 모드로 동작할지 */
    fun liveOnStart(ctx: Context) = sp(ctx).getBoolean(KEY_LIVE_ON_START, false)
    fun setLiveOnStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_LIVE_ON_START, v).apply()

    // ---------------- 영역 모드 ----------------

    /** 번역창 위치: 0=자동, 1=영역 위, 2=영역 아래 */
    fun panelPos(ctx: Context) = sp(ctx).getInt(KEY_PANEL_POS, 0)
    fun setPanelPos(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_PANEL_POS, v).apply()

    /** 번역창에 일본어 원문도 같이 보여줄지 */
    fun showOriginal(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_ORIGINAL, false)
    fun setShowOriginal(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_ORIGINAL, v).apply()

    /** 지정한 영역은 가로 화면/세로 화면 따로 저장 (게임은 보통 가로 고정이라 다시 지정할 필요 없게) */
    private fun regionKey(screenW: Int, screenH: Int) = "region_${screenW}x$screenH"

    fun region(ctx: Context, screenW: Int, screenH: Int): Rect? {
        val s = sp(ctx).getString(regionKey(screenW, screenH), null) ?: return null
        val p = s.split(",").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return null
        val r = Rect(p[0], p[1], p[2], p[3])
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    fun setRegion(ctx: Context, screenW: Int, screenH: Int, r: Rect) =
        sp(ctx).edit().putString(regionKey(screenW, screenH), "${r.left},${r.top},${r.right},${r.bottom}").apply()
}
