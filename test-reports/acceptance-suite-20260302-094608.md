# E2E Acceptance Suite Report

- Time: 2026-03-02 09:46:08
- DurationSec: 117.93
- Passed: 8
- Failed: 4

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| test_state_reset | PASS | Test account state reset to clean baseline |
| maker_state_reset | PASS | Maker account state reset to clean baseline |
| context_snapshot | PASS | Depth empty, fallback bid/ask from default |
| cancel_flow | PASS | Passive order canceled successfully |
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
  "userId": 75,
  "tokenLen": 192
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772415843",
  "makerUserId": 76,
  "tokenLen": 192
}
```

### test_state_reset
- Result: PASS
- Message: Test account state reset to clean baseline
- Details:
```json
{
  "userId": 75,
  "symbol": "BTCUSDT",
  "activeOrderCountBefore": 0,
  "canceledOrders": [],
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
  "userId": 76,
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
- Message: Depth empty, fallback bid/ask from default
- Details:
```json
{
  "bestBid": "69930.00000000",
  "bestAsk": "70070.00000000",
  "depthHasLevels": false,
  "fallbackSource": "default",
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
  "orderId": "286675048436076544",
  "side": "SELL",
  "price": "77077.00000000",
  "cancelResponse": {
    "orderId": "286675048436076544",
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
  "error": "Aggressive open order not FILLED, status=FROZEN, query={'orderId': '286675054769475584', 'clientOrderId': 'acc-open-1772415859194', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '84084', 'quantity': '1', 'filledQuantity': '0', 'status': 'FROZEN', 'reasonCode': 'FROZEN', 'reasonMsg': 'Fund frozen', 'executionMode': 'CFD_DEALER', 'liquiditySource': None, 'referenceTopic': None, 'referenceOffset': None, 'referenceEventTime': None, 'referenceBestBid': None, 'referenceBestAsk': None, 'referenceVwapPrice': None, 'slippageBps': None, 'createTime': 1772415859222}"
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
