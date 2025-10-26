package com.cripto.bot.strategy

import com.cripto.bot.model.TradeSignal
import java.util.ArrayDeque
import kotlin.math.abs

class MovingAverageStrategy(
    private val shortWindow: Int,
    private val longWindow: Int,
    private val minSpreadRatio: Double = 0.001
) {
    init {
        require(shortWindow < longWindow) { "shortWindow must be smaller than longWindow" }
    }

    private val prices = ArrayDeque<Double>()

    fun onPrice(price: Double): TradeSignal {
        prices.addLast(price)
        if (prices.size > longWindow) {
            prices.removeFirst()
        }

        if (prices.size < longWindow) {
            return TradeSignal.Hold("Collecting data (${prices.size}/$longWindow)")
        }

        val snapshot = prices.toList()
        val longAvg = snapshot.average()
        val shortAvg = snapshot.takeLast(shortWindow).average()
        val spreadRatio = if (longAvg == 0.0) 0.0 else (shortAvg - longAvg) / longAvg

        return when {
            spreadRatio > minSpreadRatio -> TradeSignal.Buy("Short SMA above long SMA by ${(spreadRatio * 100).format(2)}%")
            spreadRatio < -minSpreadRatio -> TradeSignal.Sell("Short SMA below long SMA by ${abs(spreadRatio * 100).format(2)}%")
            else -> TradeSignal.Hold("Spread ratio ${(spreadRatio * 100).format(3)}% within threshold")
        }
    }

    private fun Double.format(decimals: Int): String =
        "%.${decimals}f".format(this)
}
