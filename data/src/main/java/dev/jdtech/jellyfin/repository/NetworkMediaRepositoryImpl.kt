package dev.jdtech.jellyfin.repository

import android.net.Uri
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkPlaybackStateDto
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoDto
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinImages
import dev.jdtech.jellyfin.models.Rating
import dev.jdtech.jellyfin.models.RatingType
import dev.jdtech.jellyfin.models.SpatialFinSource
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.network.DiscoveredShare
import dev.jdtech.jellyfin.network.DiscoveredSmbServerShare
import dev.jdtech.jellyfin.network.MetadataMatchService
import dev.jdtech.jellyfin.network.NetworkCredentials
import dev.jdtech.jellyfin.network.NetworkDiscovery
import dev.jdtech.jellyfin.network.NetworkFileClient
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.network.NetworkStreamProxy
import dev.jdtech.jellyfin.network.SmbPathNormalizer
import dev.jdtech.jellyfin.network.SmbConnectionTarget
import dev.jdtech.jellyfin.network.SmbFileClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class NetworkMediaRepositoryImpl(
    private val database: ServerDatabaseDao,
    private val clientFactory: NetworkFileClientFactory,
    private val streamProxy: NetworkStreamProxy,
    private val discovery: NetworkDiscovery,
    private val smbFileClient: SmbFileClient,
    private val metadataMatchService: MetadataMatchService,
) : NetworkMediaRepository {

    override suspend fun getShares(): List<NetworkShareDto> = withContext(Dispatchers.IO) {
        database.getNetworkShares()
    }

    override suspend fun addShare(
        protocol: String,
        host: String,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        displayName: String?,
    ): NetworkShareDto = withContext(Dispatchers.IO) {
        val normalizedTarget: SmbConnectionTarget? = if (protocol.equals("smb", ignoreCase = true)) {
            SmbPathNormalizer.normalizeConnectionTarget(host, shareName)
        } else {
            null
        }
        val normalizedHost = normalizedTarget?.host?.takeIf { it.isNotBlank() } ?: host.trim()
        val normalizedShareName = normalizedTarget?.shareName?.takeIf { it.isNotBlank() } ?: shareName.trim()

        val id = UUID.randomUUID().toString()
        val path = "$protocol://$normalizedHost/$normalizedShareName"
        val share = NetworkShareDto(
            id = id,
            protocol = protocol,
            host = normalizedHost,
            shareName = normalizedShareName,
            path = path,
            displayName = displayName,
            username = username,
            password = password,
            domain = domain,
            addedAtEpochMs = System.currentTimeMillis(),
            lastScannedAtEpochMs = null,
        )
        database.insertNetworkShare(share)
        share
    }

    override suspend fun removeShare(shareId: String) = withContext(Dispatchers.IO) {
        database.deleteNetworkShare(shareId)
    }

    override suspend fun discoverShares(): List<DiscoveredShare> {
        return discovery.discoverAll()
    }

    override suspend fun discoverSmbServerShares(
        host: String,
        username: String?,
        password: String?,
        domain: String?,
    ): List<DiscoveredSmbServerShare> = withContext(Dispatchers.IO) {
        smbFileClient.listServerShares(
            host = host,
            credentials = NetworkCredentials(
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                domain = domain?.takeIf { it.isNotBlank() },
            ),
        )
    }

    override suspend fun scanShare(shareId: String) = withContext(Dispatchers.IO) {
        val share = database.getNetworkShare(shareId) ?: return@withContext
        val credentials = NetworkCredentials(
            username = share.username,
            password = share.password,
            domain = share.domain,
        )

        val videos = mutableListOf<NetworkVideoDto>()
        scanDirectory(share, credentials, "", videos)

        // Remove old videos for this share and insert new ones
        database.deleteNetworkVideosByShare(shareId)
        database.insertNetworkVideos(videos)
        database.updateNetworkShareLastScanned(shareId, System.currentTimeMillis())
    }

    private suspend fun scanDirectory(
        share: NetworkShareDto,
        credentials: NetworkCredentials,
        path: String,
        results: MutableList<NetworkVideoDto>,
    ) {
        try {
            val fileClient = clientFactory.clientFor(share.protocol)
            val entries = fileClient.listFiles(
                host = share.host,
                shareName = share.shareName,
                path = path.ifEmpty { "/" },
                credentials = credentials,
            )
            for (entry in entries) {
                if (entry.isDirectory) {
                    scanDirectory(share, credentials, entry.path, results)
                } else if (NetworkFileClient.isVideoFile(entry.name)) {
                    val parsed = MetadataMatchService.parseMetadata(entry.name)
                    val videoId = UUID.nameUUIDFromBytes(
                        "network:${share.id}:${entry.path}".toByteArray()
                    ).toString()
                    results.add(
                        NetworkVideoDto(
                            id = videoId,
                            shareId = share.id,
                            filePath = entry.path,
                            fileName = entry.name,
                            sizeBytes = entry.size,
                            durationMs = null,
                            tmdbId = null,
                            tmdbType = null,
                            title = parsed.displayTitle,
                            overview = null,
                            posterUrl = null,
                            backdropUrl = null,
                            voteAverage = null,
                            releaseYear = parsed.productionYear,
                            seasonNumber = parsed.seasonNumber,
                            episodeNumber = parsed.episodeNumber,
                            seriesGroupKey = if (parsed.seasonNumber != null) {
                                "series:${parsed.displayTitle.lowercase().trim()}"
                            } else {
                                null
                            },
                            lastModifiedEpochMs = entry.lastModified,
                            metadataFetchedAtEpochMs = null,
                            genres = null,
                            director = null,
                            writers = null,
                            imdbId = null,
                            imdbRating = null,
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error scanning directory $path on share ${share.id}")
        }
    }

    override suspend fun getVideos(): List<NetworkVideoItem> = withContext(Dispatchers.IO) {
        val videos = database.getAllNetworkVideos()
        val states = database.getAllNetworkPlaybackStates().associateBy { it.videoId }
        videos.map { it.toNetworkVideoItem(states[it.id]) }
    }

    override suspend fun getVideosByShare(shareId: String): List<NetworkVideoItem> =
        withContext(Dispatchers.IO) {
            val videos = database.getNetworkVideosByShare(shareId)
            val states = database.getAllNetworkPlaybackStates().associateBy { it.videoId }
            videos.map { it.toNetworkVideoItem(states[it.id]) }
        }

    override suspend fun getVideo(videoId: String): NetworkVideoItem? =
        withContext(Dispatchers.IO) {
            val video = database.getNetworkVideo(videoId) ?: return@withContext null
            val state = database.getNetworkPlaybackState(videoId)
            video.toNetworkVideoItem(state)
        }

    override suspend fun searchVideos(query: String): List<NetworkVideoItem> =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            if (normalized.isBlank()) return@withContext emptyList()
            val videos = database.searchNetworkVideos(normalized)
            val states = database.getAllNetworkPlaybackStates().associateBy { it.videoId }
            videos.map { it.toNetworkVideoItem(states[it.id]) }
        }

    override suspend fun getResumeItems(): List<NetworkVideoItem> =
        withContext(Dispatchers.IO) {
            val resumeStates = database.getNetworkResumeItems()
            val stateMap = resumeStates.associateBy { it.videoId }
            resumeStates.mapNotNull { state ->
                database.getNetworkVideo(state.videoId)?.toNetworkVideoItem(stateMap[state.videoId])
            }
        }

    override suspend fun updatePlaybackState(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val watched = when {
            durationMs <= 0L -> false
            positionMs >= durationMs * WATCHED_THRESHOLD -> true
            else -> false
        }
        database.insertNetworkPlaybackState(
            NetworkPlaybackStateDto(
                videoId = videoId,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                watched = watched,
                lastPlayedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun markPlayed(videoId: String, played: Boolean) =
        withContext(Dispatchers.IO) {
            val current = database.getNetworkPlaybackState(videoId)
            database.insertNetworkPlaybackState(
                NetworkPlaybackStateDto(
                    videoId = videoId,
                    positionMs = if (played) current?.durationMs ?: 0L else 0L,
                    durationMs = current?.durationMs ?: 0L,
                    watched = played,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }

    override suspend fun enrichMetadata(shareId: String) {
        metadataMatchService.enrichShare(shareId)
    }

    override fun getStreamUrl(videoId: String): String? {
        val video = database.getNetworkVideo(videoId) ?: return null
        return streamProxy.getStreamUrl(video.shareId, video.filePath)
    }

    private fun NetworkVideoDto.toNetworkVideoItem(
        state: NetworkPlaybackStateDto?,
    ): NetworkVideoItem {
        val durationMs = state?.durationMs?.takeIf { it > 0L } ?: durationMs ?: 0L
        return NetworkVideoItem(
            networkVideoId = id,
            shareId = shareId,
            filePath = filePath,
            fileName = fileName,
            sizeBytes = sizeBytes,
            tmdbId = tmdbId,
            tmdbType = tmdbType,
            seriesGroupKey = seriesGroupKey,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            releaseYear = releaseYear,
            genres = genres?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            director = director,
            writers = writers?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            imdbId = imdbId,
            name = title,
            overview = overview ?: "",
            played = state?.watched == true,
            sources = listOf(
                SpatialFinSource(
                    id = "network-$id",
                    name = fileName,
                    type = SpatialFinSourceType.NETWORK,
                    path = filePath,
                    size = sizeBytes,
                    mediaStreams = emptyList(),
                )
            ),
            runtimeTicks = durationMs * 10000L,
            playbackPositionTicks = (state?.positionMs ?: 0L) * 10000L,
            images = SpatialFinImages(
                primary = posterUrl?.let { Uri.parse(it) },
                backdrop = backdropUrl?.let { Uri.parse(it) },
            ),
            ratings = buildList {
                voteAverage?.takeIf { it > 0 }?.let { score ->
                    add(Rating(type = RatingType.TMDB, value = String.format("%.1f", score)))
                }
                imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
                    add(Rating(
                        type = RatingType.IMDB,
                        value = rating,
                        url = imdbId?.let { "https://www.imdb.com/title/$it/" },
                    ))
                }
            },
        )
    }

    private companion object {
        private const val WATCHED_THRESHOLD = 0.9
    }
}
