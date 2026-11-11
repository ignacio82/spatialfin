package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import java.text.Normalizer

internal object CastMemberMatcher {

    private val GENERIC_ROLES = setOf(
        "self",
        "himself",
        "herself",
        "themselves",
        "doctor",
        "guard",
        "nurse",
        "voice",
        "narrator",
        "man",
        "woman",
        "cop",
        "officer",
        "soldier",
        "driver",
        "waiter",
        "waitress",
        "bartender",
        "reporter",
        "anchor",
        "host",
        "guest",
        "extra",
        "actor",
        "actress",
        "unknown",
        "various",
        "additional voices",
    )

    private val PARENTHETICAL_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
    private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Matches an assistant character-identification response against the current item's
     * cast list ([people]), returning up to [maxMatches] matched [PlayerPerson] entries
     * ordered by their earliest appearance in [answerText].
     */
    fun matchCastMembers(
        answerText: String,
        people: List<PlayerPerson>,
        maxMatches: Int = 3,
    ): List<PlayerPerson> {
        if (answerText.isBlank() || people.isEmpty() || maxMatches <= 0) return emptyList()
        if (looksLikeCharacterIdPunt(answerText)) return emptyList()

        val normalizedAnswer = normalize(answerText)
        if (normalizedAnswer.isBlank()) return emptyList()

        // Prefer Actor / GuestStar entries, falling back to all people if none are tagged.
        val actorCandidates = people.filter { person ->
            person.type.equals("Actor", ignoreCase = true) ||
                person.type.equals("GuestStar", ignoreCase = true) ||
                person.type.isBlank()
        }.ifEmpty { people }

        data class MatchCandidate(
            val person: PlayerPerson,
            val earliestIndex: Int,
            val matchQuality: Int, // 0 = exact actor name, 1 = full role segment, 2 = unique role token
        )

        val directMatches = mutableListOf<MatchCandidate>()

        for (person in actorCandidates) {
            var bestIndex = Int.MAX_VALUE
            var bestQuality = Int.MAX_VALUE

            val normalizedName = normalize(person.name)
            if (normalizedName.length >= 3) {
                val idx = findWordBoundaryIndex(normalizedAnswer, normalizedName)
                if (idx >= 0) {
                    bestIndex = idx
                    bestQuality = 0
                }
            }

            for (segment in extractRoleSegments(person.role)) {
                val idx = findWordBoundaryIndex(normalizedAnswer, segment)
                if (idx >= 0 && (idx < bestIndex || (idx == bestIndex && 1 < bestQuality))) {
                    bestIndex = idx
                    bestQuality = 1
                }
            }

            if (bestIndex != Int.MAX_VALUE) {
                directMatches += MatchCandidate(
                    person = person,
                    earliestIndex = bestIndex,
                    matchQuality = bestQuality,
                )
            }
        }

        if (directMatches.isNotEmpty()) {
            return directMatches
                .sortedWith(compareBy<MatchCandidate> { it.earliestIndex }.thenBy { it.matchQuality })
                .map { it.person }
                .distinctBy { (it.id ?: "") to normalize(it.name) }
                .take(maxMatches)
        }

        // Secondary pass: check distinctive single tokens (>= 5 chars) from multi-word character roles
        // when that token belongs to only one candidate in actorCandidates.
        val tokenOwners = mutableMapOf<String, MutableList<PlayerPerson>>()
        for (person in actorCandidates) {
            for (segment in extractRoleSegments(person.role)) {
                val tokens = segment.split(' ').filter { it.length >= 5 && it !in GENERIC_ROLES }
                for (token in tokens) {
                    tokenOwners.getOrPut(token) { mutableListOf() }.add(person)
                }
            }
        }

        val tokenMatches = mutableListOf<MatchCandidate>()
        for ((token, owners) in tokenOwners) {
            val distinctOwners = owners.distinctBy { (it.id ?: "") to normalize(it.name) }
            if (distinctOwners.size == 1) {
                val idx = findWordBoundaryIndex(normalizedAnswer, token)
                if (idx >= 0) {
                    tokenMatches += MatchCandidate(
                        person = distinctOwners.first(),
                        earliestIndex = idx,
                        matchQuality = 2,
                    )
                }
            }
        }

        return tokenMatches
            .sortedWith(compareBy<MatchCandidate> { it.earliestIndex }.thenBy { it.matchQuality })
            .map { it.person }
            .distinctBy { (it.id ?: "") to normalize(it.name) }
            .take(maxMatches)
    }

    internal fun extractRoleSegments(rawRole: String): List<String> {
        if (rawRole.isBlank()) return emptyList()
        val withoutParens = rawRole.replace(PARENTHETICAL_REGEX, " ")
        return withoutParens
            .split('/', ',', ';')
            .map { normalize(it) }
            .filter { segment ->
                segment.length >= 4 && segment !in GENERIC_ROLES
            }
    }

    internal fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val stripped = decomposed.replace(DIACRITICS_REGEX, "")
        return stripped
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun findWordBoundaryIndex(haystack: String, needle: String): Int {
        if (needle.isBlank() || haystack.isBlank()) return -1
        val regex = Regex("\\b${Regex.escape(needle)}\\b")
        return regex.find(haystack)?.range?.first ?: -1
    }
}
