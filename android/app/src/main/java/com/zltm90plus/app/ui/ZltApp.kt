package com.zltm90plus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.ui.components.DeviceImage
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.components.StatusChip
import com.zltm90plus.app.ui.screens.BatteryCard
import com.zltm90plus.app.ui.screens.BatteryDetailScreen
import com.zltm90plus.app.ui.screens.ConnectScreen
import com.zltm90plus.app.ui.screens.ConnectedDevicesCard
import com.zltm90plus.app.ui.screens.ConnectedDevicesScreen
import com.zltm90plus.app.ui.screens.ConnectionDetailScreen
import com.zltm90plus.app.ui.screens.DataPlanCard
import com.zltm90plus.app.ui.screens.DeviceHeaderCard
import com.zltm90plus.app.ui.screens.DeviceImageScreen
import com.zltm90plus.app.ui.screens.NetworkCard
import com.zltm90plus.app.ui.screens.PlanDetailScreen
import com.zltm90plus.app.ui.screens.SettingsScreen
import com.zltm90plus.app.ui.theme.statusColors

enum class AppTab(val title: String, val icon: ImageVector) {
    DASHBOARD("اللوحة", Icons.Default.Info),
    BATTERY("البطارية", Icons.Default.BatteryFull),
    NETWORK("الاتصال", Icons.Default.SignalCellular4Bar),
    DEVICES("الأجهزة", Icons.Default.Devices),
    DEVICE_IMAGE("الصورة", Icons.Default.Image),
    SETTINGS("الإعدادات", Icons.Default.Settings),
}

/** Top-level navigation. The connect screen is shown until a session is established. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZltApp(
    state: DashboardUiState,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDiscover: () -> Unit,
    onEnableDemo: () -> Unit,
    onDismissMessage: () -> Unit,
    onRefresh: () -> Unit,
    onSavePlan: (Long, Long?, Long?, Long?, String?) -> Unit,
    onUpdateWifi: (String, String, (Result<Unit>) -> Unit) -> Unit,
    onRestart: ((Result<Unit>) -> Unit) -> Unit,
    onDisconnect: () -> Unit,
) {
    if (!state.isConnected || state.snapshot == null) {
        ConnectScreen(
            state = state,
            onHostChange = onHostChange,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onConnect = onConnect,
            onDiscover = onDiscover,
            onEnableDemo = onEnableDemo,
            onDismissMessage = onDismissMessage,
        )
        return
    }

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.DASHBOARD) }
    var showPlanEditor by rememberSaveable { mutableStateOf(false) }
    var showDeviceList by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZLT M90 Plus") },
                actions = {
                    if (state.isDemo) {
                        StatusChip(
                            label = "بيانات تجريبية",
                            color = statusColors().warning,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        val snapshot = state.snapshot ?: return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.DASHBOARD -> DashboardTab(
                    state = state,
                    snapshot = snapshot,
                    onRefresh = onRefresh,
                    onOpenPlanEditor = { showPlanEditor = true },
                    onOpenDeviceList = { showDeviceList = true },
                )
                AppTab.BATTERY -> BatteryDetailScreen(
                    snapshot = snapshot,
                    estimate = state.batteryEstimate,
                    history = state.batteryHistory,
                )
                AppTab.NETWORK -> ConnectionDetailScreen(
                    snapshot = snapshot,
                    onRefreshNow = onRefresh,
                    isRefreshing = state.isRefreshing,
                )
                AppTab.DEVICES -> ConnectedDevicesScreen(devices = snapshot.connectedDevices)
                AppTab.DEVICE_IMAGE -> DeviceImageScreen(state = state)
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    onUpdateWifi = onUpdateWifi,
                    onRestart = onRestart,
                    onOpenPlanSetup = { showPlanEditor = true },
                    onDisconnect = onDisconnect,
                )
            }
        }

        if (showPlanEditor) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showPlanEditor = false },
                title = { Text("إعداد الباقة") },
                text = {
                    PlanDetailScreen(
                        plan = snapshot.plan,
                        onSave = { total, used, start, renewal, operator ->
                            onSavePlan(total, used, start, renewal, operator)
                            showPlanEditor = false
                        },
                        dailyUsageBytes = emptyList(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showPlanEditor = false }) { Text("إغلاق") }
                },
            )
        }

        if (showDeviceList) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeviceList = false },
                title = { Text("الأجهزة المتصلة") },
                text = { ConnectedDevicesScreen(devices = snapshot.connectedDevices) },
                confirmButton = {
                    TextButton(onClick = { showDeviceList = false }) { Text("إغلاق") }
                },
            )
        }
    }
}

@Composable
private fun DashboardTab(
    state: DashboardUiState,
    snapshot: DeviceSnapshot,
    onRefresh: () -> Unit,
    onOpenPlanEditor: () -> Unit,
    onOpenDeviceList: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeviceHeaderCard(
            snapshot = snapshot,
            isDemo = state.isDemo,
            onRefresh = onRefresh,
            isRefreshing = state.isRefreshing,
        )
        BatteryCard(snapshot = snapshot, estimate = state.batteryEstimate)
        NetworkCard(snapshot = snapshot)
        DataPlanCard(plan = snapshot.plan, onSetupClick = onOpenPlanEditor)
        ConnectedDevicesCard(snapshot = snapshot, onOpenList = onOpenDeviceList)

        DeviceImageSection(isDemo = state.isDemo)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceImageSection(isDemo: Boolean) {
    SectionCard(title = "صورة الجهاز") {
        DeviceImage(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )
        if (isDemo) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "الواجهة في وضع تجريبي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}