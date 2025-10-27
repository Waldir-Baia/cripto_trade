package com.cripto.bot.notification

import com.cripto.bot.config.EmailConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmailTradeNotifier(
    emailConfig: EmailConfig
) : TradeNotifier {
    private val username = requireNotNull(emailConfig.username) { "emailConfig.username is required" }
    private val password = requireNotNull(emailConfig.password) { "emailConfig.password is required" }
    private val fromAddress = (emailConfig.from ?: emailConfig.username)
    private val toAddress = requireNotNull(emailConfig.to) { "emailConfig.to is required" }
    private val subjectPrefix = emailConfig.subjectPrefix
    private val session: Session

    init {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", emailConfig.host)
            put("mail.smtp.port", emailConfig.port.toString())
        }
        session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(username, password)
            }
        )
    }

    override suspend fun notifySale(notification: SaleNotification) {
        withContext(Dispatchers.IO) {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromAddress))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress))
                subject = "$subjectPrefix ${notification.symbol} SELL"
                setText(buildBody(notification))
            }
            Transport.send(message)
        }
    }

    private fun buildBody(notification: SaleNotification): String =
        """
        Venda executada pelo bot:
        Símbolo: ${notification.symbol}
        Quantidade vendida: ${"%.6f".format(notification.quantity)}
        Preço médio: ${"%.6f".format(notification.averagePrice)}
        Lucro realizado: ${"%.6f".format(notification.realizedProfit)}
        Variação em moeda: ${"%.6f".format(notification.quoteChange)}
        Motivo: ${notification.reason}
        Timestamp: ${notification.timestampMillis}
        """.trimIndent()
}
