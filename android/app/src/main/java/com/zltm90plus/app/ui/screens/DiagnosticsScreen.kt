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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.diagnostics.DiagnosticExchange
import com.zltm90plus.app.ui.components.SectionCard
import com.zltm90plus.app.ui.components.UnavailableNotice

/**
 * The connection log, on the phone.
 *
 * A failed connection leaves one Arabic sentence on screen and nothing else, which is not enough
 * to tell a wrong address from a wrong password from a firmware that answers differently than
 * expected. This shows what the app actually sent and what came back.
 *
 * Passwords and tokens are masked before an entry reaches this list, so the screen is safe to
 * screenshot and the text is safe to share.
 */
@Composable
fun DiagnosticsScreen(
    exchanges: List<DiagnosticExchange>,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "سجل الاتصال") {
            Text(
                text = "كل محاولة اتصال بالجهاز تُسجَّل هنا بما أرسله التطبيق وما ردّ به الجهاز. " +
                    "كلمات المرور والرموز محجوبة تلقائيًا. السجل في ذاكرة التطبيق فقط ولا يُرسل إلى أي جهة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onShare,
                    enabled = exchanges.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("مشاركة")
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = exchanges.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Text("مسح")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("رجوع")
            }
        }

        if (exchanges.isEmpty()) {
            SectionCard(title = "لا توجد عمليات") {
                UnavailableNotice(
                    featureName = "سجل الاتصال",
                    detail = "لم تُسجَّل أي محاولة بعد. اضغط «اتصال بالجهاز» على شاشة البداية ثم عد إلى هنا.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Newest first: the failure being investigated is the last thing that happened.
                items(exchanges.asReversed()) { exchange ->
                    ExchangeCard(exchange)
                }
            }
        }
    }
}

@Composable
private fun ExchangeCard(exchange: DiagnosticExchange) {
    val failed = exchange.error != null
    SectionCard(title = exchange.method) {
        Text(
            text = exchange.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (failed) {
            // Failure is the interesting case, so it is stated in words rather than left to a
            // missing status code.
            Text(
                text = "تعذّر إتمام الطلب",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = exchange.error!!,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = "حالة HTTP: ${exchange.statusCode}",
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = "المدة: ${exchange.durationMillis} م.ث",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        exchange.requestBody?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            BodyBlock(label = "الطلب", body = it)
        }
        exchange.responseBody?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            BodyBlock(label = "الرد من الجهاز", body = it)
        }
    }
}

@Composable
private fun BodyBlock(label: String, body: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}