package com.cripto.bot.execution

import com.cripto.bot.model.OrderSide
import kotlin.math.min

/**
 * Tracks spot inventory and returns realized PnL (in quote currency) for each execution.
 */
class PositionTracker {
    private var baseQty: Double = 0.0
    private var totalCostInQuote: Double = 0.0

    @Synchronized
    fun register(side: OrderSide, executedQty: Double, avgPrice: Double): Double {
        if (executedQty <= 0.0) return 0.0

        return when (side) {
            OrderSide.BUY -> {
                baseQty += executedQty
                totalCostInQuote += executedQty * avgPrice
                0.0
            }

            OrderSide.SELL -> {
                val sellQty = min(executedQty, baseQty.takeIf { it > 0.0 } ?: 0.0)
                if (sellQty <= 0.0) {
                    // No inventory to match; treat as zero PnL to avoid misleading numbers.
                    return 0.0
                }

                val avgCost = if (baseQty > 0.0) totalCostInQuote / baseQty else avgPrice
                val realized = (avgPrice - avgCost) * sellQty

                baseQty -= sellQty
                totalCostInQuote -= avgCost * sellQty
                if (baseQty < 1e-8) {
                    baseQty = 0.0
                    totalCostInQuote = 0.0
                } else if (totalCostInQuote < 1e-8) {
                    totalCostInQuote = 0.0
                }

                realized
            }
        }
    }

    @Synchronized
    fun availableBaseQuantity(): Double = if (baseQty < 1e-8) 0.0 else baseQty

    @Synchronized
    fun snapshot(): PositionSnapshot =
        PositionSnapshot(
            baseQuantity = baseQty,
            averageCost = if (baseQty > 0.0) totalCostInQuote / baseQty else 0.0,
            totalCostInQuote = totalCostInQuote
        )
}

data class PositionSnapshot(
    val baseQuantity: Double,
    val averageCost: Double,
    val totalCostInQuote: Double
)
