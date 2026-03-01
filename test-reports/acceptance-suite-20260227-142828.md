# E2E Acceptance Suite Report

- Time: 2026-02-27 14:28:28
- DurationSec: 144.28
- Passed: 6
- Failed: 5

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| test_state_reset | PASS | Test account state reset to clean baseline |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | FAIL | Liquidation price formula check failed |
| pnl_push | FAIL | PNL push script failed after 2 attempt(s) |
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

### test_state_reset
- Result: PASS
- Message: Test account state reset to clean baseline
- Details:
```json
{
  "userId": 38,
  "symbol": "BTCUSDT",
  "activeOrderCountBefore": 5,
  "canceledOrders": [
    {
      "orderId": "285653580625481728",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": null,
        "status": null,
        "success": false,
        "errorCode": "OMS_9003",
        "errorMessage": "乐观锁冲突"
      }
    },
    {
      "orderId": "285646862881394688",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "285646862881394688",
        "status": "CANCELED",
        "success": true,
        "errorCode": null,
        "errorMessage": null
      }
    },
    {
      "orderId": "285635804603092992",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": null,
        "status": null,
        "success": false,
        "errorCode": "OMS_2002",
        "errorMessage": "订单状态不允许撤单"
      }
    },
    {
      "orderId": "285633895842451456",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": null,
        "status": null,
        "success": false,
        "errorCode": "OMS_2002",
        "errorMessage": "订单状态不允许撤单"
      }
    },
    {
      "orderId": "284987508515672064",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "284987508515672064",
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

### context_snapshot
- Result: PASS
- Message: Fetched positions and orderbook depth
- Details:
```json
{
  "bestBid": "680000.00000000",
  "bestAsk": "686800.00000000",
  "activePosition": null,
  "positionCount": 0
}
```

### cancel_flow
- Result: PASS
- Message: Passive order canceled successfully
- Details:
```json
{
  "orderId": "285659145657389056",
  "side": "SELL",
  "price": "755480.00000000",
  "cancelResponse": {
    "orderId": null,
    "status": null,
    "success": false,
    "errorCode": "OMS_9003",
    "errorMessage": "乐观锁冲突"
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
  "error": "Aggressive open order not FILLED, status=NEW, query={'orderId': '285659175667634176', 'clientOrderId': 'acc-open-1772173654572', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '693668', 'quantity': '1', 'filledQuantity': '0', 'status': 'NEW', 'reasonCode': 'MATCH_ENGINE_REPORT', 'reasonMsg': 'Match engine reported: NEW', 'createTime': 1772173654770}"
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
      "stdout_tail": "[14:28:23] Login\n[14:28:25] Login ok, userId=38",
      "stderr_tail": "FAIL: No active position found for BTCUSDT. Create a position first, then retry."
    },
    {
      "attempt": 2,
      "returncode": 1,
      "baselineUp": null,
      "currentUp": null,
      "markPrice": null,
      "stdout_tail": "[14:28:27] Login\n[14:28:27] Login ok, userId=38",
      "stderr_tail": "FAIL: No active position found for BTCUSDT. Create a position first, then retry."
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
  "error": "No active position for liquidation trigger"
}
```
