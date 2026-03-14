package dev.jdtech.jellyfin.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.SpatialFinMediaStreamDto
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinSegmentDto
import dev.jdtech.jellyfin.models.SpatialFinShowDto
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfoDto
import dev.jdtech.jellyfin.models.SpatialFinUserDataDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.ServerWithAddressesAndUsers
import dev.jdtech.jellyfin.models.ServerWithUsers
import dev.jdtech.jellyfin.models.User
import java.util.UUID

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertUser(user: User)

    @Update fun update(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") fun get(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") fun getUser(id: UUID): User?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddresses(id: String): ServerWithAddresses

    @Query("SELECT * FROM serverAddresses WHERE id = :id") fun getAddress(id: UUID): ServerAddress

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithUsers(id: String): ServerWithUsers

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers")
    fun getServersWithAddresses(): List<ServerWithAddresses>

    @Query("DELETE FROM servers") fun clear()

    @Query("SELECT * FROM servers") fun getAllServersSync(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") fun getServersCount(): Int

    @Query("DELETE FROM servers WHERE id = :id") fun delete(id: String)

    @Query("DELETE FROM users WHERE id = :id") fun deleteUser(id: UUID)

    @Query("DELETE FROM serverAddresses WHERE id = :id") fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    fun updateServerCurrentUser(serverId: String, userId: UUID)

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    fun getServerCurrentUser(serverId: String): User?

    @Query(
        "SELECT * FROM serverAddresses WHERE id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId)"
    )
    fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertMovie(movie: SpatialFinMovieDto)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSource(source: SpatialFinSourceDto)

    @Query("SELECT * FROM movies WHERE id = :id") fun getMovie(id: UUID): SpatialFinMovieDto

    @Query(
        "SELECT * FROM movies JOIN sources ON movies.id = sources.itemId ORDER BY movies.name ASC"
    )
    fun getMoviesAndSources(): Map<SpatialFinMovieDto, List<SpatialFinSourceDto>>

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    fun getSources(itemId: UUID): List<SpatialFinSourceDto>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    fun getSourceByDownloadId(downloadId: Long): SpatialFinSourceDto?

    @Query("UPDATE sources SET downloadId = :downloadId WHERE id = :id")
    fun setSourceDownloadId(id: String, downloadId: Long)

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

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    fun getMediaStreamByDownloadId(downloadId: Long): SpatialFinMediaStreamDto?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    fun deleteMediaStreamsBySourceId(sourceId: String)

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    fun setPlayed(userId: UUID, itemId: UUID, played: Boolean)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    fun setFavorite(userId: UUID, itemId: UUID, favorite: Boolean)

    @Query("SELECT * FROM movies ORDER BY name ASC") fun getMovies(): List<SpatialFinMovieDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    fun getMoviesByServerId(serverId: String): List<SpatialFinMovieDto>

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertShow(show: SpatialFinShowDto)

    @Query("SELECT * FROM shows WHERE id = :id") fun getShow(id: UUID): SpatialFinShowDto

    @Query("SELECT * FROM shows ORDER BY name ASC") fun getShows(): List<SpatialFinShowDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name ASC")
    fun getShowsByServerId(serverId: String): List<SpatialFinShowDto>

    @Query("DELETE FROM shows WHERE id = :id") fun deleteShow(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertSeason(show: SpatialFinSeasonDto)

    @Query("SELECT * FROM seasons WHERE id = :id") fun getSeason(id: UUID): SpatialFinSeasonDto

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    fun getSeasonsByShowId(seriesId: UUID): List<SpatialFinSeasonDto>

    @Query("DELETE FROM seasons WHERE id = :id") fun deleteSeason(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertEpisode(episode: SpatialFinEpisodeDto)

    @Query("SELECT * FROM episodes WHERE id = :id") fun getEpisode(id: UUID): SpatialFinEpisodeDto

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

    @Query("SELECT * FROM seasons") fun getSeasons(): List<SpatialFinSeasonDto>

    @Query("SELECT * FROM episodes") fun getEpisodes(): List<SpatialFinEpisodeDto>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    fun getUserData(itemId: UUID, userId: UUID): SpatialFinUserDataDto?

    @Transaction
    fun getUserDataOrCreateNew(itemId: UUID, userId: UUID): SpatialFinUserDataDto {
        var userData = getUserData(itemId, userId)

        // Create user data when there is none
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

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    fun getUserDataToBeSynced(userId: UUID, itemId: UUID): SpatialFinUserDataDto?

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
}
