package dev.spatialfin.unified.applock

/**
 * Recovery hook invoked by [AppLockManager] when a Biometric or PIN DEK has been invalidated
 * and the user confirms purging unreadable encrypted downloads before generating a fresh DEK.
 *
 * Bound in `:core` `SessionModule`.
 */
fun interface EncryptedDownloadsPurgeHook {
    suspend fun purgeEncryptedDownloads()

    companion object {
        val NoOp = EncryptedDownloadsPurgeHook {}
    }
}
