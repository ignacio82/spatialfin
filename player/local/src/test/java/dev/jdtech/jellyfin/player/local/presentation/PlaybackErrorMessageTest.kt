package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.PlaybackException
import dev.jdtech.jellyfin.player.local.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMessageTest {

    @Test
    fun `format exceeds capabilities maps to the resolution-too-high message`() {
        // This is the code an 8K stream lands on when the decoder exists but
        // can't handle the resolution/level.
        assertEquals(
            R.string.player_error_decode_capabilities,
            playbackErrorMessageRes(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            ),
        )
    }

    @Test
    fun `no usable decoder maps to the unsupported-format message`() {
        assertEquals(
            R.string.player_error_decode_unsupported,
            playbackErrorMessageRes(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )
        assertEquals(
            R.string.player_error_decode_unsupported,
            playbackErrorMessageRes(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
    }

    @Test
    fun `source and network failures map to the source message`() {
        assertEquals(
            R.string.player_error_source,
            playbackErrorMessageRes(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            R.string.player_error_source,
            playbackErrorMessageRes(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
    }

    @Test
    fun `unknown codes fall back to the generic message`() {
        assertEquals(
            R.string.player_error_generic,
            playbackErrorMessageRes(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
    }
}
