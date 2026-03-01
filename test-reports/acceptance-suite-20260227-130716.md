# E2E Acceptance Suite Report

- Time: 2026-02-27 13:07:16
- DurationSec: 161.59
- Passed: 7
- Failed: 2

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | PASS | Aggressive order matched |
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
  "bestBid": "65100.00000000",
  "bestAsk": "79992.00000000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "2574000042.2000000000000000",
    "entryPrice": "56060.6060640800000000",
    "unrealizedPnl": "21534896194125.69",
    "realizedPnl": "-21545.454548541",
    "marginRatio": "58.59139873",
    "liquidationPrice": "61359.8673338200000000"
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
  "orderId": "285638293947682816",
  "side": "BUY",
  "price": "58590.00000000",
  "cancelResponse": {
    "orderId": "285638293947682816",
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
- Message: Aggressive order matched
- Details:
```json
{
  "orderId": "285638306295713792",
  "side": "SELL",
  "price": "64449.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close order not FILLED, status=, query={}"
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
  "actualLiquidationPrice": "61359.8673338200000000",
  "expectedLiquidationPrice": "61359.86733766",
  "diff": "0.0000038400000000",
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
  "baselineUp": "21534896194125.69",
  "currentUp": "23990198319051.71230353600000000000000000000000",
  "markPrice": "46740.40485686"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000043.2, after=2574000043.2, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 638688, 'liquidationThreshold': 10000, 'markPrice': 6197346601104, 'liquidationPrice': 6135986733766, 'bankruptcyPrice': 6135986733766, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060606759, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 2399019831905171000000, 'leverage': 10, 'priority': 1, 'timestamp': 1772168765728, 'sequence': 1772168765728, 'remark': 'ACC_LIQ_1772168765728_3'}"
}
```
