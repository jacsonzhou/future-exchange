# E2E Acceptance Suite Report

- Time: 2026-03-01 11:24:06
- DurationSec: 130.33
- Passed: 12
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
| liquidation_price_formula | PASS | Liquidation price formula matched |
| pnl_push | PASS | PNL changed after mark injection (attempt 1/2) |
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
  "bestBid": "66330.00000000",
  "bestAsk": "67000.00000000",
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
  "orderId": "286337384365166592",
  "side": "SELL",
  "price": "73700.00000000",
  "cancelResponse": {
    "orderId": "286337384365166592",
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
  "orderId": "286337408432082944",
  "side": "BUY",
  "price": "80400.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "286337400957833216",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "67151.2649600000000000",
    "unrealizedPnl": "3.08704",
    "realizedPnl": "0.0",
    "marginRatio": "20.00827448",
    "liquidationPrice": "60739.8376522600000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286337465625612288",
  "closeSide": "SELL",
  "closePrice": "53186.24678400",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286337433509826560",
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
  "entryPrice": "67151.2649600000000000",
  "leverage": "10",
  "actualLiquidationPrice": "60739.8376522600000000",
  "expectedLiquidationPrice": "60739.83765226",
  "diff": "0E-16",
  "tolerance": "60.73983765226"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "145.14252",
  "currentUp": "819.55802000000000000000000000000000",
  "markPrice": "68790.38100000"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=0.5, after=0.5, event={'userId': 38, 'positionId': 31, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 242890, 'liquidationThreshold': 10000, 'markPrice': 6013243927574, 'liquidationPrice': 6073983765226, 'bankruptcyPrice': 6073983765226, 'positionSide': 1, 'positionQty': 50000000, 'entryPrice': 6715126496000, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 81955802000, 'leverage': 10, 'priority': 1, 'timestamp': 1772335394784, 'sequence': 1772335394784, 'remark': 'ACC_LIQ_1772335394784_31'}"
}
```
