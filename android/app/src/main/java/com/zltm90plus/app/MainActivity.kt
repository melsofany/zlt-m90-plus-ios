package com.zltm90plus.app

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

                ZltApp(
                    state = state,
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
                )
            }
        }
    }
}