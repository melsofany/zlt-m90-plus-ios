package com.zltm90plus.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Circular percentage gauge used by the data-plan card.
 *
 * The ring is backed by a numeric label, so the value is readable without relying on colour
 * or on the arc alone.
 */
@Composable
fun ProgressRing(
    fraction: Double,
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 132.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 14.dp,
    centerLabel: String? = null,
    centerSubLabel: String? = null,
) {
    val clamped = fraction.coerceIn(0.0, 1.0)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = centerLabel?.let { "$it ${centerSubLabel.orEmpty()}" }
                    ?: "نسبة مستخدمة ${Math.round(clamped * 100)} بالمئة"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Sweep clockwise from the top; RTL users read the numeric centre, not the arc angle.
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = (clamped * 360f).toFloat(),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            centerLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            centerSubLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}