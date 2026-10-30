package com.example.jptranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.WindowManager
import kotlin.math.abs

/**
 * MediaProjection → VirtualDisplay → ImageReader 로 화면을 받아온다.
 *
 * 모든 메서드는 [handler] 스레드에서만 호출해야 한다.
 * (ImageReader 콜백도 같은 스레드라서 따로 동기화가 필요 없음)
 */
class ScreenCapturer(
    private val windowContext: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
) {
    private val wm = windowContext.getSystemService(WindowManager::class.java)

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var dirty = false

    var width = 0; private set
    var height = 0; private set

    fun start() {
        val (w, h, dpi) = metrics()
        width = w; height = h
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "jp-screen-translator", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    /** 화면 회전 등으로 크기가 바뀌었으면 캡처 크기도 맞춘다. 바뀌었으면 true */
    fun resizeIfNeeded(): Boolean {
        val (w, h, dpi) = metrics()
        if (w == width && h == height) return false
        latest?.close(); latest = null; dirty = false
        val old = reader
        val r = newReader(w, h)
        reader = r
        display?.resize(w, h, dpi)
        display?.surface = r.surface
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        width = w; height = h
        return true
    }

    fun release() {
        latest?.close(); latest = null
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
    }

    /** 새 프레임이 들어왔으면 그 프레임의 축소 지문(signature)을, 아니면 null */
    fun signatureIfNew(): Signature? {
        if (!dirty) return null
        dirty = false
        val img = latest ?: return null
        return computeSignature(img)
    }

    /** 가장 최근 프레임을 Bitmap으로 복사 */
    fun latestBitmap(): Bitmap? {
        val img = latest ?: return null
        val plane = img.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        buf.rewind()
        full.copyPixelsFromBuffer(buf)
        if (rowPadding == 0) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cropped
    }

    // ---------------------------------------------------------------------

    @SuppressLint("WrongConstant")
    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }

    private fun onImageAvailable(r: ImageReader) {
        if (r !== reader) return
        val img = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        latest?.close()
        latest = img
        dirty = true
    }

    private fun metrics(): Triple<Int, Int, Int> {
        val b = wm.maximumWindowMetrics.bounds
        val dpi = windowContext.resources.configuration.densityDpi
        return Triple(b.width(), b.height(), dpi)
    }

    /** 화면을 COLS x rows 격자로 나눠 각 칸의 평균 밝기를 구한다 (변화 감지용). */
    private fun computeSignature(img: Image): Signature {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = img.width
        val h = img.height
        val cols = Signature.COLS
        val rows = (cols * h / w.toFloat()).toInt().coerceAtLeast(1)
        val cellW = w / cols.toFloat()
        val cellH = h / rows.toFloat()
        val values = IntArray(cols * rows)
        val n = SAMPLES_PER_CELL
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                for (sy in 0 until n) {
                    val y = (r * cellH + (sy + 0.5f) * cellH / n).toInt().coerceIn(0, h - 1)
                    for (sx in 0 until n) {
                        val x = (c * cellW + (sx + 0.5f) * cellW / n).toInt().coerceIn(0, w - 1)
                        val i = y * rs + x * ps
                        val red = buf.get(i).toInt() and 0xFF
                        val green = buf.get(i + 1).toInt() and 0xFF
                        val blue = buf.get(i + 2).toInt() and 0xFF
                        sum += (red * 3 + green * 6 + blue) / 10
                    }
                }
                values[r * cols + c] = sum / (n * n)
            }
        }
        return Signature(w, h, rows, values)
    }

    /** 축소된 화면 밝기 격자 */
    class Signature(val width: Int, val height: Int, val rows: Int, val values: IntArray) {

        /** 두 프레임이 의미 있게 달라졌는지. [mask] 영역(플로팅 버튼 등)은 무시 */
        fun differsFrom(other: Signature, mask: Rect?): Boolean {
            if (other.width != width || other.height != height || other.values.size != values.size) return true
            val cellW = width / COLS.toFloat()
            val cellH = height / rows.toFloat()
            var changed = 0
            for (r in 0 until rows) {
                for (c in 0 until COLS) {
                    if (mask != null) {
                        val cx = ((c + 0.5f) * cellW).toInt()
                        val cy = ((r + 0.5f) * cellH).toInt()
                        if (cx >= mask.left - cellW && cx <= mask.right + cellW &&
                            cy >= mask.top - cellH && cy <= mask.bottom + cellH
                        ) continue
                    }
                    val i = r * COLS + c
                    if (abs(values[i] - other.values[i]) > CELL_THRESHOLD) changed++
                }
            }
            // 커서 깜빡임 같은 아주 작은 변화는 무시
            return changed > maxOf(3, values.size / 300)
        }

        companion object {
            const val COLS = 24
            const val CELL_THRESHOLD = 6
        }
    }

    companion object {
        private const val SAMPLES_PER_CELL = 6
    }
}
