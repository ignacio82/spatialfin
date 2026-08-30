package dev.spatialfin.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProtocolCodecTest {

    @Test
    fun testActionSerializationRoundTrip() {
        val actions = listOf(
            WearPlayerAction.Play,
            WearPlayerAction.Pause,
            WearPlayerAction.TogglePlayPause,
            WearPlayerAction.SeekForward(15),
            WearPlayerAction.SeekBackward(10),
            WearPlayerAction.SeekTo(120),
            WearPlayerAction.SkipIntro,
            WearPlayerAction.SkipOutro,
            WearPlayerAction.NextEpisode,
            WearPlayerAction.PreviousEpisode,
            WearPlayerAction.SetSpeed(1.5f),
            WearPlayerAction.SelectAudioTrack(language = "eng", index = 1),
            WearPlayerAction.SelectSubtitleTrack(language = "spa", index = 2),
            WearPlayerAction.DisableSubtitles,
            WearPlayerAction.AdjustVolume(percentage = 0.8f, delta = 0.1f),
            WearPlayerAction.AdjustScale(delta = 1.2f, reset = false),
            WearPlayerAction.AdjustDistance(delta = -0.5f, reset = true),
            WearPlayerAction.ResetScreenPlacement,
            WearPlayerAction.GoHome,
            WearPlayerAction.CloseApp,
            WearPlayerAction.GoBack,
            WearPlayerAction.CastToFCastReceiver(name = "Living Room TV", host = "192.168.1.50", port = 46899),
            WearPlayerAction.StopFCastCasting,
            WearPlayerAction.MusicPlayPause,
            WearPlayerAction.MusicPause,
            WearPlayerAction.MusicResume,
            WearPlayerAction.MusicNext,
            WearPlayerAction.MusicPrevious,
            WearPlayerAction.MusicAdjustVolume(percentage = 0.5f),
            WearPlayerAction.PlayMediaItem(itemId = "item-123", mediaType = "Movie", startPositionMs = 5000L),
        )

        for (action in actions) {
            val encoded = WearProtocolCodec.encodeAction(action)
            val decoded = WearProtocolCodec.decodeAction(encoded)
            assertEquals("Action roundtrip failed for $action", action, decoded)
        }
    }

    @Test
    fun testNowPlayingStateSerializationRoundTrip() {
        val state = WearNowPlayingState(
            isPlaying = true,
            positionSeconds = 3600,
            durationSeconds = 7200,
            title = "Dune: Part Two",
            overview = "Paul Atreides unites with Chani and the Fremen...",
            seriesName = null,
            seasonNumber = null,
            episodeNumber = null,
            segmentType = "intro",
            currentChapterName = "Chapter 3: The Desert",
            audioTracks = listOf(
                WearStreamInfo(index = 0, name = "English Dolby Atmos", language = "eng", isSelected = true),
                WearStreamInfo(index = 1, name = "Spanish Stereo", language = "spa", isSelected = false),
            ),
            subtitleTracks = listOf(
                WearStreamInfo(index = 0, name = "English [CC]", language = "eng", isSelected = true),
                WearStreamInfo(index = 1, name = "Spanish", language = "spa", isSelected = false),
            ),
            chapters = listOf(
                WearChapterInfo(name = "Prologue", startPositionSeconds = 0),
                WearChapterInfo(name = "Chapter 1", startPositionSeconds = 600),
            ),
            currentAudioTrack = "English Dolby Atmos",
            currentSubtitleTrack = "English [CC]",
            currentAudioLanguageCode = "eng",
            currentSubtitleLanguageCode = "eng",
            volume = 0.75f,
            speed = 1.0f,
            targetDeviceName = "Galaxy XR",
            hasCoverArtAsset = true,
            streamUrl = "http://192.168.1.100:8096/stream.mp4",
            mediaContainer = "mp4",
            itemId = "uuid-dune-2",
            timestampEpochMs = 1756500000000L,
        )

        val encoded = WearProtocolCodec.encodeNowPlaying(state)
        val decoded = WearProtocolCodec.decodeNowPlaying(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun testNextUpStateSerializationRoundTrip() {
        val state = WearNextUpState(
            items = listOf(
                WearNextUpItem(
                    id = "ep-1",
                    title = "The Heirs of the Dragon",
                    seriesName = "House of the Dragon",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    overview = "Viserys hosts a tournament...",
                    mediaType = "Episode",
                    durationSeconds = 3900,
                    playbackPositionSeconds = 1200,
                )
            ),
            updatedAtEpochMs = 1756500000000L,
        )

        val encoded = WearProtocolCodec.encodeNextUp(state)
        val decoded = WearProtocolCodec.decodeNextUp(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun testVitalsSerializationRoundTrip() {
        val vitals = WearVitalsState(
            batteryPercent = 85,
            isCharging = false,
            thermalStatus = 1,
            wifiSpeedMbps = 866,
            deviceName = "Samsung Galaxy XR",
            isHeadset = true,
        )

        val encoded = WearProtocolCodec.encodeVitals(vitals)
        val decoded = WearProtocolCodec.decodeVitals(encoded)
        assertEquals(vitals, decoded)
    }

    @Test
    fun testCredentialsSerializationRoundTrip() {
        val creds = WearCredentials(
            serverUrl = "https://jellyfin.example.com",
            accessToken = "secret-token-12345",
            userId = "user-uuid-67890",
            deviceId = "device-uuid-abcde",
            serverId = "server-uuid-fghij",
            serverName = "My Media Server",
            username = "alice",
        )

        val encoded = WearProtocolCodec.encodeCredentials(creds)
        val decoded = WearProtocolCodec.decodeCredentials(encoded)
        assertEquals(creds, decoded)
    }

    @Test
    fun testPairingSerializationRoundTrip() {
        val request = WearTvPairingRequest(
            deviceName = "Living Room TV",
            pairingToken = "pair-tok-999",
            manualCode = "482910",
            receiverUrl = "http://192.168.1.200:8080",
            expiresAtEpochMs = 1756500060000L,
        )
        val encReq = WearProtocolCodec.encodePairingRequest(request)
        val decReq = WearProtocolCodec.decodePairingRequest(encReq)
        assertEquals(request, decReq)

        val approval = WearTvPairingApproval(
            pairingToken = "pair-tok-999",
            approved = true,
            setupToken = "setup-token-abc",
            companionConfigJson = "{}",
        )
        val encApp = WearProtocolCodec.encodePairingApproval(approval)
        val decApp = WearProtocolCodec.decodePairingApproval(encApp)
        assertEquals(approval, decApp)
    }

    @Test
    fun testUnrecognizedActionFallback() {
        val invalidBytes = "{\"unknown_field\": true}".encodeToByteArray()
        val decoded = WearProtocolCodec.decodeAction(invalidBytes)
        assertTrue(decoded is WearPlayerAction.Unrecognized)
    }
}
