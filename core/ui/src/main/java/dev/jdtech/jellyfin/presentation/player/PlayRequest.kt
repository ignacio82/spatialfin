package dev.jdtech.jellyfin.presentation.player

import dev.jdtech.jellyfin.models.SpatialFinItem
import java.util.UUID

/**
 * Form-factor-agnostic playback request, hoisted out of the browse screens so
 * they no longer reference `:player:xr` (`XrPlayerActivity` /
 * `MultitaskPlayerActivity`) or the FCast session launcher directly.
 *
 * The screen still resolves stereo/projection (the detectors live in
 * `:player:core`) and decides immersive-vs-multitask, then emits one of these.
 * The app supplies the concrete launcher (`rememberPlaybackLauncher()`), which
 * maps each variant to the right Activity intent and FCast routing. This is the
 * seam that lets the film/network/local screens move into feature modules
 * without a `:player:xr` edge.
 */
sealed interface PlayRequest {

    /** A Jellyfin library movie / episode / show / season. */
    data class LibraryItem(
        val itemId: UUID,
        val itemKind: String,
        /** Non-null enables FCast routing + Split-A/V (needs the full item). */
        val item: SpatialFinItem? = null,
        val startFromBeginning: Boolean = false,
        /** true → immersive XrPlayerActivity, false → MultitaskPlayerActivity. */
        val immersive: Boolean = true,
        val mediaSourceIndex: Int? = null,
        val maxBitrate: Long? = null,
        val stereoMode: String = "mono",
        /** null → do not set the projection extra (e.g. Show/Season). */
        val projection: String? = null,
        /** FCast receiver start position (ms); null → start at 0 / unknown. */
        val resumePositionMs: Long? = null,
        val openSyncPlayDialogOnStart: Boolean = false,
        /** false → never route through a picked FCast receiver (watch-party path). */
        val allowFcastRouting: Boolean = true,
    ) : PlayRequest

    /** A device MediaStore video. */
    data class LocalMedia(
        val mediaStoreId: Long,
        val startFromBeginning: Boolean,
        val immersive: Boolean,
        val stereoMode: String = "mono",
        val projection: String? = null,
    ) : PlayRequest

    /** A network-share (SMB/NFS) video. */
    data class NetworkMedia(
        val networkVideoId: String,
        val startFromBeginning: Boolean,
        val immersive: Boolean,
        val stereoMode: String = "mono",
        val projection: String? = null,
    ) : PlayRequest

    /** A universal-source-plugin media item (always immersive). */
    data class UniversalMedia(
        val pluginId: String,
        val id: String,
        val videoUrl: String,
        val name: String,
        val stereoMode: String? = null,
        val projection: String? = null,
    ) : PlayRequest
}
