package dev.jdtech.jellyfin.player.beam

import dev.jdtech.jellyfin.plugins.model.UniversalMediaItem
import dev.jdtech.jellyfin.plugins.model.toSpatialFinItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BeamPlayerActivityIntentTest {

    @Test
    fun `createIntentForSpatialItem builds universal media intent for UniversalSpatialFinItem`() {
        val context = RuntimeEnvironment.getApplication()
        val universalItem = UniversalMediaItem(
            id = "video-42",
            pluginId = "example-plugin",
            homeRowId = "trending",
            title = "Example Plugin Video",
            videoUrl = "https://example.com/watch/42",
        ).toSpatialFinItem()

        val intent = BeamPlayerActivity.createIntentForSpatialItem(
            context = context,
            item = universalItem,
        )

        assertNotNull(intent)
        assertEquals("example-plugin", intent?.getStringExtra("universalPluginId"))
        assertEquals("video-42", intent?.getStringExtra("universalItemId"))
        assertEquals("https://example.com/watch/42", intent?.getStringExtra("universalVideoUrl"))
        assertEquals("Example Plugin Video", intent?.getStringExtra("universalTitle"))
    }
}
