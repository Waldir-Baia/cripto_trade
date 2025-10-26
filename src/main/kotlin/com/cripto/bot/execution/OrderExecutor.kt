package com.cripto.bot.execution

import com.cripto.bot.model.TradeSignal

interface OrderExecutor {
    suspend fun execute(signal: TradeSignal, lastPrice: Double)
}
