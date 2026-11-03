package blue.anika.wolle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import blue.anika.wolle.R

/*
 * Two typefaces, both variable fonts bundled in res/font (SIL Open Font License, texts in
 * assets/licenses):
 *  - Fraunces for display, headline and title text, with its SOFT axis fully on and WONK on: a
 *    warm, slightly hand-made serif with rounded terminals - the voice of the app.
 *  - Nunito, a rounded sans, for body and labels, so long pattern text stays easy to read.
 */

private fun fraunces(weight: Int, opticalSize: Float) =
    Font(
        R.font.fraunces,
        weight = FontWeight(weight),
        variationSettings =
            FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.Setting("SOFT", 100f),
                FontVariation.Setting("WONK", 1f),
                FontVariation.Setting("opsz", opticalSize),
            ),
    )

private fun nunito(weight: Int) =
    Font(
        R.font.nunito,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

/** Large sizes use the display optical size, smaller headings the text one. */
internal val FrauncesDisplay = FontFamily(fraunces(600, 96f), fraunces(700, 96f))
internal val FrauncesTitle = FontFamily(fraunces(600, 24f), fraunces(700, 24f))
internal val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

private val base = Typography()

private fun TextStyle.display(weight: Int, tracking: Float = -0.01f) =
    copy(
        fontFamily = FrauncesDisplay,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking.em,
    )

private fun TextStyle.title(weight: Int) =
    copy(fontFamily = FrauncesTitle, fontWeight = FontWeight(weight), letterSpacing = 0.em)

private fun TextStyle.body(weight: Int = 400) =
    copy(fontFamily = Nunito, fontWeight = FontWeight(weight))

/**
 * German titles are full of long compound words ("Rollkragenpulli"): hyphenate instead of breaking
 * mid-word. Headings also balance their lines; body and label styles keep the simple line breaker,
 * because text fields use them while typing.
 */
private fun TextStyle.wraps(balanced: Boolean = false) =
    copy(
        hyphens = Hyphens.Auto,
        lineBreak = if (balanced) LineBreak.Heading else LineBreak.Simple,
    )

internal val StricknaniTypography =
    Typography(
        displayLarge = base.displayLarge.display(700, -0.02f).wraps(balanced = true),
        displayMedium = base.displayMedium.display(700, -0.02f).wraps(balanced = true),
        displaySmall = base.displaySmall.display(700).wraps(balanced = true),
        headlineLarge = base.headlineLarge.display(700).wraps(balanced = true),
        headlineMedium = base.headlineMedium.display(650).wraps(balanced = true),
        headlineSmall = base.headlineSmall.title(650).wraps(balanced = true),
        titleLarge = base.titleLarge.title(650).wraps(balanced = true),
        titleMedium = base.titleMedium.title(600).copy(fontSize = 17.sp).wraps(balanced = true),
        titleSmall = base.titleSmall.body(800).wraps(),
        bodyLarge = base.bodyLarge.body().copy(lineHeight = 25.sp).wraps(),
        bodyMedium = base.bodyMedium.body().copy(lineHeight = 21.sp).wraps(),
        bodySmall = base.bodySmall.body(500).wraps(),
        labelLarge = base.labelLarge.body(800).copy(letterSpacing = 0.02.em).wraps(),
        labelMedium = base.labelMedium.body(700).wraps(),
        labelSmall = base.labelSmall.body(700).wraps(),
    )
