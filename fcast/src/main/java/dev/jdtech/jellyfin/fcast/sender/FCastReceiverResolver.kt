package dev.jdtech.jellyfin.fcast.sender

/**
 * Pure helper that resolves a free-form receiver name (typically said aloud by the user) against
 * a list of known [FCastReceiver]s. Used by the voice action layer when the user says
 * "cast this to the living room TV" — we want one of:
 *
 *   1. an exact (case-insensitive) name match,
 *   2. a containment match in either direction,
 *   3. a token-overlap match if the user's phrase shares >=50% of words with a receiver's name,
 *
 * before falling back to "unresolved" (which the screen turns into a picker dialog).
 *
 * Discovery freshness is the caller's problem — this helper just ranks what it's given.
 */
object FCastReceiverResolver {

    /**
     * Returns the best receiver match for [spokenName] among [candidates], or null if no
     * candidate is good enough. Ties are broken by source (mDNS over Manual) then by name length
     * (shorter wins, since "TV" is a more confident match than "TV in Living Room").
     */
    fun resolve(spokenName: String?, candidates: List<FCastReceiver>): FCastReceiver? {
        if (spokenName.isNullOrBlank() || candidates.isEmpty()) return null
        val normalizedQuery = normalize(spokenName)
        if (normalizedQuery.isBlank()) return null

        val scored = candidates.map { receiver ->
            ScoredReceiver(receiver, score(normalizedQuery, normalize(receiver.name)))
        }.filter { it.score > 0 }
        if (scored.isEmpty()) return null

        return scored
            .sortedWith(
                compareByDescending<ScoredReceiver> { it.score }
                    .thenBy { if (it.receiver.source == FCastReceiver.Source.Mdns) 0 else 1 }
                    .thenBy { it.receiver.name.length },
            )
            .first()
            .receiver
    }

    /** Public so screens can show "matches" feedback. */
    fun score(query: String, candidate: String): Int {
        if (query == candidate) return 100
        if (candidate.contains(query) || query.contains(candidate)) return 70
        val queryTokens = query.split(' ').filter { it.isNotBlank() }.toSet()
        val candidateTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0
        val overlap = queryTokens.intersect(candidateTokens).size
        val ratio = (overlap.toDouble() / queryTokens.size.coerceAtLeast(1).toDouble())
        return when {
            ratio >= 0.5 -> 40
            overlap > 0 -> 20
            else -> 0
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ")

    private data class ScoredReceiver(val receiver: FCastReceiver, val score: Int)
}
