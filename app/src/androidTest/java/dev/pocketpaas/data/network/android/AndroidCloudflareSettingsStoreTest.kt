package dev.pocketpaas.data.network.android

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidCloudflareSettingsStoreTest {
    @Test
    fun apiTokenAndRemoteTunnelTokenAreEncryptedInNoBackupStorageAndCanBeReadBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCloudflareSettingsStore(context, Gson(), Dispatchers.IO)
        val token = "cloudflare-test-token-" + "a".repeat(40)
        val tunnelToken = "eyJhIjoiY2xvdWRmbGFyZS10dW5uZWwtdGVzdC10b2tlbiJ9"
        val settings = CloudflareAccountSettings(
            apiToken = token,
            accountId = "b".repeat(32),
            zoneId = "c".repeat(32),
            zoneName = "example.com",
            tunnelId = "12345678-1234-1234-1234-123456789abc",
            tunnelName = "pocketpaas-abcdef123456",
            tunnelToken = tunnelToken,
            managedHostnames = listOf("www.example.com"),
        )
        try {
            store.clear()
            store.write(settings)

            val encryptedFile = context.noBackupFilesDir.resolve("cloudflare-settings.bin")
            val onDisk = encryptedFile.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(onDisk.contains(token))
            assertFalse(onDisk.contains(tunnelToken))
            assertEquals(settings, store.read())
        } finally {
            store.clear()
        }
    }
}
