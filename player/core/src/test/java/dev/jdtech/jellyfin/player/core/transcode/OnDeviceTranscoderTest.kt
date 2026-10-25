package dev.jdtech.jellyfin.player.core.transcode

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OnDeviceTranscoderTest {

    @Test
    fun `initial state is Idle`() {
        val state: OnDeviceTranscodeState = OnDeviceTranscodeState.Idle
        assertEquals(OnDeviceTranscodeState.Idle, state)
    }

    @Test
    fun `InProgress state holds correct values`() {
        val uri = mockk<Uri>()
        val state = OnDeviceTranscodeState.InProgress(
            inputUri = uri,
            title = "Sample Video",
            progressPercent = 45,
            outputFilePath = "/cache/transcoded.mp4",
        )

        assertEquals(uri, state.inputUri)
        assertEquals("Sample Video", state.title)
        assertEquals(45, state.progressPercent)
        assertEquals("/cache/transcoded.mp4", state.outputFilePath)
    }

    @Test
    fun `Completed state holds output file`() {
        val uri = mockk<Uri>()
        val file = File("/cache/transcoded.mp4")
        val state = OnDeviceTranscodeState.Completed(
            inputUri = uri,
            title = "Test Video",
            outputFile = file,
        )

        assertEquals(file, state.outputFile)
        assertEquals("Test Video", state.title)
    }

    @Test
    fun `Failed state captures error and cause`() {
        val uri = mockk<Uri>()
        val cause = IllegalArgumentException("Unsupported codec")
        val state = OnDeviceTranscodeState.Failed(
            inputUri = uri,
            title = "Corrupt Video",
            errorMessage = "Codec error",
            cause = cause,
        )

        assertEquals("Codec error", state.errorMessage)
        assertEquals(cause, state.cause)
    }
}
