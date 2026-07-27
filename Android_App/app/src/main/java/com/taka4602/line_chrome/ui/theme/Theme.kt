package com.taka4602.line_chrome.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Dark only, deliberately — there is no light variant and no dynamic colour, so
 * the app looks the same on every device and never flashes white.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = GreenContainer,
    onPrimaryContainer = OnGreenContainer,

    secondary = Mint80,
    onSecondary = Mint20,
    secondaryContainer = MintContainer,
    onSecondaryContainer = Mint80,

    tertiary = Sky80,
    onTertiary = Sky20,
    tertiaryContainer = SkyContainer,
    onTertiaryContainer = Sky80,

    background = Background,
    onBackground = OnSurface,
    surface = Background,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,

    outline = Outline,
    outlineVariant = OutlineVariant,

    error = ErrorRed,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

/**
 * Expressive-scale corner radii.
 *
 * material3 1.4.0 ships the expressive *components* as public API but keeps
 * `MaterialExpressiveTheme` and `MotionScheme` internal, so the theme itself is
 * a plain [MaterialTheme] with the expressive shape scale spelled out here.
 * Swap this for `MaterialExpressiveTheme` once those graduate.
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LineChromeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = ExpressiveShapes,
        typography = Typography,
        content = content,
    )
}
