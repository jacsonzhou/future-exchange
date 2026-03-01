# E2E Acceptance Suite Report

- Time: 2026-03-01 12:30:35
- DurationSec: 86.82
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
| context_snapshot | PASS | Depth empty, fallback bid/ask from default |
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
- Message: Depth empty, fallback bid/ask from default
- Details:
```json
{
  "bestBid": "69930.00000000",
  "bestAsk": "70070.00000000",
  "depthHasLevels": false,
  "fallbackSource": "default",
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
  "orderId": "286354206187589632",
  "side": "SELL",
  "price": "77077.00000000",
  "cancelResponse": {
    "orderId": "286354206187589632",
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
  "orderId": "286354213120774144",
  "side": "BUY",
  "price": "84084.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "286354212659400704",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "FILLED",
  "preSize": "0",
  "seedFilledQuantity": "1",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "84084.0000000000000000",
    "unrealizedPnl": "-16786.15",
    "realizedPnl": "0.0",
    "marginRatio": "-24.89752644",
    "liquidationPrice": "76055.8793969800000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286354244284452864",
  "closeSide": "SELL",
  "closePrice": "55944.00000000",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=true",
  "orderStatusObserved": "FROZEN",
  "seedOrderId": "286354243625947136",
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
  "entryPrice": "84084.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "76055.8793969800000000",
  "expectedLiquidationPrice": "76055.87939698",
  "diff": "0E-16",
  "tolerance": "76.05587939698"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "-8401.425",
  "currentUp": "-7728.61350000000000000000000000000000",
  "markPrice": "68626.77300000"
}
```

### liquidation_trigger_execution
- Result: PASS
- Message: Liquidation trigger consumed and position reduced
- Details:
```json
{
  "positionId": 41,
  "beforeSize": "0.5",
  "afterSize": "0.0",
  "event": {
    "userId": 38,
    "positionId": 41,
    "symbol": "BTCUSDT",
    "marginMode": "CROSS",
    "triggerType": "MANUAL_TEST",
    "marginRatio": -205425,
    "liquidationThreshold": 10000,
    "markPrice": 7529532060301,
    "liquidationPrice": 7605587939698,
    "bankruptcyPrice": 7605587939698,
    "positionSide": 1,
    "positionQty": 50000000,
    "entryPrice": 8408400000000,
    "currentMargin": 100000000,
    "maintenanceMargin": 5000000,
    "unrealizedPnl": -772861350000,
    "leverage": 10,
    "priority": 1,
    "timestamp": 1772339431728,
    "sequence": 1772339431728,
    "remark": "ACC_LIQ_1772339431728_41"
  },
  "seedOrderId": "286354493707128832",
  "seedOrderSide": "BUY",
  "seedOrderPrice": "69930.00000001",
  "seedRequiredQty": "0.5",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0.5"
}
```
