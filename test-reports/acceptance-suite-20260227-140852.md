# E2E Acceptance Suite Report

- Time: 2026-02-27 14:08:52
- DurationSec: 260.21
- Passed: 6
- Failed: 3

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | PASS | Aggressive order matched |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | PASS | Liquidation price formula matched |
| pnl_push | FAIL | PNL push script failed after 2 attempt(s) |
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
    "size": "2772000049.3000000000000000",
    "entryPrice": "56060.6060998100000000",
    "unrealizedPnl": "16799828614781.705",
    "realizedPnl": "-91312.8726606796",
    "marginRatio": "46.66636110",
    "liquidationPrice": "61359.8673729300000000"
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
  "orderId": "285653479966380032",
  "side": "BUY",
  "price": "72712.72800000",
  "cancelResponse": {
    "orderId": "285653479966380032",
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
  "orderId": "285653513445314560",
  "side": "SELL",
  "price": "79984.00080000",
  "qtyInt": 100000000,
  "finalStatus": "PARTIALLY_FILLED",
  "filledQuantity": "0.1",
  "note": "Partial fill accepted on thin book; residual canceled"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close did not reduce target side position, sizeBefore=2772000049.3000000000000000, sizeAfter=2772000049.3000000000000000, positionAfterClose={'symbol': 'BTCUSDT', 'side': 'SHORT', 'positionSide': 2, 'size': '2772000049.3000000000000000', 'entryPrice': '56060.6060998100000000', 'unrealizedPnl': 16806905222106.764, 'realizedPnl': -91312.8726606796, 'leverage': 10, 'marginRatio': '46.67895596', 'liquidationPrice': '61359.8673729300000000', 'updatedAt': 1772172369629}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "56060.6060998100000000",
  "leverage": "10",
  "actualLiquidationPrice": "61359.8673729300000000",
  "expectedLiquidationPrice": "61359.86737293",
  "diff": "0E-16",
  "tolerance": "61.35986737293"
}
```

### pnl_push
- Result: FAIL
- Message: PNL push script failed after 2 attempt(s)
- Details:
```json
{
  "attempts": [
    {
      "attempt": 1,
      "returncode": 1,
      "baselineUp": null,
      "currentUp": null,
      "markPrice": null,
      "stdout_tail": "[14:06:10] Login\n[14:06:10] Login ok, userId=38\n[14:06:10] Selected position: symbol=BTCUSDT, side=SHORT, size=2772000049.3000000000000000, entry=56060.6060998100000000, up=16808971442447.35\n[14:06:10] Target mark price=48996.82842203 (PRICE_MOVE_BPS=200)",
      "stderr_tail": "Kafka inject timeout: Command '['docker', 'exec', '-i', 'kafka-1', 'kafka-console-producer', '--bootstrap-server', 'localhost:9092', '--topic', 'mark-price-update', '--property', 'parse.key=true', '--property', 'key.separator=:']' timed out after 30 seconds"
    },
    {
      "attempt": 2,
      "returncode": 1,
      "baselineUp": null,
      "currentUp": null,
      "markPrice": null,
      "stdout_tail": "[14:06:57] Login\n[14:06:58] Login ok, userId=38\n[14:06:58] Selected position: symbol=BTCUSDT, side=SHORT, size=2772000049.3000000000000000, entry=56060.6060998100000000, up=16803982003356.373\n[14:06:58] Target mark price=48998.59236511 (PRICE_MOVE_BPS=200)",
      "stderr_tail": "Kafka inject timeout: Command '['docker', 'exec', '-i', 'kafka-1', 'kafka-console-producer', '--bootstrap-server', 'localhost:9092', '--topic', 'mark-price-update', '--property', 'parse.key=true', '--property', 'key.separator=:']' timed out after 30 seconds"
    }
  ]
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=2772000049.3, after=2772000049.3, event={'userId': 38, 'positionId': 3, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 466755, 'liquidationThreshold': 10000, 'markPrice': 6197346604666, 'liquidationPrice': 6135986737293, 'bankruptcyPrice': 6135986737293, 'positionSide': 2, 'positionQty': 50000000, 'entryPrice': 5606060609981, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1680498537426273800000, 'leverage': 10, 'priority': 1, 'timestamp': 1772172468377, 'sequence': 1772172468377, 'remark': 'ACC_LIQ_1772172468377_3'}"
}
```
