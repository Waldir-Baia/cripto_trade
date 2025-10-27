package com.cripto.bot.notification

data class SaleNotification(
    val symbol: String,
    val quantity: Double,
    val averagePrice: Double,
    val realizedProfit: Double,
    val quoteChange: Double,
    val reason: String,
    val timestampMillis: Long
)

interface TradeNotifier {
    suspend fun notifySale(notification: SaleNotification)
}
