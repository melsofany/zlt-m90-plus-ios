package com.zltm90plus.app

import com.zltm90plus.app.data.model.BatteryReading
import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.domain.BatteryEstimator
import com.zltm90plus.app.domain.DataPlanCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEstimatorTest {

    private val baseTime = 1_700_000_000_000L

    private fun reading(minutesFromBase: Long, percent: Int, state: ChargingState = ChargingState.DISCHARGING) =
        BatteryReading(baseTime + minutesFromBase * 60_000L, percent, state)

    @Test
    fun `device reported time always wins over estimate`() {
        val readings = listOf(reading(0, 90), reading(20, 85), reading(40, 80))
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = reading(40, 80),
            deviceReportedMinutes = 215,
            nowMillis = baseTime + 40 * 60_000L,
        )
        assertTrue(result is BatteryEstimator.Result.DeviceReported)
        assertEquals(215, (result as BatteryEstimator.Result.DeviceReported).minutes)
    }

    @Test
    fun `single reading cannot produce an estimate`() {
        val result = BatteryEstimator.estimate(
            readings = listOf(reading(0, 80)),
            current = reading(0, 80),
            deviceReportedMinutes = null,
            nowMillis = baseTime,
        )
        assertEquals(
            BatteryEstimator.Result.Unavailable(BatteryEstimator.Reason.NOT_ENOUGH_READINGS),
            result,
        )
    }

    @Test
    fun `estimator refuses while charging`() {
        val readings = listOf(reading(0, 50), reading(20, 60), reading(40, 70), reading(60, 80))
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = reading(60, 80, ChargingState.CHARGING),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 60 * 60_000L,
        )
        assertEquals(
            BatteryEstimator.Result.Unavailable(BatteryEstimator.Reason.CHARGING),
            result,
        )
    }

    @Test
    fun `zero percent reports empty battery instead of a number`() {
        val result = BatteryEstimator.estimate(
            readings = listOf(reading(0, 5), reading(20, 3), reading(40, 1)),
            current = reading(40, 0),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 40 * 60_000L,
        )
        assertEquals(
            BatteryEstimator.Result.Unavailable(BatteryEstimator.Reason.BATTERY_EMPTY),
            result,
        )
    }

    @Test
    fun `steady discharge produces a plausible estimate`() {
        val readings = (0..6).map { step -> reading(step * 20L, 90 - step * 5) }
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = readings.last(),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 120 * 60_000L,
        )
        assertTrue(result is BatteryEstimator.Result.Estimated)
        val estimated = result as BatteryEstimator.Result.Estimated
        // 5% per 20 minutes => 15%/hour; 60% left => 4 hours.
        assertEquals(15.0, estimated.percentPerHour, 0.5)
        assertEquals(240, estimated.minutes)
    }

    @Test
    fun `erratic readings refuse to publish a number`() {
        val readings = listOf(
            reading(0, 90),
            reading(10, 40),
            reading(20, 88),
            reading(30, 35),
            reading(40, 86),
        )
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = readings.last(),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 40 * 60_000L,
        )
        assertTrue(result is BatteryEstimator.Result.Unavailable)
    }

    @Test
    fun `flat readings are not a discharge trend`() {
        val readings = (0..4).map { step -> reading(step * 20L, 70) }
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = readings.last(),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 80 * 60_000L,
        )
        assertEquals(
            BatteryEstimator.Result.Unavailable(BatteryEstimator.Reason.NO_DISCHARGE_TREND),
            result,
        )
    }

    @Test
    fun `short time span is rejected even with many readings`() {
        val readings = (0..4).map { step -> reading(step * 2L, 90 - step * 3) }
        val result = BatteryEstimator.estimate(
            readings = readings,
            current = readings.last(),
            deviceReportedMinutes = null,
            nowMillis = baseTime + 8 * 60_000L,
        )
        assertEquals(
            BatteryEstimator.Result.Unavailable(BatteryEstimator.Reason.TIME_SPAN_TOO_SHORT),
            result,
        )
    }
}

class DataPlanCalculatorTest {

    @Test
    fun `summarize computes fraction and remaining`() {
        val now = 1_700_000_000_000L
        val plan = DataPlanStatus(
            totalBytes = 50_000_000_000L,
            usedBytes = 21_000_000_000L,
            renewalMillis = now + 10L * 24 * 60 * 60 * 1000,
            source = DataSource.MANUAL,
        )
        val summary = DataPlanCalculator.summarize(plan, now)!!
        assertEquals(42, summary.usedPercentRounded)
        assertEquals(29_000_000_000L, summary.remainingBytes)
        assertEquals(10L, summary.daysRemaining)
        assertTrue(!summary.isExpired)
    }

    @Test
    fun `summarize returns null when used is unknown`() {
        val plan = DataPlanStatus(totalBytes = 50_000_000_000L, usedBytes = null)
        assertNull(DataPlanCalculator.summarize(plan))
    }

    @Test
    fun `zero total is not treated as fully used`() {
        val plan = DataPlanStatus(totalBytes = 0L, usedBytes = 10L)
        assertNull(DataPlanCalculator.summarize(plan))
    }

    @Test
    fun `usage above total clamps to one hundred percent`() {
        val plan = DataPlanStatus(totalBytes = 1_000L, usedBytes = 5_000L)
        val summary = DataPlanCalculator.summarize(plan)!!
        assertEquals(100, summary.usedPercentRounded)
        assertEquals(0L, summary.remainingBytes)
    }

    @Test
    fun `required daily budget divides remaining by days left`() {
        val now = 1_700_000_000_000L
        val plan = DataPlanStatus(
            totalBytes = 10_000_000_000L,
            usedBytes = 1_000_000_000L,
            renewalMillis = now + 9L * 24 * 60 * 60 * 1000,
        )
        val budget = DataPlanCalculator.requiredDailyBudgetBytes(plan, now)!!
        assertEquals(1_000_000_000L, budget)
    }

    @Test
    fun `expired plan reports no budget`() {
        val now = 1_700_000_000_000L
        val plan = DataPlanStatus(
            totalBytes = 10_000_000_000L,
            usedBytes = 1_000_000_000L,
            renewalMillis = now - 1_000L,
        )
        assertNull(DataPlanCalculator.requiredDailyBudgetBytes(plan, now))
    }
}

class ModelDefaultsTest {

    @Test
    fun `battery without percent is unavailable not zero`() {
        val battery = BatteryStatus()
        assertTrue(!battery.isAvailable)
        assertNull(battery.percent)
    }

    @Test
    fun `connected devices with unavailable source report null count`() {
        val devices = com.zltm90plus.app.data.model.ConnectedDevices(source = DataSource.UNAVAILABLE)
        assertNull(devices.count)
    }

    @Test
    fun `connected devices with empty router list report zero`() {
        val devices = com.zltm90plus.app.data.model.ConnectedDevices(
            devices = emptyList(),
            source = DataSource.ROUTER,
        )
        assertEquals(0, devices.count)
    }

    @Test
    fun `device stable id prefers mac then ip then hostname`() {
        assertEquals(
            "AA:BB",
            com.zltm90plus.app.data.model.ConnectedDevice(
                hostname = "phone",
                macAddress = "AA:BB",
                ipAddress = "192.168.0.5",
            ).stableId,
        )
        assertEquals(
            "192.168.0.5",
            com.zltm90plus.app.data.model.ConnectedDevice(ipAddress = "192.168.0.5").stableId,
        )
    }
}