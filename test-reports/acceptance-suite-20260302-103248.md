# E2E Acceptance Suite Report

- Time: 2026-03-02 10:32:48
- DurationSec: 92.06
- Passed: 8
- Failed: 2

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from ticker24h |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | FAIL | Fill flow failed |
| manual_close_flow | PASS | Manual close order filled and position reduced |
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
  "userId": 102,
  "tokenLen": 193
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772418608",
  "makerUserId": 103,
  "tokenLen": 193
}
```

### context_snapshot
- Result: PASS
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "66097.83600000",
  "bestAsk": "66230.16400000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0010000000000000",
    "entryPrice": "66164.0000000000000000",
    "unrealizedPnl": "-0.00335",
    "realizedPnl": "0.0",
    "marginRatio": "19.99088582",
    "liquidationPrice": "59846.8341708500000000"
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
  "orderId": "286686906664620032",
  "side": "SELL",
  "price": "72853.18040000",
  "cancelResponse": {
    "orderId": "286686906664620032",
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
  "error": "Aggressive open order not FILLED, status=FROZEN, query={'orderId': '286686937907990528', 'clientOrderId': 'acc-open-1772418692283', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '79476.1968', 'quantity': '0.001', 'filledQuantity': '0', 'status': 'FROZEN', 'reasonCode': 'FROZEN', 'reasonMsg': 'Fund frozen', 'executionMode': 'CFD_DEALER', 'liquiditySource': None, 'referenceTopic': None, 'referenceOffset': None, 'referenceEventTime': None, 'referenceBestBid': None, 'referenceBestAsk': None, 'referenceVwapPrice': None, 'slippageBps': None, 'createTime': 1772418692383}"
}
```

### manual_close_flow
- Result: PASS
- Message: Manual close order filled and position reduced
- Details:
```json
{
  "orderId": "286687143806373888",
  "closeSide": "SELL",
  "closePrice": "52878.26880000",
  "sizeAfterFill": "0.0020000000000000",
  "sizeAfterClose": "0.0015000000000000",
  "submitMode": "reduceOnly=true",
  "orderStatusObserved": "FILLED",
  "seedOrderId": "286687137389088768",
  "seedOrderSide": "BUY",
  "seedOrderStatus": "CANCELED",
  "seedFilledQuantity": "0"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "66164.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "59846.8341708500000000",
  "expectedLiquidationPrice": "59846.83417085",
  "diff": "0E-16",
  "tolerance": "59.84683417085"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Seeded counterparty depth not enough for single-shot liquidation, required=0.0005, observed=0, takerSide=SELL, seedPrice=66097.83600001"
}
```
