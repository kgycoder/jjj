package com.agent.arena

import android.accessibilityservice.AccessibilityService
import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/** 작업 상태 → 색상. */
enum class CursorState(val color: Int) {
    WORKING(0xFF3B82F6.toInt()),   // 작업 중: 블루
    THINKING(0xFF6B7280.toInt()),  // 다음 작업 생각 중: 슬레이트
    DONE(0xFF22A06B.toInt()),      // 완료: 그린
    FAILED(0xFFE5484D.toInt()),    // 실패: 레드
    ASK(0xFFF59E0B.toInt()),       // 사용자 확인 필요: 앰버
    STOPPED(0xFF9CA3AF.toInt()),   // 중단: 라이트 그레이
}

/**
 * Agent Action Cursor.
 * 접근성 오버레이 창(권한 불필요, 터치 비차단)에 단색 커서 + 현재 작업 요약 라벨을 그린다.
 */
class AgentCursorOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: CursorView? = null
    private var hideRunnable: Runnable? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        cancelHide()
        val existing = view
        if (existing != null) { existing.setVisibleTarget(true, null); return }
        val v = CursorView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, lp)
            view = v
            v.setVisibleTarget(true, null)
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(delayMs: Long = 0) {
        val v = view ?: return
        cancelHide()
        val r = Runnable { v.setVisibleTarget(false) { remove(v) } }
        hideRunnable = r
        v.postDelayed(r, delayMs)
    }

    fun destroy() {
        cancelHide()
        view?.let { remove(it) }
    }

    fun setLabel(text: String, state: CursorState = CursorState.WORKING) {
        view?.setLabel(text, state)
    }

    /** 커서를 (x,y)로 부드럽게 이동하고, 도착할 때까지 대기. */
    suspend fun moveTo(x: Float, y: Float) {
        val v = view ?: return
        v.setTarget(x, y)
        val end = SystemClock.uptimeMillis() + 1100
        delay(80)
        while (!v.isSettled() && SystemClock.uptimeMillis() < end) delay(16)
    }

    /** 대기하지 않고 목표만 갱신(스와이프 중 커서가 따라가게 할 때). */
    fun glideTo(x: Float, y: Float) { view?.setTarget(x, y) }

    /** 탭 효과(눌림 + 얇은 링). */
    suspend fun press() {
        val v = view ?: return
        v.press()
        delay(170)
    }

    private fun cancelHide() {
        hideRunnable?.let { view?.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun remove(v: CursorView) {
        try { wm.removeView(v) } catch (_: Exception) { }
        if (view === v) view = null
    }
}

// ============================================================================ 내부 구현

/** 임계 감쇠 스프링: 자연스러운 가속/감속. */
private class Sp(var x: Float, val omega: Float) {
    var t = x
    var vel = 0f
    fun snap(v: Float) { x = v; t = v; vel = 0f }
    fun step(dt: Float) {
        val n = 2
        val h = dt / n
        repeat(n) {
            val a = omega * omega * (t - x) - 2f * omega * vel
            vel += a * h
            x += vel * h
        }
    }
    fun settled(eps: Float) = abs(t - x) < eps && abs(vel) < eps * 6f
}

private class CursorView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val fontScale = resources.configuration.fontScale

    // ---- 상태(스프링)
    private val px = Sp(0f, 15f)
    private val py = Sp(0f, 15f)
    private val appear = Sp(0f, 13f)
    private val press = Sp(1f, 28f)
    private val flipX = Sp(0f, 16f)
    private val flipY = Sp(0f, 16f)
    private val pillW = Sp(0f, 20f)
    private val colorT = Sp(1f, 14f)

    private var placed = false
    private var pendX = 0f
    private var pendY = 0f
    private var hasPend = false

    private var text = ""
    private var prevText = ""
    private var fadeStart = 0L
    private var pulseStart = -10_000L
    private var onHidden: (() -> Unit)? = null

    // 색상 전환(상태가 바뀌면 이전 색 → 새 색으로 부드럽게)
    private val evaluator = ArgbEvaluator()
    private var state = CursorState.WORKING
    private var colFrom = CursorState.WORKING.color
    private var colTo = CursorState.WORKING.color

    private var lastFrame = 0L
    private val loc = IntArray(2)
    private val rect = RectF()
    private val clip = Path()

    // ---- 페인트 (단색, 효과 없음)
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 23f * d)
        lineTo(6f * d, 17.5f * d)
        lineTo(10.5f * d, 27f * d)
        lineTo(14f * d, 25.5f * d)
        lineTo(9.8f * d, 16.2f * d)
        lineTo(17f * d, 16f * d)
        close()
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(3f * d)
    }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(3f * d)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
    }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F2937.toInt()
        textSize = 13.5f * d * fontScale
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val padH = 14f * d
    private val dotSpace = 15f * d
    private val pillH = 34f * d
    private val maxTextW = 210f * d

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { onFrame(frameTimeNanos) }
    }

    // ------------------------------------------------------------------ API

    fun setVisibleTarget(visible: Boolean, hidden: (() -> Unit)?) {
        appear.t = if (visible) 1f else 0f
        onHidden = if (visible) null else hidden
    }

    fun setTarget(x: Float, y: Float) {
        if (!placed) { pendX = x; pendY = y; hasPend = true; return }
        px.t = x; py.t = y
    }

    fun isSettled() = px.settled(3f) && py.settled(3f)

    fun press() {
        press.t = 0.82f
        pulseStart = SystemClock.uptimeMillis()
        postDelayed({ press.t = 1f }, 150)
    }

    fun setLabel(t: String, newState: CursorState) {
        if (newState != state) {
            colFrom = accent()
            colTo = newState.color
            state = newState
            colorT.snap(0f)
            colorT.t = 1f
        }
        val nt = t.trim()
        if (nt == text) return
        prevText = text
        text = nt
        fadeStart = SystemClock.uptimeMillis()
        val target = targetWidth()
        if (prevText.isBlank()) pillW.snap(target) else pillW.t = target
    }

    private fun accent(): Int =
        evaluator.evaluate(colorT.x.coerceIn(0f, 1f), colFrom, colTo) as Int

    // ------------------------------------------------------------------ 레이아웃/프레임

    private fun shown(s: String): String =
        TextUtils.ellipsize(s, textPaint, maxTextW, TextUtils.TruncateAt.END).toString()

    private fun targetWidth(): Float {
        if (text.isBlank()) return pillH
        return textPaint.measureText(shown(text)) + padH * 2 + dotSpace
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!placed && w > 0 && h > 0) {
            placed = true
            px.snap(w * 0.5f); py.snap(h * 0.62f)
            if (hasPend) { px.t = pendX; py.t = pendY; hasPend = false }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }

    private fun onFrame(nanos: Long) {
        val dt = if (lastFrame == 0L) 0.016f else ((nanos - lastFrame) / 1e9f).coerceIn(0.001f, 0.034f)
        lastFrame = nanos
        if (placed) {
            flipX.t = if (px.x + 20f * d + pillW.t > width - 10f * d) 1f else 0f
            flipY.t = if (py.x + 24f * d + pillH > height - 44f * d) 1f else 0f
        }
        px.step(dt); py.step(dt); appear.step(dt); press.step(dt)
        flipX.step(dt); flipY.step(dt); pillW.step(dt); colorT.step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(frameCb)
        if (appear.t == 0f && appear.x < 0.01f) {
            val cb = onHidden
            onHidden = null
            cb?.invoke()
        }
    }

    // ------------------------------------------------------------------ 그리기

    override fun onDraw(c: Canvas) {
        val a = appear.x.coerceIn(0f, 1f)
        if (a < 0.01f || !placed) return
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())

        val now = SystemClock.uptimeMillis()
        val accent = accent()
        val bx = px.x
        val by = py.x
        val s = 0.86f + 0.14f * a

        c.save()
        c.scale(s, s, bx, by)

        // 탭 링(얇은 단색 링 하나)
        val pt = (now - pulseStart) / 460f
        if (pt in 0f..1f) {
            val e = 1f - (1f - pt) * (1f - pt)
            ring.color = accent
            ring.alpha = (255 * 0.55f * (1f - pt) * a).toInt()
            c.drawCircle(bx, by, (8f + 26f * e) * d, ring)
        }

        // 커서 본체: 단색 채움 + 흰 윤곽선
        c.save()
        c.translate(bx, by)
        val ps = press.x
        c.scale(ps, ps, 0f, 0f)
        arrowFill.color = accent
        arrowFill.alpha = (255 * a).toInt()
        arrowStroke.alpha = (255 * a).toInt()
        c.drawPath(arrow, arrowFill)
        c.drawPath(arrow, arrowStroke)
        c.restore()

        drawPill(c, bx, by, a, accent, now)
        c.restore()
        c.restore()
    }

    private fun drawPill(c: Canvas, bx: Float, by: Float, a: Float, accent: Int, now: Long) {
        if (text.isBlank() && prevText.isBlank()) return
        val w = max(pillW.x, pillH)
        val leftN = bx + 20f * d
        val leftF = bx - 14f * d - w
        var left = leftN + (leftF - leftN) * flipX.x.coerceIn(0f, 1f)
        left = left.coerceIn(8f * d, max(8f * d, width - w - 8f * d))
        val topN = by + 24f * d
        val topF = by - pillH - 6f * d
        val top = topN + (topF - topN) * flipY.x.coerceIn(0f, 1f)
        rect.set(left, top, left + w, top + pillH)
        val r = pillH / 2f

        // 흰 캡슐 + 상태색 얇은 윤곽선 (그림자 없음)
        pillFill.alpha = (248 * a).toInt()
        c.drawRoundRect(rect, r, r, pillFill)
        pillStroke.color = accent
        pillStroke.alpha = (140 * a).toInt()
        c.drawRoundRect(rect, r, r, pillStroke)

        // 상태 점(단색, 정적)
        dotPaint.color = accent
        dotPaint.alpha = (255 * a).toInt()
        c.drawCircle(rect.left + padH + 3.5f * d, rect.centerY(), 3.5f * d, dotPaint)

        // 텍스트 크로스페이드(위로 밀려나며 교체)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val f = ((now - fadeStart) / 260f).coerceIn(0f, 1f)
        val ease = 1f - (1f - f) * (1f - f)
        val fm = textPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        val tx = rect.left + padH + dotSpace
        if (f < 1f && prevText.isNotBlank()) {
            textPaint.alpha = (255 * a * (1f - ease)).toInt()
            c.drawText(shown(prevText), tx, baseline - 5f * d * ease, textPaint)
        }
        textPaint.alpha = (255 * a * (if (prevText.isBlank()) 1f else ease)).toInt()
        c.drawText(shown(text), tx, baseline + 5f * d * (1f - ease), textPaint)
        c.restore()
    }
}
