package dev.jdtech.jellyfin.database

import android.app.DownloadManager
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.LocalMediaPlaybackStateDto
import dev.jdtech.jellyfin.models.NetworkPlaybackStateDto
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.NetworkVideoDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.ServerWithAddressesAndUsers
import dev.jdtech.jellyfin.models.ServerWithUsers
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.SpatialFinMediaStreamDto
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinSegmentDto
import dev.jdtech.jellyfin.models.SpatialFinShowDto
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfoDto
import dev.jdtech.jellyfin.models.SpatialFinUserDataDto
import dev.jdtech.jellyfin.models.User
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class UserMigrationUpdate(
    val user: User,
    val clearToken: Boolean = false,
    val overwritePreferences: Boolean = false,
)

data class DeletedServerOfflineSnapshot(
    val activeBaseUrl: String?,
    val snapshottedUsers: List<User>,
    val taskIds: List<String>,
    val itemIds: Set<UUID>,
    val filePaths: Set<String>,
)

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertServerIgnore(server: Server): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertUser(user: User)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertUserIgnore(user: User): Long

    @Update fun updateUser(user: User)

    @Update fun update(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") fun get(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") fun getUser(id: UUID): User?

    @Transaction
    fun executeInTransaction(block: () -> Unit) {
        block()
    }

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddresses(id: String): ServerWithAddresses?

    @Query("SELECT * FROM serverAddresses WHERE id = :id") fun getAddress(id: UUID): ServerAddress?

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithUsers(id: String): ServerWithUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers")
    fun getServersWithAddresses(): List<ServerWithAddresses>

    @Query("SELECT id FROM servers")
    fun observeServerIds(): Flow<List<String>>

    @Query("DELETE FROM servers") fun clear()

    @Query("SELECT * FROM servers") fun getAllServersSync(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") fun getServersCount(): Int

    @Query("DELETE FROM servers WHERE id = :id") fun delete(id: String)

    @Query("DELETE FROM users WHERE id = :id") fun deleteUser(id: UUID)

    @Query("DELETE FROM serverAddresses WHERE id = :id") fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    fun updateServerCurrentUser(serverId: String, userId: UUID?)

    @Query("UPDATE servers SET currentServerAddressId = :addressId WHERE id = :serverId")
    fun updateServerCurrentAddress(serverId: String, addressId: UUID?)

    @Query("UPDATE servers SET currentUserId = NULL WHERE currentUserId = :userId")
    fun clearServersCurrentUser(userId: UUID)

    @Query("UPDATE users SET accessToken = NULL WHERE id = :userId")
    fun clearUserAccessToken(userId: UUID)

    @Query("UPDATE users SET preferences = NULL")
    fun clearAllUserPreferences()

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    fun getServerCurrentUser(serverId: String): User?

    @Query(
        """
        SELECT * FROM serverAddresses
        WHERE serverId = :serverId
        ORDER BY CASE
            WHEN id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId) THEN 0
            ELSE 1
        END
        LIMIT 1
        """
    )
    fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Query(
        """
        SELECT * FROM serverAddresses
        WHERE serverId = :serverId
        ORDER BY CASE
            WHEN id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId) THEN 0
            ELSE 1
        END
        """
    )
    fun getServerAddresses(serverId: String): List<ServerAddress>

    @Query("SELECT * FROM serverAddresses")
    fun getAllServerAddresses(): List<ServerAddress>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertMovie(movie: SpatialFinMovieDto)

    @Upsert fun upsertMovie(movie: SpatialFinMovieDto)

    @Upsert fun insertSource(source: SpatialFinSourceDto)

    @Query("SELECT * FROM movies WHERE id = :id") fun getMovie(id: UUID): SpatialFinMovieDto

    @Query("SELECT * FROM movies WHERE id = :id") fun getMovieOrNull(id: UUID): SpatialFinMovieDto?

    @Query(
        "SELECT * FROM movies JOIN sources ON movies.id = sources.itemId ORDER BY movies.name ASC"
    )
    fun getMoviesAndSources(): Map<SpatialFinMovieDto, List<SpatialFinSourceDto>>

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    fun getSources(itemId: UUID): List<SpatialFinSourceDto>

    @Query("SELECT * FROM sources")
    fun getAllSources(): List<SpatialFinSourceDto>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    fun getSourceByDownloadId(downloadId: Long): SpatialFinSourceDto?

    @Query("UPDATE sources SET downloadId = :downloadId WHERE id = :id")
    fun setSourceDownloadId(id: String, downloadId: Long)

    @Query("UPDATE sources SET downloadId = NULL WHERE id = :id")
    fun clearSourceDownloadId(id: String)

    @Query("UPDATE sources SET path = :path WHERE id = :id")
    fun setSourcePath(id: String, path: String)

    @Query("DELETE FROM sources WHERE id = :id") fun deleteSource(id: String)

    @Query("DELETE FROM movies WHERE id = :id") fun deleteMovie(id: UUID)

    @Query(
        "UPDATE userdata SET playbackPositionTicks = :playbackPositionTicks WHERE itemId = :itemId AND userid = :userId"
    )
    fun setPlaybackPositionTicks(itemId: UUID, userId: UUID, playbackPositionTicks: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMediaStream(mediaStream: SpatialFinMediaStreamDto)

    @Query("SELECT * FROM mediastreams WHERE sourceId = :sourceId")
    fun getMediaStreamsBySourceId(sourceId: String): List<SpatialFinMediaStreamDto>

    @Query("SELECT * FROM mediastreams")
    fun getAllMediaStreams(): List<SpatialFinMediaStreamDto>

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    fun getMediaStreamByDownloadId(downloadId: Long): SpatialFinMediaStreamDto?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET downloadId = NULL WHERE id = :id")
    fun clearMediaStreamDownloadId(id: UUID)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    fun deleteMediaStreamsBySourceId(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDownloadTask(downloadTask: DownloadTaskDto)

    @Query("SELECT * FROM downloadtasks WHERE id = :id")
    fun getDownloadTaskById(id: String): DownloadTaskDto?

    @Query(
        "SELECT * FROM downloadtasks WHERE itemId = :itemId AND kind = :kind ORDER BY updatedAt DESC LIMIT 1"
    )
    fun observeDownloadTask(itemId: UUID, kind: DownloadTaskKind = DownloadTaskKind.PRIMARY): Flow<DownloadTaskDto?>

    @Query(
        "SELECT * FROM downloadtasks WHERE itemId = :itemId AND kind = :kind ORDER BY updatedAt DESC LIMIT 1"
    )
    fun getDownloadTask(itemId: UUID, kind: DownloadTaskKind = DownloadTaskKind.PRIMARY): DownloadTaskDto?

    @Query("SELECT * FROM downloadtasks WHERE itemId = :itemId")
    fun getDownloadTasksByItemId(itemId: UUID): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks")
    fun getAllDownloadTasks(): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks WHERE status != 8")
    fun getIncompleteDownloadTasks(): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks WHERE errorMessage = :message")
    fun getDownloadTasksByErrorMessage(message: String): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks WHERE sourceId = :sourceId")
    fun getDownloadTasksBySourceId(sourceId: String): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks WHERE mediaStreamId = :mediaStreamId ORDER BY updatedAt DESC LIMIT 1")
    fun getDownloadTaskByMediaStreamId(mediaStreamId: UUID): DownloadTaskDto?

    @Query(
        "UPDATE downloadtasks SET downloadId = :downloadId, bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, eTag = :eTag, lastModified = :lastModified, status = :status, progress = :progress, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id"
    )
    fun updateDownloadTask(
        id: String,
        downloadId: Long?,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        status: Int,
        progress: Int,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE downloadtasks SET requestUrl = :requestUrl, accessToken = :accessToken, updatedAt = :updatedAt WHERE id = :id"
    )
    fun updateDownloadTaskRequestAndToken(
        id: String,
        requestUrl: String,
        accessToken: String?,
        updatedAt: Long,
    )

    @Query(
        "UPDATE downloadtasks SET isEncrypted = :isEncrypted, encryptionIv = :encryptionIv, updatedAt = :updatedAt WHERE id = :id"
    )
    fun setDownloadTaskEncryption(
        id: String,
        isEncrypted: Boolean,
        encryptionIv: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE downloadtasks
        SET bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            eTag = :eTag,
            lastModified = :lastModified,
            status = 8,
            progress = 100,
            errorMessage = NULL,
            isEncrypted = :isEncrypted,
            encryptionIv = :encryptionIv,
            updatedAt = :updatedAt
        WHERE id = :id AND status = 2
        """
    )
    fun finalizeCompletedDownloadInternal(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        isEncrypted: Boolean,
        encryptionIv: String?,
        updatedAt: Long,
    ): Int

    /**
     * Atomically marks a `STATUS_RUNNING` (`2`) task as `STATUS_SUCCESSFUL` (`8`) alongside
     * `(isEncrypted, encryptionIv)` so a concurrent pause/lock/delete cannot be overwritten.
     * Returns `false` if the task is no longer running.
     */
    @Transaction
    fun finalizeCompletedDownload(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        eTag: String?,
        lastModified: String?,
        isEncrypted: Boolean,
        encryptionIv: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean =
        finalizeCompletedDownloadInternal(
            id = id,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            eTag = eTag,
            lastModified = lastModified,
            isEncrypted = isEncrypted,
            encryptionIv = encryptionIv,
            updatedAt = updatedAt,
        ) > 0

    @Query("SELECT * FROM downloadtasks WHERE finalPath = :finalPath LIMIT 1")
    fun getDownloadTaskByFinalPath(finalPath: String): DownloadTaskDto?

    @Query("SELECT COUNT(*) FROM downloadtasks WHERE isEncrypted = 0 AND finalPath != ''")
    fun countUnencryptedDownloads(): Int

    @Query("SELECT * FROM downloadtasks WHERE isEncrypted = 0 AND finalPath != ''")
    fun getUnencryptedDownloads(): List<DownloadTaskDto>

    @Query("SELECT * FROM downloadtasks WHERE isEncrypted = 1")
    fun getEncryptedDownloads(): List<DownloadTaskDto>

    @Query("DELETE FROM downloadtasks WHERE itemId = :itemId")
    fun deleteDownloadTasksByItemId(itemId: UUID)

    @Query("DELETE FROM downloadtasks WHERE id IN (:ids)")
    fun deleteDownloadTasksByIds(ids: List<String>)

    @Query("DELETE FROM downloadtasks WHERE itemId = :itemId AND sourceId = :sourceId")
    fun deleteDownloadTask(itemId: UUID, sourceId: String)

    @Query("DELETE FROM downloadtasks WHERE mediaStreamId = :mediaStreamId")
    fun deleteDownloadTaskByMediaStreamId(mediaStreamId: UUID)

    @Query("SELECT * FROM downloadtasks WHERE kind = 'PRIMARY' AND status != 8 ORDER BY updatedAt DESC")
    fun observeActiveDownloadTasks(): Flow<List<DownloadTaskDto>>

    @Query("SELECT * FROM downloadtasks WHERE kind = :kind AND status = 8")
    fun getCompletedDownloadTasks(kind: DownloadTaskKind): List<DownloadTaskDto>

    @Query(
        "SELECT COALESCE((SELECT serverId FROM movies WHERE id = :itemId), (SELECT serverId FROM episodes WHERE id = :itemId))"
    )
    fun getDownloadTaskServerId(itemId: UUID): String?

    fun resolveDownloadTaskServerId(task: DownloadTaskDto): String? {
        getDownloadTaskServerId(task.itemId)?.let { return it }
        val reqUrl = task.requestUrl.toHttpUrlOrNull() ?: return null
        val allAddresses = getAllServerAddresses()
        val parsed =
            allAddresses.mapNotNull { addr ->
                val url = addr.address.toHttpUrlOrNull() ?: return@mapNotNull null
                Triple(addr.serverId, url, pathSegmentsOf(url))
            }
        val matched =
            parsed
                .filter { (_, url, segs) -> matchesPrefix(reqUrl, url, segs) != null }
                .sortedByDescending { (_, _, segs) -> segs.size }
        if (matched.isEmpty()) return null
        val bestDepth = matched.first().third.size
        val owners = matched.filter { it.third.size == bestDepth }.map { it.first }.toSet()
        return owners.singleOrNull()
    }

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    fun setPlayed(userId: UUID, itemId: UUID, played: Boolean)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    fun setFavorite(userId: UUID, itemId: UUID, favorite: Boolean)

    @Query("SELECT * FROM movies ORDER BY name ASC") fun getMovies(): List<SpatialFinMovieDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    fun getMoviesByServerId(serverId: String): List<SpatialFinMovieDto>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertShow(show: SpatialFinShowDto)

    @Upsert fun upsertShow(show: SpatialFinShowDto)

    @Query("SELECT * FROM shows WHERE id = :id") fun getShow(id: UUID): SpatialFinShowDto

    @Query("SELECT * FROM shows WHERE id = :id") fun getShowOrNull(id: UUID): SpatialFinShowDto?

    @Query("SELECT * FROM shows ORDER BY name ASC") fun getShows(): List<SpatialFinShowDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name ASC")
    fun getShowsByServerId(serverId: String): List<SpatialFinShowDto>

    @Query("DELETE FROM shows WHERE id = :id") fun deleteShow(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertSeason(show: SpatialFinSeasonDto)

    @Upsert fun upsertSeason(season: SpatialFinSeasonDto)

    @Query("SELECT * FROM seasons WHERE id = :id") fun getSeason(id: UUID): SpatialFinSeasonDto

    @Query("SELECT * FROM seasons WHERE id = :id") fun getSeasonOrNull(id: UUID): SpatialFinSeasonDto?

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    fun getSeasonsByShowId(seriesId: UUID): List<SpatialFinSeasonDto>

    @Query("DELETE FROM seasons WHERE id = :id") fun deleteSeason(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertEpisode(episode: SpatialFinEpisodeDto)

    @Upsert fun upsertEpisode(episode: SpatialFinEpisodeDto)

    @Query("SELECT * FROM episodes WHERE id = :id") fun getEpisode(id: UUID): SpatialFinEpisodeDto

    @Query("SELECT * FROM episodes WHERE id = :id") fun getEpisodeOrNull(id: UUID): SpatialFinEpisodeDto?

    @Query(
        "SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY parentIndexNumber ASC, indexNumber ASC"
    )
    fun getEpisodesByShowId(seriesId: UUID): List<SpatialFinEpisodeDto>

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY indexNumber ASC")
    fun getEpisodesBySeasonId(seasonId: UUID): List<SpatialFinEpisodeDto>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    fun getEpisodesByServerId(serverId: String): List<SpatialFinEpisodeDto>

    @Query(
        "SELECT episodes.id, episodes.serverId, episodes.seasonId, episodes.seriesId, episodes.name, episodes.seriesName, episodes.overview, episodes.indexNumber, episodes.indexNumberEnd, episodes.parentIndexNumber, episodes.runtimeTicks, episodes.premiereDate, episodes.communityRating, episodes.chapters FROM episodes INNER JOIN userdata ON episodes.id = userdata.itemId WHERE serverId = :serverId AND playbackPositionTicks > 0 ORDER BY episodes.parentIndexNumber ASC, episodes.indexNumber ASC"
    )
    fun getEpisodeResumeItems(serverId: String): List<SpatialFinEpisodeDto>

    @Query("DELETE FROM episodes WHERE id = :id") fun deleteEpisode(id: UUID)

    @Query("DELETE FROM episodes WHERE seasonId = :seasonId")
    fun deleteEpisodesBySeasonId(seasonId: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSegment(segment: SpatialFinSegmentDto)

    @Query("SELECT * FROM segments WHERE itemId = :itemId")
    fun getSegments(itemId: UUID): List<SpatialFinSegmentDto>

    @Query("DELETE FROM segments WHERE itemId = :itemId")
    fun deleteSegmentsByItemId(itemId: UUID)

    @Query("SELECT * FROM seasons") fun getSeasons(): List<SpatialFinSeasonDto>

    @Query("SELECT * FROM episodes") fun getEpisodes(): List<SpatialFinEpisodeDto>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    fun getUserData(itemId: UUID, userId: UUID): SpatialFinUserDataDto?

    @Query("SELECT * FROM userdata WHERE userId = :userId")
    fun getUserDataByUserId(userId: UUID): List<SpatialFinUserDataDto>

    @Transaction
    fun getUserDataOrCreateNew(itemId: UUID, userId: UUID): SpatialFinUserDataDto {
        var userData = getUserData(itemId, userId)
        if (userData == null) {
            userData =
                SpatialFinUserDataDto(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            insertUserData(userData)
        }
        return userData
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertUserData(userData: SpatialFinUserDataDto)

    @Query("DELETE FROM userdata WHERE itemId = :itemId") fun deleteUserData(itemId: UUID)

    @Query("DELETE FROM userdata WHERE userId = :userId AND itemId = :itemId")
    fun deleteUserDataForUserAndItem(userId: UUID, itemId: UUID)

    @Query("DELETE FROM userdata WHERE userId IN (:userIds)")
    fun deleteUserDataByUserIds(userIds: List<UUID>)

    @Query("DELETE FROM userdata WHERE itemId IN (:itemIds)")
    fun deleteUserDataByItemIds(itemIds: List<UUID>)

    @Query("UPDATE userdata SET userId = :newUserId WHERE userId = :oldUserId")
    fun repointUserDataUserId(oldUserId: UUID, newUserId: UUID)

    @Query("DELETE FROM userdata WHERE itemId = :itemId AND toBeSynced = 0")
    fun deleteSyncedUserData(itemId: UUID)

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    fun getUserDataToBeSynced(userId: UUID, itemId: UUID): SpatialFinUserDataDto?

    @Query("SELECT * FROM userdata WHERE userId = :userId AND toBeSynced = 1")
    fun getUserDataToBeSynced(userId: UUID): List<SpatialFinUserDataDto>

    @Query("SELECT COUNT(*) FROM userdata WHERE toBeSynced = 1")
    fun getPendingUserDataSyncCount(): Int

    @Query("SELECT COUNT(*) FROM userdata WHERE toBeSynced = 1")
    fun observePendingUserDataSyncCount(): Flow<Int>

    @Query(
        "UPDATE userdata SET toBeSynced = :toBeSynced WHERE itemId = :itemId AND userId = :userId"
    )
    fun setUserDataToBeSynced(userId: UUID, itemId: UUID, toBeSynced: Boolean)

    @Query("SELECT * FROM movies WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchMovies(serverId: String, name: String): List<SpatialFinMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchShows(serverId: String, name: String): List<SpatialFinShowDto>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    fun searchEpisodes(serverId: String, name: String): List<SpatialFinEpisodeDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrickplayInfo(trickplayInfoDto: SpatialFinTrickplayInfoDto)

    @Query("SELECT * FROM trickplayInfos WHERE sourceId = :sourceId")
    fun getTrickplayInfo(sourceId: String): SpatialFinTrickplayInfoDto?

    @Query("UPDATE users SET serverId = :newServerId WHERE serverId = :oldServerId")
    fun repointUsersServerId(oldServerId: String, newServerId: String)

    @Query("UPDATE serverAddresses SET serverId = :newServerId WHERE serverId = :oldServerId")
    fun repointServerAddressesServerId(oldServerId: String, newServerId: String)

    @Query("UPDATE movies SET serverId = :newServerId WHERE serverId = :oldServerId")
    fun repointMoviesServerId(oldServerId: String, newServerId: String)

    @Query("UPDATE shows SET serverId = :newServerId WHERE serverId = :oldServerId")
    fun repointShowsServerId(oldServerId: String, newServerId: String)

    @Query("UPDATE episodes SET serverId = :newServerId WHERE serverId = :oldServerId")
    fun repointEpisodesServerId(oldServerId: String, newServerId: String)

    // Step A1d shared helper: rewrites incomplete downloadtasks requestUrl origin/base-path
    fun rewriteIncompleteDownloadRequestUrls(serverIds: Set<String>, targetAddress: String) {
        rewriteIncompleteDownloadTasksInternal(
            serverIds = serverIds,
            targetAddress = targetAddress,
            tokenUpdates = emptyList(),
            revokedDownloadTokenDigests = emptySet(),
        )
    }

    /**
     * Atomic legacy `srv-*` -> canonical `SystemInfo.Id` server merge, address deduplication,
     * household user + `userdata` merge, scoped `downloadtasks` refresh, and child repointing
     * (Steps A1a–A1e). Returns `(oldUserId -> canonicalUserId)` pairs when user IDs merged.
     */
    @Transaction
    fun migrateLegacyServer(
        shellIds: Set<String>,
        canonicalServer: Server,
        targetAddress: String?,
        addresses: List<String>,
        users: List<UserMigrationUpdate> = emptyList(),
        revokedDownloadTokenDigests: Set<String> = emptySet(),
    ): List<Pair<UUID, UUID>> {
        val cleanedShellIds = shellIds.filter { it.isNotBlank() && it != canonicalServer.id }.toSet()
        val scopeServerIds = cleanedShellIds + canonicalServer.id

        // Snapshot all candidate ServerAddress rows across scope BEFORE mutations for Step A1d
        val preMutationScopeAddresses =
            scopeServerIds.flatMap { getServerAddresses(it) }.map { it.address }.distinct()

        // Step A1a: Server merge-upsert (never OnConflictStrategy.REPLACE)
        val existingCanonical = get(canonicalServer.id)
        val primaryShell = cleanedShellIds.firstNotNullOfOrNull { get(it) }
        val mergedServer =
            Server(
                id = canonicalServer.id,
                name = canonicalServer.name.ifBlank {
                    existingCanonical?.name ?: primaryShell?.name ?: canonicalServer.name
                },
                currentServerAddressId =
                    canonicalServer.currentServerAddressId
                        ?: existingCanonical?.currentServerAddressId
                        ?: primaryShell?.currentServerAddressId,
                currentUserId =
                    canonicalServer.currentUserId
                        ?: existingCanonical?.currentUserId
                        ?: primaryShell?.currentUserId,
            )
        if (existingCanonical == null) {
            insertServerIgnore(mergedServer)
        }
        update(mergedServer)

        // Step A1b: ServerAddress deduplication across (shellIds + canonicalServer.id)
        val existingAddresses = scopeServerIds.flatMap { getServerAddresses(it) }
        val canonicalAddressByNorm = LinkedHashMap<String, ServerAddress>()
        val duplicateAddressIds = mutableListOf<UUID>()
        var remappedCurrentAddressId = mergedServer.currentServerAddressId

        // Prefer existing canonical-server addresses first, then shells
        val orderedExisting =
            existingAddresses.sortedBy { if (it.serverId == canonicalServer.id) 0 else 1 }
        for (addr in orderedExisting) {
            val norm = normalizeAddress(addr.address)
            val kept = canonicalAddressByNorm[norm]
            if (kept == null) {
                canonicalAddressByNorm[norm] = addr
            } else {
                if (remappedCurrentAddressId == addr.id) {
                    remappedCurrentAddressId = kept.id
                }
                duplicateAddressIds += addr.id
            }
        }

        // Insert any newly supplied addresses (including targetAddress) that don't already exist
        val allDesiredUrls = buildList {
            targetAddress?.takeIf { it.isNotBlank() }?.let { add(it) }
            addresses.filter { it.isNotBlank() }.forEach { add(it) }
        }
        for (rawUrl in allDesiredUrls) {
            val norm = normalizeAddress(rawUrl)
            if (norm !in canonicalAddressByNorm) {
                val created = ServerAddress(
                    id = UUID.randomUUID(),
                    serverId = canonicalServer.id,
                    address = norm,
                )
                insertServerAddress(created)
                canonicalAddressByNorm[norm] = created
            }
        }
        if (targetAddress != null && targetAddress.isNotBlank()) {
            val normTarget = normalizeAddress(targetAddress)
            canonicalAddressByNorm[normTarget]?.let { remappedCurrentAddressId = it.id }
        }

        // Step A1c: Household User and userdata merge
        val existingCanonicalUsers = getUsers(canonicalServer.id).associateBy { it.id }.toMutableMap()
        val shellUsers = cleanedShellIds.flatMap { getUsers(it) }
        val userIdRemap = LinkedHashMap<UUID, UUID>()
        val usersToDelete = mutableSetOf<UUID>()
        val tokenRefreshEntries = mutableListOf<TokenRefreshSpec>()

        fun mergeUserData(oldUserId: UUID, newUserId: UUID) {
            if (oldUserId == newUserId) return
            val oldRows = getUserDataByUserId(oldUserId)
            for (oldRow in oldRows) {
                val existing = getUserData(oldRow.itemId, newUserId)
                if (existing != null) {
                    val mergedFavorite = existing.favorite || oldRow.favorite
                    val mergedToBeSynced = existing.toBeSynced || oldRow.toBeSynced
                    val (mergedPlayed, mergedTicks) =
                        when {
                            existing.toBeSynced && !oldRow.toBeSynced ->
                                existing.played to existing.playbackPositionTicks
                            !existing.toBeSynced && oldRow.toBeSynced ->
                                oldRow.played to oldRow.playbackPositionTicks
                            else -> {
                                val played = existing.played || oldRow.played
                                val ticks =
                                    if (existing.played != oldRow.played) 0L
                                    else maxOf(existing.playbackPositionTicks, oldRow.playbackPositionTicks)
                                played to ticks
                            }
                        }
                    insertUserData(
                        SpatialFinUserDataDto(
                            userId = newUserId,
                            itemId = oldRow.itemId,
                            played = mergedPlayed,
                            favorite = mergedFavorite,
                            playbackPositionTicks = mergedTicks,
                            toBeSynced = mergedToBeSynced,
                        )
                    )
                    deleteUserDataForUserAndItem(oldUserId, oldRow.itemId)
                }
            }
            repointUserDataUserId(oldUserId, newUserId)
        }

        // Index shell users by id and by unique (shellId, lowerName)
        val shellUsersById = shellUsers.associateBy { it.id }
        val uniqueShellUserByLowerName: Map<String, User> =
            shellUsers
                .groupBy { it.name.lowercase(Locale.ROOT) }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }

        val handledShellUserIds = mutableSetOf<UUID>()

        // Process explicit updates first
        for (update in users) {
            val targetId = update.user.id
            val canonicalRow = existingCanonicalUsers[targetId]
            val legacyRow =
                shellUsersById[targetId]
                    ?: uniqueShellUserByLowerName[update.user.name.lowercase(Locale.ROOT)]
                        ?.takeIf { it.id !in handledShellUserIds && it.id !in existingCanonicalUsers }

            val previousTokens = buildSet {
                canonicalRow?.accessToken?.takeIf { it.isNotBlank() }?.let(::add)
                legacyRow?.accessToken?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val resolvedToken =
                when {
                    update.clearToken -> null
                    !update.user.accessToken.isNullOrBlank() -> update.user.accessToken
                    else ->
                        canonicalRow?.accessToken?.takeIf { it.isNotBlank() }
                            ?: legacyRow?.accessToken
                }
            val resolvedPrefs =
                if (update.overwritePreferences) {
                    update.user.preferences
                } else {
                    update.user.preferences
                        ?: canonicalRow?.preferences
                        ?: legacyRow?.preferences
                }
            val finalUser =
                User(
                    id = targetId,
                    name = update.user.name.ifBlank { canonicalRow?.name ?: legacyRow?.name ?: "" },
                    serverId = canonicalServer.id,
                    accessToken = resolvedToken,
                    preferences = resolvedPrefs,
                )

            if (legacyRow != null) {
                handledShellUserIds += legacyRow.id
                if (legacyRow.id != targetId) {
                    userIdRemap[legacyRow.id] = targetId
                    mergeUserData(legacyRow.id, targetId)
                    usersToDelete += legacyRow.id
                }
            }

            if (canonicalRow == null && (legacyRow == null || legacyRow.id != targetId)) {
                insertUserIgnore(finalUser)
            }
            updateUser(finalUser)
            existingCanonicalUsers[targetId] = finalUser

            if (!resolvedToken.isNullOrBlank() && resolvedToken !in previousTokens) {
                tokenRefreshEntries += TokenRefreshSpec(
                    previousTokens = previousTokens,
                    newToken = resolvedToken,
                )
            }
        }

        // Process remaining non-updated shell users
        for (shellUser in shellUsers) {
            if (shellUser.id in handledShellUserIds) continue
            val existingById = existingCanonicalUsers[shellUser.id]
            val existingByName =
                if (existingById == null) {
                    val sameNameCanonical =
                        existingCanonicalUsers.values.filter {
                            it.name.equals(shellUser.name, ignoreCase = true)
                        }
                    if (sameNameCanonical.size == 1) sameNameCanonical.single() else null
                } else {
                    null
                }
            val match = existingById ?: existingByName
            if (match != null) {
                val mergedToken = match.accessToken?.takeIf { it.isNotBlank() } ?: shellUser.accessToken
                val mergedPrefs = match.preferences ?: shellUser.preferences
                val updated = match.copy(
                    serverId = canonicalServer.id,
                    accessToken = mergedToken,
                    preferences = mergedPrefs,
                )
                updateUser(updated)
                existingCanonicalUsers[match.id] = updated
                if (shellUser.id != match.id) {
                    userIdRemap[shellUser.id] = match.id
                    mergeUserData(shellUser.id, match.id)
                    usersToDelete += shellUser.id
                }
            } else {
                // Keep shellUser with same UUID; Step A1e repoints serverId to canonicalServer.id
                updateUser(shellUser.copy(serverId = canonicalServer.id))
                existingCanonicalUsers[shellUser.id] = shellUser.copy(serverId = canonicalServer.id)
            }
        }

        // Step A1d: Scoped downloadtasks refresh
        if (!targetAddress.isNullOrBlank() || tokenRefreshEntries.isNotEmpty()) {
            rewriteIncompleteDownloadTasksInternal(
                serverIds = scopeServerIds,
                targetAddress = targetAddress,
                extraCandidateAddresses = preMutationScopeAddresses,
                tokenUpdates = tokenRefreshEntries,
                revokedDownloadTokenDigests = revokedDownloadTokenDigests,
            )
        }

        // Step A1e: Repoint surviving child rows before deleting shellIds
        for (dupAddrId in duplicateAddressIds) {
            deleteServerAddress(dupAddrId)
        }
        for (shellId in cleanedShellIds) {
            repointUsersServerId(shellId, canonicalServer.id)
            repointServerAddressesServerId(shellId, canonicalServer.id)
            repointMoviesServerId(shellId, canonicalServer.id)
            repointShowsServerId(shellId, canonicalServer.id)
            repointEpisodesServerId(shellId, canonicalServer.id)
        }
        for (oldUserId in usersToDelete) {
            deleteUser(oldUserId)
        }

        var remappedCurrentUserId =
            mergedServer.currentUserId?.let { userIdRemap[it] ?: it }
        if (remappedCurrentUserId != null && users.any { it.clearToken && it.user.id == remappedCurrentUserId }) {
            remappedCurrentUserId = null
        }

        updateServerCurrentAddress(canonicalServer.id, remappedCurrentAddressId)
        updateServerCurrentUser(canonicalServer.id, remappedCurrentUserId)

        for (shellId in cleanedShellIds) {
            delete(shellId)
        }

        return userIdRemap.entries.map { it.key to it.value }
    }

    /**
     * Atomic server deletion snapshot + cascade cleanup (Step A1f).
     */
    @Transaction
    fun deleteServerAndOfflineMedia(serverId: String): DeletedServerOfflineSnapshot {
        val activeBaseUrl = getServerCurrentAddress(serverId)?.address
        val snapshottedUsers = getUsers(serverId)
        val userIds = snapshottedUsers.map { it.id }

        val movies = getMoviesByServerId(serverId)
        val shows = getShowsByServerId(serverId)
        val seasons = shows.flatMap { getSeasonsByShowId(it.id) }
        val episodes =
            (getEpisodesByServerId(serverId) + shows.flatMap { getEpisodesByShowId(it.id) })
                .distinctBy { it.id }

        val serverItemIds = LinkedHashSet<UUID>()
        movies.forEach { serverItemIds += it.id }
        shows.forEach { serverItemIds += it.id }
        seasons.forEach { serverItemIds += it.id }
        episodes.forEach { serverItemIds += it.id }

        val isLastServer = getServersCount() <= 1
        val allTasks = getAllDownloadTasks()
        val matchedTasks =
            allTasks.filter { task ->
                val resolvedOwner = resolveDownloadTaskServerId(task)
                resolvedOwner == serverId || (resolvedOwner == null && isLastServer)
            }
        matchedTasks.forEach { serverItemIds += it.itemId }

        val filePaths = LinkedHashSet<String>()
        val taskIds = matchedTasks.map { it.id }
        for (task in matchedTasks) {
            if (task.tempPath.isNotBlank()) filePaths += task.tempPath
            if (task.finalPath.isNotBlank()) filePaths += task.finalPath
        }

        val sourcesToDelete = serverItemIds.flatMap { getSources(it) }.distinctBy { it.id }
        for (src in sourcesToDelete) {
            if (src.path.isNotBlank()) filePaths += src.path
            val streams = getMediaStreamsBySourceId(src.id)
            for (ms in streams) {
                if (ms.path.isNotBlank()) filePaths += ms.path
                deleteMediaStream(ms.id)
            }
            deleteSource(src.id)
        }

        if (taskIds.isNotEmpty()) {
            deleteDownloadTasksByIds(taskIds)
        }
        for (ep in episodes) {
            deleteSegmentsByItemId(ep.id)
            deleteEpisode(ep.id)
        }
        for (show in shows) {
            deleteShow(show.id)
        }
        for (movie in movies) {
            deleteMovie(movie.id)
        }
        if (userIds.isNotEmpty()) {
            deleteUserDataByUserIds(userIds)
        }
        if (serverItemIds.isNotEmpty()) {
            deleteUserDataByItemIds(serverItemIds.toList())
        }
        delete(serverId)

        return DeletedServerOfflineSnapshot(
            activeBaseUrl = activeBaseUrl,
            snapshottedUsers = snapshottedUsers,
            taskIds = taskIds,
            itemIds = serverItemIds,
            filePaths = filePaths,
        )
    }

    private data class TokenRefreshSpec(
        val previousTokens: Set<String>,
        val newToken: String,
    )

    private fun rewriteIncompleteDownloadTasksInternal(
        serverIds: Set<String>,
        targetAddress: String?,
        extraCandidateAddresses: List<String> = emptyList(),
        tokenUpdates: List<TokenRefreshSpec> = emptyList(),
        revokedDownloadTokenDigests: Set<String> = emptySet(),
    ) {
        val targetHttpUrl = targetAddress?.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        val scopeAddresses =
            (serverIds.flatMap { getServerAddresses(it) }.map { it.address } + extraCandidateAddresses)
                .distinct()
                .mapNotNull { raw ->
                    val parsed = raw.toHttpUrlOrNull() ?: return@mapNotNull null
                    parsed to pathSegmentsOf(parsed)
                }
                .sortedWith(
                    compareByDescending<Pair<HttpUrl, List<String>>> { it.second.size }
                        .thenByDescending { it.first.toString().length }
                )

        // Check whether any address prefix is shared with another server outside serverIds
        val otherServerAddresses =
            getAllServerAddresses()
                .filter { it.serverId !in serverIds }
                .mapNotNull { raw ->
                    val parsed = raw.address.toHttpUrlOrNull() ?: return@mapNotNull null
                    parsed to pathSegmentsOf(parsed)
                }

        val now = System.currentTimeMillis()
        val incomplete = getIncompleteDownloadTasks()
        for (task in incomplete) {
            val itemOwner = getDownloadTaskServerId(task.itemId)
            val parsedReqUrl = task.requestUrl.toHttpUrlOrNull()
            val belongsToScope =
                when {
                    itemOwner != null -> itemOwner in serverIds
                    parsedReqUrl != null -> {
                        val matchesScope =
                            scopeAddresses.any { (url, segs) -> matchesPrefix(parsedReqUrl, url, segs) != null }
                        val matchesOther =
                            otherServerAddresses.any { (url, segs) -> matchesPrefix(parsedReqUrl, url, segs) != null }
                        matchesScope && !matchesOther
                    }
                    else -> false
                }
            if (!belongsToScope) continue

            var nextUrl = task.requestUrl
            var nextToken = task.accessToken

            // 1. Rewrite origin/base-path if targetHttpUrl is provided and requestUrl matches a known scope prefix
            if (targetHttpUrl != null && parsedReqUrl != null) {
                for ((candidateUrl, candidateSegs) in scopeAddresses) {
                    val remaining = matchesPrefix(parsedReqUrl, candidateUrl, candidateSegs)
                    if (remaining != null) {
                        val builder =
                            targetHttpUrl.newBuilder()
                                .encodedPath("/")
                                .query(null)
                                .fragment(parsedReqUrl.fragment)
                        val targetBaseSegs = pathSegmentsOf(targetHttpUrl)
                        for (seg in targetBaseSegs) {
                            builder.addPathSegment(seg)
                        }
                        for (seg in remaining) {
                            builder.addPathSegment(seg)
                        }
                        for (i in 0 until parsedReqUrl.querySize) {
                            builder.addQueryParameter(
                                parsedReqUrl.queryParameterName(i),
                                parsedReqUrl.queryParameterValue(i),
                            )
                        }
                        nextUrl = builder.build().toString()
                        break
                    }
                }
            }

            // 2. Refresh token only when this task belonged to a refreshed user
            val matchedTokenSpec =
                tokenUpdates.firstOrNull { spec ->
                    val rowTok = task.accessToken
                    !rowTok.isNullOrBlank() &&
                        (rowTok in spec.previousTokens ||
                            (spec.previousTokens.isEmpty() && sha256Hex(rowTok) in revokedDownloadTokenDigests))
                }
            if (matchedTokenSpec != null) {
                nextToken = matchedTokenSpec.newToken
                val urlForToken = nextUrl.toHttpUrlOrNull()
                if (urlForToken != null && urlForToken.querySize > 0) {
                    val builder = urlForToken.newBuilder().query(null)
                    for (i in 0 until urlForToken.querySize) {
                        val name = urlForToken.queryParameterName(i)
                        val value = urlForToken.queryParameterValue(i)
                        if (name.equals("api_key", ignoreCase = true) || name.equals("apikey", ignoreCase = true)) {
                            builder.addQueryParameter(name, matchedTokenSpec.newToken)
                        } else {
                            builder.addQueryParameter(name, value)
                        }
                    }
                    nextUrl = builder.build().toString()
                }
            }

            if (nextUrl != task.requestUrl || nextToken != task.accessToken) {
                updateDownloadTaskRequestAndToken(
                    id = task.id,
                    requestUrl = nextUrl,
                    accessToken = nextToken,
                    updatedAt = now,
                )
            }
        }
    }

    private fun normalizeAddress(url: String): String = url.trim().trimEnd('/')

    private fun pathSegmentsOf(url: HttpUrl): List<String> =
        url.pathSegments.dropLastWhile { it.isEmpty() }

    private fun matchesPrefix(
        requestUrl: HttpUrl,
        prefixUrl: HttpUrl,
        prefixSegments: List<String>,
    ): List<String>? {
        if (!requestUrl.scheme.equals(prefixUrl.scheme, ignoreCase = true)) return null
        if (!requestUrl.host.equals(prefixUrl.host, ignoreCase = true)) return null
        if (requestUrl.port != prefixUrl.port) return null
        val reqSegs = requestUrl.pathSegments
        if (reqSegs.size < prefixSegments.size) return null
        for (i in prefixSegments.indices) {
            if (reqSegs[i] != prefixSegments[i]) return null
        }
        return reqSegs.subList(prefixSegments.size, reqSegs.size)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append("%02x".format(b.toInt() and 0xff))
            }
        }
    }

    @Query("SELECT * FROM localMediaPlaybackState")
    fun getAllLocalMediaPlaybackStates(): List<LocalMediaPlaybackStateDto>

    @Query("SELECT * FROM localMediaPlaybackState WHERE mediaStoreId = :mediaStoreId")
    fun getLocalMediaPlaybackState(mediaStoreId: Long): LocalMediaPlaybackStateDto?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLocalMediaPlaybackState(state: LocalMediaPlaybackStateDto)

    // Network shares

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNetworkShare(share: NetworkShareDto)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNetworkShareIgnore(share: NetworkShareDto): Long

    @Update
    fun updateNetworkShare(share: NetworkShareDto)

    @Query("SELECT * FROM networkShares ORDER BY addedAtEpochMs DESC")
    fun getNetworkShares(): List<NetworkShareDto>

    @Query("SELECT * FROM networkShares ORDER BY addedAtEpochMs DESC")
    fun observeNetworkShares(): Flow<List<NetworkShareDto>>

    @Query("SELECT COUNT(*) FROM networkShares")
    fun observeNetworkShareCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM networkVideos")
    fun observeNetworkVideoCount(): Flow<Int>

    @Query("SELECT * FROM networkShares WHERE id = :id")
    fun getNetworkShare(id: String): NetworkShareDto?

    @Query("DELETE FROM networkShares WHERE id = :id")
    fun deleteNetworkShare(id: String)

    @Query("UPDATE networkShares SET lastScannedAtEpochMs = :timestamp WHERE id = :id")
    fun updateNetworkShareLastScanned(id: String, timestamp: Long)

    // Network videos

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNetworkVideo(video: NetworkVideoDto)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNetworkVideos(videos: List<NetworkVideoDto>)

    @Query("SELECT * FROM networkVideos WHERE shareId = :shareId ORDER BY title ASC")
    fun getNetworkVideosByShare(shareId: String): List<NetworkVideoDto>

    @Query("SELECT * FROM networkVideos WHERE id = :id")
    fun getNetworkVideo(id: String): NetworkVideoDto?

    @Query("SELECT * FROM networkVideos ORDER BY title ASC")
    fun getAllNetworkVideos(): List<NetworkVideoDto>

    @Query("SELECT * FROM networkVideos WHERE title LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%'")
    fun searchNetworkVideos(query: String): List<NetworkVideoDto>

    @Query("DELETE FROM networkVideos WHERE shareId = :shareId")
    fun deleteNetworkVideosByShare(shareId: String)

    @Query("DELETE FROM networkVideos WHERE id = :id")
    fun deleteNetworkVideo(id: String)

    // Network playback state

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNetworkPlaybackState(state: NetworkPlaybackStateDto)

    @Query("SELECT * FROM networkPlaybackState WHERE videoId = :videoId")
    fun getNetworkPlaybackState(videoId: String): NetworkPlaybackStateDto?

    @Query("SELECT * FROM networkPlaybackState")
    fun getAllNetworkPlaybackStates(): List<NetworkPlaybackStateDto>

    @Query("SELECT * FROM networkPlaybackState WHERE positionMs > 0 ORDER BY lastPlayedAtEpochMs DESC")
    fun getNetworkResumeItems(): List<NetworkPlaybackStateDto>
}
