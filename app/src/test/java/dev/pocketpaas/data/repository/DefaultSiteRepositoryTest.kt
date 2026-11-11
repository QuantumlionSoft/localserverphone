package dev.pocketpaas.data.repository

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.config.SiteStoragePolicy
import dev.pocketpaas.data.local.SiteDao
import dev.pocketpaas.data.local.SiteEntity
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.SiteStillActiveException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSiteRepositoryTest {
    @Test
    fun creationUsesCanonicalIdentityFixedClockAndExcludesConfiguredServerPorts() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(
            dao,
            config = ServerConfig(apiPort = 12_000, nginxPort = 12_001),
            policy = SiteStoragePolicy(minimumPort = 12_000, maximumPort = 12_010),
        )

        val site = repository.createSite("  BÜCHER.Example.  ", ProjectType.DJANGO)

        assertEquals("xn--bcher-kva.example", site.domainName)
        assertEquals(12_002, site.internalPort)
        assertEquals(1_700_000_000_123L, site.createdAt)
        assertEquals(SiteStatus.STOPPED, site.status)
        assertNull(site.processId)
        expectFailure<IllegalArgumentException> {
            repository.createSite("XN--BCHER-KVA.EXAMPLE", ProjectType.NODEJS)
        }
        assertEquals(1, dao.getSites().size)
    }

    @Test
    fun invalidDomainAndUrlNeverReachPersistence() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)

        expectFailure<IllegalArgumentException> {
            repository.createSite("../escaped", ProjectType.STATIC)
        }
        assertTrue(dao.getSites().isEmpty())
        val site = repository.createSite("valid.example", ProjectType.STATIC)
        expectFailure<IllegalArgumentException> {
            repository.updatePublicUrl(site.id, "https://token@public.example")
        }
        assertEquals(0, dao.publicUrlWrites)
        repository.updatePublicUrl(site.id, "  https://public.example  ")
        assertEquals("https://public.example", repository.getSite(site.id)?.publicUrl)
        repository.updatePublicUrl(site.id, null)
        assertNull(repository.getSite(site.id)?.publicUrl)
    }

    @Test
    fun stateFlowPublishesPersistedRuntimeChangesAndReactiveQueries() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        assertEquals(SiteCollectionState.Loading, repository.state.value)
        runCurrent()
        assertEquals(SiteCollectionState.Ready(emptyList()), repository.state.value)

        val site = repository.createSite("live.example", ProjectType.NODEJS)
        runCurrent()
        assertEquals(listOf(site), repository.sites.value)

        repository.updateRuntimeState(site.id, SiteStatus.RUNNING, 7_321)
        runCurrent()
        val running = site.copy(status = SiteStatus.RUNNING, processId = 7_321)
        assertEquals(listOf(running), repository.sites.value)
        assertEquals(SiteCollectionState.Ready(listOf(running)), repository.state.value)
        assertEquals(running, repository.observeSite(site.id).first())
        assertEquals(listOf(running), repository.observeSitesByStatus(SiteStatus.RUNNING).first())
        assertTrue(repository.observeSitesByStatus(SiteStatus.STOPPED).first().isEmpty())
    }

    @Test
    fun observationFailureRetainsLastKnownSitesAndRecoversAfterRetryDelay() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        val site = repository.createSite("recover.example", ProjectType.STATIC)
        runCurrent()
        assertEquals(1, dao.observationStarts)

        dao.failObservation(IOException("Transient database failure"))
        runCurrent()
        assertEquals(listOf(site), repository.sites.value)
        assertEquals(SiteCollectionState.Failed(listOf(site)), repository.state.value)

        // Writes can arrive while collection is suspended. The retry must re-read them.
        repository.updatePublicUrl(site.id, "https://recovered.example")
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, dao.observationStarts)
        assertEquals(listOf(site), repository.sites.value)
        advanceTimeBy(1)
        runCurrent()

        val recovered = site.copy(publicUrl = "https://recovered.example")
        assertEquals(2, dao.observationStarts)
        assertEquals(listOf(recovered), repository.sites.value)
        assertEquals(SiteCollectionState.Ready(listOf(recovered)), repository.state.value)
    }

    @Test
    fun cancellationIsNotRetriedOrPresentedAsDatabaseFailure() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        runCurrent()

        dao.failObservation(CancellationException("Collector cancelled"))
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(1, dao.observationStarts)
        assertEquals(SiteCollectionState.Ready(emptyList()), repository.state.value)
    }

    @Test
    fun stoppedStateCannotRetainPidAndInvalidPidsCannotReachDao() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        val site = repository.createSite("runtime.example", ProjectType.NODEJS)
        repository.updateRuntimeState(site.id, SiteStatus.RUNNING, 600)

        expectFailure<IllegalArgumentException> {
            repository.updateRuntimeState(site.id, SiteStatus.STOPPED, 600)
        }
        expectFailure<IllegalArgumentException> {
            repository.updateRuntimeState(site.id, SiteStatus.RUNNING, 0)
        }
        expectFailure<IllegalArgumentException> {
            repository.compareAndSetRuntimeState(site.id, SiteStatus.RUNNING, SiteStatus.STOPPED, 600)
        }
        assertEquals(SiteStatus.RUNNING, repository.getSite(site.id)?.status)
        assertEquals(600, repository.getSite(site.id)?.processId)
        repository.updateRuntimeState(site.id, SiteStatus.STOPPED, null)
        assertNull(repository.getSite(site.id)?.processId)
    }

    @Test
    fun logAppendLimitsInputBeforePersistenceAndReplacesEmbeddedNul() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao, policy = SiteStoragePolicy(maximumLogCharacters = 4))
        val site = repository.createSite("logs.example", ProjectType.STATIC)

        repository.appendLogs(site.id, "large discarded prefix\u0000🚀é終")

        assertEquals("\uFFFD🚀é終", dao.lastAppendText)
        assertEquals(4, dao.lastAppendLimit)
        assertEquals("\uFFFD🚀é終", repository.getSite(site.id)?.logs)
        repository.clearLogs(site.id)
        assertEquals("", repository.getSite(site.id)?.logs)
    }

    @Test
    fun staleOrMissingCompareAndSetIsFalseAndMissingUpdatesAreReported() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        val site = repository.createSite("cas.example", ProjectType.NODEJS)

        assertFalse(repository.compareAndSetRuntimeState(site.id, SiteStatus.RUNNING, SiteStatus.FAILED, null))
        assertFalse(repository.compareAndSetRuntimeState(99, SiteStatus.STOPPED, SiteStatus.RUNNING, 300))
        assertTrue(repository.compareAndSetRuntimeState(site.id, SiteStatus.STOPPED, SiteStatus.DEPLOYING, null))
        expectFailure<SiteNotFoundException> { repository.updateRuntimeState(99, SiteStatus.FAILED, null) }
        expectFailure<SiteNotFoundException> { repository.updatePublicUrl(99, null) }
        expectFailure<SiteNotFoundException> { repository.appendLogs(99, "log") }
        expectFailure<SiteNotFoundException> { repository.clearLogs(99) }
        assertNull(repository.getSite(99))
    }

    @Test
    fun deletionRequiresInactiveStatusAndNoTrackedProcess() = runTest {
        val dao = RecordingSiteDao()
        val repository = repository(dao)
        val site = repository.createSite("delete.example", ProjectType.NODEJS)
        repository.updateRuntimeState(site.id, SiteStatus.RUNNING, 400)
        expectFailure<SiteStillActiveException> { repository.deleteSiteIfInactive(site.id) }
        repository.updateRuntimeState(site.id, SiteStatus.FAILED, 400)
        expectFailure<SiteStillActiveException> { repository.deleteSiteIfInactive(site.id) }
        repository.updateRuntimeState(site.id, SiteStatus.FAILED, null)

        repository.deleteSiteIfInactive(site.id)

        assertNull(repository.getSite(site.id))
        expectFailure<SiteNotFoundException> { repository.deleteSiteIfInactive(site.id) }
    }

    private fun TestScope.repository(
        dao: RecordingSiteDao,
        config: ServerConfig = ServerConfig(),
        policy: SiteStoragePolicy = SiteStoragePolicy(),
    ) = DefaultSiteRepository(
        dao,
        config,
        policy,
        Clock.fixed(Instant.ofEpochMilli(1_700_000_000_123L), ZoneOffset.UTC),
        StandardTestDispatcher(testScheduler),
        backgroundScope,
    )

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline action: suspend () -> Unit): T {
        try {
            action()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("Unreachable after JUnit failure")
    }

    /** Persistence double: Room SQL and transaction semantics have separate database tests. */
    private class RecordingSiteDao : SiteDao() {
        private val rows = MutableStateFlow<List<SiteEntity>>(emptyList())
        private val changes = Channel<Throwable?>(Channel.UNLIMITED)
        private var nextId = 1L
        var observationStarts = 0
            private set
        var publicUrlWrites = 0
            private set
        var lastAppendText: String? = null
            private set
        var lastAppendLimit: Int? = null
            private set

        fun failObservation(failure: Throwable) {
            check(changes.trySend(failure).isSuccess)
        }

        override suspend fun getSites(): List<SiteEntity> = rows.value

        override fun observeSites(): Flow<List<SiteEntity>> = flow {
            observationStarts++
            emit(rows.value)
            for (failure in changes) {
                if (failure != null) throw failure
                emit(rows.value)
            }
        }

        override fun observeSite(id: Long): Flow<SiteEntity?> = rows.map { sites -> sites.find { it.id == id } }

        override fun observeSitesByStatus(status: SiteStatus): Flow<List<SiteEntity>> =
            rows.map { sites -> sites.filter { it.status == status } }

        override suspend fun getSite(id: Long): SiteEntity? = rows.value.find { it.id == id }

        override suspend fun getSiteByDomainName(domainName: String): SiteEntity? =
            rows.value.find { it.domainName == domainName }

        override suspend fun getMaxInternalPort(): Int? = rows.value.maxOfOrNull { it.internalPort }

        override suspend fun getUsedInternalPorts(): List<Int> = rows.value.map { it.internalPort }

        override suspend fun insertSite(site: SiteEntity): Long {
            val id = nextId++
            publish(rows.value + site.copy(id = id))
            return id
        }

        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?): Int =
            update(id) { it.copy(status = status, processId = processId) }

        override suspend fun updatePublicUrl(id: Long, publicUrl: String?): Int {
            publicUrlWrites++
            return update(id) { it.copy(publicUrl = publicUrl) }
        }

        override suspend fun updateCustomHostname(id: Long, customHostname: String?): Int =
            update(id) { it.copy(customHostname = customHostname) }

        override suspend fun compareAndSetRuntimeState(
            id: Long,
            expectedStatus: SiteStatus,
            newStatus: SiteStatus,
            processId: Int?,
        ): Int {
            if (getSite(id)?.status != expectedStatus) return 0
            return update(id) { it.copy(status = newStatus, processId = processId) }
        }

        override suspend fun appendLogsBounded(id: Long, text: String, maxChars: Int): Int {
            lastAppendText = text
            lastAppendLimit = maxChars
            return update(id) { site ->
                val points = (site.logs + text).codePoints().toArray()
                val start = maxOf(0, points.size - maxChars)
                site.copy(logs = String(points, start, points.size - start))
            }
        }

        override suspend fun clearLogs(id: Long): Int = update(id) { it.copy(logs = "") }

        override suspend fun deleteSiteIfInactive(id: Long): Int {
            val site = getSite(id) ?: return 0
            if (site.status !in setOf(SiteStatus.STOPPED, SiteStatus.FAILED) || site.processId != null) return 0
            publish(rows.value.filterNot { it.id == id })
            return 1
        }

        private fun update(id: Long, transform: (SiteEntity) -> SiteEntity): Int {
            if (rows.value.none { it.id == id }) return 0
            publish(rows.value.map { if (it.id == id) transform(it) else it })
            return 1
        }

        private fun publish(sites: List<SiteEntity>) {
            rows.value = sites.sortedWith(compareByDescending<SiteEntity> { it.createdAt }.thenByDescending { it.id })
            check(changes.trySend(null).isSuccess)
        }
    }
}
