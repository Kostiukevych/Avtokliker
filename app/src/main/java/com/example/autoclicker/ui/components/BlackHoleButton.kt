package com.example.autoclicker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Кнопка в виде чёрной дыры (аккреционный диск + ядро).
 * [showPlay] true = треугольник ▶, false = квадрат ■.
 */
@Composable
fun BlackHoleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    showPlay: Boolean = true,
    enabled: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "bh")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .drawBehind {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val r = this.size.minDimension / 2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x66FF8A2A),
                            Color(0x33FF5A14),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = r * 1.15f * pulse
                    ),
                    radius = r * 1.05f * pulse,
                    center = Offset(cx, cy)
                )

                rotate(-14f, Offset(cx, cy)) {
                    scale(1f, 0.28f, Offset(cx, cy)) {
                        rotate(angle, Offset(cx, cy)) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFFB43A0A),
                                        Color(0xFFFF7A18),
                                        Color(0xFFFFD27A),
                                        Color(0xFFFFF6E6),
                                        Color(0xFFFFD27A),
                                        Color(0xFFFF7A18),
                                        Color(0xFF4A1200),
                                        Color(0xFFB43A0A)
                                    ),
                                    center = Offset(cx, cy)
                                ),
                                radius = r * 0.92f,
                                center = Offset(cx, cy),
                                style = Stroke(width = r * 0.22f, cap = StrokeCap.Butt)
                            )
                        }
                    }
                }

                rotate(-angle * 0.62f, Offset(cx, cy)) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF7A18),
                                Color(0xFFFFD27A),
                                Color(0xFFFFF6E6),
                                Color(0xFFFFD27A),
                                Color(0xFFFF7A18),
                                Color(0xFFB43A0A),
                                Color(0xFFFF7A18)
                            ),
                            center = Offset(cx, cy)
                        ),
                        radius = r * 0.42f,
                        center = Offset(cx, cy),
                        style = Stroke(width = r * 0.06f)
                    )
                }

                drawCircle(color = Color.Black, radius = r * 0.36f, center = Offset(cx, cy))
                drawCircle(
                    color = Color(0xCCFFE2B8),
                    radius = r * 0.36f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x55FF8A2A), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r * 0.5f
                    ),
                    radius = r * 0.5f,
                    center = Offset(cx, cy)
                )

                val iconColor = Color(0xFFFFF6E6)
                if (showPlay) {
                    val path = Path().apply {
                        val s = r * 0.18f
                        moveTo(cx - s * 0.55f, cy - s)
                        lineTo(cx + s * 0.95f, cy)
                        lineTo(cx - s * 0.55f, cy + s)
                        close()
                    }
                    drawPath(path, iconColor)
                } else {
                    val s = r * 0.22f
                    drawRect(
                        color = iconColor,
                        topLeft = Offset(cx - s, cy - s),
                        size = Size(s * 2f, s * 2f)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {}
}
