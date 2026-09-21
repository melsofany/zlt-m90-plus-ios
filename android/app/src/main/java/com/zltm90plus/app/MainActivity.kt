package com.zltm90plus.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zltm90plus.app.ui.MainViewModel
import com.zltm90plus.app.ui.ZltApp
import com.zltm90plus.app.ui.theme.ZltTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZltTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val exchanges by viewModel.diagnosticExchanges.collectAsStateWithLifecycle()

                ZltApp(
                    state = state,
                    diagnosticExchanges = exchanges,
                    onHostChange = viewModel::updateHost,
                    onUsernameChange = viewModel::updateUsername,
                    onPasswordChange = viewModel::updatePassword,
                    onConnect = viewModel::connect,
                    onDiscover = viewModel::discoverDevice,
                    onEnableDemo = { viewModel.enableDemoMode() },
                    onDismissMessage = viewModel::dismissMessage,
                    onRefresh = viewModel::refresh,
                    onSavePlan = viewModel::saveManualPlan,
                    onUpdateWifi = viewModel::updateWifi,
                    onRestart = viewModel::restartRouter,
                    onDisconnect = viewModel::disconnect,
                    onShareDiagnostics = { share(viewModel.diagnosticsReportText()) },
                    onClearDiagnostics = viewModel::clearDiagnostics,
                )
            }
        }
    }

    /**
     * Sharing is the only way a report leaves the device, and the user chooses the recipient. The
     * text was redacted at capture time, so no credential is in what is handed over.
     */
    private fun share(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "سجل تشخيص ZLT M90 Plus")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "مشاركة سجل التشخيص"))
    }
}