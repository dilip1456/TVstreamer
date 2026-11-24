package tv.tvstreamer

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One text setup for the whole app (1.75). Material's default style gave every Text a 24 sp line height and 0.5 sp
 * letter spacing: the 42 sp hero title overlapped on two lines and small text looked loosely spaced. Now: one sans
 * family, no extra letter spacing, line height from the font itself. Sizes stay with [Ts].
 */
object AppType {
    private fun TextStyle.fix() = copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified)

    val typography: Typography by lazy {
        val d = Typography()
        d.copy(
            displayLarge = d.displayLarge.fix(), displayMedium = d.displayMedium.fix(), displaySmall = d.displaySmall.fix(),
            headlineLarge = d.headlineLarge.fix(), headlineMedium = d.headlineMedium.fix(), headlineSmall = d.headlineSmall.fix(),
            titleLarge = d.titleLarge.fix(), titleMedium = d.titleMedium.fix(), titleSmall = d.titleSmall.fix(),
            bodyLarge = d.bodyLarge.fix(), bodyMedium = d.bodyMedium.fix(), bodySmall = d.bodySmall.fix(),
            labelLarge = d.labelLarge.fix(), labelMedium = d.labelMedium.fix(), labelSmall = d.labelSmall.fix()
        )
    }
}

/**
 * Menu and button labels used emoji and symbols as icons ("▶", "🌐", "⇅", "↺" ...). Each device draws those from a
 * different fallback font, so they never matched the text. [plain] drops such a leading symbol (a check mark stays:
 * it shows a state); screens that need an icon draw a vector icon instead.
 */
object Glyphs {
    private val LEAD = Regex("^[^\\p{L}\\p{N}\"'(✓]{1,4}\\s+")
    fun plain(s: String): String = LEAD.replace(s, "")
}
