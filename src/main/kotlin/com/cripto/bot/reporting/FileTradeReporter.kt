package com.cripto.bot.reporting

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class FileTradeReporter(
    private val filePath: Path
) : TradeReporter {

    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    init {
        val parent = filePath.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
        if (!Files.exists(filePath)) {
            val header = "timestamp,symbol,side,avgPrice,executedQty,quoteChange,realizedPnl,status,reason${System.lineSeparator()}"
            Files.writeString(
                filePath,
                header,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    override fun record(record: TradeRecord) {
        val line = buildString {
            append(formatter.format(record.timestamp.atOffset(ZoneOffset.UTC)))
            append(',')
            append(record.symbol)
            append(',')
            append(record.side)
            append(',')
            append(record.avgPrice)
            append(',')
            append(record.executedQty)
            append(',')
            append(record.quoteChange)
            append(',')
            append(record.realizedPnl)
            append(',')
            append(record.status)
            append(',')
            append(record.reason.replace(",", ";"))
            append(System.lineSeparator())
        }

        Files.writeString(
            filePath,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }
}
