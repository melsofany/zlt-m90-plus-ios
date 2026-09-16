package com.zltm90plus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.ui.ConnectionPhase
import com.zltm90plus.app.ui.DashboardUiState
import com.zltm90plus.app.ui.components.DeviceImage
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.components.StatusChip
import com.zltm90plus.app.ui.theme.statusColors

/** Welcome + connection screen. Arabic RTL, product picture on top, then the credential form. */
@Composable
fun ConnectScreen(
    state: DashboardUiState,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDiscover: () -> Unit,
    onEnableDemo: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var showTechnical by remember { mutableStateOf(false) }
    val colors = statusColors()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceImage(modifier = Modifier.size(170.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "ZLT M90 Plus",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "لإدارة الجهاز، يجب أن يكون هاتفك متصلًا بشبكة Wi-Fi الخاصة بـ ZLT M90 Plus.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            if (!state.wifiConnected) {
                StatusChip(
                    label = "الهاتف غير متصل حاليًا بشبكة Wi-Fi",
                    color = colors.warning,
                    icon = {
                        Icon(Icons.Default.Wifi, null, tint = colors.warning, modifier = Modifier.size(16.dp))
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            SectionCard(title = "بيانات الاتصال بالجهاز") {
                OutlinedTextField(
                    value = state.loginForm.host,
                    onValueChange = onHostChange,
                    label = { Text("عنوان الجهاز") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.loginForm.username,
                    onValueChange = onUsernameChange,
                    label = { Text("اسم المستخدم") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.loginForm.password,
                    onValueChange = onPasswordChange,
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "تُحفظ كلمة المرور في التخزين الآمن للجهاز فقط، ولا تُرسل إلى أي خادم خارجي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onConnect,
                    enabled = state.phase != ConnectionPhase.CONNECTING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.phase == ConnectionPhase.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text("اتصال بالجهاز")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDiscover,
                    enabled = !state.isDiscovering,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.isDiscovering) "جاري البحث…" else "اكتشاف الجهاز تلقائيًا")
                }
            }

            Spacer(Modifier.height(12.dp))
            state.userMessage?.let { message ->
                ConnectionStatusCard(
                    phase = state.phase,
                    message = message,
                    technicalDetail = state.technicalDetail,
                    showTechnical = showTechnical,
                    onToggleTechnical = { showTechnical = !showTechnical },
                    onDismiss = onDismissMessage,
                )
                Spacer(Modifier.height(12.dp))
            }

            SectionCard(title = "وضع العرض التجريبي") {
                Text(
                    text = "لتجربة الواجهة دون جهاز فعلي. كل القيم في هذا الوضع تُعرض كبيانات تجريبية وليست قيمًا حقيقية.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEnableDemo) { Text("تفعيل وضع العرض التجريبي") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    phase: ConnectionPhase,
    message: String,
    technicalDetail: String?,
    showTechnical: Boolean,
    onToggleTechnical: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = statusColors()
    val color = when (phase) {
        ConnectionPhase.CONNECTED -> colors.success
        ConnectionPhase.CONNECTING -> colors.warning
        ConnectionPhase.IDLE -> colors.unknown
        else -> colors.danger
    }
    val title = when (phase) {
        ConnectionPhase.CONNECTING -> "جاري الاتصال"
        ConnectionPhase.CONNECTED -> "تم الاتصال"
        ConnectionPhase.INVALID_CREDENTIALS -> "بيانات الدخول غير صحيحة"
        ConnectionPhase.DEVICE_NOT_FOUND -> "الجهاز غير موجود"
        ConnectionPhase.PHONE_NOT_ON_DEVICE_NETWORK -> "الهاتف غير متصل بشبكة ZLT"
        ConnectionPhase.UNSUPPORTED_FIRMWARE -> "واجهة الجهاز غير مدعومة"
        ConnectionPhase.SESSION_EXPIRED -> "انتهت الجلسة"
        ConnectionPhase.TEMPORARY_FAILURE -> "فشل مؤقت"
        ConnectionPhase.IDLE -> "لم يبدأ الاتصال بعد"
    }

    SectionCard(
        title = "حالة الاتصال",
        trailing = { StatusChip(label = title, color = color) },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (technicalDetail != null || phase == ConnectionPhase.TEMPORARY_FAILURE) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onToggleTechnical) {
                Text(if (showTechnical) "إخفاء التفاصيل التقنية" else "تفاصيل تقنية")
            }
            if (showTechnical) {
                Text(
                    text = technicalDetail ?: "لا توجد تفاصيل إضافية.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = onDismiss) { Text("إخفاء") }
        }
    }
}