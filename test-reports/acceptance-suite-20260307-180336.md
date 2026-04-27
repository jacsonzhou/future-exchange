# E2E Acceptance Suite Report

- Time: 2026-03-07 18:03:36
- DurationSec: 125.44
- Passed: 6
- Failed: 4

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from ticker24h |
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
  "userId": 111,
  "tokenLen": 192
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "r6_maker_1772877627",
  "makerUserId": 112,
  "tokenLen": 192
}
```

### context_snapshot
- Result: PASS
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "67923.70830000",
  "bestAsk": "68059.69170000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
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
  "orderId": "288612154750275584",
  "side": "SELL",
  "price": "74865.66087000",
  "cancelResponse": {
    "orderId": "288612154750275584",
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
  "error": "Aggressive open order not FILLED, status=FROZEN, query={'orderId': '288612184542416896', 'clientOrderId': 'acc-open-1772877706864', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '81671.63004', 'quantity': '0.001', 'filledQuantity': '0', 'status': 'FROZEN', 'reasonCode': 'FROZEN', 'reasonMsg': 'Fund frozen', 'executionMode': 'CFD_DEALER', 'liquiditySource': None, 'referenceTopic': None, 'referenceOffset': None, 'referenceEventTime': None, 'referenceBestBid': None, 'referenceBestAsk': None, 'referenceVwapPrice': None, 'slippageBps': None, 'createTime': 1772877706950}"
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
