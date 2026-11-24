package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Singleton
    @Provides
    fun provideWorkManager(application: Application): WorkManager {
        check(Application.getProcessName() == application.packageName) {
            "WorkManager must only be initialized in the main process (${application.packageName}), " +
                "not ${Application.getProcessName()}"
        }
        return WorkManager.getInstance(application)
    }
}
