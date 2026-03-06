# Event Envelope V1

## 1. Scope

All newly introduced shared topics in V3 must use this envelope.

Applies to:

- `ex.order.command.v1`
- `ex.order.state.v1`
- `ex.trade.v1`
- `acc.trade.entry.v1`

## 2. Envelope Schema

```json
{
  "eventId": "string",
  "eventType": "string",
  "schemaVersion": "1.0",
  "source": "string",
  "eventTime": 1710000000000,
  "traceId": "string",
  "data": {}
}
```

## 3. Field Contract

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `eventId` | string | yes | Unique event identifier in producer scope; recommended deterministic when possible |
| `eventType` | string | yes | Business event type (for example `CFD_ORDER_SUBMIT`, `ORDER_STATE`, `TRADE`, `TRADE_ENTRY`) |
| `schemaVersion` | string | yes | Fixed to `1.0` for this contract |
| `source` | string | yes | Producer service name (`spring.application.name`) |
| `eventTime` | long(ms) | yes | Business event timestamp in milliseconds |
| `traceId` | string | yes | End-to-end trace key (header trace id or event id fallback) |
| `data` | object | yes | Original domain payload with existing business fields |

## 4. Idempotency Key Recommendations

- order command: `eventType:orderId:eventTime`
- order state: `orderId:status:matchSequence`
- trade: `tradeId`
- ledger entry: `tradeId` (or `symbol:bizSeq` when tradeId is absent)

## 5. Compatibility Rules

- Legacy topics may keep raw payload format during migration.
- Shared topics must always publish envelope format.
- Consumers that support dual source should parse:
  1. `root.data` when envelope exists
  2. `root` directly for legacy/raw payload

## 6. Example Events

### 6.1 Order Command (`ex.order.command.v1`)

```json
{
  "eventId": "CFD_ORDER_SUBMIT:10001:1710000001000",
  "eventType": "CFD_ORDER_SUBMIT",
  "schemaVersion": "1.0",
  "source": "oms-core",
  "eventTime": 1710000001000,
  "traceId": "cli-10001",
  "data": {
    "orderId": 10001,
    "userId": 18,
    "symbol": "BTCUSDT",
    "orderType": "MARKET",
    "executionMode": "CFD_DEALER"
  }
}
```

### 6.2 Order State (`ex.order.state.v1`)

```json
{
  "eventId": "trade-1710000002000",
  "eventType": "ORDER_STATE",
  "schemaVersion": "1.0",
  "source": "cfd-dealer-core",
  "eventTime": 1710000002000,
  "traceId": "trade-1710000002000",
  "data": {
    "orderId": 10001,
    "symbol": "BTCUSDT",
    "status": "FILLED",
    "matchSequence": 9281,
    "filledQuantityDelta": "100000"
  }
}
```

### 6.3 Trade (`ex.trade.v1`)

```json
{
  "eventId": "trade-1710000003000",
  "eventType": "TRADE",
  "schemaVersion": "1.0",
  "source": "cfd-dealer-core",
  "eventTime": 1710000003000,
  "traceId": "trade-1710000003000",
  "data": {
    "tradeId": "trade-1710000003000",
    "symbol": "BTCUSDT",
    "price": 6700000000000,
    "quantity": 100000,
    "executionMode": "CFD_DEALER"
  }
}
```

### 6.4 Ledger Entry (`acc.trade.entry.v1`)

```json
{
  "eventId": "trade-1710000003000",
  "eventType": "TRADE_ENTRY",
  "schemaVersion": "1.0",
  "source": "ledger-core",
  "eventTime": 1710000003500,
  "traceId": "trade-1710000003000",
  "data": {
    "tradeId": "trade-1710000003000",
    "symbol": "BTCUSDT",
    "bizSeq": 100234,
    "entries": []
  }
}
```
