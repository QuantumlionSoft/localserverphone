package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SiteDao {
    @Query("SELECT * FROM sites ORDER BY createdAt DESC, id DESC")
    abstract suspend fun getSites(): List<SiteEntity>

    @Query("SELECT * FROM sites ORDER BY createdAt DESC, id DESC")
    abstract fun observeSites(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites WHERE id = :id LIMIT 1")
    abstract fun observeSite(id: Long): Flow<SiteEntity?>

    @Query("SELECT * FROM sites WHERE status = :status ORDER BY createdAt DESC, id DESC")
    abstract fun observeSitesByStatus(status: SiteStatus): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites WHERE id = :id LIMIT 1")
    abstract suspend fun getSite(id: Long): SiteEntity?

    @Query("SELECT * FROM sites WHERE domainName = :domainName LIMIT 1")
    abstract suspend fun getSiteByDomainName(domainName: String): SiteEntity?

    @Query("SELECT MAX(internalPort) FROM sites")
    abstract suspend fun getMaxInternalPort(): Int?

    @Query("SELECT internalPort FROM sites ORDER BY internalPort ASC")
    abstract suspend fun getUsedInternalPorts(): List<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSite(site: SiteEntity): Long

    /** Allocation and insertion share a Room transaction, including across concurrent callers. */
    @Transaction
    open suspend fun createWithAllocatedPort(
        domainName: String,
        projectType: ProjectType,
        createdAt: Long,
        minimumPort: Int,
        maximumPort: Int,
        reservedPorts: List<Int>,
    ): SiteEntity {
        val normalizedDomainName = SiteInput.normalizeDomainName(domainName)
        require(minimumPort in 1..65_535 && maximumPort in minimumPort..65_535) {
            "Port allocation range must be within 1..65535"
        }
        require(createdAt >= 0) { "Creation timestamp must not be negative" }
        require(getSiteByDomainName(normalizedDomainName) == null) {
            "A site already exists for domain $normalizedDomainName"
        }

        val unavailablePorts = getUsedInternalPorts().toHashSet().apply {
            addAll(reservedPorts)
        }
        val largestAllocatedPort = getMaxInternalPort()
        val preferredStart = when {
            largestAllocatedPort == null || largestAllocatedPort < minimumPort -> minimumPort
            largestAllocatedPort >= maximumPort -> minimumPort
            else -> largestAllocatedPort + 1
        }
        val allocatedPort = (preferredStart..maximumPort).firstOrNull {
            it !in unavailablePorts
        } ?: (minimumPort until preferredStart).firstOrNull {
            it !in unavailablePorts
        } ?: throw IllegalStateException("No available internal port in $minimumPort..$maximumPort")

        val candidate = SiteEntity(
            domainName = normalizedDomainName,
            internalPort = allocatedPort,
            projectType = projectType,
            createdAt = createdAt,
        )
        return candidate.copy(id = insertSite(candidate))
    }

    @Query("UPDATE sites SET status = :status, processId = :processId WHERE id = :id")
    abstract suspend fun updateRuntimeState(
        id: Long,
        status: SiteStatus,
        processId: Int?,
    ): Int

    @Query("UPDATE sites SET publicUrl = :publicUrl WHERE id = :id")
    abstract suspend fun updatePublicUrl(id: Long, publicUrl: String?): Int

    @Query("UPDATE sites SET customHostname = :customHostname WHERE id = :id")
    abstract suspend fun updateCustomHostname(id: Long, customHostname: String?): Int

    @Query(
        "UPDATE sites SET status = :newStatus, processId = :processId " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    abstract suspend fun compareAndSetRuntimeState(
        id: Long,
        expectedStatus: SiteStatus,
        newStatus: SiteStatus,
        processId: Int?,
    ): Int

    @Query("UPDATE sites SET logs = substr(logs || :text, -:maxChars) WHERE id = :id")
    protected abstract suspend fun appendLogsBounded(id: Long, text: String, maxChars: Int): Int

    /** A single SQL statement avoids lost writes and retains the most recent log characters. */
    open suspend fun appendLogs(id: Long, text: String, maxChars: Int): Int {
        require(maxChars > 0) { "Log limit must be positive" }
        return appendLogsBounded(id, text, maxChars)
    }

    @Query("UPDATE sites SET logs = '' WHERE id = :id")
    abstract suspend fun clearLogs(id: Long): Int

    @Query(
        "DELETE FROM sites WHERE id = :id " +
            "AND status IN ('STOPPED', 'FAILED') AND processId IS NULL",
    )
    abstract suspend fun deleteSiteIfInactive(id: Long): Int
}
