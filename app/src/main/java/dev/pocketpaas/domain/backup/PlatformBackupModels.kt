package dev.pocketpaas.domain.backup

import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshSettings

data class SiteBackupEntry(
    val id: Long,
    val domainName: String,
    val internalPort: Int,
    val projectType: ProjectType,
    val publicUrl: String?,
    val status: SiteStatus,
    val createdAt: Long,
    val customHostname: String? = null,
)

data class PlatformBackupSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = CURRENT_APP_VERSION,
    val exportedAt: Long,
    val sshSettings: SshSettings,
    val sshAuthorizedKeys: List<SshAuthorizedKey>,
    val services: List<ManagedServiceState>,
    val sites: List<SiteBackupEntry>,
    val recentDeployments: List<DeploymentRecord>,
    val recentActivity: List<ActivityEvent>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
        const val CURRENT_APP_VERSION = "0.5.0"
    }
}

data class SignedBackupEnvelope(
    val version: Int = ENVELOPE_VERSION,
    val algorithm: String = ALGORITHM_HMAC_SHA256,
    val exportedAt: Long,
    val payloadJson: String,
    val signatureHex: String?,
) {
    companion object {
        const val ENVELOPE_VERSION = 1
        const val ALGORITHM_HMAC_SHA256 = "HMAC-SHA256"
        const val ALGORITHM_UNSIGNED = "NONE"
    }
}

data class BackupRestoreSummary(
    val schemaVersion: Int,
    val restoredSshSettings: Boolean,
    val restoredSshKeys: Int,
    val restoredServices: Int,
    val signatureVerified: Boolean,
    val restoredAt: Long,
)

interface PlatformBackupManager {
    suspend fun exportSnapshot(signWithApiKey: Boolean = true): SignedBackupEnvelope
    suspend fun exportEnvelopeJson(signWithApiKey: Boolean = true): String
    suspend fun importEnvelopeJson(rawJson: String, requireSignature: Boolean = false): BackupRestoreSummary
}
