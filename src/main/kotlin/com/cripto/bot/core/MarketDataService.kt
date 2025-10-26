package com.cripto.bot.core

interface MarketDataService {
    suspend fun fetchLastPrice(symbol: String): Double
}
