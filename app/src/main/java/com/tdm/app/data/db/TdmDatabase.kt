package com.tdm.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DownloadTaskEntity::class,
        SourceEntity::class,
        SourceTemplateEntity::class,
        ScheduleProfileEntity::class,
        ScheduleWindowEntity::class,
        DownloadSessionEntity::class,
        DownloadStatisticsEntity::class,
        StorageProfileEntity::class,
        SystemLogEntity::class,
        AccountEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TdmDatabase : RoomDatabase() {
    abstract fun taskDao(): DownloadTaskDao
    abstract fun sourceDao(): SourceDao
    abstract fun sourceTemplateDao(): SourceTemplateDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sessionDao(): SessionDao
    abstract fun statisticsDao(): StatisticsDao
    abstract fun storageProfileDao(): StorageProfileDao
    abstract fun systemLogDao(): SystemLogDao
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile private var instance: TdmDatabase? = null

        fun get(context: Context): TdmDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val db = Room.databaseBuilder(
                    context.applicationContext, TdmDatabase::class.java, "tdm.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // transactional crash-safety (spec §24)
                    .build()
                instance = db
                return db
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS telegram_accounts (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, phoneNumber TEXT NOT NULL, apiId INTEGER NOT NULL, apiHash TEXT NOT NULL, loggedIn INTEGER NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE sources ADD COLUMN accountId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN accountId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE sources ADD COLUMN messageId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sources ADD COLUMN topicId INTEGER")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN topicId INTEGER")
            }
        }
    }
}
