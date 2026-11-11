@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pocketpaas.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.domain.dashboard.DashboardActions
import dev.pocketpaas.domain.dashboard.DashboardDevice
import dev.pocketpaas.domain.dashboard.DashboardNetworkState
import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.dashboard.DashboardOperationState
import dev.pocketpaas.domain.dashboard.PickedArchive
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.PlatformInfo
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.CloudflareConnectionState
import dev.pocketpaas.domain.network.CloudflareSitePublisher
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.repository.PlatformRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.usecase.InitializePlatform
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Test
    fun aReconnectingTunnelKeepsLocalDeploymentAvailable() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        fixture.runtime.state.value = RemoteServerState(RemoteServerStatus.RUNNING)
        fixture.networkState.value = NetworkState(
            nginxStatus = NetworkServiceStatus.RUNNING,
            tunnelStatus = NetworkServiceStatus.STARTING,
        )
        runCurrent()
        assertTrue(model.state.value.canManageSites)
        model.showAddProject()
        assertTrue(model.state.value.showAddProject)
    }

    @Before
    fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun clearOwnersAndDispatcher() {
        stores.forEach(ViewModelStore::clear)
        stores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun repositoryFailurePreservesLastKnownSitesAndBlocksDeploymentUntilRecovery() = runTest {
        val fixture = Fixture()
        fixture.sites.publish(SiteCollectionState.Ready(listOf(site)))
        val model = fixture.create()
        runCurrent()
        assertEquals(listOf(site), model.state.value.sites)
        assertTrue(model.state.value.canManageSites)

        fixture.sites.publish(SiteCollectionState.Failed(listOf(site)))
        runCurrent()
        model.setDomain("new.example")
        model.selectZip("content://projects/new.zip")
        runCurrent()
        model.deploy()
        assertEquals(listOf(site), model.state.value.sites)
        assertTrue(model.state.value.siteReadFailed)
        assertFalse(model.state.value.canManageSites)
        assertTrue(fixture.actions.deployments.isEmpty())

        fixture.sites.publish(SiteCollectionState.Ready(listOf(site.copy(logs = "recovered"))))
        runCurrent()
        assertFalse(model.state.value.siteReadFailed)
        assertEquals("recovered", model.state.value.sites.single().logs)
        assertTrue(model.state.value.canManageSites)
    }

    @Test
    fun newDeploymentNormalizesDomainAndForwardsSelectedContentUriOnlyOnDeploy() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.showAddProject()
        model.setDomain("  DEMO.Example.  ")
        model.setProjectType(ProjectType.NODEJS)
        model.selectZip("content://projects/node.zip")
        runCurrent()
        assertTrue(fixture.actions.deployments.isEmpty())

        model.deploy()

        assertEquals(
            RemoteDeployRequest(domainName = "demo.example", projectType = ProjectType.NODEJS) to "content://projects/node.zip",
            fixture.actions.deployments.single(),
        )
        assertFalse(model.state.value.showAddProject)
    }

    @Test
    fun inactiveExistingProjectUsesItsIdInsteadOfCreatingAnotherSite() = runTest {
        val fixture = Fixture()
        fixture.sites.publish(SiteCollectionState.Ready(listOf(site.copy(status = SiteStatus.FAILED))))
        val model = fixture.create()
        runCurrent()
        model.setDomain("EXISTING.EXAMPLE.")
        model.setProjectType(ProjectType.NODEJS)
        model.selectZip("content://projects/replacement.zip")
        runCurrent()

        model.deploy()

        assertEquals(RemoteDeployRequest(siteId = site.id), fixture.actions.deployments.single().first)
        assertEquals(0, fixture.sites.creations)
    }

    @Test
    fun existingTypeMismatchAndActivePidCannotBeRedeployed() = runTest {
        val fixture = Fixture()
        fixture.sites.publish(SiteCollectionState.Ready(listOf(site)))
        val model = fixture.create()
        runCurrent()
        model.setDomain(site.domainName)
        model.setProjectType(ProjectType.REACT)
        model.selectZip("content://projects/replacement.zip")
        runCurrent()
        model.deploy()
        assertTrue(fixture.actions.deployments.isEmpty())
        assertNotNull(model.state.value.draft.error)

        model.setProjectType(ProjectType.NODEJS)
        fixture.sites.publish(SiteCollectionState.Ready(listOf(site.copy(status = SiteStatus.FAILED, processId = 500))))
        runCurrent()
        model.deploy()
        assertTrue(fixture.actions.deployments.isEmpty())
        assertNotNull(model.state.value.draft.error)
    }

    @Test
    fun cancelledOlderProviderQueryCannotOverwriteNewerZipSelection() = runTest {
        val fixture = Fixture()
        lateinit var olderReply: Continuation<PickedArchive>
        lateinit var olderJob: Job
        fixture.device.onDescribe = { uri ->
            if (uri.endsWith("old.zip")) {
                olderJob = currentCoroutineContext().job
                // Models a ContentProvider that finishes a blocking query after cancellation.
                suspendCoroutine { olderReply = it }
            } else PickedArchive(uri, "new.zip", 123)
        }
        val model = fixture.create()
        runCurrent()
        model.selectZip("content://projects/old.zip")
        runCurrent()
        model.selectZip("content://projects/new.zip")
        runCurrent()
        assertTrue(olderJob.isCancelled)
        assertEquals("new.zip", model.state.value.draft.zipName)

        olderReply.resume(PickedArchive("content://projects/old.zip", "old.zip", 100))
        runCurrent()

        assertEquals("content://projects/new.zip", model.state.value.draft.zipUri)
        assertEquals("new.zip", model.state.value.draft.zipName)
    }

    @Test
    fun failedProviderAndOversizedArchiveNeverLeaveADeployableUri() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        fixture.device.onDescribe = { throw IOException("provider unavailable") }
        model.selectZip("content://projects/broken.zip")
        runCurrent()
        assertNull(model.state.value.draft.zipUri)
        assertNotNull(model.state.value.draft.error)

        fixture.device.onDescribe = { PickedArchive(it, "large.zip", 64L * 1024 * 1024 + 1) }
        model.selectZip("content://projects/large.zip")
        runCurrent()
        assertNull(model.state.value.draft.zipUri)
        assertNotNull(model.state.value.draft.error)
    }

    @Test
    fun globalStopIsAdmittedWhileDeploymentIsBusy() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        fixture.actions.operation.value = DashboardOperationState(1, DashboardOperationKind.DEPLOY, running = true)
        runCurrent()
        assertTrue(model.state.value.operationBusy)

        model.masterSwitch(false)

        assertEquals(1, fixture.actions.stops)
    }

    @Test
    fun cancelledDeploymentKeepsDraftAndDoesNotOpenSuccessLogs() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.setDomain("preserve.example")
        model.setProjectType(ProjectType.REACT)
        model.selectZip("content://projects/react.zip")
        runCurrent()
        val draft = model.state.value.draft
        fixture.actions.operation.value = DashboardOperationState(5, DashboardOperationKind.DEPLOY, running = true)
        runCurrent()

        fixture.actions.operation.value = DashboardOperationState(5, DashboardOperationKind.DEPLOY, siteId = 9, cancelled = true, message = "Kurulum iptal edildi")
        runCurrent()

        assertEquals(draft, model.state.value.draft)
        assertNull(model.state.value.selectedSiteId)
        assertFalse(model.state.value.operationBusy)
    }

    @Test
    fun successfulDeploymentClearsDraftAndSelectsResultForLogs() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.setDomain("done.example")
        model.selectZip("content://projects/done.zip")
        runCurrent()
        fixture.actions.operation.value = DashboardOperationState(6, DashboardOperationKind.DEPLOY, siteId = 9)
        runCurrent()

        assertEquals(ProjectDraft(), model.state.value.draft)
        assertEquals(9L, model.state.value.selectedSiteId)
    }

    @Test
    fun sensitiveApiKeyIsOnlySentToClipboardOnExplicitRequest() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        assertEquals(0, fixture.keys.reads)
        assertTrue(fixture.device.copied.isEmpty())

        model.copyApiKey()
        runCurrent()

        assertEquals(listOf(secret), fixture.device.copied)
        assertEquals(1, fixture.keys.reads)
        assertFalse(model.state.value.toString().contains(secret))
        assertNotNull(model.state.value.notice)
    }

    @Test
    fun initializationFailureBlocksStartupAndCanBeRefreshedSuccessfully() = runTest {
        val fixture = Fixture()
        fixture.platform.failure = IOException("storage failed")
        fixture.runtime.state.value = RemoteServerState()
        val model = fixture.create()
        runCurrent()
        assertTrue(model.state.value.setupFailed)
        assertFalse(model.state.value.initializing)
        model.masterSwitch(true)
        assertTrue(fixture.actions.starts.isEmpty())

        fixture.platform.failure = null
        model.refresh()
        runCurrent()

        assertFalse(model.state.value.setupFailed)
        assertTrue(model.state.value.setupReady)
        model.masterSwitch(true)
        assertEquals(listOf(false), fixture.actions.starts)
        assertEquals(2, fixture.platform.initializations)
    }

    @Test
    fun startupRequiresTermuxAndPermissionAndUsesExplicitLanChoice() = runTest {
        val fixture = Fixture()
        fixture.runtime.state.value = RemoteServerState()
        fixture.platform.capabilities = RuntimeCapabilities(false, false, emptyList())
        val model = fixture.create()
        runCurrent()
        model.setLanAccess(true)
        model.masterSwitch(true)
        assertTrue(fixture.actions.starts.isEmpty())

        fixture.platform.capabilities = RuntimeCapabilities(true, false, emptyList())
        model.refresh()
        runCurrent()
        model.masterSwitch(true)
        assertTrue(fixture.actions.starts.isEmpty())

        fixture.platform.capabilities = RuntimeCapabilities(true, true, emptyList())
        model.refresh()
        runCurrent()
        model.masterSwitch(true)
        assertEquals(listOf(true), fixture.actions.starts)
        fixture.runtime.state.value = RemoteServerState(RemoteServerStatus.RUNNING)
        runCurrent()
        model.setLanAccess(false)
        assertTrue(model.state.value.lanAccess)
    }

    @Test
    fun cancellingAUiOperationUsesGatewayButStartupCancellationStopsServer() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        fixture.actions.operation.value = DashboardOperationState(1, DashboardOperationKind.SITE_START, running = true)
        runCurrent()
        model.cancelOperation()
        assertEquals(1, fixture.actions.cancellations)
        assertEquals(0, fixture.actions.stops)

        fixture.actions.operation.value = DashboardOperationState()
        fixture.runtime.state.value = RemoteServerState(RemoteServerStatus.STARTING)
        runCurrent()
        model.cancelOperation()
        assertEquals(1, fixture.actions.stops)
    }

    @Test
    fun clearingViewModelOwnerCancelsPickerButLeavesApplicationOwnedOperationAlive() = runTest {
        val fixture = Fixture()
        var providerCancelled = false
        fixture.device.onDescribe = {
            try { awaitCancellation() } finally { providerCancelled = true }
        }
        val model = fixture.create()
        runCurrent()
        model.selectZip("content://projects/slow.zip")
        fixture.actions.operation.value = DashboardOperationState(3, DashboardOperationKind.DEPLOY, running = true)
        runCurrent()

        fixture.store.clear()
        runCurrent()

        assertTrue(providerCancelled)
        assertTrue(fixture.actions.operation.value.running)
        assertEquals(0, fixture.actions.cancellations)
        assertEquals(0, fixture.actions.stops)
    }

    @Test
    fun cloudflareSetupClearsTheEnteredTokenAfterThePublisherStoresIt() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.setCloudflareApiToken("sensitive-cloudflare-token-123456")
        model.setCloudflareAccountId("a".repeat(32))
        model.setCloudflareZoneName("example.com")
        assertFalse(model.state.value.toString().contains("sensitive-cloudflare-token-123456"))

        model.configureCloudflare()
        runCurrent()

        assertTrue(model.state.value.cloudflareConnection.configured)
        assertEquals("sensitive-cloudflare-token-123456", fixture.cloudflare.configuredToken)
        assertEquals("", model.state.value.cloudflareDraft.apiToken)
        assertFalse(model.state.value.cloudflareConnection.toString().contains("sensitive-cloudflare-token-123456"))
        assertFalse(model.state.value.notice?.text.orEmpty().contains("sensitive-cloudflare-token-123456"))
    }

    @Test
    fun customHostnameIsManagedSeparatelyFromTheSitePathIdentity() = runTest {
        val fixture = Fixture()
        fixture.sites.publish(SiteCollectionState.Ready(listOf(site)))
        fixture.cloudflare.cloudflareConnection.value = CloudflareConnectionState(true, "a".repeat(32), "example.com")
        val model = fixture.create()
        runCurrent()

        model.setSiteCustomHostname(site.id, "www.example.com")
        runCurrent()

        assertEquals(site.domainName, model.state.value.sites.single().domainName)
        assertEquals("www.example.com", model.state.value.sites.single().customHostname)
        assertEquals(site.id to "www.example.com", fixture.cloudflare.hostnameUpdates.single())
    }

    @Test
    fun consumingAnOldSnackbarCannotEraseANewerNotice() = runTest {
        val fixture = Fixture()
        val model = fixture.create()
        runCurrent()
        model.showMessage("İlk bildirim")
        val firstId = requireNotNull(model.state.value.notice).id
        model.showMessage("Yeni bildirim")
        model.consumeNotice(firstId)

        assertEquals("Yeni bildirim", model.state.value.notice?.text)
        model.consumeNotice(requireNotNull(model.state.value.notice).id)
        assertNull(model.state.value.notice)
    }

    private inner class Fixture {
        val sites = FakeSites()
        val runtime = FakeRuntime()
        val networkState = MutableStateFlow(NetworkState())
        val network = object : DashboardNetworkState { override val state = networkState }
        val actions = FakeActions()
        val device = FakeDevice()
        val keys = FakeKeys()
        val cloudflare = FakeCloudflare(sites)
        val platform = FakePlatform()
        val store = ViewModelStore().also(stores::add)

        fun create(): MainViewModel = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
                MainViewModel(
                    sites, runtime, network, actions, device, keys, InitializePlatform(platform), DeploymentPolicy(),
                    cloudflare = cloudflare,
                ),
            ))
        })[MainViewModel::class]
    }

    private class FakeCloudflare(private val sites: FakeSites) : CloudflareSitePublisher {
        override val cloudflareConnection = MutableStateFlow(CloudflareConnectionState())
        var configuredToken: String? = null
        val hostnameUpdates = mutableListOf<Pair<Long, String?>>()

        override suspend fun configureCloudflare(apiToken: String, accountId: String, zoneName: String) {
            configuredToken = apiToken
            cloudflareConnection.value = CloudflareConnectionState(true, accountId, zoneName)
        }

        override suspend fun disconnectCloudflare() {
            cloudflareConnection.value = CloudflareConnectionState()
        }

        override suspend fun setCustomHostname(siteId: Long, hostname: String?) {
            hostnameUpdates += siteId to hostname
            sites.updateCustomHostname(siteId, hostname)
        }
    }

    private class FakeActions : DashboardActions {
        override val operation = MutableStateFlow(DashboardOperationState())
        val starts = mutableListOf<Boolean>()
        val deployments = mutableListOf<Pair<RemoteDeployRequest, String>>()
        var stops = 0
        var cancellations = 0
        override fun startServer(enableLanAccess: Boolean) { starts += enableLanAccess }
        override fun stopServer() { stops++ }
        override fun retryNetwork() = Unit
        override fun startSite(id: Long) = Unit
        override fun stopSite(id: Long) = Unit
        override fun deploy(request: RemoteDeployRequest, uri: String) { deployments += request to uri }
        override fun cancelOperation() { cancellations++ }
        override fun dismissResult() { operation.value = DashboardOperationState() }
    }

    private class FakeDevice : DashboardDevice {
        val copied = mutableListOf<String>()
        var onDescribe: suspend (String) -> PickedArchive = { PickedArchive(it, it.substringAfterLast('/'), 123) }
        override suspend fun describeArchive(uri: String): PickedArchive = onDescribe(uri)
        override suspend fun copySensitive(text: String) { copied += text }
    }

    private class FakeKeys : ApiKeyStore {
        var reads = 0
        override suspend fun token(): String { reads++; return secret }
    }

    private class FakeRuntime : RemoteServerRuntime {
        override val state = MutableStateFlow(RemoteServerState(RemoteServerStatus.RUNNING, "127.0.0.1"))
        override suspend fun start(bindHost: String) { state.value = RemoteServerState(RemoteServerStatus.RUNNING, bindHost) }
        override suspend fun startNetwork() = Unit
        override suspend fun stop() { state.value = RemoteServerState() }
    }

    private class FakePlatform : PlatformRepository {
        var capabilities = RuntimeCapabilities(true, true, listOf("arm64-v8a"))
        var failure: IOException? = null
        var initializations = 0
        override suspend fun initialize(): PlatformInfo {
            initializations++
            failure?.let { throw it }
            return PlatformInfo(
                installationId = "test-installation", createdAtEpochMillis = 1,
                layout = RuntimeLayout("/runtime", "/runtime/htdocs", "/runtime/nginx.conf", "/runtime/conf.d", "/runtime/state", "/runtime/logs"),
                capabilities = capabilities, apiPort = 8080, nginxPort = 8081,
            )
        }
    }

    private class FakeSites : SiteRepository {
        private val rows = MutableStateFlow(emptyList<Site>())
        override val sites: StateFlow<List<Site>> = rows
        override val state = MutableStateFlow<SiteCollectionState>(SiteCollectionState.Ready(emptyList()))
        var creations = 0
        fun publish(next: SiteCollectionState) {
            state.value = next
            rows.value = when (next) {
                is SiteCollectionState.Ready -> next.sites
                is SiteCollectionState.Failed -> next.lastKnownSites
                SiteCollectionState.Loading -> rows.value
            }
        }
        private fun replace(next: Site) { publish(SiteCollectionState.Ready(rows.value.map { if (it.id == next.id) next else it })) }
        override fun observeSite(id: Long): Flow<Site?> = rows.map { sites -> sites.firstOrNull { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = rows.map { sites -> sites.filter { it.status == status } }
        override suspend fun listSites(): List<Site> = rows.value
        override suspend fun getSite(id: Long): Site? = rows.value.firstOrNull { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site {
            creations++
            return Site(rows.value.size + 1L, domainName, 9000 + rows.value.size, projectType, null, null, SiteStatus.STOPPED, "", 1).also {
                publish(SiteCollectionState.Ready(rows.value + it))
            }
        }
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
            replace(requireNotNull(getSite(id)).copy(status = status, processId = processId))
        }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean {
            if (getSite(id)?.status != expectedStatus) return false
            updateRuntimeState(id, newStatus, processId)
            return true
        }
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { replace(requireNotNull(getSite(id)).copy(publicUrl = publicUrl)) }
        override suspend fun updateCustomHostname(id: Long, customHostname: String?) {
            replace(requireNotNull(getSite(id)).copy(customHostname = customHostname))
        }
        override suspend fun appendLogs(id: Long, text: String) { val current = requireNotNull(getSite(id)); replace(current.copy(logs = current.logs + text)) }
        override suspend fun clearLogs(id: Long) { replace(requireNotNull(getSite(id)).copy(logs = "")) }
        override suspend fun deleteSiteIfInactive(id: Long) { publish(SiteCollectionState.Ready(rows.value.filterNot { it.id == id })) }
    }

    companion object {
        private val secret = "f3".repeat(32)
        private val site = Site(7, "existing.example", 9001, ProjectType.NODEJS, null, null, SiteStatus.STOPPED, "ready", 1)
    }
}
