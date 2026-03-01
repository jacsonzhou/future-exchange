# E2E Acceptance Suite Report

- Time: 2026-02-27 17:40:44
- DurationSec: 44.6
- Passed: 13
- Failed: 0

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
| liquidation_price_formula | PASS | Liquidation price formula matched |
| pnl_push | PASS | PNL changed after mark injection (attempt 1/2) |
| liquidation_trigger_execution | PASS | Liquidation trigger consumed and position reduced |

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
  "orderId": "285707684668248064",
  "side": "SELL",
  "price": "755480.00000000",
  "cancelResponse": {
    "orderId": "285707684668248064",
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
  "orderId": "285707691475603456",
  "side": "BUY",
  "price": "824160.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "285707690871623680",
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
    "marginRatio": "20.00000000",
    "liquidationPrice": "745471.3567839200000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "285707733586415616",
  "closeSide": "SELL",
  "closePrice": "544000.00000000",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "285707718205902848",
  "seedOrderSide": "BUY",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "824160.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "745471.3567839200000000",
  "expectedLiquidationPrice": "745471.35678392",
  "diff": "0E-16",
  "tolerance": "745.47135678392"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "-72080.0",
  "currentUp": "-65280.00000000000000000000000000000000",
  "markPrice": "693600.00000000"
}
```

### liquidation_trigger_execution
- Result: PASS
- Message: Liquidation trigger consumed and position reduced
- Details:
```json
{
  "positionId": 25,
  "beforeSize": "0.5",
  "afterSize": "0.0",
  "event": {
    "userId": 38,
    "positionId": 25,
    "symbol": "BTCUSDT",
    "marginMode": "CROSS",
    "triggerType": "MANUAL_TEST",
    "marginRatio": -138824,
    "liquidationThreshold": 10000,
    "markPrice": 73801664321608,
    "liquidationPrice": 74547135678392,
    "bankruptcyPrice": 74547135678392,
    "positionSide": 1,
    "positionQty": 50000000,
    "entryPrice": 82416000000000,
    "currentMargin": 100000000,
    "maintenanceMargin": 5000000,
    "unrealizedPnl": -6528000000000,
    "leverage": 10,
    "priority": 1,
    "timestamp": 1772185241675,
    "sequence": 1772185241675,
    "remark": "ACC_LIQ_1772185241675_25"
  }
}
```
