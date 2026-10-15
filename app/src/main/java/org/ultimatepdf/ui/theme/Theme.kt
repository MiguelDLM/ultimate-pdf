package org.ultimatepdf.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

/**
 * Every role is set explicitly so Material never falls back to its default (purple) tones.
 * Primary is ink, containers are neutral; the blue accent lives in `tertiary`.
 */
private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = PaperRaised,
    primaryContainer = Stone100,
    onPrimaryContainer = Ink,
    inversePrimary = Fog,
    secondary = InkSoft,
    onSecondary = PaperRaised,
    secondaryContainer = Stone200,
    onSecondaryContainer = Ink,
    tertiary = Accent,
    onTertiary = PaperRaised,
    tertiaryContainer = AccentContainer,
    onTertiaryContainer = AccentInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Stone100,
    onSurfaceVariant = Stone600,
    surfaceTint = Paper,
    inverseSurface = Ink,
    inverseOnSurface = Fog,
    error = Danger,
    onError = PaperRaised,
    errorContainer = DangerContainer,
    onErrorContainer = DangerInk,
    outline = Stone500,
    outlineVariant = Stone200,
    scrim = Ink,
    surfaceBright = PaperRaised,
    surfaceDim = Stone100,
    surfaceContainerLowest = PaperRaised,
    surfaceContainerLow = PaperRaised,
    surfaceContainer = Stone50,
    surfaceContainerHigh = Stone100,
    surfaceContainerHighest = Stone200
)

private val DarkColors = darkColorScheme(
    primary = Fog,
    onPrimary = Night,
    primaryContainer = Night600,
    onPrimaryContainer = Fog,
    inversePrimary = Ink,
    secondary = Fog400,
    onSecondary = Night,
    secondaryContainer = Night500,
    onSecondaryContainer = Fog,
    tertiary = AccentDark,
    onTertiary = Night,
    tertiaryContainer = AccentContainerDark,
    onTertiaryContainer = AccentContainer,
    background = Night,
    onBackground = Fog,
    surface = Night,
    onSurface = Fog,
    surfaceVariant = Night700,
    onSurfaceVariant = Fog400,
    surfaceTint = Night,
    inverseSurface = Fog,
    inverseOnSurface = Night,
    error = DangerDark,
    onError = Night,
    errorContainer = DangerContainerDark,
    onErrorContainer = DangerContainer,
    outline = Fog400,
    outlineVariant = Night600,
    scrim = Night,
    surfaceBright = Night600,
    surfaceDim = Night,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightRaised,
    surfaceContainer = Night800,
    surfaceContainerHigh = Night700,
    surfaceContainerHighest = Night600
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun UltimatePdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge: bars are transparent; only their icon contrast follows the theme.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
