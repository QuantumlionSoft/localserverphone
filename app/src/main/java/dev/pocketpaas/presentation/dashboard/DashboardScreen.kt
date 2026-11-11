@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pocketpaas.presentation.dashboard

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpaas.BuildConfig
import dev.pocketpaas.domain.logs.LogExportFormat
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.TunnelMode
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ServiceCategory
import dev.pocketpaas.presentation.theme.PocketPaaSTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onMasterSwitch: (Boolean) -> Unit,
    onLanAccessChanged: (Boolean) -> Unit,
    onRetryNetwork: () -> Unit,
    onCloudflareApiTokenChanged: (String) -> Unit = {},
    onCloudflareAccountIdChanged: (String) -> Unit = {},
    onCloudflareZoneNameChanged: (String) -> Unit = {},
    onConfigureCloudflare: () -> Unit = {},
    onDisconnectCloudflare: () -> Unit = {},
    onSetSiteCustomHostname: (Long, String?) -> Unit = { _, _ -> },
    onRefresh: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onAddProject: () -> Unit,
    onDismissAddProject: () -> Unit,
    onDomainChanged: (String) -> Unit,
    onProjectTypeChanged: (ProjectType) -> Unit,
    onZipSelected: (Uri) -> Unit,
    onDeploy: () -> Unit,
    onSiteSelected: (Long) -> Unit,
    onDismissLogs: () -> Unit,
    onStartSite: (Long) -> Unit,
    onStopSite: (Long) -> Unit,
    onCancelOperation: () -> Unit,
    onCopyApiKey: () -> Unit,
    onNoticeConsumed: (Long) -> Unit,
    onOpenSetup: (() -> Unit)? = null,
    onManageSiteFiles: (Long) -> Unit = {},
    onCloseSiteFileManager: () -> Unit = {},
    onNavigateSiteFiles: (String) -> Unit = {},
    onOpenSiteTextFile: (String) -> Unit = {},
    onEditSiteFileContent: (String) -> Unit = {},
    onSaveSiteTextFile: () -> Unit = {},
    onCreateSiteTextFile: (String) -> Unit = {},
    onCreateSiteFileDirectory: (String) -> Unit = {},
    onDeleteSiteTextFile: (String) -> Unit = {},
    onRefreshSiteFiles: () -> Unit = {},
    // SSH
    onSshToggle: (Boolean) -> Unit = {},
    onSshPortChanged: (Int) -> Unit = {},
    onSshPasswordAuthChanged: (Boolean) -> Unit = {},
    onSshLanChanged: (Boolean) -> Unit = {},
    onShowSshSheet: () -> Unit = {},
    onDismissSshSheet: () -> Unit = {},
    onSshKeyNameChanged: (String) -> Unit = {},
    onSshPublicKeyChanged: (String) -> Unit = {},
    onAddSshKey: () -> Unit = {},
    onRemoveSshKey: (Long) -> Unit = {},
    // Services
    onInstallService: (String) -> Unit = {},
    onStartService: (String) -> Unit = {},
    onStopService: (String) -> Unit = {},
    // Activity & Backup
    onActivitySearchQueryChanged: (String) -> Unit = {},
    onActivityLevelFilterChanged: (ActivityLevel?) -> Unit = {},
    onActivityCategoryFilterChanged: (ActivityCategory?) -> Unit = {},
    onClearActivityFilters: () -> Unit = {},
    onExportLogs: (LogExportFormat) -> Unit = {},
    onClearActivityLogs: () -> Unit = {},
    onShowBackupSheet: () -> Unit = {},
    onDismissBackupSheet: () -> Unit = {},
    onBackupImportJsonChanged: (String) -> Unit = {},
    onBackupRequireSignatureChanged: (Boolean) -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onSelectTab: (DashboardTab) -> Unit = {},
    onForceStopAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showSetupHelp by rememberSaveable { mutableStateOf(false) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onZipSelected)
    }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            try {
                snackbar.showSnackbar(notice.text)
            } finally {
                onNoticeConsumed(notice.id)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DashboardSidebar(
                state = state,
                showSetupHelp = showSetupHelp,
                onSelectTab = { tab ->
                    onSelectTab(tab)
                    scope.launch { drawerState.close() }
                },
                onAddProject = {
                    scope.launch { drawerState.close() }
                    onAddProject()
                },
                onShowSshSheet = {
                    onSelectTab(DashboardTab.SERVICES)
                    scope.launch { drawerState.close() }
                    onShowSshSheet()
                },
                onShowBackupSheet = {
                    scope.launch { drawerState.close() }
                    onShowBackupSheet()
                },
                onToggleSetupHelp = {
                    showSetupHelp = !showSetupHelp
                    scope.launch { drawerState.close() }
                },
                onOpenSetup = onOpenSetup?.let { open ->
                    {
                        scope.launch { drawerState.close() }
                        open()
                    }
                },
                onRefresh = {
                    scope.launch { drawerState.close() }
                    onRefresh()
                },
                onForceStopAll = {
                    scope.launch { drawerState.close() }
                    onForceStopAll()
                },
            )
        },
    ) {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!state.showAddProject && state.selectedSite == null) {
                    ExtendedFloatingActionButton(
                        onClick = onAddProject,
                        modifier = Modifier.testTag("add-project").semantics { contentDescription = "Yeni proje ekle" },
                        icon = { PlusMark() },
                        text = { Text("Yeni proje") },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("dashboard-list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "header") {
                        DashboardHeader(
                            onOpenSidebar = { scope.launch { drawerState.open() } },
                            onRefresh = onRefresh,
                            showSetupHelp = showSetupHelp,
                            onSetupHelp = { showSetupHelp = !showSetupHelp },
                            onOpenSetup = onOpenSetup,
                            onShowBackupSheet = onShowBackupSheet,
                        )
                    }
                    item(key = "master") {
                        MasterCard(state, onMasterSwitch, onLanAccessChanged, onCopyApiKey, onForceStopAll)
                    }
                if (showSetupHelp || state.initializing || !state.setupReady || state.setupFailed ||
                    state.runtime.status == RemoteServerStatus.FAILED || state.network.nginxStatus == NetworkServiceStatus.FAILED ||
                    state.network.tunnelStatus == NetworkServiceStatus.FAILED
                ) {
                    item(key = "setup") { SetupCard(state, onRefresh, onRequestTermuxPermission) }
                }
                item(key = "tunnel") {
                    TunnelCard(state, onRetryNetwork) { url ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Tünel adresi", url)))
                            snackbar.showSnackbar("Tünel adresi kopyalandı")
                        }
                    }
                }
                item(key = "cloudflare-management") {
                    CloudflareManagementCard(
                        state = state,
                        onApiTokenChanged = onCloudflareApiTokenChanged,
                        onAccountIdChanged = onCloudflareAccountIdChanged,
                        onZoneNameChanged = onCloudflareZoneNameChanged,
                        onConfigure = onConfigureCloudflare,
                        onDisconnect = onDisconnectCloudflare,
                    )
                }
                if (state.operationBusy) {
                    item(key = "operation") { OperationCard(state.operationDescription, onCancelOperation) }
                }
                item(key = "tabs") { DashboardTabs(state.selectedTab, onSelectTab) }
                when (state.selectedTab) {
                    DashboardTab.PROJECTS -> {
                        item(key = "sites-title") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Projeler", modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                                Text("${state.sites.size} proje", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            state.siteReadFailed -> item(key = "sites-error") {
                                InformationCard("Projeler okunamadı", "Kayıtlı projeler için veritabanını yeniden kontrol edin.") {
                                    TextButton(onClick = onRefresh) { Text("Tekrar dene") }
                                }
                            }
                            state.loadingSites -> item(key = "sites-loading") {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("Projeler yükleniyor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            state.sites.isEmpty() -> item(key = "sites-empty") {
                                InformationCard("İlk projenize yer açın", "Bir ZIP seçin; kurulum, uygulama süreci ve yönlendirme buradan yönetilsin.") {
                                    TextButton(onClick = onAddProject) { Text("Proje ekle") }
                                }
                            }
                        }
                        items(state.sites, key = { it.id }) { site ->
                            SiteCard(
                                site = site,
                                canManage = state.canManageSites,
                                canManageFiles = state.canManageSiteFiles,
                                cloudflareConfigured = state.cloudflareConnection.configured,
                                canManageHostname = state.canManageCustomHostnames,
                                cloudflareBusy = state.cloudflareBusy,
                                onLogs = { onSiteSelected(site.id) },
                                onStart = { onStartSite(site.id) },
                                onStop = { onStopSite(site.id) },
                                onFiles = { onManageSiteFiles(site.id) },
                                onSetHostname = onSetSiteCustomHostname,
                            )
                        }
                    }
                    DashboardTab.SERVICES -> {
                        item(key = "ssh-card") {
                            SshCard(state, onSshToggle, onSshPortChanged, onSshPasswordAuthChanged, onSshLanChanged, onShowSshSheet, onRemoveSshKey) { url ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SSH bağlantısı", url)))
                                    snackbar.showSnackbar("SSH komutu kopyalandı")
                                }
                            }
                        }
                        item(key = "services-title") {
                            Text("Veritabanı servisleri", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        }
                        if (state.managedServices.isEmpty()) {
                            item(key = "services-loading") {
                                InformationCard("Servisler hazırlanıyor", "Durumlar yükleniyor. Yenile düğmesine dokunun.")
                            }
                        } else {
                            state.managedServices.filter { it.category == ServiceCategory.DATABASE }.forEach { service ->
                                item(key = "service-${service.serviceKey}") {
                                    ServiceCard(service, state.serverRunning && !state.operationBusy, onInstallService, onStartService, onStopService)
                                }
                            }
                            // Core / access read-only overview
                            state.managedServices.filter { it.category != ServiceCategory.DATABASE }.forEach { service ->
                                item(key = "service-${service.serviceKey}") {
                                    ServiceCard(service, false, onInstallService, onStartService, onStopService)
                                }
                            }
                        }
                    }
                    DashboardTab.ACTIVITY -> {
                        item(key = "activity-title") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Aktivite ve yedekleme", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                                OutlinedButton(onClick = onShowBackupSheet, modifier = Modifier.testTag("open-backup-sheet")) {
                                    Text("Yedekleme")
                                }
                            }
                        }
                        item(key = "activity-controls") {
                            ActivityFilterCard(
                                state = state,
                                onQueryChanged = onActivitySearchQueryChanged,
                                onLevelChanged = onActivityLevelFilterChanged,
                                onCategoryChanged = onActivityCategoryFilterChanged,
                                onClearFilters = onClearActivityFilters,
                                onExportJson = { onExportLogs(LogExportFormat.JSON) },
                                onExportCsv = { onExportLogs(LogExportFormat.CSV) },
                                onClearLogs = onClearActivityLogs,
                            )
                        }
                        val visibleDeployments = state.filteredDeployments
                        val visibleActivity = state.filteredActivity
                        if (visibleActivity.isEmpty() && visibleDeployments.isEmpty()) {
                            item(key = "activity-empty") {
                                InformationCard(
                                    title = if (state.activityFilter.isActive) "Filtreyle eşleşen kayıt yok" else "Henüz kayıt yok",
                                    description = if (state.activityFilter.isActive) "Arama metnini veya filtre seçimlerini temizleyip tekrar deneyin." else "Yayınlar ve sistem olayları burada listelenir.",
                                )
                            }
                        }
                        if (visibleDeployments.isNotEmpty()) {
                            item(key = "deployments-title") {
                                Text("Son yayınlar (${visibleDeployments.size})", style = MaterialTheme.typography.titleMedium)
                            }
                            visibleDeployments.forEach { record ->
                                item(key = "deployment-${record.id}") { DeploymentRow(record) }
                            }
                        }
                        if (visibleActivity.isNotEmpty()) {
                            item(key = "activity-subtitle") {
                                Text("Sistem olayları (${visibleActivity.size})", style = MaterialTheme.typography.titleMedium)
                            }
                            visibleActivity.take(50).forEach { event ->
                                item(key = "activity-${event.id}") { ActivityRow(event) }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (state.showAddProject) {
        AddProjectSheet(
            state = state,
            onDismiss = onDismissAddProject,
            onDomainChanged = onDomainChanged,
            onTypeChanged = onProjectTypeChanged,
            onChooseZip = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onDeploy = onDeploy,
            onCancel = onCancelOperation,
        )
    }
    if (state.showSshSheet) {
        SshKeySheet(
            draft = state.sshDraft,
            onDismiss = onDismissSshSheet,
            onNameChanged = onSshKeyNameChanged,
            onKeyChanged = onSshPublicKeyChanged,
            onAdd = onAddSshKey,
        )
    }
    if (state.showBackupSheet) {
        BackupRestoreSheet(
            draft = state.backupDraft,
            onDismiss = onDismissBackupSheet,
            onImportJsonChanged = onBackupImportJsonChanged,
            onRequireSignatureChanged = onBackupRequireSignatureChanged,
            onExportBackup = onExportBackup,
            onImportBackup = onImportBackup,
        )
    }
    state.siteFileManager.siteId?.let { siteId ->
        state.sites.firstOrNull { it.id == siteId }?.let { site ->
            SiteFileManagerSheet(
                site = site,
                state = state.siteFileManager,
                onDismiss = onCloseSiteFileManager,
                onNavigate = onNavigateSiteFiles,
                onOpenFile = onOpenSiteTextFile,
                onContentChanged = onEditSiteFileContent,
                onSave = onSaveSiteTextFile,
                onCreateFile = onCreateSiteTextFile,
                onCreateDirectory = onCreateSiteFileDirectory,
                onDeleteFile = onDeleteSiteTextFile,
                onRefresh = onRefreshSiteFiles,
            )
        }
    }
    state.selectedSite?.takeIf { !state.showAddProject }?.let { site ->
        LogsSheet(site, state.canManageSites, onDismissLogs, { onStartSite(site.id) }, { onStopSite(site.id) })
    }
}

@Composable
private fun DashboardSidebar(
    state: DashboardUiState,
    showSetupHelp: Boolean,
    onSelectTab: (DashboardTab) -> Unit,
    onAddProject: () -> Unit,
    onShowSshSheet: () -> Unit,
    onShowBackupSheet: () -> Unit,
    onToggleSetupHelp: () -> Unit,
    onOpenSetup: (() -> Unit)?,
    onRefresh: () -> Unit,
    onForceStopAll: () -> Unit,
) {
    val runningDbCount = state.managedServices.count {
        it.category == ServiceCategory.DATABASE && it.status == ManagedServiceStatus.RUNNING
    }
    val sshRunning = state.ssh.status == NetworkServiceStatus.RUNNING
    ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp).testTag("sidebar-drawer")) {
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerMark()
                Column {
                    Text("POCKET PAAS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Kontrol Merkezi", style = MaterialTheme.typography.titleLarge)
                    Text("Sürüm ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Canlı Durum Özeti", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StateBadge(
                            label = "Sunucu · ${state.runtime.status.shortLabel}",
                            active = state.runtime.status == RemoteServerStatus.RUNNING,
                            failed = state.runtime.status == RemoteServerStatus.FAILED,
                        )
                        NetworkBadge("Nginx", state.network.nginxStatus)
                        NetworkBadge("Tünel", state.network.tunnelStatus)
                        NetworkBadge("SSH", state.ssh.status)
                        StateBadge("DB · $runningDbCount aktif", runningDbCount > 0, false)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("BÖLÜMLER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavigationDrawerItem(
                label = { Text("Projeler ve Siteler") },
                badge = { Text("${state.sites.size}") },
                selected = state.selectedTab == DashboardTab.PROJECTS,
                onClick = { onSelectTab(DashboardTab.PROJECTS) },
                modifier = Modifier.testTag("sidebar-tab-projects"),
            )
            NavigationDrawerItem(
                label = { Text("SSH ve Veritabanı Servisleri") },
                badge = { Text("${runningDbCount + if (sshRunning) 1 else 0} aktif") },
                selected = state.selectedTab == DashboardTab.SERVICES,
                onClick = { onSelectTab(DashboardTab.SERVICES) },
                modifier = Modifier.testTag("sidebar-tab-services"),
            )
            NavigationDrawerItem(
                label = { Text("Aktivite, Günlükler ve Yedek") },
                badge = { Text("${state.activity.size}") },
                selected = state.selectedTab == DashboardTab.ACTIVITY,
                onClick = { onSelectTab(DashboardTab.ACTIVITY) },
                modifier = Modifier.testTag("sidebar-tab-activity"),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("HIZLI İŞLEMLER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavigationDrawerItem(
                label = { Text("Yeni Proje Yükle (ZIP)") },
                selected = false,
                onClick = onAddProject,
                modifier = Modifier.testTag("sidebar-add-project"),
            )
            NavigationDrawerItem(
                label = { Text("SSH Yetkili Anahtarı Ekle") },
                selected = false,
                onClick = onShowSshSheet,
                modifier = Modifier.testTag("sidebar-add-ssh-key"),
            )
            NavigationDrawerItem(
                label = { Text("Yedekleme ve Geri Yükleme") },
                selected = false,
                onClick = onShowBackupSheet,
                modifier = Modifier.testTag("sidebar-backup"),
            )
            NavigationDrawerItem(
                label = { Text(if (showSetupHelp) "Termux Yardımını Gizle" else "Termux Kurulum Komutları") },
                selected = showSetupHelp,
                onClick = onToggleSetupHelp,
                modifier = Modifier.testTag("sidebar-termux-help"),
            )
            onOpenSetup?.let { open ->
                NavigationDrawerItem(
                    label = { Text("Kolay Kurulum Sihirbazı") },
                    selected = false,
                    onClick = open,
                    modifier = Modifier.testTag("sidebar-easy-setup"),
                )
            }
            NavigationDrawerItem(
                label = { Text("Durumu Yenile") },
                selected = false,
                onClick = onRefresh,
                modifier = Modifier.testTag("sidebar-refresh"),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("GÜÇ VE TEMİZLİK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = onForceStopAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth().testTag("sidebar-force-stop"),
            ) {
                Text("Her Şeyi Durdur ve Sıfırla")
            }
            Text(
                "Termux'u arka planda tetiklemeden ana sunucu, SSH, veritabanı ve askıda kalan tüm durumları anında durdurur ve sıfırlar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    onOpenSidebar: () -> Unit,
    onRefresh: () -> Unit,
    showSetupHelp: Boolean,
    onSetupHelp: () -> Unit,
    onOpenSetup: (() -> Unit)?,
    onShowBackupSheet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenSidebar,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("open-sidebar").semantics { contentDescription = "Kenar menüsünü aç" },
            ) {
                MenuMark()
                Text("Menü", modifier = Modifier.padding(start = 6.dp))
            }
            ServerMark()
            Column(Modifier.weight(1f)) {
                Text("POCKET PAAS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary)
                Text("Cebindeki sunucu", style = MaterialTheme.typography.headlineMedium)
                Text("Sürüm ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh-dashboard")) { Text("Yenile") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSetupHelp, modifier = Modifier.testTag("termux-help")) {
                Text(if (showSetupHelp) "Kurulum yardımını gizle" else "Termux kurulumu")
            }
            onOpenSetup?.let { open ->
                TextButton(onClick = open, modifier = Modifier.testTag("easy-setup")) { Text("Kolay kurulum") }
            }
            TextButton(onClick = onShowBackupSheet) { Text("Yedekleme") }
        }
    }
}

@Composable
private fun MasterCard(
    state: DashboardUiState,
    onMasterSwitch: (Boolean) -> Unit,
    onLanAccessChanged: (Boolean) -> Unit,
    onCopyApiKey: () -> Unit,
    onForceStopAll: () -> Unit = {},
) {
    val status = state.runtime.status
    val checked = status == RemoteServerStatus.STARTING || status == RemoteServerStatus.RUNNING || status == RemoteServerStatus.STOPPING
    val canToggle = status != RemoteServerStatus.STOPPING && status != RemoteServerStatus.FAILED &&
        (checked || (state.setupReady && !state.initializing && !state.setupFailed))
    val runningDbCount = state.managedServices.count {
        it.category == ServiceCategory.DATABASE && it.status == ManagedServiceStatus.RUNNING
    }
    val hasActiveOrFailedWork = status != RemoteServerStatus.STOPPED ||
        state.operationBusy ||
        state.ssh.status != NetworkServiceStatus.STOPPED ||
        runningDbCount > 0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Ana sunucu", style = MaterialTheme.typography.titleLarge)
                    Text(status.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onMasterSwitch,
                    enabled = canToggle,
                    modifier = Modifier.testTag("master-switch").semantics {
                        contentDescription = "Sunucu ve tüneli başlat veya durdur"
                        stateDescription = status.label
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkBadge("Nginx", state.network.nginxStatus)
                NetworkBadge("Tünel", state.network.tunnelStatus)
                NetworkBadge("SSH", state.ssh.status)
                StateBadge("DB · $runningDbCount aktif", runningDbCount > 0, false)
            }
            if (status == RemoteServerStatus.STARTING || status == RemoteServerStatus.STOPPING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.runtime.error?.let { ErrorText(it) }
            if (status == RemoteServerStatus.FAILED || hasActiveOrFailedWork) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == RemoteServerStatus.FAILED) {
                        OutlinedButton(onClick = { onMasterSwitch(false) }, modifier = Modifier.testTag("retry-cleanup")) {
                            Text("Temizliği yeniden dene")
                        }
                    }
                    OutlinedButton(
                        onClick = onForceStopAll,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("force-stop-all"),
                    ) {
                        Text("Her şeyi durdur")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(
                    checked = state.lanAccess,
                    onCheckedChange = onLanAccessChanged,
                    enabled = status == RemoteServerStatus.STOPPED && !state.initializing,
                    modifier = Modifier.testTag("lan-access").semantics { contentDescription = "Yerel ağdan API erişimi" },
                )
                Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Yerel ağdan yönet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "API :${state.apiPort}. Varsayılan erişim yalnızca bu cihazdandır. LAN erişimi HTTP kullanır; şifreleme yoktur. Yalnız güvendiğiniz ağda açın; Bearer anahtarı gerekir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.serverRunning) {
                TextButton(onClick = onCopyApiKey, modifier = Modifier.testTag("copy-api-key")) {
                    Text("API anahtarını kopyala")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(state: DashboardUiState, onRefresh: () -> Unit, onPermission: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Termux bağlantısı", style = MaterialTheme.typography.titleMedium)
            if (state.initializing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Çalışma ortamı kontrol ediliyor…")
            } else {
                Text(
                    when {
                        state.setupFailed -> "Çalışma ortamı doğrulanamadı. Termux kurulumunu ve komut iznini kontrol edin."
                        state.capabilities?.termuxInstalled != true -> "Önce Termux’u kurup bir kez açın. Ardından aşağıdaki paketleri Termux içinde yükleyin."
                        state.setupReady -> "Paketleri ve harici uygulama ayarını Termux içinde kontrol edin."
                        else -> "Termux komut iznini verin ve Termux ayarlarında harici uygulamalara erişimi etkinleştirin."
                    },
                )
                SelectionContainer {
                    Text(
                        "Paketleri yükleyin:\npkg install python nginx nodejs cloudflared\n\nDüzenlenecek dosya:\n~/.termux/termux.properties\nEklenecek satır:\nallow-external-apps=true\n\nAyarı yeniden yükleyin:\ntermux-reload-settings",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Bu ayarı mevcut termux.properties dosyanıza ekleyin; Termux açık ve kurulumu tamamlanmış olmalıdır.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.capabilities?.let { it.termuxInstalled && !it.termuxCommandPermissionGranted } == true) {
                        Button(onClick = onPermission, modifier = Modifier.testTag("termux-permission")) { Text("Komut izni ver") }
                    }
                    TextButton(onClick = onRefresh) { Text("Yeniden kontrol et") }
                }
            }
        }
    }
}

@Composable
private fun CloudflareManagementCard(
    state: DashboardUiState,
    onApiTokenChanged: (String) -> Unit,
    onAccountIdChanged: (String) -> Unit,
    onZoneNameChanged: (String) -> Unit,
    onConfigure: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state.cloudflareConnection.configured
    val hasCustomHosts = state.sites.any { it.customHostname != null }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("cloudflare-management"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Özel alan adları · Cloudflare", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connected) "Bağlı · ${state.cloudflareConnection.zoneName}"
                    else "Named Tunnel ve DNS kayıtlarını uygulamadan yönetin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "API token kapsamları: hesapta Cloudflare Tunnel Write; zone için Zone Read, DNS Edit ve DNS Read. API ve Tunnel run tokenları Android Keystore ile şifrelenir; yedeklere eklenmez.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.cloudflareDraft.apiToken,
                onValueChange = onApiTokenChanged,
                modifier = Modifier.fillMaxWidth().testTag("cloudflare-token"),
                enabled = !state.cloudflareBusy,
                singleLine = true,
                label = { Text("Cloudflare API token") },
                placeholder = { Text(if (connected) "Aynı token için boş bırakın" else "Token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.cloudflareDraft.accountId,
                onValueChange = onAccountIdChanged,
                modifier = Modifier.fillMaxWidth().testTag("cloudflare-account-id"),
                enabled = !state.cloudflareBusy,
                singleLine = true,
                label = { Text("Cloudflare Account ID") },
                placeholder = { Text("32 karakterlik hesap kimliği") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = state.cloudflareDraft.zoneName,
                onValueChange = onZoneNameChanged,
                modifier = Modifier.fillMaxWidth().testTag("cloudflare-zone-name"),
                enabled = !state.cloudflareBusy,
                singleLine = true,
                label = { Text("Yönetilecek zone adı") },
                placeholder = { Text("example.com") },
                supportingText = { Text("Site başına özel hostname bu zone içinde olmalıdır.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            state.cloudflareError?.let { ErrorText(it) }
            if (state.cloudflareBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Cloudflare ayarları güncelleniyor…", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onConfigure,
                    enabled = !state.cloudflareBusy && state.cloudflareDraft.accountId.isNotBlank() &&
                        state.cloudflareDraft.zoneName.isNotBlank() && (connected || state.cloudflareDraft.apiToken.isNotBlank()),
                    modifier = Modifier.testTag("cloudflare-connect"),
                ) { Text(if (connected) "Doğrula / tokenı güncelle" else "Cloudflare'ı bağla") }
                if (connected) {
                    OutlinedButton(
                        onClick = { confirmDisconnect = true },
                        enabled = !state.cloudflareBusy && !hasCustomHosts,
                        modifier = Modifier.testTag("cloudflare-disconnect"),
                    ) { Text("Bağlantıyı kes") }
                }
            }
            if (connected && hasCustomHosts) {
                Text(
                    "Bağlantıyı kesmeden önce projelerdeki tüm özel hostname atamalarını kaldırın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Cloudflare Tunnel'i kaldır?") },
            text = { Text("Uygulamanın yönettiği Named Tunnel ve şifreli bağlantı ayarları silinir. DNS hostname atamaları önce kaldırılmış olmalıdır.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDisconnect = false; onDisconnect() },
                    enabled = !state.cloudflareBusy,
                ) { Text("Tunnel'i sil ve kes") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun TunnelCard(state: DashboardUiState, onRetry: () -> Unit, onCopy: (String) -> Unit) {
    val url = state.network.publicUrl
    if (url != null) {
        Card(
            onClick = { onCopy(url) },
            modifier = Modifier.fillMaxWidth().testTag("tunnel-url").semantics { contentDescription = "Tünel adresini kopyala: $url" },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("İNTERNETE AÇIK ADRES", style = MaterialTheme.typography.labelMedium, letterSpacing = 0.7.sp)
                Text(url, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.network.tunnelMode == TunnelMode.NAMED) {
                        "Özel hostnameler kendi alan adlarının kökünde yayınlanır; proje kimliği değişmez."
                    } else {
                        "Quick Tunnel projeleri /sites/site-adı/ yolunda yayınlar. Kalıcı alan adı için aşağıdan Cloudflare'ı yapılandırın."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.network.tunnelMode != TunnelMode.NAMED) {
                    SelectionContainer {
                        Text(
                            "Web dosya yöneticisi: ${url.trimEnd('/')}/manage/ · uygulamanın API anahtarı gerekir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "Yönetim API'si özel alan adlarında yayınlanmaz; dosya yöneticisi için güvenli yerel erişimi kullanın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        InformationCard(
            "Cloudflare Tunnel",
            when (state.network.tunnelStatus) {
                NetworkServiceStatus.STARTING -> "Güvenli bağlantı kuruluyor. Genel adres hazır olduğunda burada görünecek."
                NetworkServiceStatus.FAILED -> "Tünel bağlantısı kurulamadı. Ağınızı kontrol edip yeniden deneyin."
                NetworkServiceStatus.RUNNING -> "Bağlantının genel adresi doğrulanıyor…"
                NetworkServiceStatus.STOPPED -> "Ana sunucuyu açtığınızda cihazınız için bir genel adres oluşturulur."
            },
        ) {
            state.network.lastError?.let { ErrorText(it) }
            if (state.serverRunning && (state.network.tunnelStatus == NetworkServiceStatus.FAILED || state.network.tunnelStatus == NetworkServiceStatus.STOPPED)) {
                TextButton(onClick = onRetry, enabled = !state.operationBusy, modifier = Modifier.testTag("retry-network")) {
                    Text("Bağlantıyı yeniden kur")
                }
            }
        }
    }
}

@Composable
private fun OperationCard(description: String?, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(description ?: "İşlem sürüyor…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-operation")) { Text("İşlemi iptal et") }
        }
    }
}

@Composable
private fun SiteCard(
    site: Site,
    canManage: Boolean,
    canManageFiles: Boolean,
    cloudflareConfigured: Boolean,
    canManageHostname: Boolean,
    cloudflareBusy: Boolean,
    onLogs: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFiles: () -> Unit,
    onSetHostname: (Long, String?) -> Unit,
) {
    Card(onClick = onLogs, modifier = Modifier.fillMaxWidth().testTag("site-${site.id}")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(site.domainName, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateBadge(site.status.label, site.status == SiteStatus.RUNNING, site.status == SiteStatus.FAILED)
                    Text("${site.projectType.label} · :${site.internalPort}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Text(
                site.publicUrl ?: "/sites/${site.domainName}/",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (cloudflareConfigured) {
                CustomHostnameEditor(
                    site = site,
                    enabled = canManageHostname && !cloudflareBusy,
                    onSetHostname = onSetHostname,
                )
            } else {
                Text(
                    "Özel DNS hostname atamak için önce Cloudflare'ı yapılandırın. Bu proje adı (${site.domainName}) site yolu kimliğidir; DNS adı değildir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SiteAction(site, canManage, onStart, onStop)
                TextButton(onClick = onLogs, modifier = Modifier.testTag("logs-${site.id}")) { Text("Canlı günlükler") }
                TextButton(
                    onClick = onFiles,
                    enabled = canManageFiles,
                    modifier = Modifier.testTag("files-${site.id}"),
                ) { Text("Dosyalar") }
            }
        }
    }
}

@Composable
private fun CustomHostnameEditor(
    site: Site,
    enabled: Boolean,
    onSetHostname: (Long, String?) -> Unit,
) {
    var hostname by rememberSaveable(site.id, site.customHostname) {
        mutableStateOf(site.customHostname.orEmpty())
    }
    val candidate = hostname.trim().takeIf(String::isNotEmpty)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it.take(253) },
            modifier = Modifier.fillMaxWidth().testTag("custom-hostname-${site.id}"),
            enabled = enabled,
            singleLine = true,
            label = { Text("Özel public hostname") },
            placeholder = { Text("www.example.com") },
            supportingText = {
                Text("Ayrı DNS adı; zone içinde olmalı. Proje yolu /sites/${site.domainName}/ olarak aynı kalır.")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { onSetHostname(site.id, candidate) },
                enabled = enabled && candidate != null && candidate != site.customHostname,
                modifier = Modifier.testTag("save-custom-hostname-${site.id}"),
            ) { Text(if (site.customHostname == null) "Hostname'i bağla" else "Hostname'i güncelle") }
            if (site.customHostname != null) {
                OutlinedButton(
                    onClick = {
                        hostname = ""
                        onSetHostname(site.id, null)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("remove-custom-hostname-${site.id}"),
                ) { Text("Hostname'i kaldır") }
            }
        }
    }
}

@Composable
private fun SiteAction(site: Site, canManage: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (site.status == SiteStatus.RUNNING || site.processId != null) {
        OutlinedButton(onClick = onStop, enabled = canManage, modifier = Modifier.testTag("stop-${site.id}")) { Text("Durdur") }
    } else if (site.status == SiteStatus.DEPLOYING) {
        Text("Kuruluyor…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    } else {
        OutlinedButton(onClick = onStart, enabled = canManage, modifier = Modifier.testTag("start-${site.id}")) { Text("Başlat") }
    }
}

@Composable
private fun AddProjectSheet(
    state: DashboardUiState,
    onDismiss: () -> Unit,
    onDomainChanged: (String) -> Unit,
    onTypeChanged: (ProjectType) -> Unit,
    onChooseZip: () -> Unit,
    onDeploy: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.draft
    var expanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!state.operationBusy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("add-project-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Yeni proje", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("ZIP dosyanızı seçin. Kurulum, Yayına al düğmesine bastığınızda başlar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.serverRunning) {
                Text("Proje yayınlamak için önce ana sunucuyu açın.", color = MaterialTheme.colorScheme.primary)
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = !state.operationBusy,
                    modifier = Modifier.fillMaxWidth().testTag("project-type").semantics { stateDescription = draft.projectType.label },
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Proje türü", style = MaterialTheme.typography.labelSmall)
                        Text(draft.projectType.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Seç")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ProjectType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = { expanded = false; onTypeChanged(type) })
                    }
                }
            }
            OutlinedTextField(
                value = draft.domainName,
                onValueChange = onDomainChanged,
                modifier = Modifier.fillMaxWidth().testTag("project-domain"),
                enabled = !state.operationBusy,
                singleLine = true,
                label = { Text("Domain / site adı") },
                placeholder = { Text("my-app") },
                supportingText = { Text("Örnek yol: /sites/my-app/ — özel DNS kaydı gerekmez.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            OutlinedButton(
                onClick = onChooseZip,
                enabled = !state.operationBusy,
                modifier = Modifier.fillMaxWidth().testTag("choose-zip"),
            ) { Text(if (draft.zipUri == null) "ZIP seç" else "ZIP dosyasını değiştir") }
            draft.zipName?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("zip-name")) }
            draft.error?.let { ErrorText(it) }
            if (state.operationBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.operationDescription ?: "Proje kuruluyor…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel-deployment")) { Text("Kurulumu iptal et") }
            } else {
                Button(
                    onClick = onDeploy,
                    enabled = state.canManageSites && draft.domainName.isNotBlank() && draft.zipUri != null && draft.error == null,
                    modifier = Modifier.fillMaxWidth().testTag("deploy-project"),
                ) { Text("Yayına al") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Vazgeç") }
            }
        }
    }
}

@Composable
private fun LogsSheet(site: Site, canManage: Boolean, onDismiss: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    val scroll = rememberScrollState()
    var follow by remember(site.id) { mutableStateOf(true) }
    var copied by remember(site.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val logText = remember(site.logs) {
        site.logs.takeLast(16_384).let { tail -> if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail }
    }
    LaunchedEffect(site.id, logText, follow) {
        copied = false
        if (follow) snapshotFlow { scroll.maxValue }.collectLatest { scroll.scrollTo(it) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("logs-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(site.domainName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Canlı günlükler · ${site.status.label} · son 16.384 karakter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SiteAction(site, canManage, onStart, onStop)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("${site.domainName} günlükleri", logText)))
                            copied = true
                        }
                    },
                    enabled = logText.isNotEmpty(),
                    modifier = Modifier.testTag("copy-logs"),
                ) { Text(if (copied) "Kopyalandı" else "Günlükleri kopyala") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = follow,
                    onCheckedChange = { follow = it },
                    modifier = Modifier.testTag("follow-logs").semantics { contentDescription = "Canlı günlükleri otomatik takip et" },
                )
                Text("Yeni satırları takip et", style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 96.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                SelectionContainer {
                    Text(
                        text = logText.ifEmpty { "Henüz günlük kaydı yok." },
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).testTag("live-logs"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Kapat") }
        }
    }
}

@Composable
private fun SiteFileManagerSheet(
    site: Site,
    state: SiteFileManagerUiState,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onContentChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateDirectory: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var createKind by rememberSaveable { mutableStateOf<String?>(null) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("site-file-manager-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${site.domainName} · Dosyalar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Yalnız bu sitenin proje klasörü · metin dosyaları en fazla 32 KiB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-site-file-manager")) { Text("Kapat") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("/${state.directory}", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.saving, modifier = Modifier.testTag("refresh-site-files")) { Text("Yenile") }
                OutlinedButton(
                    onClick = { draftName = ""; createKind = "directory" },
                    enabled = !state.loading && !state.saving,
                    modifier = Modifier.testTag("new-site-directory"),
                ) { Text("Klasör") }
                Button(
                    onClick = { draftName = ""; createKind = "file" },
                    enabled = !state.loading && !state.saving,
                    modifier = Modifier.testTag("new-site-file"),
                ) { Text("Dosya") }
            }
            if (state.directory.isNotEmpty()) {
                TextButton(
                    onClick = { onNavigate(state.directory.substringBeforeLast('/', "")) },
                    enabled = !state.loading && !state.saving,
                    modifier = Modifier.testTag("site-files-parent"),
                ) { Text("← Üst klasör") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorText(it) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 260.dp).testTag("site-file-entries"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.entries, key = { "${state.directory}/${it.name}" }) { entry ->
                    val path = listOf(state.directory, entry.name).filter(String::isNotEmpty).joinToString("/")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (entry.isDirectory) onNavigate(path) else onOpenFile(path) },
                            enabled = !state.loading && !state.saving,
                            modifier = Modifier.weight(1f).testTag("site-file-${entry.name}"),
                        ) {
                            Text(
                                (if (entry.isDirectory) "📁 " else "📄 ") + entry.name,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!entry.isDirectory) {
                            Text(entry.sizeBytes?.let { "$it B" } ?: "Metin", style = MaterialTheme.typography.labelSmall)
                            TextButton(
                                onClick = { deletePath = path },
                                enabled = !state.saving,
                                modifier = Modifier.testTag("delete-site-file-${entry.name}"),
                            ) { Text("Sil", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (state.entries.isEmpty() && !state.loading) {
                    item { Text("Bu klasör boş.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                }
            }
            state.editorPath?.let { path ->
                HorizontalDivider()
                Text(path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = state.editorContent,
                    onValueChange = onContentChanged,
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 128.dp).testTag("site-file-editor"),
                    enabled = !state.saving,
                    label = { Text("Metin içeriği") },
                    minLines = 5,
                    maxLines = 16,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = !state.saving && !state.loading, modifier = Modifier.testTag("save-site-file")) {
                        Text(if (state.saving) "Kaydediliyor…" else "Kaydet")
                    }
                    OutlinedButton(onClick = { deletePath = path }, enabled = !state.saving, modifier = Modifier.testTag("delete-open-site-file")) {
                        Text("Dosyayı sil")
                    }
                }
            }
        }
    }
    if (createKind != null) {
        val kind = createKind
        AlertDialog(
            onDismissRequest = { createKind = null },
            title = { Text(if (kind == "file") "Yeni metin dosyası" else "Yeni klasör") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(255) },
                    label = { Text("Ad") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.testTag("new-site-entry-name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = draftName.trim()
                        if (name.isNotEmpty()) {
                            if (kind == "file") onCreateFile(name) else onCreateDirectory(name)
                            createKind = null
                        }
                    },
                    enabled = draftName.trim().isNotEmpty(),
                ) { Text("Oluştur") }
            },
            dismissButton = { TextButton(onClick = { createKind = null }) { Text("Vazgeç") } },
        )
    }
    deletePath?.let { path ->
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text("Dosya silinsin mi?") },
            text = { Text(path) },
            confirmButton = {
                TextButton(
                    onClick = { onDeleteFile(path); deletePath = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun InformationCard(title: String, description: String, actions: @Composable () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    val explanation = when (message) {
        "Server shutdown is incomplete. Retry Stop." -> "Sunucu tamamen durdurulamadı. Temizliği yeniden dene düğmesiyle kalan süreçleri durdurmayı yeniden deneyin."
        else -> message
    }
    SelectionContainer {
        Text(explanation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun NetworkBadge(name: String, status: NetworkServiceStatus) {
    StateBadge("$name · ${status.label}", status == NetworkServiceStatus.RUNNING, status == NetworkServiceStatus.FAILED)
}

@Composable
private fun StateBadge(label: String, active: Boolean, failed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        failed -> colors.error
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Surface(color = tint.copy(alpha = 0.10f), contentColor = tint, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(tint, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ServerMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp)) {
        val gap = size.height * 0.13f
        val height = (size.height - gap * 2) / 3
        repeat(3) { index ->
            val top = index * (height + gap)
            drawRoundRect(color.copy(alpha = 0.14f), topLeft = Offset(0f, top), size = Size(size.width, height), cornerRadius = CornerRadius(4.dp.toPx()))
            drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width * 0.22f, top + height / 2))
            drawLine(color, Offset(size.width * 0.46f, top + height / 2), Offset(size.width * 0.78f, top + height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun MenuMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(2.dp.toPx(), size.height * 0.24f), Offset(size.width - 2.dp.toPx(), size.height * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(2.dp.toPx(), size.height * 0.50f), Offset(size.width - 2.dp.toPx(), size.height * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(2.dp.toPx(), size.height * 0.76f), Offset(size.width - 2.dp.toPx(), size.height * 0.76f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun PlusMark() {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.size(22.dp)) {
        drawLine(color, Offset(size.width / 2, 3.dp.toPx()), Offset(size.width / 2, size.height - 3.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(3.dp.toPx(), size.height / 2), Offset(size.width - 3.dp.toPx(), size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private val ProjectType.label: String get() = when (this) {
    ProjectType.STATIC -> "Statik HTML"
    ProjectType.DJANGO -> "Python · Django / Flask"
    ProjectType.NODEJS -> "Node.js"
    ProjectType.REACT -> "React"
}
private val SiteStatus.label: String get() = when (this) {
    SiteStatus.RUNNING -> "Yayında"
    SiteStatus.STOPPED -> "Durdu"
    SiteStatus.FAILED -> "Hata"
    SiteStatus.DEPLOYING -> "Kuruluyor"
}
private val NetworkServiceStatus.label: String get() = when (this) {
    NetworkServiceStatus.RUNNING -> "Çalışıyor"
    NetworkServiceStatus.STARTING -> "Başlatılıyor"
    NetworkServiceStatus.STOPPED -> "Durdu"
    NetworkServiceStatus.FAILED -> "Hata"
}
private val RemoteServerStatus.label: String get() = when (this) {
    RemoteServerStatus.RUNNING -> "API hazır; Nginx ve tünel aşağıda izlenir"
    RemoteServerStatus.STARTING -> "Sunucu başlatılıyor…"
    RemoteServerStatus.STOPPING -> "Süreçler güvenle durduruluyor…"
    RemoteServerStatus.STOPPED -> "Sunucu kapalı"
    RemoteServerStatus.FAILED -> "İşlem tamamlanamadı; durdurup yeniden deneyin"
}
private val RemoteServerStatus.shortLabel: String get() = when (this) {
    RemoteServerStatus.RUNNING -> "Çalışıyor"
    RemoteServerStatus.STARTING -> "Başlatılıyor"
    RemoteServerStatus.STOPPING -> "Durduruluyor"
    RemoteServerStatus.STOPPED -> "Kapalı"
    RemoteServerStatus.FAILED -> "Hata"
}

// --- New platform cards ---

@Composable
private fun DashboardTabs(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DashboardTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val label = when (tab) {
                DashboardTab.PROJECTS -> "Projeler"
                DashboardTab.SERVICES -> "Servisler"
                DashboardTab.ACTIVITY -> "Kayıtlar"
            }
            if (isSelected) {
                Button(onClick = { onSelect(tab) }, modifier = Modifier.testTag("tab-${tab.name.lowercase()}")) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(tab) }, modifier = Modifier.testTag("tab-${tab.name.lowercase()}")) { Text(label) }
            }
        }
    }
}

@Composable
private fun SshCard(
    state: DashboardUiState,
    onToggle: (Boolean) -> Unit,
    onPortChanged: (Int) -> Unit,
    onPasswordAuthChanged: (Boolean) -> Unit,
    onLanChanged: (Boolean) -> Unit,
    onShowSheet: () -> Unit,
    onRemoveKey: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    val ssh = state.ssh
    val running = ssh.status == NetworkServiceStatus.RUNNING
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("SSH erişimi", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (ssh.status) {
                            NetworkServiceStatus.RUNNING -> "Çalışıyor :${ssh.settings.port}"
                            NetworkServiceStatus.STARTING -> "Başlatılıyor…"
                            NetworkServiceStatus.FAILED -> ssh.lastError ?: "Hata"
                            NetworkServiceStatus.STOPPED -> "Kapalı :${ssh.settings.port}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    enabled = state.serverRunning || running,
                    modifier = Modifier.testTag("ssh-switch").semantics { contentDescription = "SSH sunucusunu başlat veya durdur" },
                )
            }
            ssh.lastError?.let { ErrorText(it) }
            if (running && ssh.connectionCommand != null) {
                Card(
                    onClick = { onCopy(ssh.connectionCommand) },
                    modifier = Modifier.fillMaxWidth().testTag("ssh-command"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bağlantı komutu", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(ssh.connectionCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Text("Dokunarak kopyalayın.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ssh.settings.hostKeyFingerprint?.let {
                Text("Host parmak izi: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Inline settings
            var portText by remember(ssh.settings.port) { mutableStateOf(ssh.settings.port.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                modifier = Modifier.fillMaxWidth().testTag("ssh-port"),
                label = { Text("SSH portu") },
                singleLine = true,
                supportingText = { Text("1024–65535, 8080/8081 hariç") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    TextButton(onClick = {
                        val port = portText.toIntOrNull()
                        if (port != null) onPortChanged(port)
                    }) { Text("Uygula") }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = ssh.settings.passwordAuthEnabled,
                    onCheckedChange = onPasswordAuthChanged,
                    modifier = Modifier.testTag("ssh-password-auth"),
                )
                Text("Parola ile girişe izin ver", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = ssh.settings.listenLan,
                    onCheckedChange = onLanChanged,
                    modifier = Modifier.testTag("ssh-lan"),
                )
                Text("LAN üzerinden erişilebilir kıl (0.0.0.0)", style = MaterialTheme.typography.bodySmall)
            }
            Text("${state.sshKeys.size} yetkili anahtar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.sshKeys.isNotEmpty()) {
                state.sshKeys.forEach { key ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(key.name, style = MaterialTheme.typography.bodyMedium)
                            Text(key.fingerprint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRemoveKey(key.id) }, modifier = Modifier.testTag("remove-ssh-${key.id}")) { Text("Kaldır") }
                    }
                }
            }
            OutlinedButton(onClick = onShowSheet, modifier = Modifier.fillMaxWidth().testTag("add-ssh-key")) { Text("Yeni yetkili anahtar ekle") }
        }
    }
}

@Composable
private fun ActivityFilterCard(
    state: DashboardUiState,
    onQueryChanged: (String) -> Unit,
    onLevelChanged: (ActivityLevel?) -> Unit,
    onCategoryChanged: (ActivityCategory?) -> Unit,
    onClearFilters: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val filter = state.activityFilter
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth().testTag("activity-search"),
                label = { Text("Kayıtlarda ara") },
                placeholder = { Text("Alan adı, mesaj, kategori…") },
                singleLine = true,
            )
            Text("Seviye filtresi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filter.level == null,
                    onClick = { onLevelChanged(null) },
                    label = { Text("Tümü") },
                )
                ActivityLevel.entries.forEach { level ->
                    FilterChip(
                        selected = filter.level == level,
                        onClick = { onLevelChanged(if (filter.level == level) null else level) },
                        label = { Text(level.name) },
                    )
                }
            }
            Text("Kategori filtresi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filter.category == null,
                    onClick = { onCategoryChanged(null) },
                    label = { Text("Tümü") },
                )
                ActivityCategory.entries.forEach { category ->
                    FilterChip(
                        selected = filter.category == category,
                        onClick = { onCategoryChanged(if (filter.category == category) null else category) },
                        label = { Text(category.name) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportJson, modifier = Modifier.testTag("export-logs-json")) { Text("JSON dışa aktar") }
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.testTag("export-logs-csv")) { Text("CSV dışa aktar") }
                if (filter.isActive) {
                    TextButton(onClick = onClearFilters, modifier = Modifier.testTag("clear-activity-filters")) { Text("Filtreyi sıfırla") }
                }
                if (state.activity.isNotEmpty()) {
                    TextButton(onClick = onClearLogs, modifier = Modifier.testTag("clear-activity-logs")) { Text("Kayıtları temizle") }
                }
            }
        }
    }
}

@Composable
private fun BackupRestoreSheet(
    draft: BackupDraft,
    onDismiss: () -> Unit,
    onImportJsonChanged: (String) -> Unit,
    onRequireSignatureChanged: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("backup-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Platform yedekleme ve geri yükleme", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Room v4 veritabanındaki SSH ayarları, yetkili anahtarlar, servis durumları, siteler ve son kayıtları HMAC-SHA256 imzalı JSON zarfı olarak dışa aktarın veya geri yükleyin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onExportBackup,
                modifier = Modifier.fillMaxWidth().testTag("export-backup"),
            ) { Text("İmzalı yedeği panoya kopyala") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Yedekten geri yükle", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.importJson,
                onValueChange = onImportJsonChanged,
                modifier = Modifier.fillMaxWidth().testTag("backup-import-json"),
                label = { Text("Yedek JSON zarfı") },
                placeholder = { Text("{\"version\":1,\"algorithm\":\"HMAC-SHA256\",…}") },
                minLines = 4,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.requireSignature,
                    onCheckedChange = onRequireSignatureChanged,
                    modifier = Modifier.testTag("backup-require-signature"),
                )
                Text("HMAC-SHA256 imzasını zorunlu tut", style = MaterialTheme.typography.bodySmall)
            }
            draft.error?.let { ErrorText(it) }
            Button(
                onClick = onImportBackup,
                enabled = draft.importJson.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("confirm-import-backup"),
            ) { Text("Yedeği doğrula ve geri yükle") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Kapat") }
        }
    }
}

@Composable
private fun SshKeySheet(
    draft: SshDraft,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onKeyChanged: (String) -> Unit,
    onAdd: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("ssh-key-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Yetkili anahtar ekle", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Bilgisayarınızdaki public key'in tek satırını yapıştırın (ssh-ed25519 / ssh-rsa / ecdsa).", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = draft.keyName,
                onValueChange = onNameChanged,
                modifier = Modifier.fillMaxWidth().testTag("ssh-key-name"),
                label = { Text("Anahtar adı") },
                placeholder = { Text("dizüstü bilgisayarım") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.publicKey,
                onValueChange = onKeyChanged,
                modifier = Modifier.fillMaxWidth().testTag("ssh-public-key"),
                label = { Text("Public key") },
                placeholder = { Text("ssh-ed25519 AAAA…") },
                minLines = 3,
            )
            draft.error?.let { ErrorText(it) }
            Button(
                onClick = onAdd,
                enabled = draft.keyName.isNotBlank() && draft.publicKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("confirm-add-ssh-key"),
            ) { Text("Ekle") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Vazgeç") }
        }
    }
}

@Composable
private fun ServiceCard(
    service: dev.pocketpaas.domain.services.ManagedServiceState,
    canManage: Boolean,
    onInstall: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("service-${service.serviceKey}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(service.description(service.serviceKey) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StateBadge(service.status.label, service.status == ManagedServiceStatus.RUNNING, service.status == ManagedServiceStatus.FAILED)
            }
            service.port?.let { Text("Port :$it · yalnızca 127.0.0.1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            service.lastError?.let { ErrorText(it) }
            if (service.category == ServiceCategory.DATABASE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (service.status) {
                        ManagedServiceStatus.RUNNING -> OutlinedButton(onClick = { onStop(service.serviceKey) }, enabled = canManage, modifier = Modifier.testTag("stop-service-${service.serviceKey}")) { Text("Durdur") }
                        else -> {
                            if (!service.installed) {
                                Button(onClick = { onInstall(service.serviceKey) }, enabled = canManage, modifier = Modifier.testTag("install-service-${service.serviceKey}")) { Text("Kur") }
                            }
                            OutlinedButton(onClick = { onStart(service.serviceKey) }, enabled = canManage && service.installed, modifier = Modifier.testTag("start-service-${service.serviceKey}")) { Text("Başlat") }
                        }
                    }
                }
            } else if (!service.installed) {
                TextButton(onClick = { onInstall(service.serviceKey) }, enabled = canManage, modifier = Modifier.testTag("install-service-${service.serviceKey}")) { Text("Paketi kur") }
            }
        }
    }
}

private fun dev.pocketpaas.domain.services.ManagedServiceState.description(key: String): String? =
    dev.pocketpaas.domain.services.ServiceCatalog.find(key)?.description

@Composable
private fun DeploymentRow(record: dev.pocketpaas.domain.model.DeploymentRecord) {
    Card(modifier = Modifier.fillMaxWidth().testTag("deployment-${record.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.domainName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StateBadge(record.status.label, record.status == DeploymentStatus.SUCCEEDED, record.status == DeploymentStatus.FAILED)
            }
            Text("${record.projectType.label} · ${record.archiveName ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            record.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ActivityRow(event: dev.pocketpaas.domain.model.ActivityEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(8.dp).background(
                when (event.level) {
                    ActivityLevel.ERROR -> MaterialTheme.colorScheme.error
                    ActivityLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                    ActivityLevel.INFO -> MaterialTheme.colorScheme.primary
                }, CircleShape,
            ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.message, style = MaterialTheme.typography.bodySmall)
            event.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(event.category.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ManagedServiceStatus.label: String get() = when (this) {
    ManagedServiceStatus.RUNNING -> "Çalışıyor"
    ManagedServiceStatus.STARTING -> "Başlatılıyor"
    ManagedServiceStatus.STOPPED -> "Durdu"
    ManagedServiceStatus.FAILED -> "Hata"
    ManagedServiceStatus.UNKNOWN -> "Bilinmiyor"
}
private val DeploymentStatus.label: String get() = when (this) {
    DeploymentStatus.SUCCEEDED -> "Başarılı"
    DeploymentStatus.FAILED -> "Başarısız"
    DeploymentStatus.ROLLED_BACK -> "Geri alındı"
    DeploymentStatus.CANCELLED -> "İptal edildi"
}

@Preview(name = "Dashboard", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun DashboardPreview() {
    PocketPaaSTheme {
        DashboardScreen(
            state = DashboardUiState(
                runtime = RemoteServerState(RemoteServerStatus.RUNNING, "127.0.0.1"),
                network = NetworkState(NetworkServiceStatus.RUNNING, NetworkServiceStatus.RUNNING, publicUrl = "https://pocket-demo.trycloudflare.com"),
                sites = listOf(Site(1, "my-app", 9001, ProjectType.NODEJS, 321, null, SiteStatus.RUNNING, "Listening on 9001", 0)),
                capabilities = RuntimeCapabilities(true, true, listOf("arm64-v8a")),
                loadingSites = false,
                initializing = false,
            ),
            onMasterSwitch = {}, onLanAccessChanged = {}, onRetryNetwork = {}, onRefresh = {},
            onRequestTermuxPermission = {}, onAddProject = {}, onDismissAddProject = {}, onDomainChanged = {},
            onProjectTypeChanged = {}, onZipSelected = {}, onDeploy = {}, onSiteSelected = {}, onDismissLogs = {},
            onStartSite = {}, onStopSite = {}, onCancelOperation = {}, onCopyApiKey = {}, onNoticeConsumed = {},
        )
    }
}
