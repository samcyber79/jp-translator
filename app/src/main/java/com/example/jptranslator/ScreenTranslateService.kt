package com.example.jptranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 화면 캡처 → 일본어 OCR → 한국어 번역 → 오버레이 표시를 담당하는 포그라운드 서비스.
 *
 * 상태 흐름 (모두 work 스레드에서 처리):
 *   IDLE ──탭──▶ HIDING(버튼 숨기고 깨끗한 화면 캡처) ─▶ PROCESSING(OCR+번역)
 *        ─▶ SHOWN_BASELINE(결과 표시 직후 기준 화면 저장) ─▶ SHOWN_WATCH(화면 변화 감시)
 *   SHOWN_WATCH 에서 화면이 바뀌면(스크롤, 페이지 넘김) 번역을 지우고
 *     - 실시간 모드: WAIT_STABLE(화면이 멈추길 기다림) ─▶ 다시 HIDING …
 *     - 일반 모드:   IDLE
 */
class ScreenTranslateService : Service() {

    private enum class State { IDLE, WAIT_STABLE, HIDING, PROCESSING, SHOWN_BASELINE, SHOWN_WATCH }

    private val main = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("capture").apply { start() }
    private val work = Handler(workThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private var overlay: TranslationOverlayView? = null
    private var bubble: BubbleController? = null
    private val ocr by lazy { JapaneseOcr() }
    private val translator by lazy { JaKoTranslator() }

    // ---- work 스레드 전용 상태 ----
    private var state = State.IDLE
    @Volatile private var live = false
    private var lastSig: ScreenCapturer.Signature? = null
    private var baseline: ScreenCapturer.Signature? = null
    private var baselineUntil = 0L
    private var stableRef: ScreenCapturer.Signature? = null
    private var stableSince = 0L
    private var captureSig: ScreenCapturer.Signature? = null
    private var stale = false
    private var forceShow = false
    private var job: Job? = null
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            work.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_CODE, 0)
                    val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                    if (data == null || !setup(code, data)) stopSelf()
                }
            }
            ACTION_TRANSLATE -> if (projection == null) stopSelf() else work.post { onTap() }
            ACTION_TOGGLE_LIVE -> if (projection == null) stopSelf() else work.post { toggleLive() }
            ACTION_STOP -> stopSelf()
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ setup

    private fun setup(code: Int, data: Intent): Boolean {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        } ?: return false
        projection = proj
        running = true
        // 사용자가 상태바 칩 등으로 화면 공유를 중지하면 서비스도 종료
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = windowContext.getSystemService(WindowManager::class.java)

        addOverlay()
        bubble = BubbleController(
            windowContext, wm,
            onTap = { work.post { onTap() } },
            onLongPress = { work.post { toggleLive() } },
            onMoved = { work.post { onBubbleMoved() } },
        ).also { it.show() }

        // 회전(가로/세로 화면) 감지
        windowContext.registerComponentCallbacks(configCallbacks)

        live = Prefs.liveOnStart(this)
        bubble?.setLive(live)
        updateNotification()

        // 번역 모델 미리 준비 (처음 한 번만 다운로드)
        scope.launch {
            try { translator.ensureModel() } catch (e: Exception) {
                if (e !is CancellationException) toast("번역 모델을 받지 못했어요. 인터넷 연결을 확인하세요.")
            }
        }

        work.post {
            val c = ScreenCapturer(windowContext, proj, work)
            try {
                c.start()
            } catch (e: Exception) {
                Log.e(TAG, "capture start failed", e)
                main.post { stopSelf() }
                return@post
            }
            capturer = c
            work.post(tick)
            if (live) enterWaitStable(SystemClock.uptimeMillis(), immediate = true)
        }
        return true
    }

    private fun addOverlay() {
        val v = TranslationOverlayView(windowContext)
        v.verticalRender = Prefs.verticalRender(this)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Android 12+: 불투명도가 기준값(보통 0.8) 이하인 오버레이만 터치를 아래 앱으로 통과시킨다.
            // 이걸 안 하면 번역이 떠 있는 동안 스크롤이 막힌다.
            alpha = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            else 0.9f
        }
        wm.addView(v, lp)
        overlay = v
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            work.post { onDisplayChanged() }
            // 일부 기기는 메트릭 갱신이 조금 늦으므로 한 번 더 확인
            work.postDelayed({ onDisplayChanged() }, 600)
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    // ------------------------------------------------------------------ state machine

    private fun onTick() {
        val c = capturer ?: return
        val now = SystemClock.uptimeMillis()
        // 회전 콜백을 놓친 경우 대비 (약 0.6초마다 확인)
        if (++tickCount % 4 == 0 && c.resizeIfNeeded()) { resetAfterResize(now); return }

        val sig = c.signatureIfNew()
        if (sig != null) lastSig = sig
        val mask = bubble?.screenRect

        when (state) {
            State.IDLE, State.HIDING -> Unit

            State.PROCESSING -> {
                val cs = captureSig
                if (sig != null && cs != null && sig.differsFrom(cs, mask)) stale = true
            }

            State.SHOWN_BASELINE -> {
                if (sig != null) baseline = sig
                if (now >= baselineUntil) {
                    if (baseline == null) baseline = lastSig
                    state = State.SHOWN_WATCH
                }
            }

            State.SHOWN_WATCH -> {
                val b = baseline
                if (sig != null && b != null && sig.differsFrom(b, mask)) {
                    // 스크롤/페이지 넘김 → 지난 번역은 위치가 틀리므로 지움
                    main.post { overlay?.clear() }
                    if (live) enterWaitStable(now, immediate = false) else state = State.IDLE
                }
            }

            State.WAIT_STABLE -> {
                if (sig != null) {
                    val ref = stableRef
                    if (ref == null || sig.differsFrom(ref, mask)) {
                        stableRef = sig
                        stableSince = now
                    }
                }
                if (now - stableSince >= STABLE_MS) startCapture(force = false)
            }
        }
    }

    private fun enterWaitStable(now: Long, immediate: Boolean) {
        state = State.WAIT_STABLE
        stableRef = lastSig
        stableSince = if (immediate) now - STABLE_MS else now
    }

    private fun startCapture(force: Boolean) {
        state = State.HIDING
        forceShow = force
        // 버튼과 이전 번역이 OCR에 찍히지 않도록 잠깐 숨긴 뒤 캡처
        main.post {
            bubble?.setHidden(true)
            overlay?.clear()
        }
        work.postDelayed({ doCapture() }, HIDE_DELAY_MS)
    }

    private fun doCapture() {
        if (state != State.HIDING) return
        val c = capturer ?: return
        c.signatureIfNew()?.let { lastSig = it }
        val bmp: Bitmap? = try { c.latestBitmap() } catch (e: Exception) { Log.e(TAG, "bitmap", e); null }
        if (bmp == null) {
            main.post { bubble?.setHidden(false) }
            enterWaitStable(SystemClock.uptimeMillis(), immediate = false)
            return
        }
        captureSig = lastSig
        stale = false
        state = State.PROCESSING
        main.post {
            bubble?.setHidden(false)
            bubble?.setBusy(true)
        }
        job = scope.launch {
            var error: Exception? = null
            val items = try {
                pipeline(bmp)
            } catch (e: Exception) {
                if (e !is CancellationException) { error = e; Log.e(TAG, "pipeline failed", e) }
                emptyList()
            } finally {
                bmp.recycle()
            }
            work.post { onResult(items, error) }
        }
    }

    private suspend fun pipeline(bmp: Bitmap): List<OverlayItem> {
        val blocks = ocr.recognize(bmp)
        if (blocks.isEmpty()) return emptyList()
        return coroutineScope {
            blocks.map { b ->
                async { OverlayItem(b.rect, translator.translate(b.text), b.vertical) }
            }.awaitAll()
        }
    }

    private fun onResult(items: List<OverlayItem>, error: Exception?) {
        if (state != State.PROCESSING) return
        val now = SystemClock.uptimeMillis()
        main.post { bubble?.setBusy(false) }

        if (error != null) {
            toast("번역 실패: 번역 모델이 아직 없거나 인터넷 연결이 필요해요.")
        }
        // 실시간 모드에서 처리 중에 화면이 바뀌었다면 결과를 버리고 다시 시도
        if (stale && live && !forceShow) {
            enterWaitStable(now, immediate = false)
            return
        }
        if (items.isEmpty() && !live && error == null) {
            toast("화면에서 일본어를 찾지 못했어요.")
        }
        main.post { overlay?.setItems(items) }
        state = State.SHOWN_BASELINE
        baseline = null
        baselineUntil = now + BASELINE_MS
    }

    // ------------------------------------------------------------------ user actions

    private fun onTap() {
        if (capturer == null) return
        when (state) {
            State.HIDING, State.PROCESSING -> Unit
            State.SHOWN_BASELINE, State.SHOWN_WATCH ->
                if (live) startCapture(force = true)
                else { main.post { overlay?.clear() }; state = State.IDLE }
            State.IDLE, State.WAIT_STABLE -> startCapture(force = true)
        }
    }

    private fun toggleLive() {
        live = !live
        main.post {
            bubble?.setLive(live)
            updateNotification()
        }
        toast(if (live) "실시간 번역 켜짐" else "실시간 번역 꺼짐")
        if (capturer == null) return
        if (live && state == State.IDLE) startCapture(force = false)
        if (!live && state == State.WAIT_STABLE) state = State.IDLE
    }

    private fun onBubbleMoved() {
        val now = SystemClock.uptimeMillis()
        when (state) {
            State.SHOWN_WATCH, State.SHOWN_BASELINE -> {
                state = State.SHOWN_BASELINE; baseline = null; baselineUntil = now + BASELINE_MS
            }
            State.WAIT_STABLE -> stableSince = now
            else -> Unit
        }
    }

    private fun onDisplayChanged() {
        val c = capturer ?: return
        if (c.resizeIfNeeded()) resetAfterResize(SystemClock.uptimeMillis())
    }

    /** 가로↔세로 회전 후: 지난 결과는 지우고, 실시간이면 새 화면에서 다시 번역 */
    private fun resetAfterResize(now: Long) {
        job?.cancel()
        state = State.IDLE
        lastSig = null; baseline = null; stableRef = null; captureSig = null
        main.post {
            overlay?.clear()
            bubble?.setBusy(false)
            bubble?.setHidden(false)
            bubble?.clampToScreen()
        }
        if (live) enterWaitStable(now, immediate = false)
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "화면 번역", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, ScreenTranslateService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("일본어 화면번역 실행 중")
            .setContentText(if (live) "실시간 모드 · 화면이 멈추면 자동 번역" else "버튼을 탭하면 번역 · 길게 누르면 실시간")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "번역", action(ACTION_TRANSLATE, 1))
            .addAction(0, if (live) "실시간 끄기" else "실시간 켜기", action(ACTION_TOGGLE_LIVE, 2))
            .addAction(0, "종료", action(ACTION_STOP, 3))
            .build()
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) { }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ teardown

    override fun onDestroy() {
        running = false
        work.removeCallbacksAndMessages(null)
        scope.cancel()
        val c = capturer
        capturer = null
        work.post { c?.release() }
        workThread.quitSafely()
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        if (::windowContext.isInitialized) {
            try { windowContext.unregisterComponentCallbacks(configCallbacks) } catch (_: Exception) { }
        }
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        overlay = null
        bubble?.remove()
        bubble = null
        try { ocr.close() } catch (_: Exception) { }
        try { translator.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JpTranslator"
        private const val CHANNEL = "translator"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.example.jptranslator.START"
        const val ACTION_TRANSLATE = "com.example.jptranslator.TRANSLATE"
        const val ACTION_TOGGLE_LIVE = "com.example.jptranslator.TOGGLE_LIVE"
        const val ACTION_STOP = "com.example.jptranslator.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val TICK_MS = 150L        // 화면 변화 확인 주기
        private const val STABLE_MS = 450L      // 이 시간 동안 화면이 안 바뀌면 "멈춤"으로 판단
        private const val BASELINE_MS = 450L    // 결과 표시 직후 기준 화면을 잡는 시간
        private const val HIDE_DELAY_MS = 180L  // 버튼 숨긴 뒤 캡처까지 대기

        @Volatile var running = false
            private set
    }
}
