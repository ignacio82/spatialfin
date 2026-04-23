package dev.spatialfin.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(32.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

val FullRoundedShape = RoundedCornerShape(9999.dp)
