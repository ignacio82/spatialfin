package dev.jdtech.jellyfin.cast

import dev.jdtech.jellyfin.cast.adapter.sendspinavr.AvrStatusResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationApplyRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationBundleResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationCenterSnapPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationChimeRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationLipSyncChirpPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationRevertResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepStartRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepUploadResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.ClockPingOkPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.ClockPingPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.DecoderFallbackPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.HelloOkPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.HelloRequestPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.HttpErrorResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.MaStateChangedPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairClaimRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairClaimResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackLoadPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackPausePayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackReadyOkPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackReadyPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackResyncRequiredPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackStallPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PlaybackVideoTimingPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileUpdatedPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomSetEqBypassPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomSetUpmixModePayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomSetVolumePayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomStateChangedPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RpcErrorPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrEnvelope
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrJson
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator.ValidationResult
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionHeartbeatPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionReleasedPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionStartOkPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionStartPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SessionStopPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SpeakerDegradedPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SpeakerRestoredPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.TelemetrySessionPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.TheaterAudioProtocol
import dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterAudioSchemaContractTest {

    @Serializable
    private data class FixtureManifest(
        @SerialName("protocol_version") val protocolVersion: Int,
        @SerialName("schema_version") val schemaVersion: String,
        val fixtures: List<FixtureEntry>,
    )

    @Serializable
    private data class FixtureEntry(
        val path: String,
        val category: String,
        val transport: String,
        @SerialName("message_type") val messageType: String? = null,
        @SerialName("schema_kind") val schemaKind: String,
        val direction: String,
        @SerialName("expect_valid") val expectValid: Boolean,
        @SerialName("enforced_by") val enforcedBy: String,
        @SerialName("expected_error_code") val expectedErrorCode: String? = null,
    )

    private val json = SendspinAvrJson.codec

    private fun schemasRoot(): File {
        val dirProp = System.getProperty("spatialfin.schemas.dir")
            ?: error("Missing -Dspatialfin.schemas.dir system property")
        return File(dirProp).also {
            assertTrue("Schemas dir must exist: ${it.absolutePath}", it.isDirectory)
        }
    }

    private fun loadManifest(fixturesDir: File): FixtureManifest {
        val manifestFile = File(fixturesDir, "manifest.json")
        assertTrue("Missing manifest.json at ${manifestFile.absolutePath}", manifestFile.isFile)
        return json.decodeFromString(FixtureManifest.serializer(), manifestFile.readText())
    }

    @Test
    fun `manifest covers all 51 golden fixtures with no orphans`() {
        val fixturesDir = File(schemasRoot(), "fixtures")
        val manifest = loadManifest(fixturesDir)

        assertEquals(TheaterAudioProtocol.VERSION, manifest.protocolVersion)
        assertEquals(51, manifest.fixtures.size)
        assertEquals(40, manifest.fixtures.count { it.category == "valid" })
        assertEquals(3, manifest.fixtures.count { it.category == "additive-forward-compat" })
        assertEquals(8, manifest.fixtures.count { it.category == "invalid" })
        assertEquals(24, TheaterAudioProtocol.ERROR_CODES.size)

        manifest.fixtures.forEach { entry ->
            if (entry.category == "invalid") {
                assertFalse("${entry.path} must declare expect_valid=false", entry.expectValid)
            } else {
                assertTrue("${entry.path} must declare expect_valid=true", entry.expectValid)
            }
        }

        val actualRelativePaths = listOf("valid", "additive-forward-compat", "invalid")
            .flatMap { sub ->
                File(fixturesDir, sub).listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
                    .map { "$sub/${it.name}" }
            }
            .toSet()
        val manifestPaths = manifest.fixtures.map { it.path }.toSet()
        assertEquals(manifestPaths, actualRelativePaths)
    }

    @Test
    fun `valid and forward-compat fixtures round-trip cleanly without payload session_id leakage`() {
        val fixturesDir = File(schemasRoot(), "fixtures")
        val manifest = loadManifest(fixturesDir)

        manifest.fixtures
            .filter { it.expectValid }
            .forEach { entry ->
                val raw = File(fixturesDir, entry.path).readText()
                if (entry.transport == "ws") {
                    assertEquals(
                        "WS envelope failed validation for ${entry.path}",
                        ValidationResult.Valid,
                        SendspinAvrRequestValidator.validateWsEnvelopeJson(raw),
                    )
                    val envelopeObj = json.parseToJsonElement(raw).jsonObject
                    val payloadObj = envelopeObj["payload"] as JsonObject
                    assertTrue(
                        "${entry.path} leaked session_id into payload",
                        "session_id" !in payloadObj,
                    )
                }

                when (entry.schemaKind) {
                    "hello_req" -> {
                        val env = roundTripWs(entry, raw, HelloRequestPayload.serializer())
                        assertEquals(ValidationResult.Valid, SendspinAvrRequestValidator.validateHello(env.payload))
                    }
                    "hello_ok" -> {
                        val env = roundTripWs(entry, raw, HelloOkPayload.serializer())
                        assertEquals("rpc.ok", env.type)
                    }
                    "clock_ping_req" -> roundTripWs(entry, raw, ClockPingPayload.serializer())
                    "clock_ping_ok" -> roundTripWs(entry, raw, ClockPingOkPayload.serializer())
                    "session_start_req" -> {
                        val env = roundTripWs(entry, raw, SessionStartPayload.serializer())
                        assertEquals(ValidationResult.Valid, SendspinAvrRequestValidator.validateSessionStart(env.payload))
                    }
                    "session_start_ok" -> roundTripWs(entry, raw, SessionStartOkPayload.serializer())
                    "session_heartbeat" -> roundTripWs(entry, raw, SessionHeartbeatPayload.serializer())
                    "session_stop_req" -> roundTripWs(entry, raw, SessionStopPayload.serializer())
                    "session_released" -> roundTripWs(entry, raw, SessionReleasedPayload.serializer())
                    "playback_load_req" -> {
                        val env = roundTripWs(entry, raw, PlaybackLoadPayload.serializer())
                        assertEquals(ValidationResult.Valid, SendspinAvrRequestValidator.validatePlaybackLoad(env.payload))
                    }
                    "playback_ready_req" -> roundTripWs(entry, raw, PlaybackReadyPayload.serializer())
                    "playback_ready_ok" -> roundTripWs(entry, raw, PlaybackReadyOkPayload.serializer())
                    "playback_pause_req" -> roundTripWs(entry, raw, PlaybackPausePayload.serializer())
                    "playback_stall_req" -> roundTripWs(entry, raw, PlaybackStallPayload.serializer())
                    "playback_video_timing" -> roundTripWs(entry, raw, PlaybackVideoTimingPayload.serializer())
                    "playback_resync_required" -> roundTripWs(entry, raw, PlaybackResyncRequiredPayload.serializer())
                    "room_set_volume_req" -> roundTripWs(entry, raw, RoomSetVolumePayload.serializer())
                    "room_set_eq_bypass_req" -> roundTripWs(entry, raw, RoomSetEqBypassPayload.serializer())
                    "room_set_upmix_mode_req" -> roundTripWs(entry, raw, RoomSetUpmixModePayload.serializer())
                    "room_state_changed" -> roundTripWs(entry, raw, RoomStateChangedPayload.serializer())
                    "room_profile_get_ok" -> roundTripHttp(entry, raw, RoomProfileResponse.serializer())
                    "room_profile_updated" -> roundTripWs(entry, raw, RoomProfileUpdatedPayload.serializer())
                    "calibration_chime_req" -> {
                        val req = roundTripHttp(entry, raw, CalibrationChimeRequest.serializer())
                        assertEquals(ValidationResult.Valid, SendspinAvrRequestValidator.validateCalibrationChime(req))
                    }
                    "calibration_center_snap_req" -> roundTripWs(entry, raw, CalibrationCenterSnapPayload.serializer())
                    "calibration_lip_sync_chirp_req" -> roundTripWs(entry, raw, CalibrationLipSyncChirpPayload.serializer())
                    "calibration_sweep_start_req" -> roundTripHttp(entry, raw, CalibrationSweepStartRequest.serializer())
                    "calibration_sweep_upload_ok" -> roundTripHttp(entry, raw, CalibrationSweepUploadResponse.serializer())
                    "calibration_bundles_ok" -> roundTripHttp(entry, raw, CalibrationBundleResponse.serializer())
                    "calibration_apply_req" -> roundTripHttp(entry, raw, CalibrationApplyRequest.serializer())
                    "calibration_revert_ok" -> roundTripHttp(entry, raw, CalibrationRevertResponse.serializer())
                    "telemetry_session" -> roundTripWs(entry, raw, TelemetrySessionPayload.serializer())
                    "status_idle" -> roundTripHttp(entry, raw, AvrStatusResponse.serializer())
                    "speaker_degraded" -> roundTripWs(entry, raw, SpeakerDegradedPayload.serializer())
                    "speaker_restored" -> roundTripWs(entry, raw, SpeakerRestoredPayload.serializer())
                    "decoder_fallback" -> roundTripWs(entry, raw, DecoderFallbackPayload.serializer())
                    "ma_state_changed" -> roundTripWs(entry, raw, MaStateChangedPayload.serializer())
                    "pair_claim_req" -> roundTripHttp(entry, raw, PairClaimRequest.serializer())
                    "pair_claim_ok" -> roundTripHttp(entry, raw, PairClaimResponse.serializer())
                    "rpc_error" -> {
                        val env = roundTripWs(entry, raw, RpcErrorPayload.serializer())
                        assertTrue(
                            "Unknown rpc_error code ${env.payload.code}",
                            env.payload.code in TheaterAudioProtocol.ERROR_CODES,
                        )
                    }
                    "http_error" -> {
                        val err = roundTripHttp(entry, raw, HttpErrorResponse.serializer())
                        assertTrue(
                            "Unknown http_error error_code ${err.errorCode}",
                            err.errorCode in TheaterAudioProtocol.ERROR_CODES,
                        )
                    }
                    else -> error("Unhandled schema_kind '${entry.schemaKind}' in ${entry.path}")
                }
            }
    }

    @Test
    fun `hello_ok supported_audio_codecs matches Kotlin AudioCodecTokens union exactly`() {
        val fixturesDir = File(schemasRoot(), "fixtures")
        val raw = File(fixturesDir, "valid/hello_ok.json").readText()
        val helloOkEnv = json.decodeFromString(
            SendspinAvrEnvelope.serializer(HelloOkPayload.serializer()),
            raw,
        )
        assertEquals("rpc.ok", helloOkEnv.type)

        val expected = AudioCodecTokens.ALL_PASSTHROUGH_TOKENS + AudioCodecTokens.UNIVERSAL_SOFTWARE
        assertEquals(19, expected.size)
        assertEquals(19, helloOkEnv.payload.supportedAudioCodecs.size)
        assertEquals(expected, helloOkEnv.payload.supportedAudioCodecs.toSet())
    }

    @Test
    fun `invalid fixtures fail validation with canonical Spec 0 error codes`() {
        val fixturesDir = File(schemasRoot(), "fixtures")
        val manifest = loadManifest(fixturesDir)

        manifest.fixtures
            .filter { !it.expectValid }
            .forEach { entry ->
                val expectedCode = entry.expectedErrorCode
                    ?: error("Invalid fixture ${entry.path} missing expected_error_code")
                assertTrue(
                    "Expected error code '$expectedCode' not in canonical TheaterAudioProtocol.ERROR_CODES",
                    expectedCode in TheaterAudioProtocol.ERROR_CODES,
                )

                val raw = File(fixturesDir, entry.path).readText()
                if (entry.enforcedBy == "client" || entry.enforcedBy == "both") {
                    val result = when (entry.schemaKind) {
                        "hello_req" -> {
                            val env = json.decodeFromString(
                                SendspinAvrEnvelope.serializer(HelloRequestPayload.serializer()),
                                raw,
                            )
                            SendspinAvrRequestValidator.validateHello(env.payload)
                        }
                        "session_start_req" -> {
                            val env = json.decodeFromString(
                                SendspinAvrEnvelope.serializer(SessionStartPayload.serializer()),
                                raw,
                            )
                            SendspinAvrRequestValidator.validateSessionStart(env.payload)
                        }
                        "playback_load_req" -> {
                            val env = json.decodeFromString(
                                SendspinAvrEnvelope.serializer(PlaybackLoadPayload.serializer()),
                                raw,
                            )
                            SendspinAvrRequestValidator.validatePlaybackLoad(env.payload)
                        }
                        "calibration_chime_req" -> {
                            val req = json.decodeFromString(CalibrationChimeRequest.serializer(), raw)
                            SendspinAvrRequestValidator.validateCalibrationChime(req)
                        }
                        else -> error("Unhandled client-enforced invalid schema_kind: ${entry.schemaKind}")
                    }

                    assertTrue("Expected Invalid for ${entry.path}, got $result", result is ValidationResult.Invalid)
                    assertEquals(expectedCode, (result as ValidationResult.Invalid).errorCode)
                } else {
                    when (entry.schemaKind) {
                        "calibration_chime_req" -> {
                            val req = json.decodeFromString(CalibrationChimeRequest.serializer(), raw)
                            val clientCheck = SendspinAvrRequestValidator.validateCalibrationChime(req)
                            assertTrue(clientCheck is ValidationResult.Invalid)
                            assertEquals(expectedCode, (clientCheck as ValidationResult.Invalid).errorCode)
                        }
                        "calibration_bundles_ok" ->
                            json.decodeFromString(CalibrationBundleResponse.serializer(), raw)
                        else -> error("Unhandled server-enforced invalid schema_kind: ${entry.schemaKind}")
                    }
                }
            }
    }

    private fun <T> roundTripWs(
        entry: FixtureEntry,
        raw: String,
        payloadSerializer: KSerializer<T>,
    ): SendspinAvrEnvelope<T> {
        val serializer = SendspinAvrEnvelope.serializer(payloadSerializer)
        val first = json.decodeFromString(serializer, raw)
        assertEquals("Mismatched WS envelope type in ${entry.path}", entry.messageType, first.type)
        val encoded = json.encodeToString(serializer, first)
        val second = json.decodeFromString(serializer, encoded)
        assertEquals("WS round-trip mismatch in ${entry.path}", first, second)
        return first
    }

    private fun <T> roundTripHttp(
        entry: FixtureEntry,
        raw: String,
        serializer: KSerializer<T>,
    ): T {
        val first = json.decodeFromString(serializer, raw)
        val encoded = json.encodeToString(serializer, first)
        val second = json.decodeFromString(serializer, encoded)
        assertEquals("HTTP round-trip mismatch in ${entry.path}", first, second)
        return first
    }
}
