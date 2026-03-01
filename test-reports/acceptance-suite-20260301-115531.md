# E2E Acceptance Suite Report

- Time: 2026-03-01 11:55:31
- DurationSec: 142.0
- Passed: 7
- Failed: 5

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
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | FAIL | Liquidation price formula check failed |
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
      "orderId": "286340414498148352",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "286340414498148352",
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
  "bestBid": "66603.98448000",
  "bestAsk": "67276.75200000",
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
  "error": "Cancel verification failed, status=REJECTED, query={'orderId': '286345321208877056', 'clientOrderId': 'acc-cancel-1772337244582', 'symbol': 'BTCUSDT', 'side': 'SELL', 'type': 'LIMIT', 'price': '74004.4272', 'quantity': '1', 'filledQuantity': '0', 'status': 'REJECTED', 'reasonCode': 'FREEZE_FAILED', 'reasonMsg': 'Freeze failed: Unexpected end of file from server executing POST http://ledger-core/internal/ledger/freeze', 'createTime': 1772337244615}, cancelResp={'orderId': None, 'status': None, 'success': False, 'errorCode': 'OMS_2002', 'errorMessage': '订单状态不允许撤单'}"
}
```

### fill_flow
- Result: FAIL
- Message: Fill flow failed
- Details:
```json
{
  "error": "Seed order failed before taker submit, status=REJECTED, query={'orderId': '286345392440741888', 'clientOrderId': 'acc-seed-open-1772337261586', 'symbol': 'BTCUSDT', 'side': 'SELL', 'type': 'LIMIT', 'price': '80732.1024', 'quantity': '1', 'filledQuantity': '0', 'status': 'REJECTED', 'reasonCode': 'FREEZE_FAILED', 'reasonMsg': 'Freeze failed: Unexpected end of file from server executing POST http://ledger-core/internal/ledger/freeze', 'createTime': 1772337261598}"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "No active position after fill"
}
```

### liquidation_price_formula
- Result: FAIL
- Message: Liquidation price formula check failed
- Details:
```json
{
  "error": "No active position for liquidation price check"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "No active position for liquidation trigger"
}
```
