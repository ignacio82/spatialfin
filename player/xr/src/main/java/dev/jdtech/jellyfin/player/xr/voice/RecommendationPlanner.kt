package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import org.jellyfin.sdk.model.api.MediaStreamType
import kotlin.math.min

data class RecommendationContext(
    val query: String,
    val items: List<SpatialFinItem> = emptyList(),
)

internal enum class RecommendationMediaType {
    MOVIE,
    SHOW,
}

internal data class RecommendationFilters(
    val mediaType: RecommendationMediaType? = null,
    val preferShorter: Boolean = false,
    val preferFunny: Boolean = false,
    val preferNew: Boolean = false,
    val avoidAnime: Boolean = false,
    val requireEnglishAudio: Boolean = false,
    val maxRuntimeMinutes: Int? = null,
    val preferComfort: Boolean = false,
    val preferLateNight: Boolean = false,
    val preferSurprise: Boolean = false,
)

internal data class RecommendationAnalysis(
    val filters: RecommendationFilters,
    val referenceItem: SpatialFinItem? = null,
    val usePriorContext: Boolean = false,
)

internal object RecommendationPlanner {
    private val recommendationKeywords = listOf(
        "similar",
        "recommend",
        "suggestion",
        "what else",
        "what should i watch",
        "watch after",
        "watch next",
        "anything else",
        "like this",
        "other movies",
        "other shows",
        "what other",
        "something similar",
        "more like",
        "give me a",
        "what to watch",
        "find something",
        "pick for me",
        "recommendation",
        "surprise me",
        "comfort show",
        "comfort movie",
        "late night",
    )

    // MMR tuning. Lambda = 0.5 is a 50/50 relevance-vs-diversity split and the
    // canonical choice from Carbonell & Goldstein's original MMR paper for
    // diversified retrieval. Higher values (0.7+) let near-duplicate franchise
    // entries crowd the top slots when relevance is tightly clustered — the
    // exact failure mode ("five Iron Man movies") this reranker exists to
    // prevent — because a same-franchise Jaccard of 1.0 can't outweigh a
    // small score delta. Lower values (<0.4) start surfacing very weakly
    // scored picks for the sake of variety. Bump MMR_SHORTLIST_SIZE if the
    // library is large and the rerank feels too narrow; it caps how much
    // post-sort work runs and limits the diversity horizon.
    private const val MMR_LAMBDA = 0.5
    private const val MMR_SHORTLIST_SIZE = 15
    private const val MMR_RESULT_SIZE = 6

    fun analyzeQuestion(
        question: String,
        previousContext: RecommendationContext?,
    ): RecommendationAnalysis? {
        val normalized = normalize(question)
        val hasPreviousContext = previousContext?.items?.isNotEmpty() == true
        val isFollowUpOnly =
            hasPreviousContext &&
                (
                    normalized in setOf(
                        "shorter",
                        "movie only",
                        "show only",
                        "funny",
                        "not anime",
                        "something new",
                        "with english audio",
                    ) ||
                        normalized.contains("more like") ||
                        normalized.contains("similar to")
                )
        if (!isRecommendationQuery(question) && !isFollowUpOnly) {
            return null
        }

        val filters =
            RecommendationFilters(
                mediaType =
                    when {
                        normalized.contains("movie only") || normalized.contains("movies only") -> RecommendationMediaType.MOVIE
                        normalized.contains("show only") || normalized.contains("shows only") || normalized.contains("series only") -> RecommendationMediaType.SHOW
                        else -> null
                    },
                preferShorter = normalized.contains("shorter") || normalized.contains("quick"),
                preferFunny = normalized.contains("funny") || normalized.contains("comedy") || normalized.contains("lighter"),
                preferNew = normalized.contains("something new") || normalized.contains("newer") || normalized.contains("recent"),
                avoidAnime = normalized.contains("not anime") || normalized.contains("no anime"),
                requireEnglishAudio = normalized.contains("english audio") || normalized.contains("english dub"),
                maxRuntimeMinutes =
                    Regex("under\\s+(\\d{1,3})\\s*(minutes|minute|min)").find(normalized)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull(),
                preferComfort = normalized.contains("comfort") || normalized.contains("cozy") || normalized.contains("easy watch"),
                preferLateNight = normalized.contains("late night") || normalized.contains("nightcap"),
                preferSurprise = normalized.contains("surprise me") || normalized.contains("pick something"),
            )

        val ordinal = extractOrdinalIndex(normalized)
        val referenceItem =
            if (previousContext != null && ordinal != null && (normalized.contains("more like") || normalized.contains("similar to"))) {
                previousContext.items.getOrNull(ordinal)
            } else {
                null
            }

        return RecommendationAnalysis(
            filters = filters,
            referenceItem = referenceItem,
            usePriorContext = isFollowUpOnly,
        )
    }

    fun rankCandidates(
        candidates: List<SpatialFinItem>,
        sourceWeights: Map<java.util.UUID, Int>,
        playerState: PlayerStateSnapshot,
        question: String,
        analysis: RecommendationAnalysis,
        previousContext: RecommendationContext?,
    ): List<SpatialFinItem> {
        val normalizedQuestion = normalize(question)
        val queryTokens = informativeTokens(normalizedQuestion)
        val seedItems =
            buildList {
                analysis.referenceItem?.let(::add)
                previousContext?.items?.take(3)?.forEach(::add)
            }
        val scored =
            candidates
                .distinctBy { it.id }
                .map { item -> item to scoreItem(item, sourceWeights[item.id] ?: 0, playerState, queryTokens, analysis, seedItems) }
                .filter { (_, score) -> score > -50.0 }
                .sortedByDescending { it.second }

        // Relevance-only ranking on a well-stocked library surfaces five Marvel
        // movies when the user asks for "something new". MMR rerank of the
        // shortlist enforces diversity across genre / decade / franchise so the
        // top picks span the library instead of clustering on a single strand.
        return applyMmrDiversity(scored.take(MMR_SHORTLIST_SIZE), MMR_RESULT_SIZE)
    }

    /**
     * Maximal-Marginal-Relevance rerank of an already-scored shortlist. Each
     * pick trades off its raw relevance score against the maximum similarity
     * to the items already picked. [MMR_LAMBDA] controls the balance — higher
     * leans relevance, lower leans diversity. The similarity metric is a
     * Jaccard overlap over (genre set ∪ decade bucket ∪ franchise key), which
     * is cheap to compute and catches the common failure modes (five entries
     * from the same franchise, five 90s action flicks, etc.) without needing
     * richer per-item metadata.
     *
     * Visible for testing.
     */
    internal fun applyMmrDiversity(
        scored: List<Pair<SpatialFinItem, Double>>,
        pickSize: Int,
    ): List<SpatialFinItem> {
        if (scored.size <= 1) return scored.map { it.first }
        val normalizedScores = normalizeScores(scored.map { it.second })
        val signatures = scored.map { diversitySignature(it.first) }
        val remaining = scored.indices.toMutableList()
        val picks = mutableListOf<Int>()
        while (picks.size < pickSize && remaining.isNotEmpty()) {
            val bestIdx = if (picks.isEmpty()) {
                remaining.maxBy { normalizedScores[it] }
            } else {
                remaining.maxBy { i ->
                    val maxSim = picks.maxOf { p -> jaccard(signatures[i], signatures[p]) }
                    MMR_LAMBDA * normalizedScores[i] - (1 - MMR_LAMBDA) * maxSim
                }
            }
            picks.add(bestIdx)
            remaining.remove(bestIdx)
        }
        return picks.map { scored[it].first }
    }

    private fun diversitySignature(item: SpatialFinItem): Set<String> {
        val tokens = mutableSetOf<String>()
        itemGenres(item).forEach { g -> tokens.add("g:${g.lowercase()}") }
        itemProductionYear(item)?.let { y -> tokens.add("d:${y / 10}") }
        // Franchise proxy. For shows/episodes use the series name; for movies,
        // use the first two normalized words of the title (catches "Iron Man",
        // "Iron Man 2", "Iron Man 3" as one franchise — the usual MMR failure).
        val franchise = when (item) {
            is SpatialFinEpisode -> item.seriesName
            is SpatialFinSeason -> item.seriesName
            is SpatialFinShow -> item.name
            is SpatialFinMovie -> item.name
            else -> ""
        }
        normalizedFranchiseKey(franchise)?.let { tokens.add("f:$it") }
        return tokens
    }

    private fun normalizedFranchiseKey(raw: String): String? {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .filterNot { it.length <= 2 && it !in setOf("it") } // drop short filler tokens
        if (cleaned.isEmpty()) return null
        return cleaned.take(2).joinToString(" ")
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = (a.size + b.size - intersection.toInt()).toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * Min-max normalize a relevance-score vector so MMR can compare it against
     * the [0, 1] Jaccard similarity without the raw score magnitude swallowing
     * the diversity term.
     */
    private fun normalizeScores(scores: List<Double>): List<Double> {
        if (scores.isEmpty()) return scores
        val min = scores.min()
        val max = scores.max()
        val span = max - min
        return if (span == 0.0) List(scores.size) { 1.0 } else scores.map { (it - min) / span }
    }

    fun buildRelatedItemsContext(items: List<SpatialFinItem>): String? {
        val topItems = items.take(6)
        if (topItems.isEmpty()) return null
        return topItems.joinToString("\n") { item ->
            buildString {
                append("- ")
                append(item.name)
                append(" [")
                append(typeLabel(item))
                append(']')
                itemProductionYear(item)?.let {
                    append(" ")
                    append(it)
                }
                val genres = itemGenres(item).take(3)
                if (genres.isNotEmpty()) {
                    append(" • ")
                    append(genres.joinToString(", "))
                }
                val runtimeMinutes = itemRuntimeMinutes(item)
                if (runtimeMinutes > 0) {
                    append(" • ")
                    append(runtimeMinutes)
                    append(" min")
                }
                val overview = item.overview.take(120).trim()
                if (overview.isNotBlank()) {
                    append(": ")
                    append(overview)
                }
            }
        }
    }

    fun buildRecommendationReply(
        items: List<SpatialFinItem>,
        analysis: RecommendationAnalysis,
    ): String? {
        val picks = items.take(3)
        if (picks.isEmpty()) return null

        return when {
            analysis.filters.preferSurprise -> "Try ${joinTitlesForSpeech(picks)}."
            analysis.usePriorContext -> "Here are a few better matches: ${joinTitlesForSpeech(picks)}."
            else -> "Based on your Jellyfin library, try ${joinTitlesForSpeech(picks)}."
        }
    }

    private fun scoreItem(
        item: SpatialFinItem,
        sourceWeight: Int,
        playerState: PlayerStateSnapshot,
        queryTokens: Set<String>,
        analysis: RecommendationAnalysis,
        seedItems: List<SpatialFinItem>,
    ): Double {
        var score = sourceWeight.toDouble()

        if (item.canPlay) score += 3.0 else score -= 8.0
        if (!item.played) score += 2.5
        if (item.favorite) score += 2.5
        if ((item.unplayedItemCount ?: 0) > 0) score += 3.0

        when (analysis.filters.mediaType) {
            RecommendationMediaType.MOVIE -> if (item !is SpatialFinMovie) score -= 40.0
            RecommendationMediaType.SHOW -> if (item !is SpatialFinShow && item !is SpatialFinSeason && item !is SpatialFinEpisode) score -= 40.0
            null -> Unit
        }

        if (analysis.filters.preferShorter) {
            val runtime = itemRuntimeMinutes(item)
            score += if (runtime > 0) (180 - min(runtime, 180)) / 18.0 else -1.5
        }

        analysis.filters.maxRuntimeMinutes?.let { maxRuntime ->
            val runtime = itemRuntimeMinutes(item)
            if (runtime > 0) {
                score += if (runtime <= maxRuntime) 5.0 else -((runtime - maxRuntime) / 4.0)
            }
        }

        if (analysis.filters.preferFunny) {
            score += if (itemGenres(item).any { it.contains("comedy", ignoreCase = true) }) 6.0 else -1.0
        }

        if (analysis.filters.preferComfort) {
            val comfortGenres = setOf("comedy", "family", "animation", "romance", "adventure")
            score += itemGenres(item).count { comfortGenres.contains(it.lowercase()) } * 2.0
        }

        if (analysis.filters.preferLateNight) {
            val runtime = itemRuntimeMinutes(item)
            if (runtime in 1..110) score += 3.5
            val lateNightGenres = setOf("thriller", "mystery", "comedy", "crime", "animation")
            score += itemGenres(item).count { lateNightGenres.contains(it.lowercase()) } * 1.5
        }

        if (analysis.filters.preferNew) {
            score += (itemProductionYear(item)?.minus(2000) ?: -5) / 4.0
        }

        if (analysis.filters.avoidAnime && isAnimeLike(item)) {
            score -= 18.0
        }

        if (analysis.filters.requireEnglishAudio) {
            score += if (hasEnglishAudio(item)) 4.0 else -12.0
        }

        val currentGenres = playerState.currentGenres.map { it.lowercase() }.toSet()
        val itemGenres = itemGenres(item).map { it.lowercase() }.toSet()
        score += currentGenres.intersect(itemGenres).size * 3.0

        val currentSeries = playerState.currentSeriesName.orEmpty()
        if (currentSeries.isNotBlank() && itemSeriesName(item).equals(currentSeries, ignoreCase = true)) {
            score += 5.0
        }

        seedItems.forEach { seed ->
            if (seed.id == item.id) {
                score -= 12.0
            } else {
                if (typeLabel(seed) == typeLabel(item)) score += 2.0
                if (itemSeriesName(seed).isNotBlank() && itemSeriesName(seed).equals(itemSeriesName(item), ignoreCase = true)) {
                    score += 6.0
                }
                score += itemGenres(seed).map { it.lowercase() }.intersect(itemGenres).size * 3.5
                val seedTokens = informativeTokens(normalize("${seed.name} ${seed.overview}"))
                score += seedTokens.intersect(informativeTokens(normalize("${item.name} ${item.overview}"))).size * 0.75
            }
        }

        score += queryTokens.intersect(informativeTokens(normalize("${item.name} ${item.overview} ${itemGenres(item).joinToString(" ")}"))).size * 1.25
        if (analysis.filters.preferSurprise) {
            score += ((item.id.mostSignificantBits xor item.id.leastSignificantBits).toInt() and 0xFF) / 255.0
        }
        return score
    }

    private fun itemGenres(item: SpatialFinItem): List<String> =
        when (item) {
            is SpatialFinMovie -> item.genres
            is SpatialFinShow -> item.genres
            else -> emptyList()
        }

    private fun itemRuntimeMinutes(item: SpatialFinItem): Int =
        (item.runtimeTicks / 10_000_000L / 60L).toInt()

    private fun itemProductionYear(item: SpatialFinItem): Int? =
        when (item) {
            is SpatialFinMovie -> item.productionYear
            is SpatialFinShow -> item.productionYear
            else -> null
        }

    private fun itemSeriesName(item: SpatialFinItem): String =
        when (item) {
            is SpatialFinEpisode -> item.seriesName
            is SpatialFinSeason -> item.seriesName
            else -> ""
        }

    private fun hasEnglishAudio(item: SpatialFinItem): Boolean =
        item.sources
            .flatMap { it.mediaStreams }
            .any { stream ->
                stream.type == MediaStreamType.AUDIO &&
                    (stream.language.equals("en", ignoreCase = true) ||
                        stream.language.contains("english", ignoreCase = true))
            }

    private fun isAnimeLike(item: SpatialFinItem): Boolean {
        val haystack =
            buildString {
                append(item.name)
                append(' ')
                append(item.originalTitle.orEmpty())
                append(' ')
                append(item.overview)
                append(' ')
                append(itemGenres(item).joinToString(" "))
            }.lowercase()
        return listOf("anime", "manga", "ova", "japanese animation").any(haystack::contains)
    }

    private fun typeLabel(item: SpatialFinItem): String =
        when (item) {
            is SpatialFinMovie -> "Movie"
            is SpatialFinShow -> "Series"
            is SpatialFinSeason -> "Season"
            is SpatialFinEpisode -> "Episode"
            else -> "Item"
        }

    private fun joinTitlesForSpeech(items: List<SpatialFinItem>): String =
        when (items.size) {
            1 -> items.first().name
            2 -> "${items[0].name} or ${items[1].name}"
            else -> "${items[0].name}, ${items[1].name}, or ${items[2].name}"
        }

    private fun normalize(input: String): String =
        input.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isRecommendationQuery(question: String): Boolean {
        val normalized = normalize(question)
        return recommendationKeywords.any(normalized::contains)
    }

    private fun informativeTokens(text: String): Set<String> {
        val stopWords =
            setOf(
                "the",
                "a",
                "an",
                "for",
                "me",
                "to",
                "watch",
                "something",
                "like",
                "this",
                "that",
                "with",
                "and",
                "or",
                "only",
                "more",
            )
        return text.split(' ')
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }

    private fun extractOrdinalIndex(text: String): Int? {
        return when {
            Regex("\\b(first|1st|one)\\b").containsMatchIn(text) -> 0
            Regex("\\b(second|2nd|two)\\b").containsMatchIn(text) -> 1
            Regex("\\b(third|3rd|three)\\b").containsMatchIn(text) -> 2
            Regex("\\b(fourth|4th|four)\\b").containsMatchIn(text) -> 3
            else -> null
        }
    }
}
