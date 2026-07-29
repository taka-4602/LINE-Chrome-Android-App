package com.taka4602.line_chrome.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    ) {
        // Text that names no colour of its own takes LocalContentColor, and
        // material3 defaults that to **black** — it expects a Surface to have
        // provided something, and a bare MaterialTheme never does.  Most text
        // here escapes it by sitting inside a Scaffold or an app bar, which
        // provide their own; anything that does not was drawing black on a
        // background that is nearly black, at about 1.1:1.
        //
        // Provided here rather than by wrapping the app in a Surface, because a
        // Surface would also add a background draw and another pointer-input
        // participant under everything, and the defect is only the colour.
        CompositionLocalProvider(
            LocalContentColor provides DarkColorScheme.onBackground,
            content = content,
        )
    }
}
