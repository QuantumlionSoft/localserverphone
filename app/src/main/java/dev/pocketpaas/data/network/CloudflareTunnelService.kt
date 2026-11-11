package dev.pocketpaas.data.network

import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import dev.pocketpaas.domain.network.CloudflareConnectionState
import dev.pocketpaas.domain.network.CloudflareSettingsStore
import dev.pocketpaas.domain.network.CloudflareTunnelProvider
import dev.pocketpaas.domain.network.NamedTunnelCredentials
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns Cloudflare account verification, one app-managed Named Tunnel, and its proxied DNS records. */
@Singleton
class CloudflareTunnelService @Inject constructor(
    private val settingsStore: CloudflareSettingsStore,
    private val api: CloudflareApiClient,
    @ApplicationScope applicationScope: CoroutineScope,
) : CloudflareTunnelProvider {
    private val operations = Mutex()
    private val mutableConnection = MutableStateFlow(CloudflareConnectionState())
    override val connectionState: StateFlow<CloudflareConnectionState> = mutableConnection.asStateFlow()

    init {
        applicationScope.launch { refreshConnectionState() }
    }

    override suspend fun configure(apiToken: String, accountId: String, zoneName: String): CloudflareConnectionState =
        operations.withLock {
            val normalizedAccount = accountId.trim().lowercase(Locale.ROOT)
            require(ACCOUNT_ID.matches(normalizedAccount)) { "Enter the 32-character Cloudflare Account ID." }
            val normalizedZone = SiteInput.normalizeCustomHostname(zoneName)
            val current = settingsStore.read()
            val sameScope = current != null && current.accountId == normalizedAccount && current.zoneName == normalizedZone
            val token = apiToken.trim().ifBlank {
                require(sameScope) { "Enter a Cloudflare API token for this account and zone." }
                checkNotNull(current).apiToken
            }
            require(token.length in 20..512 && token.none(Char::isISOControl) && token.none(Char::isWhitespace)) {
                "Cloudflare API token must be a non-empty token without spaces or control characters."
            }
            val zoneId = api.verifyCredentials(token, normalizedAccount, normalizedZone)
            val sameResources = current != null && sameScope && current.zoneId == zoneId
            if (!sameResources && current != null) {
                require(current.managedHostnames.isEmpty()) {
                    "Unpublish the existing custom hostnames before changing Cloudflare account or zone."
                }
                require(current.tunnelId == null) {
                    "Disconnect and remove the existing Cloudflare Tunnel before changing account or zone."
                }
            }
            val next = CloudflareAccountSettings(
                apiToken = token,
                accountId = normalizedAccount,
                zoneId = zoneId,
                zoneName = normalizedZone,
                tunnelId = current?.takeIf { sameResources }?.tunnelId,
                tunnelName = current?.takeIf { sameResources }?.tunnelName,
                tunnelToken = current?.takeIf { sameResources }?.tunnelToken,
                managedHostnames = current?.takeIf { sameResources }?.managedHostnames.orEmpty(),
            )
            settingsStore.write(next)
            publish(next)
            mutableConnection.value
        }

    override suspend fun disconnect() = operations.withLock {
        val current = settingsStore.read() ?: return@withLock
        require(current.managedHostnames.isEmpty()) {
            "Remove all managed DNS hostnames before disconnecting Cloudflare."
        }
        api.deleteTunnel(current)
        settingsStore.clear()
        mutableConnection.value = CloudflareConnectionState()
    }

    override suspend fun prepare(sites: List<Site>, origin: String): NamedTunnelCredentials = operations.withLock {
        val settings = settingsStore.read()
            ?: throw IllegalStateException("Connect a Cloudflare API token before publishing custom hostnames.")
        val desired = desiredHostnames(sites, settings)
        require(desired.isNotEmpty()) { "At least one custom hostname is required for a Named Tunnel." }
        val ready = ensureTunnel(settings)
        reconcileLocked(ready, sites, origin, desired)
        NamedTunnelCredentials(
            accountId = ready.accountId,
            tunnelId = checkNotNull(ready.tunnelId),
            tunnelName = checkNotNull(ready.tunnelName),
            tunnelToken = checkNotNull(ready.tunnelToken),
        )
    }

    override suspend fun reconcile(sites: List<Site>, origin: String) = operations.withLock {
        val current = settingsStore.read()
        val desired = if (current == null) {
            require(sites.none { it.customHostname != null }) {
                "Connect Cloudflare before assigning a custom hostname to a site."
            }
            return@withLock
        } else desiredHostnames(sites, current)
        if (current.tunnelId == null && desired.isEmpty()) return@withLock
        val ready = ensureTunnel(current)
        reconcileLocked(ready, sites, origin, desired)
    }

    suspend fun refreshConnectionState() = operations.withLock {
        publish(settingsStore.read())
    }

    private suspend fun ensureTunnel(current: CloudflareAccountSettings): CloudflareAccountSettings {
        if (current.tunnelId != null) {
            require(current.tunnelName != null) {
                "Cloudflare Tunnel identity is incomplete; disconnect and reconnect after unpublishing hostnames."
            }
        }
        val prepared = if (current.tunnelName != null) current else {
            val candidate = current.copy(
                tunnelName = "pocketpaas-${UUID.randomUUID().toString().replace("-", "").take(12)}",
                tunnelId = null,
                tunnelToken = null,
            )
            settingsStore.write(candidate)
            candidate
        }
        val tunnelId = prepared.tunnelId ?: api.findOrCreateTunnel(prepared).also { createdId ->
            settingsStore.write(prepared.copy(tunnelId = createdId))
        }
        val identified = prepared.copy(tunnelId = tunnelId)
        // Fetch the current remote run token on every reconciliation so dashboard token rotations
        // do not leave PocketPaaS trying to start cloudflared with an obsolete credential.
        val tunnelToken = api.getTunnelToken(identified)
        val ready = identified.copy(tunnelToken = tunnelToken)
        if (identified.tunnelToken != tunnelToken) settingsStore.write(ready)
        publish(ready)
        return ready
    }

    private suspend fun reconcileLocked(
        settings: CloudflareAccountSettings,
        sites: List<Site>,
        origin: String,
        desiredHostnames: Set<String>,
    ) {
        val pendingHostnames = (settings.managedHostnames + desiredHostnames).distinct().sorted()
        val intent = settings.copy(managedHostnames = pendingHostnames)
        // Keep the union until all remote work succeeds. If a removal is interrupted, a retry must
        // still know which old DNS names PocketPaaS is allowed to clean up.
        settingsStore.write(intent)
        publish(intent)
        api.updateTunnelIngress(settings, sites.filter { it.customHostname != null }, origin)
        val managed = api.syncDnsRecords(
            intent,
            desiredHostnames,
            checkNotNull(settings.tunnelId),
        )
        val updated = intent.copy(managedHostnames = managed)
        settingsStore.write(updated)
        publish(updated)
    }

    private fun desiredHostnames(sites: List<Site>, settings: CloudflareAccountSettings): Set<String> {
        val hosts = sites.mapNotNull { site ->
            site.customHostname?.let(SiteInput::normalizeCustomHostname)?.also { hostname ->
                require(SiteInput.isHostnameInZone(hostname, settings.zoneName)) {
                    "$hostname is outside the configured Cloudflare zone ${settings.zoneName}."
                }
            }
        }
        require(hosts.distinct().size == hosts.size) { "Each custom hostname can be assigned to only one site." }
        return hosts.toSet()
    }

    private fun publish(settings: CloudflareAccountSettings?) {
        mutableConnection.value = if (settings == null) CloudflareConnectionState() else {
            CloudflareConnectionState(
                configured = true,
                accountId = settings.accountId,
                zoneName = settings.zoneName,
                tunnelId = settings.tunnelId,
            )
        }
    }

    private companion object {
        val ACCOUNT_ID = Regex("[a-f0-9]{32}")
    }
}
