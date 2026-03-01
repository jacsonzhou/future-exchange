# E2E Acceptance Suite Report

- Time: 2026-02-27 13:00:43
- DurationSec: 141.6
- Passed: 7
- Failed: 2

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | PASS | Manual close order filled and position reduced |
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
  "bestBid": "80800.00000000",
  "bestAsk": "81608.00000000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "2574000041.3000000000000000",
    "entryPrice": "56060.6060598100000000",
    "unrealizedPnl": "16472944711889.102",
    "realizedPnl": "-21545.454548541",
    "marginRatio": "48.35117936",
    "liquidationPrice": "61359.8673291500000000"
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
  "orderId": "285636734882942976",
  "side": "BUY",
  "price": "72720.00000000",
  "cancelResponse": {
    "orderId": "285636734882942976",
    "status": "CANCELED",
    "success": true,
    "errorCode": null,
    "errorMessage": null
  },
  "finalStatus": "CANCELED"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "Aggressive open order not FILLED, status=PARTIALLY_FILLED, query={'orderId': '285636761109925888', 'clientOrderId': 'acc-open-1772168310629', 'symbol': 'BTCUSDT', 'side': 'SELL', 'type': 'LIMIT', 'price': '79992', 'quantity': '1', 'filledQuantity': '0.1', 'status': 'PARTIALLY_FILLED', 'reasonCode': 'MATCH_ENGINE_REPORT', 'reasonMsg': 'Match engine reported: PARTIALLY_FILLED', 'createTime': 1772168310723}"
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "285636960154816512",
  "closeSide": "BUY",
  "closePrice": "80791.92000000",
  "sizeAfterFill": "2574000041.3000000000000000",
  "sizeAfterClose": "2574000041.3000000000000000"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "56060.6060598100000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673291500000000",
  "expectedLiquidationPrice": "61359.86732915",
  "diff": "0E-16",
  "tolerance": "61.35986732915"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "16472944711889.102",
  "currentUp": "19029485863907.13259101100000000000000000000000",
  "markPrice": "48667.64352734"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000041.3, after=2574000041.3, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 534196, 'liquidationThreshold': 10000, 'markPrice': 6197346600244, 'liquidationPrice': 6135986732915, 'bankruptcyPrice': 6135986732915, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060605981, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1902948586390713300000, 'leverage': 10, 'priority': 1, 'timestamp': 1772168380528, 'sequence': 1772168380528, 'remark': 'ACC_LIQ_1772168380528_3'}"
}
```
