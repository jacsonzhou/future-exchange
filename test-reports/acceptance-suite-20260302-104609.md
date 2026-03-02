# E2E Acceptance Suite Report

- Time: 2026-03-02 10:46:09
- DurationSec: 200.16
- Passed: 7
- Failed: 3

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| context_snapshot | PASS | Depth empty, fallback bid/ask from default |
| cancel_flow | PASS | Passive order canceled successfully |
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
  "userId": 104,
  "tokenLen": 193
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "cfd_maker_1772418908",
  "makerUserId": 105,
  "tokenLen": 193
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
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0010000000000000",
    "entryPrice": "66291.0000000000000000",
    "unrealizedPnl": "0.01975",
    "realizedPnl": "0.0",
    "marginRatio": "20.05361122",
    "liquidationPrice": "59961.7085427100000000"
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
  "orderId": "286689962332524544",
  "side": "SELL",
  "price": "77077.00000000",
  "cancelResponse": {
    "orderId": "286689962332524544",
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
  "error": "Aggressive open order not FILLED, status=FROZEN, query={'orderId': '286689965247565824', 'clientOrderId': 'acc-open-1772419414145', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '84084', 'quantity': '0.001', 'filledQuantity': '0', 'status': 'FROZEN', 'reasonCode': 'FROZEN', 'reasonMsg': 'Fund frozen', 'executionMode': 'CFD_DEALER', 'liquiditySource': None, 'referenceTopic': None, 'referenceOffset': None, 'referenceEventTime': None, 'referenceBestBid': None, 'referenceBestAsk': None, 'referenceVwapPrice': None, 'slippageBps': None, 'createTime': 1772419414157}"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close did not reduce target side position, sizeBefore=0.0010000000000000, sizeAfter=0.0010000000000000, positionAfterClose={'symbol': 'BTCUSDT', 'side': 'LONG', 'positionSide': 1, 'size': '0.0010000000000000', 'entryPrice': '66291.0000000000000000', 'unrealizedPnl': 0.01975, 'realizedPnl': 0.0, 'leverage': 10, 'marginRatio': '20.05361122', 'liquidationPrice': '59961.7085427100000000', 'updatedAt': 1772419319493}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "66291.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "59961.7085427100000000",
  "expectedLiquidationPrice": "59961.70854271",
  "diff": "0E-16",
  "tolerance": "59.96170854271"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=0.001, after=0.001, event={'userId': 104, 'positionId': 56, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 200536, 'liquidationThreshold': 10000, 'markPrice': 5936209145728, 'liquidationPrice': 5996170854271, 'bankruptcyPrice': 5996170854271, 'positionSide': 1, 'positionQty': 50000, 'entryPrice': 6629100000000, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': 1975000, 'leverage': 10, 'priority': 1, 'timestamp': 1772419522503, 'sequence': 1772419522503, 'remark': 'ACC_LIQ_1772419522503_56'}"
}
```
