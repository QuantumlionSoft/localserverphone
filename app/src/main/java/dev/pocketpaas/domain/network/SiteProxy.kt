package dev.pocketpaas.domain.network

import dev.pocketpaas.domain.model.Site

data class SiteProxySnapshot(
    val configuration: String?,
    val customHostConfiguration: String? = null,
)

interface SiteProxy {
    suspend fun ensureServerRunning()
    suspend fun snapshot(site: Site): SiteProxySnapshot
    suspend fun configure(site: Site, rootDir: String)
    suspend fun restore(site: Site, snapshot: SiteProxySnapshot)
}
