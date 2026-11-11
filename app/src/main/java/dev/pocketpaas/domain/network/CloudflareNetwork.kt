package dev.pocketpaas.domain.network

import dev.pocketpaas.domain.model.Site
import kotlinx.coroutines.flow.StateFlow

/** Secret-bearing Cloudflare configuration. Persisted only by the Android Keystore-backed store. */
data class CloudflareAccountSettings(
    val apiToken: String,
    val accountId: String,
    val zoneId: String,
    val zoneName: String,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val tunnelToken: String? = null,
    val managedHostnames: List<String> = emptyList(),
) {
    override fun toString(): String =
        "CloudflareAccountSettings(accountId=$accountId, zoneId=$zoneId, zoneName=$zoneName, " +
            "tunnelId=$tunnelId, tunnelName=$tunnelName, managedHostnames=$managedHostnames, " +
            "apiToken=[redacted], tunnelToken=[redacted])"
}

/** Safe for UI state: deliberately never contains the Cloudflare API token or Tunnel run token. */
data class CloudflareConnectionState(
    val configured: Boolean = false,
    val accountId: String? = null,
    val zoneName: String? = null,
    val tunnelId: String? = null,
)

data class NamedTunnelCredentials(
    val accountId: String,
    val tunnelId: String,
    val tunnelName: String,
    val tunnelToken: String,
) {
    override fun toString(): String =
        "NamedTunnelCredentials(accountId=$accountId, tunnelId=$tunnelId, tunnelName=$tunnelName, tunnelToken=[redacted])"
}

interface CloudflareSettingsStore {
    suspend fun read(): CloudflareAccountSettings?
    suspend fun write(settings: CloudflareAccountSettings)
    suspend fun clear()
}

/** Configures Cloudflare resources and prepares the per-tunnel credential used by cloudflared. */
interface CloudflareTunnelProvider {
    val connectionState: StateFlow<CloudflareConnectionState>

    suspend fun configure(apiToken: String, accountId: String, zoneName: String): CloudflareConnectionState
    suspend fun disconnect()
    suspend fun prepare(sites: List<Site>, origin: String): NamedTunnelCredentials
    suspend fun reconcile(sites: List<Site>, origin: String)
}

/** UI-facing operations; implementation serializes site/Nginx/tunnel changes as one lifecycle operation. */
interface CloudflareSitePublisher {
    val cloudflareConnection: StateFlow<CloudflareConnectionState>

    suspend fun configureCloudflare(apiToken: String, accountId: String, zoneName: String)
    suspend fun disconnectCloudflare()
    suspend fun setCustomHostname(siteId: Long, hostname: String?)
}
