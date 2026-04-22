package dev.jdtech.jellyfin.player.xr.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The punt detector gates the cloud-Gemini retry for character identification.
 * A false positive spends a cloud API call we didn't need; a false negative
 * leaves the user with a bad on-device reply when cloud could have answered.
 * The seed list here locks in the phrase variants observed from Gemini Nano
 * and Gemma 4B during manual testing.
 */
class LooksLikeCharacterIdPuntTest {

    @Test fun `canonical punt from the prompt matches`() {
        assertTrue(looksLikeCharacterIdPunt("I can't tell which character this is from the frame."))
    }

    @Test fun `common near-variants match`() {
        listOf(
            "I can't identify the character in this frame.",
            "I cannot tell who this is.",
            "I'm unable to identify them with confidence.",
            "I don't know who this character is.",
            "I'm not sure who this is — possibly Ned.",
            "I am not sure who this character is.",
            "Not confident enough to name them.",
            "No cast metadata available to identify them.",
        ).forEach { text ->
            assertTrue("should flag: $text", looksLikeCharacterIdPunt(text))
        }
    }

    @Test fun `confident answer does not match`() {
        listOf(
            "That's Jon Snow, played by Kit Harington — Ned Stark's bastard son.",
            "Daenerys Targaryen, played by Emilia Clarke, the last of her House.",
            "Walter White (Bryan Cranston), the chemistry teacher turned meth cook.",
        ).forEach { text ->
            assertFalse("should not flag: $text", looksLikeCharacterIdPunt(text))
        }
    }

    @Test fun `null and blank are treated as punts`() {
        assertTrue(looksLikeCharacterIdPunt(null))
        assertTrue(looksLikeCharacterIdPunt(""))
        assertTrue(looksLikeCharacterIdPunt("   "))
    }

    @Test fun `case insensitive`() {
        assertTrue(looksLikeCharacterIdPunt("I CAN'T TELL."))
        assertTrue(looksLikeCharacterIdPunt("NOT SURE WHO THIS IS"))
    }
}
