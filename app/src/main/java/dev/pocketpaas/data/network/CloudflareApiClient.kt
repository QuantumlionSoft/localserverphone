package dev.pocketpaas.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pocketpaas.core.config.NetworkPolicy
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.network.CloudflareAccountSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudflareHttpResponse(val statusCode: Int, val body: String)

interface CloudflareHttpTransport {
    suspend fun execute(method: String, path: String, apiToken: String, body: String? = null): CloudflareHttpResponse
}

/** A bounded HTTPS-only transport. Redirects are disabled so bearer tokens never follow a redirect. */
@Singleton
class UrlConnectionCloudflareTransport @Inject constructor(
    private val policy: NetworkPolicy,
) : CloudflareHttpTransport {
    override suspend fun execute(
        method: String,
        path: String,
        apiToken: String,
        body: String?,
    ): CloudflareHttpResponse = withContext(Dispatchers.IO) {
        require(method in setOf("GET", "POST", "PUT", "DELETE"))
        require(path.startsWith('/') && path.none { it.isISOControl() || it == '#' })
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(API_ROOT + path).openConnection() as HttpURLConnection
            connection = opened
            opened.apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = policy.cloudflareConnectTimeoutMillis
                readTimeout = policy.cloudflareReadTimeoutMillis
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PocketPaaS-Android")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                }
            }
            if (body != null) opened.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = opened.responseCode
            val stream = if (status in 200..299) opened.inputStream else opened.errorStream
            val response = stream?.use { input ->
                val bytes = ByteArray(policy.cloudflareMaximumResponseBytes + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                if (count > policy.cloudflareMaximumResponseBytes) {
                    throw IOException("Cloudflare returned an oversized API response.")
                }
                String(bytes, 0, count, Charsets.UTF_8)
            }.orEmpty()
            CloudflareHttpResponse(status, response)
        } catch (failure: IOException) {
            throw failure
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val API_ROOT = "https://api.cloudflare.com/client/v4"
    }
}

/** Small, testable Cloudflare API surface used for Tunnel management and DNS publication. */
@Singleton
class CloudflareApiClient @Inject constructor(
    private val transport: CloudflareHttpTransport,
) {
    suspend fun verifyCredentials(apiToken: String, accountId: String, zoneName: String): String {
        val verification = resultObject(request("GET", "/user/tokens/verify", apiToken), "token verification")
        require(verification.stringOrNull("status") == "active") {
            "Cloudflare API token is not active. Check the token and its expiration."
        }
        val queryName = encode(zoneName)
        val zones = resultArray(
            request("GET", "/zones?name=$queryName&status=active&per_page=50", apiToken),
            "zone lookup",
        )
        val matches = zones.mapNotNull { value ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { zone ->
                    zone.stringOrNull("name")?.equals(zoneName, ignoreCase = true) == true &&
                    zone.getAsJsonObjectOrNull("account")?.stringOrNull("id")
                        ?.equals(accountId, ignoreCase = true) == true
            }
        }
        require(matches.size == 1) {
            "Cloudflare did not return exactly one active zone named $zoneName for account $accountId."
        }
        return matches.single().string("id", "zone lookup")
    }

    suspend fun findOrCreateTunnel(settings: CloudflareAccountSettings): String {
        val accountId = settings.accountId
        val token = settings.apiToken
        val name = checkNotNull(settings.tunnelName)
        val path = "/accounts/$accountId/cfd_tunnel?name=${encode(name)}&is_deleted=false&per_page=100"
        val existing = resultArray(request("GET", path, token), "tunnel lookup").mapNotNull { value ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.stringOrNull("name") == name }
        }
        require(existing.size <= 1) { "More than one PocketPaaS Cloudflare Tunnel has the same name; remove duplicates in Cloudflare first." }
        if (existing.isNotEmpty()) {
            val existingId = existing.single().string("id", "tunnel lookup")
            require(TUNNEL_ID.matches(existingId)) { "Cloudflare returned an invalid Tunnel ID." }
            return existingId
        }

        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("config_src", "cloudflare")
        }
        val created = resultObject(
            request("POST", "/accounts/$accountId/cfd_tunnel", token, body.toString()),
            "tunnel creation",
        )
        val tunnelId = created.string("id", "tunnel creation")
        require(TUNNEL_ID.matches(tunnelId)) { "Cloudflare returned an invalid Tunnel ID." }
        return tunnelId
    }

    /** Remote-managed tunnels authenticate cloudflared with the token endpoint, not a local credentials JSON. */
    suspend fun getTunnelToken(settings: CloudflareAccountSettings): String {
        val tunnelId = checkNotNull(settings.tunnelId)
        require(TUNNEL_ID.matches(tunnelId)) { "Cloudflare Tunnel ID is invalid." }
        val response = request(
            "GET",
            "/accounts/${settings.accountId}/cfd_tunnel/$tunnelId/token",
            settings.apiToken,
        )
        val tunnelToken = response.get("result")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString ?: throw CloudflareApiException("Cloudflare returned an invalid Tunnel token.")
        require(tunnelToken.length in MINIMUM_TUNNEL_TOKEN_CHARACTERS..MAXIMUM_TUNNEL_TOKEN_CHARACTERS &&
            tunnelToken.none { it.isWhitespace() || it.isISOControl() }
        ) { "Cloudflare returned an invalid Tunnel token." }
        return tunnelToken
    }

    suspend fun updateTunnelIngress(
        settings: CloudflareAccountSettings,
        sites: List<Site>,
        origin: String,
    ) {
        val originMatch = ORIGIN.matchEntire(origin)
        val originPort = originMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        require(originPort != null && originPort in 1..65_535) {
            "Cloudflare Tunnel origin must be a loopback HTTP URL on a valid port."
        }
        val ingress = JsonArray()
        sites.filter { it.customHostname != null }.sortedBy { it.customHostname }.forEach { site ->
            val hostname = checkNotNull(site.customHostname)
            val rule = JsonObject().apply {
                addProperty("hostname", hostname)
                addProperty("service", origin)
                add("originRequest", JsonObject().apply { addProperty("httpHostHeader", hostname) })
            }
            ingress.add(rule)
        }
        ingress.add(JsonObject().apply { addProperty("service", "http_status:404") })
        val body = JsonObject().apply {
            add("config", JsonObject().apply { add("ingress", ingress) })
        }
        request(
            "PUT",
            "/accounts/${settings.accountId}/cfd_tunnel/${checkNotNull(settings.tunnelId)}/configurations",
            settings.apiToken,
            body.toString(),
        )
    }

    suspend fun syncDnsRecords(
        settings: CloudflareAccountSettings,
        desiredHostnames: Set<String>,
        tunnelId: String,
    ): List<String> {
        val target = "$tunnelId.cfargotunnel.com"
        val managed = settings.managedHostnames.toSet()
        desiredHostnames.sorted().forEach { hostname ->
            val records = dnsRecords(settings, hostname)
            require(records.size <= 1) { "Multiple DNS records already exist for $hostname; resolve the conflict in Cloudflare first." }
            val record = records.singleOrNull()
            if (record == null) {
                val body = dnsRecordBody(hostname, target)
                request("POST", "/zones/${settings.zoneId}/dns_records", settings.apiToken, body.toString())
            } else {
                require(record.type == "CNAME" && record.content.equals(target, ignoreCase = true)) {
                    "A conflicting DNS record already exists for $hostname. Remove or change it in Cloudflare, then retry."
                }
                if (!record.proxied) {
                    request(
                        "PUT",
                        "/zones/${settings.zoneId}/dns_records/${record.id}",
                        settings.apiToken,
                        dnsRecordBody(hostname, target).toString(),
                    )
                }
            }
        }
        (managed - desiredHostnames).sorted().forEach { hostname ->
            val records = dnsRecords(settings, hostname)
            records.filter { it.type == "CNAME" && it.content.equals(target, ignoreCase = true) }
                .forEach { record ->
                    request("DELETE", "/zones/${settings.zoneId}/dns_records/${record.id}", settings.apiToken)
                }
        }
        return desiredHostnames.sorted()
    }

    suspend fun deleteTunnel(settings: CloudflareAccountSettings) {
        val id = settings.tunnelId ?: return
        request("DELETE", "/accounts/${settings.accountId}/cfd_tunnel/$id", settings.apiToken)
    }

    private suspend fun dnsRecords(settings: CloudflareAccountSettings, hostname: String): List<DnsRecord> {
        val path = "/zones/${settings.zoneId}/dns_records?name=${encode(hostname)}&per_page=100"
        return resultArray(request("GET", path, settings.apiToken), "DNS record lookup").map { value ->
            if (!value.isJsonObject) throw CloudflareApiException("Cloudflare returned an invalid DNS record.")
            val record = value.asJsonObject
            val id = record.string("id", "DNS record lookup")
            require(DNS_RECORD_ID.matches(id)) { "Cloudflare returned an invalid DNS record ID." }
            DnsRecord(
                id = id,
                type = record.string("type", "DNS record lookup"),
                name = record.string("name", "DNS record lookup").trimEnd('.').lowercase(),
                content = record.string("content", "DNS record lookup"),
                proxied = record.booleanOrFalse("proxied"),
            )
        }.filter { it.name == hostname }
    }

    private fun dnsRecordBody(hostname: String, target: String) = JsonObject().apply {
        addProperty("type", "CNAME")
        addProperty("name", hostname)
        addProperty("content", target)
        addProperty("ttl", 1)
        addProperty("proxied", true)
    }

    private suspend fun request(method: String, path: String, token: String, body: String? = null): JsonObject {
        val response = try {
            transport.execute(method, path, token, body)
        } catch (failure: IOException) {
            throw CloudflareApiException("Cloudflare API request failed: ${failure.message.orEmpty().take(256)}", failure)
        }
        val payload = try {
            JsonParser.parseString(response.body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (failure: RuntimeException) {
            null
        } ?: throw CloudflareApiException("Cloudflare returned an invalid JSON response (HTTP ${response.statusCode}).")
        val success = payload.get("success")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        if (response.statusCode !in 200..299 || !success) {
            val messages = payload.getAsJsonArrayOrNull("errors")?.mapNotNull { error ->
                error.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("message")
            }.orEmpty().joinToString("; ").take(512)
            val rawReason = messages.ifBlank { "HTTP ${response.statusCode}" }
            val secrets = listOf(token) + requestSecrets(body)
            val reason = secrets.filter(String::isNotEmpty).distinct().fold(rawReason) { safe, secret ->
                safe.replace(secret, "[redacted]")
            }
            throw CloudflareApiException("Cloudflare API rejected the request: $reason")
        }
        return payload
    }

    private fun resultObject(payload: JsonObject, operation: String): JsonObject =
        payload.getAsJsonObjectOrNull("result")
            ?: throw CloudflareApiException("Cloudflare returned no result for $operation.")

    private fun resultArray(payload: JsonObject, operation: String): JsonArray =
        payload.getAsJsonArrayOrNull("result")
            ?: throw CloudflareApiException("Cloudflare returned no result list for $operation.")

    private fun JsonObject.string(name: String, operation: String): String = stringOrNull(name)
        ?: throw CloudflareApiException("Cloudflare returned an invalid $name in $operation.")

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun JsonObject.booleanOrFalse(name: String): Boolean = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
    }?.asBoolean ?: false

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun requestSecrets(body: String?): List<String> {
        if (body == null) return emptyList()
        return runCatching {
            val request = JsonParser.parseString(body)
            if (!request.isJsonObject) return@runCatching emptyList()
            request.asJsonObject.entrySet().mapNotNull { (name, value) ->
                value.takeIf {
                    (name.contains("token", ignoreCase = true) ||
                        name.contains("secret", ignoreCase = true) ||
                        name.contains("password", ignoreCase = true)) &&
                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                }?.asString
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class DnsRecord(
        val id: String,
        val type: String,
        val name: String,
        val content: String,
        val proxied: Boolean,
    )

    private companion object {
        const val MINIMUM_TUNNEL_TOKEN_CHARACTERS = 20
        const val MAXIMUM_TUNNEL_TOKEN_CHARACTERS = 4_096
        val TUNNEL_ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        val DNS_RECORD_ID = Regex("[a-fA-F0-9]{32}")
        val ORIGIN = Regex("http://127\\.0\\.0\\.1:([0-9]{1,5})")
    }
}

internal class CloudflareApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
