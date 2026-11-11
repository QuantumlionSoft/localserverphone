package dev.pocketpaas.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PlatformSettingsEntity::class,
        SiteEntity::class,
        ActivityEventEntity::class,
        DeploymentRecordEntity::class,
        ServiceStateEntity::class,
        SshSettingsEntity::class,
        SshAuthorizedKeyEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(SiteTypeConverters::class, PlatformTypeConverters::class)
abstract class PocketPaaSDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): PlatformSettingsDao
    abstract fun siteDao(): SiteDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun deploymentRecordDao(): DeploymentRecordDao
    abstract fun serviceStateDao(): ServiceStateDao
    abstract fun sshDao(): SshDao

    companion object {
        const val NAME = "pocket_paas.db"
    }
}
