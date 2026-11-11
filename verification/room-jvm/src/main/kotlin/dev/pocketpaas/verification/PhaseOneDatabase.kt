package dev.pocketpaas.verification

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import dev.pocketpaas.data.local.SiteTypeConverters
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus

/** Immutable historical schema; newer production fields must never enter old fixtures. */
@Database(
    entities = [LegacyPlatformSettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PhaseOneDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): LegacyPlatformSettingsDao
}

@Entity(tableName = "platform_settings")
data class LegacyPlatformSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val installationId: String,
    val createdAtEpochMillis: Long,
)

@Dao
abstract class LegacyPlatformSettingsDao {
    @Query("SELECT * FROM platform_settings WHERE id = 1")
    abstract suspend fun getSettings(): LegacyPlatformSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(settings: LegacyPlatformSettingsEntity): Long
    @Transaction
    open suspend fun getOrCreate(candidate: LegacyPlatformSettingsEntity): LegacyPlatformSettingsEntity {
        insertIfAbsent(candidate)
        return checkNotNull(getSettings())
    }
}

@Database(entities = [LegacyPlatformSettingsEntity::class, LegacySiteEntityV2::class], version = 2, exportSchema = true)
@TypeConverters(SiteTypeConverters::class)
abstract class PhaseTwoDatabase : RoomDatabase() {
    abstract fun platformSettingsDao(): LegacyPlatformSettingsDao
    abstract fun siteDao(): LegacySiteDaoV2
}

/** Frozen v2 site schema: later public-hostname columns must be created by their migrations. */
@Entity(
    tableName = "sites",
    indices = [
        Index(value = ["domainName"], unique = true),
        Index(value = ["internalPort"], unique = true),
    ],
)
data class LegacySiteEntityV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domainName: String,
    val internalPort: Int,
    val projectType: ProjectType,
    val processId: Int? = null,
    val publicUrl: String? = null,
    val status: SiteStatus = SiteStatus.STOPPED,
    val logs: String = "",
    val createdAt: Long,
)

@Dao
abstract class LegacySiteDaoV2 {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSite(site: LegacySiteEntityV2): Long

    @Query("SELECT * FROM sites WHERE id = :id LIMIT 1")
    abstract suspend fun getSite(id: Long): LegacySiteEntityV2?

    @Query("UPDATE sites SET logs = substr(logs || :text, -:maxChars) WHERE id = :id")
    abstract suspend fun appendLogs(id: Long, text: String, maxChars: Int): Int
}
