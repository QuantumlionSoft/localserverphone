package dev.pocketpaas.domain.backup

import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ServiceCategory
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshSettings
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    @Test
    fun `signed backup round trip verifies hmac and forces ssh disabled on restore`() {
        val keyLine = sampleEd25519Key("workstation")
        val parsed = SshKeyFormat.parse(keyLine)
        val snapshot = PlatformBackupSnapshot(
            exportedAt = 1_700_000_000_000L,
            sshSettings = SshSettings(enabled = true, port = 8022, passwordAuthEnabled = false, listenLan = true),
            sshAuthorizedKeys = listOf(
                SshAuthorizedKey(1, "workstation", parsed.line, parsed.fingerprint, enabled = true, createdAt = 100L),
            ),
            services = listOf(
                ManagedServiceState(
                    serviceKey = "postgresql",
                    displayName = "PostgreSQL",
                    category = ServiceCategory.DATABASE,
                    installed = true,
                    status = ManagedServiceStatus.RUNNING,
                    processId = 4321,
                    port = 5432,
                    lastError = null,
                    updatedAt = 200L,
                ),
            ),
            sites = listOf(
                SiteBackupEntry(
                    1, "demo.example", 18000, ProjectType.NODEJS, null, SiteStatus.RUNNING, 50L,
                    customHostname = "www.demo.example",
                ),
            ),
            recentDeployments = emptyList(),
            recentActivity = emptyList(),
        )
        val secret = "a".repeat(64)
        val envelope = BackupCodec.createEnvelope(snapshot, secret)
        val json = BackupCodec.encodeEnvelopeJson(envelope)

        val verified = BackupCodec.decodeAndVerify(
            rawJson = json,
            secretKey = secret,
            requireSignature = true,
        )
        assertTrue(verified.signatureVerified)
        assertFalse(verified.snapshot.sshSettings.enabled)
        assertEquals(8022, verified.snapshot.sshSettings.port)
        assertEquals(1, verified.snapshot.sshAuthorizedKeys.size)
        assertEquals(parsed.fingerprint, verified.snapshot.sshAuthorizedKeys.single().fingerprint)
        assertEquals(1, verified.snapshot.services.size)
        assertEquals("www.demo.example", verified.snapshot.sites.single().customHostname)
        assertEquals(5, verified.snapshot.schemaVersion)
    }

    @Test
    fun `duplicate custom hostnames in backup are rejected`() {
        val snapshot = PlatformBackupSnapshot(
            exportedAt = 1_000L,
            sshSettings = SshSettings(port = 8022),
            sshAuthorizedKeys = emptyList(),
            services = emptyList(),
            sites = listOf(
                SiteBackupEntry(1, "site-one", 18000, ProjectType.STATIC, null, SiteStatus.STOPPED, 1, "www.example.com"),
                SiteBackupEntry(2, "site-two", 18001, ProjectType.STATIC, null, SiteStatus.STOPPED, 2, "www.example.com"),
            ),
            recentDeployments = emptyList(),
            recentActivity = emptyList(),
        )
        val json = BackupCodec.encodeEnvelopeJson(BackupCodec.createEnvelope(snapshot, secretKey = null))

        val failure = runCatching { BackupCodec.decodeAndVerify(json, secretKey = null, requireSignature = false) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `tampered payload or wrong secret fails hmac verification`() {
        val snapshot = PlatformBackupSnapshot(
            exportedAt = 1_000L,
            sshSettings = SshSettings(port = 8022),
            sshAuthorizedKeys = emptyList(),
            services = emptyList(),
            sites = emptyList(),
            recentDeployments = emptyList(),
            recentActivity = emptyList(),
        )
        val envelope = BackupCodec.createEnvelope(snapshot, "a".repeat(64))
        val json = BackupCodec.encodeEnvelopeJson(envelope)

        // Wrong secret
        assertTrue(
            runCatching {
                BackupCodec.decodeAndVerify(json, secretKey = "b".repeat(64), requireSignature = true)
            }.isFailure,
        )

        // Tampered payload
        val tampered = BackupCodec.encodeEnvelopeJson(
            envelope.copy(payloadJson = envelope.payloadJson.replace("8022", "8023")),
        )
        assertTrue(
            runCatching {
                BackupCodec.decodeAndVerify(tampered, secretKey = "a".repeat(64), requireSignature = false)
            }.isFailure,
        )
    }

    @Test
    fun `reserved ssh port in backup is rejected`() {
        val snapshot = PlatformBackupSnapshot(
            exportedAt = 1_000L,
            sshSettings = SshSettings(port = 8080),
            sshAuthorizedKeys = emptyList(),
            services = emptyList(),
            sites = emptyList(),
            recentDeployments = emptyList(),
            recentActivity = emptyList(),
        )
        val json = BackupCodec.encodeEnvelopeJson(BackupCodec.createEnvelope(snapshot, secretKey = null))
        assertTrue(
            runCatching {
                BackupCodec.decodeAndVerify(json, secretKey = null, requireSignature = false)
            }.isFailure,
        )
    }

    private fun sampleEd25519Key(comment: String): String {
        val typeBytes = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val keyBytes = ByteArray(32) { (it + 7).toByte() }
        val buffer = ByteArray(4 + typeBytes.size + keyBytes.size)
        buffer[0] = (typeBytes.size shr 24).toByte()
        buffer[1] = (typeBytes.size shr 16).toByte()
        buffer[2] = (typeBytes.size shr 8).toByte()
        buffer[3] = typeBytes.size.toByte()
        typeBytes.copyInto(buffer, 4)
        keyBytes.copyInto(buffer, 4 + typeBytes.size)
        val blob = Base64.getEncoder().encodeToString(buffer)
        return "ssh-ed25519 $blob $comment"
    }
}
