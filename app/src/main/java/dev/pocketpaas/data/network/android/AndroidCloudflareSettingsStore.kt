package dev.pocketpaas.data.network.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import dev.pocketpaas.domain.network.CloudflareSettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypts the Cloudflare API token and remote Tunnel run token with an Android Keystore AES-GCM key. */
@Singleton
class AndroidCloudflareSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CloudflareSettingsStore {
    private val baseFile = File(context.noBackupFilesDir, "cloudflare-settings.bin")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()

    override suspend fun read(): CloudflareAccountSettings? = mutex.withLock {
        withContext(ioDispatcher) {
            val input = try {
                file.openRead()
            } catch (_: FileNotFoundException) {
                if (hasRecoveryFile()) damaged()
                return@withContext null
            }
            val encrypted = input.use { readBounded(it) }
            val plaintext = decrypt(encrypted)
            val stored = try {
                gson.fromJson(String(plaintext, Charsets.UTF_8), PersistedCloudflareSettings::class.java)
            } catch (failure: RuntimeException) {
                throw IOException("Cloudflare settings are damaged; reconnect the account.", failure)
            } ?: damaged()
            val settings = stored.toDomain()
            validate(settings)
            settings
        }
    }

    override suspend fun write(settings: CloudflareAccountSettings) = mutex.withLock {
        withContext(ioDispatcher) {
            validate(settings)
            val plaintext = gson.toJson(PersistedCloudflareSettings.from(settings)).toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(plaintext)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(ioDispatcher) { file.delete() }
    }

    private fun validate(settings: CloudflareAccountSettings) {
        require(settings.apiToken.length in 20..512 && settings.apiToken.none(Char::isISOControl) &&
            settings.apiToken.none(Char::isWhitespace)) { "Cloudflare API token is invalid." }
        require(ACCOUNT_ID.matches(settings.accountId)) { "Cloudflare Account ID is invalid." }
        require(RESOURCE_ID.matches(settings.zoneId)) { "Cloudflare Zone ID is invalid." }
        require(SiteInput.normalizeCustomHostname(settings.zoneName) == settings.zoneName) {
            "Cloudflare zone name is not canonical."
        }
        if (settings.tunnelId != null) require(TUNNEL_ID.matches(settings.tunnelId)) {
            "Cloudflare Tunnel ID is invalid."
        }
        if (settings.tunnelName != null) require(TUNNEL_NAME.matches(settings.tunnelName)) {
            "Cloudflare Tunnel name is invalid."
        }
        if (settings.tunnelToken != null) {
            require(settings.tunnelToken.length in 20..MAXIMUM_TUNNEL_TOKEN_CHARACTERS &&
                settings.tunnelToken.none { it.isWhitespace() || it.isISOControl() }
            ) { "Cloudflare Tunnel token is invalid." }
        }
        require(settings.managedHostnames.distinct().size == settings.managedHostnames.size) {
            "Cloudflare managed hostname list is invalid."
        }
        settings.managedHostnames.forEach { hostname ->
            require(SiteInput.normalizeCustomHostname(hostname) == hostname &&
                SiteInput.isHostnameInZone(hostname, settings.zoneName)
            ) { "A managed hostname is outside the configured Cloudflare zone." }
        }
        require(settings.tunnelId == null || settings.tunnelName != null) {
            "Cloudflare Tunnel name is missing."
        }
        require(settings.tunnelToken == null || (settings.tunnelId != null && settings.tunnelName != null)) {
            "Cloudflare Tunnel token has no Tunnel identity."
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Android Keystore returned an invalid encryption IV." }
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(FILE_VERSION.toByte()) + iv + ciphertext
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size in (1 + GCM_IV_BYTES + GCM_TAG_BYTES)..MAXIMUM_FILE_BYTES &&
            (encrypted[0].toInt() and 0xff) == FILE_VERSION
        ) { "Cloudflare settings file is damaged." }
        val iv = encrypted.copyOfRange(1, 1 + GCM_IV_BYTES)
        val ciphertext = encrypted.copyOfRange(1 + GCM_IV_BYTES, encrypted.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (failure: Exception) {
            throw IOException("Cloudflare settings could not be decrypted; reconnect the account.", failure)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > MAXIMUM_FILE_BYTES) damaged()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun hasRecoveryFile(): Boolean = baseFile.exists() || File(baseFile.path + ".bak").exists() ||
        File(baseFile.path + ".new").exists()

    private fun damaged(): Nothing = throw IOException("Cloudflare settings are damaged; reconnect the account.")

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.pocketpaas.cloudflare.settings.aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_VERSION = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MAXIMUM_TUNNEL_TOKEN_CHARACTERS = 4_096
        const val MAXIMUM_FILE_BYTES = 8_192
        val ACCOUNT_ID = Regex("[a-f0-9]{32}")
        val RESOURCE_ID = Regex("[a-f0-9]{32}")
        val TUNNEL_ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        val TUNNEL_NAME = Regex("pocketpaas-[a-f0-9]{12}")
    }
}

private data class PersistedCloudflareSettings(
    @field:SerializedName("apiToken") val apiToken: String?,
    @field:SerializedName("accountId") val accountId: String?,
    @field:SerializedName("zoneId") val zoneId: String?,
    @field:SerializedName("zoneName") val zoneName: String?,
    @field:SerializedName("tunnelId") val tunnelId: String?,
    @field:SerializedName("tunnelName") val tunnelName: String?,
    @field:SerializedName("tunnelToken") val tunnelToken: String?,
    @field:SerializedName("managedHostnames") val managedHostnames: List<String>?,
) {
    override fun toString(): String =
        "PersistedCloudflareSettings(accountId=$accountId, zoneName=$zoneName, " +
            "managedHostnames=$managedHostnames, apiToken=[redacted], tunnelToken=[redacted])"

    fun toDomain(): CloudflareAccountSettings = CloudflareAccountSettings(
        apiToken = checkNotNull(apiToken),
        accountId = checkNotNull(accountId),
        zoneId = checkNotNull(zoneId),
        zoneName = checkNotNull(zoneName),
        tunnelId = tunnelId,
        tunnelName = tunnelName,
        tunnelToken = tunnelToken,
        managedHostnames = managedHostnames.orEmpty(),
    )

    companion object {
        fun from(settings: CloudflareAccountSettings) = PersistedCloudflareSettings(
            apiToken = settings.apiToken,
            accountId = settings.accountId,
            zoneId = settings.zoneId,
            zoneName = settings.zoneName,
            tunnelId = settings.tunnelId,
            tunnelName = settings.tunnelName,
            tunnelToken = settings.tunnelToken,
            managedHostnames = settings.managedHostnames,
        )
    }
}
