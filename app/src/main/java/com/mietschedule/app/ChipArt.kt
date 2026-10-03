package com.mietschedule.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Микросхема для мини-игры: корпус, выводы, маркировка.
 *
 * Рисуется кодом, а не картинкой, по двум причинам. Первая — картинка
 * растягивается мыльно на экранах разной плотности, а микросхема должна
 * оставаться резкой: на ней 28 выводов, и размытые окончания читаются как
 * грязь. Вторая — рисунок обязан реагировать на нажатие, то есть иметь
 * состояние; у статичного PNG для этого нужен был бы второй файл.
 *
 * Все размеры считаются от габаритов холста, поэтому корпус занимает
 * одинаковую долю экрана и на 5", и на складном.
 */
class ChipArt(
    private val measurer: TextMeasurer,
    private val bodyTop: Color,
    private val bodyBottom: Color,
    private val pinColor: Color,
    private val silkscreen: Color,
    private val highlight: Color,
) {
    /**
     * Один вывод: скруглённый прямоугольник.
     *
     * Выводы по краям повёрнуты — рисуются повёрнутым прямоугольником
     * вокруг точки. Кегль скругления равен половине толщины, иначе
     * выводы выглядят срезанными.
     */
    /** Рисует один вывод микросхемы. */
    private fun DrawScope.pin(
        center: Offset,
        length: Float,
        thickness: Float,
        vertical: Boolean,
    ) {
        drawRoundRect(
            color = pinColor,
            topLeft = Offset(
                center.x - (if (vertical) thickness else length) / 2f,
                center.y - (if (vertical) length else thickness) / 2f,
            ),
            size = Size(
                if (vertical) thickness else length,
                if (vertical) length else thickness,
            ),
            cornerRadius = CornerRadius(thickness / 2.6f),
        )
    }

    /**
     * @param pulse 0…1 — вспышка при нажатии: 1 в момент тапа, 0 в покое.
     * @param press 0…1 — нажатость корпуса: чем больше, тем глубже корпус.
     */
    fun DrawScope.draw(
        glow: Float,
        pulse: Float,
        press: Float,
        chipLabel: String,
    ) {
        val w = size.width
        val h = size.height

        // Габариты корпуса. Держим соотношение 1.55:1 — настоящая
        // микросхема в DIP-корпусе, а не квадрат.
        val chipW = w * 0.74f
        val chipH = min(chipW / 1.55f, h * 0.60f)
        val cx = w / 2f
        val cy = h / 2f

        // Нажатие слегка уменьшает корпус: палец «вдавливает» его.
        val pressScale = 1f - 0.022f * press
        val bodyW = chipW * pressScale
        val bodyH = chipH * pressScale
        val left = cx - bodyW / 2f
        val top = cy - bodyH / 2f
        val radius = bodyH * 0.08f

        // ── выводы ──────────────────────────────────────────────────────
        // Три сверху и снизу, четыре слева и справа — как у DIP-28.
        // Рисуются до корпуса: вывод уходит под его край.
        val t = bodyH * 0.055f          // толщина вывода
        val lenV = bodyH * 0.20f        // длина вертикальных
        val lenH = bodyW * 0.075f       // длина боковых
        val inset = bodyH * 0.055f      // отступ от края корпуса

        for (i in 0 until 3) {
            val y = top + bodyH / 4f * (i + 1)
            pin(Offset(cx, y - lenV / 2f - inset), lenV, t, vertical = true)
            pin(Offset(cx, y + lenV / 2f + inset), lenV, t, vertical = true)
        }
        for (i in 0 until 4) {
            val x = left + bodyW / 5f * (i + 1)
            pin(Offset(x - lenH / 2f - inset, cy), lenH, t, vertical = false)
            pin(Offset(x + lenH / 2f + inset, cy), lenH, t, vertical = false)
        }

        // ── свечение при нажатии ────────────────────────────────────────
        if (pulse > 0.01f) {
            val ringColor = highlight.copy(alpha = 0.42f * pulse)
            // Три концентрических кольца: ближнее ярче. Без них вспышка
            // читается как сплошная заливка, а не как удар.
            for (k in 0 until 3) {
                val grow = 1f + 0.16f * k + 0.22f * (1f - pulse)
                drawRoundRect(
                    color = ringColor.copy(alpha = ringColor.alpha * (1f - k * 0.28f)),
                    topLeft = Offset(left - grow * 6f, top - grow * 6f),
                    size = Size(bodyW + grow * 12f, bodyH + grow * 12f),
                    cornerRadius = CornerRadius(radius + grow * 6f),
                    style = Stroke(width = 2.5f),
                )
            }
        }

        // ── корпус ──────────────────────────────────────────────────────
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    bodyTop.copy(alpha = 0.95f + 0.05f * pulse),
                    bodyBottom,
                ),
                startY = top,
                endY = top + bodyH,
            ),
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(radius),
        )

        // Утопленный прямоугольник: корпус микросхемы не плоский, а с
        // литьём. Одна линия даёт объём без градиента.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(left + bodyW * 0.055f, top + bodyH * 0.06f),
            size = Size(bodyW * 0.89f, bodyH * 0.88f),
            cornerRadius = CornerRadius(radius * 0.72f),
            style = Stroke(width = 1.6f),
        )

        // Угловая метка «пин 1» — вырез в левом верхнем углу. На настоящей
        // микросхеме она есть, и без неё рисунок неузнаваем.
        val notch = bodyH * 0.13f
        drawCircle(
            color = bodyBottom.copy(alpha = 0.55f),
            radius = notch,
            center = Offset(left + radius * 0.9f, top + radius * 0.9f),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.20f),
            radius = notch * 0.62f,
            center = Offset(left + radius * 0.9f, top + radius * 0.9f),
        )

        // ── маркировка ──────────────────────────────────────────────────
        // Метка микросхемы и надпись. Шрифт фиксированный по высоте
        // корпуса, а не по экрану: иначе на планшете надпись разрастается.
        val labelLayout = measurer.measure(
            text = chipLabel,
            style = TextStyle(
                fontSize = (bodyH * 0.20f).sp,
                fontWeight = FontWeight.Bold,
                color = silkscreen.copy(alpha = 0.92f),
                letterSpacing = 1.sp,
            ),
        )
        val markY = cy - labelLayout.size.height * 1.55f
        drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(cx - labelLayout.size.width / 2f, markY),
        )

        val subLayout = measurer.measure(
            text = "ТАПНИ",
            style = TextStyle(
                fontSize = (bodyH * 0.105f).sp,
                fontWeight = FontWeight.Medium,
                color = silkscreen.copy(alpha = 0.55f),
                letterSpacing = 2.sp,
            ),
        )
        drawText(
            textLayoutResult = subLayout,
            topLeft = Offset(cx - subLayout.size.width / 2f, cy + bodyH * 0.19f),
        )

        // Штрих «заземления» под маркировкой — как на корпусе.
        val barW = bodyW * 0.34f
        val barY = cy + bodyH * 0.06f
        val path = Path().apply {
            moveTo(cx - barW / 2f, barY)
            lineTo(cx + barW / 2f, barY)
        }
        // pathEffect — часть Stroke, а не отдельный аргумент drawPath.
        drawPath(
            path = path,
            color = silkscreen.copy(alpha = 0.32f),
            style = Stroke(
                width = 2f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.cornerPathEffect(2f),
            ),
        )

        // Блик: узкая светлая полоса поперёк корпуса. Она даёт объём
        // матовому пластику и стоит одного прямоугольника.
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.02f),
                ),
                startY = top + bodyH * 0.08f,
                endY = top + bodyH * 0.46f,
            ),
            topLeft = Offset(left + bodyH * 0.10f, top + bodyH * 0.08f),
            size = Size(bodyW - bodyH * 0.20f, bodyH * 0.38f),
            cornerRadius = CornerRadius(bodyH * 0.06f),
        )
    }

    companion object {
        /** Метка на корпусе. */
        const val CHIP_LABEL = "МИЭТ"
    }
}