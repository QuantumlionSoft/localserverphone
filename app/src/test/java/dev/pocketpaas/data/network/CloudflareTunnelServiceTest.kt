package dev.pocketpaas.data.network

import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import dev.pocketpaas.domain.network.CloudflareSettingsStore
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareTunnelServiceTest {
    @Test
    fun configureVerifiesAndStoresSecretsWithoutPublishingThemInUiState() = runBlocking {
        val transport = FakeTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","account":{"id":"$ACCOUNT_ID"}}]"""),
        )
        val store = MemorySettingsStore()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            val state = service.configure(TOKEN, ACCOUNT_ID, "EXAMPLE.COM.")
            assertTrue(state.configured)
            assertEquals("example.com", state.zoneName)
            assertEquals(ACCOUNT_ID, state.accountId)
            assertNull(state.tunnelId)
            assertEquals(TOKEN, store.value?.apiToken)
            assertFalse(service.connectionState.value.toString().contains(TOKEN))
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun prepareCreatesNamedTunnelConfigAndProxiedDnsThenPersistsTunnelIdentity() = runBlocking {
        val transport = FakeTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","account":{"id":"$ACCOUNT_ID"}}]"""),
            success("""[]"""),
            success("""{"id":"$TUNNEL_ID","name":"pocketpaas-abcdef123456"}"""),
            success("\"$TUNNEL_TOKEN\""),
            success("{}"),
            success("""[]"""),
            success("""{"id":"$DNS_ID"}"""),
        )
        val store = MemorySettingsStore()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            service.configure(TOKEN, ACCOUNT_ID, "example.com")
            val credentials = service.prepare(listOf(site("www.example.com")), ORIGIN)

            assertEquals(ACCOUNT_ID, credentials.accountId)
            assertEquals(TUNNEL_ID, credentials.tunnelId)
            assertTrue(credentials.tunnelName.matches(Regex("pocketpaas-[a-f0-9]{12}")))
            assertEquals(credentials.tunnelName, store.value?.tunnelName)
            assertEquals(TUNNEL_TOKEN, credentials.tunnelToken)
            assertFalse(credentials.toString().contains(credentials.tunnelToken))
            assertFalse(store.value.toString().contains(TOKEN))
            assertFalse(store.value.toString().contains(TUNNEL_TOKEN))
            assertEquals(TUNNEL_ID, store.value?.tunnelId)
            assertEquals(TUNNEL_TOKEN, store.value?.tunnelToken)
            assertEquals(listOf("www.example.com"), store.value?.managedHostnames)
            assertEquals(TUNNEL_ID, service.connectionState.value.tunnelId)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun reconcileRefreshesAndPersistsARotatedCloudflareRunToken() = runBlocking {
        val rotatedToken = "eyJhIjoiY2xvdWRmbGFyZS1yb3RhdGVkLXR1bm5lbC10b2tlbiJ9"
        val transport = FakeTransport(
            success("\"$rotatedToken\""),
            success("{}"),
        )
        val store = MemorySettingsStore().apply {
            value = CloudflareAccountSettings(
                apiToken = TOKEN,
                accountId = ACCOUNT_ID,
                zoneId = ZONE_ID,
                zoneName = "example.com",
                tunnelId = TUNNEL_ID,
                tunnelName = "pocketpaas-abcdef123456",
                tunnelToken = TUNNEL_TOKEN,
            )
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            service.reconcile(emptyList(), ORIGIN)

            assertEquals(rotatedToken, store.value?.tunnelToken)
            assertEquals("/accounts/$ACCOUNT_ID/cfd_tunnel/$TUNNEL_ID/token", transport.requests[0].path)
            assertEquals("PUT", transport.requests[1].method)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun reconcileRemovesOnlyPreviouslyManagedDnsAndClearsTheRemoteIngress() = runBlocking {
        val transport = FakeTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","account":{"id":"$ACCOUNT_ID"}}]"""),
            success("""[]"""),
            success("""{"id":"$TUNNEL_ID","name":"pocketpaas-abcdef123456"}"""),
            success("\"$TUNNEL_TOKEN\""),
            success("{}"),
            success("""[]"""),
            success("""{"id":"$DNS_ID"}"""),
            success("\"$TUNNEL_TOKEN\""),
            success("{}"),
            success("""[{"id":"$DNS_ID","type":"CNAME","name":"www.example.com","content":"$TUNNEL_ID.cfargotunnel.com","proxied":true}]"""),
            success("{}"),
        )
        val store = MemorySettingsStore()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            service.configure(TOKEN, ACCOUNT_ID, "example.com")
            service.prepare(listOf(site("www.example.com")), ORIGIN)
            service.reconcile(emptyList(), ORIGIN)

            assertEquals(emptyList<String>(), store.value?.managedHostnames)
            assertEquals("PUT", transport.requests[9].method)
            assertTrue(transport.requests[9].body.orEmpty().contains("http_status:404"))
            assertEquals("DELETE", transport.requests[11].method)
            assertEquals(TUNNEL_ID, store.value?.tunnelId)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun interruptedHostnameRemovalRetainsCleanupIntentForTheNextReconciliation() = runBlocking {
        val transport = FakeTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","account":{"id":"$ACCOUNT_ID"}}]"""),
            success("""[]"""),
            success("""{"id":"$TUNNEL_ID","name":"pocketpaas-abcdef123456"}"""),
            success("\"$TUNNEL_TOKEN\""),
            success("{}"),
            success("""[]"""),
            success("""{"id":"$DNS_ID"}"""),
            success("\"$TUNNEL_TOKEN\""),
            success("{}"),
        )
        val store = MemorySettingsStore()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            service.configure(TOKEN, ACCOUNT_ID, "example.com")
            service.prepare(listOf(site("www.example.com")), ORIGIN)
            transport.failAtRequest = transport.requests.size + 3

            val interrupted = runCatching { service.reconcile(emptyList(), ORIGIN) }.exceptionOrNull()
            assertNotNull(interrupted)
            assertEquals(listOf("www.example.com"), store.value?.managedHostnames)

            transport.failAtRequest = null
            transport.enqueue(success("\"$TUNNEL_TOKEN\""))
            transport.enqueue(success("{}"))
            transport.enqueue(success("""[{"id":"$DNS_ID","type":"CNAME","name":"www.example.com","content":"$TUNNEL_ID.cfargotunnel.com","proxied":true}]"""))
            transport.enqueue(success("{}"))
            service.reconcile(emptyList(), ORIGIN)

            assertEquals(emptyList<String>(), store.value?.managedHostnames)
            assertEquals("DELETE", transport.requests.last().method)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun outOfZoneHostnameIsRejectedBeforeCreatingRemoteResources() = runBlocking {
        val transport = FakeTransport()
        val store = MemorySettingsStore().apply {
            value = CloudflareAccountSettings(TOKEN, ACCOUNT_ID, ZONE_ID, "example.com")
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = CloudflareTunnelService(store, CloudflareApiClient(transport), owner)
        try {
            val failure = runCatching { service.prepare(listOf(site("evil.test")), ORIGIN) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("outside the configured Cloudflare zone"))
            assertTrue(transport.requests.isEmpty())
            assertNull(store.value?.tunnelId)
        } finally {
            owner.cancel()
        }
    }

    private class MemorySettingsStore : CloudflareSettingsStore {
        @Volatile var value: CloudflareAccountSettings? = null
        override suspend fun read(): CloudflareAccountSettings? = value
        override suspend fun write(settings: CloudflareAccountSettings) { value = settings }
        override suspend fun clear() { value = null }
    }

    private class FakeTransport(vararg responses: CloudflareHttpResponse) : CloudflareHttpTransport {
        val requests = mutableListOf<Request>()
        private val responses = ArrayDeque(responses.toList())
        var failAtRequest: Int? = null

        fun enqueue(response: CloudflareHttpResponse) { responses.addLast(response) }

        override suspend fun execute(method: String, path: String, apiToken: String, body: String?): CloudflareHttpResponse {
            requests += Request(method, path, body)
            if (requests.size == failAtRequest) throw IOException("Simulated interrupted Cloudflare request")
            if (responses.isEmpty()) throw IOException("No fake response configured")
            return responses.removeFirst()
        }
    }

    private data class Request(val method: String, val path: String, val body: String?)

    private fun site(hostname: String) = Site(
        id = 1,
        domainName = "my-app",
        internalPort = 18001,
        projectType = ProjectType.STATIC,
        processId = null,
        publicUrl = null,
        status = SiteStatus.STOPPED,
        logs = "",
        createdAt = 1,
        customHostname = hostname,
    )

    private fun success(result: String) = CloudflareHttpResponse(200, """{"success":true,"result":$result}""")

    private companion object {
        val TOKEN = "a".repeat(40)
        val ACCOUNT_ID = "b".repeat(32)
        val ZONE_ID = "c".repeat(32)
        const val TUNNEL_ID = "12345678-1234-1234-1234-123456789abc"
        const val TUNNEL_TOKEN = "eyJhIjoiY2xvdWRmbGFyZS10dW5uZWwtdGVzdC10b2tlbiJ9"
        val DNS_ID = "d".repeat(32)
        const val ORIGIN = "http://127.0.0.1:8081"
    }
}
