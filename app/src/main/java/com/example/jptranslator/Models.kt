package com.example.jptranslator

import android.graphics.Rect

/** OCR로 찾은 한 줄(가로쓰기) 또는 한 열(세로쓰기) */
data class OcrLine(val rect: Rect, val text: String)

/** 하나의 문단/말풍선 단위 */
data class OcrBlock(
    val rect: Rect,          // 화면 좌표
    val text: String,        // 읽는 순서대로 이어 붙인 원문
    val vertical: Boolean,   // 세로쓰기 여부
)

/** 화면에 그릴 번역 결과 */
data class OverlayItem(
    val rect: Rect,
    val text: String,
    val vertical: Boolean,
)
