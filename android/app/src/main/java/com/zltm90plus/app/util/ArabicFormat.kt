package com.zltm90plus.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Arabic (Egypt) formatting helpers. All user-visible text and numbers are Arabic-first. */
object ArabicFormat {

    private val locale = Locale("ar")

    fun percent(value: Int): String = "$value٪"

    fun percent(value: Double): String = "${Math.round(value).toInt()}٪"

    /** "5 ساعات و20 دقيقة" style duration from minutes. */
    fun duration(minutes: Int): String {
        if (minutes <= 0) return "أقل من دقيقة"
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> "$mins دقيقة"
            mins == 0 -> "$hours ساعة"
            else -> "$hours ساعة و$mins دقيقة"
        }
    }

    fun dataSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        val mb = bytes / 1_000_000.0
        return when {
            gb >= 1.0 -> String.format(locale, "%.1f GB", gb)
            mb >= 1.0 -> String.format(locale, "%.0f MB", mb)
            bytes >= 1_000 -> String.format(locale, "%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    fun dateTime(millis: Long): String =
        SimpleDateFormat("d MMM yyyy - HH:mm", locale).format(Date(millis))

    fun date(millis: Long): String =
        SimpleDateFormat("d MMM yyyy", locale).format(Date(millis))

    fun relativeTime(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = nowMillis - millis
        return when {
            diff < 0 -> "الآن"
            diff < 60_000 -> "الآن"
            diff < 3_600_000 -> "قبل ${diff / 60_000} دقيقة"
            diff < 86_400_000 -> "قبل ${diff / 3_600_000} ساعة"
            else -> dateTime(millis)
        }
    }

    /** Arabic pluralisation for a device count. */
    fun deviceCount(count: Int): String = when (count) {
        0 -> "لا توجد أجهزة متصلة"
        1 -> "جهاز واحد متصل"
        2 -> "جهازان متصلان"
        in 3..10 -> "$count أجهزة متصلة"
        else -> "$count جهازًا متصلًا"
    }

    fun days(count: Long): String = when (count) {
        0L -> "ينتهي اليوم"
        1L -> "يوم واحد متبقٍ"
        2L -> "يومان متبقيان"
        in 3..10 -> "$count أيام متبقية"
        else -> "$count يومًا متبقيًا"
    }
}