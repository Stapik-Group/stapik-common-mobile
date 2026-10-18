package pl.stapik.common.theme

import kotlin.math.pow

/** WCAG 2.x contrast ratio between two colours, from 1.0 to 21.0. */
object Contrast {
    fun ratio(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun luminance(c: Rgb): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)
    }
}
