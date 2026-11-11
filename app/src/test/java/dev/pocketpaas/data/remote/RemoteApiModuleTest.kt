package dev.pocketpaas.data.remote

import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.files.SiteFileEntry
import dev.pocketpaas.domain.files.SiteFileManager
import dev.pocketpaas.domain.files.SiteFileNotFoundException
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.repository.SiteRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RemoteApiModuleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `missing malformed wrong and duplicate credentials cannot read or mutate sites`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val unauthorized = client.get("/api/sites")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals("Bearer realm=\"PocketPaaS\"", unauthorized.headers[HttpHeaders.WWWAuthenticate])
        for (authorization in listOf("Basic $TOKEN", "Bearer ${"b".repeat(64)}", "Bearer $TOKEN ", "Bearer ${TOKEN.uppercase()}")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sites") { header(HttpHeaders.Authorization, authorization) }.status)
        }
        val duplicate = client.get("/api/sites") {
            headers.append(HttpHeaders.Authorization, "Bearer $TOKEN")
            headers.append(HttpHeaders.Authorization, "Bearer $TOKEN")
        }
        assertEquals(HttpStatusCode.Unauthorized, duplicate.status)
        assertEquals(0, fixture.repository.reads.get())
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `authentication runs before content parsing and filesystem creation`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val response = client.post("/api/deploy") { setBody("malformed oversized body") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(fixture.uploads.exists())
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `authorized list has bounded unicode logs and no store headers`() = testApplication {
        val fixture = Fixture()
        fixture.repository.site = fixture.repository.site.copy(logs = "secret-head" + "😀".repeat(30))
        application { installRemoteApi(fixture.dependencies(RemoteApiPolicy(maximumReturnedLogCharacters = 4))) }
        val response = client.get("/api/sites") { authorize() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val body = response.bodyAsText()
        assertTrue(body.contains("😀".repeat(4)))
        assertFalse(body.contains("secret-head"))
        assertTrue(body.contains("\"publicUrl\":null"))
        assertTrue(body.contains("\"customHostname\":null"))
        fixture.repository.site = fixture.repository.site.copy(customHostname = "www.example.com")
        val customHostnameBody = client.get("/api/sites") { authorize() }.bodyAsText()
        assertTrue(customHostnameBody.contains("\"domainName\":\"example.com\""))
        assertTrue(customHostnameBody.contains("\"customHostname\":\"www.example.com\""))
    }

    @Test fun `file manager page is hardened and site file API authenticates before reading or parsing`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }

        val page = client.get("/_pocketpaas/manager/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertTrue(page.headers["Content-Security-Policy"].orEmpty().contains("default-src 'none'"))
        assertTrue(page.bodyAsText().contains("Site dosyaları"))
        assertTrue(client.get("/_pocketpaas/manager.js").bodyAsText().contains("apiKey"))
        assertEquals(HttpStatusCode.OK, client.get("/_pocketpaas/manager.css").status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/_pocketpaas/files/sites").status)
        val malformed = client.put("/_pocketpaas/files/sites/1/file") { setBody("not json") }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        assertEquals(0, fixture.siteFiles.calls.get())
    }

    @Test fun `authenticated site file endpoints list read write create folder and delete`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }

        val sites = client.get("/_pocketpaas/files/sites") { authorize() }
        assertEquals(HttpStatusCode.OK, sites.status)
        assertTrue(sites.bodyAsText().contains("example.com"))
        val entries = client.get("/_pocketpaas/files/sites/1/entries") { authorize() }
        assertEquals(HttpStatusCode.OK, entries.status)
        assertTrue(entries.bodyAsText().contains("\"entries\""))
        assertTrue(entries.bodyAsText().contains("index.html"))

        val created = client.put("/_pocketpaas/files/sites/1/file") {
            authorize()
            setBody("{\"path\":\"index.html\",\"content\":\"<h1>hello</h1>\"}")
        }
        assertEquals(HttpStatusCode.NoContent, created.status)
        val read = client.get("/_pocketpaas/files/sites/1/file?path=index.html") { authorize() }
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.bodyAsText().contains("<h1>hello</h1>"))

        val folder = client.post("/_pocketpaas/files/sites/1/directory") {
            authorize()
            setBody("{\"path\":\"assets\"}")
        }
        assertEquals(HttpStatusCode.Created, folder.status)
        val deleted = client.delete("/_pocketpaas/files/sites/1/file?path=index.html") { authorize() }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(fixture.siteFiles.deleted)
    }

    @Test fun `multipart supports either order and cleans private archive after successful deployment`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        for (reverse in listOf(false, true)) {
            val response = client.post("/api/deploy") { authorize(); setBody(multipart(reverse = reverse)) }
            assertEquals(response.bodyAsText(), HttpStatusCode.Created, response.status)
            assertEquals(RemoteDeployRequest(domainName = "example.com", projectType = ProjectType.NODEJS), fixture.operations.lastRequest)
            assertTrue(fixture.operations.archiveWasReadable)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
        assertEquals(2, fixture.operations.deploys.get())
    }

    @Test fun `existing site deployment accepts only positive numeric site id metadata`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val response = client.post("/api/deploy") { authorize(); setBody(multipart(metadata = "{\"siteId\":1}")) }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(RemoteDeployRequest(siteId = 1), fixture.operations.lastRequest)
    }

    @Test fun `streaming upload preserves binary bytes across buffers and false boundary prefixes`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val archive = ByteArray(50_000) { (it % 256).toByte() } + "\r\n--boundarX".toByteArray()
        val body = part("metadata", "{\"siteId\":1}", "application/json").toByteArray() +
            "--boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray() +
            archive + "\r\n--boundary--\r\n".toByteArray()
        val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart(body)) }
        assertEquals(HttpStatusCode.Created, response.status)
        assertArrayEquals(archive, fixture.operations.archiveBytes)
        assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `utf8 archive filename is accepted and never used as destination path`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val body = part("archive", "PKbytes", "application/zip", "gönderim-😀.zip") +
            part("metadata", "{\"siteId\":1}", "application/json") + "--boundary--\r\n"
        val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart(body.toByteArray())) }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `full false boundary markers and their overlapping suffixes remain archive content`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        for (tail in listOf("\r\n--boundaryX", "\r\n--boundary\rX", "\r\n--boundary-X", "\r\n--boundary--X", "\r\n--boundary--\rX", "\r\n--boundaryX\r\n--boundaryQ\r")) {
            val archive = ("PKbytes" + tail).toByteArray()
            val body = part("metadata", "{\"siteId\":1}", "application/json").toByteArray() +
                "--boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray() +
                archive + "\r\n--boundary--\r\n".toByteArray()
            val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart(body)) }
            assertEquals(response.bodyAsText(), HttpStatusCode.Created, response.status)
            assertArrayEquals(archive, fixture.operations.archiveBytes)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `strict metadata rejects duplicate unknown null wrong typed and conflicting fields`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val invalid = listOf(
            "{\"siteId\":1,\"siteId\":2}", "{\"siteId\":\"1\"}", "{\"siteId\":null}",
            "{\"siteId\":1.0}", "{\"siteId\":1e0}", "{\"siteId\":0}", "{\"siteId\":9223372036854775808}",
            "{\"siteId\":1,\"domainName\":\"x.com\"}", "{\"siteId\":1,\"extra\":true}",
            "{\"domainName\":\"../bad\",\"projectType\":\"STATIC\"}",
            "{\"domainName\":\"x.com\",\"projectType\":null}",
            "{\"domainName\":\"x.com\",\"projectType\":\"PYTHON\"}",
            "{\"domainName\":\"x.com\"}", "{siteId:1}", "{\"siteId\":1}{}", "[]",
        )
        for (metadata in invalid) {
            val response = client.post("/api/deploy") { authorize(); setBody(multipart(metadata = metadata)) }
            assertEquals(metadata + response.bodyAsText(), HttpStatusCode.BadRequest, response.status)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `malformed utf8 metadata is rejected before deployment`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val malformed = "--boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) +
            "\r\n--boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\n\r\nPKbytes\r\n--boundary--\r\n".toByteArray()
        val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart(malformed)) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `duplicate unknown missing and malformed multipart parts leave no archive`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val metadataPart = part("metadata", "{\"siteId\":1}", "application/json")
        val archivePart = part("archive", "PKbytes", "application/zip", "project.zip")
        val bodies = listOf(
            metadataPart + metadataPart + archivePart, metadataPart, archivePart,
            metadataPart + archivePart + part("unknown", "text", "text/plain"),
            metadataPart + part("archive", "PKbytes", "application/zip"),
            metadataPart + archivePart.replace("Content-Type: application/zip", "Content-Transfer-Encoding: base64"),
            metadataPart + archivePart.replace("Content-Type: application/zip", "Content-Type: application/zip\r\nContent-Type: application/zip"),
        )
        for (body in bodies) {
            val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart((body + "--boundary--\r\n").toByteArray())) }
            assertEquals(response.bodyAsText(), HttpStatusCode.BadRequest, response.status)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `request archive metadata and header bounds produce payload too large`() = testApplication {
        val fixture = Fixture()
        val policy = RemoteApiPolicy(maximumArchiveBytes = 64, maximumRequestBytes = 1024, maximumMetadataBytes = 64, maximumPartHeaderCharacters = 256)
        application { installRemoteApi(fixture.dependencies(policy)) }
        val requests = listOf(
            multipart(archive = "x".repeat(65)), multipart(metadata = " ".repeat(65)),
            multipart(archive = "x".repeat(1100)),
            rawMultipart((part("metadata", "{\"siteId\":1}", "application/json") +
                part("archive", "PK", "application/zip", "x".repeat(250)) + "--boundary--\r\n").toByteArray()),
        )
        for (request in requests) {
            val response = client.post("/api/deploy") { authorize(); setBody(request) }
            assertEquals(response.bodyAsText(), HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
        assertEquals(0, fixture.operations.deploys.get())
    }

    @Test fun `incorrect media types and missing boundary are rejected`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val wrongType = client.post("/api/deploy") { authorize(); setBody("body") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)
        val missingBoundary = client.post("/api/deploy") {
            authorize(); setBody(ByteArrayContent("body".toByteArray(), ContentType.MultiPart.FormData))
        }
        assertEquals(HttpStatusCode.BadRequest, missingBoundary.status)
        val wrongMetadataType = rawMultipart((part("metadata", "{\"siteId\":1}", "text/plain") +
            part("archive", "PK", "application/zip", "project.zip") + "--boundary--\r\n").toByteArray())
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/api/deploy") { authorize(); setBody(wrongMetadataType) }.status)
    }

    @Test fun `truncated boundaries unexpected epilogues and preambles are rejected`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        val valid = multipartBytes()
        for (bytes in listOf(valid.copyOf(valid.size - 8), "preamble\r\n".toByteArray() + valid, valid + "epilogue".toByteArray())) {
            val response = client.post("/api/deploy") { authorize(); setBody(rawMultipart(bytes)) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `positive site id routes delegate and reject bodies and ambiguous ids`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        assertEquals(HttpStatusCode.OK, client.post("/api/sites/1/start") { authorize() }.status)
        assertEquals(1L, fixture.operations.lastStarted)
        assertEquals(HttpStatusCode.OK, client.post("/api/sites/1/stop") { authorize() }.status)
        assertEquals(1L, fixture.operations.lastStopped)
        for (id in listOf("0", "-1", "01", "9223372036854775808", "text")) {
            assertEquals(id, HttpStatusCode.BadRequest, client.post("/api/sites/$id/start") { authorize() }.status)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/sites/1/start") { authorize(); setBody("unexpected") }.status)
    }

    @Test fun `operation failures map sanitized errors and cleanup archive`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }
        for ((kind, status) in listOf(
            RemoteErrorKind.NOT_FOUND to HttpStatusCode.NotFound,
            RemoteErrorKind.CONFLICT to HttpStatusCode.Conflict,
            RemoteErrorKind.UNAVAILABLE to HttpStatusCode.ServiceUnavailable,
            RemoteErrorKind.FAILED to HttpStatusCode.InternalServerError,
        )) {
            fixture.operations.failure = RemoteOperationException(kind)
            val response = client.post("/api/deploy") { authorize(); setBody(multipart()) }
            assertEquals(status, response.status)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        }
        fixture.operations.failure = IllegalStateException("SECRET_TOKEN absolute-private-path")
        val response = client.post("/api/deploy") { authorize(); setBody(multipart()) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse(response.bodyAsText().contains("SECRET_TOKEN"))
        assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `only one mutation admitted and lists remain responsive`() = testApplication {
        val fixture = Fixture()
        fixture.operations.entered = CompletableDeferred()
        fixture.operations.release = CompletableDeferred()
        application { installRemoteApi(fixture.dependencies()) }
        coroutineScope {
            val first = async { client.post("/api/deploy") { authorize(); setBody(multipart()) } }
            requireNotNull(fixture.operations.entered).await()
            try {
                assertEquals(HttpStatusCode.Conflict, client.post("/api/sites/1/stop") { authorize() }.status)
                assertEquals(HttpStatusCode.Conflict, client.post("/api/deploy") { authorize(); setBody(multipart()) }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/sites") { authorize() }.status)
            } finally { requireNotNull(fixture.operations.release).complete(Unit) }
            assertEquals(HttpStatusCode.Created, first.await().status)
        }
        assertEquals(1, fixture.operations.deploys.get())
    }

    @Test fun `operation timeout cancels task and releases archive and admission`() = testApplication {
        val fixture = Fixture()
        fixture.operations.deployDelayMillis = 500
        application { installRemoteApi(fixture.dependencies(RemoteApiPolicy(operationTimeoutMillis = 25))) }
        val timeout = client.post("/api/deploy") { authorize(); setBody(multipart()) }
        assertEquals(HttpStatusCode.GatewayTimeout, timeout.status)
        assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
        fixture.operations.deployDelayMillis = 0
        assertEquals(HttpStatusCode.Created, client.post("/api/deploy") { authorize(); setBody(multipart()) }.status)
    }

    @Test fun `slow upload times out before deployment and releases private archive`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies(RemoteApiPolicy(uploadTimeoutMillis = 50))) }
        val slow = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.parse("multipart/form-data; boundary=boundary")
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully("--boundary\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\n\r\n".toByteArray())
                channel.flush()
                delay(250)
            }
        }
        val response = client.post("/api/deploy") { authorize(); setBody(slow) }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals(0, fixture.operations.deploys.get())
        assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
    }

    @Test fun `stored malformed token never authenticates even matching invalid credentials`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies().copy(keys = object : ApiKeyStore {
            override suspend fun token() = "corrupt-token"
        })) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/sites") { authorize() }.status)
        assertEquals(0, fixture.repository.reads.get())
    }

    @Test fun `credential storage failure and corrupt token fail closed`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies().copy(keys = object : ApiKeyStore {
            override suspend fun token(): String = throw IllegalStateException("private path with secret")
        })) }
        val response = client.get("/api/sites") { authorize() }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"error\":\"credentials_unavailable\"}", response.bodyAsText())
        assertEquals(0, fixture.repository.reads.get())
    }

    private inner class Fixture {
        val uploads = File(temporary.newFolder(), "uploads")
        val repository = MemorySites()
        val operations = FakeOperations(repository)
        val siteFiles = MemorySiteFileManager()
        fun dependencies(policy: RemoteApiPolicy = RemoteApiPolicy()) = RemoteApiDependencies(
            sites = repository,
            operations = operations,
            keys = object : ApiKeyStore { override suspend fun token() = TOKEN },
            uploadDirectory = uploads,
            policy = policy,
            siteFiles = siteFiles,
            siteFileManagerHtml = "<!doctype html><h1>Site dosyaları</h1>",
            siteFileManagerCss = "body { color: black; }",
            siteFileManagerJavaScript = "const apiKey = '';",
        )
    }

    private class MemorySiteFileManager : SiteFileManager {
        val calls = AtomicInteger()
        val contents = linkedMapOf<String, String>()
        val directories = linkedSetOf("")
        var deleted = false
        override suspend fun list(siteId: Long, relativeDirectory: String): List<SiteFileEntry> {
            calls.incrementAndGet()
            checkSite(siteId)
            val files = contents.keys.filter { it.substringBeforeLast('/', "") == relativeDirectory }.map { path ->
                SiteFileEntry(path.substringAfterLast('/'), false, contents.getValue(path).toByteArray().size.toLong())
            }
            val folders = directories.filter { it.isNotEmpty() && it.substringBeforeLast('/', "") == relativeDirectory }.map { path ->
                SiteFileEntry(path.substringAfterLast('/'), true, null)
            }
            return if (relativeDirectory.isEmpty() && contents.isEmpty()) listOf(SiteFileEntry("index.html", false, 0))
            else folders + files
        }
        override suspend fun read(siteId: Long, relativePath: String): String {
            calls.incrementAndGet()
            checkSite(siteId)
            return contents[relativePath] ?: throw SiteFileNotFoundException(siteId, relativePath)
        }
        override suspend fun write(siteId: Long, relativePath: String, content: String) {
            calls.incrementAndGet()
            checkSite(siteId)
            contents[relativePath] = content
        }
        override suspend fun createDirectory(siteId: Long, relativePath: String) {
            calls.incrementAndGet()
            checkSite(siteId)
            directories += relativePath
        }
        override suspend fun delete(siteId: Long, relativePath: String) {
            calls.incrementAndGet()
            checkSite(siteId)
            contents.remove(relativePath)
            deleted = true
        }
        private fun checkSite(siteId: Long) {
            if (siteId != 1L) throw SiteNotFoundException(siteId)
        }
    }

    private class FakeOperations(private val sites: MemorySites) : RemoteSiteOperations {
        val deploys = AtomicInteger()
        var lastRequest: RemoteDeployRequest? = null
        var archiveWasReadable = false
        var archiveBytes = ByteArray(0)
        var lastStarted: Long? = null
        var lastStopped: Long? = null
        var failure: Exception? = null
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var deployDelayMillis = 0L
        override suspend fun deploy(request: RemoteDeployRequest, archive: File): Site {
            deploys.incrementAndGet()
            lastRequest = request
            archiveBytes = archive.readBytes()
            archiveWasReadable = archiveBytes.isNotEmpty()
            entered?.complete(Unit)
            release?.await()
            if (deployDelayMillis > 0) delay(deployDelayMillis)
            failure?.let { throw it }
            return sites.site.copy(status = SiteStatus.RUNNING)
        }
        override suspend fun start(siteId: Long): Site { lastStarted = siteId; return sites.site.copy(status = SiteStatus.RUNNING) }
        override suspend fun stop(siteId: Long): Site { lastStopped = siteId; return sites.site.copy(status = SiteStatus.STOPPED) }
    }

    private class MemorySites : SiteRepository {
        var site = Site(1, "example.com", 18000, ProjectType.NODEJS, null, null, SiteStatus.STOPPED, "", 1)
        val reads = AtomicInteger()
        override val sites: StateFlow<List<Site>> = MutableStateFlow(listOf(site))
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(listOf(site)))
        override fun observeSite(id: Long): Flow<Site?> = sites.map { it.find { value -> value.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = sites.map { it.filter { value -> value.status == status } }
        override suspend fun listSites(): List<Site> { reads.incrementAndGet(); return listOf(site) }
        override suspend fun getSite(id: Long): Site? = site.takeIf { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site = site.copy(domainName = domainName, projectType = projectType)
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) { site = site.copy(status = status, processId = processId) }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean {
            if (site.id != id || site.status != expectedStatus) return false
            updateRuntimeState(id, newStatus, processId)
            return true
        }
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { site = site.copy(publicUrl = publicUrl) }
        override suspend fun appendLogs(id: Long, text: String) { site = site.copy(logs = site.logs + text) }
        override suspend fun clearLogs(id: Long) { site = site.copy(logs = "") }
        override suspend fun deleteSiteIfInactive(id: Long) { check(site.id == id && site.status == SiteStatus.STOPPED) }
    }

    private companion object {
        val TOKEN = "a".repeat(64)
        fun io.ktor.client.request.HttpRequestBuilder.authorize() { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        fun part(name: String, body: String, contentType: String, filename: String? = null): String =
            "--boundary\r\nContent-Disposition: form-data; name=\"$name\"" +
                (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\nContent-Type: $contentType\r\n\r\n$body\r\n"
        fun multipartBytes(metadata: String = "{\"domainName\":\"Example.COM\",\"projectType\":\"NODEJS\"}", archive: String = "PKbytes", reverse: Boolean = false): ByteArray {
            val metadataPart = part("metadata", metadata, "application/json")
            val archivePart = part("archive", archive, "application/zip", "../untrusted.zip")
            return ((if (reverse) archivePart + metadataPart else metadataPart + archivePart) + "--boundary--\r\n").toByteArray()
        }
        fun multipart(metadata: String = "{\"domainName\":\"Example.COM\",\"projectType\":\"NODEJS\"}", archive: String = "PKbytes", reverse: Boolean = false) =
            rawMultipart(multipartBytes(metadata, archive, reverse))
        fun rawMultipart(bytes: ByteArray) = ByteArrayContent(bytes, ContentType.parse("multipart/form-data; boundary=boundary"))
    }
}
