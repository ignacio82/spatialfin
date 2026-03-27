package dev.jdtech.jellyfin.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.database.MIGRATION_14_15
import dev.jdtech.jellyfin.database.MIGRATION_15_16
import dev.jdtech.jellyfin.database.MIGRATION_16_17
import dev.jdtech.jellyfin.database.MIGRATION_6_7
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideServerDatabaseDao(@ApplicationContext app: Context): ServerDatabaseDao {
        return Room.databaseBuilder(app.applicationContext, ServerDatabase::class.java, "servers")
            .addMigrations(MIGRATION_6_7, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .allowMainThreadQueries()
            .build()
            .getServerDatabaseDao()
    }
}
