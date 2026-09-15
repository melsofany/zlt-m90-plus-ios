package com.zltm90plus.app.domain

import com.zltm90plus.app.data.model.BatteryReading
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.ReadingQuality
import kotlin.math.abs
import kotlin.math.max

/**
 * Estimates remaining runtime from a series of battery readings.
 *
 * The estimator refuses to answer unless it has a real trend: at least [MIN_READINGS]
 * discharging samples spanning at least [MIN_SPAN_MS]. A single reading can never produce
 * a number, and charging samples are excluded so "time to full" is not mistaken for runtime.
 */
object BatteryEstimator {

    const val MIN_READINGS = 3
    const val MIN_SPAN_MS = 15 * 60 * 1000L
    const val MAX_READING_GAP_MS = 60 * 60 * 1000L
    const val MAX_HISTORY = 240
    private const val MAX_WINDOW_MS = 24 * 60 * 60 * 1000L

    sealed interface Result {
        /** Firmware reports its own remaining time; always prefer this over any estimate. */
        data class DeviceReported(val minutes: Int) : Result

        data class Estimated(
            val minutes: Int,
            val percentPerHour: Double,
            val quality: ReadingQuality,
            val sampleCount: Int,
        ) : Result

        data class Unavailable(val reason: Reason) : Result
    }

    enum class Reason {
        NO_READINGS,
        BATTERY_EMPTY,
        CHARGING,
        NOT_ENOUGH_READINGS,
        TIME_SPAN_TOO_SHORT,
        NO_DISCHARGE_TREND,
        ERRATIC_READINGS,
    }

    fun estimate(
        readings: List<BatteryReading>,
        current: BatteryReading?,
        deviceReportedMinutes: Int?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result {
        if (deviceReportedMinutes != null && deviceReportedMinutes > 0) {
            return Result.DeviceReported(deviceReportedMinutes)
        }
        if (current == null) return Result.Unavailable(Reason.NO_READINGS)
        if (current.percent <= 0) return Result.Unavailable(Reason.BATTERY_EMPTY)
        if (current.chargingState == ChargingState.CHARGING || current.chargingState == ChargingState.FULL) {
            return Result.Unavailable(Reason.CHARGING)
        }

        val window = readings
            .filter { it.chargingState == ChargingState.DISCHARGING }
            .filter { it.percent > 0 }
            .filter { nowMillis - it.timestampMillis <= MAX_WINDOW_MS }
            .sortedBy { it.timestampMillis }
            .takeLast(MAX_HISTORY)

        if (window.size < MIN_READINGS) return Result.Unavailable(Reason.NOT_ENOUGH_READINGS)

        // Drop samples separated by suspiciously long gaps: the trend across them is meaningless.
        val consistent = mutableListOf<BatteryReading>()
        for (reading in window) {
            val previous = consistent.lastOrNull()
            if (previous == null || reading.timestampMillis - previous.timestampMillis <= MAX_READING_GAP_MS) {
                consistent += reading
            } else {
                consistent.clear()
                consistent += reading
            }
        }
        if (consistent.size < MIN_READINGS) return Result.Unavailable(Reason.NOT_ENOUGH_READINGS)

        val span = consistent.last().timestampMillis - consistent.first().timestampMillis
        if (span < MIN_SPAN_MS) return Result.Unavailable(Reason.TIME_SPAN_TOO_SHORT)

        val baseTime = consistent.first().timestampMillis.toDouble()
        val xs = consistent.map { it.timestampMillis - baseTime }
        val ys = consistent.map { it.percent.toDouble() }
        val n = xs.size.toDouble()
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n

        var numerator = 0.0
        var denominator = 0.0
        for (i in xs.indices) {
            numerator += (xs[i] - meanX) * (ys[i] - meanY)
            denominator += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (denominator == 0.0) return Result.Unavailable(Reason.ERRATIC_READINGS)

        val slopePerMs = numerator / denominator
        if (slopePerMs >= 0.0) return Result.Unavailable(Reason.NO_DISCHARGE_TREND)

        val intercept = meanY - slopePerMs * meanX
        var ssTot = 0.0
        var ssRes = 0.0
        consistent.forEach { reading ->
            val predicted = intercept + slopePerMs * (reading.timestampMillis - baseTime)
            ssTot += (reading.percent - meanY) * (reading.percent - meanY)
            ssRes += (reading.percent - predicted) * (reading.percent - predicted)
        }
        val rSquared = if (ssTot == 0.0) 0.0 else max(0.0, 1.0 - ssRes / ssTot)
        // A weak fit means load swings dominate; do not publish a misleading number.
        if (rSquared < 0.4) return Result.Unavailable(Reason.ERRATIC_READINGS)

        val percentPerHour = abs(slopePerMs) * 3_600_000.0
        if (percentPerHour <= 0.01) return Result.Unavailable(Reason.NO_DISCHARGE_TREND)

        val minutes = (current.percent / percentPerHour * 60.0).toInt()
        if (minutes <= 0) return Result.Unavailable(Reason.NO_DISCHARGE_TREND)

        val quality = if (consistent.size >= 8 && rSquared >= 0.75) ReadingQuality.GOOD else ReadingQuality.LOW
        return Result.Estimated(
            minutes = minutes,
            percentPerHour = percentPerHour,
            quality = quality,
            sampleCount = consistent.size,
        )
    }
}
