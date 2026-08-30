package dev.jdtech.jellyfin.models

import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStreamLanguageTest {

    private fun audio(
        index: Int = 0,
        title: String = "",
        displayTitle: String? = null,
        language: String = "",
    ) = SpatialFinMediaStream(
        index = index,
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = MediaStreamType.AUDIO,
        codec = "eac3",
        isExternal = false,
        path = null,
        channelLayout = "5.1",
        videoRangeType = null,
        height = null,
        width = null,
        videoDoViTitle = null,
    )

    @Test
    fun `normalize maps two and three letter codes and names to one code`() {
        listOf("en", "eng", "English", "ENGLISH", "en-US").forEach {
            assertEquals("normalize($it)", "eng", MediaStreamLanguage.normalize(it))
        }
        assertEquals("por", MediaStreamLanguage.normalize("pt-BR"))
        assertEquals("fra", MediaStreamLanguage.normalize("fre"))
        assertEquals("deu", MediaStreamLanguage.normalize("ger"))
    }

    @Test
    fun `normalize treats undefined and blank as no information`() {
        assertNull(MediaStreamLanguage.normalize("und"))
        assertNull(MediaStreamLanguage.normalize(""))
        assertNull(MediaStreamLanguage.normalize("   "))
        assertNull(MediaStreamLanguage.normalize(null))
    }

    /**
     * The Silo S3:E2 case: the MKV's EBML header carries no language for the
     * English audio track, so matching on the tag alone picks the Portuguese
     * track that happens to be flagged default.
     */
    @Test
    fun `untagged english track is matched from its display title`() {
        val untaggedEnglish = audio(
            index = 2,
            title = "English",
            displayTitle = "English - Dolby Digital Plus + Dolby Atmos - 5.1",
            language = "",
        )
        assertTrue(untaggedEnglish.matchesLanguage("eng"))
        assertFalse(untaggedEnglish.matchesLanguage("por"))
    }

    @Test
    fun `explicit language tag wins over a misleading title`() {
        // A Portuguese track whose title mentions the English dub it was made
        // from must not be treated as English.
        val portuguese = audio(
            index = 1,
            title = "Portuguese (from English master)",
            displayTitle = "Portugues - Dolby Digital Plus - 5.1",
            language = "por",
        )
        assertTrue(portuguese.matchesLanguage("por"))
        assertFalse(portuguese.matchesLanguage("eng"))
    }

    @Test
    fun `title matching requires a whole word not a substring`() {
        // "Digital" contains "ita"; "Commentary" contains no language at all.
        val commentary = audio(index = 3, title = "Digital Commentary", language = "")
        assertFalse(commentary.matchesLanguage("ita"))
    }

    @Test
    fun `brazilian portuguese is recognised by name`() {
        val brazilian = audio(index = 1, displayTitle = "Brazilian Portuguese - 5.1", language = "")
        assertTrue(brazilian.matchesLanguage("por"))
        assertFalse(brazilian.matchesLanguage("eng"))
    }

    @Test
    fun `an untagged untitled track matches nothing`() {
        val anonymous = audio(index = 4, title = "", displayTitle = null, language = "und")
        assertFalse(anonymous.matchesLanguage("eng"))
        assertFalse(anonymous.matchesLanguage("por"))
    }

    @Test
    fun `displayCode falls back to the title when the tag is missing`() {
        assertEquals("ENG", MediaStreamLanguage.displayCode(audio(language = "en")))
        assertEquals(
            "ENG",
            MediaStreamLanguage.displayCode(
                audio(language = "", displayTitle = "English - Dolby Digital Plus - 5.1"),
            ),
        )
        assertNull(MediaStreamLanguage.displayCode(audio(language = "und", displayTitle = null)))
    }
}
