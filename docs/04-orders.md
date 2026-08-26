---
slug: orders
title: Working with orders
summary: Submit, identify, retrieve, and monitor Trading API orders using the orders endpoint.
source: https://docs.alpaca.markets/us/docs/working-with-orders
order: 4
---

The Trading API exposes order operations under `/v2/orders`. An application can submit new orders, retrieve existing orders, and listen for status changes.

## Submit an order

Create an order by sending a `POST` request to `/v2/orders`. The request identifies the asset, quantity or notional amount, side, order type, and time in force. A basic market-order payload looks like this:

```json
{
  "symbol": "AAPL",
  "qty": 1,
  "side": "buy",
  "type": "market",
  "time_in_force": "day"
}
```

A limit order also supplies a limit price. More advanced order classes can connect entry, take-profit, and stop-loss instructions, but their validation and lifecycle rules should be reviewed before use.

## Client order IDs

An application can assign a unique `client_order_id` when submitting an order. This is useful for correlating brokerage activity with a strategy, job, or internal request. Use a distinct value for every order and persist the association in the application.

Orders can be looked up using the client-provided identifier or the order identifier returned by the API.

## Retrieve and monitor orders

Send a `GET` request to `/v2/orders` to list orders. Filters can narrow results by status and other supported fields. Applications should also subscribe to trade updates over the streaming interface so order-state changes are received promptly instead of relying only on polling.

Network timeouts require careful handling: a timed-out submission may still have reached the trading service. Before retrying, reconcile the order using its identifiers to avoid creating an unintended duplicate.
