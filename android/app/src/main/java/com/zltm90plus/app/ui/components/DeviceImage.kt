package com.zltm90plus.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zltm90plus.app.R

/**
 * Optional high-resolution product photo. Drop a licensed ZLT M90 Plus image at
 * `app/src/main/assets/zlt_m90_plus_device.png` and it replaces the placeholder automatically,
 * with no code change.
 */
private const val REAL_PHOTO_ASSET = "zlt_m90_plus_device.png"

/**
 * Shows the ZLT M90 Plus product picture.
 *
 * This repository ships no licensed vendor photograph, so by default it renders a clearly
 * labelled schematic placeholder. The placeholder is never presented as an official image.
 */
@Composable
fun DeviceImage(
    modifier: Modifier = Modifier,
    contentDescriptionText: String = "صورة جهاز ZLT M90 Plus",
) {
    val context = LocalContext.current
    val photo = remember(context) { loadAssetBitmap(context, REAL_PHOTO_ASSET) }

    if (photo != null) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = contentDescriptionText,
            contentScale = ContentScale.Fit,
            modifier = modifier.semantics { contentDescription = contentDescriptionText },
        )
    } else {
        DeviceIllustration(modifier = modifier, label = contentDescriptionText)
    }
}

@Composable
private fun DeviceIllustration(modifier: Modifier, label: String) {
    Column(
        modifier = modifier.semantics {
            contentDescription = "$label — صورة مؤقتة وليست صورة رسمية للمنتج"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.zlt_m90_plus_device),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(160.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "صورة مؤقتة",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

private fun loadAssetBitmap(context: Context, name: String) =
    runCatching { context.assets.open(name).use { BitmapFactory.decodeStream(it) } }.getOrNull()