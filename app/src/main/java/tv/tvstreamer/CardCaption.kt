package tv.tvstreamer

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Poster card caption sizes (1.97): explicit line heights so the title + year / rating lines are never clipped. */
object CardCaption {
    /** Line heights in sp (Material's default body style is 24 sp whatever the font size). */
    const val TITLE_LH = 17
    const val SUB_LH = 15
    /** Room for both lines plus a little slack for descenders, in sp. */
    const val TV_SP = TITLE_LH + SUB_LH + 4

    /** TV caption box: fixed (focus never re-lays out the row), sized in sp so a bigger system font still fits. */
    @Composable
    fun tvHeight(): Dp = with(LocalDensity.current) { TV_SP.sp.toDp() }.coerceAtLeast(36.dp)
}
