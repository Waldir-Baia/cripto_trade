package com.cripto.bot.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BotConfig(
    @SerialName("apiKey")
    val apiKey: String,
    @SerialName("apiSecret")
    val apiSecret: String,
    @SerialName("baseUrl")
    val baseUrl: String = "https://testnet.binance.vision",
    @SerialName("symbol")
    val symbol: String,
    @SerialName("quoteOrderQuantity")
    val quoteOrderQuantity: Double = 10.0,
    @SerialName("shortWindow")
    val shortWindow: Int = 7,
    @SerialName("longWindow")
    val longWindow: Int = 25,
    @SerialName("pollingIntervalMs")
    val pollingIntervalMs: Long = 5_000,
    @SerialName("minSpreadRatio")
    val minSpreadRatio: Double = 0.0005,
    @SerialName("quantityPrecision")
    val quantityPrecision: Int = 6,
    @SerialName("minQuantity")
    val minQuantity: Double = 0.000001,
    @SerialName("minNotional")
    val minNotional: Double = 10.0,
    @SerialName("useFullBalance")
    val useFullBalance: Boolean = false,
    @SerialName("minProfitRatio")
    val minProfitRatio: Double = 0.0,
    @SerialName("tradeMode")
    val tradeMode: TradeMode = TradeMode.PAPER,
    @SerialName("reportsDir")
    val reportsDir: String = "reports"
)

enum class TradeMode {
    PAPER,
    LIVE
}
