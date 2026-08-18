package blue.anika.wolle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LightColors =
    lightColorScheme(
        primary = StricknaniBerry40,
        secondary = StricknaniSage40,
        tertiary = StricknaniMustard40,
        error = StricknaniError40,
        background = StricknaniLightBackground,
        surface = StricknaniLightSurface,
        surfaceContainer = StricknaniLightSurfaceContainer,
    )

internal val DarkColors =
    darkColorScheme(
        primary = StricknaniBerry80,
        secondary = StricknaniSage80,
        tertiary = StricknaniMustard80,
        error = StricknaniError80,
        background = StricknaniDarkBackground,
        surface = StricknaniDarkSurface,
        surfaceContainer = StricknaniDarkSurfaceContainer,
    )

/** A deliberately varied shape scale: expressive cards are soft, while controls stay compact. */
internal val ExpressiveShapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )

internal val ExpressiveTypography =
    Typography()
        .copy(
            displayLarge = Typography().displayLarge.copy(fontWeight = FontWeight.Bold),
            headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = Typography().labelLarge.copy(letterSpacing = 0.1.sp),
        )

/**
 * Full Material You theming: dynamic, wallpaper-derived color on Android 12+, falling back to a
 * warm, yarn/craft-inspired hand-picked palette on older devices (see Color.kt). `darkTheme`
 * defaults to following the system setting; `MainActivity` overrides it from
 * `AppPreferencesRepository.themeMode` (SNA-18's light/dark/auto toggle) when the user picked
 * something other than "system".
 */
@Composable
fun StricknaniTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
