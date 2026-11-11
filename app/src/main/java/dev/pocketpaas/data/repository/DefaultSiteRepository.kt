package dev.pocketpaas.data.repository

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.config.SiteStoragePolicy
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.data.local.SiteDao
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.SiteStillActiveException
import dev.pocketpaas.domain.repository.SiteRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultSiteRepository @Inject constructor(
    private val dao: SiteDao,
    private val config: ServerConfig,
    private val policy: SiteStoragePolicy,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope applicationScope: CoroutineScope,
) : SiteRepository {
    private val mutableSites = MutableStateFlow<List<Site>>(emptyList())
    override val sites: StateFlow<List<Site>> = mutableSites.asStateFlow()
    private val mutableState = MutableStateFlow<SiteCollectionState>(SiteCollectionState.Loading)
    override val state: StateFlow<SiteCollectionState> = mutableState.asStateFlow()

    init {
        applicationScope.launch(ioDispatcher) {
            dao.observeSites()
                .map { entities -> entities.map { it.toDomain() } }
                .distinctUntilChanged()
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException) throw cause
                    if (cause !is Exception) throw cause
                    mutableState.value = SiteCollectionState.Failed(mutableSites.value)
                    delay(1_000L shl attempt.coerceAtMost(4).toInt())
                    true
                }
                .collect { currentSites ->
                    mutableSites.value = currentSites
                    mutableState.value = SiteCollectionState.Ready(currentSites)
                }
        }
    }

    override fun observeSite(id: Long): Flow<Site?> {
        SiteInput.validateSiteId(id)
        return dao.observeSite(id).map { it?.toDomain() }.distinctUntilChanged()
    }

    override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> =
        dao.observeSitesByStatus(status).map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun listSites(): List<Site> = withContext(ioDispatcher) {
        dao.getSites().map { it.toDomain() }
    }

    override suspend fun getSite(id: Long): Site? = withContext(ioDispatcher) {
        SiteInput.validateSiteId(id)
        dao.getSite(id)?.toDomain()
    }

    override suspend fun createSite(domainName: String, projectType: ProjectType): Site = withContext(ioDispatcher) {
        dao.createWithAllocatedPort(
            domainName = SiteInput.normalizeDomainName(domainName),
            projectType = projectType,
            createdAt = clock.millis(),
            minimumPort = policy.minimumPort,
            maximumPort = policy.maximumPort,
            reservedPorts = listOf(config.apiPort, config.nginxPort),
        ).toDomain()
    }

    override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            SiteInput.validateRuntimeState(status, processId)
            requireAffectedRow(id, dao.updateRuntimeState(id, status, processId))
        }
    }

    override suspend fun compareAndSetRuntimeState(
        id: Long,
        expectedStatus: SiteStatus,
        newStatus: SiteStatus,
        processId: Int?,
    ): Boolean = withContext(ioDispatcher) {
        SiteInput.validateSiteId(id)
        SiteInput.validateRuntimeState(newStatus, processId)
        dao.compareAndSetRuntimeState(id, expectedStatus, newStatus, processId) == 1
    }

    override suspend fun updatePublicUrl(id: Long, publicUrl: String?) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            requireAffectedRow(id, dao.updatePublicUrl(id, SiteInput.normalizePublicUrl(publicUrl)))
        }
    }

    override suspend fun updateCustomHostname(id: Long, customHostname: String?) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            val normalized = customHostname?.let(SiteInput::normalizeCustomHostname)
            requireAffectedRow(id, dao.updateCustomHostname(id, normalized))
        }
    }

    override suspend fun appendLogs(id: Long, text: String) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            requireAffectedRow(
                id,
                dao.appendLogs(id, SiteInput.logTail(text, policy.maximumLogCharacters), policy.maximumLogCharacters),
            )
        }
    }

    override suspend fun clearLogs(id: Long) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            requireAffectedRow(id, dao.clearLogs(id))
        }
    }

    override suspend fun deleteSiteIfInactive(id: Long) {
        withContext(ioDispatcher) {
            SiteInput.validateSiteId(id)
            if (dao.deleteSiteIfInactive(id) == 0) {
                if (dao.getSite(id) == null) throw SiteNotFoundException(id)
                throw SiteStillActiveException(id)
            }
        }
    }

    private fun requireAffectedRow(id: Long, affectedRows: Int) {
        if (affectedRows == 0) throw SiteNotFoundException(id)
        check(affectedRows == 1) { "Unexpected affected site row count" }
    }
}
