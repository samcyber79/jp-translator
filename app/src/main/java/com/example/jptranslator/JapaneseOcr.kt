package com.example.jptranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit 일본어 OCR + 가로/세로쓰기 판별 + 읽는 순서 정렬.
 *
 * - 가로쓰기: 줄을 위→아래 순서로 이어 붙임
 * - 세로쓰기: 열을 오른쪽→왼쪽 순서로 이어 붙임
 * - 만화처럼 한 말풍선의 세로 열들이 여러 블록으로 쪼개져 나오는 경우가 있어서
 *   가까이 붙어 있는 세로쓰기 블록은 하나로 합친다.
 */
class JapaneseOcr {

    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private class Group(val lines: MutableList<OcrLine>, val vertical: Boolean) {
        val rect: Rect
            get() = Rect(lines[0].rect).also { r -> lines.forEach { r.union(it.rect) } }

        /** 글자 크기 추정치: 세로쓰기는 열 폭, 가로쓰기는 줄 높이의 중앙값 */
        val charSize: Int
            get() = lines.map { if (vertical) it.rect.width() else it.rect.height() }.sorted()[lines.size / 2]
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val result: Text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

        val groups = mutableListOf<Group>()
        for (block in result.textBlocks) {
            val lines = block.lines.mapNotNull { l ->
                val box = l.boundingBox ?: return@mapNotNull null
                val t = l.text.trim()
                if (t.isEmpty()) null else OcrLine(Rect(box), t)
            }
            if (lines.isEmpty()) continue
            if (lines.none { JP_REGEX.containsMatchIn(it.text) }) continue   // 일본어가 없는 블록은 건너뜀
            groups += Group(lines.toMutableList(), isVertical(lines, block.boundingBox))
        }

        mergeVerticalGroups(groups)

        return groups.map { g ->
            val ordered = if (g.vertical) g.lines.sortedByDescending { it.rect.centerX() }
            else g.lines.sortedBy { it.rect.top }
            OcrBlock(g.rect, joinLines(ordered.map { it.text }), g.vertical)
        }
    }

    /**
     * 영역 모드용: 잘라낸 영역 이미지를 인식하고 블록을 읽는 순서로 정렬해서 돌려준다.
     * 작은 글씨는 인식률이 떨어지므로 영역이 작으면 확대해서 인식한다.
     */
    suspend fun recognizeRegion(crop: Bitmap): List<OcrBlock> {
        val scale = when {
            crop.height < 160 -> 2.0f
            crop.height < 320 && crop.width < 900 -> 1.5f
            else -> 1.0f
        }
        val input = if (scale > 1f) Bitmap.createScaledBitmap(
            crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true,
        ) else crop
        val blocks = try { recognize(input) } finally { if (input !== crop) input.recycle() }
        val verticalCount = blocks.count { it.vertical }
        return if (verticalCount * 2 > blocks.size) blocks.sortedByDescending { it.rect.right }
        else blocks.sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left }))
    }

    fun close() = recognizer.close()

    // ---------------------------------------------------------------------

    private fun isTall(r: Rect) = r.height() > r.width() * 1.2f

    private fun isVertical(lines: List<OcrLine>, blockBox: Rect?): Boolean {
        val multi = lines.filter { it.text.length >= 2 }
        if (multi.isNotEmpty()) {
            return multi.count { isTall(it.rect) } * 2 > multi.size
        }
        // 전부 한 글자짜리 줄이면 블록 전체 모양으로 판단
        val b = blockBox ?: return false
        return lines.size >= 2 && b.height() > b.width() * 1.5f
    }

    /** 서로 붙어 있는 세로쓰기 블록(같은 말풍선의 열들)을 합친다. */
    private fun mergeVerticalGroups(groups: MutableList<Group>) {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in groups.indices) {
                val a = groups[i]
                if (!a.vertical) continue
                for (j in i + 1 until groups.size) {
                    val b = groups[j]
                    if (!b.vertical) continue
                    if (shouldMerge(a, b)) {
                        a.lines += b.lines
                        groups.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
    }

    private fun shouldMerge(a: Group, b: Group): Boolean {
        val ca = a.charSize.toFloat()
        val cb = b.charSize.toFloat()
        if (ca <= 0 || cb <= 0) return false
        val ratio = ca / cb
        if (ratio < 0.6f || ratio > 1.65f) return false          // 글자 크기가 많이 다르면 다른 텍스트

        val ra = a.rect
        val rb = b.rect
        val cs = max(ca, cb)
        // 가로 간격: 열 사이 간격이 글자 크기의 0.9배 이하
        val hGap = max(ra.left, rb.left) - min(ra.right, rb.right)
        if (hGap > cs * 0.9f) return false
        // 세로 범위가 어느 정도 겹쳐야 함
        val vOverlap = min(ra.bottom, rb.bottom) - max(ra.top, rb.top)
        return vOverlap > cs * 0.5f
    }

    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                val next = l.first()
                // 일본어끼리는 띄어쓰기 없이, 영문/숫자 사이에는 공백
                if (!isCjk(prev) && !isCjk(next)) sb.append(' ')
            }
            sb.append(l)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char) = JP_REGEX.matches(c.toString()) || c in "。、！？「」『』（）…ー・〜"

    companion object {
        /** 히라가나, 가타카나, 한자, 반각 가타카나 */
        val JP_REGEX = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]")
    }
}
