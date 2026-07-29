package com.taka4602.line_chrome.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A dark-only palette built around LINE's green, warmed slightly so the
 * expressive surfaces read as tinted rather than grey.
 */

val Green80 = Color(0xFF7FDC94)
val Green20 = Color(0xFF003919)
val GreenContainer = Color(0xFF00602B)
val OnGreenContainer = Color(0xFFB8F5C4)

val Mint80 = Color(0xFFB3CCB8)
val Mint20 = Color(0xFF1D3524)
val MintContainer = Color(0xFF344E39)

val Sky80 = Color(0xFFA5CDDC)
val Sky20 = Color(0xFF06343F)
val SkyContainer = Color(0xFF234C59)

val Background = Color(0xFF101410)
val SurfaceContainerLowest = Color(0xFF0B0F0B)
val SurfaceContainerLow = Color(0xFF181D18)
val SurfaceContainer = Color(0xFF1C211C)
val SurfaceContainerHigh = Color(0xFF272B26)
val SurfaceContainerHighest = Color(0xFF313631)
val SurfaceVariant = Color(0xFF414941)
val OnSurface = Color(0xFFE0E4DB)
val OnSurfaceVariant = Color(0xFFC1C9BF)
val Outline = Color(0xFF8B938A)
val OutlineVariant = Color(0xFF414941)

/**
 * Links, in message text.
 *
 * Pale for a blue, because it has to clear 4.5:1 against the *sent* bubble as
 * well as the received one — `GreenContainer` is much lighter than the chat
 * background, and a saturated blue that looks right on the darker surface only
 * reaches about 4.3:1 on the green one.  This lands at 5.5:1 there and 10:1 on
 * received.
 */
val LinkBlue = Color(0xFFB0D2FF)

val ErrorRed = Color(0xFFFFB4AB)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

/** Deterministic avatar tints for contacts with no profile picture. */
val AvatarTints = listOf(
    Color(0xFF3F6E4B), Color(0xFF4A5F7A), Color(0xFF6E5B3F),
    Color(0xFF6E3F5B), Color(0xFF3F6A6E), Color(0xFF5B4A7A),
)
