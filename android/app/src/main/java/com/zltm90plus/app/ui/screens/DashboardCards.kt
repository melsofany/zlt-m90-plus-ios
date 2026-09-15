package com.zltm90plus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.NetworkState
import com.zltm90plus.app.data.model.SignalLevel
import com.zltm90plus.app.domain.BatteryEstimator
import com.zltm90plus.app.domain.DataPlanCalculator
import com.zltm90plus.app.ui.components.DataSourceLabel
import com.zltm90plus.app.ui.components.InfoRow
import com.zltm90plus.app.ui.components.ProgressRing
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.components.StatusChip
import com.zltm90plus.app.ui.components.UnavailableNotice
import com.zltm90plus.app.ui.label
import com.zltm90plus.app.ui.theme.statusColors
import com.zltm90plus.app.util.ArabicFormat

/**
 * Battery card. Never fabricates a percentage: a missing firmware value shows the explicit
 * "unavailable" notice instead, and the remaining-time line distinguishes device-reported
 * values from app estimates.
 */
@Composable
fun BatteryCard(
    snapshot: DeviceSnapshot,
    estimate: BatteryEstimator.Result?,
    modifier: Modifier = Modifier,
) {
    val battery = snapshot.battery
    val colors = statusColors()

    SectionCard(
        title = "البطارية",
        modifier = modifier,
        trailing = {
            Icon(
                imageVector = Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        if (!battery.isAvailable) {
            UnavailableNotice(
                featureName = "نسبة البطارية",
                detail = "Firmware الجهاز لا يوفّر نسبة الشحن في هذه النسخة. لن يعرض التطبيق أي رقم تقديري بدلًا منها.",
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ArabicFormat.percent(battery.percent!!),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(12.dp))
                StatusChip(
                    label = battery.chargingState.label(),
                    color = when (battery.chargingState) {
                        ChargingState.CHARGING -> colors.warning
                        ChargingState.FULL -> colors.success
                        ChargingState.DISCHARGING -> MaterialTheme.colorScheme.primary
                        ChargingState.UNKNOWN -> colors.unknown
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            DataSourceLabel(source = battery.source)
        }

        Spacer(Modifier.height(12.dp))
        RemainingTimeRow(estimate = estimate)

        Spacer(Modifier.height(8.dp))
        InfoRow(
            label = "آخر تحديث",
            value = ArabicFormat.relativeTime(battery.updatedAtMillis),
            valueWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}

@Composable
fun RemainingTimeRow(estimate: BatteryEstimator.Result?, modifier: Modifier = Modifier) {
    val colors = statusColors()
    when (estimate) {
        is BatteryEstimator.Result.DeviceReported -> {
            InfoRow(
                label = "الوقت المتبقي",
                value = "${ArabicFormat.duration(estimate.minutes)} (حسب الجهاز)",
                modifier = modifier,
            )
        }
        is BatteryEstimator.Result.Estimated -> {
            Column(modifier = modifier.fillMaxWidth()) {
                InfoRow(
                    label = "الوقت المتبقي",
                    value = "${ArabicFormat.duration(estimate.minutes)} (تقديري)",
                )
                Text(
                    text = "الوقت المتبقي تقديري وقد يختلف حسب قوة الإشارة وعدد الأجهزة المتصلة واستهلاك البيانات.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is BatteryEstimator.Result.Unavailable, null -> {
            InfoRow(
                label = "الوقت المتبقي",
                value = "لا يمكن التقدير حاليًا",
                valueColor = colors.warning,
            )
        }
    }
}

/** Internet + mobile network card. Each state carries text, icon and colour together. */
@Composable
fun NetworkCard(snapshot: DeviceSnapshot, modifier: Modifier = Modifier) {
    val network = snapshot.network
    val colors = statusColors()
    val stateColor = when (network.state) {
        NetworkState.ONLINE -> colors.success
        NetworkState.ROUTER_ONLY_NO_INTERNET, NetworkState.CONNECTING -> colors.warning
        NetworkState.NO_CELLULAR -> colors.danger
        NetworkState.UNKNOWN -> colors.unknown
    }

    SectionCard(
        title = "الاتصال والإنترنت",
        modifier = modifier,
        trailing = {
            Icon(
                imageVector = Icons.Default.SignalCellular4Bar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        StatusChip(label = network.state.label(), color = stateColor)
        Spacer(Modifier.height(10.dp))

        network.carrierName?.let {
            InfoRow(label = "المشغل", value = it)
        } ?: InfoRow(
            label = "المشغل",
            value = "غير متاح",
            valueColor = colors.unknown,
        )

        network.networkType?.let {
            InfoRow(label = "نوع الشبكة", value = it)
        } ?: InfoRow(
            label = "نوع الشبكة",
            value = "غير متاح",
            valueColor = colors.unknown,
        )

        InfoRow(
            label = "قوة الإشارة",
            value = when {
                network.signalPercent != null -> "${signalLabel(network.signalLevel)} (${ArabicFormat.percent(network.signalPercent)})"
                network.signalLevel != SignalLevel.UNKNOWN -> signalLabel(network.signalLevel)
                else -> "غير متاحة"
            },
            valueColor = if (network.signalLevel == SignalLevel.UNKNOWN) colors.unknown else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun signalLabel(level: SignalLevel): String = when (level) {
    SignalLevel.EXCELLENT -> "ممتازة"
    SignalLevel.GOOD -> "جيدة"
    SignalLevel.FAIR -> "متوسطة"
    SignalLevel.WEAK -> "ضعيفة"
    SignalLevel.NONE -> "لا توجد إشارة"
    SignalLevel.UNKNOWN -> "غير معروفة"
}

/** Data plan card with ring gauge. Shows an explicit empty state when no source is configured. */
@Composable
fun DataPlanCard(
    plan: DataPlanStatus,
    onSetupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    val summary = DataPlanCalculator.summarize(plan)

    SectionCard(
        title = "الباقة والاستهلاك",
        modifier = modifier,
        trailing = { DataSourceLabel(source = plan.source) },
    ) {
        if (summary == null) {
            UnavailableNotice(
                featureName = "بيانات الباقة",
                detail = "الجهاز لا يعرض بيانات الباقة، ولم يتم إدخالها يدويًا بعد.",
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSetupClick, modifier = Modifier.fillMaxWidth()) {
                Text("إعداد الباقة")
            }
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            ProgressRing(
                fraction = summary.usedFraction,
                color = when {
                    summary.usedFraction >= 0.9 -> colors.danger
                    summary.usedFraction >= 0.75 -> colors.warning
                    else -> colors.success
                },
                centerLabel = ArabicFormat.percent(summary.usedPercentRounded),
                centerSubLabel = "مستخدم",
            )
        }
        Spacer(Modifier.height(12.dp))

        InfoRow(label = "المستخدم", value = ArabicFormat.dataSize(plan.usedBytes ?: 0L))
        InfoRow(label = "إجمالي الباقة", value = ArabicFormat.dataSize(plan.totalBytes ?: 0L))
        InfoRow(label = "المتبقي", value = ArabicFormat.dataSize(summary.remainingBytes))
        plan.renewalMillis?.let { renewal ->
            InfoRow(label = "تاريخ التجديد", value = ArabicFormat.date(renewal))
            summary.daysRemaining?.let { days ->
                InfoRow(label = "المتبقي للباقة", value = ArabicFormat.days(days))
            }
        }
        if (plan.source == DataSource.MANUAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "تقديرية حسب إدخال المستخدم.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSetupClick, modifier = Modifier.fillMaxWidth()) {
            Text("تعديل بيانات الباقة")
        }
    }
}

/** Connected Wi-Fi clients summary card. */
@Composable
fun ConnectedDevicesCard(
    snapshot: DeviceSnapshot,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    val count = snapshot.connectedDevices.count

    SectionCard(
        title = "الأجهزة المتصلة",
        modifier = modifier,
        trailing = {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        when (count) {
            null -> UnavailableNotice(
                featureName = "قائمة الأجهزة المتصلة",
                detail = "Firmware الجهاز لا يوفّر قائمة العملاء المتصلين في هذه النسخة.",
            )
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = ArabicFormat.deviceCount(count),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (count == 0) colors.unknown else MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onOpenList) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = "فتح قائمة الأجهزة المتصلة",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenList, modifier = Modifier.fillMaxWidth()) {
                    Text("عرض الأجهزة")
                }
            }
        }
    }
}

/** Header card: product identity plus a one-line health summary. */
@Composable
fun DeviceHeaderCard(
    snapshot: DeviceSnapshot,
    isDemo: Boolean,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    SectionCard(
        title = snapshot.deviceInfo.model ?: "ZLT M90 Plus",
        modifier = modifier,
        trailing = {
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(
                    imageVector = Icons.Default.Cached,
                    contentDescription = "تحديث الآن",
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isDemo) {
                StatusChip(label = "بيانات تجريبية", color = colors.warning)
            }
            snapshot.deviceInfo.firmwareVersion?.let {
                StatusChip(label = "Firmware $it", color = colors.unknown)
            }
        }
        Spacer(Modifier.height(8.dp))
        snapshot.deviceInfo.wifiSsid?.let {
            InfoRow(label = "شبكة Wi-Fi", value = it)
        }
        if (isDemo) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "أنت في وضع العرض التجريبي. القيم المعروضة أمثلة لتصميم الواجهة وليست قراءات حقيقية من الجهاز.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
    }
}