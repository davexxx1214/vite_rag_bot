---
slug: authentication
title: Authentication and API domains
summary: Select the correct API hostname and credentials for live, paper, market-data, or broker requests.
source: https://docs.alpaca.markets/us/docs/authentication
order: 2
---

Alpaca exposes different services under different hostnames. Credentials are scoped to their environment, so live-account credentials cannot be substituted for paper-account credentials or the reverse.

## Common API hostnames

- Live Trading API: `https://api.alpaca.markets`
- Paper Trading API: `https://paper-api.alpaca.markets`
- Market Data API: `https://data.alpaca.markets`
- Live Broker API: `https://broker-api.alpaca.markets`
- Broker authentication: `https://authx.alpaca.markets`

Sandbox broker services use their corresponding sandbox hostnames. Always confirm that the hostname matches both the product and the credentials being used.

## Trading API key authentication

For the key-based Trading API flow, send the key ID and secret using these HTTP headers:

```http
APCA-API-KEY-ID: YOUR_API_KEY_ID
APCA-API-SECRET-KEY: YOUR_API_SECRET_KEY
```

For example, reading the live account uses the `/v2/account` path on the live Trading API hostname. A paper account uses the same API shape but the paper hostname and paper credentials.

## Broker client credentials

Broker integrations can exchange a client ID and client secret, or a signed client assertion, for a short-lived access token. The token endpoint returns a bearer token with a limited lifetime. Reuse that token until it is close to expiration instead of requesting a new token for every API call.

The client-credentials token flow described for Broker API is not the authentication flow for the Trading API.

## Security notes

Keep every secret on the server. Do not place API credentials in browser JavaScript, public Vite variables, URLs, logs, or a public source repository. Use environment-specific secrets so a test deployment cannot accidentally use live-trading credentials.
