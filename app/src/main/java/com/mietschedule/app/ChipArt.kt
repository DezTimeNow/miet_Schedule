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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Микросхема для мини-игры: корпус, выводы, маркировка.
 *
 * Рисуется кодом, а не картинкой, по двум причинам. Первая — картинка
 * растягивается мыльно на экранах разной плотности, а микросхема должна
 * оставаться резкой: на ней 14 выводов, и размытые окончания читаются как
 * грязь. Вторая — рисунок обязан реагировать на нажатие, то есть иметь
 * состояние; у статичного PNG для этого нужен был бы второй файл.
 *
 * ПОЧЕМУ ВЫВОДЫ РАСПОЛОЖЕНЫ ИМЕННО ТАК. Настоящая микросхема в корпусе
 * DIP-28 имеет по 7 выводов на каждой длинной стороне и ничего на коротких.
 * Прежняя версия рисовала 3 вывода сверху-снизу и 4 слева-справа, причём
 * все они сходились в одной точке корпуса — получался крест в середине
 * микросхемы, а не выводы по краям. Владелец назвал рисунок кривым, и
 * это был не каприз: геометрию можно проверить, и она не сходилась
 * (все выводы попадали в координаты 200,178 — центр корпуса).
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

    /** Рисует один вывод: прямоугольник, торчащий наружу от стороны. */
    private fun DrawScope.pin(
        center: Offset,
        width: Float,
        length: Float,
        vertical: Boolean,
    ) {
        drawRoundRect(
            color = pinColor,
            topLeft = Offset(
                center.x - (if (vertical) width else length) / 2f,
                center.y - (if (vertical) length else width) / 2f,
            ),
            size = Size(
                if (vertical) width else length,
                if (vertical) length else width,
            ),
            cornerRadius = CornerRadius(width / 2.4f),
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

        // Габариты корпуса. Соотношение 1.45:1 — настоящая микросхема в
        // DIP-корпусе, а не квадрат. Корпус занимает большую часть холста:
        // раньше он был 74 % ширины, и рисунок выглядел мелким и тесным.
        val chipW = w * 0.80f
        val chipH = min(chipW / 1.45f, h * 0.62f)
        val cx = w / 2f
        val cy = h / 2f

        // Нажатие слегка уменьшает корпус: палец «вдавливает» его.
        val pressScale = 1f - 0.022f * press
        val bodyW = chipW * pressScale
        val bodyH = chipH * pressScale
        val left = cx - bodyW / 2f
        val top = cy - bodyH / 2f
        val radius = bodyH * 0.055f

        // ── выводы ──────────────────────────────────────────────────────
        // По 7 на каждой длинной стороне, перпендикулярно стороне и с
        // одинаковым вылетом наружу. Отступ от углов не даёт первому
        // выводу слипнуться со скруглением корпуса.
        val pinW = bodyW * 0.030f        // ширина вывода вдоль стороны
        val pinOut = bodyH * 0.115f      // насколько вывод торчит наружу
        val margin = bodyW * 0.115f      // отступ от угла до первого вывода
        val span = bodyW - 2f * margin
        val step = span / (PINS_PER_SIDE - 1)

        // Нижние и верхние выводы: центр находится снаружи корпуса, тело
        // вывода заходит под его край — поэтому рисуем до корпуса.
        for (i in 0 until PINS_PER_SIDE) {
            val x = left + margin + step * i

            // Сверху: вывод уходит вверх от корпуса.
            pin(Offset(x, top - pinOut / 2f), pinW, pinOut, vertical = true)
            // Снизу: зеркально.
            pin(Offset(x, top + bodyH + pinOut / 2f), pinW, pinOut, vertical = true)
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
            topLeft = Offset(left + bodyW * 0.045f, top + bodyH * 0.075f),
            size = Size(bodyW * 0.91f, bodyH * 0.85f),
            cornerRadius = CornerRadius(radius * 0.62f),
            style = Stroke(width = 1.6f),
        )

        // Угловая метка «пин 1» — вырез в левом верхнем углу. На настоящей
        // микросхеме она есть, и без неё рисунок неузнаваем.
        val notch = bodyH * 0.055f
        drawCircle(
            color = bodyBottom.copy(alpha = 0.55f),
            radius = notch,
            center = Offset(left + radius * 0.85f, top + radius * 0.85f),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.20f),
            radius = notch * 0.60f,
            center = Offset(left + radius * 0.85f, top + radius * 0.85f),
        )

        // ── маркировка ──────────────────────────────────────────────────
        // Метка микросхемы и надпись. Шрифт фиксированный по высоте
        // корпуса, а не по экрану: иначе на планшете надпись разрастается.
        val labelLayout = measurer.measure(
            text = chipLabel,
            style = TextStyle(
                fontSize = (bodyH * 0.235f).sp,
                fontWeight = FontWeight.Bold,
                color = silkscreen.copy(alpha = 0.92f),
                letterSpacing = 1.sp,
            ),
        )
        val markY = cy - labelLayout.size.height * 1.45f
        drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(cx - labelLayout.size.width / 2f, markY),
        )

        val subLayout = measurer.measure(
            text = "ТАПНИ",
            style = TextStyle(
                fontSize = (bodyH * 0.095f).sp,
                fontWeight = FontWeight.Medium,
                color = silkscreen.copy(alpha = 0.55f),
                letterSpacing = 2.sp,
            ),
        )
        drawText(
            textLayoutResult = subLayout,
            topLeft = Offset(cx - subLayout.size.width / 2f, cy + bodyH * 0.20f),
        )

        // Штрих «заземления» под маркировкой — как на корпусе.
        val barW = bodyW * 0.26f
        val barY = cy + bodyH * 0.055f
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
                startY = top + bodyH * 0.09f,
                endY = top + bodyH * 0.44f,
            ),
            topLeft = Offset(left + bodyW * 0.055f, top + bodyH * 0.09f),
            size = Size(bodyW * 0.89f, bodyH * 0.35f),
            cornerRadius = CornerRadius(bodyH * 0.05f),
        )
    }

    companion object {
        /** Метка на корпусе. */
        const val CHIP_LABEL = "МИЭТ"

        /**
         * Выводов на длинной стороне.
         *
         * На корпусе DIP-28 их 14 на стороне, но при ширине экрана
         * 320 px семь выводов читаются, а четырнадцать сливаются в
         * полосу. Значение вынесено в константу, потому что на нём держится
         * геометрия рисунка: шаг между выводами считается из него, и
         * расхождение с кодом даст выводы, наезжающие друг на друга.
         */
        const val PINS_PER_SIDE = 7
    }
}
