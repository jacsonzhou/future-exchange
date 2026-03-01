# E2E Acceptance Suite Report

- Time: 2026-02-27 12:48:42
- DurationSec: 97.06
- Passed: 4
- Failed: 5

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | FAIL | Cancel flow failed |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | PASS | Liquidation price formula matched |
| pnl_push | FAIL | PNL push script failed |
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
    "size": "2574000040.2000000000000000",
    "entryPrice": "56060.6060503300000000",
    "unrealizedPnl": "8485994530981.987",
    "realizedPnl": "0.0",
    "marginRatio": "33.74614319",
    "liquidationPrice": "61359.8673187700000000"
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
  "error": "HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772167627872,\"code\":503,\"success\":false}"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772167629070,\"code\":503,\"success\":false}"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "HTTP 503 POST http://localhost:8082/api/order/create: {\"message\":\"Order service is temporarily unavailable. Please try again later.\",\"timestamp\":1772167630439,\"code\":503,\"success\":false}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "56060.6060503300000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673187700000000",
  "expectedLiquidationPrice": "61359.86731877",
  "diff": "0E-16",
  "tolerance": "61.35986731877"
}
```

### pnl_push
- Result: FAIL
- Message: PNL push script failed
- Details:
```json
{
  "returncode": 1,
  "stdout_tail": "[12:47:11] Login\n[12:47:11] Login ok, userId=38\n[12:47:11] Selected position: symbol=BTCUSDT, side=SHORT, size=2574000040.2000000000000000, entry=56060.6060503300000000, up=8485994530981.987\n[12:47:11] Target mark price=51708.51805113 (PRICE_MOVE_BPS=200)\n[12:47:45] Injected mark event id=manual-mark-1772167637194, mp=51708.51805113",
  "stderr_tail": "FAIL: received 1000 (OK); then sent 1000 (OK)"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000040.7, after=2574000040.7, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 385165, 'liquidationThreshold': 10000, 'markPrice': 6197346599720, 'liquidationPrice': 6135986732396, 'bankruptcyPrice': 6135986732396, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060605507, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1120227469927154100000, 'leverage': 10, 'priority': 1, 'timestamp': 1772167666060, 'sequence': 1772167666060, 'remark': 'ACC_LIQ_1772167666060_3'}"
}
```
