package com.example.flowave.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.flowave.ui.theme.CyanNeon
import com.example.flowave.ui.theme.PinkNeon
import com.example.flowave.ui.theme.PurpleNeon
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerType {
    WAVEFORM,
    BARS,
    CIRCULAR,
    OSCILLOSCOPE
}

@Composable
fun CanvasVisualizer(
    waveform: FloatArray,
    type: VisualizerType = VisualizerType.BARS,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val centerX = width / 2f

        when (type) {
            VisualizerType.BARS -> {
                val barCount = waveform.size.coerceAtMost(48)
                val barWidth = width / (barCount * 1.5f)
                val gap = barWidth * 0.5f

                for (i in 0 until barCount) {
                    val amp = waveform[i].coerceIn(0.05f, 1f)
                    val barHeight = height * 0.7f * amp
                    val x = i * (barWidth + gap) + gap
                    val y = centerY - (barHeight / 2f)

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(CyanNeon, PurpleNeon, PinkNeon)
                        ),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                    )
                }
            }

            VisualizerType.WAVEFORM -> {
                val path = Path()
                val stepX = width / (waveform.size - 1)
                path.moveTo(0f, centerY)

                for (i in waveform.indices) {
                    val x = i * stepX
                    val amp = waveform[i]
                    val y = centerY + (sin(i * 0.3) * amp * (height * 0.35f)).toFloat()
                    path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        colors = listOf(CyanNeon, PinkNeon)
                    ),
                    style = Stroke(width = 6f)
                )
            }

            VisualizerType.CIRCULAR -> {
                val radius = (width.coerceAtMost(height) * 0.3f)
                val count = waveform.size
                val angleStep = (2 * Math.PI / count).toFloat()

                for (i in 0 until count) {
                    val angle = i * angleStep
                    val amp = waveform[i]
                    val barLength = 20f + (amp * 80f)

                    val startX = centerX + (radius * cos(angle))
                    val startY = centerY + (radius * sin(angle))
                    val endX = centerX + ((radius + barLength) * cos(angle))
                    val endY = centerY + ((radius + barLength) * sin(angle))

                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(CyanNeon, PinkNeon)
                        ),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 5f
                    )
                }
            }

            VisualizerType.OSCILLOSCOPE -> {
                val path = Path()
                val count = waveform.size
                path.moveTo(centerX, centerY)

                for (i in 0 until count) {
                    val amp = waveform[i] * 120f
                    val angle = i * 0.2f
                    val x = centerX + (amp * cos(angle))
                    val y = centerY + (amp * sin(angle))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                drawPath(
                    path = path,
                    color = CyanNeon,
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}
