package dev.jdtech.jellyfin.player.xr

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvedSurfaceMeshBuilderTest {

    @Test
    fun `generateCylindricalMeshData produces 48-segment cylindrical arc with normalized UVs`() {
        val width = 8.0f
        val height = 4.5f
        val segments = 48
        val radius = CurvedSurfaceMeshBuilder.DEFAULT_CURVE_RADIUS_METERS

        val geometry = CurvedSurfaceMeshBuilder.generateCylindricalMeshData(
            width = width,
            height = height,
            radius = radius,
            segments = segments,
        )

        val expectedVertices = (segments + 1) * 2
        val expectedTriangles = segments * 2
        val expectedIndices = expectedTriangles * 3
        assertEquals(expectedVertices, geometry.vertexCount)
        assertEquals(expectedTriangles, geometry.triangleCount)
        assertEquals(expectedVertices * 3, geometry.positions.size)
        assertEquals(expectedVertices * 2, geometry.texCoords.size)
        assertEquals(expectedIndices, geometry.indices.size)

        // Verify UVs span [0, 1] monotonically across columns and match Impress StereoSurface V orientation (top = 1.0f, bottom = 0.0f)
        for (i in 0..segments) {
            val expectedU = i.toFloat() / segments.toFloat()
            val topUvIdx = (i * 2) * 2
            val bottomUvIdx = (i * 2 + 1) * 2

            assertEquals(expectedU, geometry.texCoords[topUvIdx], 1e-5f)
            assertEquals(1.0f, geometry.texCoords[topUvIdx + 1], 1e-5f)
            assertEquals(expectedU, geometry.texCoords[bottomUvIdx], 1e-5f)
            assertEquals(0.0f, geometry.texCoords[bottomUvIdx + 1], 1e-5f)
        }

        // Center column (i = segments / 2) is tangent to z = 0, x = 0
        val centerCol = segments / 2
        val centerTopPosIdx = (centerCol * 2) * 3
        val centerBottomPosIdx = (centerCol * 2 + 1) * 3
        assertEquals(0.0f, geometry.positions[centerTopPosIdx], 1e-5f)
        assertEquals(height / 2f, geometry.positions[centerTopPosIdx + 1], 1e-5f)
        assertEquals(0.0f, geometry.positions[centerTopPosIdx + 2], 1e-5f)
        assertEquals(0.0f, geometry.positions[centerBottomPosIdx], 1e-5f)
        assertEquals(-height / 2f, geometry.positions[centerBottomPosIdx + 1], 1e-5f)
        assertEquals(0.0f, geometry.positions[centerBottomPosIdx + 2], 1e-5f)

        // Left and right edges curve toward the viewer (z > 0) and are symmetric in x
        val leftTopPosIdx = 0
        val rightTopPosIdx = (segments * 2) * 3
        val leftX = geometry.positions[leftTopPosIdx]
        val leftZ = geometry.positions[leftTopPosIdx + 2]
        val rightX = geometry.positions[rightTopPosIdx]
        val rightZ = geometry.positions[rightTopPosIdx + 2]

        assertTrue("Left X should be negative", leftX < 0f)
        assertTrue("Right X should be positive", rightX > 0f)
        assertEquals(abs(leftX), rightX, 1e-5f)
        assertTrue("Edges should curve toward viewer (z > 0)", leftZ > 0f)
        assertEquals(leftZ, rightZ, 1e-5f)

        // Every vertex lies on the cylinder of radius R centered at (0, y, R)
        for (v in 0 until expectedVertices) {
            val x = geometry.positions[v * 3]
            val z = geometry.positions[v * 3 + 2]
            val dz = radius - z
            val distFromCylinderAxis = sqrt(x * x + dz * dz)
            assertEquals(radius, distFromCylinderAxis, 1e-4f)
        }

        // All indices are within valid vertex bounds [0, expectedVertices)
        for (idxPos in 0 until expectedIndices) {
            val vIdx = geometry.indices[idxPos]
            assertTrue("Index $vIdx out of bounds", vIdx in 0 until expectedVertices)
        }
    }

    @Test
    fun `buildCylindricalQuad constructs CustomMesh shape`() {
        val customMesh = CurvedSurfaceMeshBuilder.buildCylindricalQuad(8.0f, 4.5f)
        assertNotNull(customMesh)
        assertNotNull(customMesh.leftEye)
    }

    @Test
    fun `XrScreenCurvePreset maps keys and radii and Deeper preset curves more than Subtle`() {
        assertEquals(XrScreenCurvePreset.FLAT, XrScreenCurvePreset.fromKey("flat"))
        assertEquals(XrScreenCurvePreset.SUBTLE, XrScreenCurvePreset.fromKey("subtle"))
        assertEquals(XrScreenCurvePreset.CINEMA, XrScreenCurvePreset.fromKey("cinema"))
        assertEquals(XrScreenCurvePreset.DEEP, XrScreenCurvePreset.fromKey("deep"))
        assertEquals(XrScreenCurvePreset.CINEMA, XrScreenCurvePreset.fromKey("unknown_key", fallbackCurvedBoolean = true))
        assertEquals(XrScreenCurvePreset.FLAT, XrScreenCurvePreset.fromKey("unknown_key", fallbackCurvedBoolean = false))

        val subtleMesh = CurvedSurfaceMeshBuilder.generateCylindricalMeshData(
            width = 8.0f,
            height = 4.5f,
            radius = XrScreenCurvePreset.SUBTLE.radiusMeters,
        )
        val cinemaMesh = CurvedSurfaceMeshBuilder.generateCylindricalMeshData(
            width = 8.0f,
            height = 4.5f,
            radius = XrScreenCurvePreset.CINEMA.radiusMeters,
        )
        val deepMesh = CurvedSurfaceMeshBuilder.generateCylindricalMeshData(
            width = 8.0f,
            height = 4.5f,
            radius = XrScreenCurvePreset.DEEP.radiusMeters,
        )

        // Left edge Z displacement (index 2) increases as radius tightens
        val subtleLeftZ = subtleMesh.positions[2]
        val cinemaLeftZ = cinemaMesh.positions[2]
        val deepLeftZ = deepMesh.positions[2]
        assertTrue(cinemaLeftZ > subtleLeftZ)
        assertTrue(deepLeftZ > cinemaLeftZ)
    }
}
