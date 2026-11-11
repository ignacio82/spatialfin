package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMemberMatcherTest {

    private val sampleCast = listOf(
        PlayerPerson(
            id = "11111111-1111-1111-1111-111111111111",
            name = "Adam Scott",
            role = "Mark Scout / Mark S.",
            type = "Actor",
        ),
        PlayerPerson(
            id = "22222222-2222-2222-2222-222222222222",
            name = "Britt Lower",
            role = "Helly Riggs (voice)",
            type = "Actor",
        ),
        PlayerPerson(
            id = "33333333-3333-3333-3333-333333333333",
            name = "Patricia Arquette",
            role = "Harmony Cobel",
            type = "Actor",
        ),
        PlayerPerson(
            id = "44444444-4444-4444-4444-444444444444",
            name = "Stellan Skarsgård",
            role = "Doctor",
            type = "Actor",
        ),
        PlayerPerson(
            id = "55555555-5555-5555-5555-555555555555",
            name = "Ben Stiller",
            role = "Director",
            type = "Director",
        ),
    )

    @Test
    fun `matches actor name and composite slash role in order of appearance`() {
        val answer =
            "On the left is Mark Scout (played by Adam Scott) talking with Harmony Cobel (Patricia Arquette)."
        val matched = CastMemberMatcher.matchCastMembers(answer, sampleCast)

        assertEquals(2, matched.size)
        assertEquals("Adam Scott", matched[0].name)
        assertEquals("Patricia Arquette", matched[1].name)
    }

    @Test
    fun `matches diacritic-normalized actor name and strips parenthetical role annotations`() {
        val answer = "That character is Helly Riggs, alongside Stellan Skarsgard."
        val matched = CastMemberMatcher.matchCastMembers(answer, sampleCast)

        assertEquals(2, matched.size)
        assertEquals("Britt Lower", matched[0].name)
        assertEquals("Stellan Skarsgård", matched[1].name)
    }

    @Test
    fun `ignores generic single-word roles like doctor and ignores punt answers`() {
        val genericOnlyAnswer = "There is a doctor standing in the hallway."
        assertTrue(CastMemberMatcher.matchCastMembers(genericOnlyAnswer, sampleCast).isEmpty())

        val puntAnswer = "I can't tell from this frame who that is, even though Adam Scott is in the cast."
        assertTrue(CastMemberMatcher.matchCastMembers(puntAnswer, sampleCast).isEmpty())
    }
}
