package com.example.jptranslator

import android.content.Context

/** 앱 설정 (SharedPreferences) */
object Prefs {
    private const val NAME = "settings"
    private const val KEY_VERTICAL_RENDER = "vertical_render"
    private const val KEY_LIVE_ON_START = "live_on_start"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 세로쓰기 원문은 번역도 세로로 표시할지 (false면 같은 자리에 가로로 표시) */
    fun verticalRender(ctx: Context) = sp(ctx).getBoolean(KEY_VERTICAL_RENDER, true)
    fun setVerticalRender(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_VERTICAL_RENDER, v).apply()

    /** 시작하자마자 실시간 모드로 동작할지 */
    fun liveOnStart(ctx: Context) = sp(ctx).getBoolean(KEY_LIVE_ON_START, false)
    fun setLiveOnStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_LIVE_ON_START, v).apply()
}
