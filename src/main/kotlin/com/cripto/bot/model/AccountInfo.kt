package com.cripto.bot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountInformation(
    @SerialName("balances")
    val balances: List<Balance>
) {
    @Serializable
    data class Balance(
        @SerialName("asset")
        val asset: String,
        @SerialName("free")
        val free: String,
        @SerialName("locked")
        val locked: String
    )
}
