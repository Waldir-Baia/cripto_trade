package com.cripto.bot.execution

import com.cripto.bot.config.BotConfig

class TradeDecisions(
    private val config: BotConfig,
    private val tracker: PositionTracker
) {

    fun hasOpenPosition(): Boolean = tracker.availableBaseQuantity() > config.minQuantity

    fun evaluateExit(lastPrice: Double, signalReason: String?): ExitEvaluation? {
        val snapshot = tracker.snapshot()
        if (snapshot.baseQuantity <= config.minQuantity) {
            return null
        }

        val profitRatio = if (snapshot.averageCost > 0.0) {
            (lastPrice - snapshot.averageCost) / snapshot.averageCost
        } else {
            0.0
        }

        val targetRatio = when {
            config.minProfitRatio > 0.0 -> config.minProfitRatio
            config.minProfitRatio == 0.0 -> 1e-9
            else -> config.minProfitRatio
        }

        if (profitRatio >= targetRatio) {
            val reason = profitReason(signalReason, profitRatio)
            return ExitEvaluation(ExitType.PROFIT_TARGET, reason, snapshot, profitRatio)
        }

        val stopLoss = config.stopLossRatio
        if (stopLoss > 0.0 && profitRatio <= -stopLoss) {
            val reason = stopLossReason(stopLoss, profitRatio)
            return ExitEvaluation(ExitType.STOP_LOSS, reason, snapshot, profitRatio)
        }

        return null
    }

    private fun profitReason(signalReason: String?, profitRatio: Double): String {
        val profitPct = profitRatio.toPercent(2)
        return when {
            signalReason.isNullOrBlank() -> "Profit target reached: +$profitPct"
            else -> "$signalReason | Profit target reached: +$profitPct"
        }
    }

    private fun stopLossReason(stopLossRatio: Double, profitRatio: Double): String {
        val dropPct = (-profitRatio).toPercent(2)
        val stopPct = stopLossRatio.toPercent(2)
        return "Stop-loss triggered: drop $dropPct >= $stopPct"
    }
}

data class ExitEvaluation(
    val type: ExitType,
    val reason: String,
    val snapshot: PositionSnapshot,
    val profitRatio: Double
)

enum class ExitType {
    PROFIT_TARGET,
    STOP_LOSS
}

internal fun Double.toPercent(decimals: Int): String {
    val pattern = "%.${decimals}f%%"
    return pattern.format(this * 100.0)
}
