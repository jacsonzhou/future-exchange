# E2E Acceptance Suite Report

- Time: 2026-03-01 12:23:29
- DurationSec: 43.94
- Passed: 6
- Failed: 1

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| test_state_reset | PASS | Test account state reset to clean baseline |
| maker_state_reset | PASS | Maker account state reset to clean baseline |
| context_snapshot | FAIL | Failed to fetch context |

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
      "orderId": "286349105549021184",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "286349105549021184",
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
- Result: FAIL
- Message: Failed to fetch context
- Details:
```json
{
  "error": "Depth empty for BTCUSDT: {'symbol': 'BTCUSDT', 'asks': [], 'bids': [], 'timestamp': 1772339009189}"
}
```
