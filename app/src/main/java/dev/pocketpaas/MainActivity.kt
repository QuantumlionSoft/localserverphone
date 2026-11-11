package dev.pocketpaas

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketpaas.data.remote.android.RemoteServerService
import dev.pocketpaas.presentation.dashboard.DashboardScreen
import dev.pocketpaas.presentation.dashboard.MainViewModel
import dev.pocketpaas.presentation.theme.PocketPaaSTheme
import dev.pocketpaas.presentation.setup.SetupScreen
import dev.pocketpaas.presentation.setup.SetupViewModel
import dev.pocketpaas.domain.setup.TermuxSetupInstructions
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.setup.ForegroundStartGate
import dev.pocketpaas.domain.setup.TermuxPermissionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val setupViewModel: SetupViewModel by viewModels()
    private val startGate = ForegroundStartGate()
    private var pendingNotificationTicket: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketPaaSTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val setup by setupViewModel.state.collectAsStateWithLifecycle()
                var showSetupOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    pendingNotificationTicket?.let { ticket ->
                        pendingNotificationTicket = null
                        startGate.permissionResult(ticket)
                        if (startGate.takeReady()) viewModel.masterSwitch(true)
                        if (!granted) viewModel.showMessage("Bildirim izni verilmedi; sunucuyu Android'in etkin uygulamalar alanından da durdurabilirsiniz.")
                    }
                }
                val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    viewModel.refresh()
                    setupViewModel.refreshPermission()
                    if (!granted) setupViewModel.showError("İzin açılmadı. İzin adımındaki Pocket PaaS ayarlarını aç düğmesini kullanın; İzinler veya Ek izinler bölümünü kontrol edin.")
                }
                val startServer: () -> Unit = {
                    val ticket = startGate.request()
                    if (ticket != null) {
                        if (setupViewModel.state.value.permissionStatus?.granted != true) {
                            startGate.cancel()
                            setupViewModel.showError("Başlatmadan önce İzin adımını tamamlayın. Termux sürümü bu bağlantıyı desteklemiyorsa uyumlu sürüm açıklamasını izleyin.")
                            showSetupOverride = true
                        } else if (viewModel.state.value.setupReady && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingNotificationTicket = ticket
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startGate.permissionResult(ticket)
                            if (startGate.takeReady()) viewModel.masterSwitch(true)
                        }
                    }
                }
                val requestTermuxPermission: () -> Unit = {
                    when (setupViewModel.state.value.permissionStatus?.state) {
                        TermuxPermissionState.GRANTED -> {
                            viewModel.refresh()
                            setupViewModel.refreshPermission()
                            viewModel.showMessage("Komut izni zaten açık; Android yeniden bir izin penceresi göstermeyebilir. Sonraki adıma geçebilirsiniz.")
                        }
                        TermuxPermissionState.DENIED -> termuxPermission.launch("com.termux.permission.RUN_COMMAND")
                        else -> {
                            setupViewModel.refreshPermission()
                            setupViewModel.showError("Bu Termux kurulumunda izin henüz kullanılabilir değil. İzin ekranındaki sürüm açıklamasını ve Uyumlu Termux seçeneğini kontrol edin.")
                            showSetupOverride = true
                        }
                    }
                }
                val currentStart by rememberUpdatedState(startServer)
                val setupVisible by rememberUpdatedState(showSetupOverride ?: !setup.completed)
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        setupViewModel.state.collect { latest ->
                            latest.startRequest?.let { id ->
                                if (setupVisible && setupViewModel.consumeStartRequest(id)) currentStart()
                            }
                        }
                    }
                }
                LaunchedEffect(setup.verified, setup.completed, state.runtime.status, state.network.nginxStatus) {
                    if (setup.verified && state.runtime.status == RemoteServerStatus.RUNNING &&
                        state.network.nginxStatus == NetworkServiceStatus.RUNNING
                    ) {
                        setupViewModel.complete()
                        if (setup.completed) showSetupOverride = false
                    }
                }
                if (setup.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (showSetupOverride ?: !setup.completed) {
                    SetupScreen(
                        state = state,
                        setupCommand = TermuxSetupInstructions.command,
                        commandCopied = setup.commandCopied,
                        onInstallTermux = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) },
                        onOpenTermux = {
                            val launch = packageManager.getLaunchIntentForPackage("com.termux")
                            if (launch == null) setupViewModel.showError("Termux bulunamadı. Önce Termux'u yükleyin.")
                            else openExternal(launch)
                        },
                        onCopySetupCommand = {
                            try {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("Pocket PaaS kurulum komutu", TermuxSetupInstructions.command),
                                )
                                setupViewModel.commandCopied()
                            } catch (_: Exception) { setupViewModel.showError("Komut panoya kopyalanamadı. Yeniden deneyin.") }
                        },
                        onRequestPermission = requestTermuxPermission,
                        onRefresh = { viewModel.refresh(); setupViewModel.refreshPermission() },
                        onStart = setupViewModel::checkAndStart,
                        onOpenDashboard = { startGate.cancel(); setupViewModel.cancelCheck(); showSetupOverride = false },
                        checking = setup.checking,
                        verificationError = setup.error ?: setup.permissionError,
                        onOpenPermissionSettings = {
                            openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        },
                        onOpenTermuxSettings = {
                            openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.termux", null)))
                        },
                        versionLabel = "${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}",
                        permissionStatus = setup.permissionStatus,
                        inspectingPermission = setup.inspectingPermission,
                    )
                } else DashboardScreen(
                    state = state,
                    onMasterSwitch = { enabled ->
                        if (enabled) startServer() else {
                            startGate.cancel()
                            pendingNotificationTicket = null
                            viewModel.masterSwitch(enabled)
                        }
                    },
                    onLanAccessChanged = viewModel::setLanAccess,
                    onRetryNetwork = viewModel::retryNetwork,
                    onCloudflareApiTokenChanged = viewModel::setCloudflareApiToken,
                    onCloudflareAccountIdChanged = viewModel::setCloudflareAccountId,
                    onCloudflareZoneNameChanged = viewModel::setCloudflareZoneName,
                    onConfigureCloudflare = viewModel::configureCloudflare,
                    onDisconnectCloudflare = viewModel::disconnectCloudflare,
                    onSetSiteCustomHostname = viewModel::setSiteCustomHostname,
                    onRefresh = { viewModel.refresh(); setupViewModel.refreshPermission() },
                    onRequestTermuxPermission = requestTermuxPermission,
                    onAddProject = viewModel::showAddProject,
                    onDismissAddProject = viewModel::dismissAddProject,
                    onDomainChanged = viewModel::setDomain,
                    onProjectTypeChanged = viewModel::setProjectType,
                    onZipSelected = { viewModel.selectZip(it.toString()) },
                    onDeploy = viewModel::deploy,
                    onSiteSelected = viewModel::selectSite,
                    onDismissLogs = viewModel::dismissLogs,
                    onManageSiteFiles = viewModel::openSiteFileManager,
                    onCloseSiteFileManager = viewModel::closeSiteFileManager,
                    onNavigateSiteFiles = viewModel::navigateSiteFileDirectory,
                    onOpenSiteTextFile = viewModel::openSiteTextFile,
                    onEditSiteFileContent = viewModel::setSiteFileEditorContent,
                    onSaveSiteTextFile = viewModel::saveSiteTextFile,
                    onCreateSiteTextFile = viewModel::createSiteTextFile,
                    onCreateSiteFileDirectory = viewModel::createSiteFileDirectory,
                    onDeleteSiteTextFile = viewModel::deleteSiteTextFile,
                    onRefreshSiteFiles = viewModel::refreshSiteFiles,
                    onStartSite = viewModel::startSite,
                    onStopSite = viewModel::stopSite,
                    onCancelOperation = viewModel::cancelOperation,
                    onCopyApiKey = viewModel::copyApiKey,
                    onNoticeConsumed = viewModel::consumeNotice,
                    onOpenSetup = { startGate.cancel(); setupViewModel.cancelCheck(); setupViewModel.refreshPermission(); showSetupOverride = true },
                    onSshToggle = viewModel::toggleSsh,
                    onSshPortChanged = viewModel::setSshPort,
                    onSshPasswordAuthChanged = viewModel::setSshPasswordAuth,
                    onSshLanChanged = viewModel::setSshLan,
                    onShowSshSheet = viewModel::showSshSheet,
                    onDismissSshSheet = viewModel::dismissSshSheet,
                    onSshKeyNameChanged = viewModel::setSshKeyName,
                    onSshPublicKeyChanged = viewModel::setSshPublicKey,
                    onAddSshKey = viewModel::addSshKey,
                    onRemoveSshKey = viewModel::removeSshKey,
                    onInstallService = viewModel::installService,
                    onStartService = viewModel::startService,
                    onStopService = viewModel::stopService,
                    onActivitySearchQueryChanged = viewModel::setActivitySearchQuery,
                    onActivityLevelFilterChanged = viewModel::setActivityLevelFilter,
                    onActivityCategoryFilterChanged = viewModel::setActivityCategoryFilter,
                    onClearActivityFilters = viewModel::clearActivityFilters,
                    onExportLogs = viewModel::exportLogs,
                    onClearActivityLogs = viewModel::clearActivityLogs,
                    onShowBackupSheet = viewModel::showBackupSheet,
                    onDismissBackupSheet = viewModel::dismissBackupSheet,
                    onBackupImportJsonChanged = viewModel::setBackupImportJson,
                    onBackupRequireSignatureChanged = viewModel::setBackupRequireSignature,
                    onExportBackup = viewModel::exportBackup,
                    onImportBackup = viewModel::importBackup,
                    onSelectTab = viewModel::selectTab,
                    onForceStopAll = {
                        startGate.cancel()
                        pendingNotificationTicket = null
                        setupViewModel.cancelCheck()
                        viewModel.forceStopAll()
                        runCatching { stopService(Intent(this@MainActivity, RemoteServerService::class.java)) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startGate.onResume()
        viewModel.refresh()
        setupViewModel.refreshPermission()
        if (startGate.takeReady()) viewModel.masterSwitch(true)
    }

    override fun onPause() {
        startGate.onPause()
        super.onPause()
    }

    override fun onStop() {
        startGate.onStop()
        pendingNotificationTicket = null
        setupViewModel.onHidden()
        super.onStop()
    }

    private fun openExternal(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { setupViewModel.showError("Bu işlem açılamadı. Termux'u veya tarayıcınızı kontrol edin.") }
        catch (_: SecurityException) { setupViewModel.showError("Android bu uygulamanın açılmasına izin vermedi.") }
    }
}
