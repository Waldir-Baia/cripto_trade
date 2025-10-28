package com.cripto.bot.execution

import com.cripto.bot.binance.BinanceApiClient
import com.cripto.bot.config.BotConfig
import com.cripto.bot.model.BinanceOrderResponse
import com.cripto.bot.model.NewOrderRequest
import com.cripto.bot.model.OrderSide
import com.cripto.bot.model.TradeSignal
import com.cripto.bot.reporting.TradeRecord
import com.cripto.bot.reporting.TradeReporter
import com.cripto.bot.config.TradeMode
import com.cripto.bot.notification.TradeNotifier
import com.cripto.bot.notification.SaleNotification
import mu.KotlinLogging
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode

class BinanceOrderExecutor(
    private val apiClient: BinanceApiClient,
    private val config: BotConfig,
    private val tradeReporter: TradeReporter?,
    private val positionTracker: PositionTracker?,
    private val tradeNotifier: TradeNotifier?
) : OrderExecutor {
    private val logger = KotlinLogging.logger {}
    private val baseAsset: String
    private val quoteAsset: String

    init {
        val (base, quote) = splitSymbol(config.symbol)
        baseAsset = base
        quoteAsset = quote
    }

    override suspend fun execute(signal: TradeSignal, lastPrice: Double) {
        when (signal) {
            is TradeSignal.Buy -> placeBuy(signal.reason, lastPrice)
            is TradeSignal.Sell -> placeSell(signal.reason, lastPrice)
            is TradeSignal.Hold -> {
                val slReason = stopLossTriggered(lastPrice)
                if (slReason != null) {
                    placeSell(slReason, lastPrice)
                } else {
                    logger.debug { "HOLD signal (${signal.reason})" }
                }
            }
        }
    }

    private suspend fun placeBuy(reason: String, lastPrice: Double) {
        val quoteQty = determineQuoteQuantity()
        if (quoteQty <= 0.0) {
            return
        }

        val request = NewOrderRequest(
            symbol = config.symbol,
            side = OrderSide.BUY,
            quoteOrderQty = quoteQty
        )

        executeAndReport(request, OrderSide.BUY, reason, lastPrice)
    }

    private suspend fun placeSell(reason: String, lastPrice: Double) {
        val tracker = positionTracker
        if (tracker == null) {
            logger.warn { "SELL requested but position tracker unavailable" }
            return
        }

        val snapshot = tracker.snapshot()
        if (snapshot.baseQuantity < config.minQuantity) {
            logger.warn { "Skipping SELL: no base asset available (have=${snapshot.baseQuantity})" }
            return
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
        val stopLossReason = stopLossTriggered(lastPrice)
        val finalReason = when {
            profitRatio >= targetRatio -> reason
            stopLossReason != null -> stopLossReason
            else -> null
        }
        if (finalReason == null) {
            logger.debug {
                "Skipping SELL: profitRatio=${"%.5f".format(profitRatio)} below target ${"%.5f".format(targetRatio)} and stop-loss not triggered"
            }
            return
        }

        val normalizedQty = normalizeQuantity(snapshot.baseQuantity)
        if (normalizedQty < config.minQuantity) {
            logger.warn { "Skipping SELL: normalized quantity $normalizedQty below minimum ${config.minQuantity}" }
            return
        }

        val request = NewOrderRequest(
            symbol = config.symbol,
            side = OrderSide.SELL,
            quantity = normalizedQty
        )

        executeAndReport(request, OrderSide.SELL, finalReason, lastPrice)
    }

    private fun stopLossTriggered(lastPrice: Double): String? {
        val tracker = positionTracker ?: return null
        val sl = config.stopLossRatio
        if (sl <= 0.0) return null
        val snapshot = tracker.snapshot()
        if (snapshot.baseQuantity < config.minQuantity) return null
        if (snapshot.averageCost <= 0.0) return null
        val ratio = (lastPrice - snapshot.averageCost) / snapshot.averageCost
        return if (ratio <= -sl) {
            val dropPct = (-ratio * 100.0).format(2)
            val slPct = (sl * 100.0).format(2)
            "Stop-loss triggered: drop ${dropPct}% ≥ ${slPct}%"
        } else null
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
        val realized = positionTracker?.register(side, stats.executedQty, stats.avgPrice) ?: 0.0
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
        positionTracker?.let {
            val held = it.availableBaseQuantity()
            if (held >= config.minQuantity) {
                logger.debug { "Skipping BUY: position already open (base=$held)" }
                return 0.0
            }
        }

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

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
