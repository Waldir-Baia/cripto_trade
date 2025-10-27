package com.cripto.bot

import com.cripto.bot.binance.BinanceApiClient
import com.cripto.bot.binance.BinanceMarketDataService
import com.cripto.bot.config.BotConfig
import com.cripto.bot.config.BotConfigLoader
import com.cripto.bot.config.TradeMode
import com.cripto.bot.core.TradingBot
import com.cripto.bot.execution.BinanceOrderExecutor
import com.cripto.bot.execution.OrderExecutor
import com.cripto.bot.execution.PaperOrderExecutor
import com.cripto.bot.execution.PositionTracker
import com.cripto.bot.reporting.FileTradeReporter
import com.cripto.bot.reporting.TradeReporter
import com.cripto.bot.strategy.MovingAverageStrategy
import com.cripto.bot.notification.EmailTradeNotifier
import com.cripto.bot.notification.TradeNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Paths

fun main(): Unit = runBlocking {
    val config = BotConfigLoader.load()
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
        install(Logging) {
            logger = io.ktor.client.plugins.logging.Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    val binanceClient = BinanceApiClient(httpClient, config)
    val marketData = BinanceMarketDataService(binanceClient)
    val strategy = MovingAverageStrategy(
        shortWindow = config.shortWindow,
        longWindow = config.longWindow,
        minSpreadRatio = config.minSpreadRatio
    )
    val tradeReporter = when (config.tradeMode) {
        TradeMode.LIVE -> FileTradeReporter(Paths.get(config.reportsDir, "trade-history.csv"))
        TradeMode.PAPER -> null
    }
    val positionTracker = when (config.tradeMode) {
        TradeMode.LIVE -> PositionTracker()
        TradeMode.PAPER -> null
    }
    val tradeNotifier = config.emailConfig
        ?.takeIf { it.enabled }
        ?.let { EmailTradeNotifier(it) }

    val orderExecutor = createOrderExecutor(config, binanceClient, tradeReporter, positionTracker, tradeNotifier)

    if (orderExecutor is BinanceOrderExecutor) {
        orderExecutor.liquidateExistingPosition()
    }

    val bot = TradingBot(
        config = config,
        strategy = strategy,
        marketDataService = marketData,
        orderExecutor = orderExecutor
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        httpClient.close()
    })

    bot.run()
}

private fun createOrderExecutor(
    config: BotConfig,
    binanceClient: BinanceApiClient,
    tradeReporter: TradeReporter?,
    positionTracker: PositionTracker?,
    tradeNotifier: TradeNotifier?
): OrderExecutor =
    when (config.tradeMode) {
        TradeMode.PAPER -> PaperOrderExecutor()
        TradeMode.LIVE -> BinanceOrderExecutor(binanceClient, config, tradeReporter, positionTracker, tradeNotifier)
    }
