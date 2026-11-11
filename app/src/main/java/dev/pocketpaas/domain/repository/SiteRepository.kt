package dev.pocketpaas.domain.repository

import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SiteRepository {
    val sites: StateFlow<List<Site>>
    val state: StateFlow<SiteCollectionState>

    fun observeSite(id: Long): Flow<Site?>
    fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>>
    suspend fun listSites(): List<Site>
    suspend fun getSite(id: Long): Site?
    suspend fun createSite(domainName: String, projectType: ProjectType): Site
    suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?)
    suspend fun compareAndSetRuntimeState(
        id: Long,
        expectedStatus: SiteStatus,
        newStatus: SiteStatus,
        processId: Int?,
    ): Boolean
    suspend fun updatePublicUrl(id: Long, publicUrl: String?)
    suspend fun updateCustomHostname(id: Long, customHostname: String?) {
        throw UnsupportedOperationException("Custom hostnames are not supported by this repository.")
    }
    suspend fun appendLogs(id: Long, text: String)
    suspend fun clearLogs(id: Long)
    suspend fun deleteSiteIfInactive(id: Long)
}
