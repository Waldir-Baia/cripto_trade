package com.cripto.bot.execution

import com.cripto.bot.config.BotConfig
import com.cripto.bot.model.OrderSide
import com.cripto.bot.model.TradeSignal
import com.cripto.bot.notification.SaleNotification
import com.cripto.bot.notification.TradeNotifier
import com.cripto.bot.reporting.TradeRecord
import com.cripto.bot.reporting.TradeReporter
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.min

class PaperOrderExecutor(
    private val config: BotConfig,
    private val positionTracker: PositionTracker,
    private val tradeReporter: TradeReporter?,
    private val tradeNotifier: TradeNotifier?
) : OrderExecutor {

    private val logger = KotlinLogging.logger {}
    private val tradeDecisions = TradeDecisions(config, positionTracker)

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
                logger.info {
                    "[PAPER] Exit triggered on repeated BUY: ${evaluation.reason} (profit=$profit)"
                }
                simulateSell(evaluation, lastPrice)
            } else {
                logger.debug { "[PAPER] Skipping BUY: position already open" }
            }
            return
        }

        val quoteQty = config.quoteOrderQuantity
        if (quoteQty < config.minNotional) {
            logger.debug {
                "[PAPER] Skipping BUY: quote amount $quoteQty below minNotional ${config.minNotional}"
            }
            return
        }

        val baseQty = normalizeQuantity(quoteQty / lastPrice)
        if (baseQty < config.minQuantity) {
            logger.debug {
                "[PAPER] Skipping BUY: quantity $baseQty below minimum ${config.minQuantity}"
            }
            return
        }

        val orderReason = reason.ifBlank { "Momentum crossover" }
        positionTracker.register(OrderSide.BUY, baseQty, lastPrice)
        recordTrade(
            side = OrderSide.BUY,
            avgPrice = lastPrice,
            executedQty = baseQty,
            quoteChange = -quoteQty,
            realized = 0.0,
            reason = orderReason,
            status = "FILLED"
        )
        logger.info { "[PAPER] BUY $baseQty ${config.symbol} @ $lastPrice ($orderReason)" }
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
                "[PAPER] Skipping SELL: exit conditions not met (signalReason=$signalReason, profit=$profit)"
            }
            return
        }

        simulateSell(evaluation, lastPrice)
    }

    private suspend fun handleHold(reason: String, lastPrice: Double) {
        val evaluation = tradeDecisions.evaluateExit(lastPrice, null)
        if (evaluation != null) {
            val profit = evaluation.profitRatio.toPercent(3)
            logger.info { "[PAPER] Stop exit triggered while holding: ${evaluation.reason} (profit=$profit)" }
            simulateSell(evaluation, lastPrice)
        } else {
            logger.debug { "[PAPER] HOLD ($reason)" }
        }
    }

    private suspend fun simulateSell(evaluation: ExitEvaluation, lastPrice: Double) {
        val normalizedQty = normalizeQuantity(evaluation.snapshot.baseQuantity)
        if (normalizedQty < config.minQuantity) {
            logger.warn {
                "[PAPER] Skipping SELL: normalized quantity $normalizedQty below minimum ${config.minQuantity}"
            }
            return
        }

        val execQty = min(normalizedQty, evaluation.snapshot.baseQuantity)
        val quoteChange = execQty * lastPrice
        val realized = positionTracker.register(OrderSide.SELL, execQty, lastPrice)
        recordTrade(
            side = OrderSide.SELL,
            avgPrice = lastPrice,
            executedQty = execQty,
            quoteChange = quoteChange,
            realized = realized,
            reason = evaluation.reason,
            status = "FILLED"
        )
        tradeNotifier?.notifySale(
            SaleNotification(
                symbol = config.symbol,
                quantity = execQty,
                averagePrice = lastPrice,
                realizedProfit = realized,
                quoteChange = quoteChange,
                reason = evaluation.reason,
                timestampMillis = System.currentTimeMillis()
            )
        )
        logger.info {
            "[PAPER] SELL $execQty ${config.symbol} @ $lastPrice (reason=${evaluation.reason}, realized=${"%.5f".format(realized)})"
        }
    }


    private fun recordTrade(
        side: OrderSide,
        avgPrice: Double,
        executedQty: Double,
        quoteChange: Double,
        realized: Double,
        reason: String,
        status: String
    ) {
        tradeReporter?.record(
            TradeRecord(
                timestamp = Instant.now(),
                symbol = config.symbol,
                side = side,
                avgPrice = avgPrice,
                executedQty = executedQty,
                quoteChange = quoteChange,
                realizedPnl = realized,
                reason = reason,
                status = status
            )
        )
    }

    private fun normalizeQuantity(quantity: Double): Double {
        if (quantity <= 0.0) return 0.0
        val scaled = BigDecimal.valueOf(quantity)
            .setScale(config.quantityPrecision, RoundingMode.DOWN)
            .stripTrailingZeros()
        return scaled.toDouble()
    }
}
