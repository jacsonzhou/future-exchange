# E2E Acceptance Suite Report

- Time: 2026-02-27 17:38:05
- DurationSec: 127.32
- Passed: 11
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
  "bestBid": "680000.00000000",
  "bestAsk": "686800.00000000",
  "activePosition": null,
  "positionCount": 0
}
```

### cancel_flow
- Result: FAIL
- Message: Cancel flow failed
- Details:
```json
{
  "error": "Cancel verification failed, status=NEW, query={'orderId': '285706666312208384', 'clientOrderId': 'acc-cancel-1772184977368', 'symbol': 'BTCUSDT', 'side': 'SELL', 'type': 'LIMIT', 'price': '755480', 'quantity': '1', 'filledQuantity': '0', 'status': 'NEW', 'reasonCode': 'MATCH_ENGINE_REPORT', 'reasonMsg': 'Match engine reported: NEW', 'createTime': 1772184977422}, cancelResp={'orderId': None, 'status': None, 'success': False, 'errorCode': 'OMS_9003', 'errorMessage': '乐观锁冲突'}"
}
```

### fill_flow
- Result: PASS
- Message: Aggressive order matched with seeded counterparty
- Details:
```json
{
  "orderId": "285706862022627328",
  "side": "BUY",
  "price": "824160.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "285706860261019648",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "CANCELED",
  "preSize": "0",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "1.0000000000000000",
    "entryPrice": "755480.0000000000000000",
    "unrealizedPnl": "0.0",
    "realizedPnl": "0.0",
    "marginRatio": "20.00000000",
    "liquidationPrice": "826893.5323383100000000"
  }
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "285706881840713728",
  "closeSide": "BUY",
  "closePrice": "824160.00000000",
  "sizeAfterFill": "1.0000000000000000",
  "sizeAfterClose": "0.5000000000000000",
  "submitMode": "reduceOnly=false_fallback",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "285706867668160512",
  "seedOrderSide": "SELL",
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
  "side": "SHORT",
  "entryPrice": "755480.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "826893.5323383100000000",
  "expectedLiquidationPrice": "826893.53233831",
  "diff": "0E-16",
  "tolerance": "826.89353233831"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "-34340.0",
  "currentUp": "-26098.40000000000000000000000000000000",
  "markPrice": "807676.80000000"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=0.5, after=0.5, event={'userId': 38, 'positionId': 22, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 57823, 'liquidationThreshold': 10000, 'markPrice': 83516246766169, 'liquidationPrice': 82689353233831, 'bankruptcyPrice': 82689353233831, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 75548000000000, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': -2609840000000, 'leverage': 10, 'priority': 1, 'timestamp': 1772185039759, 'sequence': 1772185039759, 'remark': 'ACC_LIQ_1772185039759_22'}"
}
```
