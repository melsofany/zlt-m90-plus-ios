package com.zltm90plus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.ui.DashboardUiState
import com.zltm90plus.app.ui.components.InfoRow
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.util.ArabicFormat

/**
 * Settings and lifecycle actions.
 *
 * Both Wi-Fi changes and a restart affect the physical device, so each is behind an explicit
 * confirmation dialog. They are also blocked in demo mode by the ViewModel.
 */
@Composable
fun SettingsScreen(
    state: DashboardUiState,
    onUpdateWifi: (String, String, (Result<Unit>) -> Unit) -> Unit,
    onRestart: ((Result<Unit>) -> Unit) -> Unit,
    onOpenPlanSetup: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var ssid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "معلومات الجهاز") {
            val info = state.snapshot?.deviceInfo
            InfoRow(label = "الموديل", value = info?.model ?: "غير متاح")
            InfoRow(label = "إصدار Firmware", value = info?.firmwareVersion ?: "غير متاح")
            InfoRow(label = "العنوان المُعد", value = state.loginForm.host)
            state.lastRefreshMillis?.let {
                InfoRow(label = "آخر تحديث ناجح", value = ArabicFormat.relativeTime(it))
            }
        }

        SectionCard(title = "إعداد الباقة") {
            Text(
                text = "أدخل بيانات الباقة يدويًا عندما لا يوفّرها الجهاز أو مزود الخدمة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenPlanSetup, modifier = Modifier.fillMaxWidth()) {
                Text("إعداد الباقة")
            }
        }

        SectionCard(title = "تغيير شبكة Wi-Fi") {
            Text(
                text = "عملية مؤثرة: ستُفصل الأجهزة المتصلة عن الشبكة الحالية بعد التغيير.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("اسم الشبكة (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wifiPassword,
                onValueChange = { wifiPassword = it },
                label = { Text("كلمة مرور الشبكة") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { pendingAction = PendingAction.Wifi(ssid, wifiPassword) },
                enabled = ssid.isNotBlank() && wifiPassword.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("حفظ إعدادات Wi-Fi")
            }
        }

        SectionCard(title = "إعادة تشغيل الجهاز") {
            Text(
                text = "عملية مؤثرة: سينقطع الاتصال لمدة دقيقة تقريبًا.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { pendingAction = PendingAction.Restart },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("إعادة تشغيل الجهاز")
            }
        }

        SectionCard(title = "الجلسة") {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("قطع الاتصال ومسح الجلسة")
            }
        }

        resultMessage?.let { message ->
            SectionCard(title = "نتيجة العملية") {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { resultMessage = null }) { Text("إخفاء") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "لا ينفذ التطبيق أي عملية مؤثرة دون تأكيد صريح منك، ولا يرسل بيانات الجهاز إلى أي خادم خارجي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingAction?.let { action ->
        val title = when (action) {
            is PendingAction.Wifi -> "تأكيد تغيير شبكة Wi-Fi"
            PendingAction.Restart -> "تأكيد إعادة تشغيل الجهاز"
        }
        val body = when (action) {
            is PendingAction.Wifi -> "سيتم تغيير اسم الشبكة وكلمة المرور. هل أنت متأكد؟"
            PendingAction.Restart -> "سيتم إعادة تشغيل الجهاز وستنقطع الاتصالات مؤقتًا. هل أنت متأكد؟"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            is PendingAction.Wifi -> onUpdateWifi(action.ssid, action.password) { result ->
                                resultMessage = result.fold(
                                    onSuccess = { "تم إرسال إعدادات Wi-Fi إلى الجهاز." },
                                    onFailure = { it.message ?: "فشلت العملية." },
                                )
                            }
                            PendingAction.Restart -> onRestart { result ->
                                resultMessage = result.fold(
                                    onSuccess = { "تم إرسال أمر إعادة التشغيل إلى الجهاز." },
                                    onFailure = { it.message ?: "فشلت العملية." },
                                )
                            }
                        }
                    },
                ) { Text("تأكيد") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("إلغاء") }
            },
        )
    }
}

private sealed interface PendingAction {
    data class Wifi(val ssid: String, val password: String) : PendingAction
    data object Restart : PendingAction
}