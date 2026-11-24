package dev.spatialfin.unified.applock

/**
 * Synchronous pre-wipe hook invoked by [AppDataWiper] before clearing external storage
 * and calling `ActivityManager.clearApplicationUserData()`.
 *
 * Bound in `:core` `SessionModule` to cancel WorkManager jobs, active downloads, and
 * detached tasks and remove MediaStore-managed external downloads.
 */
fun interface PreWipeHook {
    fun cancelBackgroundWorkSync()

    companion object {
        val NoOp = PreWipeHook {}
    }
}
