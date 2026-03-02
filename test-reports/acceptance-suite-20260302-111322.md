# E2E Acceptance Suite Report

- Time: 2026-03-02 11:13:22
- DurationSec: 68.82
- Passed: 10
- Failed: 0

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from default |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | PASS | Aggressive order matched with seeded counterparty |
| manual_close_flow | PASS | Manual close order filled and position reduced |
| liquidation_price_formula | PASS | Liquidation price formula matched |
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
  "apiGateway": "http://127.0.0.1:8082",
  "omsBase": "http://127.0.0.1:8081",
  "matchBase": "http://127.0.0.1:8083",
  "positionInternalBase": "http://127.0.0.1:8086",
  "liquidationBase": "http://127.0.0.1:8102"
}
```

### login
- Result: PASS
- Message: Login success
- Details:
```json
{
  "userId": 104,
  "tokenLen": 193
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772418908",
  "makerUserId": 105,
  "tokenLen": 193
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
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0030000000000000",
    "entryPrice": "66291.0000000000000000",
    "unrealizedPnl": "-0.0003",
    "realizedPnl": "-0.0003",
    "marginRatio": "19.99972847",
    "liquidationPrice": "59961.7085427100000000"
  },
  "positionCount": 1
}
```

### cancel_flow
- Result: PASS
- Message: Passive order canceled successfully
- Details:
```json
{
  "orderId": "286697373604777984",
  "side": "SELL",
  "price": "77077.00000000",
  "cancelResponse": {
    "orderId": "286697373604777984",
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
  "orderId": "286697375882285056",
  "side": "BUY",
  "price": "84084.00000000",
  "qtyInt": 100000,
  "finalStatus": "FILLED",
  "seedOrderId": "286697375655792640",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0.0030000000000000",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0040000000000000",
    "entryPrice": "66291.0000000000000000",
    "unrealizedPnl": "0.0",
    "realizedPnl": "-0.0003",
    "marginRatio": "20.00000000",
    "liquidationPrice": "59961.7085427100000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286697381108387840",
  "closeSide": "SELL",
  "closePrice": "55944.00000000",
  "sizeAfterFill": "0.0040000000000000",
  "sizeAfterClose": "0.0035000000000000",
  "submitMode": "reduceOnly=true",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286697380965781504",
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
  "entryPrice": "66291.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "59961.7085427100000000",
  "expectedLiquidationPrice": "59961.70854271",
  "diff": "0E-16",
  "tolerance": "59.96170854271"
}
```

### liquidation_trigger_execution
- Result: PASS
- Message: Liquidation trigger consumed and position reduced
- Details:
```json
{
  "positionId": 56,
  "beforeSize": "0.0035",
  "afterSize": "0.003",
  "event": {
    "userId": 104,
    "positionId": 56,
    "symbol": "BTCUSDT",
    "marginMode": "CROSS",
    "triggerType": "MANUAL_TEST",
    "marginRatio": 199997,
    "liquidationThreshold": 10000,
    "markPrice": 5936209145728,
    "liquidationPrice": 5996170854271,
    "bankruptcyPrice": 5996170854271,
    "positionSide": 1,
    "positionQty": 50000,
    "entryPrice": 6629100000000,
    "currentMargin": 100000000,
    "maintenanceMargin": 5000000,
    "unrealizedPnl": -35000,
    "leverage": 10,
    "priority": 1,
    "timestamp": 1772421199978,
    "sequence": 1772421199978,
    "remark": "ACC_LIQ_1772421199978_56"
  },
  "seedOrderId": "286697404428718080",
  "seedOrderSide": "BUY",
  "seedOrderPrice": "69930.00000001",
  "seedRequiredQty": "0.0005",
  "seedOrderStatus": "FILLED",
  "seedFilledQuantity": "0.0015"
}
```
