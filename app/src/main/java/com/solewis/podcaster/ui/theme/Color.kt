package com.solewis.podcaster.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A cool slate-blue palette, sampled from Android's own Settings and Chrome menus rather than
 * invented: the two anchors below are the exact pixel values from those screens.
 *
 *   light primary  #4B6088  - hue 219, the navy pill behind "Use dark theme"
 *   dark primary   #A4C2F1  - hue 217, the checked checkbox on a dark menu
 *
 * One hue at two tones, which is the mechanism behind an accent that reads as deliberate in both
 * themes: roughly tone 40 for light, tone 80 for dark. The previous palette was an aqua (#00677D /
 * #88CFE3) on warm paper neutrals; this hue is darker and less saturated, which is easier to sit
 * with for a screen you look at while a podcast plays.
 *
 * The neutrals moved with it, and had to. Everything here used to be warm - paper #FBF9F5,
 * a stone secondary - and a cool navy over warm beige reads as muddy rather than as a choice.
 * The surface values are sampled too (#FAFAFE and #EDEEF8 light, #2C3139 dark), and carry a slight
 * blue bias for the same reason the accent does: a pure grey next to this blue looks unconsidered.
 *
 * Contrast is checked, not assumed, against the surfaces each colour actually lands on. Primary
 * gives 6.1:1 on the light surface and 6.3:1 for white text on top - the aqua it replaces managed
 * 6.2:1, so nothing regressed - and 10.0:1 on the dark surface. Every text pair here clears AA and
 * most clear AAA, which matters because primary is a *text* colour in this app (every row's show
 * name), not only a fill.
 */

// Primary - slate blue. Light is roughly tone 40, dark roughly tone 80, of one hue.
// Named by role rather than by hue: these are the third and fourth colours to occupy this slot,
// and "TealLight" holding a navy was the confusion worth avoiding.
val PrimaryLight = Color(0xFF4B6088)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFDBE2F8)
val OnPrimaryContainerLight = Color(0xFF0C1B36)

val PrimaryDark = Color(0xFFA4C2F1)
val OnPrimaryDark = Color(0xFF17304F)
val PrimaryContainerDark = Color(0xFF33486B)
val OnPrimaryContainerDark = Color(0xFFD5E2FF)

// Secondary - the same family drained of most of its chroma, for tinted surfaces that should not
// compete with a primary action. Only `secondaryContainer` is actually used (the highlight flash
// on a show's episode row), so this stays quiet on purpose.
val SecondaryLight = Color(0xFF565E70)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDAE1F0)
val OnSecondaryContainerLight = Color(0xFF131B27)

val SecondaryDark = Color(0xFFBEC6DC)
val OnSecondaryDark = Color(0xFF283041)
val SecondaryContainerDark = Color(0xFF3E4658)
val OnSecondaryContainerDark = Color(0xFFDAE2F9)

// Tertiary - a muted teal, kept as the one step away from the blue so the palette has somewhere to
// go for a rare highlight. Currently unused by any screen; here so a component that reaches for the
// role by default doesn't land on Material's own purple.
val TertiaryLight = Color(0xFF3F6470)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFC6E4EF)
val OnTertiaryContainerLight = Color(0xFF001F27)

val TertiaryDark = Color(0xFFA6CBD8)
val OnTertiaryDark = Color(0xFF06353F)
val TertiaryContainerDark = Color(0xFF244C57)
val OnTertiaryContainerDark = Color(0xFFC2E8F4)

// Neutrals - cool blue-grey rather than warm paper, so they belong to the accent above.
val SurfaceLight = Color(0xFFFAFAFE)
val OnSurfaceLight = Color(0xFF1A1C22)
val SurfaceVariantLight = Color(0xFFE1E3ED)
val OnSurfaceVariantLight = Color(0xFF44474F)
val OutlineLight = Color(0xFF757783)
val OutlineVariantLight = Color(0xFFC5C7D2)

val SurfaceDark = Color(0xFF14161B)
val OnSurfaceDark = Color(0xFFE2E2E9)
val SurfaceVariantDark = Color(0xFF44474F)
val OnSurfaceVariantDark = Color(0xFFC4C6D0)
val OutlineDark = Color(0xFF8E9099)
val OutlineVariantDark = Color(0xFF44474F)

// Surface containers - the tonal-elevation ladder Material 3 components (NavigationBar, Card,
// sheets, dialogs) read by default. Left unset, these fall back to M3's own baseline *purple*
// defaults regardless of the primary/surface colors above - which is what quietly put a lavender
// tint on the nav bar before these were added.
//
// The two sampled surfaces sit at the top of each ladder, which is where they came from: #EDEEF8 is
// the Settings page behind its white cards, and #2C3139 is a dark menu - both containers rather than
// the base background, so that is the role they take here.
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF4F5FB)
val SurfaceContainerLight = Color(0xFFEDEEF8)
val SurfaceContainerHighLight = Color(0xFFE7E9F3)
val SurfaceContainerHighestLight = Color(0xFFE1E3ED)

val SurfaceContainerLowestDark = Color(0xFF0E1014)
val SurfaceContainerLowDark = Color(0xFF1B1E24)
val SurfaceContainerDark = Color(0xFF22262D)
val SurfaceContainerHighDark = Color(0xFF2C3139)
val SurfaceContainerHighestDark = Color(0xFF363E4A)

// Inverse roles - used by components like Snackbar.
val InverseSurfaceLight = Color(0xFF2F3138)
val InverseOnSurfaceLight = Color(0xFFF1F2F8)
val InverseSurfaceDark = Color(0xFFE2E2E9)
val InverseOnSurfaceDark = Color(0xFF2F3138)

// Error - muted brick, still legible as an error without being alarming. Deliberately left warm
// while everything else went cool: an error is the one thing that should not look like it belongs.
val ErrorLight = Color(0xFF8B4A42)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD3)
val OnErrorContainerLight = Color(0xFF3A0905)

val ErrorDark = Color(0xFFFFB4A8)
val OnErrorDark = Color(0xFF5C130A)
val ErrorContainerDark = Color(0xFF73342A)
val OnErrorContainerDark = Color(0xFFFFDAD3)
