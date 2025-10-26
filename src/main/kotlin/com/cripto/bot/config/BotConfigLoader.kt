package com.cripto.bot.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json

object BotConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(path: Path = Paths.get("config", "bot-config.json")): BotConfig {
        val env = readFromEnv()
        val fromFile = readFromFile(path)

        val baseConfig = fromFile ?: env
            ?: error("No configuration found. Provide BINANCE_API_KEY/BINANCE_API_SECRET env vars or config/bot-config.json")

        return overrideWithEnv(baseConfig)
    }

    private fun readFromFile(path: Path): BotConfig? =
        runCatching {
            Files.readString(path)
        }.getOrNull()?.let { json.decodeFromString(BotConfig.serializer(), it) }

    private fun readFromEnv(): BotConfig? {
        val apiKey = System.getenv("BINANCE_API_KEY")?.takeIf { it.isNotBlank() }
        val apiSecret = System.getenv("BINANCE_API_SECRET")?.takeIf { it.isNotBlank() }
        val symbol = System.getenv("BOT_SYMBOL")?.takeIf { it.isNotBlank() }

        if (apiKey == null || apiSecret == null || symbol == null) return null

        val baseUrl = System.getenv("BINANCE_BASE_URL") ?: "https://testnet.binance.vision"
        val quoteQty = System.getenv("BOT_QUOTE_QTY")?.toDoubleOrNull() ?: 10.0
        val shortWindow = System.getenv("BOT_SHORT_WINDOW")?.toIntOrNull() ?: 7
        val longWindow = System.getenv("BOT_LONG_WINDOW")?.toIntOrNull() ?: 25
        val pollingMs = System.getenv("BOT_POLLING_MS")?.toLongOrNull() ?: 5_000L
        val tradeMode = System.getenv("BOT_TRADE_MODE")?.let { TradeMode.valueOf(it.uppercase()) } ?: TradeMode.PAPER
        val minSpread = System.getenv("BOT_MIN_SPREAD")?.toDoubleOrNull() ?: 0.0005
        val quantityPrecision = System.getenv("BOT_QUANTITY_PRECISION")?.toIntOrNull() ?: 6
        val minQuantity = System.getenv("BOT_MIN_QUANTITY")?.toDoubleOrNull() ?: 0.000001
        val minNotional = System.getenv("BOT_MIN_NOTIONAL")?.toDoubleOrNull() ?: 10.0
        val reportsDir = System.getenv("BOT_REPORTS_DIR") ?: "reports"
        val useFullBalance = System.getenv("BOT_USE_FULL_BALANCE")?.toBooleanStrictOrNull() ?: false
        val minProfitRatio = System.getenv("BOT_MIN_PROFIT_RATIO")?.toDoubleOrNull() ?: 0.0

        return BotConfig(
            apiKey = apiKey,
            apiSecret = apiSecret,
            baseUrl = baseUrl,
            symbol = symbol.uppercase(),
            quoteOrderQuantity = quoteQty,
            shortWindow = shortWindow,
            longWindow = longWindow,
            pollingIntervalMs = pollingMs,
            minSpreadRatio = minSpread,
            quantityPrecision = quantityPrecision,
            minQuantity = minQuantity,
            minNotional = minNotional,
            useFullBalance = useFullBalance,
            minProfitRatio = minProfitRatio,
            tradeMode = tradeMode,
            reportsDir = reportsDir
        )
    }

    private fun overrideWithEnv(config: BotConfig): BotConfig {
        fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

        return config.copy(
            apiKey = env("BINANCE_API_KEY") ?: config.apiKey,
            apiSecret = env("BINANCE_API_SECRET") ?: config.apiSecret,
            baseUrl = env("BINANCE_BASE_URL") ?: config.baseUrl,
            symbol = env("BOT_SYMBOL")?.uppercase() ?: config.symbol,
            quoteOrderQuantity = env("BOT_QUOTE_QTY")?.toDoubleOrNull() ?: config.quoteOrderQuantity,
            shortWindow = env("BOT_SHORT_WINDOW")?.toIntOrNull() ?: config.shortWindow,
            longWindow = env("BOT_LONG_WINDOW")?.toIntOrNull() ?: config.longWindow,
            pollingIntervalMs = env("BOT_POLLING_MS")?.toLongOrNull() ?: config.pollingIntervalMs,
            minSpreadRatio = env("BOT_MIN_SPREAD")?.toDoubleOrNull() ?: config.minSpreadRatio,
            quantityPrecision = env("BOT_QUANTITY_PRECISION")?.toIntOrNull() ?: config.quantityPrecision,
            minQuantity = env("BOT_MIN_QUANTITY")?.toDoubleOrNull() ?: config.minQuantity,
            minNotional = env("BOT_MIN_NOTIONAL")?.toDoubleOrNull() ?: config.minNotional,
            useFullBalance = env("BOT_USE_FULL_BALANCE")?.toBooleanStrictOrNull() ?: config.useFullBalance,
            minProfitRatio = env("BOT_MIN_PROFIT_RATIO")?.toDoubleOrNull() ?: config.minProfitRatio,
            tradeMode = env("BOT_TRADE_MODE")?.let { TradeMode.valueOf(it.uppercase()) } ?: config.tradeMode,
            reportsDir = env("BOT_REPORTS_DIR") ?: config.reportsDir
        )
    }
}
