package dev.jdtech.jellyfin.data.musicassistant.utils

class UniqueIdGenerator {
    private var nextInt = 0

    fun nextInt(): Int {
        return nextInt++
    }
}
