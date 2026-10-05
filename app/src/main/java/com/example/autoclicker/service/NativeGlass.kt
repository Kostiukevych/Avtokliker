package com.example.autoclicker.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.ui.theme.NeonTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
//  Нативные (View) версии «жидкого стекла» для плавающего окна и его всплывающих окон.
//  Рисуются так же, как в Compose-версии и в HTML: стеклянное тело, цветной ободок,
//  свечение внутри, электрический разряд при нажатии, неоновая рамка панели.
//  Цвет берётся из NeonTheme (общий с главным экраном).
// =====================================================================================

internal fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
internal fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

internal fun withA(color: Int, a: Float): Int =
    (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

internal object GlassDraw {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun roundPath(w: Float, h: Float, r: Float): Path =
        Path().apply { addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW) }

    private fun ellipse(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, pos: FloatArray) {
        if (rx <= 0f || ry <= 0f) return
        c.save()
        c.scale(1f, ry / rx, cx, cy)
        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(cx, cy, rx, colors, pos, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rx, p)
        c.restore()
    }

    /** Стеклянное тело кнопки (.glass) */
    fun glass(c: Canvas, w: Float, h: Float, corner: Float, d: Float, off: Float, on: Float) {
        if (w <= 0f || h <= 0f) return
        val r = min(corner, h / 2f)
        val gl = NeonTheme.glowLevel()
        val hh = NeonTheme.getHue() + off
        val inA = 0.78f + on.coerceIn(0f, 1f) * 0.22f
        val c1 = NeonTheme.hsl(hh, 1f, 0.60f)
        val c2 = NeonTheme.hsl(hh + 50f, 1f, 0.62f)
        val path = roundPath(w, h, r)

        // тело
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(withA(0xFF2C3250.toInt(), 0.46f), withA(0xFF080914.toInt(), 0.66f)),
            null, Shader.TileMode.CLAMP
        )
        c.drawPath(path, p)

        c.save()
        c.clipPath(path)
        // свечение снизу внутри кнопки
        ellipse(
            c, w * 0.5f, h * 1.38f, w * 1.1f, h * 0.9f,
            intArrayOf(withA(c1, inA * gl), withA(c2, inA * 0.6f * gl), withA(c2, 0f), withA(c2, 0f)),
            floatArrayOf(0f, 0.42f, 0.74f, 1f)
        )
        // блик
        ellipse(
            c, w * 0.2f, 0f, w * 0.7f, h * 0.6f,
            intArrayOf(0x52FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.7f, 1f)
        )
        // верхний глянец
        p.reset()
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x2EFFFFFF, 0x05FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.4f, 0.56f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        c.restore()

        // цветной ободок
        val sw = 1.5f * d
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xE6FFFFFF.toInt(), c1, withA(c1, 0f), c2),
            floatArrayOf(0f, 0.36f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        p.alpha = 217
        rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
        val rr = max(r - sw / 2f, 0f)
        c.drawRoundRect(rect, rr, rr, p)

        // тонкая белая кромка
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x24FFFFFF
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, r, r, p)
    }

    /** Неоновая рамка панели: свечение снаружи (в пределах padding родителя), внутри и сама линия. */
    fun frame(c: Canvas, w: Float, h: Float, corner: Float, d: Float) {
        if (w <= 0f || h <= 0f) return
        val r = min(corner, min(w, h) / 2f)
        val hue = NeonTheme.getHue()
        val k = 0.35f + 0.65f * NeonTheme.brightness.coerceIn(0f, 1f)
        val glow = NeonTheme.hsl(hue, 1f, 0.58f)
        val path = roundPath(w, h, r)

        c.save()
        c.clipOutPath(path)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        for (i in 1..5) {
            p.strokeWidth = i * 3.2f * d
            p.color = withA(glow, 0.13f * k)
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
        }
        c.restore()

        c.save()
        c.clipPath(path)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        for (i in 1..4) {
            p.strokeWidth = i * 10f * d
            p.color = withA(glow, 0.10f * k)
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
        }
        c.restore()

        val sw = 2f * d
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = withA(NeonTheme.hsl(hue, 1f, 0.62f), k)
        rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
        c.drawRoundRect(rect, max(r - sw / 2f, 0f), max(r - sw / 2f, 0f), p)
    }

    /** Фон панели: тёмное стекло */
    fun panelBg(c: Canvas, w: Float, h: Float, corner: Float) {
        val r = min(corner, min(w, h) / 2f)
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xEC0C0E22.toInt(), 0xF5060712.toInt()), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
    }

    private fun bolt(
        c: Canvas, rnd: Random, x0: Float, y0: Float, x1: Float, y1: Float,
        amp: Float, segs: Int, lw: Float, lc: Float, glow: Int, core: Int
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = max(hypot(dx, dy), 1f)
        val nx = -dy / len
        val ny = dx / len
        val path = Path()
        path.moveTo(x0, y0)
        for (i in 1 until segs) {
            val t = i / segs.toFloat()
            val o = (rnd.nextFloat() - 0.5f) * amp
            path.lineTo(x0 + dx * t + nx * o, y0 + dy * t + ny * o)
        }
        path.lineTo(x1, y1)
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = lw
        p.color = glow
        c.drawPath(path, p)
        p.strokeWidth = lc
        p.color = core
        c.drawPath(path, p)
    }

    /** Электрический разряд при нажатии: дуги от точки касания, кольцо-вспышка, искры. */
    fun zap(c: Canvas, w: Float, h: Float, d: Float, ox: Float, oy: Float, t: Float, seed: Int) {
        val sec = t * 0.5f
        val glowC = NeonTheme.hsl(NeonTheme.getHue(), 1f, 0.60f)
        val coreC = 0xF7F5FFEB.toInt()
        val frame = Random(seed * 7919 + (t * 24f).toInt())
        val fade = max(0f, 1f - sec / 0.38f)
        if (fade > 0f) {
            val tr = Random(seed)
            repeat(4) {
                val a = tr.nextFloat() * 6.283f
                val tx = w / 2f + cos(a) * (w / 2f - 3f * d)
                val ty = h / 2f + sin(a) * (h / 2f - 3f * d)
                if (frame.nextFloat() >= 0.25f) {
                    bolt(c, frame, ox, oy, tx, ty, 16f * d, 8, 4f * d, 1.3f * d, withA(glowC, 0.4f * fade), withA(coreC, 0.97f * fade))
                }
            }
        }
        val ringA = max(0f, 1f - sec / 0.3f)
        if (ringA > 0f) {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = withA(glowC, 0.8f * ringA)
            c.drawCircle(ox, oy, (6f + sec * 240f) * d, p)
        }
        val sr = Random(seed * 31 + 7)
        repeat(12) {
            val a = sr.nextFloat() * 6.283f
            val sp = 70f + sr.nextFloat() * 120f
            val life = 0.2f + sr.nextFloat() * 0.3f
            if (sec < life) {
                fun px(s: Float): Float = ox + cos(a) * sp * ((1f - exp(-3.7f * s)) / 3.7f) * d
                fun py(s: Float): Float = oy + (sin(a) * sp * ((1f - exp(-3.7f * s)) / 3.7f) + 60f * s * s) * d
                val s0 = max(0f, sec - 0.03f)
                val al = min(1f, (life - sec) * 4f)
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = 2.4f * d
                p.color = withA(glowC, 0.5f * al)
                c.drawLine(px(s0), py(s0), px(sec), py(sec), p)
                p.strokeWidth = 1f * d
                p.color = withA(coreC, al)
                c.drawLine(px(s0), py(s0), px(sec), py(sec), p)
            }
        }
    }
}

/**
 * Стеклянная кнопка. Наследует Button, поэтому весь прежний код (text, isEnabled, setOnClickListener,
 * setOnTouchListener) работает без изменений. Состояние «включено» — isOn (свечение сильнее).
 */
open class NeonGlassButton(context: Context) : Button(context) {

    var hueOffset: Float = 0f
        set(v) { field = v; invalidate() }

    /** Радиус скругления в dp (999 = таблетка). */
    var cornerDp: Float = 999f
        set(v) { field = v; invalidate() }

    var isOn: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            animateOn(if (v) 1f else 0f)
        }

    protected var onT = 0f
    protected val d = context.resources.displayMetrics.density
    private var onAnim: ValueAnimator? = null
    private var zapAnim: ValueAnimator? = null
    private var zapT = 1f
    private var zapSeed = 0
    private var zapX = 0f
    private var zapY = 0f
    private val redraw = Runnable { invalidate() }

    init {
        background = null
        stateListAnimator = null
        isAllCaps = false
        setTextColor(0xFFF4F6FC.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        includeFontPadding = false
        setPadding(context.dpi(6f), 0, context.dpi(6f), 0)
        setShadowLayer(context.dpf(3f), 0f, context.dpf(1f), 0x66000000)
        setWillNotDraw(false)
    }

    private fun animateOn(target: Float) {
        onAnim?.cancel()
        onAnim = ValueAnimator.ofFloat(onT, target).apply {
            duration = 250L
            addUpdateListener { onT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Мгновенно выставить «включено» без анимации (при построении окна). */
    fun setOnInstant(v: Boolean) {
        onAnim?.cancel()
        isOn = v
        onT = if (v) 1f else 0f
        invalidate()
    }

    fun startZap(x: Float, y: Float) {
        zapX = x
        zapY = y
        zapSeed++
        zapAnim?.cancel()
        zapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            interpolator = LinearInterpolator()
            addUpdateListener { zapT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isEnabled) startZap(ev.x, ev.y)
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(120L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Хук для наследников: рисуется поверх стекла, под текстом. */
    protected open fun drawExtras(canvas: Canvas) {}

    override fun onDraw(canvas: Canvas) {
        GlassDraw.glass(canvas, width.toFloat(), height.toFloat(), cornerDp * d, d, hueOffset, onT)
        drawExtras(canvas)
        super.onDraw(canvas)
        if (zapT < 1f) {
            canvas.save()
            canvas.clipPath(GlassDraw.roundPath(width.toFloat(), height.toFloat(), min(cornerDp * d, height / 2f)))
            GlassDraw.zap(canvas, width.toFloat(), height.toFloat(), d, zapX, zapY, zapT, zapSeed)
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NeonTheme.addListener(redraw)
    }

    override fun onDetachedFromWindow() {
        NeonTheme.removeListener(redraw)
        onAnim?.cancel()
        zapAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Переключатель: стеклянная плашка с дорожкой и ручкой слева, подпись справа. */
class NeonGlassToggle(context: Context) : NeonGlassButton(context) {

    var onCheckedChange: ((Boolean) -> Unit)? = null
    private var knobT = 0f
    private var knobAnim: ValueAnimator? = null

    var checked: Boolean = false
        set(v) {
            field = v
            isOn = v
            knobAnim?.cancel()
            knobAnim = ValueAnimator.ofFloat(knobT, if (v) 1f else 0f).apply {
                duration = 250L
                addUpdateListener { knobT = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        cornerDp = 22f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(context.dpi(14f + 52f + 12f), 0, context.dpi(10f), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setOnClickListener {
            checked = !checked
            onCheckedChange?.invoke(checked)
        }
    }

    /** Выставить состояние сразу, без анимации и без вызова слушателя. */
    fun setCheckedSilently(v: Boolean) {
        knobAnim?.cancel()
        knobT = if (v) 1f else 0f
        setOnInstant(v)
        invalidate()
    }

    override fun drawExtras(canvas: Canvas) {
        val trackW = 52f * d
        val trackH = 30f * d
        val left = 14f * d
        val top = (height - trackH) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = NeonTheme.getHue()
        p.shader = if (knobT > 0.5f) {
            LinearGradient(0f, top, 0f, top + trackH, NeonTheme.hsl(hue, 0.9f, 0.38f), NeonTheme.hsl(hue, 0.9f, 0.52f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, top, 0f, top + trackH, 0xCC0A0B16.toInt(), 0x99141628.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(left, top, left + trackW, top + trackH, trackH / 2f, trackH / 2f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x24FFFFFF
        canvas.drawRoundRect(left, top, left + trackW, top + trackH, trackH / 2f, trackH / 2f, p)
        val kr = 11f * d
        val kx = left + 4f * d + kr + knobT * (trackW - 8f * d - 2f * kr)
        val ky = top + trackH / 2f
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(kx - kr * 0.3f, ky - kr * 0.35f, kr * 1.4f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFEEF0FF.toInt(), 0xFFC6C9EA.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(kx, ky, kr, p)
    }
}

/** Стеклянный слайдер: «вода» в канавке, стеклянная ручка и окошко со значением. */
class NeonGlassSlider(context: Context) : View(context) {

    var min = 0
    var max = 100
    var value = 0
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var formatter: (Int) -> String = { it.toString() }
    var onChange: ((Int) -> Unit)? = null

    /** true, пока палец на слайдере (окно настроек в это время не перерисовывается). */
    var dragging = false
        private set

    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val redraw = Runnable { invalidate() }

    init {
        minimumHeight = context.dpi(52f)
    }

    private fun grooveLeft() = 16f * d
    private fun grooveRight() = width - 74f * d

    private fun fromX(x: Float) {
        val gl = grooveLeft()
        val gw = grooveRight() - gl
        if (gw <= 0f) return
        val pp = ((x - gl) / gw).coerceIn(0f, 1f)
        val nv = (min + pp * (max - min)).roundToInt()
        if (nv != value) {
            value = nv
            onChange?.invoke(nv)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = context.dpi(52f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                fromX(e.x)
            }
            MotionEvent.ACTION_MOVE -> fromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        GlassDraw.glass(c, w, h, 999f * d, d, 0f, 0f)
        val hue = NeonTheme.getHue()
        val frac = if (max > min) (value - min).toFloat() / (max - min) else 0f
        val gl = grooveLeft()
        val gr = grooveRight()
        val gh = 16f * d
        val gt = (h - gh) / 2f

        // канавка
        p.reset(); p.isAntiAlias = true
        p.color = 0x73000000
        c.drawRoundRect(gl, gt, gr, gt + gh, gh / 2f, gh / 2f, p)
        // вода
        val fw = (gr - gl) * frac
        if (fw > 1f) {
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(
                0f, gt, 0f, gt + gh,
                intArrayOf(NeonTheme.hsl(hue, 0.95f, 0.64f), NeonTheme.hsl(hue, 0.90f, 0.42f), NeonTheme.hsl(hue, 0.90f, 0.30f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRoundRect(gl, gt, gl + fw, gt + gh, gh / 2f, gh / 2f, p)
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(0f, gt + 2f * d, 0f, gt + 2f * d + gh * 0.36f, 0xA6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            if (fw > 12f * d) c.drawRoundRect(gl + 5f * d, gt + 2f * d, gl + fw - 5f * d, gt + 2f * d + gh * 0.36f, gh / 2f, gh / 2f, p)
        }
        // ручка
        val kr = 17f * d
        val kx = gl + fw
        val ky = h / 2f
        p.reset(); p.isAntiAlias = true
        p.color = NeonTheme.hsl(hue, 1f, 0.55f, 0.35f * NeonTheme.glowLevel())
        c.drawCircle(kx, ky, kr * 1.3f, p)
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(
            kx - kr * 0.3f, ky - kr * 0.4f, kr * 1.5f,
            intArrayOf(0xF2FFFFFF.toInt(), NeonTheme.hsl(hue, 1f, 0.80f, 0.85f), NeonTheme.hsl(hue, 0.9f, 0.50f, 0.8f), NeonTheme.hsl(hue, 0.9f, 0.30f, 0.92f)),
            floatArrayOf(0f, 0.22f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(kx, ky, kr, p)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0x8CFFFFFF.toInt()
        c.drawCircle(kx, ky, kr - d, p)
        // окошко значения
        val cw = 52f * d
        val ch = 28f * d
        val cl = w - 12f * d - cw
        val ct = (h - ch) / 2f
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, ct, 0f, ct + ch, 0x38FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(cl, ct, cl + cw, ct + ch, 11f * d, 11f * d, p)
        p.reset(); p.isAntiAlias = true
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 12f * d
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        c.drawText(formatter(value), cl + cw / 2f, ct + ch / 2f + 4.2f * d, p)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NeonTheme.addListener(redraw)
    }

    override fun onDetachedFromWindow() {
        NeonTheme.removeListener(redraw)
        super.onDetachedFromWindow()
    }
}

/** Панель с тёмным стеклом и неоновой рамкой. Дочерние элементы лежат внутри. */
open class NeonPanel(context: Context, private val cornerDp: Float = 22f) : LinearLayout(context) {
    private val d = context.resources.displayMetrics.density
    private val redraw = Runnable { invalidate() }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onDraw(canvas: Canvas) {
        GlassDraw.panelBg(canvas, width.toFloat(), height.toFloat(), cornerDp * d)
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        GlassDraw.frame(canvas, width.toFloat(), height.toFloat(), cornerDp * d, d)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NeonTheme.addListener(redraw)
    }

    override fun onDetachedFromWindow() {
        NeonTheme.removeListener(redraw)
        super.onDetachedFromWindow()
    }
}

/** Мелкая подпись секции (.lbl) */
internal fun neonLabel(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(0xFF98A2BF.toInt())
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.06f
    setPadding(context.dpi(4f), context.dpi(8f), 0, context.dpi(4f))
}

/** Фабрика: стеклянная кнопка с текстом. */
internal fun glassButton(
    context: Context,
    label: String,
    heightDp: Float = 40f,
    cornerDp: Float = 16f,
    hueOffset: Float = 0f,
    on: Boolean = false,
    textSp: Float = 12f,
    onClick: () -> Unit
): NeonGlassButton = NeonGlassButton(context).apply {
    text = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
    this.cornerDp = cornerDp
    this.hueOffset = hueOffset
    setOnInstant(on)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(heightDp))
    setOnClickListener { onClick() }
}
