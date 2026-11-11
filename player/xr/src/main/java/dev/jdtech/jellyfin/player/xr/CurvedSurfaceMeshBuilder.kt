package dev.jdtech.jellyfin.player.xr

import androidx.xr.scenecore.SurfaceEntity
import dev.jdtech.jellyfin.player.core.ProjectionModeDetector
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Selectable horizontal curvature presets for the XR cinema screen.
 *
 * Each curved preset preserves the full `8.0m × 4.5m` arc length while keeping the forward edge
 * sagitta safely behind the floating control plane (`UI_DEPTH_METERS = 2.0f`).
 */
enum class XrScreenCurvePreset(
    val key: String,
    val radiusMeters: Float,
    val shortLabel: String,
    val title: String,
    val subtitle: String,
) {
    FLAT(
        key = "flat",
        radiusMeters = 0f,
        shortLabel = "Flat",
        title = "Flat screen",
        subtitle = "Standard 2D / 3D planar panel",
    ),
    SUBTLE(
        key = "subtle",
        radiusMeters = 9.0f,
        shortLabel = "Subtle",
        title = "Subtle curve (9.0m)",
        subtitle = "~51° arc · gentle edge wrap for multi-tasking",
    ),
    CINEMA(
        key = "cinema",
        radiusMeters = 6.5f,
        shortLabel = "Cinema",
        title = "Cinema curve (6.5m)",
        subtitle = "~70° arc · standard theater seating",
    ),
    DEEP(
        key = "deep",
        radiusMeters = 5.2f,
        shortLabel = "Deep",
        title = "Deep curve (5.2m)",
        subtitle = "~88° arc · deep wrap-around immersion",
    );

    fun nextSteppedPreset(): XrScreenCurvePreset = when (this) {
        FLAT -> SUBTLE
        SUBTLE -> CINEMA
        CINEMA -> DEEP
        DEEP -> FLAT
    }

    companion object {
        fun fromKey(key: String?, fallbackCurvedBoolean: Boolean = false): XrScreenCurvePreset {
            val normalized = key?.trim()?.lowercase()
            val matched = entries.firstOrNull { it.key == normalized }
            if (matched != null) return matched
            return if (fallbackCurvedBoolean) CINEMA else FLAT
        }

        fun fromPreferences(appPreferences: AppPreferences): XrScreenCurvePreset {
            val rawKey = if (appPreferences.sharedPreferences.contains(appPreferences.xrPlayerScreenCurvePreset.backendName)) {
                appPreferences.getValue(appPreferences.xrPlayerScreenCurvePreset)
            } else {
                null
            }
            val legacyCurved = appPreferences.getValue(appPreferences.xrPlayerCurvedScreen)
            return fromKey(rawKey, fallbackCurvedBoolean = legacyCurved)
        }
    }
}

/**
 * Generates cylindrical [SurfaceEntity.Shape.CustomMesh] geometry for the curved XR cinema screen.
 *
 * The mesh is centered at `(0, 0, 0)` in entity-local space, tangent to the `z = 0` plane at the
 * horizontal center (`x = 0, z = 0`), with the left and right edges curving gently toward the
 * viewer (`z > 0`) along a cylinder of radius [radius].
 *
 * Normalized `[0.0, 1.0]` UV coordinates are generated across the full surface so SceneCore's
 * internal `stereoMode` shader (`MONO`, `SIDE_BY_SIDE`, `TOP_BOTTOM`, `MULTIVIEW_LEFT_PRIMARY`,
 * `MULTIVIEW_RIGHT_PRIMARY`) samples each eye half without needing separate eye meshes.
 */
object CurvedSurfaceMeshBuilder {
    const val DEFAULT_CURVE_RADIUS_METERS: Float = 6.5f
    const val SUBTLE_CURVE_RADIUS_METERS: Float = 9.0f
    const val DEEP_CURVE_RADIUS_METERS: Float = 5.2f
    const val DEFAULT_CURVE_SEGMENTS: Int = 48

    fun shouldUseCurvedMesh(
        preset: XrScreenCurvePreset,
        projectionType: String?,
        isWallPinned: Boolean,
    ): Boolean {
        val isFlatProjection = projectionType == null || projectionType == ProjectionModeDetector.PROJECTION_FLAT
        return preset != XrScreenCurvePreset.FLAT && !isWallPinned && isFlatProjection
    }

    fun shouldUseCurvedMesh(
        curvedScreenEnabled: Boolean,
        projectionType: String?,
        isWallPinned: Boolean,
    ): Boolean = shouldUseCurvedMesh(
        preset = if (curvedScreenEnabled) XrScreenCurvePreset.CINEMA else XrScreenCurvePreset.FLAT,
        projectionType = projectionType,
        isWallPinned = isWallPinned,
    )

    data class CylindricalMeshData(
        val positions: FloatArray,
        val texCoords: FloatArray,
        val indices: IntArray,
        val vertexCount: Int,
        val triangleCount: Int,
    )

    /**
     * Computes raw vertex positions (`x, y, z`), normalized texture coordinates (`u, v`), and
     * counter-clockwise triangle indices (`+Z` front-facing) for a cylindrical cinema screen.
     */
    fun generateCylindricalMeshData(
        width: Float,
        height: Float,
        radius: Float = DEFAULT_CURVE_RADIUS_METERS,
        segments: Int = DEFAULT_CURVE_SEGMENTS,
    ): CylindricalMeshData {
        val safeWidth = width.coerceAtLeast(0.1f)
        val safeHeight = height.coerceAtLeast(0.1f)
        val safeRadius = radius.coerceAtLeast(1.0f)
        val safeSegments = segments.coerceIn(4, 256)

        val columnCount = safeSegments + 1
        val vertexCount = columnCount * 2
        val triangleCount = safeSegments * 2

        val positions = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)
        val indices = IntArray(triangleCount * 3)

        val halfHeight = safeHeight * 0.5f
        val totalArcRadians = safeWidth / safeRadius

        for (i in 0..safeSegments) {
            val u = i.toFloat() / safeSegments.toFloat()
            val theta = (u - 0.5f) * totalArcRadians
            val x = safeRadius * sin(theta)
            val z = safeRadius * (1.0f - cos(theta))

            val topVertex = i * 2
            val bottomVertex = topVertex + 1

            // Top vertex (2 * i) — Impress StereoSurface maps top (+halfHeight) to v = 1.0f
            val topPosIdx = topVertex * 3
            positions[topPosIdx] = x
            positions[topPosIdx + 1] = halfHeight
            positions[topPosIdx + 2] = z

            val topUvIdx = topVertex * 2
            texCoords[topUvIdx] = u
            texCoords[topUvIdx + 1] = 1.0f

            // Bottom vertex (2 * i + 1) — Impress StereoSurface maps bottom (-halfHeight) to v = 0.0f
            val bottomPosIdx = bottomVertex * 3
            positions[bottomPosIdx] = x
            positions[bottomPosIdx + 1] = -halfHeight
            positions[bottomPosIdx + 2] = z

            val bottomUvIdx = bottomVertex * 2
            texCoords[bottomUvIdx] = u
            texCoords[bottomUvIdx + 1] = 0.0f
        }

        var idxOffset = 0
        for (i in 0 until safeSegments) {
            val topLeft = i * 2
            val bottomLeft = topLeft + 1
            val topRight = topLeft + 2
            val bottomRight = topLeft + 3

            // Counter-clockwise front-facing (+Z normal) Triangle 1
            indices[idxOffset++] = topLeft
            indices[idxOffset++] = bottomLeft
            indices[idxOffset++] = topRight

            // Counter-clockwise front-facing (+Z normal) Triangle 2
            indices[idxOffset++] = topRight
            indices[idxOffset++] = bottomLeft
            indices[idxOffset++] = bottomRight
        }

        return CylindricalMeshData(
            positions = positions,
            texCoords = texCoords,
            indices = indices,
            vertexCount = vertexCount,
            triangleCount = triangleCount,
        )
    }

    /**
     * Builds a [SurfaceEntity.Shape.CustomMesh] backed by direct native-order NIO buffers.
     */
    fun buildCylindricalQuad(
        width: Float,
        height: Float,
        radius: Float = DEFAULT_CURVE_RADIUS_METERS,
        segments: Int = DEFAULT_CURVE_SEGMENTS,
    ): SurfaceEntity.Shape.CustomMesh {
        val data = generateCylindricalMeshData(
            width = width,
            height = height,
            radius = radius,
            segments = segments,
        )
        val triangleMesh = SurfaceEntity.Shape.TriangleMesh(
            positions = toDirectFloatBuffer(data.positions),
            texCoords = toDirectFloatBuffer(data.texCoords),
            indices = toDirectIntBuffer(data.indices),
        )
        return SurfaceEntity.Shape.CustomMesh(
            leftEye = triangleMesh,
            rightEye = null,
            drawMode = SurfaceEntity.DrawMode.TRIANGLES,
        )
    }

    private fun toDirectFloatBuffer(array: FloatArray): FloatBuffer {
        val buffer = ByteBuffer
            .allocateDirect(array.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(array)
        buffer.flip()
        return buffer
    }

    private fun toDirectIntBuffer(array: IntArray): IntBuffer {
        val buffer = ByteBuffer
            .allocateDirect(array.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        buffer.put(array)
        buffer.flip()
        return buffer
    }
}
