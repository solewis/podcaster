package com.solewis.podcaster.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Slightly softer/rounder than Material 3's own defaults - part of the calm, minimalist feel.
 * Referenced by name (`MaterialTheme.shapes.medium`, etc.) rather than screens each picking
 * their own literal corner radius, so every rounded corner in the app - artwork, cards, sheets,
 * dialogs - comes from one shared scale.
 */
val PodcasterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
