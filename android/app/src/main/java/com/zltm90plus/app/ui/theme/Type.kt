package com.zltm90plus.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/**
 * Arabic-first typography. Every style sets [TextDirection.Rtl] so mixed Arabic/Latin strings
 * (like "192.168.0.1" or "GB 21") render with the correct paragraph direction when the user's
 * system language is not Arabic.
 */
private fun rtl(style: TextStyle) = style.copy(
    textDirection = TextDirection.Rtl,
    fontFamily = FontFamily.Default,
)

val ZltTypography = Typography(
    displaySmall = rtl(TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 44.sp)),
    headlineMedium = rtl(TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 36.sp)),
    headlineSmall = rtl(TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 32.sp)),
    titleLarge = rtl(TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 30.sp)),
    titleMedium = rtl(TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp)),
    bodyLarge = rtl(TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)),
    bodyMedium = rtl(TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp)),
    bodySmall = rtl(TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)),
    labelLarge = rtl(TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp)),
    labelMedium = rtl(TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)),
    labelSmall = rtl(TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)),
)