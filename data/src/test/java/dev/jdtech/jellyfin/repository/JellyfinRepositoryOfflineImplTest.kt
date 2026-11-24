package dev.jdtech.jellyfin.repository

import android.content.Context
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JellyfinRepositoryOfflineImplTest {
    private lateinit var context: Context
    private lateinit var jellyfinApi: JellyfinApi
    private lateinit var database: ServerDatabaseDao
    private lateinit var appPreferences: AppPreferences
    private lateinit var downloadStorageManager: DownloadStorageManager
    private lateinit var repo: JellyfinRepositoryOfflineImpl

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val sp = context.getSharedPreferences("offline_repo_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
        appPreferences = AppPreferences(sp)
        jellyfinApi = mockk(relaxed = true)
        every { jellyfinApi.userId } returns null
        database = mockk(relaxed = true)
        downloadStorageManager = DownloadStorageManager(context, database)
        repo = JellyfinRepositoryOfflineImpl(
            context = context,
            jellyfinApi = jellyfinApi,
            database = database,
            appPreferences = appPreferences,
            downloadStorageManager = downloadStorageManager,
        )
    }

    @Test
    fun `observeSessions and observeSocketState emit empty and Disconnected immediately`() = runTest {
        assertEquals(emptyList<Any>(), repo.observeSessions().first())
        assertTrue(repo.observeSocketState().first() is SocketApiState.Disconnected)
    }

    @Test
    fun `offline reads tolerate null userId and never fall back to getAllServersSync`() = runTest {
        val movieId = UUID.randomUUID()
        appPreferences.setValue(appPreferences.currentServer, "srv-1")
        every { database.getServerCurrentUser("srv-1") } returns null
        every { database.getMovie(movieId) } returns SpatialFinMovieDto(
            id = movieId, serverId = "srv-1", name = "Blade Runner", originalTitle = null,
            overview = "", runtimeTicks = 100L, premiereDate = null, communityRating = null,
            officialRating = null, status = "Ended", productionYear = 1982, endDate = null, chapters = null,
        )
        every { database.getSources(movieId) } returns emptyList()

        val movie = repo.getMovie(movieId)
        assertEquals("Blade Runner", movie.name)
        assertFalse(movie.played)
        assertFalse(movie.favorite)
        assertEquals(0L, movie.playbackPositionTicks)
        assertTrue(repo.getFavoriteItems().isEmpty())
        assertTrue(repo.getResumeItems().isEmpty())
        assertTrue(repo.getNextUp().isEmpty())
        verify(exactly = 0) { database.getAllServersSync() }
    }

    @Test
    fun `write methods no-op when userId is null and getUserId throws`() = runTest {
        val itemId = UUID.randomUUID()
        repo.postPlaybackStop(itemId, 100L, 50, false)
        repo.postPlaybackProgress(itemId, 100L, false)
        repo.markAsFavorite(itemId)
        repo.unmarkAsFavorite(itemId)
        repo.markAsPlayed(itemId)
        repo.markAsUnplayed(itemId)

        verify(exactly = 0) { database.setPlaybackPositionTicks(any(), any(), any()) }
        verify(exactly = 0) { database.setPlayed(any(), any(), any()) }
        verify(exactly = 0) { database.setFavorite(any(), any(), any()) }
        verify(exactly = 0) { database.setUserDataToBeSynced(any(), any(), any()) }

        var threw = false
        try {
            repo.getUserId()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
