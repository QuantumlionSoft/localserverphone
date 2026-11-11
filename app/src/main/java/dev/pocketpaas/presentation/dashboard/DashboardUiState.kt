package dev.pocketpaas.presentation.dashboard

import dev.pocketpaas.domain.logs.ActivityFilter
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.network.CloudflareConnectionState
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshState

data class ProjectDraft(
    val domainName: String = "",
    val projectType: ProjectType = ProjectType.STATIC,
    val zipUri: String? = null,
    val zipName: String? = null,
    val error: String? = null,
)

data class SshDraft(
    val keyName: String = "",
    val publicKey: String = "",
    val error: String? = null,
)

data class BackupDraft(
    val importJson: String = "",
    val requireSignature: Boolean = false,
    val lastExportedJson: String? = null,
    val error: String? = null,
)

data class CloudflareDraft(
    /** In-memory form input only; the API token is persisted separately by the Keystore store. */
    val apiToken: String = "",
    val accountId: String = "",
    val zoneName: String = "",
) {
    override fun toString(): String = "CloudflareDraft(apiToken=[redacted], accountId=$accountId, zoneName=$zoneName)"
}

data class DashboardNotice(val id: Long, val text: String)

data class DashboardUiState(
    val runtime: RemoteServerState = RemoteServerState(),
    val network: NetworkState = NetworkState(),
    val sites: List<Site> = emptyList(),
    val loadingSites: Boolean = true,
    val siteReadFailed: Boolean = false,
    val initializing: Boolean = true,
    val setupFailed: Boolean = false,
    val capabilities: RuntimeCapabilities? = null,
    val apiPort: Int = 8080,
    val nginxPort: Int = 8081,
    val showAddProject: Boolean = false,
    val draft: ProjectDraft = ProjectDraft(),
    val selectedSiteId: Long? = null,
    val siteFileManager: SiteFileManagerUiState = SiteFileManagerUiState(),
    val cloudflareConnection: CloudflareConnectionState = CloudflareConnectionState(),
    val cloudflareDraft: CloudflareDraft = CloudflareDraft(),
    val cloudflareBusy: Boolean = false,
    val cloudflareError: String? = null,
    val operationBusy: Boolean = false,
    val operationDescription: String? = null,
    val lanAccess: Boolean = false,
    val notice: DashboardNotice? = null,
    val ssh: SshState = SshState(),
    val sshKeys: List<SshAuthorizedKey> = emptyList(),
    val sshDraft: SshDraft = SshDraft(),
    val showSshSheet: Boolean = false,
    val managedServices: List<ManagedServiceState> = emptyList(),
    val deployments: List<DeploymentRecord> = emptyList(),
    val activity: List<ActivityEvent> = emptyList(),
    val activityFilter: ActivityFilter = ActivityFilter(),
    val showBackupSheet: Boolean = false,
    val backupDraft: BackupDraft = BackupDraft(),
    val selectedTab: DashboardTab = DashboardTab.PROJECTS,
) {
    val selectedSite: Site? get() = sites.firstOrNull { it.id == selectedSiteId }
    val serverRunning: Boolean get() = runtime.status == RemoteServerStatus.RUNNING
    val canManageSites: Boolean get() = serverRunning && !operationBusy && !siteReadFailed && !loadingSites
    val canManageSiteFiles: Boolean get() = setupReady && !initializing && !operationBusy && !siteReadFailed && !loadingSites
    val canManageCustomHostnames: Boolean get() =
        setupReady && !initializing && !operationBusy && !siteReadFailed && !loadingSites && !cloudflareBusy
    val setupReady: Boolean get() = capabilities?.let { it.termuxInstalled && it.termuxCommandPermissionGranted } == true
    val databaseServices: List<ManagedServiceState> get() = managedServices.filter { it.serviceKey in DATABASE_KEYS }
    val sshConnectionCommand: String? get() = ssh.connectionCommand
    val filteredActivity: List<ActivityEvent> get() = activity.filter(activityFilter::matches)
    val filteredDeployments: List<DeploymentRecord> get() = deployments.filter(activityFilter::matchesDeployment)

    companion object {
        val DATABASE_KEYS = setOf("postgresql", "redis", "mariadb")
    }
}

enum class DashboardTab { PROJECTS, SERVICES, ACTIVITY }
