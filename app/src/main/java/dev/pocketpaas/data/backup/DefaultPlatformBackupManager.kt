package dev.pocketpaas.data.backup

import dev.pocketpaas.core.config.SshPolicy
import dev.pocketpaas.data.local.ServiceStateDao
import dev.pocketpaas.data.local.ServiceStateEntity
import dev.pocketpaas.data.local.SshAuthorizedKeyEntity
import dev.pocketpaas.data.local.SshDao
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.data.local.toEntity
import dev.pocketpaas.domain.backup.BackupCodec
import dev.pocketpaas.domain.backup.BackupRestoreSummary
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.backup.PlatformBackupSnapshot
import dev.pocketpaas.domain.backup.SignedBackupEnvelope
import dev.pocketpaas.domain.backup.SiteBackupEntry
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.ssh.SshSettings
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class DefaultPlatformBackupManager @Inject constructor(
    private val sites: SiteRepository,
    private val sshDao: SshDao,
    private val serviceDao: ServiceStateDao,
    private val deployments: DeploymentHistoryRepository,
    private val activity: ActivityRepository,
    private val apiKeys: ApiKeyStore,
    private val sshPolicy: SshPolicy,
    private val clock: Clock,
) : PlatformBackupManager {

    override suspend fun exportSnapshot(signWithApiKey: Boolean): SignedBackupEnvelope {
        val now = clock.millis()
        val settings = sshDao.getSettings()?.toDomain() ?: SshSettings(port = sshPolicy.defaultPort, updatedAt = now)
        val keys = sshDao.getAuthorizedKeys().map { it.toDomain() }
        val services = serviceDao.getServices().map { it.toDomain() }
        val siteEntries = sites.listSites().map { site ->
            SiteBackupEntry(
                id = site.id,
                domainName = site.domainName,
                internalPort = site.internalPort,
                projectType = site.projectType,
                publicUrl = site.publicUrl,
                status = site.status,
                createdAt = site.createdAt,
                customHostname = site.customHostname,
            )
        }
        val recentDeployments = deployments.recent(50)
        val recentActivity = activity.recent(100)

        val snapshot = PlatformBackupSnapshot(
            exportedAt = now,
            sshSettings = settings,
            sshAuthorizedKeys = keys,
            services = services,
            sites = siteEntries,
            recentDeployments = recentDeployments,
            recentActivity = recentActivity,
        )
        val secret = if (signWithApiKey) resolveSecretOrNull() else null
        val envelope = BackupCodec.createEnvelope(snapshot, secret)
        runCatching {
            activity.record(
                category = ActivityCategory.SYSTEM,
                level = ActivityLevel.INFO,
                message = "Platform yedeği dışa aktarıldı (${keys.size} SSH anahtarı, ${siteEntries.size} site).",
            )
        }
        return envelope
    }

    override suspend fun exportEnvelopeJson(signWithApiKey: Boolean): String =
        BackupCodec.encodeEnvelopeJson(exportSnapshot(signWithApiKey))

    override suspend fun importEnvelopeJson(rawJson: String, requireSignature: Boolean): BackupRestoreSummary {
        val secret = resolveSecretOrNull()
        val verified = BackupCodec.decodeAndVerify(
            rawJson = rawJson,
            secretKey = secret,
            requireSignature = requireSignature,
            sshPolicy = sshPolicy,
        )
        val snapshot = verified.snapshot
        val now = clock.millis()

        val currentSettings = sshDao.getSettings()
        val restoredSettings = snapshot.sshSettings.copy(
            enabled = false,
            hostKeyFingerprint = currentSettings?.hostKeyFingerprint ?: snapshot.sshSettings.hostKeyFingerprint,
            updatedAt = now,
        )
        sshDao.upsertSettings(restoredSettings.toEntity(now))

        var restoredKeyCount = 0
        var existingCount = sshDao.countAuthorizedKeys()
        for (key in snapshot.sshAuthorizedKeys) {
            if (existingCount >= sshPolicy.maximumAuthorizedKeys) break
            if (sshDao.findByFingerprint(key.fingerprint) == null) {
                sshDao.insertAuthorizedKey(
                    SshAuthorizedKeyEntity(
                        name = key.name,
                        publicKey = key.publicKey,
                        fingerprint = key.fingerprint,
                        enabled = key.enabled,
                        createdAt = if (key.createdAt > 0) key.createdAt else now,
                    ),
                )
                existingCount++
                restoredKeyCount++
            }
        }

        var restoredServiceCount = 0
        for (service in snapshot.services) {
            val existing = serviceDao.getService(service.serviceKey)
            if (existing == null) {
                serviceDao.upsert(
                    ServiceStateEntity(
                        serviceKey = service.serviceKey,
                        displayName = service.displayName,
                        category = service.category,
                        installed = service.installed,
                        status = ManagedServiceStatus.STOPPED,
                        processId = null,
                        port = service.port,
                        lastError = null,
                        updatedAt = now,
                    ),
                )
                restoredServiceCount++
            }
        }

        val summary = BackupRestoreSummary(
            schemaVersion = snapshot.schemaVersion,
            restoredSshSettings = true,
            restoredSshKeys = restoredKeyCount,
            restoredServices = restoredServiceCount,
            signatureVerified = verified.signatureVerified,
            restoredAt = now,
        )
        runCatching {
            activity.record(
                category = ActivityCategory.SECURITY,
                level = ActivityLevel.INFO,
                message = "Yedek geri yüklendi: $restoredKeyCount yeni SSH anahtarı, $restoredServiceCount servis kaydı (imza=${if (verified.signatureVerified) "doğrulandı" else "imzasız"}).",
            )
        }
        return summary
    }

    private suspend fun resolveSecretOrNull(): String? = try {
        apiKeys.token()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
