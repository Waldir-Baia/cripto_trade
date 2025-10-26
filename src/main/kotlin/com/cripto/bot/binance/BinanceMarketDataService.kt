package com.cripto.bot.binance

import com.cripto.bot.core.MarketDataService

class BinanceMarketDataService(
    private val apiClient: BinanceApiClient
) : MarketDataService {
    override suspend fun fetchLastPrice(symbol: String): Double =
        apiClient.getTickerPrice(symbol)
}
