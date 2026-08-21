package blue.anika.wolle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

/** Compact heading styles for Markdown embedded in cards and detail sections. */
internal data class MarkdownHeadingStyles(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
)

/**
 * The renderer's Material 3 defaults use display styles for Markdown headings. Those styles are
 * intended for hero content and make an h1 much larger than the surrounding card hierarchy. Keep
 * the levels distinct while using the app's compact title/body scale instead.
 */
internal fun markdownHeadingStyles(typography: Typography): MarkdownHeadingStyles =
    MarkdownHeadingStyles(
        h1 = typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        h2 = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        h3 = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        h4 = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        h5 = typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        h6 = typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )

/** The shared Markdown typography used by descriptions, notes, stitch samples, and steps. */
@Composable
internal fun stricknaniMarkdownTypography(): MarkdownTypography {
    val headings = markdownHeadingStyles(MaterialTheme.typography)
    return markdownTypography(
        h1 = headings.h1,
        h2 = headings.h2,
        h3 = headings.h3,
        h4 = headings.h4,
        h5 = headings.h5,
        h6 = headings.h6,
    )
}
