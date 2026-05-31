package dev.jdtech.jellyfin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import java.net.HttpURLConnection
import java.net.URL
import dev.jdtech.jellyfin.models.SpatialFinChapter
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinItemPerson
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinSources
import dev.jdtech.jellyfin.models.toSpatialFinItem
import dev.jdtech.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerContentSource
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerPerson
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

class PlaylistManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val pluginContentRepository: PluginContentRepository,
) {
    private var startItem: SpatialFinItem? = null
    private var items: List<SpatialFinItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    var currentItemIndex: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        maxBitrate: Long? = null,
        startFromBeginning: Boolean = false,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = itemId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    items = episodes
                    episode
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks.div(10000) else 0
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, maxBitrate, playbackPosition)
        playerItems.add(playerItem)

        return playerItem
    }

    suspend fun getInitialItemForUniversal(
        pluginId: String,
        itemId: String,
        videoUrl: String,
        title: String,
    ): PlayerItem {
        android.util.Log.e("CRITICAL_PLAYLIST", "Resolving universal item: $itemId from plugin: $pluginId")
        android.util.Log.e("CRITICAL_PLAYLIST", "Original URL: $videoUrl")
        
        val resolved = try {
            pluginContentRepository.getVideoUrl(pluginId, videoUrl)
        } catch (e: Exception) {
            android.util.Log.e("CRITICAL_PLAYLIST", "Exception during URL resolution", e)
            null
        }

        requireNotNull(resolved) {
            "Could not resolve playable stream for universal item $itemId"
        }
        
        val resolvedUrl = if (resolved?.url?.startsWith("data:application/dash+xml;base64,") == true) {
            try {
                val base64 = resolved.url.substringAfter("base64,")
                val xml = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
                val file = java.io.File(context.cacheDir, "universal_manifest.mpd")
                file.writeText(xml)
                android.util.Log.e("CRITICAL_PLAYLIST", "Wrote virtual manifest to: ${file.absolutePath}")
                "file://${file.absolutePath}"
            } catch (e: Exception) {
                android.util.Log.e("CRITICAL_PLAYLIST", "Failed to write virtual manifest", e)
                resolved.url
            }
        } else {
            resolved.url
        }
        val mimeType = if (resolvedUrl.endsWith(".mpd") || resolvedUrl.contains("application/dash+xml")) "application/dash+xml" else resolved?.mimeType
        
        android.util.Log.e("CRITICAL_PLAYLIST", "Final resolved URL: $resolvedUrl, mimeType=$mimeType")
        
        val playerItem =
            PlayerItem(
                name = title,
                itemId = UUID.nameUUIDFromBytes(itemId.toByteArray()),
                mediaSourceUri = resolvedUrl,
                mediaSourceId = itemId,
                contentSource = PlayerContentSource.UNIVERSAL,
                playbackPosition = 0L,
                mimeType = mimeType,
                videoUrl = resolved?.videoUrl,
                audioUrl = resolved?.audioUrl,
                videoMimeType = resolved?.videoMimeType,
                audioMimeType = resolved?.audioMimeType
            )
        playerItems.clear()
        playerItems.add(playerItem)
        currentItemIndex = 0
        return playerItem
    }

    suspend fun loadNextUniversalItems(pluginId: String, currentVideoUrl: String) {
        val newItems = pluginContentRepository.getPager(pluginId, currentVideoUrl)
        newItems.forEach { item ->
            val playerItem = PlayerItem(
                name = item.title,
                itemId = UUID.nameUUIDFromBytes(item.id.toByteArray()),
                mediaSourceUri = item.videoUrl, // We will resolve it later when we play it
                mediaSourceId = item.id,
                contentSource = PlayerContentSource.UNIVERSAL,
                playbackPosition = 0L,
                mimeType = null,
                videoUrl = item.videoUrl,
                audioUrl = null,
                videoMimeType = null,
                audioMimeType = null
            )
            // Add custom field to store pluginId in the PlayerItem? Wait, we can just use intent extras.
            // Actually, we shouldn't resolve here, it might be slow. We'll resolve in `resolveUniversalItem`.
            playerItems.add(playerItem)
        }
    }

    suspend fun resolveUniversalItem(playerItem: PlayerItem, pluginId: String): PlayerItem {
        val resolved = try {
            pluginContentRepository.getVideoUrl(pluginId, playerItem.videoUrl ?: playerItem.mediaSourceUri)
        } catch (e: Exception) {
            android.util.Log.e("CRITICAL_PLAYLIST", "Exception during URL resolution", e)
            null
        }
        val resolvedUrl = resolved?.url ?: playerItem.mediaSourceUri
        val mimeType = if (resolvedUrl.endsWith(".mpd") || resolvedUrl.contains("application/dash+xml")) "application/dash+xml" else resolved?.mimeType

        return playerItem.copy(
            mediaSourceUri = resolvedUrl,
            mimeType = mimeType,
            videoUrl = resolved?.videoUrl,
            audioUrl = resolved?.audioUrl,
            videoMimeType = resolved?.videoMimeType,
            audioMimeType = resolved?.audioMimeType
        )
    }

    fun getStorySoFarContext(): String? {
        if (items.isEmpty() || currentItemIndex <= 0) return null

        val contextItems = items.subList(maxOf(0, currentItemIndex - 3), currentItemIndex)
        if (contextItems.isEmpty()) return null

        val sb = StringBuilder()
        contextItems.forEach { item ->
            if (item is SpatialFinEpisode) {
                sb.append("Ep ${item.indexNumber} '${item.name}': ${item.overview.take(200)}... ")
            }
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {

        val itemIndex = currentItemIndex - 1
        val playerItem =
            when (startItem) {
                is SpatialFinMovie -> null
                is SpatialFinEpisode -> {
                    if (currentItemIndex == 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> {
                    if (itemIndex >= 0 && itemIndex < playerItems.size) {
                        playerItems[itemIndex]
                    } else {
                        null
                    }
                }
            }

        if (playerItem != null && !playerItems.contains(playerItem)) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")

        val itemIndex = currentItemIndex + 1
        val playerItem =
            when (startItem) {
                is SpatialFinMovie -> null
                is SpatialFinEpisode -> {
                    if (currentItemIndex == items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> {
                    if (itemIndex < playerItems.size) {
                        playerItems[itemIndex]
                    } else {
                        null
                    }
                }
            }

        if (playerItem != null && !playerItems.contains(playerItem)) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemIndex = items.indexOfFirst { it.id == itemId }
    }

    suspend fun getPlayerItem(
        itemId: UUID,
        playbackPosition: Long = 0L,
        playlistItemId: UUID? = null,
        mediaSourceIndex: Int? = null,
        maxBitrate: Long? = null,
    ): PlayerItem? {
        val item = repository.getItem(itemId) ?: return null
        return item.toPlayerItem(mediaSourceIndex, maxBitrate, playbackPosition).copy(
            playlistItemId = playlistItemId
        )
    }

    private suspend fun SpatialFinItem.toPlayerItem(
        mediaSourceIndex: Int?,
        maxBitrate: Long?,
        playbackPosition: Long,
    ): PlayerItem {
        Timber.d("Converting SpatialFinItem ${this.id} to PlayerItem")

        val mediaSources = repository.getMediaSources(id, true, maxBitrate)
        val resolvedGenres =
            when (this) {
                is SpatialFinMovie -> genres
                is SpatialFinShow -> genres
                is SpatialFinEpisode ->
                    runCatching { repository.getShow(seriesId).genres }
                        .getOrDefault(emptyList())
                else -> emptyList()
            }
        val inferredAnime = isAnimeItem(this, resolvedGenres, mediaSources)
        val preferredAudioLanguage =
            if (inferredAnime) {
                appPreferences.getValue(appPreferences.animeAudioLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeAudioLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
            }
        val selectedSource =
            if (mediaSourceIndex == null) {
                chooseBestMediaSource(
                    mediaSources = mediaSources,
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferStyledSubtitles = inferredAnime,
                )
            } else {
                mediaSources.getOrNull(mediaSourceIndex)
            } ?: throw Exception("No media sources available")
        val effectiveMaxBitrate =
            when {
                maxBitrate != null -> maxBitrate
                preferredAudioLanguage.isNullOrBlank() -> null
                sourceHasPreferredLanguageInUnsupportedCodec(selectedSource, preferredAudioLanguage) -> 8_000_000L
                else -> null
            }
        val mediaSource =
            if (effectiveMaxBitrate == maxBitrate) {
                selectedSource
            } else {
                val rescoredSources = repository.getMediaSources(id, true, effectiveMaxBitrate)
                chooseBestMediaSource(
                    mediaSources = rescoredSources,
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferStyledSubtitles = inferredAnime,
                ) ?: selectedSource
            }
        Timber.i(
            "Playlist source selection itemId=%s inferredAnime=%b preferredAudio=%s selectedSource=%s selectedCodec=%s effectiveMaxBitrate=%s",
            id,
            inferredAnime,
            preferredAudioLanguage,
            mediaSource.name.ifBlank { mediaSource.id },
            mediaSource.mediaStreams.firstOrNull { it.type == MediaStreamType.AUDIO && languageMatches(it.language, preferredAudioLanguage) }?.codec,
            effectiveMaxBitrate,
        )
        // Include both embedded and truly-external subtitle streams. Use Jellyfin's
        // server-provided deliveryUrl as-is: Jellyfin's index-mapping behavior varies per
        // file (server-side bugs in some Jellyfin versions produce mismatched content for
        // some tracks), but rewriting the URL client-side fixes one file while breaking
        // another. Passing the path Jellyfin itself returned is the most consistent
        // behavior.
        val subtitleStreams = mediaSource.mediaStreams.filter { mediaStream ->
            mediaStream.type == MediaStreamType.SUBTITLE && !mediaStream.path.isNullOrBlank()
        }
        subtitleStreams.take(5).forEach { s ->
            Timber.i(
                "Subtitle sideload index=%s codec=%s isExternal=%b lang=%s path=%s",
                s.index, s.codec, s.isExternal, s.language, s.path,
            )
        }
        // Media3's SingleSampleMediaPeriod loads the entire subtitle into a single
        // ByteBuffer via Arrays.copyOf (doubles on grow), so an N-byte track needs ~2N
        // of heap. Probe Content-Length on IO dispatchers in parallel and drop tracks
        // above MAX_SUBTITLE_BYTES so Media3 never attempts the load.
        val externalSubtitles = coroutineScope {
            subtitleStreams
                .map { mediaStream ->
                    ExternalSubtitle(
                        mediaStream.title,
                        mediaStream.language,
                        mediaStream.path!!.toUri(),
                        when (mediaStream.codec.lowercase()) {
                            "subrip", "srt" -> MimeTypes.APPLICATION_SUBRIP
                            "webvtt", "vtt" -> MimeTypes.TEXT_VTT
                            "ass", "ssa" -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.TEXT_UNKNOWN
                        },
                    )
                }
                .map { sub ->
                    async(Dispatchers.IO) { sub to probeSubtitleSize(sub.uri.toString()) }
                }
                .awaitAll()
                .mapNotNull { (sub, probedBytes) ->
                    if (probedBytes != null && probedBytes > MAX_SUBTITLE_BYTES) {
                        Timber.w(
                            "Subtitle sideload DROPPED (too large %d bytes > cap %d): title=%s lang=%s uri=%s",
                            probedBytes, MAX_SUBTITLE_BYTES, sub.title, sub.language, sub.uri,
                        )
                        null
                    } else {
                        sub
                    }
                }
        }
        val trickplayInfo =
            when (this) {
                is SpatialFinSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is SpatialFinEpisode) parentIndexNumber else null,
            indexNumber = if (this is SpatialFinEpisode) indexNumber else null,
            indexNumberEnd = if (this is SpatialFinEpisode) indexNumberEnd else null,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            people = when (this) {
                is SpatialFinMovie -> people.toPlayerPeople()
                is SpatialFinEpisode -> people.toPlayerPeople()
                else -> emptyList()
            },
            overview = overview,
            genres = resolvedGenres,
            ratings = ratings,
            productionYear = when (this) {
                is SpatialFinMovie -> productionYear
                is SpatialFinShow -> productionYear
                else -> null
            },
            officialRating = when (this) {
                is SpatialFinMovie -> officialRating
                is SpatialFinShow -> officialRating
                else -> null
            },
            backdropImageUri = when (this) {
                is SpatialFinEpisode -> (images.backdrop ?: images.primary)?.toString()
                is SpatialFinMovie -> (images.backdrop ?: images.primary)?.toString()
                else -> null
            },
            logoImageUri = when (this) {
                is SpatialFinEpisode -> (images.showLogo ?: images.logo)?.toString()
                is SpatialFinMovie -> images.logo?.toString()
                is dev.jdtech.jellyfin.models.SpatialFinShow -> images.logo?.toString()
                else -> null
            },
            seriesName = if (this is SpatialFinEpisode) seriesName else null,
            seriesId = if (this is SpatialFinEpisode) seriesId else null,
        )
    }

    private fun chooseBestMediaSource(
        mediaSources: List<dev.jdtech.jellyfin.models.SpatialFinSource>,
        preferredAudioLanguage: String?,
        preferStyledSubtitles: Boolean,
    ): dev.jdtech.jellyfin.models.SpatialFinSource? {
        return mediaSources.maxByOrNull { source ->
            var score = 0
            if (source.type == SpatialFinSourceType.LOCAL) score += 500

            val preferredAudioStream =
                preferredAudioLanguage?.let { preferred ->
                    source.mediaStreams.firstOrNull { stream ->
                        stream.type == MediaStreamType.AUDIO && languageMatches(stream.language, preferred)
                    }
                }
            if (preferredAudioStream != null) {
                score += 400
                if (isBeamFriendlyAudioCodec(preferredAudioStream.codec)) {
                    score += 120
                } else {
                    score -= 80
                }
            }

            if (preferStyledSubtitles && source.mediaStreams.any { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        (stream.codec.equals("ass", ignoreCase = true) || stream.codec.equals("ssa", ignoreCase = true))
                }
            ) {
                score += 70
            }

            score += source.mediaStreams.count { it.type == MediaStreamType.AUDIO } * 10
            score
        }
    }

    private fun isAnimeItem(
        item: SpatialFinItem,
        genres: List<String>,
        mediaSources: List<dev.jdtech.jellyfin.models.SpatialFinSource>,
    ): Boolean {
        if (genres.any { it.contains("anime", ignoreCase = true) }) return true
        val hasJapaneseAudio = mediaSources.any { source ->
            source.mediaStreams.any { stream ->
                stream.type == MediaStreamType.AUDIO && languageMatches(stream.language, "jpn")
            }
        }
        val hasStyledSubtitles = mediaSources.any { source ->
            source.mediaStreams.any { stream ->
                stream.type == MediaStreamType.SUBTITLE &&
                    (stream.codec.equals("ass", ignoreCase = true) || stream.codec.equals("ssa", ignoreCase = true))
            }
        }
        if (hasJapaneseAudio && hasStyledSubtitles) return true

        val titleText = listOf(item.name, item.originalTitle.orEmpty()).joinToString(" ")
        return titleText.any { char -> char.code in 0x3040..0x30FF || char.code in 0x4E00..0x9FFF }
    }

    private fun sourceHasPreferredLanguageInUnsupportedCodec(
        source: dev.jdtech.jellyfin.models.SpatialFinSource,
        preferredAudioLanguage: String,
    ): Boolean {
        val preferredAudioStream =
            source.mediaStreams.firstOrNull { stream ->
                stream.type == MediaStreamType.AUDIO &&
                    languageMatches(stream.language, preferredAudioLanguage)
            } ?: return false
        return !isBeamFriendlyAudioCodec(preferredAudioStream.codec)
    }

    private fun isBeamFriendlyAudioCodec(codec: String): Boolean {
        return when (codec.lowercase()) {
            "aac", "mp4a-latm", "opus", "vorbis", "flac", "mp3" -> true
            else -> false
        }
    }

    private fun languageMatches(candidate: String?, preferred: String?): Boolean {
        val normalizedCandidate = normalizeLanguage(candidate) ?: return false
        val normalizedPreferred = normalizeLanguage(preferred) ?: return false
        return normalizedCandidate == normalizedPreferred
    }

    private fun normalizeLanguage(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
        return when {
            normalized == "ja" || normalized == "jpn" || normalized.startsWith("ja-") || normalized.contains("japanese") -> "jpn"
            normalized == "en" || normalized == "eng" || normalized.startsWith("en-") || normalized.contains("english") -> "eng"
            else -> normalized
        }
    }

    private fun List<SpatialFinChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }

    private fun List<SpatialFinItemPerson>.toPlayerPeople(): List<PlayerPerson> {
        return this.map { person ->
            PlayerPerson(
                name = person.name,
                role = person.role,
                // PersonKind.toString() returns the serial name: "Actor", "Director", etc.
                type = person.type.toString(),
                imageUri = person.image.uri?.toString(),
            )
        }
    }

    companion object {
        // Skip subtitle tracks above this byte count. Anime typesetting-heavy ASS tracks
        // (full vector karaoke, 34k+ Dialogue lines) can reach 200+ MB; Media3's
        // SingleSampleMediaPeriod loads them into a single grown ByteBuffer (~2× size),
        // which OOMs the player even with largeHeap=true. 50 MB comfortably covers the
        // worst real "signs & songs" tracks while excluding the pathological ones.
        private const val MAX_SUBTITLE_BYTES = 50L * 1024L * 1024L
    }

    /**
     * Probe the byte size of a subtitle URL without downloading the body. Jellyfin's
     * subtitle endpoint rejects HEAD with 405 but returns `Content-Length` on GET, so we
     * start the GET, read the header, and close the stream before any body is consumed.
     * Returns null on any network error — the caller treats unknown size as allowed.
     */
    private fun probeSubtitleSize(url: String): Long? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
                // Prevent Android's HttpURLConnection from transparently following
                // gzip-encoded bodies — we only want the Content-Length header.
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                conn.connect()
                val len = conn.contentLengthLong
                if (len >= 0) len else null
            } finally {
                // Closing the stream without reading stops the body transfer.
                runCatching { conn.inputStream?.close() }
                conn.disconnect()
            }
        }.onFailure { Timber.d(it, "probeSubtitleSize failed for %s", url) }.getOrNull()
    }
}
