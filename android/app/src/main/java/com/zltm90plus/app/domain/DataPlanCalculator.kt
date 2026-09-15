package com.zltm90plus.app.domain

import com.zltm90plus.app.data.model.DataPlanStatus
import java.util.concurrent.TimeUnit

/** Pure helpers for data-plan maths. Kept separate so it is trivially unit-testable. */
object DataPlanCalculator {

    data class Summary(
        val usedFraction: Double,
        val usedPercentRounded: Int,
        val remainingBytes: Long,
        val daysRemaining: Long?,
        val isExpired: Boolean,
    )

    /** Returns null when total or used is unknown; never invents a ratio. */
    fun summarize(plan: DataPlanStatus, nowMillis: Long = System.currentTimeMillis()): Summary? {
        val fraction = plan.usedFraction ?: return null
        val remaining = plan.remainingBytes ?: return null
        val renewal = plan.renewalMillis
        val daysRemaining = renewal?.let { TimeUnit.MILLISECONDS.toDays(it - nowMillis) }
        return Summary(
            usedFraction = fraction,
            usedPercentRounded = Math.round(fraction * 100.0).toInt(),
            remainingBytes = remaining,
            daysRemaining = daysRemaining,
            isExpired = renewal != null && renewal < nowMillis,
        )
    }

    /** Bytes per day needed to stay inside the plan until renewal. Null when unknowable. */
    fun requiredDailyBudgetBytes(plan: DataPlanStatus, nowMillis: Long = System.currentTimeMillis()): Long? {
        val remaining = plan.remainingBytes ?: return null
        val renewal = plan.renewalMillis ?: return null
        val millisLeft = renewal - nowMillis
        if (millisLeft <= 0) return null
        val daysLeft = TimeUnit.MILLISECONDS.toDays(millisLeft).coerceAtLeast(1)
        return remaining / daysLeft
    }
}
