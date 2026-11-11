package dev.pocketpaas.data.remote

import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.files.SiteFileManager
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteApiServer
import dev.pocketpaas.domain.remote.RemoteApiServerFactory
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.remote.RemoteUploadStorage
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.ssh.SshManager
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.netty.NettyApplicationEngine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class NettyRemoteApiServerFactory(
    private val engineFactory: ApplicationEngineFactory<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val config: ServerConfig,
    private val policy: RemoteApiPolicy,
    private val keys: ApiKeyStore,
    private val sites: SiteRepository,
    private val storage: RemoteUploadStorage,
    private val ssh: SshManager? = null,
    private val services: ManagedServices? = null,
    private val activity: ActivityRepository? = null,
    private val deployments: DeploymentHistoryRepository? = null,
    private val backup: PlatformBackupManager? = null,
    private val sshProvider: () -> SshManager? = { ssh },
    private val servicesProvider: () -> ManagedServices? = { services },
    private val siteFiles: SiteFileManager? = null,
    private val siteFileManagerWebAssets: SiteFileManagerWebAssets? = null,
) : RemoteApiServerFactory {
    override fun create(bindHost: String, operations: RemoteSiteOperations): RemoteApiServer {
        require(bindHost == "127.0.0.1" || bindHost == "0.0.0.0")
        return Server(bindHost, operations)
    }

    private inner class Server(
        private val host: String,
        private val operations: RemoteSiteOperations,
    ) : RemoteApiServer {
        private val lifecycle = Mutex()
        private var owned: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private val connections = AtomicInteger()

        override suspend fun start() = lifecycle.withLock {
            check(owned == null) { "The remote listener already has an owner." }
            withContext(Dispatchers.IO) {
                cleanUploads()
                val created = embeddedServer(engineFactory, environment = applicationEnvironment(), configure = {
                    connector { host = this@Server.host; port = config.apiPort }
                    connectionGroupSize = 1
                    workerGroupSize = 2
                    callGroupSize = 4
                    runningLimit = 8
                    maxInitialLineLength = 2048
                    maxHeaderSize = 8192
                    maxChunkSize = 16 * 1024
                    enableHttp2 = false
                    enableH2c = false
                    tcpKeepAlive = true
                    // The upload parser has its own deadline. Deployment responses can take
                    // 35 minutes, so a connection idle timer must not abort that operation.
                    requestReadTimeoutSeconds = 0
                    responseWriteTimeoutSeconds = 30
                    channelPipelineConfig = {
                        addBefore("continue", "pocketpaasConnections", RemoteConnectionGuard(
                            connections, policy.maximumConnections, policy.headerTimeoutMillis,
                        ))
                    }
                }) {
                    installRemoteApi(
                        RemoteApiDependencies(
                            sites = sites,
                            operations = operations,
                            keys = keys,
                            uploadDirectory = storage.directory,
                            policy = policy,
                            ssh = sshProvider(),
                            services = servicesProvider(),
                            activity = activity,
                            deployments = deployments,
                            backup = backup,
                            siteFiles = siteFiles,
                            siteFileManagerHtml = siteFileManagerWebAssets?.html,
                            siteFileManagerCss = siteFileManagerWebAssets?.css,
                            siteFileManagerJavaScript = siteFileManagerWebAssets?.javaScript,
                        ),
                    )
                }
                owned = created
                try {
                    created.start(wait = false)
                    currentCoroutineContext().ensureActive()
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        try {
                            created.stop(0, STOP_TIMEOUT_MILLIS)
                            owned = null
                        } catch (cleanup: Throwable) {
                            failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
            }
        }

        override suspend fun stop() = lifecycle.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val running = owned ?: return@withContext
                // Close/cancel HTTP calls before the coordinator waits for deployment rollback.
                running.stop(0, STOP_TIMEOUT_MILLIS)
                owned = null
            }
        }
    }

    private fun cleanUploads() {
        val root = storage.directory.toPath()
        if (Files.isSymbolicLink(root)) throw IOException("Remote upload storage must be private and direct.")
        Files.createDirectories(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Remote upload storage is unavailable.")
        Files.newDirectoryStream(root).use { entries ->
            var scanned = 0
            for (entry in entries) {
                if (++scanned > 128) throw IOException("Remote upload cache requires local storage recovery.")
                if (entry.fileName.toString().matches(UPLOAD_FILE) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(entry)
                }
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 1500L
        private val UPLOAD_FILE = Regex("remote-upload--?[0-9]+\\.zip")
    }
}
