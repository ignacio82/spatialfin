package dev.spatialfin.fcast.session

import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.player.core.splitav.RoomGeometryContract
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolved Jellyfin audio stream metadata handed to [SendspinAvrSplitAvEngine] on session start,
 * audio track switches, and episode advances.
 */
data class ResolvedAudioStream(
    val streamUrl: String,
    val streamLayout: String,
    val mediaSourceId: String,
    val audioStreamIndex: Int,
    val sourceCodec: String,
    val jellyfinToken: String,
)

/**
 * Launch configuration for a Spec 0 Theater Audio (`CastProtocol.SendspinAvr`) session.
 */
data class SendspinAvrSessionConfig(
    val receiver: CastReceiver,
    val bearerToken: String,
    val clientId: String,
    val clientName: String,
    val formFactor: String,
    val appVersion: String,
    val sessionId: String = UUID.randomUUID().toString(),
    val streamUrl: String,
    val streamLayout: String,
    val mediaSourceId: String,
    val audioStreamIndex: Int,
    val sourceCodec: String,
    val jellyfinToken: String,
    val tlsSpkiSha256: String? = null,
    val initialTargetPtsUs: Long = 0L,
    val startPaused: Boolean = false,
    val forceTakeover: Boolean = false,
    val clientDisplayOffsetUs: Long = 0L,
    val initialLcrScreenLock: Boolean = true,
    val initialAnchorUuid: String? = null,
    val initialAnchorToMlpTransform: List<Float>? = null,
    val initialRoomGeometry: RoomGeometryContract? = null,
    val resolveTrackSwitchStream: (suspend (audioStreamIndex: Int, codec: String?) -> ResolvedAudioStream?)? = null,
    val resolveMediaItemStream: (suspend (itemId: String, mediaSourceId: String?, audioStreamIndex: Int) -> ResolvedAudioStream?)? = null,
)

/**
 * Protocol-specific Split A/V runtime engine driven behind [SplitAvController].
 */
internal interface SplitAvSessionEngine {
    val state: StateFlow<SplitAvController.State>
    val lastDriftMs: StateFlow<Long?>
    val theaterSnapshot: StateFlow<TheaterSessionSnapshot?>

    suspend fun pause()
    suspend fun resume()
    suspend fun seekTo(positionMs: Long)
    fun currentMasterPositionMs(): Long?
    suspend fun endFromMaster()
    suspend fun endByReceiver()
    suspend fun stop(reason: String = "user_stop")
    fun onMasterDisconnected(userExit: Boolean)
    suspend fun handleTheaterClientEvent(event: TheaterClientEvent)
    fun shutdown()
}
