package dev.jdtech.jellyfin.fcast.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SplitAvMetadataTest {

    @Test
    fun `withSplitAv then splitAv round-trips role and cadence`() {
        val msg = PlayMessage(container = "video/mp4", url = "https://x/y.mp4")
            .withSplitAv(
                SplitAvMetadata(role = SplitAvRole.AUDIO, syncCadenceHz = 10),
            )

        val parsed = msg.splitAv()
        assertNotNull(parsed)
        assertEquals(SplitAvRole.AUDIO, parsed!!.role)
        assertEquals(10, parsed.syncCadenceHz)
        assertEquals(SplitAvMetadata.SCHEMA_VERSION, parsed.version)
    }

    @Test
    fun `splitAv returns null when metadata or custom is absent`() {
        val noMeta = PlayMessage(container = "video/mp4", url = "https://x/y.mp4")
        assertNull(noMeta.splitAv())

        val emptyMeta = noMeta.copy(metadata = MetadataObject(title = "T"))
        assertNull(emptyMeta.splitAv())
    }

    @Test
    fun `withSplitAv preserves sibling custom keys`() {
        // Pre-existing custom payload from a different feature
        val existing = buildJsonObject { put("ambilight", JsonPrimitive("on")) }
        val msg = PlayMessage(
            container = "video/mp4",
            url = "https://x/y.mp4",
            metadata = MetadataObject(custom = existing),
        ).withSplitAv(SplitAvMetadata(role = SplitAvRole.VIDEO))

        val custom = msg.metadata?.custom as? JsonObject
        assertNotNull(custom)
        assertEquals(JsonPrimitive("on"), custom!!["ambilight"])
        assertNotNull(custom["splitAv"])
        // and the typed accessor still finds it
        assertEquals(SplitAvRole.VIDEO, msg.splitAv()?.role)
    }

    @Test
    fun `withSplitAv replaces a previous splitAv entry rather than nesting it`() {
        val once = PlayMessage(container = "video/mp4", url = "https://x/y.mp4")
            .withSplitAv(SplitAvMetadata(role = SplitAvRole.AUDIO, syncCadenceHz = 5))
        val twice = once.withSplitAv(SplitAvMetadata(role = SplitAvRole.AUDIO, syncCadenceHz = 20))

        assertEquals(20, twice.splitAv()?.syncCadenceHz)
        // and the JSON only has one splitAv key (no "splitAv.splitAv" nesting)
        val custom = twice.metadata?.custom as JsonObject
        assertEquals(1, custom.keys.count { it == "splitAv" })
    }

    @Test
    fun `splitAv tolerates malformed payload by returning null`() {
        val bogus = buildJsonObject { put("splitAv", JsonPrimitive("not an object")) }
        val msg = PlayMessage(
            container = "video/mp4",
            url = "https://x/y.mp4",
            metadata = MetadataObject(custom = bogus),
        )
        assertNull(msg.splitAv())
    }

    @Test
    fun `Play message with splitAv survives full frame round-trip`() {
        val original = FCastMessage.Play(
            PlayMessage(container = "video/mp4", url = "https://x/y.mp4")
                .withSplitAv(SplitAvMetadata(role = SplitAvRole.AUDIO, syncCadenceHz = 10)),
        )

        val bytes = FCastFrame.encode(original)
        val stream = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        val decoded = FCastFrame.read(stream) as FCastMessage.Play

        val parsed = decoded.payload.splitAv()
        assertEquals(SplitAvRole.AUDIO, parsed?.role)
        assertEquals(10, parsed?.syncCadenceHz)
    }
}
