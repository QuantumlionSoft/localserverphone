package dev.pocketpaas.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.domain.backup.BackupRestoreSummary
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.backup.SignedBackupEnvelope
import dev.pocketpaas.domain.files.SiteFileManager
import dev.pocketpaas.domain.files.SiteFileNotFoundException
import dev.pocketpaas.domain.files.SiteFileTooLargeException
import dev.pocketpaas.domain.logs.ActivityFilter
import dev.pocketpaas.domain.logs.LogExportFormat
import dev.pocketpaas.domain.logs.LogExportFormatter
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.model.SiteStillActiveException
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.services.ServiceCatalog
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshManager
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

data class SiteFileManagerWebAssets(val html: String, val css: String, val javaScript: String)

data class RemoteApiDependencies(
    val sites: SiteRepository,
    val operations: RemoteSiteOperations,
    val keys: ApiKeyStore,
    val uploadDirectory: File,
    val policy: RemoteApiPolicy = RemoteApiPolicy(),
    val ssh: SshManager? = null,
    val services: ManagedServices? = null,
    val activity: ActivityRepository? = null,
    val deployments: DeploymentHistoryRepository? = null,
    val backup: PlatformBackupManager? = null,
    val siteFiles: SiteFileManager? = null,
    val siteFileManagerHtml: String? = null,
    val siteFileManagerCss: String? = null,
    val siteFileManagerJavaScript: String? = null,
)

data class RemoteSiteFileEntryResponse(val name: String, val isDirectory: Boolean, val sizeBytes: Long?)
data class RemoteSiteFileEntriesResponse(val entries: List<RemoteSiteFileEntryResponse>)
data class RemoteSiteFileRequest(val path: String? = null, val content: String? = null)
data class RemoteSiteFilePathRequest(val path: String? = null)
data class RemoteSiteFileContentResponse(val path: String, val content: String)
data class RemoteFileManagerSiteResponse(val id: Long, val domainName: String, val projectType: String)

data class RemoteErrorResponse(val error: String)

data class RemoteSiteResponse(
    val id: Long,
    val domainName: String,
    val internalPort: Int,
    val projectType: String,
    val processId: Int?,
    val publicUrl: String?,
    val status: String,
    val logs: String,
    val createdAt: Long,
    val customHostname: String? = null,
)

data class RemoteSshKeyResponse(
    val id: Long,
    val name: String,
    val fingerprint: String,
    val enabled: Boolean,
    val createdAt: Long,
)

data class RemoteSshStatusResponse(
    val status: String,
    val processId: Int?,
    val port: Int,
    val passwordAuthEnabled: Boolean,
    val listenLan: Boolean,
    val hostKeyFingerprint: String?,
    val authorizedKeyCount: Int,
    val connectionCommand: String?,
    val lastError: String?,
    val keys: List<RemoteSshKeyResponse>,
)

data class RemoteSshSettingsRequest(
    val port: Int? = null,
    val passwordAuthEnabled: Boolean? = null,
    val listenLan: Boolean? = null,
)

data class RemoteSshKeyCreateRequest(
    val name: String? = null,
    val publicKey: String? = null,
)

data class RemoteServiceResponse(
    val serviceKey: String,
    val displayName: String,
    val category: String,
    val installed: Boolean,
    val status: String,
    val processId: Int?,
    val port: Int?,
    val lastError: String?,
    val updatedAt: Long,
)

data class RemoteActivityEventResponse(
    val id: Long,
    val category: String,
    val level: String,
    val message: String,
    val detail: String?,
    val timestamp: Long,
)

data class RemoteDeploymentRecordResponse(
    val id: Long,
    val siteId: Long?,
    val domainName: String,
    val projectType: String,
    val status: String,
    val archiveName: String?,
    val archiveSizeBytes: Long?,
    val message: String?,
    val startedAt: Long,
    val finishedAt: Long?,
)

data class RemoteBackupImportRequest(
    val envelopeJson: String? = null,
    val requireSignature: Boolean? = null,
)

internal class RemoteApiFailure(val status: HttpStatusCode, val code: String) : IllegalArgumentException(code)

private val jsonParser = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
private const val MAXIMUM_JSON_BODY_BYTES = 16 * 1024
private const val MAXIMUM_BACKUP_BODY_BYTES = 512 * 1024

fun Application.installRemoteApi(dependencies: RemoteApiDependencies) {
    install(ContentNegotiation) { gson { serializeNulls(); disableHtmlEscaping() } }
    install(StatusPages) {
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            val (status, code) = when (failure) {
                is RemoteApiFailure -> failure.status to failure.code
                is RemoteOperationException -> when (failure.kind) {
                    RemoteErrorKind.NOT_FOUND -> HttpStatusCode.NotFound to "site_not_found"
                    RemoteErrorKind.CONFLICT -> HttpStatusCode.Conflict to "operation_conflict"
                    RemoteErrorKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable to "runtime_unavailable"
                    RemoteErrorKind.FAILED -> HttpStatusCode.InternalServerError to "operation_failed"
                }
                is SiteNotFoundException -> HttpStatusCode.NotFound to "site_not_found"
                is SiteFileNotFoundException -> HttpStatusCode.NotFound to "file_not_found"
                is SiteFileTooLargeException -> HttpStatusCode.PayloadTooLarge to "file_too_large"
                is SiteStillActiveException -> HttpStatusCode.Conflict to "operation_conflict"
                is BadRequestException, is IllegalArgumentException -> HttpStatusCode.BadRequest to "invalid_request"
                else -> HttpStatusCode.InternalServerError to "operation_failed"
            }
            if (status == HttpStatusCode.Unauthorized) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"PocketPaaS\"")
            }
            call.respond(status, RemoteErrorResponse(code))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, RemoteErrorResponse("not_found")) }
    }
    val authentication = createApplicationPlugin("PocketPaaSBearerAuthentication") {
        onCall { call ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.Connection, "close")
            call.response.header("X-Content-Type-Options", "nosniff")
            if (call.request.path() == "/api" || call.request.path().startsWith("/api/")) {
                verifyBearer(call, dependencies.keys)
            }
        }
    }
    install(authentication)
    val admission = Semaphore(1)
    val upload = RemoteMultipartUpload(dependencies.uploadDirectory, dependencies.policy)
    routing {
        route("/api") {
            get("/sites") {
                call.respond(dependencies.sites.listSites().map { it.response(dependencies.policy) })
            }
            post("/deploy") {
                val result = admitted(admission) {
                    upload.receive(call) { request, archive ->
                        operationDeadline(dependencies.policy) { dependencies.operations.deploy(request, archive) }
                    }
                }
                call.respond(HttpStatusCode.Created, result.response(dependencies.policy))
            }
            post("/sites/{id}/start") {
                val id = positiveId(call, "id", "invalid_site_id")
                requireEmptyBody(call)
                val result = admitted(admission) {
                    operationDeadline(dependencies.policy) { dependencies.operations.start(id) }
                }
                call.respond(result.response(dependencies.policy))
            }
            post("/sites/{id}/stop") {
                val id = positiveId(call, "id", "invalid_site_id")
                requireEmptyBody(call)
                val result = admitted(admission) {
                    operationDeadline(dependencies.policy) { dependencies.operations.stop(id) }
                }
                call.respond(result.response(dependencies.policy))
            }

            // --- SSH Management Endpoints ---
            get("/ssh") {
                val ssh = requireFeature(dependencies.ssh)
                call.respond(ssh.statusResponse())
            }
            post("/ssh/start") {
                val ssh = requireFeature(dependencies.ssh)
                requireEmptyBody(call)
                val status = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        ssh.start()
                        ssh.statusResponse()
                    }
                }
                call.respond(status)
            }
            post("/ssh/stop") {
                val ssh = requireFeature(dependencies.ssh)
                requireEmptyBody(call)
                val status = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        ssh.stop()
                        ssh.statusResponse()
                    }
                }
                call.respond(status)
            }
            post("/ssh/settings") {
                val ssh = requireFeature(dependencies.ssh)
                val request = receiveBoundedJson<RemoteSshSettingsRequest>(call, MAXIMUM_JSON_BODY_BYTES)
                val status = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        val current = ssh.state.value.settings
                        val next = current.copy(
                            port = request.port ?: current.port,
                            passwordAuthEnabled = request.passwordAuthEnabled ?: current.passwordAuthEnabled,
                            listenLan = request.listenLan ?: current.listenLan,
                        )
                        ssh.updateSettings(next)
                        ssh.statusResponse()
                    }
                }
                call.respond(status)
            }
            post("/ssh/keys") {
                val ssh = requireFeature(dependencies.ssh)
                val request = receiveBoundedJson<RemoteSshKeyCreateRequest>(call, MAXIMUM_JSON_BODY_BYTES)
                val name = request.name?.let(SshKeyFormat::validateName)
                    ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                val rawKey = request.publicKey?.takeIf { it.isNotBlank() }
                    ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                SshKeyFormat.parse(rawKey)
                val created = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        ssh.addAuthorizedKey(name, rawKey)
                    }
                }
                call.respond(HttpStatusCode.Created, created.response())
            }
            delete("/ssh/keys/{id}") {
                val ssh = requireFeature(dependencies.ssh)
                val id = positiveId(call, "id", "invalid_key_id")
                requireEmptyBody(call)
                val status = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        ssh.removeAuthorizedKey(id)
                        ssh.statusResponse()
                    }
                }
                call.respond(status)
            }

            // --- Managed Database Services Endpoints ---
            get("/services") {
                val services = requireFeature(dependencies.services)
                call.respond(services.services.value.map { it.response() })
            }
            post("/services/{key}/install") {
                val services = requireFeature(dependencies.services)
                val serviceKey = validatedServiceKey(call)
                requireEmptyBody(call)
                val state = admitted(admission) {
                    operationDeadline(dependencies.policy) { services.install(serviceKey) }
                }
                call.respond(state.response())
            }
            post("/services/{key}/start") {
                val services = requireFeature(dependencies.services)
                val serviceKey = validatedServiceKey(call)
                requireEmptyBody(call)
                val state = admitted(admission) {
                    operationDeadline(dependencies.policy) { services.start(serviceKey) }
                }
                call.respond(state.response())
            }
            post("/services/{key}/stop") {
                val services = requireFeature(dependencies.services)
                val serviceKey = validatedServiceKey(call)
                requireEmptyBody(call)
                val state = admitted(admission) {
                    operationDeadline(dependencies.policy) { services.stop(serviceKey) }
                }
                call.respond(state.response())
            }

            // --- Activity, Deployment History & Log Export Endpoints ---
            get("/activity") {
                val activity = requireFeature(dependencies.activity)
                val limit = queryLimit(call, defaultLimit = 100, maximumLimit = 500)
                val filter = parseActivityFilter(call)
                val events = activity.recent(limit).filter(filter::matches).map { it.response() }
                call.respond(events)
            }
            get("/deployments") {
                val deployments = requireFeature(dependencies.deployments)
                val limit = queryLimit(call, defaultLimit = 50, maximumLimit = 200)
                val query = call.request.queryParameters["q"].orEmpty()
                val filter = ActivityFilter(query = query)
                val records = deployments.recent(limit).filter(filter::matchesDeployment).map { it.response() }
                call.respond(records)
            }
            get("/logs/export") {
                val activity = requireFeature(dependencies.activity)
                val deployments = requireFeature(dependencies.deployments)
                val formatParam = call.request.queryParameters["format"]?.trim()?.lowercase() ?: "json"
                val format = when (formatParam) {
                    "json" -> LogExportFormat.JSON
                    "csv" -> LogExportFormat.CSV
                    else -> throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_format")
                }
                val filter = parseActivityFilter(call)
                val events = activity.recent(500).filter(filter::matches)
                val records = deployments.recent(200).filter(filter::matchesDeployment)
                val payload = LogExportFormatter.exportCombined(
                    events = events,
                    records = records,
                    format = format,
                    exportedAt = System.currentTimeMillis(),
                )
                val contentType = when (format) {
                    LogExportFormat.JSON -> ContentType.Application.Json
                    LogExportFormat.CSV -> ContentType.Text.CSV
                }
                call.respondText(payload, contentType = contentType, status = HttpStatusCode.OK)
            }

            // --- Backup & Restore Endpoints ---
            get("/backup/export") {
                val backup = requireFeature(dependencies.backup)
                val envelope: SignedBackupEnvelope = backup.exportSnapshot(signWithApiKey = true)
                call.respond(envelope)
            }
            post("/backup/import") {
                val backup = requireFeature(dependencies.backup)
                val rawBody = receiveBoundedString(call, MAXIMUM_BACKUP_BODY_BYTES)
                val summary: BackupRestoreSummary = admitted(admission) {
                    operationDeadline(dependencies.policy) {
                        val trimmed = rawBody.trim()
                        if (trimmed.contains("\"envelopeJson\"")) {
                            val wrapper = try {
                                jsonParser.fromJson(trimmed, RemoteBackupImportRequest::class.java)
                            } catch (failure: JsonSyntaxException) {
                                throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                            }
                            val innerJson = wrapper?.envelopeJson?.takeIf { it.isNotBlank() }
                                ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                            backup.importEnvelopeJson(innerJson, requireSignature = wrapper.requireSignature ?: false)
                        } else {
                            backup.importEnvelopeJson(trimmed, requireSignature = false)
                        }
                    }
                }
                call.respond(summary)
            }
        }
        get("/_pocketpaas/manager/") {
            managerSecurityHeaders(call)
            call.respondText(
                requireManagerAsset(dependencies.siteFileManagerHtml),
                contentType = ContentType.Text.Html,
            )
        }
        get("/_pocketpaas/manager.css") {
            managerSecurityHeaders(call)
            call.respondText(
                requireManagerAsset(dependencies.siteFileManagerCss),
                contentType = ContentType.parse("text/css; charset=utf-8"),
            )
        }
        get("/_pocketpaas/manager.js") {
            managerSecurityHeaders(call)
            call.respondText(
                requireManagerAsset(dependencies.siteFileManagerJavaScript),
                contentType = ContentType.parse("application/javascript; charset=utf-8"),
            )
        }
        route("/_pocketpaas/files") {
            get("/sites") {
                verifyBearer(call, dependencies.keys)
                requireEmptyBody(call)
                val result = dependencies.sites.listSites().map {
                    RemoteFileManagerSiteResponse(it.id, it.domainName, it.projectType.name)
                }
                call.respond(result)
            }
            get("/sites/{id}/entries") {
                verifyBearer(call, dependencies.keys)
                requireEmptyBody(call)
                val manager = requireFeature(dependencies.siteFiles)
                val id = positiveId(call, "id", "invalid_site_id")
                val path = call.request.queryParameters["path"].orEmpty()
                val result = admitted(admission) {
                    operationDeadline(dependencies.policy) { manager.list(id, path) }
                }
                call.respond(RemoteSiteFileEntriesResponse(
                    entries = result.map { RemoteSiteFileEntryResponse(it.name, it.isDirectory, it.sizeBytes) },
                ))
            }
            get("/sites/{id}/file") {
                verifyBearer(call, dependencies.keys)
                requireEmptyBody(call)
                val manager = requireFeature(dependencies.siteFiles)
                val id = positiveId(call, "id", "invalid_site_id")
                val path = requiredSiteFilePath(call)
                val content = admitted(admission) {
                    operationDeadline(dependencies.policy) { manager.read(id, path) }
                }
                call.respond(RemoteSiteFileContentResponse(path, content))
            }
            put("/sites/{id}/file") {
                verifyBearer(call, dependencies.keys)
                val manager = requireFeature(dependencies.siteFiles)
                val id = positiveId(call, "id", "invalid_site_id")
                val request = receiveBoundedJson<RemoteSiteFileRequest>(call, MAXIMUM_SITE_FILE_BODY_BYTES)
                val path = request.path?.takeIf(String::isNotBlank)
                    ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                val content = request.content
                    ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                admitted(admission) {
                    operationDeadline(dependencies.policy) { manager.write(id, path, content) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/sites/{id}/directory") {
                verifyBearer(call, dependencies.keys)
                val manager = requireFeature(dependencies.siteFiles)
                val id = positiveId(call, "id", "invalid_site_id")
                val request = receiveBoundedJson<RemoteSiteFilePathRequest>(call, MAXIMUM_JSON_BODY_BYTES)
                val path = request.path?.takeIf(String::isNotBlank)
                    ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
                admitted(admission) {
                    operationDeadline(dependencies.policy) { manager.createDirectory(id, path) }
                }
                call.respond(HttpStatusCode.Created)
            }
            delete("/sites/{id}/file") {
                verifyBearer(call, dependencies.keys)
                requireEmptyBody(call)
                val manager = requireFeature(dependencies.siteFiles)
                val id = positiveId(call, "id", "invalid_site_id")
                val path = requiredSiteFilePath(call)
                admitted(admission) {
                    operationDeadline(dependencies.policy) { manager.delete(id, path) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private const val MAXIMUM_SITE_FILE_BODY_BYTES = 256 * 1024
private const val SITE_FILE_MANAGER_CSP = "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

private fun managerSecurityHeaders(call: ApplicationCall) {
    call.response.header("Content-Security-Policy", SITE_FILE_MANAGER_CSP)
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header("X-Frame-Options", "DENY")
}

private fun requireManagerAsset(asset: String?): String = asset
    ?: throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "feature_unavailable")

private fun requiredSiteFilePath(call: ApplicationCall): String =
    call.request.queryParameters["path"]?.takeIf(String::isNotBlank)
        ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")

private suspend fun verifyBearer(call: ApplicationCall, keys: ApiKeyStore) {
    val headers = call.request.headers.getAll(HttpHeaders.Authorization)
    val raw = headers?.singleOrNull()
    val supplied = raw?.let {
        if (it.length == 71 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) it.substring(7) else null
    }
    if (supplied == null || !Regex("[a-f0-9]{64}").matches(supplied)) {
        throw RemoteApiFailure(HttpStatusCode.Unauthorized, "unauthorized")
    }
    val expected = try { keys.token() } catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "credentials_unavailable") }
    if (!Regex("[a-f0-9]{64}").matches(expected)) {
        throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "credentials_unavailable")
    }
    if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
        throw RemoteApiFailure(HttpStatusCode.Unauthorized, "unauthorized")
    }
}

private fun <T : Any> requireFeature(feature: T?): T =
    feature ?: throw RemoteApiFailure(HttpStatusCode.ServiceUnavailable, "feature_unavailable")

private fun positiveId(call: ApplicationCall, parameter: String, errorCode: String): Long {
    val raw = call.parameters[parameter]
    if (raw == null || !Regex("[1-9][0-9]{0,18}").matches(raw)) {
        throw RemoteApiFailure(HttpStatusCode.BadRequest, errorCode)
    }
    return raw.toLongOrNull()?.takeIf { it > 0 }
        ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, errorCode)
}

private fun validatedServiceKey(call: ApplicationCall): String {
    val key = call.parameters["key"]?.trim()?.lowercase()
        ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_service_key")
    val definition = ServiceCatalog.find(key)
        ?: throw RemoteApiFailure(HttpStatusCode.NotFound, "service_not_found")
    if (definition.externallyManaged) {
        throw RemoteApiFailure(HttpStatusCode.BadRequest, "externally_managed_service")
    }
    return definition.serviceKey
}

private fun queryLimit(call: ApplicationCall, defaultLimit: Int, maximumLimit: Int): Int {
    val raw = call.request.queryParameters["limit"] ?: return defaultLimit
    val parsed = raw.toIntOrNull()
        ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_limit")
    if (parsed !in 1..maximumLimit) {
        throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_limit")
    }
    return parsed
}

private fun parseActivityFilter(call: ApplicationCall): ActivityFilter {
    val levelParam = call.request.queryParameters["level"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val level = levelParam?.let {
        runCatching { ActivityLevel.valueOf(it) }.getOrElse {
            throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_level")
        }
    }
    val categoryParam = call.request.queryParameters["category"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val category = categoryParam?.let {
        runCatching { ActivityCategory.valueOf(it) }.getOrElse {
            throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_category")
        }
    }
    val query = call.request.queryParameters["q"]?.take(128).orEmpty()
    return ActivityFilter(query = query, level = level, category = category)
}

private suspend fun receiveBoundedString(call: ApplicationCall, maxBytes: Int): String {
    val channel = call.receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read > 0) {
            total += read
            if (total > maxBytes) throw RemoteApiFailure(HttpStatusCode.PayloadTooLarge, "payload_too_large")
            output.write(buffer, 0, read)
        }
    }
    val bytes = output.toByteArray()
    if (bytes.isEmpty()) throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
    return String(bytes, Charsets.UTF_8)
}

private suspend inline fun <reified T : Any> receiveBoundedJson(call: ApplicationCall, maxBytes: Int): T {
    val text = receiveBoundedString(call, maxBytes)
    return try {
        jsonParser.fromJson(text, T::class.java)
            ?: throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
    } catch (failure: JsonSyntaxException) {
        throw RemoteApiFailure(HttpStatusCode.BadRequest, "invalid_request")
    }
}

private fun requireEmptyBody(call: ApplicationCall) {
    val lengths = call.request.headers.getAll(HttpHeaders.ContentLength)
    if (!call.request.headers.getAll(HttpHeaders.TransferEncoding).isNullOrEmpty() ||
        (lengths != null && (lengths.size != 1 || lengths.single() != "0"))
    ) throw RemoteApiFailure(HttpStatusCode.BadRequest, "body_not_allowed")
}

private suspend fun <T> admitted(semaphore: Semaphore, block: suspend () -> T): T {
    if (!semaphore.tryAcquire()) throw RemoteApiFailure(HttpStatusCode.Conflict, "operation_in_progress")
    try { return block() } finally { semaphore.release() }
}

private suspend fun <T> operationDeadline(policy: RemoteApiPolicy, operation: suspend () -> T): T =
    withTimeoutOrNull(policy.operationTimeoutMillis) { CompletedOperation(operation()) }?.value
        ?: throw RemoteApiFailure(HttpStatusCode.GatewayTimeout, "operation_timeout")

private data class CompletedOperation<T>(val value: T)

private fun Site.response(policy: RemoteApiPolicy): RemoteSiteResponse = RemoteSiteResponse(
    id, domainName, internalPort, projectType.name, processId, publicUrl, status.name,
    if (policy.maximumReturnedLogCharacters == 0) "" else SiteInput.logTail(logs, policy.maximumReturnedLogCharacters), createdAt,
    customHostname,
)

private fun SshAuthorizedKey.response(): RemoteSshKeyResponse = RemoteSshKeyResponse(
    id = id,
    name = name,
    fingerprint = fingerprint,
    enabled = enabled,
    createdAt = createdAt,
)

private suspend fun SshManager.statusResponse(): RemoteSshStatusResponse {
    val current = state.value
    val keys = listAuthorizedKeys().map { it.response() }
    return RemoteSshStatusResponse(
        status = current.status.name,
        processId = current.processId,
        port = current.settings.port,
        passwordAuthEnabled = current.settings.passwordAuthEnabled,
        listenLan = current.settings.listenLan,
        hostKeyFingerprint = current.settings.hostKeyFingerprint,
        authorizedKeyCount = keys.size,
        connectionCommand = current.connectionCommand,
        lastError = current.lastError,
        keys = keys,
    )
}

private fun ManagedServiceState.response(): RemoteServiceResponse = RemoteServiceResponse(
    serviceKey = serviceKey,
    displayName = displayName,
    category = category.name,
    installed = installed,
    status = status.name,
    processId = processId,
    port = port,
    lastError = lastError,
    updatedAt = updatedAt,
)

private fun ActivityEvent.response(): RemoteActivityEventResponse = RemoteActivityEventResponse(
    id = id,
    category = category.name,
    level = level.name,
    message = message,
    detail = detail,
    timestamp = timestamp,
)

private fun DeploymentRecord.response(): RemoteDeploymentRecordResponse = RemoteDeploymentRecordResponse(
    id = id,
    siteId = siteId,
    domainName = domainName,
    projectType = projectType.name,
    status = status.name,
    archiveName = archiveName,
    archiveSizeBytes = archiveSizeBytes,
    message = message,
    startedAt = startedAt,
    finishedAt = finishedAt,
)
