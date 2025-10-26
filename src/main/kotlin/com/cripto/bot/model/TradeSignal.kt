package com.cripto.bot.model

sealed interface TradeSignal {
    data class Buy(val reason: String) : TradeSignal
    data class Sell(val reason: String) : TradeSignal
    data class Hold(val reason: String = "Waiting") : TradeSignal
}

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderType {
    MARKET,
    LIMIT
}

enum class TimeInForce {
    GTC,
    IOC,
    FOK
}
