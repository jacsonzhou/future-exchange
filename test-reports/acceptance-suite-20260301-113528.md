# E2E Acceptance Suite Report

- Time: 2026-03-01 11:35:28
- DurationSec: 179.0
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
  "activeOrderCountBefore": 1,
  "canceledOrders": [
    {
      "orderId": "286337588355141632",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "286337588355141632",
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
  "bestBid": "66482.80848000",
  "bestAsk": "67154.35200000",
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
  "orderId": "286340251763347456",
  "side": "SELL",
  "price": "73869.78720000",
  "cancelResponse": {
    "orderId": null,
    "status": null,
    "success": false,
    "errorCode": "OMS_9003",
    "errorMessage": "乐观锁冲突"
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
  "orderId": "286340374916501504",
  "side": "BUY",
  "price": "80585.22240000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "286340365441568768",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "67240.6174800000000000",
    "unrealizedPnl": "209.83252",
    "realizedPnl": "0.0",
    "marginRatio": "20.55996444",
    "liquidationPrice": "60820.6590271400000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286340440905486336",
  "closeSide": "SELL",
  "closePrice": "53255.96337600",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286340400153628672",
  "seedOrderSide": "BUY",
  "seedOrderStatus": "FILLED",
  "seedFilledQuantity": "0.5"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "67240.6174800000000000",
  "leverage": "10",
  "actualLiquidationPrice": "60820.6590271400000000",
  "expectedLiquidationPrice": "60820.65902714",
  "diff": "0E-16",
  "tolerance": "60.82065902714"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "110.06626",
  "currentUp": "784.67376000000000000000000000000000",
  "markPrice": "68809.96500000"
}
```

### liquidation_trigger_execution
- Result: PASS
- Message: Liquidation trigger consumed and position reduced
- Details:
```json
{
  "positionId": 34,
  "beforeSize": "0.5",
  "afterSize": "0.0",
  "event": {
    "userId": 38,
    "positionId": 34,
    "symbol": "BTCUSDT",
    "marginMode": "CROSS",
    "triggerType": "MANUAL_TEST",
    "marginRatio": 206169,
    "liquidationThreshold": 10000,
    "markPrice": 6021245243687,
    "liquidationPrice": 6082065902714,
    "bankruptcyPrice": 6082065902714,
    "positionSide": 1,
    "positionQty": 50000000,
    "entryPrice": 6724061748000,
    "currentMargin": 100000000,
    "maintenanceMargin": 5000000,
    "unrealizedPnl": 11561626000,
    "leverage": 10,
    "priority": 1,
    "timestamp": 1772336115141,
    "sequence": 1772336115141,
    "remark": "ACC_LIQ_1772336115141_34"
  },
  "seedOrderId": "286340576834490368",
  "seedOrderSide": "BUY",
  "seedOrderPrice": "66569.95422001",
  "seedRequiredQty": "0.5",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0.5"
}
```
