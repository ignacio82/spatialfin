package dev.jdtech.jellyfin.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppPreferencesModule {
    @Singleton
    @Provides
    fun provideAppPreferences(
        application: Application,
        sp: SharedPreferences,
    ): AppPreferences {
        val xrPlayerSp = application.getSharedPreferences(
            AppPreferences.XR_PLAYER_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val isMainProcess = Application.getProcessName() == application.packageName
        val isDebuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return AppPreferences(
            sharedPreferences = sp,
            xrPlayerSharedPreferences = xrPlayerSp,
            isMainProcess = isMainProcess,
            isDebuggable = isDebuggable,
        )
    }
}
