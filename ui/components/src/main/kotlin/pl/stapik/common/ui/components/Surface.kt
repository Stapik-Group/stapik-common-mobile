package pl.stapik.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.stapik.common.ui.theme.StapikColors

/** The 3D border of the classic themes: light on the top and left when [raised], dark on the bottom and right. */
fun Modifier.bevel(raised: Boolean, light: Color, dark: Color, thickness: Dp = 2.dp): Modifier = this.drawBehind {
    val stroke = thickness.toPx()
    val topLeft = if (raised) light else dark
    val bottomRight = if (raised) dark else light
    drawLine(topLeft, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
    drawLine(topLeft, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
    drawLine(bottomRight, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
    drawLine(bottomRight, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
}

/**
 * A box in the look of the current theme: a bevel in the classic themes, a rounded card with a thin
 * border and a soft shadow in the others.
 */
fun Modifier.themedSurface(colors: StapikColors, background: Color, raised: Boolean = true): Modifier =
    if (colors.usesBevel) {
        this.background(background).bevel(raised, colors.borderLight, colors.borderDark)
    } else {
        val shape = RoundedCornerShape(colors.cornerRadius)
        this
            .shadow(elevation = if (raised) 2.dp else 0.dp, shape = shape)
            .clip(shape)
            .background(background)
            .border(1.dp, colors.borderLight, shape)
    }
