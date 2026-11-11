package dev.pocketpaas.domain.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import dev.pocketpaas.core.config.SshPolicy
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.services.ServiceCatalog
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshKeyFormat
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BackupCodec {
    const val MAXIMUM_BACKUP_JSON_CHARACTERS = 512 * 1024

    private val compactGson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val prettyGson = GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()

    data class VerifiedSnapshot(
        val snapshot: PlatformBackupSnapshot,
        val signatureVerified: Boolean,
    )

    fun createEnvelope(
        snapshot: PlatformBackupSnapshot,
        secretKey: String?,
    ): SignedBackupEnvelope {
        val payloadJson = compactGson.toJson(snapshot)
        val trimmedSecret = secretKey?.trim()?.takeIf { it.isNotEmpty() }
        val signature = trimmedSecret?.let { hmacSha256Hex(it, canonicalSigningInput(snapshot.exportedAt, payloadJson)) }
        return SignedBackupEnvelope(
            version = SignedBackupEnvelope.ENVELOPE_VERSION,
            algorithm = if (signature != null) SignedBackupEnvelope.ALGORITHM_HMAC_SHA256 else SignedBackupEnvelope.ALGORITHM_UNSIGNED,
            exportedAt = snapshot.exportedAt,
            payloadJson = payloadJson,
            signatureHex = signature,
        )
    }

    fun encodeEnvelopeJson(envelope: SignedBackupEnvelope): String = prettyGson.toJson(envelope)

    fun decodeAndVerify(
        rawJson: String,
        secretKey: String?,
        requireSignature: Boolean,
        sshPolicy: SshPolicy = SshPolicy(),
    ): VerifiedSnapshot {
        val trimmed = rawJson.trim()
        require(trimmed.isNotEmpty()) { "Yedek verisi boş olamaz." }
        require(trimmed.length <= MAXIMUM_BACKUP_JSON_CHARACTERS) {
            "Yedek verisi izin verilen boyut sınırını (${MAXIMUM_BACKUP_JSON_CHARACTERS} karakter) aşıyor."
        }
        val envelope = try {
            compactGson.fromJson(trimmed, SignedBackupEnvelope::class.java)
        } catch (failure: JsonSyntaxException) {
            throw IllegalArgumentException("Yedek JSON zarfı okunamadı.", failure)
        } ?: throw IllegalArgumentException("Yedek JSON zarfı geçersiz.")

        require(envelope.version == SignedBackupEnvelope.ENVELOPE_VERSION) {
            "Desteklenmeyen yedek zarfı sürümü: ${envelope.version}"
        }
        val payloadJson = envelope.payloadJson
        require(!payloadJson.isNullOrBlank()) { "Yedek içeriği (payloadJson) eksik." }
        require(payloadJson.length <= MAXIMUM_BACKUP_JSON_CHARACTERS) { "Yedek içeriği boyutu sınırı aşıyor." }

        val signatureVerified = verifyEnvelopeSignature(envelope, secretKey, requireSignature)

        val snapshot = try {
            compactGson.fromJson(payloadJson, PlatformBackupSnapshot::class.java)
        } catch (failure: JsonSyntaxException) {
            throw IllegalArgumentException("Yedek veri şeması okunamadı.", failure)
        } ?: throw IllegalArgumentException("Yedek veri şeması boş.")

        val sanitizedSnapshot = validateAndNormalizeSnapshot(snapshot, sshPolicy)
        return VerifiedSnapshot(sanitizedSnapshot, signatureVerified)
    }

    private fun verifyEnvelopeSignature(
        envelope: SignedBackupEnvelope,
        secretKey: String?,
        requireSignature: Boolean,
    ): Boolean {
        val providedSig = envelope.signatureHex?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (providedSig == null) {
            require(!requireSignature) { "İmzalı yedek zorunlu ancak yedek zarfında HMAC imzası bulunmuyor." }
            return false
        }
        require(envelope.algorithm == SignedBackupEnvelope.ALGORITHM_HMAC_SHA256) {
            "Desteklenmeyen imza algoritması: ${envelope.algorithm}"
        }
        require(HEX_64.matches(providedSig)) { "Yedek imzası geçerli bir SHA-256 hex değeri değil." }
        val key = secretKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("İmzalı yedeği doğrulamak için API anahtarı kullanılamıyor.")
        val expectedSig = hmacSha256Hex(key, canonicalSigningInput(envelope.exportedAt, envelope.payloadJson))
        val matches = MessageDigest.isEqual(
            expectedSig.toByteArray(Charsets.US_ASCII),
            providedSig.toByteArray(Charsets.US_ASCII),
        )
        require(matches) { "Yedek HMAC-SHA256 imzası doğrulanamadı; veri değiştirilmiş veya farklı anahtarla imzalanmış." }
        return true
    }

    private fun validateAndNormalizeSnapshot(
        snapshot: PlatformBackupSnapshot,
        sshPolicy: SshPolicy,
    ): PlatformBackupSnapshot {
        require(snapshot.schemaVersion in 1..PlatformBackupSnapshot.CURRENT_SCHEMA_VERSION) {
            "Desteklenmeyen veritabanı şema sürümü: ${snapshot.schemaVersion}"
        }
        val settings = snapshot.sshSettings
        require(sshPolicy.isAllowedPort(settings.port)) {
            "Yedekteki SSH portu (${settings.port}) izin verilen aralıkta değil."
        }

        val normalizedKeys = mutableListOf<SshAuthorizedKey>()
        val seenFingerprints = HashSet<String>()
        val rawKeys = snapshot.sshAuthorizedKeys
        require(rawKeys.size <= sshPolicy.maximumAuthorizedKeys) {
            "Yedekteki SSH anahtar sayısı sınırı (${sshPolicy.maximumAuthorizedKeys}) aşıyor."
        }
        for (key in rawKeys) {
            val cleanName = SshKeyFormat.validateName(key.name)
            val parsed = SshKeyFormat.parse(key.publicKey)
            if (seenFingerprints.add(parsed.fingerprint)) {
                normalizedKeys += SshAuthorizedKey(
                    id = key.id.coerceAtLeast(0L),
                    name = cleanName,
                    publicKey = parsed.line,
                    fingerprint = parsed.fingerprint,
                    enabled = key.enabled,
                    createdAt = key.createdAt.coerceAtLeast(0L),
                )
            }
        }

        val normalizedSites = snapshot.sites.map { site ->
            val hostname = site.customHostname?.let(SiteInput::normalizeCustomHostname)
            require(site.customHostname == null || hostname == site.customHostname) {
                "Yedekteki özel hostname kanonik biçimde değil."
            }
            site.copy(customHostname = hostname)
        }
        val customHostnames = normalizedSites.mapNotNull { it.customHostname }
        require(customHostnames.distinct().size == customHostnames.size) {
            "Yedekte bir özel hostname birden fazla siteye atanmış."
        }
        val validServices = snapshot.services.filter { ServiceCatalog.find(it.serviceKey) != null }
        return snapshot.copy(
            sshSettings = settings.copy(enabled = false), // Güvenlik: geri yükleme sonrası SSH kendiliğinden başlamaz
            sshAuthorizedKeys = normalizedKeys,
            services = validServices,
            sites = normalizedSites,
        )
    }

    internal fun canonicalSigningInput(exportedAt: Long, payloadJson: String): String =
        "v1:$exportedAt:$payloadJson"

    internal fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xff
            chars[i * 2] = hex[b ushr 4]
            chars[i * 2 + 1] = hex[b and 0x0f]
        }
        return String(chars)
    }

    private val HEX_64 = Regex("[a-f0-9]{64}")
}
