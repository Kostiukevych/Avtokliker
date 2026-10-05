package com.example.autoclicker.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================================
//  Liquid Glass + Neon: порт HTML-макета liquid-glass-autoclicker.html на Jetpack Compose.
//  Публичные имена (GlassPanel, GlassButton, GlassPointButton, GlassSlider, GlassSwitch,
//  LiquidReservoir, GlassDialog, liquidGlassBackground, LocalNeonHue, LocalNeonBrightness)
//  сохранены, чтобы остальные файлы приложения продолжали компилироваться.
// =====================================================================================

/** Текущий оттенок неона (обновляется ~5 раз/с, читается только в фазе рисования). */
val LocalNeonHue = compositionLocalOf<State<Float>> { mutableFloatStateOf(NeonTheme.BASE_HUE) }

/** Часы анимаций в секундах (~30 кадров/с, только пока приложение видно на экране). */
val LocalNeonClock = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/** Яркость неона 0..1. */
val LocalNeonBrightness = compositionLocalOf { 0.85f }

/**
 * Включает неон для всего вложенного UI. Анимации крутятся ТОЛЬКО пока Activity в состоянии STARTED —
 * когда игра на переднем плане, приложение не тратит батарею на невидимые анимации.
 */
@Composable
fun ProvideNeon(brightness: Float, content: @Composable () -> Unit) {
    val hue = remember { mutableFloatStateOf(NeonTheme.getHue()) }
    val clock = remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val t0 = withFrameNanos { it }
            var last = t0
            var lastHue = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (now - last >= 32_000_000L) {
                    last = now
                    clock.floatValue = ((now - t0) / 1_000_000L).toFloat() / 1000f
                    if (now - lastHue >= 200_000_000L) {
                        lastHue = now
                        hue.floatValue = NeonTheme.getHue()
                    }
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalNeonHue provides hue,
        LocalNeonClock provides clock,
        LocalNeonBrightness provides brightness,
        content = content
    )
}

// ---------------------------------------------------------------------------------
//  Вспомогательное
// ---------------------------------------------------------------------------------

/** Цвет в HSL: hue + offset, как hsl(var(--hh) ...) в CSS. */
internal fun nc(hue: Float, off: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color(NeonTheme.hsl(hue + off, s, l, a))

internal fun glowOf(bri: Float): Float = 0.2f + 0.8f * bri.coerceIn(0f, 1f)

/** Эллиптический радиальный градиент (CSS: radial-gradient(rx ry at cx cy, ...)). */
private fun DrawScope.ellipse(cx: Float, cy: Float, rx: Float, ry: Float, stops: Array<Pair<Float, Color>>) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({ scale(1f, ry / rx, Offset(cx, cy)) }) {
        drawCircle(
            brush = Brush.radialGradient(*stops, center = Offset(cx, cy), radius = rx),
            radius = rx,
            center = Offset(cx, cy)
        )
    }
}

private fun roundPath(w: Float, h: Float, r: Float): Path =
    Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r))) }

/** Фон страницы: тёмный #080a18 + синее и фиолетовое пятна, как body в HTML. */
fun Modifier.liquidGlassBackground(): Modifier = drawBehind {
    drawRect(Color(0xFF080A18))
    val blue = Color(0xFF263D96)
    val violet = Color(0xFF5D2B8C)
    ellipse(
        size.width * 0.15f, size.height * 0.24f, size.width * 0.75f, size.height * 0.38f,
        arrayOf(0f to blue, 0.7f to blue.copy(alpha = 0f), 1f to blue.copy(alpha = 0f))
    )
    ellipse(
        size.width * 0.88f, size.height * 0.82f, size.width * 0.75f, size.height * 0.42f,
        arrayOf(0f to violet, 0.7f to violet.copy(alpha = 0f), 1f to violet.copy(alpha = 0f))
    )
}

// ---------------------------------------------------------------------------------
//  Неоновая рамка (.nfx): отдельный слой, яркость = прозрачность, дышащее гало
// ---------------------------------------------------------------------------------
@Composable
fun NeonFrame(cornerRadius: Dp, modifier: Modifier = Modifier) {
    val hue = LocalNeonHue.current
    val clock = LocalNeonClock.current
    val bri = LocalNeonBrightness.current
    Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            val r = min(cornerRadius.toPx(), min(w, h) / 2f)
            val cr = CornerRadius(r, r)
            val hh = hue.value
            val k = 0.35f + 0.65f * bri.coerceIn(0f, 1f)
            val breathe = 0.75f + 0.25f * sin(clock.value * (2f * PI.toFloat() / 6f))
            val glow = nc(hh, 0f, 1f, 0.58f)
            val path = roundPath(w, h, r)
            val d = density

            // внешнее свечение (только снаружи)
            clipPath(path, clipOp = ClipOp.Difference) {
                for (i in 1..7) {
                    drawRoundRect(
                        glow.copy(alpha = (0.10f * k).coerceIn(0f, 1f)),
                        size = size, cornerRadius = cr,
                        style = Stroke(width = i * 10f * d)
                    )
                }
                for (i in 1..4) {
                    drawRoundRect(
                        glow.copy(alpha = (0.045f * k * breathe).coerceIn(0f, 1f)),
                        size = size, cornerRadius = cr,
                        style = Stroke(width = (50f + i * 14f) * d)
                    )
                }
            }
            // внутреннее свечение
            clipPath(path) {
                for (i in 1..5) {
                    drawRoundRect(
                        glow.copy(alpha = (0.09f * k).coerceIn(0f, 1f)),
                        size = size, cornerRadius = cr,
                        style = Stroke(width = i * 11f * d)
                    )
                }
            }
            // сама линия неона
            val sw = 2f * d
            drawRoundRect(
                nc(hh, 0f, 1f, 0.62f, k.coerceIn(0f, 1f)),
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(w - sw, h - sw),
                cornerRadius = CornerRadius(max(r - sw / 2f, 0f)),
                style = Stroke(sw)
            )
        }
    )
}

// ---------------------------------------------------------------------------------
//  Стекло (.glass): тело, внутреннее свечение, блики, цветной ободок
// ---------------------------------------------------------------------------------
fun Modifier.glassSurface(
    hue: State<Float>,
    bri: Float,
    off: Float = 0f,
    on: () -> Float = { 0f },
    corner: Dp = 999.dp
): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    val d = density
    val r = min(corner.toPx(), h / 2f)
    val cr = CornerRadius(r, r)
    val gl = glowOf(bri)
    val hh = hue.value + off
    val o = on().coerceIn(0f, 1f)
    val inA = 0.78f + o * 0.22f
    val outA = 0.70f + o * 0.25f
    val c1 = nc(hh, 0f, 1f, 0.60f)
    val c2 = nc(hh, 50f, 1f, 0.62f)
    val path = roundPath(w, h, r)

    // тень и цветное свечение под кнопкой (только снаружи)
    clipPath(path, clipOp = ClipOp.Difference) {
        drawRoundRect(Color.Black.copy(alpha = 0.32f), Offset(w * 0.07f, h * 0.16f), Size(w * 0.86f, h * 0.95f), cr)
        ellipse(
            w * 0.5f, h * 1.02f, w * 0.46f, h * 0.62f,
            arrayOf(0f to c1.copy(alpha = outA * gl * 0.60f), 1f to c1.copy(alpha = 0f))
        )
    }

    // тело стекла
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF2C3250).copy(alpha = 0.46f), Color(0xFF080914).copy(alpha = 0.66f))
        ),
        cornerRadius = cr
    )

    clipPath(path) {
        // свечение внутри снизу
        ellipse(
            w * 0.5f, h * 1.38f, w * 1.1f, h * 0.9f,
            arrayOf(
                0f to c1.copy(alpha = (inA * gl).coerceIn(0f, 1f)),
                0.42f to c2.copy(alpha = (inA * 0.6f * gl).coerceIn(0f, 1f)),
                0.74f to c2.copy(alpha = 0f),
                1f to c2.copy(alpha = 0f)
            )
        )
        // блик слева сверху
        ellipse(
            w * 0.2f, 0f, w * 0.7f, h * 0.6f,
            arrayOf(0f to Color.White.copy(alpha = 0.32f), 0.7f to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = 0f))
        )
        // верхний глянец
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.18f),
                0.4f to Color.White.copy(alpha = 0.02f),
                0.56f to Color.White.copy(alpha = 0f),
                1f to Color.White.copy(alpha = 0f)
            )
        )
    }

    // цветной ободок 1.5dp
    val sw = 1.5f * d
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.9f),
            0.36f to c1,
            0.58f to c1.copy(alpha = 0f),
            1f to c2,
            start = Offset(0f, 0f),
            end = Offset(w, h)
        ),
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius(max(r - sw / 2f, 0f)),
        alpha = 0.85f,
        style = Stroke(sw)
    )
    // тонкая белая кромка
    drawRoundRect(
        Color.White.copy(alpha = 0.14f),
        topLeft = Offset(0.5f, 0.5f),
        size = Size(w - 1f, h - 1f),
        cornerRadius = cr,
        style = Stroke(1f)
    )
}

// ---------------------------------------------------------------------------------
//  Электрический разряд при нажатии
// ---------------------------------------------------------------------------------
private class ZapState {
    var tick by mutableIntStateOf(0)
    var origin: Offset = Offset.Zero
}

private fun Modifier.zapOnPress(z: ZapState): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        z.origin = down.position
        z.tick = z.tick + 1
    }
}

private fun DrawScope.drawBolt(
    rnd: Random, x0: Float, y0: Float, x1: Float, y1: Float,
    amp: Float, segs: Int, lw: Float, lc: Float, glow: Color, core: Color
) {
    val dx = x1 - x0
    val dy = y1 - y0
    val len = max(hypot(dx, dy), 1f)
    val nx = -dy / len
    val ny = dx / len
    val p = Path()
    p.moveTo(x0, y0)
    for (i in 1 until segs) {
        val t = i / segs.toFloat()
        val o = (rnd.nextFloat() - 0.5f) * amp
        p.lineTo(x0 + dx * t + nx * o, y0 + dy * t + ny * o)
    }
    p.lineTo(x1, y1)
    drawPath(p, glow, style = Stroke(width = lw, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    drawPath(p, core, style = Stroke(width = lc, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
}

@Composable
private fun BoxScope.ZapOverlay(z: ZapState, hue: State<Float>) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(z.tick) {
        if (z.tick > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(500, easing = LinearEasing))
        }
    }
    Canvas(Modifier.matchParentSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val d = density
        val sec = t * 0.5f
        val w = size.width
        val h = size.height
        val pad = 18f * d
        val glow = nc(hue.value, 0f, 1f, 0.60f)
        val core = Color(0xFFF5FFEB)
        val o = z.origin
        val frame = Random(z.tick * 7919 + (t * 24f).toInt())
        clipRect(-pad, -pad, w + pad, h + pad) {
            val fade = max(0f, 1f - sec / 0.38f)
            if (fade > 0f) {
                val tr = Random(z.tick)
                repeat(4) {
                    val a = tr.nextFloat() * 6.283f
                    val tx = w / 2f + cos(a) * (w / 2f - 3f * d)
                    val ty = h / 2f + sin(a) * (h / 2f - 3f * d)
                    if (frame.nextFloat() >= 0.25f) {
                        drawBolt(frame, o.x, o.y, tx, ty, 16f * d, 8, 4f * d, 1.3f * d, glow.copy(alpha = 0.4f * fade), core.copy(alpha = 0.97f * fade))
                    }
                }
            }
            val ringA = max(0f, 1f - sec / 0.3f)
            if (ringA > 0f) {
                drawCircle(glow.copy(alpha = 0.8f * ringA), radius = (6f + sec * 240f) * d, center = o, style = Stroke(2f * d))
            }
            val sr = Random(z.tick * 31 + 7)
            repeat(12) {
                val a = sr.nextFloat() * 6.283f
                val sp = 70f + sr.nextFloat() * 120f
                val life = 0.2f + sr.nextFloat() * 0.3f
                if (sec < life) {
                    fun pos(s: Float): Offset {
                        val k = (1f - kotlin.math.exp(-3.7f * s)) / 3.7f
                        return Offset(o.x + cos(a) * sp * k * d, o.y + (sin(a) * sp * k + 60f * s * s) * d)
                    }
                    val p0 = pos(max(0f, sec - 0.03f))
                    val p1 = pos(sec)
                    val al = min(1f, (life - sec) * 4f)
                    drawLine(glow.copy(alpha = 0.5f * al), p0, p1, strokeWidth = 2.4f * d, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
                    drawLine(core.copy(alpha = al), p0, p1, strokeWidth = 1f * d, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
//  Электрические пузырьки (аквариум и полоски слайдеров)
// ---------------------------------------------------------------------------------
class FxConfig(
    val rMin: Float, val rMax: Float,
    val vMin: Float, val vMax: Float,
    val drift: Float,
    val zapMin: Float, val zapMax: Float,
    val arc: Float, val arcAmp: Float,
    val lw: Float, val lc: Float,
    val minCount: Int, val maxCount: Int, val perDp: Float
)

private val TankFx = FxConfig(
    rMin = 3.5f, rMax = 10f, vMin = 12f, vMax = 34f, drift = 7f,
    zapMin = 0.25f, zapMax = 1.35f, arc = 80f, arcAmp = 14f, lw = 4f, lc = 1.2f,
    minCount = 10, maxCount = 22, perDp = 38f
)
private val SliderFx = FxConfig(
    rMin = 2f, rMax = 4.6f, vMin = 7f, vMax = 17f, drift = 3f,
    zapMin = 0.3f, zapMax = 1.1f, arc = 34f, arcAmp = 7f, lw = 2.6f, lc = 0.9f,
    minCount = 8, maxCount = 30, perDp = 16f
)

private class Bubble {
    var x = 0f; var y = 0f; var r = 0f; var vy = 0f; var ph = 0f; var zap = 0f; var next = 0f
}

private class FxState {
    val bubs = ArrayList<Bubble>()
    var w = 0f
    var h = 0f
    var lastT = -1f
    val rnd = Random(System.nanoTime())

    fun spawn(b: Bubble, cfg: FxConfig, d: Float, init: Boolean) {
        val r = (cfg.rMin + rnd.nextFloat() * (cfg.rMax - cfg.rMin)) * d
        b.r = r
        b.x = rnd.nextFloat() * w
        b.y = if (init) rnd.nextFloat() * h else h + r + rnd.nextFloat() * 16f * d
        b.vy = (cfg.vMin + rnd.nextFloat() * (cfg.vMax - cfg.vMin)) * d
        b.ph = rnd.nextFloat() * 6.28f
        b.zap = 0f
        b.next = rnd.nextFloat()
    }

    fun advance(t: Float, width: Float, height: Float, d: Float, cfg: FxConfig, boost: Float) {
        w = width
        h = height
        val n = (width / d / cfg.perDp).roundToInt().coerceIn(cfg.minCount, cfg.maxCount)
        while (bubs.size < n) { val b = Bubble(); spawn(b, cfg, d, true); bubs.add(b) }
        while (bubs.size > n) bubs.removeAt(bubs.size - 1)
        val dt = if (lastT < 0f) 0f else (t - lastT).coerceIn(0f, 0.1f)
        lastT = t
        if (dt <= 0f) return
        for (b in bubs) {
            b.y -= b.vy * dt
            b.ph += dt * 1.6f
            b.x += sin(b.ph) * cfg.drift * d * dt
            if (b.y < -b.r - 2f) spawn(b, cfg, d, false)
            b.next -= dt
            if (b.next <= 0f) {
                b.zap = 0.08f + rnd.nextFloat() * 0.14f
                b.next = (cfg.zapMin + rnd.nextFloat() * (cfg.zapMax - cfg.zapMin)) / boost
            }
            if (b.zap > 0f) b.zap -= dt
        }
    }
}

private fun DrawScope.drawFx(fx: FxState, cfg: FxConfig, hue: Float, fillFrac: Float) {
    val fw = size.width * fillFrac.coerceIn(0f, 1f)
    if (fw < 1f) return
    val d = density
    val core = Color(0xF2F0FFE6)
    clipRect(0f, 0f, fw, size.height) {
        for (b in fx.bubs) {
            val z = b.zap > 0f
            val hr = b.r * (if (z) 3.4f else 2f)
            val c = Offset(b.x, b.y)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to nc(hue, 0f, 1f, 0.65f, if (z) 0.6f else 0.2f),
                    1f to nc(hue, 0f, 1f, 0.55f, 0f),
                    center = c, radius = hr
                ),
                radius = hr, center = c, blendMode = BlendMode.Plus
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.85f),
                    0.35f to nc(hue, 0f, 1f, 0.75f, 0.35f),
                    1f to nc(hue, 0f, 1f, 0.50f, 0.2f),
                    center = Offset(b.x - b.r * 0.35f, b.y - b.r * 0.4f), radius = b.r * 1.3f
                ),
                radius = b.r, center = c
            )
            drawCircle(Color.White.copy(alpha = 0.55f), radius = b.r, center = c, style = Stroke(0.8f * d))
        }
        val glow = nc(hue, 0f, 1f, 0.60f, 0.35f)
        val lw = cfg.lw * d
        val lc = cfg.lc * d
        val bubs = fx.bubs
        for (i in bubs.indices) {
            val a = bubs[i]
            if (a.zap > 0f) {
                val t1 = fx.rnd.nextFloat() * 6.283f
                val t2 = t1 + PI.toFloat() + (fx.rnd.nextFloat() - 0.5f) * 1.2f
                val k = a.r * 0.8f
                drawBolt(fx.rnd, a.x + cos(t1) * k, a.y + sin(t1) * k, a.x + cos(t2) * k, a.y + sin(t2) * k, a.r * 0.8f, 4, lw, lc, glow, core)
            }
            for (j in i + 1 until bubs.size) {
                val q = bubs[j]
                if (a.zap <= 0f && q.zap <= 0f) continue
                val dx = q.x - a.x
                val dy = q.y - a.y
                val dist = hypot(dx, dy)
                if (dist > cfg.arc * d || dist < a.r + q.r + 1f) continue
                val ux = dx / dist
                val uy = dy / dist
                drawBolt(
                    fx.rnd, a.x + ux * a.r, a.y + uy * a.r, q.x - ux * q.r, q.y - uy * q.r,
                    min(cfg.arcAmp * d, dist * 0.35f), 7, lw, lc, glow, core
                )
            }
        }
    }
}

@Composable
fun ElectricBubbles(
    modifier: Modifier,
    cfg: FxConfig,
    fill: () -> Float = { 1f },
    boost: () -> Float = { 1f }
) {
    val hue = LocalNeonHue.current
    val clock = LocalNeonClock.current
    val fx = remember { FxState() }
    Canvas(modifier) {
        val t = clock.value
        fx.advance(t, size.width, size.height, density, cfg, boost())
        drawFx(fx, cfg, hue.value, fill())
    }
}

// ---------------------------------------------------------------------------------
//  Панель (.panel) с неоновой рамкой
// ---------------------------------------------------------------------------------
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A0C1E).copy(alpha = 0.55f), Color(0xFF060712).copy(alpha = 0.70f))
                    )
                )
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 20.dp),
            content = content
        )
        NeonFrame(cornerRadius, Modifier.matchParentSize())
    }
}

/** Выпадающее меню (.menu): тёмная панель с неоновой рамкой. */
@Composable
fun GlassMenuPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF101228).copy(alpha = 0.97f), Color(0xFF080916).copy(alpha = 0.98f))
                    )
                )
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp),
            content = content
        )
        NeonFrame(28.dp, Modifier.matchParentSize())
    }
}

/** Подпись секции (.lbl) */
@Composable
fun GlassLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = Color(0xFF98A2BF),
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 8.dp)
    )
}

// ---------------------------------------------------------------------------------
//  Кнопка
// ---------------------------------------------------------------------------------
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOn: Boolean = false,
    height: Dp = 56.dp,
    cornerRadius: Dp = 999.dp,
    hueOffset: Float = 0f,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current
    val onAnim = animateFloatAsState(if (isOn) 1f else 0f, tween(250), label = "glassOn")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160), label = "glassScale")
    val zap = remember { ZapState() }
    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .glassSurface(hue, bri, hueOffset, { onAnim.value }, cornerRadius)
            .zapOnPress(zap)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
        ZapOverlay(zap, hue)
    }
}

/** Квадратная кнопка с иконкой (бургер, закрыть). */
@Composable
fun GlassSquareButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    content: @Composable () -> Unit
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier.width(size),
        height = size,
        cornerRadius = 18.dp,
        content = content
    )
}

/** Иконка «бургер» / «крестик». */
@Composable
fun BurgerIcon(open: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) {
        val s = 2.2f * density
        val c = Color(0xFFF4F6FC)
        val w = size.width
        val h = size.height
        if (open) {
            drawLine(c, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), s, StrokeCap.Round)
            drawLine(c, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), s, StrokeCap.Round)
        } else {
            for (y in listOf(0.29f, 0.5f, 0.71f)) {
                drawLine(c, Offset(w * 0.2f, h * y), Offset(w * 0.8f, h * y), s, StrokeCap.Round)
            }
        }
    }
}

/** Кнопка точки (1..10) — своя гамма цвета у каждой, как в HTML (--off = (i-1)*10). */
@Composable
fun GlassPointButton(
    id: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        isOn = enabled,
        height = 60.dp,
        cornerRadius = 26.dp,
        hueOffset = (id - 1) * 10f
    ) {
        Text("$id", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4F6FC))
    }
}

// ---------------------------------------------------------------------------------
//  Переключатель (.toggle)
// ---------------------------------------------------------------------------------
@Composable
fun GlassSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    height: Dp = 64.dp
) {
    val hue = LocalNeonHue.current
    val thumb by animateDpAsState(if (checked) 28.dp else 0.dp, tween(300), label = "swThumb")
    GlassButton(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        isOn = checked,
        height = height,
        cornerRadius = 28.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 66.dp, height = 38.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val brush = if (checked) {
                            Brush.verticalGradient(listOf(nc(hue.value, 0f, 0.9f, 0.38f), nc(hue.value, 0f, 0.9f, 0.52f)))
                        } else {
                            Brush.verticalGradient(listOf(Color(0xFF0A0B16).copy(alpha = 0.8f), Color(0xFF141628).copy(alpha = 0.6f)))
                        }
                        drawRect(brush)
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = 8f * density)
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = 4.dp + thumb, y = 4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                0f to Color.White,
                                0.45f to Color(0xFFEEF0FF),
                                1f to Color(0xFFC6C9EA)
                            )
                        )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    color = Color(0xFFF4F6FC),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 17.sp
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color(0xFFC3CADF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
//  Слайдер с «водой», бегущими полосками, электрическими пузырьками и стеклянной ручкой
// ---------------------------------------------------------------------------------
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    formattedValue: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val hue = LocalNeonHue.current
    val clock = LocalNeonClock.current
    val bri = LocalNeonBrightness.current
    val span = valueRange.endInclusive - valueRange.start
    val frac = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val currentOnChange by rememberUpdatedState(onValueChange)
    val startPad = 62.dp
    val endPad = 94.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .glassSurface(hue, bri, 0f, { 0f }, 999.dp)
            .pointerInput(valueRange, steps) {
                val sPx = startPad.toPx()
                val gPx = size.width - (startPad + endPad).toPx()
                fun update(x: Float) {
                    if (gPx <= 0f) return
                    val p = ((x - sPx) / gPx).coerceIn(0f, 1f)
                    val q = if (steps > 0) (p * (steps + 1)).roundToInt().toFloat() / (steps + 1) else p
                    val v = valueRange.start + q * (valueRange.endInclusive - valueRange.start)
                    currentOnChange(v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    update(down.position.x)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop != null) {
                        horizontalDrag(slop.id) { change ->
                            update(change.position.x)
                            change.consume()
                        }
                    }
                    dragging = false
                }
            }
    ) {
        val grooveW = maxWidth - startPad - endPad

        // иконка
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 18.dp)
                .size(34.dp),
            contentAlignment = Alignment.Center
        ) { icon() }

        // канавка с водой
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = startPad)
                .width(grooveW.coerceAtLeast(1.dp))
                .height(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            Canvas(Modifier.matchParentSize()) {
                val h = size.height
                val fw = size.width * frac
                val hh = hue.value
                // внутренняя тень канавки
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = 7f * density)
                )
                if (fw > 1f) {
                    val rr = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to nc(hh, 0f, 0.95f, 0.64f),
                            0.55f to nc(hh, 0f, 0.90f, 0.42f),
                            1f to nc(hh, 0f, 0.90f, 0.30f)
                        ),
                        size = Size(fw, h), cornerRadius = rr
                    )
                    // глянец
                    val gp = 6f * density
                    if (fw > gp * 2f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0f)),
                                startY = 2f * density, endY = 2f * density + h * 0.36f
                            ),
                            topLeft = Offset(gp, 2f * density),
                            size = Size(fw - gp * 2f, h * 0.36f),
                            cornerRadius = CornerRadius(h / 2f, h / 2f)
                        )
                    }
                    // бегущие полоски
                    clipPath(roundPath(fw, h, h / 2f)) {
                        val step = 20f * density
                        val shift = (clock.value * 25f * density) % step
                        var x = -step * 2f + shift
                        while (x < fw + step) {
                            drawLine(
                                Color.White.copy(alpha = 0.16f),
                                Offset(x, h), Offset(x + h * 0.27f, 0f),
                                strokeWidth = 7f * density
                            )
                            x += step
                        }
                    }
                }
            }
            ElectricBubbles(
                Modifier.matchParentSize(), SliderFx,
                fill = { frac },
                boost = { if (dragging) 3.5f else 1f }
            )
        }

        // ручка
        Canvas(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = startPad + grooveW * frac - 28.dp)
                .size(56.dp)
        ) {
            val hh = hue.value
            val c = center
            val r = size.minDimension / 2f
            drawCircle(nc(hh, 0f, 1f, 0.55f, 0.7f * glowOf(bri)), radius = r * 1.25f, center = c, blendMode = BlendMode.Plus)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.95f),
                    0.07f to Color.White.copy(alpha = 0.95f),
                    0.22f to nc(hh, 0f, 1f, 0.80f, 0.85f),
                    0.60f to nc(hh, 0f, 0.9f, 0.50f, 0.80f),
                    1f to nc(hh, 0f, 0.9f, 0.30f, 0.92f),
                    center = Offset(size.width * 0.34f, size.height * 0.28f), radius = r * 1.5f
                ),
                radius = r, center = c
            )
            drawCircle(Color.White.copy(alpha = 0.55f), radius = r - density, center = c, style = Stroke(2f * density))
        }

        // чип со значением
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .size(width = 66.dp, height = 38.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formattedValue,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------------
//  Аквариум наверху экрана (.tank)
// ---------------------------------------------------------------------------------
@Composable
fun LiquidReservoir(
    cycleDelayMinutes: Int,
    neonBrightness: Int,
    modifier: Modifier = Modifier
) {
    val hue = LocalNeonHue.current
    val clock = LocalNeonClock.current

    // уровень воды: 100% минус средняя заполненность слайдеров (логика приложения сохранена)
    val cycleFraction = ((cycleDelayMinutes - 1f) / 14f).coerceIn(0f, 1f)
    val neonFraction = (neonBrightness / 100f).coerceIn(0f, 1f)
    val level = (1f - (cycleFraction + neonFraction) / 2f).coerceIn(0f, 1f)
    val isAlert = level < 0.03f
    val shown by animateFloatAsState(level, tween(500), label = "tankLevel")

    Box(modifier.fillMaxWidth().height(78.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0B0D1C), Color(0xFF05060F))))
        ) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val hh = hue.value
                val d = density
                val bob = (sin(clock.value * (2f * PI.toFloat() / 10f)) + 1f) * 1.5f * d
                val top = h * (1f - shown) + bob
                if (top < h) {
                    val c1 = if (isAlert) Color(0xFFFF5252) else nc(hh, 0f, 0.85f, 0.52f)
                    val c2 = if (isAlert) Color(0xFFB00000) else nc(hh, 0f, 0.90f, 0.28f)
                    drawRect(Brush.verticalGradient(listOf(c1, c2), startY = top, endY = h), Offset(0f, top), Size(w, h - top))
                    // свечение над водой
                    val glowC = if (isAlert) Color(0xFFFF5252) else nc(hh, 0f, 1f, 0.55f)
                    drawRect(
                        Brush.verticalGradient(
                            listOf(glowC.copy(alpha = 0f), glowC.copy(alpha = 0.65f)),
                            startY = top - 16f * d, endY = top
                        ),
                        Offset(0f, top - 16f * d), Size(w, 16f * d)
                    )
                    // светлая кромка поверхности
                    drawRect(nc(hh, 0f, 1f, 0.82f, 0.85f), Offset(0f, top), Size(w, 1f * d))
                }
            }
            ElectricBubbles(Modifier.matchParentSize(), TankFx)
            if (isAlert) {
                Text(
                    "ПУСТО (<3%)",
                    color = Color(0xFFFF8A80),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        NeonFrame(32.dp, Modifier.matchParentSize())
    }
}

// ---------------------------------------------------------------------------------
//  Выбор цвета неона: автосмена + 36 цветов (как меню в HTML)
// ---------------------------------------------------------------------------------
@Composable
fun NeonColorPicker(modifier: Modifier = Modifier) {
    val hueState = LocalNeonHue.current
    var auto by remember { mutableStateOf(NeonTheme.isAuto) }
    val idx = (hueState.value / 10f).roundToInt() % NeonTheme.SWATCH_COUNT
    Column(modifier) {
        GlassSwitch(
            text = "Автосмена цвета",
            subtitle = "каждые 3 сек, плавно",
            checked = auto,
            onCheckedChange = {
                auto = it
                NeonTheme.setAuto(it)
            }
        )
        Spacer(Modifier.height(14.dp))
        for (row in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until 9) {
                    val i = row * 9 + col
                    NeonSwatch(
                        hue = i * 10f,
                        selected = i == idx,
                        onClick = {
                            NeonTheme.setHue(i * 10f)
                            auto = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NeonSwatch(hue: Float, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (selected) 1.14f else 1f, tween(150), label = "swatch")
    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val r = size.minDimension / 2f
                val base = nc(hue, 0f, 1f, 0.52f)
                drawCircle(base.copy(alpha = 0.35f), radius = r * 1.35f, blendMode = BlendMode.Plus)
                drawCircle(base, radius = r)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.75f),
                        0.45f to Color.White.copy(alpha = 0f),
                        1f to Color.White.copy(alpha = 0f),
                        center = Offset(size.width * 0.34f, size.height * 0.28f), radius = r
                    ),
                    radius = r
                )
                if (selected) {
                    drawCircle(Color.White, radius = r - density, style = Stroke(2f * density))
                    drawCircle(base, radius = r + 2f * density, style = Stroke(2f * density))
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    )
}

// ---------------------------------------------------------------------------------
//  Диалог
// ---------------------------------------------------------------------------------
@Composable
fun GlassDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "Готово",
    cancelText: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF03030A).copy(alpha = 0.60f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxH = maxHeight * 0.92f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .clip(RoundedCornerShape(30.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF101228).copy(alpha = 0.97f), Color(0xFF080916).copy(alpha = 0.98f))
                            )
                        )
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF1F4FB),
                            modifier = Modifier.weight(1f)
                        )
                        GlassSquareButton(onClick = onDismissRequest, size = 44.dp) {
                            BurgerIcon(open = true)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        content = content
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(onClick = onConfirm, modifier = Modifier.weight(1f), height = 52.dp, isOn = true) {
                            Text(confirmText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        GlassButton(onClick = onDismissRequest, modifier = Modifier.weight(1f), height = 52.dp, hueOffset = 30f) {
                            Text(cancelText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
                NeonFrame(30.dp, Modifier.matchParentSize())
            }
        }
    }
}
