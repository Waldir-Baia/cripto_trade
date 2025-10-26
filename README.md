## Binance Trading Bot (Kotlin)

Automated trading bot scaffold written in Kotlin that connects to the Binance Spot API (testnet by default). It periodically ingests ticker data, feeds it into a moving-average strategy, and routes trade signals either to a paper executor (default) or to the live Binance endpoint.

### Features
- Kotlin/JVM project using coroutines and Ktor HTTP client.
- Configurable SMA spread strategy with adjustable windows, spread threshold, and polling cadence.
- Binance REST integration with signed order submission.
- Switchable trade modes: paper vs. live.
- Structured logging plus CSV trade reports (quote delta + realized PnL) for every live execution.

### Project structure
```
src/main/kotlin/com/cripto/bot
|-- Main.kt          # bootstrap + wiring
|-- binance          # API client + market data
|-- config           # config data classes + loader
|-- core             # trading loop
|-- execution        # order executors (paper/live)
|-- model            # DTOs and shared enums
`-- strategy         # moving-average strategy
```

### Setup
1. Copy `config/bot-config.example.json` to `config/bot-config.json` and fill your Binance (testnet) API credentials, or duplicate `.env.example` -> `.env` and export the variables before running. Expected env vars:
   - `BINANCE_API_KEY`
   - `BINANCE_API_SECRET`
   - Optional overrides: `BOT_SYMBOL`, `BOT_QUOTE_QTY`, `BOT_SHORT_WINDOW`, `BOT_LONG_WINDOW`, `BOT_POLLING_MS`, `BOT_MIN_SPREAD`, `BOT_QUANTITY_PRECISION`, `BOT_MIN_QUANTITY`, `BOT_MIN_NOTIONAL`, `BOT_USE_FULL_BALANCE`, `BOT_MIN_PROFIT_RATIO`, `BINANCE_BASE_URL`, `BOT_TRADE_MODE`, `BOT_REPORTS_DIR`.

2. Install Gradle or generate a wrapper (run `gradle wrapper` once) so you can build without a global Gradle install.

3. Run the bot:
   ```bash
   ./gradlew run
   # or
   gradle run
   ```

### Notes
- Default mode is `PAPER`, meaning orders are only logged. Switch to `LIVE` in your config/env to place real orders (ensure you are on testnet or accept the risk).
- When in `LIVE`, every filled order appends a line to `reports/trade-history.csv` with `quoteChange` (cash spent/received) and `realizedPnl` (profit/loss for sells relative to your current inventory cost basis).
- The project targets Java 17; make sure your JDK matches.
- Tune the SMA windows, `minSpreadRatio`, `quoteOrderQuantity`, and the SELL precision (`quantityPrecision`/`minQuantity`) to align with your bankroll and risk tolerance before enabling live trades.
- `useFullBalance=true` faz o bot consumir todo o saldo livre do ativo de cotação em cada compra; `minProfitRatio` controla o gatilho mínimo de lucro (ex.: 0.002 = 0,2%) antes de autorizar uma venda.

### Next steps
- Add persistence (PostgreSQL/SQLite) to keep trade history.
- Plug a websocket stream for faster price feeds.
- Implement additional strategies (momentum, RSI, arbitrage monitors).
