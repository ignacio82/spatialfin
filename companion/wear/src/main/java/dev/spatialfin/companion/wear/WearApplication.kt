package dev.spatialfin.companion.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class WearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Matches UnifiedApplication: a DebugTree in release leaks playback titles and
        // node ids into logcat for anything on the watch to read.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("WearApplication: initialized")
    }
}
