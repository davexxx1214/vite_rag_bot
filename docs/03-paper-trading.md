---
slug: paper-trading
title: Paper trading
summary: Test an application against simulated order execution before considering live-market operation.
source: https://docs.alpaca.markets/us/v1.4.2/docs/paper-trading
order: 3
---

Paper trading is a simulation environment for exercising an Alpaca trading application without sending orders to a live exchange. It uses market information to simulate fills and lets developers inspect simulated balances and activity in the dashboard.

## Paper and live environments

The paper and live Trading APIs use compatible request shapes, but they have different credentials and base URLs. To use paper trading, configure the paper API hostname and the API key shown for the paper account.

```text
Paper base URL: https://paper-api.alpaca.markets
```

Changing from paper to live operation should be an explicit deployment decision, not an automatic fallback.

## What paper trading is good for

Paper trading helps test authentication, request formatting, order lifecycle handling, reconnect logic, retries, and strategy behavior while markets are moving. It is more realistic than a simple historical backtest because the application interacts with a running API and current market conditions.

## Simulation limitations

Paper results are not a promise of live results. A simulation may not reproduce factors such as:

- The market impact or information leakage caused by an order.
- Latency-related slippage.
- Queue position for a limit order.
- Price improvement, regulatory fees, or dividends.
- Differences in liquidity and fill assumptions.

Different simulators can also report different outcomes because they use different data sources, fill models, liquidity assumptions, and return calculations. Production readiness therefore requires operational safeguards in addition to successful paper results.
