package com.cripto.bot.binance

import com.cripto.bot.config.BotConfig
import com.cripto.bot.model.AccountInformation
import com.cripto.bot.model.BinanceOrderResponse
import com.cripto.bot.model.NewOrderRequest
import com.cripto.bot.model.TickerPriceResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import mu.KotlinLogging

class BinanceApiClient(
    private val client: HttpClient,
    private val config: BotConfig
) {
    private val logger = KotlinLogging.logger {}

    suspend fun getTickerPrice(symbol: String): Double {
        val response: TickerPriceResponse = client.get("${config.baseUrl}/api/v3/ticker/price") {
            parameter("symbol", symbol.uppercase())
        }.body()
        return response.price.toDouble()
    }

    suspend fun placeOrder(request: NewOrderRequest): BinanceOrderResponse {
        require(request.quantity != null || request.quoteOrderQty != null) {
            "Either quantity or quoteOrderQty must be set"
        }

        val params = Parameters.build {
            append("symbol", request.symbol.uppercase())
            append("side", request.side.name)
            append("type", request.type.name)
            append("timestamp", System.currentTimeMillis().toString())
            request.timeInForce?.let { append("timeInForce", it.name) }
            request.quantity?.let { append("quantity", it.asPlainString()) }
            request.quoteOrderQty?.let { append("quoteOrderQty", it.asPlainString()) }
        }

        val signedBody = signParameters(params)

        logger.info { "Submitting ${request.side} order for ${request.symbol} (quoteQty=${request.quoteOrderQty ?: request.quantity})" }

        val response = client.post("${config.baseUrl}/api/v3/order") {
            contentType(ContentType.Application.FormUrlEncoded)
            headers {
                append("X-MBX-APIKEY", config.apiKey)
            }
            setBody(signedBody)
        }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrElse { "<unreadable body: ${it.message}>" }
            logger.error { "Binance order failed with status=${response.status.value}: $errorBody" }
            error("Binance rejected the order: HTTP ${response.status.value}")
        }

        return response.body()
    }

    suspend fun getAssetBalance(asset: String): Double {
        val params = Parameters.build {
            append("timestamp", System.currentTimeMillis().toString())
        }
        val signed = signParameters(params)
        val response: AccountInformation = client.get("${config.baseUrl}/api/v3/account?$signed") {
            headers {
                append("X-MBX-APIKEY", config.apiKey)
            }
        }.body()
        return response.balances
            .firstOrNull { it.asset.equals(asset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrNull()
            ?: 0.0
    }

    private fun signParameters(parameters: Parameters): String {
        val sorted = parameters.entries()
            .sortedBy { it.key }
            .flatMap { entry ->
                entry.value.map { value ->
                    "${entry.key}=$value"
                }
            }
            .joinToString("&")

        val signature = hmacSha256(sorted, config.apiSecret)
        return "$sorted&signature=$signature"
    }
    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun Double.asPlainString(): String =
        BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
}
