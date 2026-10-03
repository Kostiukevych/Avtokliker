package com.example.autoclicker.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

val LocalNeonHue = compositionLocalOf { 0f }
val LocalNeonBrightness = compositionLocalOf { 0.85f }

/**
 * Тёмный фон страницы с мягкими радиальными градиентами (синий слева сверху, фиолетовый справа снизу).
 */
fun Modifier.liquidGlassBackground(): Modifier = drawBehind {
    // 1. Базовый тёмный градиент #0b0c16 -> #05050a
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0B0C16), Color(0xFF05050A)),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    )
    // 2. Синий радиальный градиент слева сверху (15% 10%)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF1B2250), Color.Transparent),
            center = Offset(size.width * 0.15f, size.height * 0.10f),
            radius = size.width.coerceAtLeast(size.height) * 0.75f
        )
    )
    // 3. Фиолетовый радиальный градиент справа снизу (90% 95%)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF3A1450), Color.Transparent),
            center = Offset(size.width * 0.90f, size.height * 0.95f),
            radius = size.width.coerceAtLeast(size.height) * 0.75f
        )
    )
}

/**
 * Неоновая рамка со свечением.
 * Отрисовывается через drawWithCache/drawBehind без повторной композиции дерева UI при смене цвета.
 */
fun Modifier.neonGlow(
    hueProvider: () -> Float,
    brightnessProvider: () -> Float,
    cornerRadius: Dp,
    strokeWidth: Dp = 2.5.dp,
    isAlert: Boolean = false
): Modifier = drawWithCache {
    val crPx = cornerRadius.toPx()
    val swPx = strokeWidth.toPx()

    onDrawWithContent {
        drawContent()

        val bri = brightnessProvider().coerceIn(0.1f, 1.5f)
        val hue = hueProvider()
        val baseColor = if (isAlert) Color(0xFFFF2020) else Color.hsv(hue, 1f, 1f)
        val mixedColor = if (isAlert) Color(0xFFFF4040) else lerp(baseColor, Color.White, 0.45f)

        // Широкое внешнее свечение
        drawRoundRect(
            color = baseColor.copy(alpha = (0.16f * bri).coerceIn(0f, 1f)),
            size = size,
            cornerRadius = CornerRadius(crPx, crPx),
            style = Stroke(width = swPx * 3.6f)
        )
        // Среднее свечение
        drawRoundRect(
            color = baseColor.copy(alpha = (0.32f * bri).coerceIn(0f, 1f)),
            size = size,
            cornerRadius = CornerRadius(crPx, crPx),
            style = Stroke(width = swPx * 2.0f)
        )
        // Основная неоновая обводка
        drawRoundRect(
            color = mixedColor.copy(alpha = (0.90f * bri).coerceIn(0f, 1f)),
            size = size,
            cornerRadius = CornerRadius(crPx, crPx),
            style = Stroke(width = swPx)
        )
        // Внутренний блик сверху
        drawRoundRect(
            color = Color.White.copy(alpha = 0.35f),
            topLeft = Offset(1f, 1f),
            size = Size(size.width - 2f, (size.height * 0.35f).coerceAtLeast(4f)),
            cornerRadius = CornerRadius(crPx, crPx),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * Стеклянная панель (GlassPanel) с неоновой рамкой.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current

    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.04f),
                        Color.White.copy(alpha = 0.12f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(400f, 600f)
                )
            )
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = cornerRadius,
                strokeWidth = 2.5.dp
            )
            .padding(14.dp),
        content = content
    )
}

/**
 * Стеклянная кнопка-таблетка (GlassButton).
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOn: Boolean = false,
    height: Dp = 56.dp,
    cornerRadius: Dp = 999.dp,
    content: @Composable () -> Unit
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current
    val shape = RoundedCornerShape(cornerRadius)

    val bgBrush = if (isOn) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFC8FF78).copy(alpha = 0.35f),
                Color(0xFF8CFF28).copy(alpha = 0.10f),
                Color(0xFFB4FF50).copy(alpha = 0.22f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.03f),
                Color.White.copy(alpha = 0.18f)
            )
        )
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bgBrush)
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = cornerRadius,
                strokeWidth = 2.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Кнопка точки (GlassPointButton) для сетки 5 в ряд.
 */
@Composable
fun GlassPointButton(
    id: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current
    val shape = RoundedCornerShape(999.dp)

    val bgBrush = if (enabled) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFC8FF78).copy(alpha = 0.35f),
                Color(0xFF8CFF28).copy(alpha = 0.10f),
                Color(0xFFB4FF50).copy(alpha = 0.22f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.03f),
                Color.White.copy(alpha = 0.18f)
            )
        )
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(bgBrush)
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = 999.dp,
                strokeWidth = 1.8.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$id",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8CFF00))
                    .drawBehind {
                        drawCircle(Color(0xFF8CFF00).copy(alpha = 0.8f), radius = size.width * 1.3f)
                    }
            )
        }
    }
}

/**
 * Стеклянный слайдер (GlassSlider) с иконкой, неоновым каналом и плавающим значением.
 */
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
    val bri = LocalNeonBrightness.current
    val outerShape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(outerShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.38f),
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.03f),
                        Color.White.copy(alpha = 0.18f)
                    )
                )
            )
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = 999.dp,
                strokeWidth = 2.dp
            )
            .padding(5.dp)
    ) {
        // Core background
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF12121A).copy(alpha = 0.75f), Color(0xFF08080C).copy(alpha = 0.9f))
                    )
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }

            // Slider
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF80D8FF),
                    activeTrackColor = Color(0xFF8CFF00),
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                )
            )

            // Value badge
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .width(58.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formattedValue,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Стеклянный переключатель (GlassSwitch).
 */
@Composable
fun GlassSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.25f)
                    )
                )
            )
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = 18.dp,
                strokeWidth = 1.8.dp
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        // Custom Toggle Pill Track
        val thumbOffset by animateFloatAsState(
            targetValue = if (checked) 20f else 0f,
            animationSpec = tween(durationMillis = 250),
            label = "switchThumb"
        )
        val trackBg = if (checked) {
            Brush.verticalGradient(listOf(Color(0xFF5A9A10), Color(0xFF8CFF00)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFF2B2B33), Color(0xFF4A4A55)))
        }

        Box(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(trackBg)
                .padding(2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffset.roundToInt(), 0) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFF4F5FF), Color(0xFFC9CDF2)))
                    )
            )
        }
    }
}

/**
 * Резервуар с водой и пузырьками вверху экрана (LiquidReservoir).
 */
@Composable
fun LiquidReservoir(
    cycleDelayMinutes: Int,
    neonBrightness: Int,
    modifier: Modifier = Modifier
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current

    // Вычисление уровня воды: 100% минус средняя заполненность слайдеров
    val cycleFraction = ((cycleDelayMinutes - 1f) / 14f).coerceIn(0f, 1f)
    val neonFraction = (neonBrightness / 100f).coerceIn(0f, 1f)
    val usedAverage = (cycleFraction + neonFraction) / 2f
    val level = (1f - usedAverage).coerceIn(0f, 1f)
    val isAlert = level < 0.03f

    val infiniteTransition = rememberInfiniteTransition(label = "bubbles")
    val bubblePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bubblePhase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.40f)
                    )
                )
            )
            .neonGlow(
                hueProvider = { hue },
                brightnessProvider = { bri },
                cornerRadius = 18.dp,
                strokeWidth = 2.5.dp,
                isAlert = isAlert
            )
            .padding(6.dp)
    ) {
        // Внутренний контейнер резервуара
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF121218), Color(0xFF07070C)))
                )
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()

            // Вода (салатовый градиент)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .align(Alignment.BottomCenter)
                    .background(
                        if (isAlert) {
                            Brush.verticalGradient(
                                listOf(Color(0xFFFF5252).copy(alpha = 0.6f), Color(0xFFD50000).copy(alpha = 0.8f))
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFFC8FF64).copy(alpha = 0.55f),
                                    Color(0xFF8CFF28).copy(alpha = 0.70f),
                                    Color(0xFF50C814).copy(alpha = 0.85f)
                                )
                            )
                        }
                    )
            )

            // Анимированные пузырьки (исчезают при level < 3%)
            if (!isAlert && level >= 0.03f) {
                val bubbleOffsets = listOf(0.1f, 0.22f, 0.35f, 0.48f, 0.62f, 0.75f, 0.88f)
                val bubbleSizes = listOf(4.dp, 7.dp, 5.dp, 8.dp, 4.dp, 6.dp, 5.dp)

                for (i in bubbleOffsets.indices) {
                    val xFraction = bubbleOffsets[i]
                    val bSize = bubbleSizes[i]
                    val speedOffset = (i * 0.17f) % 1f
                    val bProgress = (bubblePhase + speedOffset) % 1f
                    val bAlpha = (1f - bProgress) * 0.9f

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset {
                                IntOffset(
                                    x = (totalWidthPx * xFraction).roundToInt(),
                                    y = -(bProgress * totalHeightPx * level).roundToInt()
                                )
                            }
                            .size(bSize)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = bAlpha))
                    )
                }
            }

            // Индикатор уровня текстом
            Text(
                text = if (isAlert) "ПУСТО (<3%)" else "РЕЗЕРВУАР: ${(level * 100).toInt()}%",
                color = if (isAlert) Color(0xFFFF5252) else Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Стеклянный диалог с неоновой рамкой, крестиком закрытия и кнопками «Готово» / «Отмена».
 */
@Composable
fun GlassDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "Готово",
    cancelText: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit
) {
    val hue = LocalNeonHue.current
    val bri = LocalNeonBrightness.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF03030A).copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Окно диалога
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.24f),
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.16f)
                            )
                        )
                    )
                    .neonGlow(
                        hueProvider = { hue },
                        brightnessProvider = { bri },
                        cornerRadius = 28.dp,
                        strokeWidth = 2.5.dp
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Consume click
                    )
                    .padding(20.dp)
            ) {
                // Header: Заголовок и крестик
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // Кнопка ✕
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f))
                                )
                            )
                            .clickable(onClick = onDismissRequest),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Тело диалога
                content()

                Spacer(modifier = Modifier.height(16.dp))

                // Кнопки действий: Готово и Отмена
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        height = 46.dp,
                        isOn = true
                    ) {
                        Text(confirmText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    GlassButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        height = 46.dp
                    ) {
                        Text(cancelText, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
