# E2E Acceptance Suite Report

- Time: 2026-03-02 11:16:07
- DurationSec: 34.29
- Passed: 10
- Failed: 0

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from ticker24h |
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
  "userId": 106,
  "tokenLen": 193
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772421295",
  "makerUserId": 107,
  "tokenLen": 193
}
```

### context_snapshot
- Result: PASS
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "66384.44910000",
  "bestAsk": "66517.35090000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0010000000000000",
    "entryPrice": "66450.9000000000000000",
    "unrealizedPnl": "0.03065",
    "realizedPnl": "0.0",
    "marginRatio": "20.08298543",
    "liquidationPrice": "60106.3417085400000000"
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
  "orderId": "286698037273694208",
  "side": "SELL",
  "price": "73169.08599000",
  "cancelResponse": {
    "orderId": "286698037273694208",
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
  "orderId": "286698044412399616",
  "side": "BUY",
  "price": "79820.82108000",
  "qtyInt": 100000,
  "finalStatus": "FILLED",
  "seedOrderId": "286698043699367936",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0.0010000000000000",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0020000000000000",
    "entryPrice": "66452.7000000000000000",
    "unrealizedPnl": "0.0036",
    "realizedPnl": "0.0",
    "marginRatio": "20.00487552",
    "liquidationPrice": "60107.9698492500000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286698058744336384",
  "closeSide": "SELL",
  "closePrice": "53107.55928000",
  "sizeAfterFill": "0.0020000000000000",
  "sizeAfterClose": "0.0015000000000000",
  "submitMode": "reduceOnly=true",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286698057016283136",
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
  "entryPrice": "66452.7000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "60107.9698492500000000",
  "expectedLiquidationPrice": "60107.96984925",
  "diff": "0E-16",
  "tolerance": "60.10796984925"
}
```

### liquidation_trigger_execution
- Result: PASS
- Message: Liquidation trigger consumed and position reduced
- Details:
```json
{
  "positionId": 58,
  "beforeSize": "0.0015",
  "afterSize": "0.001",
  "event": {
    "userId": 106,
    "positionId": 58,
    "symbol": "BTCUSDT",
    "marginMode": "CROSS",
    "triggerType": "MANUAL_TEST",
    "marginRatio": 201741,
    "liquidationThreshold": 10000,
    "markPrice": 5950689015076,
    "liquidationPrice": 6010796984925,
    "bankruptcyPrice": 6010796984925,
    "positionSide": 1,
    "positionQty": 50000,
    "entryPrice": 6645270000000,
    "currentMargin": 100000000,
    "maintenanceMargin": 5000000,
    "unrealizedPnl": 9652500,
    "leverage": 10,
    "priority": 1,
    "timestamp": 1772421364542,
    "sequence": 1772421364542,
    "remark": "ACC_LIQ_1772421364542_58"
  },
  "seedOrderId": "286698094660161536",
  "seedOrderSide": "BUY",
  "seedOrderPrice": "66384.44910001",
  "seedRequiredQty": "0.0005",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0"
}
```
