package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GroupEntity
import androidx.xr.scenecore.Space
import timber.log.Timber

/**
 * XR panel geometry: depth, projection, and constrain helpers for the player's
 * video/UI/subtitle root entities.
 *
 * Extracted from SpatialPlayerScreen.kt. The depth constants live here because
 * every caller combines them with a projection computation.
 */

// The default "IMAX-centered" distance from the user to the video panel. Chosen
// to match Android XR design guidance for cinema-scale content.
internal const val VIDEO_DEPTH_METERS = 6.0f

// UI (controls, dialogs, subtitles) sits much closer so text stays readable.
internal const val UI_DEPTH_METERS = 2.0f

// Default panel scale baseline — tuned so a DEFAULT_VIDEO_WIDTH_METERS-wide panel
// at VIDEO_DEPTH_METERS matches perceived size of a large living-room display.
internal const val DEFAULT_VIDEO_PANEL_SCALE = 1.39f

internal fun extractVideoDepth(pose: Pose, currentDepth: Float): Float {
    val translation = pose.translation
    val distance =
        kotlin.math.sqrt(
            translation.x * translation.x +
                translation.y * translation.y +
                translation.z * translation.z,
        )
    // If distance is extremely small (near head), preserve current depth to prevent snapping.
    // Otherwise, clamp to the Android XR design guide range (0.75m to 15m).
    val finalDepth = if (distance <= 0.5f) currentDepth else distance.coerceIn(0.75f, 15.0f)
    if (System.currentTimeMillis() % 1000 < 10) {
        Timber.v("extractVideoDepth: raw=%.3f clamped=%.3f fallback=%.3f", distance, finalDepth, currentDepth)
    }
    return finalDepth
}

internal fun projectPoseFromOrigin(sourcePose: Pose, depthScale: Float): Pose {
    val translation = sourcePose.translation
    return Pose(
        Vector3(
            translation.x * depthScale,
            translation.y * depthScale,
            translation.z * depthScale,
        ),
        sourcePose.rotation,
    )
}

internal fun syncProjectedOverlayRoots(
    videoPose: Pose,
    videoScale: Float,
    uiRoot: GroupEntity,
    subtitleRoot: GroupEntity,
    depthScale: Float,
) {
    val projectedPose = projectPoseFromOrigin(videoPose, depthScale)
    runCatching {
        uiRoot.setScale(videoScale)
        uiRoot.setPose(projectedPose)
    }
    runCatching {
        subtitleRoot.setScale(videoScale)
        subtitleRoot.setPose(projectedPose)
    }
}

internal fun constrainPoseToDepth(
    sourcePose: Pose,
    targetDepth: Float,
    fallbackPose: Pose,
): Pose {
    val translation = sourcePose.translation
    val distance =
        kotlin.math.sqrt(
            translation.x * translation.x +
                translation.y * translation.y +
                translation.z * translation.z,
        )
    val fallbackTranslation = fallbackPose.translation
    val fallbackDistance =
        kotlin.math.sqrt(
            fallbackTranslation.x * fallbackTranslation.x +
                fallbackTranslation.y * fallbackTranslation.y +
                fallbackTranslation.z * fallbackTranslation.z,
        )
    val direction =
        when {
            distance > 0.001f -> Vector3(
                translation.x / distance,
                translation.y / distance,
                translation.z / distance,
            )
            fallbackDistance > 0.001f -> Vector3(
                fallbackTranslation.x / fallbackDistance,
                fallbackTranslation.y / fallbackDistance,
                fallbackTranslation.z / fallbackDistance,
            )
            else -> Vector3(0f, 0f, -1f)
        }
    return Pose(
        Vector3(
            direction.x * targetDepth,
            direction.y * targetDepth,
            direction.z * targetDepth,
        ),
        sourcePose.rotation,
    )
}

internal fun safeGetEntityPose(entity: Entity): Pose? {
    return runCatching { entity.getPose(Space.ACTIVITY) }
        .onFailure { Timber.d("Skipping disposed XR player entity pose save") }
        .getOrNull()
}
