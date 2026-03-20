package dev.jdtech.jellyfin.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.jdtech.jellyfin.models.SpatialFinEpisodeDto
import dev.jdtech.jellyfin.models.LocalMediaPlaybackStateDto
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.SpatialFinMediaStreamDto
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.models.SpatialFinSeasonDto
import dev.jdtech.jellyfin.models.SpatialFinSegmentDto
import dev.jdtech.jellyfin.models.SpatialFinShowDto
import dev.jdtech.jellyfin.models.SpatialFinSourceDto
import dev.jdtech.jellyfin.models.SpatialFinTrickplayInfoDto
import dev.jdtech.jellyfin.models.SpatialFinUserDataDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User

@Database(
    entities =
        [
            Server::class,
            ServerAddress::class,
            User::class,
            SpatialFinMovieDto::class,
            SpatialFinShowDto::class,
            SpatialFinSeasonDto::class,
            SpatialFinEpisodeDto::class,
            SpatialFinSourceDto::class,
            SpatialFinMediaStreamDto::class,
            SpatialFinUserDataDto::class,
            SpatialFinTrickplayInfoDto::class,
            SpatialFinSegmentDto::class,
            LocalMediaPlaybackStateDto::class,
            DownloadTaskDto::class,
        ],
    version = 13,
    autoMigrations =
        [
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5, spec = ServerDatabase.TrickplayMigration::class),
            AutoMigration(from = 5, to = 6, spec = ServerDatabase.IntrosMigration::class),
            AutoMigration(from = 7, to = 8),
            AutoMigration(from = 8, to = 9),
            AutoMigration(from = 9, to = 10),
            AutoMigration(from = 10, to = 11),
            AutoMigration(from = 11, to = 12),
            AutoMigration(from = 12, to = 13),
        ],
)
@TypeConverters(Converters::class)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun getServerDatabaseDao(): ServerDatabaseDao

    @DeleteTable(tableName = "trickPlayManifests") class TrickplayMigration : AutoMigrationSpec

    @DeleteTable(tableName = "intros") class IntrosMigration : AutoMigrationSpec
}

val MIGRATION_6_7 =
    object : Migration(startVersion = 6, endVersion = 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE segments")
            db.execSQL(
                "CREATE TABLE segments (`itemId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTicks` INTEGER NOT NULL, `endTicks` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `type`), FOREIGN KEY(`itemId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
        }
    }
