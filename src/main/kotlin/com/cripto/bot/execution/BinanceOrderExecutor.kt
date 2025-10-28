package com.cripto.bot.execution

import com.cripto.bot.binance.BinanceApiClient
import com.cripto.bot.config.BotConfig
import com.cripto.bot.config.TradeMode
import com.cripto.bot.model.BinanceOrderResponse
import com.cripto.bot.model.NewOrderRequest
import com.cripto.bot.model.OrderSide
import com.cripto.bot.model.TradeSignal
import com.cripto.bot.notification.SaleNotification
import com.cripto.bot.notification.TradeNotifier
import com.cripto.bot.reporting.TradeRecord
import com.cripto.bot.reporting.TradeReporter
import mu.KotlinLogging
import kotlin.math.min
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class BinanceOrderExecutor(
    private val apiClient: BinanceApiClient,
    private val config: BotConfig,
    private val tradeReporter: TradeReporter?,
    private val positionTracker: PositionTracker,
    private val tradeNotifier: TradeNotifier?
) : OrderExecutor {
    private val logger = KotlinLogging.logger {}
    private val baseAsset: String
    private val quoteAsset: String
    private val tradeDecisions = TradeDecisions(config, positionTracker)

    init {
        val (base, quote) = splitSymbol(config.symbol)
        baseAsset = base
        quoteAsset = quote
    }

    override suspend fun execute(signal: TradeSignal, lastPrice: Double) {
        when (signal) {
            is TradeSignal.Buy -> handleBuy(signal.reason, lastPrice)
            is TradeSignal.Sell -> handleSell(signal.reason, lastPrice)
            is TradeSignal.Hold -> handleHold(signal.reason, lastPrice)
        }
    }

    private suspend fun handleBuy(reason: String, lastPrice: Double) {
        if (tradeDecisions.hasOpenPosition()) {
            val evaluation = tradeDecisions.evaluateExit(lastPrice, reason)
            if (evaluation != null) {
                val profit = evaluation.profitRatio.toPercent(3)
                logger.info { "Exit triggered on repeated BUY: ${evaluation.reason} (profit=$profit)" }
                submitSell(evaluation, lastPrice)
            } else {
                logger.debug { "Skipping BUY: position already open" }
            }
            return
        }

        val quoteQty = determineQuoteQuantity()
        if (quoteQty <= 0.0) {
            return
        }

        val orderReason = reason.ifBlank { "Momentum crossover" }
        val request = NewOrderRequest(
            symbol = config.symbol,
            side = OrderSide.BUY,
            quoteOrderQty = quoteQty
        )

        executeAndReport(request, OrderSide.BUY, orderReason, lastPrice)
    }

    private suspend fun handleSell(signalReason: String, lastPrice: Double) {
        val evaluation = tradeDecisions.evaluateExit(lastPrice, signalReason)
        if (evaluation == null) {
            val snapshot = positionTracker.snapshot()
            val profit = if (snapshot.averageCost > 0.0 && snapshot.baseQuantity >= config.minQuantity) {
                ((lastPrice - snapshot.averageCost) / snapshot.averageCost).toPercent(4)
            } else {
                "n/a"
            }
            logger.debug {
                "Skipping SELL: exit conditions not met (signalReason=$signalReason, profit=$profit)"
            }
            return
        }

        submitSell(evaluation, lastPrice)
    }

    private suspend fun handleHold(reason: String, lastPrice: Double) {
        val evaluation = tradeDecisions.evaluateExit(lastPrice, null)
        if (evaluation != null) {
            val profit = evaluation.profitRatio.toPercent(3)
            logger.info { "Stop exit triggered while holding: ${evaluation.reason} (profit=$profit)" }
            submitSell(evaluation, lastPrice)
        } else {
            logger.debug { "HOLD ($reason)" }
        }
    }

    private suspend fun submitSell(evaluation: ExitEvaluation, lastPrice: Double) {
        val normalizedQty = normalizeQuantity(evaluation.snapshot.baseQuantity)
        if (normalizedQty < config.minQuantity) {
            logger.warn {
                "Skipping SELL: normalized quantity $normalizedQty below minimum ${config.minQuantity}"
            }
            return
        }

        val availableBalance = normalizeQuantity(apiClient.getAssetBalance(baseAsset))
        val sellQty = min(normalizedQty, availableBalance)
        if (sellQty < config.minQuantity) {
            logger.warn {
                "Skipping SELL: available $baseAsset balance $availableBalance below minimum ${config.minQuantity}"
            }
            return
        }

        val request = NewOrderRequest(
            symbol = config.symbol,
            side = OrderSide.SELL,
            quantity = sellQty
        )

        executeAndReport(request, OrderSide.SELL, evaluation.reason, lastPrice)
    }

    private suspend fun executeAndReport(
        request: NewOrderRequest,
        side: OrderSide,
        reason: String,
        lastPrice: Double
    ) {
        val response = apiClient.placeOrder(request)
        val stats = response.executionStats(lastPrice)
        logger.info {
            "Executed $side on ${response.symbol} at approx $$lastPrice " +
                "(orderId=${response.orderId}, reason=$reason, status=${response.status})"
        }
        val quoteChange = stats.toQuoteChange(side, request.quoteOrderQty ?: config.quoteOrderQuantity)
        val realized = positionTracker.register(side, stats.executedQty, stats.avgPrice)
        tradeReporter?.record(response.toTradeRecord(side, reason, stats, quoteChange, realized))
        if (side == OrderSide.SELL) {
            tradeNotifier?.notifySale(
                SaleNotification(
                    symbol = response.symbol,
                    quantity = stats.executedQty,
                    averagePrice = stats.avgPrice,
                    realizedProfit = realized,
                    quoteChange = quoteChange,
                    reason = reason,
                    timestampMillis = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun liquidateExistingPosition() {
        val balance = apiClient.getAssetBalance(baseAsset)
        val normalizedQty = normalizeQuantity(balance)
        if (normalizedQty < config.minQuantity) {
            logger.info { "No leftover $baseAsset position to liquidate on startup (have=$balance)" }
            return
        }
        val fallbackPrice = apiClient.getTickerPrice(config.symbol)
        logger.warn { "Liquidating leftover $baseAsset position of $normalizedQty before starting trading loop." }
        val request = NewOrderRequest(
            symbol = config.symbol,
            side = OrderSide.SELL,
            quantity = normalizedQty
        )
        executeAndReport(request, OrderSide.SELL, "Startup liquidation", fallbackPrice)
    }

    private suspend fun determineQuoteQuantity(): Double {
        val budget = if (config.useFullBalance && config.tradeMode == TradeMode.LIVE) {
            apiClient.getAssetBalance(quoteAsset)
        } else {
            config.quoteOrderQuantity
        }

        if (budget < config.minNotional) {
            logger.debug {
                "Skipping BUY: available quote $budget below minNotional ${config.minNotional}"
            }
            return 0.0
        }

        return budget
    }

    private fun BinanceOrderResponse.toTradeRecord(
        side: OrderSide,
        reason: String,
        stats: ExecutionStats,
        quoteChange: Double,
        realizedPnl: Double
    ): TradeRecord {
        return TradeRecord(
            timestamp = Instant.ofEpochMilli(transactTime),
            symbol = symbol,
            side = side,
            avgPrice = stats.avgPrice,
            executedQty = stats.executedQty,
            quoteChange = quoteChange,
            realizedPnl = realizedPnl,
            reason = reason,
            status = status
        )
    }

    private fun ExecutionStats.toQuoteChange(side: OrderSide, fallbackQuote: Double): Double =
        when (side) {
            OrderSide.BUY -> -(executedQuote.takeIf { it > 0 } ?: fallbackQuote)
            OrderSide.SELL -> executedQuote.takeIf { it > 0 } ?: fallbackQuote
        }

    private fun BinanceOrderResponse.executionStats(fallbackPrice: Double): ExecutionStats {
        val (qty, quote) = fills.fold(0.0 to 0.0) { acc, fill ->
            val fillQty = fill.qty.toDoubleOrNull() ?: 0.0
            val fillPrice = fill.price.toDoubleOrNull() ?: fallbackPrice
            acc.first + fillQty to acc.second + (fillPrice * fillQty)
        }
        val avgPrice = when {
            qty > 0.0 -> quote / qty
            else -> fallbackPrice
        }
        return ExecutionStats(
            executedQty = qty,
            executedQuote = quote,
            avgPrice = avgPrice
        )
    }

    private data class ExecutionStats(
        val executedQty: Double,
        val executedQuote: Double,
        val avgPrice: Double
    )

    private fun splitSymbol(symbol: String): Pair<String, String> {
        val knownQuotes = listOf(
            "USDT", "BUSD", "FDUSD", "TUSD", "USDC",
            "BIDR", "BRL", "TRY", "EUR", "GBP",
            "BTC", "ETH", "BNB", "DAI"
        )
        val upper = symbol.uppercase()
        val quote = knownQuotes.firstOrNull { upper.endsWith(it) }
            ?: error("Unable to resolve quote asset for symbol $symbol")
        val base = upper.removeSuffix(quote)
        return base to quote
    }

    private fun normalizeQuantity(quantity: Double): Double {
        if (quantity <= 0.0) return 0.0
        val scaled = BigDecimal.valueOf(quantity)
            .setScale(config.quantityPrecision, RoundingMode.DOWN)
            .stripTrailingZeros()
        return scaled.toDouble()
    }
}
