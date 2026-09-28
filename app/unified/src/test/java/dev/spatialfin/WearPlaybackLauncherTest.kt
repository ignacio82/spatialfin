package dev.spatialfin

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.test.SpatialFinTestApplication
import dev.spatialfin.unified.DeviceClass
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class WearPlaybackLauncherTest {
    private val id = UUID.randomUUID()
    private val item = mockk<SpatialFinMovie>(relaxed = true) {
        every { id } returns this@WearPlaybackLauncherTest.id
        every { name } returns "Film"
    }
    private val repository = mockk<JellyfinRepository> {
        coEvery { getItem(id) } returns item
    }
    private val context = mockk<Context>(relaxed = true) {
        every { packageName } returns "dev.spatialfin.debug"
    }

    @Test fun `resume can launch every form factor with no active player`() = runTest {
        val intent = slot<Intent>()
        every { context.startActivity(capture(intent)) } returns Unit
        for ((device, activity) in listOf(DeviceClass.PHONE to "BeamPlayerActivity",
            DeviceClass.TV to "TvPlayerActivity", DeviceClass.XR to "XrPlayerActivity")) {
            assertEquals("Opening Film", launchWearLibraryItem(context, repository, device, id.toString(), 12_000) { true })
            assertTrue(intent.captured.component!!.className.endsWith(activity))
            assertEquals(id.toString(), intent.captured.getStringExtra("itemId"))
            assertFalse(intent.captured.getBooleanExtra("startFromBeginning", false))
        }
    }

    @Test fun `activity leaving foreground during lookup prevents playback launch`() = runTest {
        var foreground = true
        coEvery { repository.getItem(id) } answers { foreground = false; item }
        val result = runCatching {
            launchWearLibraryItem(context, repository, DeviceClass.XR, id.toString(), 0) { foreground }
        }
        assertTrue(result.isFailure)
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
