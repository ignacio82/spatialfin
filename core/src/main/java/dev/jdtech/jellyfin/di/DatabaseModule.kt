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
import dev.jdtech.jellyfin.database.MIGRATION_17_18
import dev.jdtech.jellyfin.database.MIGRATION_6_7
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.security.ContentKeyManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideContentKeyManager(@ApplicationContext app: Context): ContentKeyManager =
        ContentKeyManager(app)

    @Singleton
    @Provides
    fun provideServerDatabaseDao(@ApplicationContext app: Context): ServerDatabaseDao {
        return Room.databaseBuilder(app.applicationContext, ServerDatabase::class.java, "servers")
            .addMigrations(MIGRATION_6_7, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
            // Destructive only on *downgrade* (DB schema newer than the installed
            // code — rare; Play normally blocks version downgrades). A missing
            // *forward* migration must NOT silently wipe the user's offline
            // downloads + userdata: it now throws at runtime so the gap is caught
            // in QA / upgrade testing instead of in production. The full 2->18
            // chain is covered by autoMigrations + the manual MIGRATION_* above
            // (schemas committed under data/schemas; SpatialFin never shipped a
            // v1 DB).
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .allowMainThreadQueries()
            .build()
            .getServerDatabaseDao()
    }
}
