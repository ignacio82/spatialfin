package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class ImagesDownloaderWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val itemId = params.inputData.getString(KEY_ITEM_ID) ?: return@withContext Result.failure()

            val urlsByName = listOf(
                KEY_URL_PRIMARY to "primary",
                KEY_URL_BACKDROP to "backdrop",
                KEY_URL_LOGO to "logo",
            ).mapNotNull { (key, name) ->
                params.inputData.getString(key)?.let { name to it }
            }

            if (urlsByName.isEmpty()) {
                Timber.d("ImagesDownloaderWorker: no URLs for itemId=%s, skipping", itemId)
                return@withContext Result.success()
            }

            val basePath = "images/$itemId"
            val baseDir = File(appContext.filesDir, basePath)
            try {
                baseDir.mkdirs()
            } catch (e: IOException) {
                Timber.e(e, "ImagesDownloaderWorker: failed to create dir %s", basePath)
                return@withContext Result.retry()
            }

            val client = OkHttpClient()
            var anyFailed = false

            for ((name, urlString) in urlsByName) {
                val file = File(baseDir, name)
                if (file.exists()) continue

                try {
                    val request = Request.Builder().url(urlString).build()
                    val bytes = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("ImagesDownloaderWorker: HTTP %d for %s/%s", response.code, itemId, name)
                            null
                        } else {
                            response.body.bytes()
                        }
                    }
                    if (bytes != null) {
                        file.writeBytes(bytes)
                        Timber.d("ImagesDownloaderWorker: saved %s/%s (%d bytes)", itemId, name, bytes.size)
                    } else {
                        anyFailed = true
                    }
                } catch (e: IOException) {
                    Timber.e(e, "ImagesDownloaderWorker: failed to download %s/%s", itemId, name)
                    anyFailed = true
                }
            }

            if (anyFailed) Result.retry() else Result.success()
        }

    companion object {
        const val KEY_ITEM_ID = "KEY_ITEM_ID"
        const val KEY_URL_PRIMARY = "url_primary"
        const val KEY_URL_BACKDROP = "url_backdrop"
        const val KEY_URL_LOGO = "url_logo"

        fun uniqueWorkName(itemId: UUID): String = "download-images:$itemId"
    }
}
