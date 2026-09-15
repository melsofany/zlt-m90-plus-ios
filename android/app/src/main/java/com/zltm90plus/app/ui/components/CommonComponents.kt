package com.zltm90plus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.ui.theme.statusColors

/** Standard rounded card with an Arabic-titled header. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * A coloured status chip. Colour is never the only signal: each chip also carries an icon and
 * a text label so it survives greyscale and colour-vision differences.
 */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.invoke()
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** One labelled value row, used across all detail cards. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueWeight: FontWeight = FontWeight.SemiBold,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = valueWeight,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/**
 * The recurrent "this value is not available" affordance. Preferring an explicit statement over
 * a placeholder number is a core product rule, so it has its own component.
 */
@Composable
fun UnavailableNotice(
    featureName: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = statusColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.warning.copy(alpha = 0.12f))
            .padding(12.dp)
            .semantics { contentDescription = "$featureName غير متاح" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(
                text = "غير متاحة من الجهاز",
                style = MaterialTheme.typography.labelLarge,
                color = colors.warning,
            )
            Text(
                text = detail ?: "$featureName لا يوفّرها Firmware الجهاز في هذه النسخة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Arabic label describing where a value came from. Shown next to every non-obvious figure. */
@Composable
fun DataSourceLabel(source: DataSource, modifier: Modifier = Modifier) {
    val colors = statusColors()
    val (text, color) = when (source) {
        DataSource.ROUTER -> "من الجهاز" to colors.success
        DataSource.CARRIER -> "من مزود الخدمة" to colors.success
        DataSource.MANUAL -> "إدخال يدوي" to colors.warning
        DataSource.ESTIMATED -> "تقديري" to colors.warning
        DataSource.UNAVAILABLE -> "غير متاحة" to colors.unknown
    }
    StatusChip(label = text, color = color, modifier = modifier)
}