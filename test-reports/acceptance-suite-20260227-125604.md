# E2E Acceptance Suite Report

- Time: 2026-02-27 12:56:04
- DurationSec: 83.14
- Passed: 5
- Failed: 4

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | FAIL | Cancel flow failed |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | PASS | Liquidation price formula matched |
| pnl_push | PASS | PNL changed after mark injection (attempt 1/2) |
| liquidation_trigger_execution | FAIL | Liquidation trigger stage failed |

## Details

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

### context_snapshot
- Result: PASS
- Message: Fetched positions and orderbook depth
- Details:
```json
{
  "bestBid": "68270.00000000",
  "bestAsk": "80000.00000000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "2574000040.7000000000000000",
    "entryPrice": "56060.6060550700000000",
    "unrealizedPnl": "13864229250627.756",
    "realizedPnl": "-11969.696972465",
    "marginRatio": "43.38415578",
    "liquidationPrice": "61359.8673239600000000"
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
  "error": "Order submit failed. gateway={'error': 'HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772168083582,\"code\":503,\"success\":false}'}, gatewayErr=HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772168083582,\"code\":503,\"success\":false}, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "HTTP 404 GET http://localhost:8082/api/v1/oms/order/query?orderId=285635811725021184: {\"timestamp\":\"2026-02-27T04:54:44.760+00:00\",\"path\":\"/api/v1/oms/order/query\",\"status\":404,\"error\":\"Not Found\",\"requestId\":\"3dd686f4\"}"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'error': 'HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772168085904,\"code\":503,\"success\":false}'}, gatewayErr=HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772168085904,\"code\":503,\"success\":false}, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "56060.6060593400000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673239600000000",
  "expectedLiquidationPrice": "61359.86732863",
  "diff": "0.0000046700000000",
  "tolerance": "61.35986732863"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "13864229250627.756",
  "currentUp": "16472944711889.10161811900000000000000000000000",
  "markPrice": "49660.86074218"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000041.3, after=2574000041.3, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 483512, 'liquidationThreshold': 10000, 'markPrice': 6197346600244, 'liquidationPrice': 6135986732915, 'bankruptcyPrice': 6135986732915, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060605981, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1647294471188910200000, 'leverage': 10, 'priority': 1, 'timestamp': 1772168107727, 'sequence': 1772168107727, 'remark': 'ACC_LIQ_1772168107727_3'}"
}
```
