package dev.pocketpaas.data.network

import com.google.gson.JsonParser
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareApiClientTest {
    @Test
    fun credentialVerificationBindsTheActiveZoneToTheSuppliedAccount() = runBlocking {
        val transport = RecordingTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","status":"active","account":{"id":"$ACCOUNT_ID"}}]"""),
        )
        val zoneId = CloudflareApiClient(transport).verifyCredentials(TOKEN, ACCOUNT_ID, "example.com")

        assertEquals(ZONE_ID, zoneId)
        assertEquals("GET", transport.requests[0].method)
        assertEquals("/user/tokens/verify", transport.requests[0].path)
        assertEquals(TOKEN, transport.requests[0].apiToken)
        assertEquals("/zones?name=example.com&status=active&per_page=50", transport.requests[1].path)
    }

    @Test
    fun credentialVerificationRejectsZoneOwnedByAnotherAccount() = runBlocking {
        val transport = RecordingTransport(
            success("""{"status":"active"}"""),
            success("""[{"id":"$ZONE_ID","name":"example.com","status":"active","account":{"id":"${"f".repeat(32)}"}}]"""),
        )

        val failure = runCatching {
            CloudflareApiClient(transport).verifyCredentials(TOKEN, ACCOUNT_ID, "example.com")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun missingTunnelIsCreatedWithCloudflareManagedConfiguration() = runBlocking {
        val transport = RecordingTransport(
            success("""[]"""),
            success("""{"id":"$TUNNEL_ID","name":"pocketpaas-abcdef123456"}"""),
        )
        val id = CloudflareApiClient(transport).findOrCreateTunnel(settings())

        assertEquals(TUNNEL_ID, id)
        assertEquals("GET", transport.requests[0].method)
        assertTrue(transport.requests[0].path.contains("name=pocketpaas-abcdef123456"))
        assertEquals("POST", transport.requests[1].method)
        val body = JsonParser.parseString(checkNotNull(transport.requests[1].body)).asJsonObject
        assertEquals("cloudflare", body.get("config_src").asString)
        assertEquals("pocketpaas-abcdef123456", body.get("name").asString)
        assertFalse(body.has("tunnel_secret"))
        assertFalse(transport.requests.any { it.path.contains(TOKEN) })
    }

    @Test
    fun ingressHasExactHostRulesAndAnExplicit404CatchAll() = runBlocking {
        val transport = RecordingTransport(success("{}"))
        val site = sampleSite().copy(customHostname = "www.example.com")
        CloudflareApiClient(transport).updateTunnelIngress(settings(tunnelId = TUNNEL_ID), listOf(site), ORIGIN)

        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/accounts/$ACCOUNT_ID/cfd_tunnel/$TUNNEL_ID/configurations", request.path)
        val ingress = JsonParser.parseString(checkNotNull(request.body)).asJsonObject
            .getAsJsonObject("config").getAsJsonArray("ingress")
        assertEquals(2, ingress.size())
        val host = ingress[0].asJsonObject
        assertEquals("www.example.com", host.get("hostname").asString)
        assertEquals(ORIGIN, host.get("service").asString)
        assertEquals("www.example.com", host.getAsJsonObject("originRequest").get("httpHostHeader").asString)
        assertEquals("http_status:404", ingress[1].asJsonObject.get("service").asString)
    }

    @Test
    fun dnsCreationUsesProxiedCnameAndDeletingStaleHostnamesOnlyRemovesOurTarget() = runBlocking {
        val transport = RecordingTransport(
            success("""[]"""),
            success("""{"id":"$DNS_RECORD_ID"}"""),
            success("""[{"id":"$DNS_RECORD_ID","type":"CNAME","name":"old.example.com","content":"$TUNNEL_ID.cfargotunnel.com","proxied":true}]"""),
            success("""{"id":"$DNS_RECORD_ID"}"""),
        )
        val managed = CloudflareApiClient(transport).syncDnsRecords(
            settings(tunnelId = TUNNEL_ID, managedHostnames = listOf("old.example.com")),
            setOf("www.example.com"),
            TUNNEL_ID,
        )

        assertEquals(listOf("www.example.com"), managed)
        assertEquals(listOf("GET", "POST", "GET", "DELETE"), transport.requests.map { it.method })
        val created = JsonParser.parseString(checkNotNull(transport.requests[1].body)).asJsonObject
        assertEquals("CNAME", created.get("type").asString)
        assertEquals("www.example.com", created.get("name").asString)
        assertEquals("$TUNNEL_ID.cfargotunnel.com", created.get("content").asString)
        assertTrue(created.get("proxied").asBoolean)
        assertEquals(1, created.get("ttl").asInt)
        assertTrue(transport.requests[3].path.endsWith("/$DNS_RECORD_ID"))
    }

    @Test
    fun dnsConflictIsRejectedWithoutOverwritingARecords() = runBlocking {
        val transport = RecordingTransport(
            success("""[{"id":"$DNS_RECORD_ID","type":"A","name":"www.example.com","content":"192.0.2.7","proxied":false}]"""),
        )

        val failure = runCatching {
            CloudflareApiClient(transport).syncDnsRecords(settings(tunnelId = TUNNEL_ID), setOf("www.example.com"), TUNNEL_ID)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun existingTargetCnameIsProxiedAndIdempotentlyReused() = runBlocking {
        val transport = RecordingTransport(
            success("""[{"id":"$DNS_RECORD_ID","type":"CNAME","name":"www.example.com","content":"$TUNNEL_ID.cfargotunnel.com","proxied":false}]"""),
            success("""{"id":"$DNS_RECORD_ID"}"""),
        )
        CloudflareApiClient(transport).syncDnsRecords(settings(tunnelId = TUNNEL_ID), setOf("www.example.com"), TUNNEL_ID)
        assertEquals("PUT", transport.requests[1].method)
        val body = JsonParser.parseString(checkNotNull(transport.requests[1].body)).asJsonObject
        assertTrue(body.get("proxied").asBoolean)
    }

    @Test
    fun apiErrorsRedactATokenIfCloudflareEchoesIt() = runBlocking {
        val transport = RecordingTransport(
            CloudflareHttpResponse(
                403,
                """{"success":false,"errors":[{"message":"Rejected token $TOKEN"}],"result":null}""",
            ),
        )
        val failure = runCatching { CloudflareApiClient(transport).verifyCredentials(TOKEN, ACCOUNT_ID, "example.com") }
            .exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure?.message.orEmpty().contains(TOKEN))
        assertTrue(failure?.message.orEmpty().contains("[redacted]"))
    }

    @Test
    fun remoteManagedTunnelTokenComesFromTheTokenEndpoint() = runBlocking {
        val transport = RecordingTransport(success("\"$TUNNEL_TOKEN\""))

        val tunnelToken = CloudflareApiClient(transport).getTunnelToken(settings(tunnelId = TUNNEL_ID))

        assertEquals(TUNNEL_TOKEN, tunnelToken)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("/accounts/$ACCOUNT_ID/cfd_tunnel/$TUNNEL_ID/token", transport.requests.single().path)
        assertEquals(TOKEN, transport.requests.single().apiToken)
    }

    @Test
    fun invalidRemoteTunnelTokenIsRejected() = runBlocking {
        val transport = RecordingTransport(success("\"too-short\""))

        val failure = runCatching {
            CloudflareApiClient(transport).getTunnelToken(settings(tunnelId = TUNNEL_ID))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun nonLoopbackTunnelOriginIsRejectedBeforeAnyApiRequest() = runBlocking {
        val transport = RecordingTransport()
        val failure = runCatching {
            CloudflareApiClient(transport).updateTunnelIngress(settings(tunnelId = TUNNEL_ID), emptyList(), "http://evil.example:8081")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(transport.requests.isEmpty())

        val invalidPort = runCatching {
            CloudflareApiClient(transport).updateTunnelIngress(
                settings(tunnelId = TUNNEL_ID), emptyList(), "http://127.0.0.1:99999",
            )
        }.exceptionOrNull()
        assertTrue(invalidPort is IllegalArgumentException)
        assertTrue(transport.requests.isEmpty())
    }

    private class RecordingTransport(vararg responses: CloudflareHttpResponse) : CloudflareHttpTransport {
        val requests = mutableListOf<Request>()
        private val responses = ArrayDeque(responses.toList())

        override suspend fun execute(method: String, path: String, apiToken: String, body: String?): CloudflareHttpResponse {
            requests += Request(method, path, apiToken, body)
            if (responses.isEmpty()) throw IOException("No fake response configured")
            return responses.removeFirst()
        }
    }

    private data class Request(val method: String, val path: String, val apiToken: String, val body: String?)

    private fun settings(
        tunnelId: String? = null,
        managedHostnames: List<String> = emptyList(),
    ) = CloudflareAccountSettings(
        apiToken = TOKEN,
        accountId = ACCOUNT_ID,
        zoneId = ZONE_ID,
        zoneName = "example.com",
        tunnelId = tunnelId,
        tunnelName = "pocketpaas-abcdef123456",
        tunnelToken = TUNNEL_TOKEN.takeIf { tunnelId != null },
        managedHostnames = managedHostnames,
    )

    private fun sampleSite() = Site(
        id = 1,
        domainName = "my-app",
        internalPort = 18001,
        projectType = ProjectType.STATIC,
        processId = null,
        publicUrl = null,
        status = SiteStatus.STOPPED,
        logs = "",
        createdAt = 1,
    )

    private fun success(result: String) = CloudflareHttpResponse(
        200,
        """{"success":true,"errors":[],"result":$result}""",
    )

    private companion object {
        val TOKEN = "a".repeat(40)
        val ACCOUNT_ID = "b".repeat(32)
        val ZONE_ID = "c".repeat(32)
        const val TUNNEL_ID = "12345678-1234-1234-1234-123456789abc"
        const val TUNNEL_TOKEN = "eyJhIjoiY2xvdWRmbGFyZS10dW5uZWwtdGVzdC10b2tlbiJ9"
        val DNS_RECORD_ID = "d".repeat(32)
        const val ORIGIN = "http://127.0.0.1:8081"
    }
}
