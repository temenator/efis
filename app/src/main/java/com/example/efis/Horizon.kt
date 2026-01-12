package com.example.efis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI

@Composable
fun Horizon(
    rollDeg: Float,
    pitchDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) / 2f

        // Круглая маска (чтобы прибор был круглым)
        val circle = Path().apply {
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }

        clipPath(circle) {
            // pitch: смещение горизонта по вертикали
            // калибровка: 45° ~= 0.8 * radius
            val pitchRad = pitchDeg * (PI.toFloat() / 180f)
            val pitchPx = (-pitchRad / (PI.toFloat() / 4f)) * (radius * 0.8f)

            // Вращаем "мир" на -roll (самолёт держим ровно)
            rotate(-rollDeg, pivot = Offset(cx, cy)) {

                // Небо
                drawRect(
                    color = Color(0xFF2E6BE6),
                    topLeft = Offset(cx - radius, cy - radius + pitchPx),
                    size = Size(radius * 2f, radius * 2f)
                )

                // Земля
                drawRect(
                    color = Color(0xFF8B5A2B),
                    topLeft = Offset(cx - radius, cy + pitchPx),
                    size = Size(radius * 2f, radius * 2f)
                )

                // Линия горизонта
                drawLine(
                    color = Color.White,
                    start = Offset(cx - radius, cy + pitchPx),
                    end = Offset(cx + radius, cy + pitchPx),
                    strokeWidth = 4f
                )

                // Риски pitch каждые 10°
                for (d in -30..30 step 10) {
                    if (d == 0) continue
                    val y = cy + pitchPx + (-d / 45f) * (radius * 0.8f)
                    val len = if (d % 20 == 0) radius * 0.55f else radius * 0.35f
                    drawLine(
                        color = Color.White,
                        start = Offset(cx - len / 2f, y),
                        end = Offset(cx + len / 2f, y),
                        strokeWidth = 3f
                    )
                }
            }
        }

        // Рамка прибора
        drawCircle(
            color = Color.White,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 6f)
        )

        // Фиксированный "самолётик"
        drawLine(
            color = Color.Yellow,
            start = Offset(cx - radius * 0.35f, cy),
            end = Offset(cx + radius * 0.35f, cy),
            strokeWidth = 6f
        )
        drawLine(
            color = Color.Yellow,
            start = Offset(cx, cy - radius * 0.10f),
            end = Offset(cx, cy + radius * 0.10f),
            strokeWidth = 6f
        )
    }
}
