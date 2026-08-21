package com.solewis.podcaster.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A deliberately calm, low-saturation palette: a muted teal for primary actions and progress
 * (focus, not urgency), warm stone for secondary accents, and a soft clay for the rare tertiary
 * highlight - against warm-neutral surfaces rather than stark white/black, which read calmer at
 * the reading distances a text-heavy episode list sits at.
 */

// Primary - muted teal.
val TealLight = Color(0xFF4C6B63)
val OnTealLight = Color(0xFFFFFFFF)
val TealContainerLight = Color(0xFFCEE9E0)
val OnTealContainerLight = Color(0xFF092019)

val TealDark = Color(0xFFA5D0C4)
val OnTealDark = Color(0xFF12352C)
val TealContainerDark = Color(0xFF334B44)
val OnTealContainerDark = Color(0xFFBFE8DC)

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
