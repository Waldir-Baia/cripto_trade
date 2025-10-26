package com.cripto.bot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TickerPriceResponse(
    @SerialName("symbol")
    val symbol: String,
    @SerialName("price")
    val price: String
)

@Serializable
data class BinanceOrderResponse(
    @SerialName("symbol")
    val symbol: String,
    @SerialName("orderId")
    val orderId: Long,
    @SerialName("clientOrderId")
    val clientOrderId: String,
    @SerialName("transactTime")
    val transactTime: Long,
    @SerialName("status")
    val status: String,
    @SerialName("side")
    val side: String,
    @SerialName("type")
    val type: String,
    @SerialName("fills")
    val fills: List<Fill> = emptyList()
) {
    @Serializable
    data class Fill(
        @SerialName("price")
        val price: String,
        @SerialName("qty")
        val qty: String,
        @SerialName("commissionAsset")
        val commissionAsset: String? = null,
        @SerialName("commission")
        val commission: String? = null
    )
}

data class NewOrderRequest(
    val symbol: String,
    val side: OrderSide,
    val type: OrderType = OrderType.MARKET,
    val timeInForce: TimeInForce? = null,
    val quantity: Double? = null,
    val quoteOrderQty: Double? = null
)
