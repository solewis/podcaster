package com.solewis.podcaster.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Calm doesn't mean washed out: one aqua carries every primary action and accent (a "focus" hue,
 * not an urgent one), warm stone for secondary accents, and a soft clay for the rare tertiary
 * highlight - against warm-neutral surfaces rather than stark white/black. One decisive accent
 * against a quiet neutral base, rather than everything muted at once, which is what read as bland
 * rather than minimalist.
 *
 * The two themes take the *same hue at different tones*, which is the mechanism behind an accent
 * that looks deliberate in both: light mode uses a mid tone that still reads as a colour, dark mode
 * a light one that stays legible on a dark surface. The light value used to be #002B36 - so dark it
 * read as ink rather than as aqua, which left tinted surfaces looking merely grey and selected
 * controls falling back on the warm secondary. #00677D is the same hue at a tone that shows.
 *
 * Both are checked against the surfaces they actually land on: #00677D gives 6.2:1 on the light
 * background and 6.5:1 for white text on top, so primary-coloured labels stay past AA rather than
 * only just reaching it. Anything lighter drops toward 4:1 and starts to fail the small labels.
 */

// Primary - aqua. Light is roughly tone 40, dark roughly tone 80, of one hue.
val TealLight = Color(0xFF00677D)
val OnTealLight = Color(0xFFFFFFFF)
val TealContainerLight = Color(0xFFC4E5EF)
val OnTealContainerLight = Color(0xFF001016)

val TealDark = Color(0xFF88CFE3)
val OnTealDark = Color(0xFF00323F)
val TealContainerDark = Color(0xFF00485A)
val OnTealContainerDark = Color(0xFFB8E7F6)

// Secondary - warm stone.
val StoneLight = Color(0xFF6B5F52)
val OnStoneLight = Color(0xFFFFFFFF)
val StoneContainerLight = Color(0xFFEFE1D2)
val OnStoneContainerLight = Color(0xFF241A0E)

val StoneDark = Color(0xFFD6C6B3)
val OnStoneDark = Color(0xFF3A3021)
val StoneContainerDark = Color(0xFF524635)
val OnStoneContainerDark = Color(0xFFF0E3D3)

// Tertiary - muted clay, used sparingly.
val ClayLight = Color(0xFF7A5A4C)
val OnClayLight = Color(0xFFFFFFFF)
val ClayContainerLight = Color(0xFFF4DED2)
val OnClayContainerLight = Color(0xFF2C160B)

val ClayDark = Color(0xFFE8BFAE)
val OnClayDark = Color(0xFF442A1E)
val ClayContainerDark = Color(0xFF5D4235)
val OnClayContainerDark = Color(0xFFFFDBCC)

// Neutrals - warm paper / warm charcoal, not stark white/black.
val SurfaceLight = Color(0xFFFBF9F5)
val OnSurfaceLight = Color(0xFF1B1C18)
val SurfaceVariantLight = Color(0xFFE4E1D8)
val OnSurfaceVariantLight = Color(0xFF48473C)
val OutlineLight = Color(0xFF78776B)
val OutlineVariantLight = Color(0xFFC9C6B9)

val SurfaceDark = Color(0xFF1A1C17)
val OnSurfaceDark = Color(0xFFE4E2D8)
val SurfaceVariantDark = Color(0xFF48473C)
val OnSurfaceVariantDark = Color(0xFFC9C6B9)
val OutlineDark = Color(0xFF929184)
val OutlineVariantDark = Color(0xFF48473C)

// Surface containers - the tonal-elevation ladder Material 3 components (NavigationBar, Card,
// sheets, dialogs) read by default. Left unset, these fall back to M3's own baseline *purple*
// defaults regardless of the primary/surface colors above - which is what quietly put a lavender
// tint on the nav bar before these were added.
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF5F3EC)
val SurfaceContainerLight = Color(0xFFEFEDE5)
val SurfaceContainerHighLight = Color(0xFFE9E7DE)
val SurfaceContainerHighestLight = Color(0xFFE3E1D8)

val SurfaceContainerLowestDark = Color(0xFF14150F)
val SurfaceContainerLowDark = Color(0xFF22241C)
val SurfaceContainerDark = Color(0xFF262820)
val SurfaceContainerHighDark = Color(0xFF30322A)
val SurfaceContainerHighestDark = Color(0xFF3B3D33)

// Inverse roles - used by components like Snackbar.
val InverseSurfaceLight = Color(0xFF2F312A)
val InverseOnSurfaceLight = Color(0xFFF1EFE6)
val InverseSurfaceDark = Color(0xFFE4E2D8)
val InverseOnSurfaceDark = Color(0xFF2F312A)

// Error - muted brick, still legible as an error without being alarming.
val ErrorLight = Color(0xFF8B4A42)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD3)
val OnErrorContainerLight = Color(0xFF3A0905)

val ErrorDark = Color(0xFFFFB4A8)
val OnErrorDark = Color(0xFF5C130A)
val ErrorContainerDark = Color(0xFF73342A)
val OnErrorContainerDark = Color(0xFFFFDAD3)
