package uk.co.riera.talkbalance.session

import kotlin.math.roundToInt

/** The report's "balanced would be about X% each" guidance, resolved for the session [Mode]. */
data class BalanceTarget(
    /** Even-split percentage per category. */
    val perCategoryPercent: Int,
    /** Ready-to-show copy for the report. */
    val copy: String,
)

/**
 * Pure computation of the report's balance target.
 *
 * - **Me mode** has a single enrolled identity but the report is *You vs Others* — two categories,
 *   so a fair split is 50/50 (dividing 100 by the one enrolled person, as the old code did, wrongly
 *   claimed "100% each").
 * - **Table mode** compares the [enrolledCount] enrolled people, so a fair split is `100 / N` each.
 */
fun balanceTarget(mode: Mode, enrolledCount: Int): BalanceTarget = when (mode) {
    Mode.ME -> BalanceTarget(
        perCategoryPercent = 50,
        copy = "Balanced would be about 50% each — you vs everyone else.",
    )
    // Table (enrolled people) and Discover (discovered speakers) both compare N categories, so a
    // fair split is 100 / N each.
    Mode.TABLE, Mode.DISCOVER -> {
        val n = enrolledCount.coerceAtLeast(1)
        val pct = (100.0 / n).roundToInt()
        BalanceTarget(
            perCategoryPercent = pct,
            copy = "Balanced would be about $pct% each.",
        )
    }
}
