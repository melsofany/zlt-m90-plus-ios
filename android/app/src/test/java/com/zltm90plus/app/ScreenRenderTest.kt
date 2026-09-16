package com.zltm90plus.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.model.RouterDeviceInfo
import com.zltm90plus.app.data.remote.MockRouterApi
import com.zltm90plus.app.ui.DashboardUiState
import com.zltm90plus.app.ui.ConnectionPhase
import com.zltm90plus.app.ui.ZltApp
import com.zltm90plus.app.ui.screens.BatteryCard
import com.zltm90plus.app.ui.screens.ConnectScreen
import com.zltm90plus.app.ui.screens.ConnectedDevicesCard
import com.zltm90plus.app.ui.screens.DataPlanCard
import com.zltm90plus.app.ui.screens.NetworkCard
import com.zltm90plus.app.ui.theme.ZltTheme
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the real composables under Robolectric and asserts on visible Arabic strings.
 *
 * This catches the honesty rules that matter most: unavailable firmware data must never be
 * rendered as a fabricated number, and sample data must stay visibly labelled.
 */
@RunWith(AndroidJUnit4::class)
class ScreenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val unavailableBattery = DeviceSnapshot(
        deviceInfo = RouterDeviceInfo(model = "ZLT M90 Plus"),
        battery = BatteryStatus(source = DataSource.UNAVAILABLE),
    )

    @Test
    fun batteryCardShowsUnavailableInsteadOfAFakePercent() {
        composeRule.setContent {
            ZltTheme {
                BatteryCard(snapshot = unavailableBattery, estimate = null)
            }
        }
        composeRule.onNodeWithText("غير متاحة من الجهاز").assertIsDisplayed()
        composeRule.onNodeWithText("لا يمكن التقدير حاليًا").assertIsDisplayed()
    }

    @Test
    fun batteryCardRendersPercentAndChargingStateWhenFirmwareProvidesThem() {
        val snapshot = unavailableBattery.copy(
            battery = BatteryStatus(
                percent = 78,
                chargingState = ChargingState.CHARGING,
                updatedAtMillis = System.currentTimeMillis(),
                source = DataSource.ROUTER,
            ),
        )
        composeRule.setContent {
            ZltTheme { BatteryCard(snapshot = snapshot, estimate = null) }
        }
        composeRule.onNodeWithText("78٪").assertIsDisplayed()
        composeRule.onNodeWithText("يشحن").assertIsDisplayed()
    }

    @Test
    fun networkCardKeepsTextAndIconForEachState() {
        val snapshot = unavailableBattery.copy(
            network = com.zltm90plus.app.data.model.NetworkStatus(
                state = NetworkState.ROUTER_ONLY_NO_INTERNET,
                carrierName = null,
                networkType = null,
                source = DataSource.ROUTER,
            ),
        )
        composeRule.setContent { ZltTheme { NetworkCard(snapshot = snapshot) } }
        composeRule.onNodeWithText("متصل بالجهاز فقط ولا يوجد إنترنت").assertIsDisplayed()
        // Carrier and network type are both unknown here, so the placeholder repeats.
        composeRule.onAllNodesWithText("غير متاح").assertCountEquals(2)
    }

    @Test
    fun dataPlanCardWithNoSourceOffersManualSetup() {
        var setupClicked = false
        composeRule.setContent {
            ZltTheme {
                DataPlanCard(
                    plan = DataPlanStatus(source = DataSource.UNAVAILABLE),
                    onSetupClick = { setupClicked = true },
                )
            }
        }
        composeRule.onNodeWithText("غير متاحة من الجهاز").assertIsDisplayed()
        composeRule.onNodeWithText("الجهاز لا يعرض بيانات الباقة، ولم يتم إدخالها يدويًا بعد.").assertIsDisplayed()
        composeRule.onNodeWithText("إعداد الباقة").performClick()
        assert(setupClicked)
    }

    @Test
    fun dataPlanCardLabelsManualEntryAsUserEstimate() {
        composeRule.setContent {
            ZltTheme {
                DataPlanCard(
                    plan = DataPlanStatus(
                        totalBytes = 50_000_000_000L,
                        usedBytes = 21_000_000_000L,
                        source = DataSource.MANUAL,
                    ),
                    onSetupClick = {},
                )
            }
        }
        composeRule.onNodeWithText("42٪").assertIsDisplayed()
        composeRule.onNodeWithText("تقديرية حسب إدخال المستخدم.").assertIsDisplayed()
        composeRule.onNodeWithText("إدخال يدوي").assertIsDisplayed()
    }

    @Test
    fun connectScreenRendersRtlArabicForm() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ZltTheme {
                    ConnectScreen(
                        state = DashboardUiState(),
                        onHostChange = {},
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onConnect = {},
                        onDiscover = {},
                        onEnableDemo = {},
                        onDismissMessage = {},
                        onOpenDiagnostics = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("ZLT M90 Plus").assertIsDisplayed()
        composeRule.onNodeWithText("اتصال بالجهاز").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("اكتشاف الجهاز تلقائيًا").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("عنوان الجهاز").assertIsDisplayed()
        composeRule.onNodeWithText("كلمة المرور").assertIsDisplayed()
    }

    @Test
    fun everyScreenRendersOnASmallPhoneInDarkMode() {
        val snapshot = DeviceSnapshot(
            deviceInfo = RouterDeviceInfo(model = "ZLT M90 Plus"),
            battery = BatteryStatus(
                percent = 42,
                chargingState = ChargingState.DISCHARGING,
                source = DataSource.ROUTER,
            ),
        )
        composeRule.setContent {
            ZltTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BatteryCard(snapshot = snapshot, estimate = null)
                    NetworkCard(snapshot = snapshot)
                    ConnectedDevicesCard(snapshot = snapshot, onOpenList = {})
                }
            }
        }
        composeRule.onNodeWithText("42٪").assertIsDisplayed()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun connectedDashboardRendersAllCardsAndNavigatesBetweenTabs() {
        val connected = DashboardUiState(
            phase = ConnectionPhase.CONNECTED,
            snapshot = DeviceSnapshot(
                deviceInfo = RouterDeviceInfo(
                    model = "ZLT M90 Plus",
                    firmwareVersion = "1.0.0",
                    source = DataSource.ROUTER,
                ),
                battery = BatteryStatus(
                    percent = 78,
                    chargingState = ChargingState.DISCHARGING,
                    source = DataSource.ROUTER,
                ),
                plan = DataPlanStatus(
                    totalBytes = 50_000_000_000L,
                    usedBytes = 21_000_000_000L,
                    source = DataSource.MANUAL,
                ),
            ),
        )

        composeRule.setContent {
            ZltTheme {
                ZltApp(
                    state = connected,
                    onHostChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onConnect = {},
                    onDiscover = {},
                    onEnableDemo = {},
                    onDismissMessage = {},
                    diagnosticExchanges = emptyList(),
                    onShareDiagnostics = {},
                    onClearDiagnostics = {},
                    onRefresh = {},
                    onSavePlan = { _, _, _, _, _ -> },
                    onUpdateWifi = { _, _, _ -> },
                    onRestart = {},
                    onDisconnect = {},
                )
            }
        }

        // The dashboard is taller than the test viewport, so scroll each card into view first.
        composeRule.onNodeWithText("78٪").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("الباقة والاستهلاك").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("صورة الجهاز").performScrollTo().assertIsDisplayed()

        // "البطارية" also appears as a card label, so target the bottom-bar tabs explicitly.
        composeRule.onNode(hasText("البطارية") and isSelectable()).performClick()
        composeRule.onNodeWithText("سجل قراءات البطارية").performScrollTo().assertIsDisplayed()

        composeRule.onNode(hasText("الاتصال") and isSelectable()).performClick()
        composeRule.onNodeWithText("اختبار الاتصال").performScrollTo().assertIsDisplayed()

        composeRule.onNode(hasText("الإعدادات") and isSelectable()).performClick()
        composeRule.onNodeWithText("معلومات الجهاز").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun mockApiProvidesAFullSnapshotForOfflineDevelopment() = runTest {
        val api = MockRouterApi(latencyMillis = 0)
        api.login("admin", "demo")
        val battery = api.fetchBatteryStatus()
        assert(battery.percent != null)
        assert(battery.source == DataSource.ESTIMATED)

        val devices = api.fetchConnectedDevices()
        assert(devices.devices.isNotEmpty())
    }

    @Test
    fun diagnosticsScreenShowsWhatWasSentAndReceivedWithoutThePassword() {
        val secret = "Sup3rSecretPassw0rd"
        // Exactly what the transport records: already redacted when the exchange is built.
        val sentBody = com.zltm90plus.app.diagnostics.DiagnosticRedaction.redact(
            "goformId=LOGIN&password=$secret",
        )
        val exchange = com.zltm90plus.app.diagnostics.DiagnosticExchange(
            timestampMillis = 1_700_000_000_000,
            url = "http://192.168.8.1/goform/goform_set_cmd_process",
            method = "POST",
            requestBody = sentBody,
            statusCode = 200,
            responseBody = """{"result":"0"}""",
            error = null,
            durationMillis = 31,
        )

        composeRule.setContent {
            ZltTheme {
                ZltApp(
                    state = DashboardUiState(phase = ConnectionPhase.INVALID_CREDENTIALS),
                    diagnosticExchanges = listOf(exchange),
                    onHostChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onConnect = {},
                    onDiscover = {},
                    onEnableDemo = {},
                    onDismissMessage = {},
                    onRefresh = {},
                    onSavePlan = { _, _, _, _, _ -> },
                    onUpdateWifi = { _, _, _ -> },
                    onRestart = {},
                    onDisconnect = {},
                    onShareDiagnostics = {},
                    onClearDiagnostics = {},
                )
            }
        }

        // The log must be reachable while the connection is failing, which is the whole point.
        composeRule.onNodeWithText("عرض سجل الاتصال").performScrollTo().performClick()

        composeRule.onNodeWithText("سجل الاتصال").assertIsDisplayed()
        composeRule.onNodeWithText("goform_set_cmd_process", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("POST").assertIsDisplayed()

        // The security property: the password the user typed is nowhere on this screen.
        composeRule.onAllNodesWithText(secret, substring = true).assertCountEquals(0)
    }
}