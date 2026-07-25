package dev.jdtech.jellyfin.player.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibassTextRendererTest {

    @Test
    fun `embedded SubRip cue timestamps are relative to the Media3 sample`() {
        val sample = """
            1
            00:00:00,000 --> 00:00:02,500
            The cue belongs at the sample PTS.
        """.trimIndent().toByteArray()

        val cues = SrtOrVttSampleParser.parse(sample, sampleStartMs = 65_432)

        assertEquals(
            listOf(
                SrtOrVttSampleParser.Cue(
                    startMs = 65_432,
                    endMs = 67_932,
                    text = "The cue belongs at the sample PTS.",
                ),
            ),
            cues,
        )
    }

    @Test
    fun `side-loaded SubRip file keeps its absolute timestamps at sample zero`() {
        val file = """
            1
            00:01:02,125 --> 00:01:04,500
            First cue

            2
            00:02:10,000 --> 00:02:11,250
            Second
            line
        """.trimIndent().toByteArray()

        val cues = SrtOrVttSampleParser.parse(file, sampleStartMs = 0)

        assertEquals(62_125, cues[0].startMs)
        assertEquals(64_500, cues[0].endMs)
        assertEquals(130_000, cues[1].startMs)
        assertEquals(131_250, cues[1].endMs)
        assertEquals("Second\\Nline", cues[1].text)
    }

    @Test
    fun `UTF-8 BOM does not hide a SubRip file or its first cue`() {
        val file = (
            "\uFEFF1\n" +
                "00:00:01,000 --> 00:00:03,000\n" +
                "Visible first cue\n"
            ).toByteArray()

        assertTrue(SrtOrVttSampleParser.looksLikeFile(file))
        assertEquals(
            listOf(
                SrtOrVttSampleParser.Cue(
                    startMs = 1_000,
                    endMs = 3_000,
                    text = "Visible first cue",
                ),
            ),
            SrtOrVttSampleParser.parse(file, sampleStartMs = 0),
        )
    }

    @Test
    fun `WebVTT timestamps and cue settings receive the sample offset`() {
        val sample = ("\uFEFF" + """
            WEBVTT

            cue-id
            00:01.250 --> 00:03.000 align:start position:10%
            Hello WebVTT
        """.trimIndent()).toByteArray()

        assertTrue(SrtOrVttSampleParser.looksLikeFile(sample))
        assertEquals(
            listOf(
                SrtOrVttSampleParser.Cue(
                    startMs = 6_250,
                    endMs = 8_000,
                    text = "Hello WebVTT",
                ),
            ),
            SrtOrVttSampleParser.parse(sample, sampleStartMs = 5_000),
        )
    }
}
