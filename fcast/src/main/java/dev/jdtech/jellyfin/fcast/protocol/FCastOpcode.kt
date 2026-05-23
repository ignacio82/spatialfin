package dev.jdtech.jellyfin.fcast.protocol

/**
 * FCast wire opcodes per the v3 protocol spec (docs.fcast.org/protocol/v3).
 *
 * v1 covers None..VolumeUpdate + SetVolume.
 * v2 adds PlaybackError (9), SetSpeed (10), Version (11), Ping (12), Pong (13).
 * v3 adds Initial (14), PlayUpdate (15), SetPlaylistItem (16),
 *        SubscribeEvent (17), UnsubscribeEvent (18), Event (19).
 */
enum class FCastOpcode(val code: Int, val minVersion: Int) {
    None(0, 1),
    Play(1, 1),
    Pause(2, 1),
    Resume(3, 1),
    Stop(4, 1),
    Seek(5, 1),
    PlaybackUpdate(6, 1),
    VolumeUpdate(7, 1),
    SetVolume(8, 1),
    PlaybackError(9, 2),
    SetSpeed(10, 2),
    Version(11, 2),
    Ping(12, 2),
    Pong(13, 2),
    Initial(14, 3),
    PlayUpdate(15, 3),
    SetPlaylistItem(16, 3),
    SubscribeEvent(17, 3),
    UnsubscribeEvent(18, 3),
    Event(19, 3),

    // SpatialFin Custom Extensions
    SpatialFinTracksUpdate(100, 3),
    SpatialFinSetTrack(101, 3),
    ;

    companion object {
        private val byCode: Map<Int, FCastOpcode> = entries.associateBy { it.code }
        fun fromCode(code: Int): FCastOpcode? = byCode[code]
    }
}
