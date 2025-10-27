## Binance Trading Bot (Kotlin)

Automated trading bot written in Kotlin that connects to the Binance Spot API (testnet by default). It ingests ticker data, feeds it into a moving-average strategy, and routes trade signals either to a paper executor (default) or to the live Binance endpoint. An optional conversion-arbitrage scanner can look for triangular opportunities and, if enabled, execute the conversions automatically.

### Features
- Kotlin/JVM project using coroutines and Ktor HTTP client.
- Configurable SMA spread strategy with adjustable windows, spread threshold, and polling cadence.
- Binance REST integration with signed order submission.
- Switchable trade modes: paper vs. live.
- Structured logging plus CSV trade reports (quote delta + realized PnL) for every live execution.
- Optional email notifications per sale and optional triangular conversion arbitrage module.

### Project structure
```
src/main/kotlin/com/cripto/bot
|-- Main.kt          # bootstrap + wiring
|-- binance          # API client + market data
|-- config           # config data classes + loader
|-- core             # trading loop
|-- execution        # order executors (paper/live)
|-- notification     # notifiers (email)
|-- model            # DTOs and shared enums
`-- strategy         # moving-average strategy
```

### Setup
1. Copy `config/bot-config.example.json` to `config/bot-config.json` and fill your Binance (testnet) API credentials, or duplicate `.env.example` -> `.env` and export the variables before running. Expected env vars:
   - `BINANCE_API_KEY`
   - `BINANCE_API_SECRET`
   - Optional overrides: `BOT_SYMBOL`, `BOT_QUOTE_QTY`, `BOT_SHORT_WINDOW`, `BOT_LONG_WINDOW`, `BOT_POLLING_MS`, `BOT_MIN_SPREAD`, `BOT_QUANTITY_PRECISION`, `BOT_MIN_QUANTITY`, `BOT_MIN_NOTIONAL`, `BOT_USE_FULL_BALANCE`, `BOT_MIN_PROFIT_RATIO`, `BINANCE_BASE_URL`, `BOT_TRADE_MODE`, `BOT_REPORTS_DIR`.
   - Optional blocks inside `bot-config.json`: `emailConfig` (SMTP details) and `conversionArbitrage` (triangular paths).

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
- Tune the SMA windows, `minSpreadRatio`, `quoteOrderQuantity`, and the SELL precision (`quantityPrecision`/`minQuantity`) to match your bankroll and risk tolerance before enabling real trades.
- `useFullBalance=true` makes the bot spend the entire free balance of the quote asset on each buy; `minProfitRatio` controls the minimum profit trigger (e.g., 0.002 = 0.2%) before a sell is allowed.
- To receive an e-mail after every sale, provide SMTP credentials under `emailConfig` (use an app-specific password whenever possible) and set `enabled=true`.

### Conversion arbitrage (experimental)
- Enable `conversionArbitrage.enabled=true` and describe triangular paths in `conversionArbitrage.paths`. Each path lists legs (`symbol`, `fromAsset`, `toAsset`, `side`) that form a cycle returning to `startAsset`.
- The runner fetches the best bid/ask for each symbol, estimates net return (applying `feeRate`), and logs opportunities whose `profitRatio` ≥ `conversionArbitrage.minProfitRatio`.
- Set `conversionArbitrage.budget` to choose how much of the starting asset is used per attempt. When `executeTrades=true`, the bot sequentially places the three trades for the profitable path; leave it `false` if you only want the opportunity logs.

### Next steps
- Add persistence (PostgreSQL/SQLite) to keep trade history.
- Plug a websocket stream for faster price feeds.
- Implement additional strategies (momentum, RSI, arbitrage monitors).
