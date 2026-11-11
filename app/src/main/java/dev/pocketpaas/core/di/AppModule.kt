package dev.pocketpaas.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.google.gson.Gson
import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.core.config.NetworkPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.core.config.ServicePolicy
import dev.pocketpaas.core.config.SiteStoragePolicy
import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.core.config.SshPolicy
import dev.pocketpaas.data.backup.DefaultPlatformBackupManager
import dev.pocketpaas.data.dashboard.android.AndroidDashboardActions
import dev.pocketpaas.data.dashboard.android.AndroidDashboardDevice
import dev.pocketpaas.data.deployment.RecordingDeploymentControl
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.dashboard.DashboardActions
import dev.pocketpaas.domain.dashboard.DashboardDevice
import dev.pocketpaas.domain.dashboard.DashboardNetworkState
import dev.pocketpaas.domain.remote.LocalSiteOperations
import dev.pocketpaas.data.remote.NettyRemoteApiServerFactory
import dev.pocketpaas.data.remote.RemoteServerRuntimeManager
import dev.pocketpaas.data.remote.SiteFileManagerWebAssets
import dev.pocketpaas.data.remote.android.AndroidApiKeyStore
import dev.pocketpaas.data.runtime.TermuxRuntimeRecovery
import dev.pocketpaas.data.deployment.DeploymentOrchestrator
import dev.pocketpaas.domain.deployment.DeploymentControl
import dev.pocketpaas.domain.deployment.RuntimeRecoveryWorkspace
import dev.pocketpaas.domain.network.ManagedNetwork
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteApiServerFactory
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.remote.RemoteUploadStorage
import dev.pocketpaas.domain.files.SiteFileManager
import dev.pocketpaas.domain.runtime.RuntimeRecovery
import dev.pocketpaas.domain.runtime.RuntimeReset
import dev.pocketpaas.data.local.ActivityEventDao
import dev.pocketpaas.data.local.DatabaseMigrations
import dev.pocketpaas.data.local.DeploymentRecordDao
import dev.pocketpaas.data.local.ServiceStateDao
import dev.pocketpaas.data.local.SiteDao
import dev.pocketpaas.data.local.PlatformSettingsDao
import dev.pocketpaas.data.local.PocketPaaSDatabase
import dev.pocketpaas.data.local.SshDao
import dev.pocketpaas.data.process.termux.TermuxAgentBootstrap
import dev.pocketpaas.data.process.termux.TermuxBridge
import dev.pocketpaas.data.process.termux.TermuxBridgeCredentialsStore
import dev.pocketpaas.data.process.termux.TermuxProcessLauncher
import dev.pocketpaas.data.process.termux.android.AndroidTermuxAgentBootstrap
import dev.pocketpaas.data.process.termux.android.AndroidTermuxCredentialsStore
import dev.pocketpaas.data.repository.DefaultActivityRepository
import dev.pocketpaas.data.repository.DefaultDeploymentHistoryRepository
import dev.pocketpaas.data.repository.DefaultPlatformRepository
import dev.pocketpaas.data.repository.DefaultSiteRepository
import dev.pocketpaas.data.repository.DefaultServerStateRepository
import dev.pocketpaas.data.services.TermuxServiceManager
import dev.pocketpaas.data.ssh.TermuxSshManager
import dev.pocketpaas.data.process.ShellExecutor
import dev.pocketpaas.data.runtime.ShellRuntimeFileStore
import dev.pocketpaas.data.runtime.RuntimeSiteFileManager
import dev.pocketpaas.data.runtime.TermuxRuntimeArchiveTransfer
import dev.pocketpaas.data.deployment.ShellDeploymentWorkspace
import dev.pocketpaas.data.network.NetworkManager
import dev.pocketpaas.data.network.CloudflareHttpTransport
import dev.pocketpaas.data.network.UrlConnectionCloudflareTransport
import dev.pocketpaas.data.network.CloudflareTunnelService
import dev.pocketpaas.data.network.android.AndroidCloudflareSettingsStore
import dev.pocketpaas.domain.network.CloudflareSettingsStore
import dev.pocketpaas.domain.network.CloudflareTunnelProvider
import dev.pocketpaas.domain.network.CloudflareSitePublisher
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.deployment.RuntimeArchiveTransfer
import dev.pocketpaas.domain.network.SiteProxy
import dev.pocketpaas.domain.system.RuntimeDeploymentScriptSource
import dev.pocketpaas.data.system.AndroidSystemManager
import dev.pocketpaas.data.setup.TermuxSetupVerifier
import dev.pocketpaas.data.setup.android.AndroidSetupPreferences
import dev.pocketpaas.domain.setup.SetupPreferences
import dev.pocketpaas.domain.setup.SetupVerifier
import dev.pocketpaas.domain.setup.TermuxPermissionInspector
import dev.pocketpaas.domain.repository.PlatformRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.system.SystemManager
import dev.pocketpaas.domain.process.ProcessLauncher
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.repository.ServerStateRepository
import dev.pocketpaas.domain.system.RuntimeFileStore
import dev.pocketpaas.domain.system.RuntimeLayoutProvider
import dev.pocketpaas.domain.system.RuntimeScriptSource
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import javax.inject.Singleton
import java.time.Clock
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTermuxPermissionInspector(implementation: AndroidSystemManager): TermuxPermissionInspector = implementation

    @Provides
    @Singleton
    fun provideSetupPreferences(implementation: AndroidSetupPreferences): SetupPreferences = implementation

    @Provides
    @Singleton
    fun provideSetupVerifier(implementation: TermuxSetupVerifier): SetupVerifier = implementation

    @Provides
    @Singleton
    fun provideDashboardActions(implementation: AndroidDashboardActions): DashboardActions = implementation

    @Provides
    @Singleton
    fun provideDashboardDevice(implementation: AndroidDashboardDevice): DashboardDevice = implementation

    @Provides
    @Singleton
    fun provideDashboardNetworkState(implementation: NetworkManager): DashboardNetworkState = implementation

    @Provides
    @Singleton
    fun provideLocalSiteOperations(implementation: RemoteServerRuntimeManager): LocalSiteOperations = implementation

    @Provides
    @Singleton
    fun provideRemoteApiPolicy(): RemoteApiPolicy = RemoteApiPolicy()

    @Provides
    @Singleton
    fun provideApiKeyStore(implementation: AndroidApiKeyStore): ApiKeyStore = implementation

    @Provides
    @Singleton
    fun provideRemoteUploadStorage(@ApplicationContext context: Context): RemoteUploadStorage =
        RemoteUploadStorage(File(context.cacheDir, "remote-api-uploads"))

    @Provides
    @Singleton
    fun provideSiteFileManagerWebAssets(@ApplicationContext context: Context): SiteFileManagerWebAssets =
        SiteFileManagerWebAssets(
            html = context.assets.open("site_file_manager.html").bufferedReader(Charsets.UTF_8).use { it.readText() },
            css = context.assets.open("site_file_manager.css").bufferedReader(Charsets.UTF_8).use { it.readText() },
            javaScript = context.assets.open("site_file_manager.js").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )

    @Provides
    @Singleton
    fun provideRemoteApiServerFactory(
        engineFactory: ApplicationEngineFactory<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
        config: ServerConfig,
        policy: RemoteApiPolicy,
        keys: ApiKeyStore,
        sites: SiteRepository,
        storage: RemoteUploadStorage,
        ssh: javax.inject.Provider<dev.pocketpaas.domain.ssh.SshManager>,
        services: javax.inject.Provider<dev.pocketpaas.domain.services.ManagedServices>,
        activity: dev.pocketpaas.domain.repository.ActivityRepository,
        deployments: dev.pocketpaas.domain.repository.DeploymentHistoryRepository,
        backup: PlatformBackupManager,
        siteFiles: SiteFileManager,
        siteFileManagerWebAssets: SiteFileManagerWebAssets,
    ): RemoteApiServerFactory = NettyRemoteApiServerFactory(
        engineFactory = engineFactory,
        config = config,
        policy = policy,
        keys = keys,
        sites = sites,
        storage = storage,
        activity = activity,
        deployments = deployments,
        backup = backup,
        sshProvider = ssh::get,
        servicesProvider = services::get,
        siteFiles = siteFiles,
        siteFileManagerWebAssets = siteFileManagerWebAssets,
    )

    @Provides
    @Singleton
    fun provideRemoteServerRuntime(implementation: RemoteServerRuntimeManager): RemoteServerRuntime = implementation

    @Provides
    @Singleton
    fun provideRemoteSiteOperations(implementation: RemoteServerRuntimeManager): RemoteSiteOperations = implementation

    @Provides
    @Singleton
    fun provideManagedNetwork(implementation: NetworkManager): ManagedNetwork = implementation

    @Provides
    @Singleton
    fun provideCloudflareHttpTransport(implementation: UrlConnectionCloudflareTransport): CloudflareHttpTransport = implementation

    @Provides
    @Singleton
    fun provideCloudflareSettingsStore(implementation: AndroidCloudflareSettingsStore): CloudflareSettingsStore = implementation

    @Provides
    @Singleton
    fun provideCloudflareTunnelProvider(implementation: CloudflareTunnelService): CloudflareTunnelProvider = implementation

    @Provides
    @Singleton
    fun provideCloudflareSitePublisher(implementation: NetworkManager): CloudflareSitePublisher = implementation

    @Provides
    @Singleton
    fun provideSshPolicy(): SshPolicy = SshPolicy()

    @Provides
    @Singleton
    fun provideServicePolicy(): ServicePolicy = ServicePolicy()

    @Provides
    @Singleton
    fun provideDeploymentControl(
        implementation: DeploymentOrchestrator,
        history: dev.pocketpaas.domain.repository.DeploymentHistoryRepository,
        activity: dev.pocketpaas.domain.repository.ActivityRepository,
        clock: Clock,
    ): DeploymentControl = RecordingDeploymentControl(implementation, history, activity, clock)

    @Provides
    @Singleton
    fun providePlatformBackupManager(implementation: DefaultPlatformBackupManager): PlatformBackupManager = implementation

    @Provides
    @Singleton
    fun provideActivityEventDao(database: PocketPaaSDatabase): ActivityEventDao = database.activityEventDao()

    @Provides
    @Singleton
    fun provideDeploymentRecordDao(database: PocketPaaSDatabase): DeploymentRecordDao = database.deploymentRecordDao()

    @Provides
    @Singleton
    fun provideSshDao(database: PocketPaaSDatabase): SshDao = database.sshDao()

    @Provides
    @Singleton
    fun provideServiceStateDao(database: PocketPaaSDatabase): ServiceStateDao = database.serviceStateDao()

    @Provides
    @Singleton
    fun provideActivityRepository(implementation: DefaultActivityRepository): dev.pocketpaas.domain.repository.ActivityRepository = implementation

    @Provides
    @Singleton
    fun provideDeploymentHistoryRepository(implementation: DefaultDeploymentHistoryRepository): dev.pocketpaas.domain.repository.DeploymentHistoryRepository = implementation

    @Provides
    @Singleton
    fun provideSshManager(
        shell: ProcessExecutor,
        dao: SshDao,
        activity: dev.pocketpaas.domain.repository.ActivityRepository,
        runtime: RemoteServerRuntime,
        policy: SshPolicy,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): dev.pocketpaas.domain.ssh.SshManager = TermuxSshManager(shell, dao, activity, runtime, policy, clock, scope)

    @Provides
    @Singleton
    fun provideManagedServices(
        shell: ProcessExecutor,
        dao: ServiceStateDao,
        activity: dev.pocketpaas.domain.repository.ActivityRepository,
        runtime: RemoteServerRuntime,
        policy: ServicePolicy,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): dev.pocketpaas.domain.services.ManagedServices = TermuxServiceManager(shell, dao, activity, runtime, policy, clock, scope)

    @Provides
    @Singleton
    fun provideRuntimeRecovery(implementation: TermuxRuntimeRecovery): RuntimeRecovery = implementation

    @Provides
    @Singleton
    fun provideRuntimeReset(implementation: TermuxBridge): RuntimeReset = implementation

    @Provides
    @Singleton
    fun provideRuntimeRecoveryWorkspace(implementation: ShellDeploymentWorkspace): RuntimeRecoveryWorkspace = implementation

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PocketPaaSDatabase =
        Room.databaseBuilder(context, PocketPaaSDatabase::class.java, PocketPaaSDatabase.NAME)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3, DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideSettingsDao(database: PocketPaaSDatabase): PlatformSettingsDao =
        database.platformSettingsDao()

    @Provides
    @Singleton
    fun provideSiteDao(database: PocketPaaSDatabase): SiteDao = database.siteDao()

    @Provides
    @Singleton
    fun provideSiteRepository(implementation: DefaultSiteRepository): SiteRepository = implementation

    @Provides
    @Singleton
    fun provideSiteStoragePolicy(): SiteStoragePolicy = SiteStoragePolicy()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideServerConfig(): ServerConfig = ServerConfig()

    @Provides
    @Singleton
    fun provideProcessPolicy(): ProcessPolicy = ProcessPolicy()

    @Provides
    @Singleton
    fun provideNetworkPolicy(): NetworkPolicy = NetworkPolicy()

    @Provides
    @Singleton
    fun provideDeploymentPolicy(): DeploymentPolicy = DeploymentPolicy()

    @Provides
    @Singleton
    fun provideArchiveTransfer(implementation: TermuxRuntimeArchiveTransfer): RuntimeArchiveTransfer = implementation

    @Provides
    @Singleton
    fun provideDeploymentWorkspace(implementation: ShellDeploymentWorkspace): DeploymentWorkspace = implementation

    @Provides
    @Singleton
    fun provideSiteProxy(implementation: NetworkManager): SiteProxy = implementation

    @Provides
    @Singleton
    fun provideDeploymentScriptSource(@ApplicationContext context: Context): RuntimeDeploymentScriptSource {
        val source = lazy { context.assets.open("pocketpaas_deploy.py").bufferedReader(Charsets.UTF_8).use { it.readText() } }
        return RuntimeDeploymentScriptSource { withContext(Dispatchers.IO) { source.value } }
    }

    @Provides
    @Singleton
    fun provideProcessExecutor(implementation: ShellExecutor): ProcessExecutor = implementation

    @Provides
    @Singleton
    fun provideRuntimeFileStore(implementation: ShellRuntimeFileStore): RuntimeFileStore = implementation

    @Provides
    @Singleton
    fun provideRuntimeLayoutProvider(process: ProcessExecutor): RuntimeLayoutProvider =
        RuntimeLayoutProvider { process.runtimeLayout() }

    @Provides
    @Singleton
    fun provideSiteFileManager(implementation: RuntimeSiteFileManager): SiteFileManager = implementation

    @Provides
    @Singleton
    fun provideRuntimeScriptSource(@ApplicationContext context: Context): RuntimeScriptSource {
        val source = lazy { context.assets.open("pocketpaas_files.py").bufferedReader(Charsets.UTF_8).use { it.readText() } }
        return RuntimeScriptSource { withContext(Dispatchers.IO) { source.value } }
    }

    @Provides
    @Singleton
    fun provideServerStateRepository(implementation: DefaultServerStateRepository): ServerStateRepository = implementation

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideTermuxCredentialsStore(
        @ApplicationContext context: Context,
    ): TermuxBridgeCredentialsStore = AndroidTermuxCredentialsStore(context)

    @Provides
    @Singleton
    fun provideTermuxAgentBootstrap(
        @ApplicationContext context: Context,
    ): TermuxAgentBootstrap = AndroidTermuxAgentBootstrap(context)

    @Provides
    @Singleton
    fun provideTermuxBridge(
        credentialsStore: TermuxBridgeCredentialsStore,
        bootstrap: TermuxAgentBootstrap,
        gson: Gson,
        @ApplicationScope applicationScope: CoroutineScope,
    ): TermuxBridge = TermuxBridge(credentialsStore, bootstrap, gson, applicationScope)

    @Provides
    @Singleton
    fun provideProcessLauncher(
        bridge: TermuxBridge,
        @ApplicationScope applicationScope: CoroutineScope,
    ): ProcessLauncher = TermuxProcessLauncher(bridge, applicationScope)

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideSystemManager(implementation: AndroidSystemManager): SystemManager = implementation

    @Provides
    @Singleton
    fun providePlatformRepository(implementation: DefaultPlatformRepository): PlatformRepository =
        implementation

    @Provides
    @Singleton
    fun provideKtorEngineFactory():
        ApplicationEngineFactory<NettyApplicationEngine, NettyApplicationEngine.Configuration> = Netty

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
