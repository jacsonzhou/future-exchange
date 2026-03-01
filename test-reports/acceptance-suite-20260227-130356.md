# E2E Acceptance Suite Report

- Time: 2026-02-27 13:03:56
- DurationSec: 93.23
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
  "bestBid": "68270.00000000",
  "bestAsk": "79992.00000000",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "2574000041.3000000000000000",
    "entryPrice": "56060.6060598100000000",
    "unrealizedPnl": "19029485863907.133",
    "realizedPnl": "-21545.454548541",
    "marginRatio": "53.41957077",
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
  "orderId": "285637739783655424",
  "side": "BUY",
  "price": "61443.00000000",
  "cancelResponse": {
    "orderId": "285637739783655424",
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
  "orderId": "285637747639586816",
  "side": "SELL",
  "price": "67587.30000000",
  "qtyInt": 100000000,
  "finalStatus": "PARTIALLY_FILLED",
  "filledQuantity": "0.9",
  "note": "Partial fill accepted on thin book; residual canceled"
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
  "entryPrice": "56060.6060640800000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673291500000000",
  "expectedLiquidationPrice": "61359.86733382",
  "diff": "0.0000046700000000",
  "tolerance": "61.35986733382"
}
```

### pnl_push
- Result: PASS
- Message: PNL changed after mark injection (attempt 1/2)
- Details:
```json
{
  "attempt": 1,
  "baselineUp": "19029485863907.133",
  "currentUp": "21534896194125.68990405400000000000000000000000",
  "markPrice": "47694.29066351"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2574000042.2, after=2574000042.2, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 585914, 'liquidationThreshold': 10000, 'markPrice': 6197346600716, 'liquidationPrice': 6135986733382, 'bankruptcyPrice': 6135986733382, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060606408, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 2153489619412569000000, 'leverage': 10, 'priority': 1, 'timestamp': 1772168582676, 'sequence': 1772168582676, 'remark': 'ACC_LIQ_1772168582676_3'}"
}
```
