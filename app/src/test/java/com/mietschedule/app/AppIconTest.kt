package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Иконка приложения.
 *
 * Требование владельца: свой рисунок на белом фоне вместо прежнего значка.
 * Исходник — изображение 7087x7087 с прозрачностью, логотип вписан в круг.
 *
 * Проверяется не «файл существует», а состав, от которого зависит
 * отображение: белый фон, логотип отдельным слоем и наличие растров во всех
 * плотностях. Пропущенная плотность не видна в коде — иконка просто
 * размывается на части устройств.
 */
class AppIconTest {

    private val resDir = File("src/main/res")

    private fun pngSize(f: File): Pair<Int, Int> {
        // Ширина и высота лежат в заголовке PNG: 8 байт сигнатуры, затем
        // длина блока, тип IHDR, и уже за ними — размеры.
        val b = f.readBytes()
        fun intAt(off: Int) =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
        return intAt(16) to intAt(20)
    }

    @Test
    fun `фон иконки белый`() {
        val colors = File(resDir, "values/colors.xml").readText()
        assertTrue(
            "Фон иконки должен быть белым: требование владельца — «на белом фоне лого»",
            Regex("""<color name="ic_launcher_background">#FFFFFF</color>""")
                .containsMatchIn(colors),
        )
    }

    @Test
    fun `адаптивная иконка берёт логотип отдельным слоем`() {
        val xml = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(
            "Фон адаптивной иконки должен ссылаться на цвет",
            xml.contains("@color/ic_launcher_background"),
        )
        assertTrue(
            "Логотип должен лежать отдельным слоем, иначе маска обрежет его вместе с фоном",
            xml.contains("@mipmap/ic_launcher_fg"),
        )
    }

    /**
     * Растры есть во всех плотностях и нужного размера.
     *
     * Логотип ужат до 86dp из 108dp: под круглой маской края круга иначе
     * срезаются. Проверяем и наличие, и размер — файл с чужим размером
     * компилируется и выглядит сломанным.
     */
    @Test
    fun `растры иконки есть во всех плотностях`() {
        val dens = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
        for ((name, k) in dens) {
            val dir = File(resDir, "mipmap-$name")
            assertTrue("нет каталога mipmap-$name", dir.isDirectory)

            val fg = File(dir, "ic_launcher_fg.png")
            assertTrue("нет foreground для mipmap-$name", fg.isFile)
            val (fw, fh) = pngSize(fg)
            val expect = Math.round(108 * k).toInt()
            assertEquals("неверный размер foreground в mipmap-$name", expect, fw)
            assertEquals("foreground в mipmap-$name не квадратный", fw, fh)

            for (fn in listOf("ic_launcher.png", "ic_launcher_round.png")) {
                assertTrue("нет $fn в mipmap-$name", File(dir, fn).isFile)
            }
        }
    }
}