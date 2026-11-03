package blue.anika.wolle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val LightColors =
    lightColorScheme(
        primary = Raspberry,
        onPrimary = Color.White,
        primaryContainer = RaspberryContainer,
        onPrimaryContainer = OnRaspberryContainer,
        secondary = Lagoon,
        onSecondary = Color.White,
        secondaryContainer = LagoonContainer,
        onSecondaryContainer = OnLagoonContainer,
        tertiary = Marigold,
        onTertiary = Color.White,
        tertiaryContainer = MarigoldContainer,
        onTertiaryContainer = OnMarigoldContainer,
        error = BerryError,
        errorContainer = BerryErrorContainer,
        background = LilacMist,
        onBackground = PlumInk,
        surface = LilacMist,
        onSurface = PlumInk,
        onSurfaceVariant = PlumInkMuted,
        surfaceVariant = LilacContainerHigh,
        surfaceContainerLowest = LilacContainerLowest,
        surfaceContainerLow = LilacContainerLow,
        surfaceContainer = LilacContainer,
        surfaceContainerHigh = LilacContainerHigh,
        surfaceContainerHighest = LilacContainerHighest,
        outline = LilacOutline,
        outlineVariant = LilacOutlineVariant,
    )

internal val DarkColors =
    darkColorScheme(
        primary = RaspberryNight,
        onPrimary = OnRaspberryContainer,
        primaryContainer = RaspberryNightContainer,
        onPrimaryContainer = OnRaspberryNightContainer,
        secondary = LagoonNight,
        onSecondary = OnLagoonContainer,
        secondaryContainer = LagoonNightContainer,
        onSecondaryContainer = OnLagoonNightContainer,
        tertiary = MarigoldNight,
        onTertiary = OnMarigoldContainer,
        tertiaryContainer = MarigoldNightContainer,
        onTertiaryContainer = OnMarigoldNightContainer,
        error = BerryErrorNight,
        errorContainer = BerryErrorNightContainer,
        background = MidnightPlum,
        onBackground = MoonInk,
        surface = MidnightPlum,
        onSurface = MoonInk,
        onSurfaceVariant = MoonInkMuted,
        surfaceVariant = PlumContainerHigh,
        surfaceContainerLowest = PlumContainerLowest,
        surfaceContainerLow = PlumContainerLow,
        surfaceContainer = PlumContainer,
        surfaceContainerHigh = PlumContainerHigh,
        surfaceContainerHighest = PlumContainerHighest,
        outline = PlumOutline,
        outlineVariant = PlumOutlineVariant,
    )

/** Soft, pillowy shapes: cards are generously round, controls rounder still. */
internal val StricknaniShapes =
    Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )

/**
 * Stricknani's own look - the hand-dyed palette in Color.kt, Fraunces and Nunito (Type.kt) and soft
 * shapes. [dynamicColor] switches to the wallpaper-derived Material You palette instead (an opt-in
 * in Settings -> Appearance, Android 12+ only). `darkTheme` follows the system unless the user
 * picked light or dark (SNA-18).
 */
@Composable
fun StricknaniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StricknaniTypography,
        shapes = StricknaniShapes,
        content = content,
    )
}
