package dev.pocketpaas.data.network

import com.google.gson.JsonParser
import dev.pocketpaas.core.config.NetworkPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.data.local.SiteEntity
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.TunnelMode
import dev.pocketpaas.domain.network.SiteProxy
import dev.pocketpaas.domain.network.ManagedNetwork
import dev.pocketpaas.domain.dashboard.DashboardNetworkState
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.network.CloudflareSitePublisher
import dev.pocketpaas.domain.network.CloudflareTunnelProvider
import dev.pocketpaas.domain.network.SiteProxySnapshot
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessIOException
import dev.pocketpaas.domain.process.ProcessOutput
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.repository.ServerStateRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.system.RuntimeFileStore
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes configuration changes and owns only processes launched in this application session. */
@Singleton
class NetworkManager @Inject constructor(
    private val shell: ProcessExecutor,
    private val files: RuntimeFileStore,
    private val configurations: NginxConfigBuilder,
    private val serverState: ServerStateRepository,
    private val sites: SiteRepository,
    private val config: ServerConfig,
    private val policy: NetworkPolicy,
    private val cloudflare: CloudflareTunnelProvider,
    private val deploymentWorkspace: DeploymentWorkspace,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : SiteProxy, ManagedNetwork, DashboardNetworkState, CloudflareSitePublisher {
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow(NetworkState())
    override val state: StateFlow<NetworkState> = mutableState.asStateFlow()
    override val cloudflareConnection: StateFlow<dev.pocketpaas.domain.network.CloudflareConnectionState>
        get() = cloudflare.connectionState
    private val mutableLogs = MutableSharedFlow<ProcessOutput>(
        replay = 128,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val logs: SharedFlow<ProcessOutput> = mutableLogs.asSharedFlow()
    private var nginx: ActiveProcess? = null
    private var tunnel: ActiveProcess? = null
    private var activeRevision: String? = null

    init {
        require(policy.tunnelMetricsPort != config.apiPort && policy.tunnelMetricsPort != config.nginxPort) {
            "Cloudflare metrics cannot share the API or Nginx port."
        }
    }

    suspend fun startNginx() = lifecycle.withLock { startNginxLocked() }

    override suspend fun ensureServerRunning() = startNginx()

    suspend fun startCloudflareTunnel() = lifecycle.withLock {
        startNginxLocked()
        startTunnelLocked()
    }

    override suspend fun startAll() = lifecycle.withLock {
        startNginxLocked()
        startTunnelLocked()
    }

    suspend fun stopCloudflareTunnel() = lifecycle.withLock { stopTunnelLocked() }

    override suspend fun configureCloudflare(apiToken: String, accountId: String, zoneName: String) {
        lifecycle.withLock { cloudflare.configure(apiToken, accountId, zoneName) }
    }

    override suspend fun disconnectCloudflare() = lifecycle.withLock {
        require(sites.listSites().none { it.customHostname != null }) {
            "Remove every site's custom hostname before disconnecting Cloudflare."
        }
        val shouldResumeQuickTunnel = tunnel != null && tunnel?.tunnelMode == TunnelMode.NAMED
        if (shouldResumeQuickTunnel) stopTunnelLocked()
        cloudflare.reconcile(emptyList(), nginxOrigin())
        cloudflare.disconnect()
        if (shouldResumeQuickTunnel && state.value.nginxStatus == NetworkServiceStatus.RUNNING) startTunnelLocked()
    }

    override suspend fun setCustomHostname(siteId: Long, hostname: String?) = lifecycle.withLock {
        setCustomHostnameLocked(siteId, hostname)
    }

    suspend fun stopNginx() = lifecycle.withLock { stopAllLocked() }

    override suspend fun stopAll() = lifecycle.withLock { stopAllLocked() }

    suspend fun generateVirtualHost(site: SiteEntity, rootDir: String) = generateVirtualHost(site.toDomain(), rootDir)

    override suspend fun configure(site: Site, rootDir: String) = generateVirtualHost(site, rootDir)

    override suspend fun snapshot(site: Site): SiteProxySnapshot = lifecycle.withLock {
        verifySiteIdentity(site)
        val layout = prepareLayout()
        SiteProxySnapshot(
            configuration = files.readTextOrNull(configurations.hostPath(layout, site)),
            customHostConfiguration = files.readTextOrNull(configurations.customHostPath(layout, site)),
        )
    }

    override suspend fun restore(site: Site, snapshot: SiteProxySnapshot) = lifecycle.withLock {
        verifySiteIdentity(site)
        replaceVirtualHost(site, prepareLayout(), snapshot.configuration, snapshot.customHostConfiguration)
    }

    /** Validates a staged master and host before replacing either active file. */
    suspend fun generateVirtualHost(site: Site, rootDir: String) = lifecycle.withLock {
        verifySiteIdentity(site)
        require(site.internalPort != policy.tunnelMetricsPort) { "Site port conflicts with Cloudflare metrics." }
        val layout = prepareLayout()
        val content = configurations.renderVirtualHost(site, rootDir, layout)
        val customContent = site.customHostname?.let { configurations.renderCustomVirtualHost(site, rootDir, layout) }
        replaceVirtualHost(site, layout, content, customContent)
    }

    private suspend fun setCustomHostnameLocked(siteId: Long, input: String?) {
        SiteInput.validateSiteId(siteId)
        val hostname = input?.trim()?.takeIf { it.isNotEmpty() }?.let(SiteInput::normalizeCustomHostname)
        val site = sites.getSite(siteId) ?: throw IllegalArgumentException("Site $siteId does not exist.")
        if (site.customHostname == hostname) return

        val allSites = sites.listSites()
        if (hostname != null) {
            val connection = cloudflare.connectionState.value
            require(connection.configured && connection.zoneName != null) {
                "Connect Cloudflare API settings before assigning a custom hostname."
            }
            require(SiteInput.isHostnameInZone(hostname, connection.zoneName)) {
                "$hostname must be inside the configured Cloudflare zone ${connection.zoneName}."
            }
            require(allSites.none { it.id != siteId && it.customHostname == hostname }) {
                "This custom hostname is already assigned to another site."
            }
        }

        val layout = prepareLayout()
        val rootDir = deploymentWorkspace.inspect(site).rootDir
        val networkWasRunning = tunnel?.let {
            !it.completion.isCompleted && state.value.tunnelStatus == NetworkServiceStatus.RUNNING
        } == true
        val updatedSite = site.copy(customHostname = hostname)
        sites.updateCustomHostname(siteId, hostname)
        try {
            val pathConfiguration = configurations.renderVirtualHost(updatedSite, rootDir, layout)
            val customConfiguration = hostname?.let { configurations.renderCustomVirtualHost(updatedSite, rootDir, layout) }
            replaceVirtualHost(updatedSite, layout, pathConfiguration, customConfiguration)
            cloudflare.reconcile(sites.listSites(), nginxOrigin())
            if (networkWasRunning) {
                startTunnelLocked()
                val currentTunnel = tunnel
                val mode = currentTunnel?.tunnelMode
                val publicAddress = when (mode) {
                    TunnelMode.NAMED -> namedPublicUrl(sites.listSites())
                    TunnelMode.QUICK -> currentTunnel?.assignedUrl
                    null -> null
                }
                if (mode != null && publicAddress != null) {
                    currentTunnel?.assignedUrl = publicAddress
                    publishPublicUrl(publicAddress, layout, mode)
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    sites.updateCustomHostname(siteId, site.customHostname)
                    val oldPathConfiguration = configurations.renderVirtualHost(site, rootDir, layout)
                    val oldCustomConfiguration = site.customHostname?.let {
                        configurations.renderCustomVirtualHost(site, rootDir, layout)
                    }
                    replaceVirtualHost(site, layout, oldPathConfiguration, oldCustomConfiguration)
                    cloudflare.reconcile(sites.listSites(), nginxOrigin())
                    if (networkWasRunning) {
                        if (nginx == null || state.value.nginxStatus != NetworkServiceStatus.RUNNING) startNginxLocked()
                        startTunnelLocked()
                        val currentTunnel = tunnel
                        val mode = currentTunnel?.tunnelMode
                        val address = when (mode) {
                            TunnelMode.NAMED -> namedPublicUrl(sites.listSites())
                            TunnelMode.QUICK -> currentTunnel?.assignedUrl
                            null -> null
                        }
                        if (mode != null && address != null) {
                            currentTunnel?.assignedUrl = address
                            publishPublicUrl(address, layout, mode)
                        }
                    }
                }.exceptionOrNull()?.let { rollbackFailure ->
                    suppress(failure, rollbackFailure)
                    runCatching { stopTunnelLocked() }.exceptionOrNull()?.let { suppress(failure, it) }
                    runCatching { clearPublicUrls() }.exceptionOrNull()?.let { suppress(failure, it) }
                    mutableState.value = state.value.copy(
                        nginxStatus = NetworkServiceStatus.FAILED,
                        tunnelStatus = if (tunnel == null) state.value.tunnelStatus else NetworkServiceStatus.FAILED,
                        tunnelProcessId = tunnel?.handle?.processId,
                        publicUrl = null,
                        lastError = "Custom hostname rollback failed: ${description(rollbackFailure)}",
                    )
                }
            }
            throw failure
        }
    }

    private suspend fun replaceVirtualHost(
        site: Site,
        layout: RuntimeLayout,
        content: String?,
        customContent: String?,
    ) {
        require(site.internalPort != policy.tunnelMetricsPort) { "Site port conflicts with Cloudflare metrics." }
        val hostPath = configurations.hostPath(layout, site)
        val configured = configuredHosts(layout)
        require(content == null || hostPath in configured || configured.size < policy.maximumVirtualHosts) {
            "The maximum number of virtual hosts has been reached."
        }
        val customHostPath = configurations.customHostPath(layout, site)
        val customHosts = configuredCustomHosts(layout)
        require(customContent == null || customHostPath in customHosts || customHosts.size < policy.maximumVirtualHosts) {
            "The maximum number of custom hostnames has been reached."
        }
        val candidateHost = "${layout.nginxHostsPath}/.${site.domainName}.${UUID.randomUUID()}.candidate"
        val candidateCustomHost = "${configurations.customHostsDirectory(layout)}/.${site.domainName}.${UUID.randomUUID()}.candidate"
        val candidateMaster = "${layout.nginxConfigPath}.${UUID.randomUUID()}.candidate"
        val revision = UUID.randomUUID().toString()
        val previousHost = files.readTextOrNull(hostPath)
        val previousCustomHost = files.readTextOrNull(customHostPath)
        val previousMaster = files.readTextOrNull(layout.nginxConfigPath)
        val previousRevision = activeRevision
        val candidateIncludes = configured.filter { it != hostPath } + if (content != null) listOf(candidateHost) else emptyList()
        val candidateCustomIncludes = customHosts.filter { it != customHostPath } +
            if (customContent != null) listOf(candidateCustomHost) else emptyList()
        val candidateConfiguration = configurations.renderMaster(
            layout,
            candidateIncludes,
            revision,
            candidateCustomIncludes,
        )
        val newMaster = configurations.renderMaster(layout, revision = revision)
        var hostInstalled = false
        var customHostInstalled = false
        var masterInstalled = false
        try {
            if (content != null) files.writeTextAtomic(candidateHost, content)
            if (customContent != null) files.writeTextAtomic(candidateCustomHost, customContent)
            files.writeTextAtomic(candidateMaster, candidateConfiguration)
            testNginx(layout, candidateMaster)
            hostInstalled = true
            restoreFile(hostPath, content)
            customHostInstalled = true
            restoreFile(customHostPath, customContent)
            masterInstalled = true
            files.writeTextAtomic(layout.nginxConfigPath, newMaster)
            nginx?.let { running ->
                ensureRunning(running, "Nginx")
                signalNginx(layout, "reload")
                awaitNginxReady(running, revision)
            }
            activeRevision = revision
            verifySiteIdentity(site)
            sites.updatePublicUrl(site.id, if (content != null) publicUrlForSite(site) else null)
        } catch (failure: Throwable) {
            if (hostInstalled || customHostInstalled || masterInstalled) {
                withContext(NonCancellable) {
                    val rollbackFailure = runCatching {
                        if (hostInstalled) restoreFile(hostPath, previousHost)
                        if (customHostInstalled) restoreFile(customHostPath, previousCustomHost)
                        if (masterInstalled) restoreFile(layout.nginxConfigPath, previousMaster)
                        nginx?.let { running ->
                            testNginx(layout, layout.nginxConfigPath)
                            signalNginx(layout, "reload")
                            if (previousRevision != null) awaitNginxReady(running, previousRevision)
                        }
                        activeRevision = previousRevision
                    }.exceptionOrNull()
                    if (rollbackFailure != null) {
                        suppress(failure, rollbackFailure)
                        runCatching { stopAllLocked() }.exceptionOrNull()?.let { suppress(failure, it) }
                        mutableState.value = state.value.copy(
                            nginxStatus = NetworkServiceStatus.FAILED,
                            lastError = "Nginx configuration rollback failed: ${description(rollbackFailure)}",
                        )
                    }
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                runCatching { files.delete(candidateHost) }
                runCatching { files.delete(candidateCustomHost) }
                runCatching { files.delete(candidateMaster) }
            }
        }
    }

    private suspend fun startNginxLocked() {
        nginx?.let { current ->
            if (!current.completion.isCompleted && state.value.nginxStatus == NetworkServiceStatus.RUNNING) return
            stopAllLocked()
        }
        mutableState.value = state.value.copy(nginxStatus = NetworkServiceStatus.STARTING, lastError = null)
        var created: ActiveProcess? = null
        var candidateMaster: String? = null
        try {
            clearPublicUrls()
            val layout = prepareLayout()
            configuredHosts(layout)
            configuredCustomHosts(layout)
            val binary = shell.resolveBinary("nginx")
            val revision = UUID.randomUUID().toString()
            val content = configurations.renderMaster(layout, revision = revision)
            candidateMaster = "${layout.nginxConfigPath}.${UUID.randomUUID()}.candidate"
            files.writeTextAtomic(candidateMaster, content)
            testNginx(layout, candidateMaster)
            files.writeTextAtomic(layout.nginxConfigPath, content)
            val handle = shell.startProcess(
                listOf(binary, "-p", "${layout.rootPath}/", "-c", layout.nginxConfigPath, "-g", "daemon off;"),
                emptyMap(),
                File(layout.rootPath),
            )
                val running = track(handle)
            created = running
            nginx = running
            running.logJob = applicationScope.launch { handle.logs.collect { mutableLogs.tryEmit(it) } }
            awaitNginxReady(running, revision)
            activeRevision = revision
            mutableState.value = state.value.copy(
                nginxStatus = NetworkServiceStatus.RUNNING,
                nginxProcessId = handle.processId,
                lastError = null,
            )
            watchNginx(running)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val stopped = cleanupCreated(created, failure)
                if (nginx === created && stopped) nginx = null
                runCatching { clearPublicUrls() }.exceptionOrNull()?.let { suppress(failure, it) }
                mutableState.value = state.value.copy(
                    nginxStatus = if (failure is CancellationException && stopped) NetworkServiceStatus.STOPPED else NetworkServiceStatus.FAILED,
                    nginxProcessId = nginx?.handle?.processId,
                    lastError = if (failure is CancellationException && stopped) null else description(failure.suppressed.firstOrNull() ?: failure),
                )
            }
            throw failure
        } finally {
            withContext(NonCancellable) { candidateMaster?.let { runCatching { files.delete(it) } } }
        }
    }

    private suspend fun startTunnelLocked() {
        val currentSites = sites.listSites()
        val mode = if (currentSites.any { it.customHostname != null }) TunnelMode.NAMED else TunnelMode.QUICK
        tunnel?.let { current ->
            if (!current.completion.isCompleted && state.value.tunnelStatus == NetworkServiceStatus.RUNNING && current.tunnelMode == mode) return
        }
        if (tunnel == null) deleteNamedTunnelFiles()

        // Reconcile Cloudflare while the current tunnel is still available; a failed API update
        // must not take down a working Quick Tunnel.
        val origin = nginxOrigin()
        val namedCredentials = if (mode == TunnelMode.NAMED) {
            cloudflare.prepare(currentSites, origin)
        } else {
            if (cloudflare.connectionState.value.configured) cloudflare.reconcile(currentSites, origin)
            null
        }
        tunnel?.let { stopTunnelLocked() }
        mutableState.value = state.value.copy(
            tunnelStatus = NetworkServiceStatus.STARTING,
            publicUrl = null,
            lastError = null,
            tunnelMode = mode,
        )
        var created: ActiveProcess? = null
        try {
            clearPublicUrls()
            val layout = prepareLayout()
            val binary = shell.resolveBinary("cloudflared")
            val handle = when (mode) {
                TunnelMode.QUICK -> {
                    val cloudflareConfiguration = "${layout.runtimePath}/cloudflared-quick.yml"
                    files.writeTextAtomic(cloudflareConfiguration, "{}\n")
                    shell.startProcess(
                        listOf(
                            binary, "tunnel", "--config", cloudflareConfiguration, "--no-autoupdate",
                            "--url", origin,
                            "--metrics", "127.0.0.1:${policy.tunnelMetricsPort}",
                        ),
                        emptyMap(),
                        File(layout.rootPath),
                    )
                }
                TunnelMode.NAMED -> {
                    val credentials = checkNotNull(namedCredentials)
                    val tokenPath = "${layout.runtimePath}/cloudflared-named.token"
                    files.writeTextAtomic(tokenPath, credentials.tunnelToken)
                    shell.startProcess(
                        listOf(
                            binary, "tunnel", "--no-autoupdate", "--metrics",
                            "127.0.0.1:${policy.tunnelMetricsPort}", "run", "--token-file", tokenPath,
                        ),
                        emptyMap(),
                        File(layout.rootPath),
                    )
                }
            }
            val running = track(handle, mode)
            created = running
            tunnel = running
            val firstUrl = CompletableDeferred<String>()
            val parsers = ProcessStream.entries.associateWith { CloudflareLogParser() }
            val tracked = running
            running.assignedUrl = if (mode == TunnelMode.NAMED) namedPublicUrl(currentSites) else null
            running.logJob = applicationScope.launch {
                try {
                    handle.logs.collect { output ->
                        mutableLogs.tryEmit(output)
                        if (mode == TunnelMode.QUICK) {
                            parsers.getValue(output.stream).append(output.text)?.let { url ->
                                firstUrl.complete(url)
                                lifecycle.withLock {
                                    if (tunnel === tracked && state.value.tunnelStatus == NetworkServiceStatus.RUNNING && state.value.publicUrl != url) {
                                        if (tunnelReady(url)) {
                                            publishPublicUrl(url, layout, TunnelMode.QUICK)
                                            tracked.assignedUrl = url
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failTunnel(tracked, failure)
                }
            }
            val url = when (mode) {
                TunnelMode.QUICK -> awaitQuickTunnelUrl(running, firstUrl)
                TunnelMode.NAMED -> {
                    awaitNamedTunnelReady(running)
                    checkNotNull(namedPublicUrl(currentSites)) { "A Named Tunnel requires at least one custom hostname." }
                }
            }
            publishPublicUrl(url, layout, mode)
            ensureRunning(running, "Cloudflare tunnel")
            running.assignedUrl = url
            mutableState.value = state.value.copy(
                tunnelStatus = NetworkServiceStatus.RUNNING,
                tunnelProcessId = handle.processId,
                publicUrl = url,
                lastError = null,
                tunnelMode = mode,
            )
            watchTunnel(running)
            monitorTunnelReadiness(running, layout)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                var stopped = cleanupCreated(created, failure)
                if (created == null && mode == TunnelMode.NAMED) {
                    runCatching { deleteNamedTunnelFiles() }.exceptionOrNull()?.let {
                        suppress(failure, it)
                        stopped = false
                    }
                }
                if (tunnel === created && stopped) tunnel = null
                runCatching { clearPublicUrls() }.exceptionOrNull()?.let { suppress(failure, it) }
                mutableState.value = state.value.copy(
                    tunnelStatus = if (failure is CancellationException && stopped) NetworkServiceStatus.STOPPED else NetworkServiceStatus.FAILED,
                    tunnelProcessId = tunnel?.handle?.processId,
                    publicUrl = null,
                    lastError = if (failure is CancellationException && stopped) null else description(failure.suppressed.firstOrNull() ?: failure),
                    tunnelMode = mode,
                )
            }
            throw failure
        }
    }

    private fun track(handle: ManagedProcess, tunnelMode: TunnelMode? = null) = ActiveProcess(
        handle,
        applicationScope.async(start = CoroutineStart.UNDISPATCHED) { runCatching { handle.await() } },
        tunnelMode,
    )

    private fun watchNginx(running: ActiveProcess) {
        running.watcher = applicationScope.launch {
            val result = running.completion.await()
            lifecycle.withLock {
                if (nginx !== running) return@withLock
                running.logJob?.cancel()
                if (result.isSuccess) nginx = null
                var error = exitDescription("Nginx", result)
                runCatching { stopTunnelLocked() }.exceptionOrNull()?.let { error += "; ${description(it)}" }
                mutableState.value = state.value.copy(
                    nginxStatus = NetworkServiceStatus.FAILED,
                    nginxProcessId = nginx?.handle?.processId,
                    lastError = error,
                )
            }
        }
    }

    private fun watchTunnel(running: ActiveProcess) {
        running.watcher = applicationScope.launch {
            val result = running.completion.await()
            lifecycle.withLock {
                if (tunnel !== running) return@withLock
                running.logJob?.cancel()
                running.healthJob?.cancel()
                if (result.isSuccess) tunnel = null
                var error = exitDescription(running.tunnelDisplayName(), result)
                if (running.tunnelMode == TunnelMode.NAMED) {
                    runCatching { deleteNamedTunnelFiles() }.exceptionOrNull()?.let { error += "; ${description(it)}" }
                }
                runCatching { clearPublicUrls() }.exceptionOrNull()?.let { error += "; ${description(it)}" }
                mutableState.value = state.value.copy(
                    tunnelStatus = NetworkServiceStatus.FAILED,
                    tunnelProcessId = tunnel?.handle?.processId,
                    publicUrl = null,
                    lastError = error,
                    tunnelMode = running.tunnelMode,
                )
            }
        }
    }

    private fun monitorTunnelReadiness(running: ActiveProcess, layout: RuntimeLayout) {
        running.healthJob = applicationScope.launch {
            try {
                while (isActive) {
                    delay(TUNNEL_HEALTH_INTERVAL_MILLIS)
                    val url = running.assignedUrl ?: continue
                    val ready = when (running.tunnelMode) {
                        TunnelMode.QUICK -> tunnelReady(url)
                        TunnelMode.NAMED -> namedTunnelReady()
                        null -> false
                    }
                    lifecycle.withLock {
                        if (tunnel !== running) return@launch
                        ensureRunning(running, running.tunnelDisplayName())
                        if (!ready && state.value.tunnelStatus == NetworkServiceStatus.RUNNING) {
                            clearPublicUrls()
                            mutableState.value = state.value.copy(
                                tunnelStatus = NetworkServiceStatus.STARTING,
                                lastError = "${running.tunnelDisplayName()} is waiting for an edge connection.",
                            )
                        } else if (ready && state.value.tunnelStatus == NetworkServiceStatus.STARTING) {
                            val mode = checkNotNull(running.tunnelMode)
                            publishPublicUrl(url, layout, mode)
                            ensureRunning(running, running.tunnelDisplayName())
                            mutableState.value = state.value.copy(
                                tunnelStatus = NetworkServiceStatus.RUNNING,
                                lastError = null,
                                tunnelMode = mode,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failTunnel(running, failure)
            }
        }
    }

    private suspend fun failTunnel(running: ActiveProcess, failure: Throwable) {
        withContext(NonCancellable) {
            lifecycle.withLock {
                if (tunnel === running) {
                    runCatching { stopTunnelLocked() }.exceptionOrNull()?.let { suppress(failure, it) }
                    mutableState.value = state.value.copy(
                        tunnelStatus = NetworkServiceStatus.FAILED,
                        lastError = description(failure),
                    )
                }
            }
        }
    }

    private suspend fun stopTunnelLocked() {
        val running = tunnel
        var failure: Throwable? = null
        withContext(NonCancellable) {
            if (running != null) {
                running.watcher?.cancel()
                running.healthJob?.cancel()
                failure = runCatching { running.handle.stop() }.exceptionOrNull()
                running.logJob?.cancel()
            }
            if (failure == null) failure = runCatching { deleteNamedTunnelFiles() }.exceptionOrNull()
            if (running != null && failure == null) tunnel = null
            runCatching { clearPublicUrls() }.exceptionOrNull()?.let { cause ->
                if (failure == null) failure = cause else suppress(failure!!, cause)
            }
            mutableState.value = state.value.copy(
                tunnelStatus = if (failure == null) NetworkServiceStatus.STOPPED else NetworkServiceStatus.FAILED,
                tunnelProcessId = tunnel?.handle?.processId,
                publicUrl = null,
                lastError = failure?.let(::description),
                tunnelMode = tunnel?.tunnelMode,
            )
        }
        failure?.let { throw it }
    }

    private suspend fun stopAllLocked() {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            failure = runCatching { stopTunnelLocked() }.exceptionOrNull()
            val running = nginx
            if (running != null) {
                running.watcher?.cancel()
                // Quit asks Nginx to drain active clients. The managed process group is the fallback.
                runCatching {
                    ensureRunning(running, "Nginx")
                    val layout = shell.runtimeLayout()
                    signalNginx(layout, "quit")
                    withTimeout(GRACEFUL_QUIT_MILLIS) { running.completion.await().getOrThrow() }
                }
                val stopFailure = runCatching { running.handle.stop() }.exceptionOrNull()
                stopFailure?.let { cause ->
                    if (failure == null) failure = cause else suppress(failure!!, cause)
                }
                running.logJob?.cancel()
                if (stopFailure == null) nginx = null
            }
            mutableState.value = state.value.copy(
                nginxStatus = if (nginx == null) NetworkServiceStatus.STOPPED else NetworkServiceStatus.FAILED,
                nginxProcessId = nginx?.handle?.processId,
                lastError = failure?.let(::description),
            )
        }
        failure?.let { throw it }
    }

    private suspend fun cleanupCreated(created: ActiveProcess?, failure: Throwable): Boolean {
        if (created == null) return true
        created.watcher?.cancel()
        created.healthJob?.cancel()
        var stopFailure = runCatching { created.handle.stop() }.exceptionOrNull()
        stopFailure?.let { suppress(failure, it) }
        if (stopFailure == null && created.tunnelMode == TunnelMode.NAMED) {
            stopFailure = runCatching { deleteNamedTunnelFiles() }.exceptionOrNull()
            stopFailure?.let { suppress(failure, it) }
        }
        created.logJob?.cancel()
        return stopFailure == null
    }

    private suspend fun publishPublicUrl(url: String, layout: RuntimeLayout, mode: TunnelMode) {
        serverState.updatePublicUrl(url)
        val configured = configuredHosts(layout).toSet()
        sites.listSites().forEach { site ->
            val public = if (configurations.hostPath(layout, site) !in configured) null else {
                when (mode) {
                    TunnelMode.QUICK -> "${url.trimEnd('/')}${configurations.sitePrefix(site)}"
                    TunnelMode.NAMED -> site.customHostname?.let { "https://$it/" }
                }
            }
            sites.updatePublicUrl(site.id, public)
        }
        mutableState.value = state.value.copy(publicUrl = url, tunnelMode = mode)
    }

    private fun publicUrlForSite(site: Site): String? = when (state.value.tunnelMode) {
        TunnelMode.QUICK -> state.value.publicUrl?.let { "${it.trimEnd('/')}${configurations.sitePrefix(site)}" }
        TunnelMode.NAMED -> site.customHostname?.let { "https://$it/" }
        null -> null
    }

    private suspend fun clearPublicUrls() {
        mutableState.value = state.value.copy(publicUrl = null)
        var failure: Throwable? = runCatching { serverState.updatePublicUrl(null) }.exceptionOrNull()
        val currentSites = runCatching { sites.listSites() }.getOrElse { cause ->
            if (failure == null) failure = cause else suppress(failure!!, cause)
            emptyList()
        }
        currentSites.filter { it.publicUrl != null }.forEach { site ->
            runCatching { sites.updatePublicUrl(site.id, null) }.exceptionOrNull()?.let { cause ->
                if (failure == null) failure = cause else suppress(failure!!, cause)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun prepareLayout(): RuntimeLayout {
        val layout = shell.runtimeLayout()
        listOf(
            layout.rootPath, layout.projectsPath, layout.nginxHostsPath, configurations.customHostsDirectory(layout),
            layout.runtimePath, layout.logsPath,
            "${layout.runtimePath}/client-body", "${layout.runtimePath}/proxy-temp",
            "${layout.runtimePath}/fastcgi-temp", "${layout.runtimePath}/uwsgi-temp", "${layout.runtimePath}/scgi-temp",
        ).forEach { files.ensureDirectory(it) }
        return layout
    }

    private suspend fun verifySiteIdentity(site: Site) {
        val persisted = sites.getSite(site.id)
        require(
            persisted != null && persisted.domainName == site.domainName &&
                persisted.internalPort == site.internalPort && persisted.customHostname == site.customHostname,
        ) {
            "The site's database identity, hostname, or port changed during configuration."
        }
    }

    private suspend fun configuredHosts(layout: RuntimeLayout): List<String> {
        val hosts = files.listFiles(layout.nginxHostsPath).filter { it.endsWith(".conf") }.sorted()
        require(hosts.size <= policy.maximumVirtualHosts) { "Too many Nginx virtual hosts are installed." }
        return hosts
    }

    private suspend fun configuredCustomHosts(layout: RuntimeLayout): List<String> {
        val hosts = files.listFiles(configurations.customHostsDirectory(layout)).filter { it.endsWith(".conf") }.sorted()
        require(hosts.size <= policy.maximumVirtualHosts) { "Too many Nginx custom-host servers are installed." }
        return hosts
    }

    private suspend fun restoreFile(path: String, text: String?) {
        if (text == null) files.delete(path) else files.writeTextAtomic(path, text)
    }

    private suspend fun testNginx(layout: RuntimeLayout, path: String) {
        val binary = shell.resolveBinary("nginx")
        runNginxCommand(layout, listOf(binary, "-p", "${layout.rootPath}/", "-c", path, "-t"))
    }

    private suspend fun signalNginx(layout: RuntimeLayout, signal: String) {
        val binary = shell.resolveBinary("nginx")
        runNginxCommand(layout, listOf(binary, "-p", "${layout.rootPath}/", "-c", layout.nginxConfigPath, "-s", signal))
    }

    private suspend fun runNginxCommand(layout: RuntimeLayout, arguments: List<String>) {
        val result = shell.execute(
            ProcessRequest(ProcessCommand.Arguments(arguments), emptyMap(), File(layout.rootPath)),
            policy.configTestTimeoutMillis,
        )
        if (!result.isSuccessful) throw ProcessIOException(
            "Nginx command failed (${result.termination}, exit ${result.exitCode}): ${result.stderr.takeLast(1_024)}",
        )
    }

    private suspend fun awaitNginxReady(running: ActiveProcess, revision: String) {
        val ready = withTimeoutOrNull(policy.startupTimeoutMillis) {
            while (true) {
                ensureRunning(running, "Nginx")
                val response = httpGet("http://${config.nginxHost}:${config.nginxPort}/_pocketpaas/health")
                if (response?.status == HttpURLConnection.HTTP_OK && response.body == revision) {
                    ensureRunning(running, "Nginx")
                    return@withTimeoutOrNull true
                }
                delay(policy.readinessPollMillis)
            }
        }
        if (ready != true) throw ProcessIOException(
            "Nginx readiness timed out after ${policy.startupTimeoutMillis} ms on ${config.nginxHost}:${config.nginxPort}. " +
                "Check Nginx logs and whether the port is already occupied.",
        )
    }

    private suspend fun awaitQuickTunnelUrl(running: ActiveProcess, firstUrl: CompletableDeferred<String>): String {
        val url = withTimeoutOrNull(policy.tunnelStartupTimeoutMillis) {
            while (!firstUrl.isCompleted) {
                ensureRunning(running, running.tunnelDisplayName())
                delay(policy.readinessPollMillis)
            }
            val assigned = firstUrl.await()
            while (!tunnelReady(assigned)) {
                ensureRunning(running, running.tunnelDisplayName())
                delay(policy.readinessPollMillis)
            }
            ensureRunning(running, running.tunnelDisplayName())
            assigned
        }
        return url ?: throw ProcessIOException(
            if (firstUrl.isCompleted) {
                "Cloudflare edge connection did not become ready within ${policy.tunnelStartupTimeoutMillis} ms. " +
                    "Check the Internet connection, DNS and firewall/VPN settings."
            } else {
                "Cloudflare did not report a Quick Tunnel URL within ${policy.tunnelStartupTimeoutMillis} ms. " +
                    "Check cloudflared logs, its installed version and the Internet connection."
            },
        )
    }

    private suspend fun awaitNamedTunnelReady(running: ActiveProcess) {
        val ready = withTimeoutOrNull(policy.tunnelStartupTimeoutMillis) {
            while (!namedTunnelReady()) {
                ensureRunning(running, running.tunnelDisplayName())
                delay(policy.readinessPollMillis)
            }
            ensureRunning(running, running.tunnelDisplayName())
            true
        }
        if (ready != true) throw ProcessIOException(
            "Cloudflare Named Tunnel did not connect to an edge within ${policy.tunnelStartupTimeoutMillis} ms. " +
                "Check the Cloudflare account, Internet connection and cloudflared logs.",
        )
    }

    private suspend fun namedTunnelReady(): Boolean =
        httpGet("http://127.0.0.1:${policy.tunnelMetricsPort}/ready")?.status == HttpURLConnection.HTTP_OK

    private fun nginxOrigin(): String = "http://${config.nginxHost}:${config.nginxPort}"

    private fun namedPublicUrl(currentSites: List<Site>): String? = currentSites.mapNotNull { it.customHostname }
        .distinct()
        .sorted()
        .firstOrNull()
        ?.let { "https://$it" }

    private suspend fun deleteNamedTunnelFiles() {
        val layout = shell.runtimeLayout()
        files.delete("${layout.runtimePath}/cloudflared-named.token")
        files.delete("${layout.runtimePath}/cloudflared-named.yml")
        // Remove credentials left by earlier debug builds that used a locally-managed JSON file.
        files.delete("${layout.runtimePath}/cloudflared-named.json")
    }

    private suspend fun tunnelReady(url: String): Boolean {
        val root = "http://127.0.0.1:${policy.tunnelMetricsPort}"
        if (httpGet("$root/ready")?.status != HttpURLConnection.HTTP_OK) return false
        val response = httpGet("$root/quicktunnel") ?: return false
        if (response.status != HttpURLConnection.HTTP_OK) return false
        return runCatching {
            val hostname = JsonParser.parseString(response.body).asJsonObject.get("hostname").asString
            hostname.equals(URI(url).host, ignoreCase = true)
        }.getOrDefault(false)
    }

    private suspend fun httpGet(address: String): HttpResponse? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val deadline = System.nanoTime() + policy.readinessConnectTimeoutMillis * 1_000_000L
        try {
            connection = URL(address).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = policy.readinessConnectTimeoutMillis
            connection.readTimeout = policy.readinessConnectTimeoutMillis
            connection.requestMethod = "GET"
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = ByteArray(MAXIMUM_PROBE_BYTES + 1)
                var total = 0
                while (total < bytes.size) {
                    if (System.nanoTime() >= deadline) return@withContext null
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total > MAXIMUM_PROBE_BYTES) return@withContext null
                String(bytes, 0, total, Charsets.UTF_8)
            }.orEmpty()
            HttpResponse(status, body)
        } catch (_: java.io.IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun ensureRunning(running: ActiveProcess, name: String) {
        if (running.completion.isCompleted) throw ProcessIOException(exitDescription(name, running.completion.await()))
    }

    private fun exitDescription(name: String, result: Result<ProcessResult>): String = result.fold(
        onSuccess = { "$name exited (${it.termination}, exit ${it.exitCode}): ${it.stderr.takeLast(1_024)}" },
        onFailure = { "$name failed: ${description(it)}" },
    )

    private fun description(failure: Throwable): String = (failure.message ?: failure.javaClass.simpleName).take(1_024)

    private fun suppress(primary: Throwable, secondary: Throwable) {
        if (secondary !== primary) primary.addSuppressed(secondary)
    }

    private data class HttpResponse(val status: Int, val body: String)

    private class ActiveProcess(
        val handle: ManagedProcess,
        val completion: Deferred<Result<ProcessResult>>,
        val tunnelMode: TunnelMode? = null,
    ) {
        fun tunnelDisplayName(): String = when (tunnelMode) {
            TunnelMode.QUICK -> "Cloudflare Quick Tunnel"
            TunnelMode.NAMED -> "Cloudflare Named Tunnel"
            null -> "Cloudflare tunnel"
        }

        @Volatile var assignedUrl: String? = null
        var logJob: Job? = null
        var watcher: Job? = null
        var healthJob: Job? = null
    }

    private companion object {
        const val GRACEFUL_QUIT_MILLIS = 2_000L
        const val TUNNEL_HEALTH_INTERVAL_MILLIS = 2_000L
        const val MAXIMUM_PROBE_BYTES = 4_096
    }
}
