package com.zltm90plus.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.ui.DashboardUiState
import com.zltm90plus.app.ui.components.DeviceImage
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.label
import com.zltm90plus.app.util.ArabicFormat

/** Screen 6: a dedicated full-size view of the device picture with quick indicators. */
@Composable
fun DeviceImageScreen(state: DashboardUiState) {
    val snapshot = state.snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionCard(title = "صورة الجهاز") {
            DeviceImage(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "لم تُضمَّن صورة رسمية مرخّصة لجهاز ZLT M90 Plus في هذا المشروع. " +
                    "لعرض الصورة الحقيقية، ضع ملفًا باسم zlt_m90_plus_device.png في مجلد assets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (snapshot != null) {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "مؤشرات سريعة") {
                Text(
                    text = "البطارية: " + (
                        snapshot.battery.percent?.let { ArabicFormat.percent(it) } ?: "غير متاحة"
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "الاتصال: ${snapshot.network.state.label()}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "الأجهزة المتصلة: " +
                        (snapshot.connectedDevices.count?.let { ArabicFormat.deviceCount(it) } ?: "غير متاحة"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}