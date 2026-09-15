package com.zltm90plus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.data.model.BatteryReading
import com.zltm90plus.app.data.model.ConnectedDevices
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.domain.BatteryEstimator
import com.zltm90plus.app.ui.components.DataSourceLabel
import com.zltm90plus.app.ui.components.InfoRow
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.components.UnavailableNotice
import com.zltm90plus.app.ui.label
import com.zltm90plus.app.util.ArabicFormat

/** Screen 3: battery detail, separating device-reported remaining time from the app estimate. */
@Composable
fun BatteryDetailScreen(
    snapshot: DeviceSnapshot,
    estimate: BatteryEstimator.Result?,
    history: List<BatteryReading>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val battery = snapshot.battery

        SectionCard(title = "الحالة الحالية", trailing = { DataSourceLabel(battery.source) }) {
            if (!battery.isAvailable) {
                UnavailableNotice(
                    featureName = "قراءة البطارية",
                    detail = "لم تُرجع واجهة الجهاز نسبة الشحن. تحقق من إصدار Firmware أو من دعم الجهاز لهذه القراءة.",
                )
            } else {
                InfoRow(label = "النسبة الحالية", value = ArabicFormat.percent(battery.percent!!))
                InfoRow(label = "حالة الشحن", value = battery.chargingState.label())
                InfoRow(label = "آخر تحديث", value = ArabicFormat.relativeTime(battery.updatedAtMillis))
                battery.voltageMillivolts?.let { InfoRow(label = "الجهد", value = "$it mV") }
                battery.temperatureCelsius?.let { InfoRow(label = "الحرارة", value = "$it °C") }
            }
        }

        SectionCard(title = "الوقت المتبقي") {
            val reported = battery.deviceReportedRemainingMinutes
            InfoRow(
                label = "حسب الجهاز",
                value = reported?.let { ArabicFormat.duration(it) } ?: "غير متاح من الجهاز",
                valueColor = if (reported == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            when (val result = estimate) {
                is BatteryEstimator.Result.Estimated -> {
                    InfoRow(
                        label = "تقدير التطبيق",
                        value = ArabicFormat.duration(result.minutes),
                    )
                    InfoRow(
                        label = "متوسط الاستهلاك",
                        value = String.format(java.util.Locale("ar"), "%.1f٪ في الساعة", result.percentPerHour),
                    )
                    InfoRow(label = "عدد القراءات المستخدمة", value = "${result.sampleCount}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "الوقت المتبقي تقديري وقد يختلف حسب قوة الإشارة وعدد الأجهزة المتصلة واستهلاك البيانات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BatteryEstimator.Result.Unavailable, null -> {
                    InfoRow(
                        label = "تقدير التطبيق",
                        value = "لا يمكن التقدير حاليًا",
                    )
                    Text(
                        text = estimateReason((estimate as? BatteryEstimator.Result.Unavailable)?.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BatteryEstimator.Result.DeviceReported -> {
                    Text(
                        text = "الجهاز يرسل زمنًا فعليًا متبقيًا، لذلك يُعرض أولًا ولا يُستبدل بتقدير التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(title = "سجل قراءات البطارية") {
            if (history.isEmpty()) {
                Text(
                    text = "لا توجد قراءات محفوظة بعد. يُبنى السجل تلقائيًا مع كل تحديث ناجح.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "آخر ${history.size} قراءة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                history.takeLast(12).reversed().forEach { reading ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = ArabicFormat.relativeTime(reading.timestampMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${ArabicFormat.percent(reading.percent)} · ${reading.chargingState.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

private fun estimateReason(reason: BatteryEstimator.Reason?): String = when (reason) {
    BatteryEstimator.Reason.BATTERY_EMPTY -> "البطارية فارغة، ولا يمكن تقدير زمن متبقٍ."
    BatteryEstimator.Reason.CHARGING -> "التقدير لا يُحسب أثناء الشحن. يُعرض بعد فصل الشاحن واستقرار الاستهلاك."
    BatteryEstimator.Reason.NOT_ENOUGH_READINGS -> "لا توجد قراءات كافية بعد. يحتاج التقدير إلى عدة قراءات متتالية."
    BatteryEstimator.Reason.TIME_SPAN_TOO_SHORT -> "الفترة الزمنية بين القراءات قصيرة جدًا لحساب معدل موثوق."
    BatteryEstimator.Reason.NO_DISCHARGE_TREND -> "لا يوجد انخفاض واضح في البطارية خلال الفترة المرصودة."
    BatteryEstimator.Reason.ERRATIC_READINGS -> "تغيّر الحمل بشكل كبير خلال القراءات، ما يجعل التقدير غير موثوق."
    BatteryEstimator.Reason.NO_READINGS, null -> "لا توجد قراءات محفوظة حتى الآن."
}

/** Screen 4: connection detail with an on-demand probe. */
@Composable
fun ConnectionDetailScreen(
    snapshot: DeviceSnapshot,
    onRefreshNow: () -> Unit,
    isRefreshing: Boolean,
) {
    val network = snapshot.network
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "حالة الاتصال", trailing = { DataSourceLabel(network.source) }) {
            InfoRow(label = "حالة الإنترنت", value = network.state.label())
            InfoRow(label = "المشغل", value = network.carrierName ?: "غير متاح")
            InfoRow(label = "نوع الشبكة", value = network.networkType ?: "غير متاح")
            InfoRow(
                label = "قوة الإشارة",
                value = when {
                    network.signalPercent != null -> ArabicFormat.percent(network.signalPercent)
                    network.signalDbm != null -> "${network.signalDbm} dBm"
                    else -> "غير متاحة"
                },
            )
            InfoRow(label = "عنوان IP المحلي", value = network.localIpAddress ?: "غير متاح")
            InfoRow(
                label = "مدة الاتصال",
                value = network.connectionUptimeMinutes?.let { ArabicFormat.duration(it.toInt()) } ?: "غير متاحة",
            )
            InfoRow(
                label = "آخر اختبار ناجح",
                value = network.lastSuccessfulProbeMillis?.let { ArabicFormat.relativeTime(it) } ?: "لم ينجح بعد",
            )
        }

        SectionCard(title = "اختبار الاتصال") {
            Text(
                text = "يُختبر الاتصال بعنوان عام معروف، لأن نجاح الوصول إلى صفحة الراوتر لا يُعد دليلًا كافيًا على وجود إنترنت.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = onRefreshNow,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isRefreshing) "جاري التحديث…" else "تحديث الآن")
            }
        }
    }
}

/** Screen 5: manual plan entry plus a simple daily-consumption chart when history exists. */
@Composable
fun PlanDetailScreen(
    plan: com.zltm90plus.app.data.model.DataPlanStatus,
    onSave: (Long, Long?, Long?, Long?, String?) -> Unit,
    dailyUsageBytes: List<Pair<String, Long>>,
) {
    val totalText = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val unit = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("GB") }
    val usedText = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val renewalText = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "إدخال بيانات الباقة يدويًا", trailing = { DataSourceLabel(plan.source) }) {
            Text(
                text = "استخدم هذه الحقول فقط إذا لم يوفّر الجهاز أو مزود الخدمة بيانات الباقة. سيتم وسم القيم بأنها «تقديرية حسب إدخال المستخدم».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = totalText.value,
                onValueChange = { totalText.value = it },
                label = { Text("حجم الباقة") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("GB", "MB").forEach { option ->
                    androidx.compose.material3.FilterChip(
                        selected = unit.value == option,
                        onClick = { unit.value = option },
                        label = { Text(option) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = usedText.value,
                onValueChange = { usedText.value = it },
                label = { Text("المستخدم حاليًا") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = renewalText.value,
                onValueChange = { renewalText.value = it },
                label = { Text("تاريخ التجديد (سنة-شهر-يوم)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(
                onClick = {
                    val multiplier = if (unit.value == "GB") 1_000_000_000L else 1_000_000L
                    val total = totalText.value.toDoubleOrNull()?.let { (it * multiplier).toLong() }
                    val used = usedText.value.toDoubleOrNull()?.let { (it * multiplier).toLong() }
                    val renewal = renewalText.value.takeIf { it.isNotBlank() }?.let(::parseDateOrNull)
                    if (total != null) onSave(total, used, null, renewal, null)
                },
                enabled = totalText.value.toDoubleOrNull() != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("حفظ بيانات الباقة")
            }
        }

        SectionCard(title = "الاستهلاك اليومي") {
            if (dailyUsageBytes.isEmpty()) {
                UnavailableNotice(
                    featureName = "الرسم البياني للاستهلاك",
                    detail = "لا توجد قراءات تاريخية كافية. لا يعرف التطبيق استهلاك الشريحة إلا إذا أعاد الجهاز هذه البيانات فعلًا.",
                )
            } else {
                dailyUsageBytes.forEach { (label, bytes) ->
                    InfoRow(label = label, value = ArabicFormat.dataSize(bytes))
                }
            }
        }
    }
}

private fun parseDateOrNull(raw: String): Long? =
    runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(raw.trim())?.time
    }.getOrNull()

/** Connected-devices list. Block/disconnect actions are intentionally not wired yet. */
@Composable
fun ConnectedDevicesScreen(devices: ConnectedDevices) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (devices.source == DataSource.UNAVAILABLE) {
            UnavailableNotice(
                featureName = "قائمة الأجهزة المتصلة",
                detail = "لم تُرجع واجهة الجهاز قائمة العملاء المتصلين.",
            )
            return@Column
        }

        Text(
            text = ArabicFormat.deviceCount(devices.devices.size),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "الحظر أو الفصل وظيفة اختيارية ولا تُنفذ إلا بعد تأكيد صريح منك، وهي غير مُفعّلة في هذه النسخة.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices.devices, key = { it.stableId }) { device ->
                SectionCard(title = device.hostname ?: "جهاز بدون اسم") {
                    device.macAddress?.let { InfoRow(label = "عنوان MAC", value = it) }
                    device.ipAddress?.let { InfoRow(label = "عنوان IP", value = it) }
                    device.connectionType?.let { InfoRow(label = "نوع الاتصال", value = it) }
                }
            }
        }
    }
}