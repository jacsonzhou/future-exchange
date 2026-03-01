# E2E Acceptance Suite Report

- Time: 2026-02-27 13:15:48
- DurationSec: 86.02
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
  "bestBid": "80791.92000000",
  "bestAsk": "81599.83920000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "2574000043.2000000000000000",
    "entryPrice": "56060.6060675900000000",
    "unrealizedPnl": "15603483459304.48",
    "realizedPnl": "-21545.454548541",
    "marginRatio": "46.67334275",
    "liquidationPrice": "61359.8673376600000000"
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
  "error": "Order submit failed. gateway={'error': 'HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772169264720,\"code\":503,\"success\":false}'}, gatewayErr=HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772169264720,\"code\":503,\"success\":false}, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, gatewayErr=None, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, gatewayErr=None, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "56060.6060675900000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673376600000000",
  "expectedLiquidationPrice": "61359.86733766",
  "diff": "0E-16",
  "tolerance": "61.35986733766"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "15603483459304.48",
  "currentUp": "18177413838917.89189665600000000000000000000000",
  "markPrice": "48998.67384776"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000043.2, after=2574000043.2, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 517075, 'liquidationThreshold': 10000, 'markPrice': 6197346601104, 'liquidationPrice': 6135986733766, 'bankruptcyPrice': 6135986733766, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060606759, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1817741383891789000000, 'leverage': 10, 'priority': 1, 'timestamp': 1772169298228, 'sequence': 1772169298228, 'remark': 'ACC_LIQ_1772169298228_3'}"
}
```
