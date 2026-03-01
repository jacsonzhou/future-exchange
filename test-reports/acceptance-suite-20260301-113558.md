# E2E Acceptance Suite Report

- Time: 2026-03-01 11:35:58
- DurationSec: 107.65
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
| cancel_flow | FAIL | Cancel flow failed |
| fill_flow | PASS | Aggressive order matched with seeded counterparty |
| manual_close_flow | PASS | Manual close order filled and position reduced |
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
  "activeOrderCountBefore": 1,
  "canceledOrders": [
    {
      "orderId": "286340251763347456",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "286340251763347456",
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
  "activeOrderCountBefore": 1,
  "canceledOrders": [
    {
      "orderId": "286340365441568768",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "286340365441568768",
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

### context_snapshot
- Result: PASS
- Message: Fetched positions and orderbook depth
- Details:
```json
{
  "bestBid": "53255.96337600",
  "bestAsk": "67242.37800000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "67240.6174800000000000",
    "unrealizedPnl": "209.83252",
    "realizedPnl": "0.0",
    "marginRatio": "20.55996444",
    "liquidationPrice": "60820.6590271400000000"
  },
  "positionCount": 1
}
```

### cancel_flow
- Result: FAIL
- Message: Cancel flow failed
- Details:
```json
{
  "error": "Cancel verification failed, status=NEW, query={'orderId': '286340414498148352', 'clientOrderId': 'acc-cancel-1772336074545', 'symbol': 'BTCUSDT', 'side': 'SELL', 'type': 'LIMIT', 'price': '73966.6158', 'quantity': '1', 'filledQuantity': '0', 'status': 'NEW', 'reasonCode': 'MATCH_ENGINE_REPORT', 'reasonMsg': 'Match engine reported: NEW', 'createTime': 1772336074764}, cancelResp={'orderId': None, 'status': None, 'success': False, 'errorCode': 'OMS_9003', 'errorMessage': '乐观锁冲突'}"
}
```

### fill_flow
- Result: PASS
- Message: Aggressive order matched with seeded counterparty
- Details:
```json
{
  "orderId": "286340679636881408",
  "side": "BUY",
  "price": "80690.85360000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "286340677090938880",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "67276.0645199900000000",
    "unrealizedPnl": "203.98548001",
    "realizedPnl": "-7327.658681995",
    "marginRatio": "20.54412210",
    "liquidationPrice": "60852.7216763700000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286340738038370304",
  "closeSide": "SELL",
  "closePrice": "53283.18758400",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286340702462283776",
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
  "entryPrice": "67276.0645199900000000",
  "leverage": "10",
  "actualLiquidationPrice": "60852.7216763700000000",
  "expectedLiquidationPrice": "60852.72167637",
  "diff": "0E-16",
  "tolerance": "60.85272167637"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "liquidation-core (8102) is not listening"
}
```
