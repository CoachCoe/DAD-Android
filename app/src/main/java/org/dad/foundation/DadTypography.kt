package org.dad.foundation

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.dad.foundation.generated.DADTokens

/**
 * DAD display typeface. Space Grotesk (SIL OFL) is bundled as `res/font` statics and carries the
 * display/headline styles and the figure styles used for amounts and streak days; body/title/label
 * stay on the platform default (Roboto). Registered via `R.font` + [FontFamily], not a Gradle
 * dependency.
 */
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private fun text(step: String): androidx.compose.ui.unit.TextUnit = (DADTokens.text.getValue(step)).sp

/**
 * The app Typography. Only display/headline styles are re-typed in Space Grotesk — these cover the
 * figure styles the UI uses for amounts and streak days (gift stepper `displaySmall`, impact/given and
 * streak `headlineMedium`). Font sizes come from [DADTokens.text] where a style maps cleanly; the rest
 * of each style (line height, letter spacing) keeps the Material default. body/title/label are left
 * untouched so running copy stays on Roboto.
 */
@Composable
fun dadTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = text("3xl")),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = text("3xl")),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = text("2xl")),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = text("2xl")),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = text("xl")),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = text("lg")),
    )
}
