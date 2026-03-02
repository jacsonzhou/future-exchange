# E2E Acceptance Suite Report

- Time: 2026-03-02 10:24:12
- DurationSec: 120.49
- Passed: 6
- Failed: 4

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from ticker24h |
| cancel_flow | FAIL | Cancel flow failed |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | FAIL | Manual close flow failed |
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
  "userId": 98,
  "tokenLen": 192
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772418093",
  "makerUserId": 99,
  "tokenLen": 192
}
```

### context_snapshot
- Result: PASS
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "6600592800000.00000000",
  "bestAsk": "6613807200000.00000000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0010000000000000",
    "entryPrice": "66072.0000000000000000",
    "unrealizedPnl": "0.01315",
    "realizedPnl": "0.0",
    "marginRatio": "20.03581743",
    "liquidationPrice": "59763.6180904500000000"
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
  "error": "Order submit failed. gateway={'error': 'timed out'}, gatewayErr=timed out, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'error': 'timed out'}, gatewayErr=timed out, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'error': 'timed out'}, gatewayErr=timed out, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "66072.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "59763.6180904500000000",
  "expectedLiquidationPrice": "59763.61809045",
  "diff": "0E-16",
  "tolerance": "59.76361809045"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Order submit failed. gateway={'error': 'timed out'}, gatewayErr=timed out, oms={'orderId': None, 'status': None, 'clientOrderId': None, 'success': False, 'errorCode': 'OMS_9001', 'errorMessage': '系统异常'}, omsErr=None"
}
```
