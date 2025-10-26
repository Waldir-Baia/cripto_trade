package com.cripto.bot.execution

import com.cripto.bot.model.TradeSignal
import mu.KotlinLogging

class PaperOrderExecutor : OrderExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun execute(signal: TradeSignal, lastPrice: Double) {
        when (signal) {
            is TradeSignal.Buy -> logger.info { "[PAPER] BUY signal at $lastPrice (${signal.reason})" }
            is TradeSignal.Sell -> logger.info { "[PAPER] SELL signal at $lastPrice (${signal.reason})" }
            is TradeSignal.Hold -> logger.debug { "[PAPER] HOLD (${signal.reason})" }
        }
    }
}
