package dev.jdtech.jellyfin.database

import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.DownloadTaskKind
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinSourceType
import dev.jdtech.jellyfin.models.SpatialFinUserDataDto
import dev.jdtech.jellyfin.models.User
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerDatabaseMigrationTest {
    private lateinit var db: ServerDatabase
    private lateinit var dao: ServerDatabaseDao

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.getServerDatabaseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migrateLegacyServer merges shell server, deduplicates addresses, merges userdata, and rewrites scoped downloadtasks`() {
        val shellId = "srv-legacy-1"
        val canonicalId = "abc123def456"
        val legacyAddrId = UUID.randomUUID()
        val legacyUserId = UUID.randomUUID()
        val canonicalUserId = UUID.randomUUID()
        val movieId = UUID.randomUUID()

        dao.insertServer(
            Server(
                id = shellId,
                name = "Old Shell",
                currentServerAddressId = legacyAddrId,
                currentUserId = legacyUserId,
            )
        )
        dao.insertServerAddress(
            ServerAddress(
                id = legacyAddrId,
                serverId = shellId,
                address = "http://192.168.1.50:8096/jellyfin",
            )
        )
        dao.insertUser(
            User(
                id = legacyUserId,
                name = "Alice",
                serverId = shellId,
                accessToken = "old-token-alice",
                preferences = """{"pref_theme":"dark"}""",
            )
        )
        dao.insertMovie(
            SpatialFinMovieDto(
                id = movieId,
                serverId = shellId,
                name = "Dune",
                originalTitle = "Dune",
                overview = "",
                runtimeTicks = 1000L,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "Ended",
                productionYear = 2021,
                endDate = null,
                chapters = null,
            )
        )
        dao.insertUserData(
            SpatialFinUserDataDto(
                userId = legacyUserId,
                itemId = movieId,
                played = false,
                favorite = true,
                playbackPositionTicks = 500L,
                toBeSynced = true,
            )
        )
        dao.insertDownloadTask(
            DownloadTaskDto(
                id = "task-1",
                itemId = movieId,
                sourceId = "src-1",
                kind = DownloadTaskKind.PRIMARY,
                mediaStreamId = null,
                downloadId = null,
                requestUrl = "http://192.168.1.50:8096/jellyfin/Videos/$movieId/stream?api_key=old-token-alice",
                accessToken = "old-token-alice",
                tempPath = "/tmp/a.download",
                finalPath = "/tmp/a.mp4",
                bytesDownloaded = 10L,
                totalBytes = 100L,
                eTag = null,
                lastModified = null,
                status = DownloadManager.STATUS_PAUSED,
                progress = 10,
                errorMessage = null,
                updatedAt = 1L,
            )
        )

        val remapped = dao.migrateLegacyServer(
            shellIds = setOf(shellId),
            canonicalServer = Server(
                id = canonicalId,
                name = "Living Room Jellyfin",
                currentServerAddressId = null,
                currentUserId = canonicalUserId,
            ),
            targetAddress = "https://media.example.com/jf/",
            addresses = listOf("http://192.168.1.50:8096/jellyfin/", "https://media.example.com/jf"),
            users = listOf(
                UserMigrationUpdate(
                    user = User(
                        id = canonicalUserId,
                        name = "Alice",
                        serverId = canonicalId,
                        accessToken = "new-token-alice",
                        preferences = null,
                    ),
                    overwritePreferences = false,
                )
            ),
        )

        assertEquals(listOf(legacyUserId to canonicalUserId), remapped)
        assertNull(dao.get(shellId))
        val canonical = dao.get(canonicalId)
        assertNotNull(canonical)
        assertEquals("Living Room Jellyfin", canonical!!.name)
        assertEquals(canonicalUserId, canonical.currentUserId)

        val addrs = dao.getServerAddresses(canonicalId)
        assertEquals(2, addrs.size)
        assertEquals("https://media.example.com/jf", dao.getServerCurrentAddress(canonicalId)?.address)

        val migratedUser = dao.getUser(canonicalUserId)
        assertNotNull(migratedUser)
        assertEquals("new-token-alice", migratedUser!!.accessToken)
        assertEquals("""{"pref_theme":"dark"}""", migratedUser.preferences)
        assertNull(dao.getUser(legacyUserId))

        val mergedUserData = dao.getUserData(movieId, canonicalUserId)
        assertNotNull(mergedUserData)
        assertTrue(mergedUserData!!.favorite)
        assertEquals(500L, mergedUserData.playbackPositionTicks)
        assertTrue(mergedUserData.toBeSynced)

        val migratedMovie = dao.getMovieOrNull(movieId)
        assertEquals(canonicalId, migratedMovie?.serverId)

        val updatedTask = dao.getDownloadTaskById("task-1")!!
        assertEquals(
            "https://media.example.com/jf/Videos/$movieId/stream?api_key=new-token-alice",
            updatedTask.requestUrl,
        )
        assertEquals("new-token-alice", updatedTask.accessToken)
    }

    @Test
    fun `rewriteIncompleteDownloadRequestUrls enforces path segment boundary and longest prefix first`() {
        val srvId = "srv-a"
        val itemId = UUID.randomUUID()
        dao.insertServer(Server(id = srvId, name = "A", currentServerAddressId = null, currentUserId = null))
        dao.insertServerAddress(ServerAddress(UUID.randomUUID(), srvId, "http://192.168.1.10:8096/jelly"))
        dao.insertServerAddress(ServerAddress(UUID.randomUUID(), srvId, "http://192.168.1.10:8096/jelly/sub"))
        dao.insertMovie(
            SpatialFinMovieDto(
                id = itemId, serverId = srvId, name = "M", originalTitle = null, overview = "",
                runtimeTicks = 0L, premiereDate = null, communityRating = null, officialRating = null,
                status = "Ended", productionYear = null, endDate = null, chapters = null,
            )
        )
        // Non-segment-boundary URL (/jellyfin vs /jelly) MUST NOT be rewritten
        dao.insertDownloadTask(
            DownloadTaskDto(
                id = "t-no-match", itemId = itemId, sourceId = "s1", kind = DownloadTaskKind.PRIMARY,
                mediaStreamId = null, downloadId = null,
                requestUrl = "http://192.168.1.10:8096/jellyfin/Videos/$itemId/stream",
                accessToken = null, tempPath = "/tmp/1.download", finalPath = "/tmp/1.mp4",
                bytesDownloaded = 0L, totalBytes = 10L, eTag = null, lastModified = null,
                status = DownloadManager.STATUS_PAUSED, progress = 0, errorMessage = null, updatedAt = 1L,
            )
        )
        // Longest prefix (/jelly/sub) MUST win over /jelly
        dao.insertDownloadTask(
            DownloadTaskDto(
                id = "t-longest", itemId = itemId, sourceId = "s2", kind = DownloadTaskKind.PRIMARY,
                mediaStreamId = null, downloadId = null,
                requestUrl = "http://192.168.1.10:8096/jelly/sub/Videos/$itemId/stream",
                accessToken = null, tempPath = "/tmp/2.download", finalPath = "/tmp/2.mp4",
                bytesDownloaded = 0L, totalBytes = 10L, eTag = null, lastModified = null,
                status = DownloadManager.STATUS_PAUSED, progress = 0, errorMessage = null, updatedAt = 1L,
            )
        )

        dao.rewriteIncompleteDownloadRequestUrls(setOf(srvId), "https://new.example.com/media")

        assertEquals(
            "http://192.168.1.10:8096/jellyfin/Videos/$itemId/stream",
            dao.getDownloadTaskById("t-no-match")!!.requestUrl,
        )
        assertEquals(
            "https://new.example.com/media/Videos/$itemId/stream",
            dao.getDownloadTaskById("t-longest")!!.requestUrl,
        )
    }

    @Test
    fun `finalizeCompletedDownload requires STATUS_RUNNING and deleteServerAndOfflineMedia snapshots and cascades`() {
        val srvId = "srv-del"
        val userId = UUID.randomUUID()
        val movieId = UUID.randomUUID()
        val addrId = UUID.randomUUID()
        dao.insertServer(Server(srvId, "Del", addrId, userId))
        dao.insertServerAddress(ServerAddress(addrId, srvId, "http://10.0.0.5:8096"))
        dao.insertUser(User(userId, "Bob", srvId, "tok-bob"))
        dao.insertMovie(
            SpatialFinMovieDto(
                id = movieId, serverId = srvId, name = "M", originalTitle = null, overview = "",
                runtimeTicks = 0L, premiereDate = null, communityRating = null, officialRating = null,
                status = "Ended", productionYear = null, endDate = null, chapters = null,
            )
        )
        dao.insertSource(SpatialFinSourceDto("src-1", movieId, "1080p", SpatialFinSourceType.LOCAL, "/tmp/m.mp4"))
        dao.insertDownloadTask(
            DownloadTaskDto(
                id = "t-run", itemId = movieId, sourceId = "src-1", kind = DownloadTaskKind.PRIMARY,
                mediaStreamId = null, downloadId = null,
                requestUrl = "http://10.0.0.5:8096/Videos/$movieId/stream",
                accessToken = "tok-bob", tempPath = "/tmp/m.mp4.download", finalPath = "/tmp/m.mp4",
                bytesDownloaded = 50L, totalBytes = 100L, eTag = null, lastModified = null,
                status = DownloadManager.STATUS_PAUSED, progress = 50, errorMessage = null, updatedAt = 1L,
            )
        )

        // Paused task must fail finalizeCompletedDownload
        assertFalse(
            dao.finalizeCompletedDownload(
                id = "t-run", bytesDownloaded = 100L, totalBytes = 100L,
                eTag = null, lastModified = null, isEncrypted = true, encryptionIv = "iv123",
            )
        )

        // Transition to RUNNING -> finalize succeeds atomically with encryption metadata
        dao.updateDownloadTask("t-run", null, 50L, 100L, null, null, DownloadManager.STATUS_RUNNING, 50, null, 2L)
        assertTrue(
            dao.finalizeCompletedDownload(
                id = "t-run", bytesDownloaded = 100L, totalBytes = 100L,
                eTag = "e1", lastModified = null, isEncrypted = true, encryptionIv = "iv123",
            )
        )
        val finalized = dao.getDownloadTaskById("t-run")!!
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, finalized.status)
        assertTrue(finalized.isEncrypted)
        assertEquals("iv123", finalized.encryptionIv)

        // Atomic delete snapshots everything and removes rows
        val snap = dao.deleteServerAndOfflineMedia(srvId)
        assertEquals("http://10.0.0.5:8096", snap.activeBaseUrl)
        assertEquals(listOf(userId), snap.snapshottedUsers.map { it.id })
        assertEquals(listOf("t-run"), snap.taskIds)
        assertTrue(movieId in snap.itemIds)
        assertTrue("/tmp/m.mp4" in snap.filePaths)
        assertNull(dao.get(srvId))
        assertNull(dao.getMovieOrNull(movieId))
        assertTrue(dao.getAllDownloadTasks().isEmpty())
        assertTrue(dao.getAllSources().isEmpty())
    }
}
