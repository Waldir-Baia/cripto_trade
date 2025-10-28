package com.cripto.bot.core

import com.cripto.bot.config.BotConfig
import com.cripto.bot.execution.OrderExecutor
import com.cripto.bot.model.TradeSignal
import com.cripto.bot.strategy.MovingAverageStrategy
import kotlinx.coroutines.delay
import mu.KotlinLogging

class TradingBot(
    private val config: BotConfig,
    private val strategy: MovingAverageStrategy,
    private val marketDataService: MarketDataService,
    private val orderExecutor: OrderExecutor
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run() {
        logger.info { "Trading bot started on ${config.symbol} (${config.tradeMode})" }
        while (true) {
            try {
                val lastPrice = marketDataService.fetchLastPrice(config.symbol)
                val signal = strategy.onPrice(lastPrice)
                orderExecutor.execute(signal, lastPrice)
            } catch (ex: Exception) {
                logger.error(ex) { "Loop error" }
            }
            delay(config.pollingIntervalMs)
        }
    }
}
