# E2E Acceptance Suite Report

- Time: 2026-03-07 18:18:47
- DurationSec: 218.4
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
| fill_flow | PASS | Aggressive order matched with seeded counterparty |
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
  "userId": 18,
  "tokenLen": 176
}
```

### maker_login
- Result: PASS
- Message: Maker account login success
- Details:
```json
{
  "makerUsername": "zhoufan2",
  "makerUserId": 23,
  "tokenLen": 176
}
```

### context_snapshot
- Result: PASS
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "67862.26980000",
  "bestAsk": "67998.13020000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "346.1860000000000000",
    "entryPrice": "66898.2564183300000000",
    "unrealizedPnl": "-403823.74706401065",
    "realizedPnl": "-286.44773372867",
    "marginRatio": "16.22964034",
    "liquidationPrice": "73221.9721991700000000"
  },
  "positionCount": 5
}
```

### cancel_flow
- Result: PASS
- Message: Passive order canceled successfully
- Details:
```json
{
  "orderId": "288615749927309312",
  "side": "BUY",
  "price": "61076.04282000",
  "cancelResponse": {
    "orderId": "288615749927309312",
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
- Message: Aggressive order matched with seeded counterparty
- Details:
```json
{
  "orderId": "288616043767664640",
  "side": "SELL",
  "price": "54289.81584000",
  "qtyInt": 100000,
  "finalStatus": "FILLED",
  "seedOrderId": "288616037073555456",
  "seedOrderSide": "BUY",
  "seedOrderStatus": "CANCELED",
  "preSize": "346.1860000000000000",
  "seedFilledQuantity": "0",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "positionSide": 2,
    "size": "354.2660000000000000",
    "entryPrice": "66921.7926993700000000",
    "unrealizedPnl": "-357244.4207649876",
    "realizedPnl": "-286.44773372867",
    "marginRatio": "16.73415350",
    "liquidationPrice": "73247.7333027900000000"
  }
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close did not reduce target side position, sizeBefore=354.2660000000000000, sizeAfter=374.4660000000000000, positionAfterClose={'symbol': 'BTCUSDT', 'side': 'SHORT', 'positionSide': 2, 'size': '374.4660000000000000', 'entryPrice': '66976.2004893200000000', 'unrealizedPnl': -357614.8467662969, 'realizedPnl': -286.44773372867, 'leverage': 10, 'marginRatio': '16.90716648', 'liquidationPrice': '73307.2841176600000000', 'updatedAt': 1772878649307}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "SHORT",
  "entryPrice": "66976.2004893200000000",
  "leverage": "10",
  "actualLiquidationPrice": "73307.2841176600000000",
  "expectedLiquidationPrice": "73307.28411766",
  "diff": "0E-16",
  "tolerance": "73.30728411766"
}
```

### liquidation_trigger_execution
- Result: FAIL
- Message: Liquidation trigger stage failed
- Details:
```json
{
  "error": "Liquidation trigger did not reduce position size within timeout, before=374.466, after=592.626, event={'userId': 18, 'positionId': 30, 'symbol': 'BTCUSDT', 'marginMode': 'CROSS', 'triggerType': 'MANUAL_TEST', 'marginRatio': 165901, 'liquidationThreshold': 10000, 'markPrice': 7404035695884, 'liquidationPrice': 7330728411766, 'bankruptcyPrice': 7330728411766, 'positionSide': 2, 'positionQty': 50000, 'entryPrice': 6697620048932, 'currentMargin': 100000000, 'maintenanceMargin': 5000000, 'unrealizedPnl': -39485549046630, 'leverage': 10, 'priority': 1, 'timestamp': 1772878667267, 'sequence': 1772878667267, 'remark': 'ACC_LIQ_1772878667267_30'}"
}
```
