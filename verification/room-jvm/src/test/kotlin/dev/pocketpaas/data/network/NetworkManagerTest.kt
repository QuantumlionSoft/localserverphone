package dev.pocketpaas.data.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pocketpaas.core.config.NetworkPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.TunnelMode
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.deployment.PreparedProject
import dev.pocketpaas.domain.deployment.ProjectEntryPoint
import dev.pocketpaas.domain.deployment.ProjectMetadata
import dev.pocketpaas.domain.network.CloudflareConnectionState
import dev.pocketpaas.domain.network.CloudflareTunnelProvider
import dev.pocketpaas.domain.network.NamedTunnelCredentials
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessIOException
import dev.pocketpaas.domain.process.ProcessOutput
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.process.ProcessTermination
import dev.pocketpaas.domain.repository.ServerStateRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.system.RuntimeFileStore
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real loopback HTTP probes exercise orchestration; process stream draining is tested separately. */
class NetworkManagerTest {
    @Test
    fun snapshotRemovalAndRestoreReloadBothRevisionsAndClearThenRepublishSiteUrl() = runBlocking {
        fixture {
            val site = sites.getSite(1)!!
            manager.startAll()
            val snapshot = manager.snapshot(site)
            assertNotNull(snapshot.configuration)
            manager.restore(site, dev.pocketpaas.domain.network.SiteProxySnapshot(null))
            assertNull(files.readTextOrNull(builder.hostPath(layout, site)))
            assertNull(sites.getSite(1)?.publicUrl)
            manager.restore(site, snapshot)
            assertEquals(snapshot.configuration, files.readTextOrNull(builder.hostPath(layout, site)))
            assertEquals("$PUBLIC_URL/sites/example.com/", sites.getSite(1)?.publicUrl)
            assertEquals(2, processes.reloads.get())
        }
    }

    @Test
    fun failedRemovalValidationPreservesLiveHostAndItsPublishedUrl() = runBlocking {
        fixture {
            val site = sites.getSite(1)!!
            manager.startAll()
            val snapshot = manager.snapshot(site)
            processes.failNextConfigTest = true
            expectFailure { manager.restore(site, dev.pocketpaas.domain.network.SiteProxySnapshot(null)) }
            assertEquals(snapshot.configuration, files.readTextOrNull(builder.hostPath(layout, site)))
            assertEquals("$PUBLIC_URL/sites/example.com/", sites.getSite(1)?.publicUrl)
            assertEquals(0, processes.reloads.get())
        }
    }

    @Test
    fun startAndStopAreIdempotentAndPersistOnlyConfiguredSiteUrls() = runBlocking {
        fixture {
            manager.startAll()
            manager.startAll()
            assertEquals(1, processes.nginxStarts.get())
            assertEquals(1, processes.tunnelStarts.get())
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.tunnelStatus)
            assertEquals(PUBLIC_URL, serverState.publicUrl.value)
            assertEquals("$PUBLIC_URL/sites/example.com/", sites.getSite(1)?.publicUrl)
            assertNull(sites.getSite(2)?.publicUrl)
            manager.stopAll()
            manager.stopAll()
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
            assertEquals(1, processes.tunnel!!.stopCalls.get())
            assertNull(serverState.publicUrl.value)
            assertNull(sites.getSite(1)?.publicUrl)
        }
    }

    @Test
    fun namedTunnelPublishesTheCustomHostnameAtTheSiteRoot() = runBlocking {
        fixture {
            cloudflare.configure("api-token", ACCOUNT_ID, "example.com")
            val site = sites.getSite(1)!!.copy(customHostname = "www.example.com")
            sites.updateCustomHostname(site.id, site.customHostname)
            manager.generateVirtualHost(site, "$ROOT/htdocs/${site.domainName}")

            manager.startAll()

            assertNotNull(files.readTextOrNull(builder.customHostPath(layout, site)))
            assertEquals("eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=", files.readTextOrNull("${layout.runtimePath}/cloudflared-named.token"))
            assertEquals(TunnelMode.NAMED, manager.state.value.tunnelMode)
            assertEquals("https://www.example.com", manager.state.value.publicUrl)
            assertEquals("https://www.example.com/", sites.getSite(1)?.publicUrl)
            assertTrue(processes.lastTunnelArguments.contains("run"))
            val tokenFileIndex = processes.lastTunnelArguments.indexOf("--token-file")
            assertTrue(tokenFileIndex >= 0)
            assertEquals("${layout.runtimePath}/cloudflared-named.token", processes.lastTunnelArguments[tokenFileIndex + 1])
            assertFalse(processes.lastTunnelArguments.contains("--config"))
            assertFalse(processes.lastTunnelArguments.contains("--url"))
            assertFalse(processes.lastTunnelArguments.any { it.contains("eHh4eHh4") })
            assertEquals(0, hostnameProbeCount.get())
        }
    }

    @Test
    fun stoppingNamedTunnelDeletesTheTemporaryRunToken() = runBlocking {
        fixture {
            cloudflare.configure("api-token", ACCOUNT_ID, "example.com")
            val site = sites.getSite(1)!!.copy(customHostname = "www.example.com")
            sites.updateCustomHostname(site.id, site.customHostname)
            manager.generateVirtualHost(site, "$ROOT/htdocs/${site.domainName}")

            manager.startAll()
            assertNotNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.token"))

            manager.stopAll()

            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.token"))
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
        }
    }

    @Test
    fun failedNamedTunnelProcessStartRemovesTemporaryCredentials() = runBlocking {
        fixture {
            cloudflare.configure("api-token", ACCOUNT_ID, "example.com")
            val site = sites.getSite(1)!!.copy(customHostname = "www.example.com")
            sites.updateCustomHostname(site.id, site.customHostname)
            manager.generateVirtualHost(site, "$ROOT/htdocs/${site.domainName}")
            processes.failNextTunnelStart = true

            expectFailure { manager.startAll() }

            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.token"))
            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.yml"))
            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.json"))
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.tunnelStatus)
        }
    }

    @Test
    fun quickTunnelStartupRemovesNamedCredentialsLeftByAnEarlierProcess() = runBlocking {
        fixture {
            files.writeTextAtomic("${layout.runtimePath}/cloudflared-named.token", "stale token")
            files.writeTextAtomic("${layout.runtimePath}/cloudflared-named.json", "stale legacy credentials")
            files.writeTextAtomic("${layout.runtimePath}/cloudflared-named.yml", "stale configuration")

            manager.startAll()

            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.token"))
            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.json"))
            assertNull(files.readTextOrNull("${layout.runtimePath}/cloudflared-named.yml"))
            assertEquals(TunnelMode.QUICK, manager.state.value.tunnelMode)
        }
    }

    @Test
    fun liveQuickTunnelSwitchesToNamedAndReturnsToQuickWhenLastHostnameIsRemoved() = runBlocking {
        fixture {
            manager.startAll()
            val quick = processes.tunnel!!
            cloudflare.configure("api-token", ACCOUNT_ID, "example.com")

            manager.setCustomHostname(1, "www.example.com")

            val named = processes.tunnel!!
            assertFalse(quick.isAlive)
            assertTrue(named.isAlive)
            assertEquals(TunnelMode.NAMED, manager.state.value.tunnelMode)
            assertEquals("https://www.example.com/", sites.getSite(1)?.publicUrl)
            assertNotNull(files.readTextOrNull(builder.customHostPath(layout, sites.getSite(1)!!)))

            manager.setCustomHostname(1, null)

            assertFalse(named.isAlive)
            assertEquals(TunnelMode.QUICK, manager.state.value.tunnelMode)
            assertEquals("$PUBLIC_URL/sites/example.com/", sites.getSite(1)?.publicUrl)
            assertNull(files.readTextOrNull(builder.customHostPath(layout, sites.getSite(1)!!)))
            assertEquals(3, processes.tunnelStarts.get())
        }
    }

    @Test
    fun customHostnameMustBelongToConfiguredZoneBeforePersistence() = runBlocking {
        fixture {
            manager.startAll()
            cloudflare.configure("api-token", ACCOUNT_ID, "example.com")
            val failure = expectFailure { manager.setCustomHostname(1, "outside.test") }
            assertTrue(failure.message.orEmpty().contains("configured Cloudflare zone"))
            assertNull(sites.getSite(1)?.customHostname)
            assertNull(files.readTextOrNull(builder.customHostPath(layout, sites.getSite(1)!!)))
        }
    }

    @Test
    fun announcementBeforeMetricsReadinessCannotPublishPublicUrl() = runBlocking {
        fixture {
            metricsReady = false
            val startup = async { manager.startAll() }
            eventually { processes.tunnel != null }
            delay(75)
            assertEquals(NetworkServiceStatus.STARTING, manager.state.value.tunnelStatus)
            assertNull(manager.state.value.publicUrl)
            assertNull(serverState.publicUrl.value)
            assertFalse(serverState.history.any { it != null })
            metricsReady = true
            startup.await()
            assertEquals(PUBLIC_URL, serverState.publicUrl.value)
            assertTrue(metricsProbeCount.get() > 0)
            assertTrue(hostnameProbeCount.get() > 0)
        }
    }

    @Test
    fun readinessFromAnotherTunnelCannotPublishAssignedLogUrl() = runBlocking {
        fixture {
            metricsHostname = "another-host.trycloudflare.com"
            val startup = async { manager.startAll() }
            eventually { hostnameProbeCount.get() > 0 }
            assertNull(serverState.publicUrl.value)
            assertEquals(NetworkServiceStatus.STARTING, manager.state.value.tunnelStatus)
            metricsHostname = PUBLIC_HOST
            startup.await()
            assertEquals(PUBLIC_URL, manager.state.value.publicUrl)
        }
    }

    @Test
    fun splitLogChunksWaitForUrlDelimiterThenPassHttpReadiness() = runBlocking {
        fixture {
            processes.automaticTunnelBanner = false
            val startup = async { manager.startAll() }
            eventually { processes.tunnel != null }
            val process = processes.tunnel!!
            process.emit(ProcessStream.STDERR, "Your quick Tunnel has been created\nhttps://blue-")
            process.emit(ProcessStream.STDERR, "bird.trycloudflare.com")
            delay(50)
            assertNull(serverState.publicUrl.value)
            assertEquals(NetworkServiceStatus.STARTING, manager.state.value.tunnelStatus)
            process.emit(ProcessStream.STDERR, " |\n")
            startup.await()
            assertEquals(PUBLIC_URL, serverState.publicUrl.value)
        }
    }

    @Test
    fun unexpectedNginxExitStopsTunnelAndClearsDatabaseUrls() = runBlocking {
        fixture {
            manager.startAll()
            val runningTunnel = processes.tunnel!!
            processes.nginx!!.exit(7, "worker initialization failed")
            eventually { manager.state.value.nginxStatus == NetworkServiceStatus.FAILED }
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
            assertFalse(runningTunnel.isAlive)
            assertNull(serverState.publicUrl.value)
            assertNull(sites.getSite(1)?.publicUrl)
            assertTrue(manager.state.value.lastError.orEmpty().contains("exit 7"))
        }
    }

    @Test
    fun unexpectedTunnelExitPreservesNginxAndClearsPublicUrls() = runBlocking {
        fixture {
            manager.startAll()
            processes.tunnel!!.exit(4, "connection failed")
            eventually { manager.state.value.tunnelStatus == NetworkServiceStatus.FAILED }
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertTrue(processes.nginx!!.isAlive)
            assertNull(manager.state.value.publicUrl)
            assertNull(serverState.publicUrl.value)
            assertNull(sites.getSite(1)?.publicUrl)
        }
    }

    @Test
    fun edgeDisconnectionClearsUrlsAndReconnectionRestoresSameLiveTunnel() = runBlocking {
        fixture {
            manager.startAll()
            val process = processes.tunnel!!
            metricsReady = false
            eventually { manager.state.value.tunnelStatus == NetworkServiceStatus.STARTING }
            assertTrue(process.isAlive)
            assertNull(serverState.publicUrl.value)
            assertNull(sites.getSite(1)?.publicUrl)
            metricsReady = true
            eventually { manager.state.value.tunnelStatus == NetworkServiceStatus.RUNNING }
            assertEquals(process.processId, manager.state.value.tunnelProcessId)
            assertEquals(PUBLIC_URL, serverState.publicUrl.value)
            assertEquals("$PUBLIC_URL/sites/example.com/", sites.getSite(1)?.publicUrl)
            assertEquals(1, processes.tunnelStarts.get())
        }
    }

    @Test
    fun invalidCandidateNeverReplacesActiveHostOrMaster() = runBlocking {
        fixture {
            manager.startAll()
            val before = files.snapshot()
            val reloads = processes.reloads.get()
            processes.failNextConfigTest = true
            val failure = expectFailure {
                manager.generateVirtualHost(sites.getSite(1)!!.copy(projectType = ProjectType.NODEJS), "$ROOT/htdocs/example.com")
            }
            assertTrue(failure.message.orEmpty().contains("Nginx command failed"))
            assertEquals(before, files.snapshot())
            assertEquals(reloads, processes.reloads.get())
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertEquals(PUBLIC_URL, manager.state.value.publicUrl)
        }
    }

    @Test
    fun unregisteredSiteCannotOverwriteAnExistingVirtualHost() = runBlocking {
        fixture {
            val before = files.snapshot()
            val unregistered = sites.getSite(1)!!.copy(id = 999)
            val failure = expectFailure { manager.generateVirtualHost(unregistered, "$ROOT/htdocs/example.com") }
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, files.snapshot())
            assertEquals(0, processes.reloads.get())
        }
    }

    @Test
    fun reloadSignalWithoutNewRevisionRollsBackLastGoodConfiguration() = runBlocking {
        fixture {
            manager.startAll()
            val before = files.snapshot()
            val markerBefore = healthMarker.get()
            processes.ignoreNextReload = true
            expectFailure {
                manager.generateVirtualHost(sites.getSite(1)!!.copy(projectType = ProjectType.NODEJS), "$ROOT/htdocs/example.com")
            }
            assertEquals(before, files.snapshot())
            assertEquals(markerBefore, healthMarker.get())
            assertEquals(2, processes.reloads.get())
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.tunnelStatus)
            assertEquals(PUBLIC_URL, serverState.publicUrl.value)
        }
    }

    @Test
    fun uncertainFileWriteResponseStillRestoresPreviousActiveHost() = runBlocking {
        fixture {
            manager.startAll()
            val before = files.snapshot()
            files.failAfterWritingPath = builder.hostPath(layout, sites.getSite(1)!!)
            val failure = expectFailure {
                manager.generateVirtualHost(sites.getSite(1)!!.copy(projectType = ProjectType.NODEJS), "$ROOT/htdocs/example.com")
            }
            assertTrue(failure.message.orEmpty().contains("Simulated uncertain atomic write"))
            assertEquals(before, files.snapshot())
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.tunnelStatus)
        }
    }

    @Test
    fun acceptedNewRevisionReplacesHostAndPublishesItsSiteUrl() = runBlocking {
        fixture {
            manager.startAll()
            val revision = healthMarker.get()
            val newSite = sites.getSite(2)!!
            manager.generateVirtualHost(newSite, "$ROOT/htdocs/second.example")
            assertTrue(revision != healthMarker.get())
            assertNotNull(files.readTextOrNull(builder.hostPath(layout, newSite)))
            assertEquals("$PUBLIC_URL/sites/second.example/", sites.getSite(2)?.publicUrl)
            assertEquals(1, processes.reloads.get())
            assertFalse(files.snapshot().keys.any { it.endsWith(".candidate") })
        }
    }

    @Test
    fun cancelledTunnelStartupStopsChildAndLeavesNginxOwned() = runBlocking {
        fixture {
            metricsReady = false
            val startup = async { manager.startAll() }
            eventually { processes.tunnel != null && metricsProbeCount.get() > 0 }
            val process = processes.tunnel!!
            startup.cancelAndJoin()
            assertFalse(process.isAlive)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertNull(serverState.publicUrl.value)
            assertNull(manager.state.value.publicUrl)
        }
    }

    @Test
    fun cancelledNginxStartupStopsChildAndCleansCandidate() = runBlocking {
        fixture {
            nginxReady = false
            val startup = async { manager.startNginx() }
            eventually { processes.nginx != null }
            val process = processes.nginx!!
            startup.cancelAndJoin()
            assertFalse(process.isAlive)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.nginxStatus)
            assertNull(manager.state.value.nginxProcessId)
            assertFalse(files.snapshot().keys.any { it.endsWith(".candidate") })
        }
    }

    @Test
    fun failedCancellationCleanupRetainsOwnedChildForExplicitStopRetry() = runBlocking {
        fixture {
            metricsReady = false
            val startup = async { manager.startAll() }
            eventually { processes.tunnel != null && metricsProbeCount.get() > 0 }
            val process = processes.tunnel!!
            process.failNextStop = true
            startup.cancelAndJoin()
            assertTrue(process.isAlive)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.tunnelStatus)
            assertEquals(process.processId, manager.state.value.tunnelProcessId)
            assertTrue(manager.state.value.lastError.orEmpty().contains("Simulated stop failure"))
            assertNull(serverState.publicUrl.value)
            manager.stopCloudflareTunnel()
            assertFalse(process.isAlive)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
            assertNull(manager.state.value.tunnelProcessId)
        }
    }

    @Test
    fun nginxReadinessDeadlineIsAFailureInsteadOfAUserCancellation() = runBlocking {
        fixture(startupTimeoutMillis = 100) {
            nginxReady = false

            val failure = expectFailure { manager.startNginx() }

            assertTrue(failure is ProcessIOException)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.nginxStatus)
            assertTrue(manager.state.value.lastError.orEmpty().contains("100 ms"))
            assertTrue(manager.state.value.lastError.orEmpty().contains("127.0.0.1:${config.nginxPort}"))
            assertFalse(processes.nginx!!.isAlive)
            assertNull(manager.state.value.nginxProcessId)
            assertFalse(files.snapshot().keys.any { it.endsWith(".candidate") })
        }
    }

    @Test
    fun missingTunnelAnnouncementHasAnActionableFailureAndKeepsNginxRunning() = runBlocking {
        fixture(tunnelStartupTimeoutMillis = 120) {
            processes.automaticTunnelBanner = false

            val failure = expectFailure { manager.startAll() }

            assertTrue(failure is ProcessIOException)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.tunnelStatus)
            assertTrue(manager.state.value.lastError.orEmpty().contains("Quick Tunnel URL"))
            assertTrue(manager.state.value.lastError.orEmpty().contains("120 ms"))
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertTrue(processes.nginx!!.isAlive)
            assertFalse(processes.tunnel!!.isAlive)
            assertNull(manager.state.value.tunnelProcessId)
            assertNull(manager.state.value.publicUrl)
            assertNull(serverState.publicUrl.value)
        }
    }

    @Test
    fun edgeReadinessDeadlineIsDistinctFromAnUnreportedTunnelAddress() = runBlocking {
        fixture(tunnelStartupTimeoutMillis = 120) {
            metricsReady = false

            val failure = expectFailure { manager.startAll() }

            assertTrue(failure is ProcessIOException)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.tunnelStatus)
            assertTrue(manager.state.value.lastError.orEmpty().contains("Cloudflare edge connection"))
            assertTrue(manager.state.value.lastError.orEmpty().contains("120 ms"))
            assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
            assertFalse(processes.tunnel!!.isAlive)
            assertNull(manager.state.value.tunnelProcessId)
            assertNull(serverState.publicUrl.value)
        }
    }

    @Test
    fun anOuterDeadlineStillCancelsStartupWithoutReportingAnInternalFailure() = runBlocking {
        fixture(startupTimeoutMillis = 2_000) {
            nginxReady = false

            val failure = expectFailure { withTimeout(250) { manager.startNginx() } }

            assertTrue(failure is TimeoutCancellationException)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.nginxStatus)
            assertNull(manager.state.value.lastError)
            assertFalse(processes.nginx!!.isAlive)
            assertNull(manager.state.value.nginxProcessId)
        }
    }

    @Test
    fun failedStreamSupervisionRetainsOwnerUntilAStopRetryAcknowledgesTermination() = runBlocking {
        fixture {
            manager.startAll()
            val process = processes.tunnel!!
            process.failSupervision(IOException("Reading STDERR failed"))
            eventually { manager.state.value.tunnelStatus == NetworkServiceStatus.FAILED }
            assertTrue(process.isAlive)
            assertEquals(process.processId, manager.state.value.tunnelProcessId)
            process.failNextStop = true

            expectFailure { manager.stopAll() }

            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.FAILED, manager.state.value.tunnelStatus)
            assertTrue(process.isAlive)
            assertEquals(process.processId, manager.state.value.tunnelProcessId)
            assertNull(manager.state.value.publicUrl)
            assertNull(serverState.publicUrl.value)

            manager.stopAll()

            assertFalse(process.isAlive)
            assertEquals(2, process.stopCalls.get())
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.nginxStatus)
            assertEquals(NetworkServiceStatus.STOPPED, manager.state.value.tunnelStatus)
            assertNull(manager.state.value.nginxProcessId)
            assertNull(manager.state.value.tunnelProcessId)
            assertNull(serverState.publicUrl.value)
        }
    }

    @Test
    fun rapidStopAndRestartCannotLetOldWatcherOverrideCurrentSession() = runBlocking {
        fixture {
            repeat(3) {
                manager.startAll()
                val oldNginx = processes.nginx!!
                val oldTunnel = processes.tunnel!!
                manager.stopAll()
                manager.startAll()
                oldNginx.exit(9, "late old exit")
                oldTunnel.exit(9, "late old exit")
                delay(40)
                assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.nginxStatus)
                assertEquals(NetworkServiceStatus.RUNNING, manager.state.value.tunnelStatus)
                assertTrue(manager.state.value.nginxProcessId != oldNginx.processId)
                assertTrue(manager.state.value.tunnelProcessId != oldTunnel.processId)
                assertEquals(PUBLIC_URL, manager.state.value.publicUrl)
            }
        }
    }

    private suspend fun fixture(
        startupTimeoutMillis: Long = 450,
        tunnelStartupTimeoutMillis: Long = 3_000,
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(startupTimeoutMillis, tunnelStartupTimeoutMillis)
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(3_000) {
            while (!predicate()) delay(5)
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable =
        checkNotNull(runCatching { block() }.exceptionOrNull()) { "Expected operation to fail" }

    private class Fixture(startupTimeoutMillis: Long, tunnelStartupTimeoutMillis: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = MemoryFileStore()
        val sites = MemorySiteRepository()
        val serverState = MemoryServerStateRepository()
        val layout = RuntimeLayout(
            rootPath = ROOT,
            projectsPath = "$ROOT/htdocs",
            nginxConfigPath = "$ROOT/nginx/nginx.conf",
            nginxHostsPath = "$ROOT/nginx/conf.d",
            runtimePath = "$ROOT/runtime",
            logsPath = "$ROOT/logs",
        )
        val healthMarker = AtomicReference<String?>(null)
        val metricsProbeCount = AtomicInteger()
        val hostnameProbeCount = AtomicInteger()
        @Volatile var nginxReady = true
        @Volatile var metricsReady = true
        @Volatile var metricsHostname = PUBLIC_HOST
        private val nginxServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val metricsServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val config = ServerConfig(nginxPort = nginxServer.address.port)
        val policy = NetworkPolicy(
            startupTimeoutMillis = startupTimeoutMillis,
            tunnelStartupTimeoutMillis = tunnelStartupTimeoutMillis,
            readinessPollMillis = 10,
            readinessConnectTimeoutMillis = 100,
            configTestTimeoutMillis = 1_000,
            tunnelMetricsPort = metricsServer.address.port,
        )
        val builder = NginxConfigBuilder(config)
        val processes = FakeProcessExecutor(layout, files, healthMarker)
        val cloudflare = FakeCloudflareTunnelProvider()
        val workspace = MemoryDeploymentWorkspace(layout)
        val manager = NetworkManager(processes, files, builder, serverState, sites, config, policy, cloudflare, workspace, scope)

        init {
            files.contents[builder.hostPath(layout, sites.sites.value.first())] = builder.renderVirtualHost(
                sites.sites.value.first(), "$ROOT/htdocs/example.com", layout,
            )
            nginxServer.createContext("/_pocketpaas/health") { exchange ->
                val body = healthMarker.get()
                respond(exchange, if (nginxReady && processes.nginx?.isAlive == true && body != null) 200 else 503, body.orEmpty())
            }
            metricsServer.createContext("/ready") { exchange ->
                metricsProbeCount.incrementAndGet()
                val ready = metricsReady && processes.tunnel?.isAlive == true
                respond(exchange, if (ready) 200 else 503, if (ready) "ready" else "not ready")
            }
            metricsServer.createContext("/quicktunnel") { exchange ->
                hostnameProbeCount.incrementAndGet()
                respond(exchange, 200, "{\"hostname\":\"$metricsHostname\"}")
            }
            nginxServer.start()
            metricsServer.start()
        }

        suspend fun close() {
            try {
                manager.stopAll()
            } finally {
                scope.cancel()
                nginxServer.stop(0)
                metricsServer.stop(0)
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            exchange.use {
                val bytes = body.toByteArray(Charsets.UTF_8)
                it.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.use { stream -> stream.write(bytes) }
            }
        }
    }

    private class FakeProcessExecutor(
        private val layout: RuntimeLayout,
        private val files: MemoryFileStore,
        private val healthMarker: AtomicReference<String?>,
    ) : ProcessExecutor {
        val nginxStarts = AtomicInteger()
        val tunnelStarts = AtomicInteger()
        val reloads = AtomicInteger()
        private val ids = AtomicInteger(1_000)
        @Volatile var failNextConfigTest = false
        @Volatile var ignoreNextReload = false
        @Volatile var automaticTunnelBanner = true
        @Volatile var failNextTunnelStart = false
        @Volatile var lastTunnelArguments: List<String> = emptyList()
        @Volatile var nginx: FakeManagedProcess? = null
        @Volatile var tunnel: FakeManagedProcess? = null

        override suspend fun execute(request: ProcessRequest, timeoutMillis: Long): ProcessResult {
            require(timeoutMillis > 0)
            val args = (request.command as ProcessCommand.Arguments).values
            require(args.first() == "/runtime/bin/nginx")
            if ("-t" in args && failNextConfigTest) {
                failNextConfigTest = false
                return result(1, ProcessTermination.EXITED, "configuration test failed")
            }
            val signalIndex = args.indexOf("-s")
            if (signalIndex >= 0) {
                when (args[signalIndex + 1]) {
                    "quit" -> nginx?.exit(0)
                    "reload" -> {
                        reloads.incrementAndGet()
                        if (ignoreNextReload) ignoreNextReload = false else updateHealthMarker(layout.nginxConfigPath)
                    }
                    else -> error("Unexpected signal")
                }
            }
            return result(0, ProcessTermination.EXITED)
        }

        override suspend fun executeCommand(command: String, envVars: Map<String, String>, workingDir: File): ProcessResult =
            execute(ProcessRequest(ProcessCommand.Arguments(listOf(command)), envVars, workingDir), 1_000)

        override suspend fun startProcess(request: ProcessRequest): ManagedProcess =
            startProcess((request.command as ProcessCommand.Arguments).values, request.environment, request.workingDir)

        override suspend fun startProcess(arguments: List<String>, envVars: Map<String, String>, workingDir: File): ManagedProcess {
            require(envVars.isEmpty())
            require(workingDir.path.replace('\\', '/').endsWith(ROOT))
            return when (arguments.first()) {
                "/runtime/bin/nginx" -> FakeManagedProcess(ids.incrementAndGet()).also {
                    nginxStarts.incrementAndGet()
                    updateHealthMarker(arguments[arguments.indexOf("-c") + 1])
                    nginx = it
                }
                "/runtime/bin/cloudflared" -> {
                    if (failNextTunnelStart) {
                        failNextTunnelStart = false
                        throw IOException("Simulated cloudflared process start failure")
                    }
                    FakeManagedProcess(ids.incrementAndGet()).also {
                        lastTunnelArguments = arguments
                        tunnelStarts.incrementAndGet()
                        tunnel = it
                        if (automaticTunnelBanner) it.emit(ProcessStream.STDERR, "Your quick Tunnel has been created\n$PUBLIC_URL |\n")
                    }
                }
                else -> error("Unexpected executable")
            }
        }

        override suspend fun resolveBinary(name: String): String = "/runtime/bin/$name"
        override suspend fun runtimeLayout(): RuntimeLayout = layout

        private fun updateHealthMarker(path: String) {
            val text = checkNotNull(files.contents[path]) { "No master configuration" }
            val revision = checkNotNull(Regex("return 200 \"([a-zA-Z0-9_-]+)\";").find(text)).groupValues[1]
            healthMarker.set(revision)
        }
    }

    private class FakeManagedProcess(override val processId: Int) : ManagedProcess {
        override val sessionId: String = UUID.randomUUID().toString()
        private val sequence = AtomicLong()
        private val completion = CompletableDeferred<ProcessResult>()
        private val mutableLogs = MutableSharedFlow<ProcessOutput>(replay = 128, extraBufferCapacity = 128)
        override val logs: SharedFlow<ProcessOutput> = mutableLogs
        val stopCalls = AtomicInteger()
        @Volatile var failNextStop = false
        @Volatile private var physicallyAlive = true
        @Volatile private var supervisionFailed = false
        val isAlive: Boolean get() = physicallyAlive

        override suspend fun await(): ProcessResult = completion.await()
        override suspend fun stop(): ProcessResult {
            stopCalls.incrementAndGet()
            if (failNextStop) {
                failNextStop = false
                throw IOException("Simulated stop failure")
            }
            physicallyAlive = false
            val acknowledged = result(0, ProcessTermination.STOPPED).copy(processId = processId)
            completion.complete(acknowledged)
            return if (supervisionFailed) acknowledged else completion.await()
        }

        fun exit(code: Int, stderr: String = "") {
            physicallyAlive = false
            completion.complete(result(code, ProcessTermination.EXITED, stderr).copy(processId = processId))
        }

        fun failSupervision(failure: IOException) {
            supervisionFailed = true
            completion.completeExceptionally(failure)
        }

        fun emit(stream: ProcessStream, text: String) {
            check(mutableLogs.tryEmit(ProcessOutput(sessionId, stream, text, sequence.incrementAndGet(), System.currentTimeMillis())))
        }
    }

    private class FakeCloudflareTunnelProvider : CloudflareTunnelProvider {
        private val mutableConnection = MutableStateFlow(CloudflareConnectionState())
        override val connectionState: StateFlow<CloudflareConnectionState> = mutableConnection
        val reconciledHostnames = CopyOnWriteArrayList<Set<String>>()

        override suspend fun configure(apiToken: String, accountId: String, zoneName: String): CloudflareConnectionState {
            require(apiToken.isNotBlank())
            return CloudflareConnectionState(true, accountId, zoneName).also { mutableConnection.value = it }
        }

        override suspend fun disconnect() {
            mutableConnection.value = CloudflareConnectionState()
        }

        override suspend fun prepare(sites: List<Site>, origin: String): NamedTunnelCredentials {
            check(sites.any { it.customHostname != null })
            check(origin.startsWith("http://127.0.0.1:"))
            val accountId = checkNotNull(mutableConnection.value.accountId)
            return NamedTunnelCredentials(
                accountId = accountId,
                tunnelId = TUNNEL_ID,
                tunnelName = "pocketpaas-abcdef123456",
                tunnelToken = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
            )
        }

        override suspend fun reconcile(sites: List<Site>, origin: String) {
            check(origin.startsWith("http://127.0.0.1:"))
            reconciledHostnames += sites.mapNotNull { it.customHostname }.toSet()
        }
    }

    private class MemoryDeploymentWorkspace(private val layout: RuntimeLayout) : DeploymentWorkspace {
        override suspend fun prepare(site: Site, archivePath: String, deploymentId: String): PreparedProject = error("Not used")
        override suspend fun activate(site: Site, prepared: PreparedProject) = Unit
        override suspend fun rollback(site: Site, prepared: PreparedProject) = Unit
        override suspend fun abort(site: Site, deploymentId: String) = Unit
        override suspend fun complete(site: Site, prepared: PreparedProject) = Unit
        override suspend fun inspect(site: Site): ProjectMetadata {
            val entry = when (site.projectType) {
                ProjectType.STATIC -> ProjectEntryPoint.STATIC
                ProjectType.REACT -> ProjectEntryPoint.REACT
                ProjectType.NODEJS -> ProjectEntryPoint.NODEJS
                ProjectType.DJANGO -> ProjectEntryPoint.DJANGO
            }
            val root = "${layout.projectsPath}/${site.domainName}"
            return ProjectMetadata(if (site.projectType == ProjectType.REACT) "$root/dist" else root, entry, true, true)
        }
    }

    private class MemoryFileStore : RuntimeFileStore {
        val contents = ConcurrentHashMap<String, String>()
        private val directories = ConcurrentHashMap.newKeySet<String>()
        @Volatile var failAfterWritingPath: String? = null
        override suspend fun writeTextAtomic(path: String, text: String) {
            contents[path] = text
            if (failAfterWritingPath == path) {
                failAfterWritingPath = null
                throw IOException("Simulated uncertain atomic write")
            }
        }
        override suspend fun readTextOrNull(path: String): String? = contents[path]
        override suspend fun delete(path: String) { contents.remove(path) }
        override suspend fun ensureDirectory(path: String) { directories.add(path) }
        override suspend fun listFiles(directory: String): List<String> = contents.keys.filter {
            it.substringBeforeLast('/') == directory
        }.sorted()
        fun snapshot(): Map<String, String> = contents.toMap()
    }

    private class MemoryServerStateRepository : ServerStateRepository {
        override val publicUrl = MutableStateFlow<String?>(null)
        val history = CopyOnWriteArrayList<String?>()
        override suspend fun updatePublicUrl(url: String?) {
            history.add(url)
            publicUrl.value = url
        }
    }

    private class MemorySiteRepository : SiteRepository {
        private val values = MutableStateFlow(listOf(initialSite(1, "example.com"), initialSite(2, "second.example")))
        override val sites: StateFlow<List<Site>> = values
        private val collection = MutableStateFlow<SiteCollectionState>(SiteCollectionState.Ready(values.value))
        override val state: StateFlow<SiteCollectionState> = collection
        override fun observeSite(id: Long): Flow<Site?> = values.map { list -> list.find { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = values.map { list -> list.filter { it.status == status } }
        override suspend fun listSites(): List<Site> = values.value
        override suspend fun getSite(id: Long): Site? = values.value.find { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site {
            val id = (values.value.maxOfOrNull { it.id } ?: 0) + 1
            val site = initialSite(id, domainName).copy(projectType = projectType)
            replace(values.value + site)
            return site
        }
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
            update(id) { it.copy(status = status, processId = processId) }
        }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean {
            if (getSite(id)?.status != expectedStatus) return false
            updateRuntimeState(id, newStatus, processId)
            return true
        }
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { update(id) { it.copy(publicUrl = publicUrl) } }
        override suspend fun updateCustomHostname(id: Long, customHostname: String?) {
            update(id) { it.copy(customHostname = customHostname) }
        }
        override suspend fun appendLogs(id: Long, text: String) { update(id) { it.copy(logs = it.logs + text) } }
        override suspend fun clearLogs(id: Long) { update(id) { it.copy(logs = "") } }
        override suspend fun deleteSiteIfInactive(id: Long) {
            val site = checkNotNull(getSite(id))
            require(site.status == SiteStatus.STOPPED && site.processId == null)
            replace(values.value.filterNot { it.id == id })
        }
        private fun update(id: Long, change: (Site) -> Site) {
            check(values.value.any { it.id == id })
            replace(values.value.map { if (it.id == id) change(it) else it })
        }
        private fun replace(sites: List<Site>) {
            values.value = sites
            collection.value = SiteCollectionState.Ready(sites)
        }
    }

    private companion object {
        const val ACCOUNT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TUNNEL_ID = "12345678-1234-1234-1234-123456789abc"
        const val ROOT = "/termux/home/.pocket-paas"
        const val PUBLIC_HOST = "blue-bird.trycloudflare.com"
        const val PUBLIC_URL = "https://$PUBLIC_HOST"

        fun initialSite(id: Long, domainName: String) = Site(
            id = id,
            domainName = domainName,
            internalPort = 18000 + id.toInt(),
            projectType = ProjectType.STATIC,
            processId = null,
            publicUrl = null,
            status = SiteStatus.STOPPED,
            logs = "",
            createdAt = 1,
        )

        fun result(exit: Int, termination: ProcessTermination, stderr: String = "") = ProcessResult(
            processId = null,
            exitCode = exit,
            stdout = "",
            stderr = stderr,
            durationMillis = 1,
            termination = termination,
            stdoutTruncated = false,
            stderrTruncated = false,
        )
    }
}
