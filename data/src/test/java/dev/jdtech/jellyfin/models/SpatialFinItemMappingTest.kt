package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for servers whose media is not filed under a Movies or TV
 * library. Jellyfin hands those back as `Video` / `MusicVideo` / `Trailer` items;
 * dropping them made libraries, Home rows, and search look empty.
 *
 * Robolectric here only supplies `android.net.Uri` for the image mapper; `@Config(sdk = [35])`
 * pins the SDK because Robolectric ships resource jars only up to API 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpatialFinItemMappingTest {

    private val repository = mockk<JellyfinRepository>(relaxed = true)

    @Before
    fun setUp() {
        every { repository.getBaseUrl() } returns "http://localhost:8096"
    }

    @Test
    fun `standalone video kinds map to a playable item`() = runTest {
        listOf(BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.TRAILER).forEach { kind ->
            val item = dto(kind).toSpatialFinItem(repository)

            assertTrue("$kind must map to a playable item", item is SpatialFinMovie)
            assertEquals("Beach trip 2019", item!!.name)
        }
    }

    @Test
    fun `stills map to a photo, not a playable item`() = runTest {
        val parent = UUID.randomUUID()
        val item = BaseItemDto(
            id = UUID.randomUUID(),
            name = "IMG_0042",
            type = BaseItemKind.PHOTO,
            parentId = parent,
            width = 4032,
            height = 3024,
        ).toSpatialFinItem(repository)

        val photo = item as? SpatialFinPhoto
        assertNotNull("PHOTO must map to SpatialFinPhoto", photo)
        // The viewer pages through the folder off this, so losing it strands the photo.
        assertEquals(parent, photo!!.parentId)
        assertEquals(4032, photo.width)
        assertFalse("A still is not playable", photo.canPlay)
    }

    @Test
    fun `photo albums browse like folders`() = runTest {
        val item = dto(BaseItemKind.PHOTO_ALBUM).toSpatialFinItem(repository)

        assertTrue("PHOTO_ALBUM must browse as a folder", item is SpatialFinFolder)
    }

    @Test
    fun `kinds with no representation are still dropped`() = runTest {
        assertNull(dto(BaseItemKind.LIVE_TV_CHANNEL).toSpatialFinItem(repository))
    }

    private fun dto(kind: BaseItemKind) =
        BaseItemDto(id = UUID.randomUUID(), name = "Beach trip 2019", type = kind)
}
