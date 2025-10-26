package com.cripto.bot.reporting

import com.cripto.bot.model.OrderSide
import java.time.Instant

data class TradeRecord(
    val timestamp: Instant,
    val symbol: String,
    val side: OrderSide,
    val avgPrice: Double,
    val executedQty: Double,
    val quoteChange: Double,
    val realizedPnl: Double,
    val reason: String,
    val status: String
)

interface TradeReporter {
    fun record(record: TradeRecord)
}
