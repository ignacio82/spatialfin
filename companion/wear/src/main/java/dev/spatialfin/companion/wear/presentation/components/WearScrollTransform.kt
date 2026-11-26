package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * Replacement for Wear Compose Material3's removed `Modifier.scrollTransform(scope)`
 * using the stable 1.7.x `TransformationSpec` API (`transformedHeight` + container
 * `graphicsLayer` scaling/translation/fade for round bezels).
 */
@Composable
internal fun Modifier.scrollTransform(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec = rememberTransformationSpec(),
): Modifier =
    this.transformedHeight(scope, spec).graphicsLayer {
        with(scope) {
            with(spec) {
                applyContainerTransformation(scrollProgress)
            }
        }
    }
