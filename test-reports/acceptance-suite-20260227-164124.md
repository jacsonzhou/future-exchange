# E2E Acceptance Suite Report

- Time: 2026-02-27 16:41:24
- DurationSec: 28.98
- Passed: 11
- Failed: 1

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
| manual_close_flow | PASS | Manual close order filled and position reduced |
| liquidation_price_formula | FAIL | Liquidation price formula check failed |
| pnl_push | PASS | PNL changed after mark injection (attempt 1/2) |

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
  "apiGateway": "http://127.0.0.1:8082",
  "omsBase": "http://127.0.0.1:8081",
  "matchBase": "http://127.0.0.1:8083",
  "positionInternalBase": "http://127.0.0.1:8086",
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
  "activeOrderCountBefore": 2,
  "canceledOrders": [
    {
      "orderId": "285679198448652288",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "285679198448652288",
        "status": "CANCELED",
        "success": true,
        "errorCode": null,
        "errorMessage": null
      }
    },
    {
      "orderId": "285678965694140416",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "285678965694140416",
        "status": "CANCELED",
        "success": true,
        "errorCode": null,
        "errorMessage": null
      }
    }
  ],
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
  "bestBid": "680000.00000000",
  "bestAsk": "686800.00000000",
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
  "orderId": "285692743634653184",
  "side": "SELL",
  "price": "755480.00000000",
  "cancelResponse": {
    "orderId": "285692743634653184",
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
  "orderId": "285692744402210816",
  "side": "BUY",
  "price": "824160.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "285692744263798784",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "FILLED",
  "preSize": "0",
  "seedFilledQuantity": "1",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "824160.0000000000000000",
    "unrealizedPnl": "0.0",
    "realizedPnl": "0.0",
    "marginRatio": "0.00000000",
    "liquidationPrice": "0"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "285692814979764224",
  "closeSide": "SELL",
  "closePrice": "544000.00000000",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "285692800438112256",
  "seedOrderSide": "BUY",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0"
}
```

### liquidation_price_formula
- Result: FAIL
- Message: Liquidation price formula check failed
- Details:
```json
{
  "error": "Liquidation price mismatch, actual=0, expected=745471.35678392, diff=745471.35678392, tolerance=745.47135678392"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "0.0",
  "currentUp": "8241.60000000000000000000000000000000",
  "markPrice": "840643.20000000"
}
```
