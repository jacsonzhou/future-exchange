# E2E Acceptance Suite Report

- Time: 2026-03-01 12:09:15
- DurationSec: 55.1
- Passed: 10
- Failed: 2

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| test_state_reset | PASS | Test account state reset to clean baseline |
| maker_state_reset | PASS | Maker account state reset to clean baseline |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | PASS | Aggressive order matched with seeded counterparty |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | PASS | Liquidation price formula matched |
| liquidation_trigger_execution | FAIL | Liquidation trigger stage failed |

## Details

### runtime_schema_fix
- Result: PASS
- Message: Liquidation runtime schema aligned
- Details:
```json
{
  "mysqlContainer": "web3-mysql",
  "completedAtBefore": true,
  "validatedAtBefore": true,
  "completedAtAfter": true,
  "validatedAtAfter": true
}
```

### service_check
- Result: PASS
- Message: Required services are listening
- Details:
```json
{
  "apiGateway": "http://localhost:8082",
  "omsBase": "http://localhost:8081",
  "matchBase": "http://localhost:8083",
  "positionInternalBase": "http://localhost:8086",
  "liquidationBase": "http://localhost:8102"
}
```

### login
- Result: PASS
- Message: Login success
- Details:
```json
{
  "userId": 38,
  "tokenLen": 176
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "zhoufan",
  "makerUserId": 16,
  "tokenLen": 175
}
```

### test_state_reset
- Result: PASS
- Message: Test account state reset to clean baseline
- Details:
```json
{
  "userId": 38,
  "symbol": "BTCUSDT",
  "activeOrderCountBefore": 0,
  "canceledOrders": [],
  "activeOrderCountAfter": 0,
  "positionRowsAfter": 0,
  "liquidationRowsAfter": 0,
  "liquidationEventRowsAfter": 0
}
```

### maker_state_reset
- Result: PASS
- Message: Maker account state reset to clean baseline
- Details:
```json
{
  "userId": 16,
  "symbol": "BTCUSDT",
  "activeOrderCountBefore": 0,
  "canceledOrders": [],
  "activeOrderCountAfter": 0,
  "positionRowsAfter": 0,
  "liquidationRowsAfter": 0,
  "liquidationEventRowsAfter": 0
}
```

### context_snapshot
- Result: PASS
- Message: Fetched positions and orderbook depth
- Details:
```json
{
  "bestBid": "66603.98448000",
  "bestAsk": "67276.75200000",
  "activePosition": null,
  "positionCount": 0
}
```

### cancel_flow
- Result: PASS
- Message: Passive order canceled successfully
- Details:
```json
{
  "orderId": "286349105549021184",
  "side": "SELL",
  "price": "74004.42720000",
  "cancelResponse": {
    "orderId": "286349105549021184",
    "status": "CANCELED",
    "success": true,
    "errorCode": null,
    "errorMessage": null
  },
  "finalStatus": "CANCELED"
}
```

### fill_flow
- Result: PASS
- Message: Aggressive order matched with seeded counterparty
- Details:
```json
{
  "orderId": "286349109185482752",
  "side": "BUY",
  "price": "80732.10240000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "286349108933824512",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0",
  "seedFilledQuantity": "0.98",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "80462.9953920000000000",
    "unrealizedPnl": "269.107008",
    "realizedPnl": "0.0",
    "marginRatio": "20.60000000",
    "liquidationPrice": "72780.5988470400000000"
  }
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Depth empty for BTCUSDT: {'symbol': 'BTCUSDT', 'asks': [], 'bids': [], 'timestamp': 1772338153157}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "80462.9953920000000000",
  "leverage": "10",
  "actualLiquidationPrice": "72780.5988470400000000",
  "expectedLiquidationPrice": "72780.59884704",
  "diff": "0E-16",
  "tolerance": "72.78059884704"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Depth empty for BTCUSDT: {'symbol': 'BTCUSDT', 'asks': [], 'bids': [], 'timestamp': 1772338155259}"
}
```
