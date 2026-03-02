# E2E Acceptance Suite Report

- Time: 2026-03-02 10:40:33
- DurationSec: 271.29
- Passed: 7
- Failed: 3

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
- Message: Depth empty, fallback bid/ask from ticker24h
- Details:
```json
{
  "bestBid": "66224.70900000",
  "bestAsk": "66357.29100000",
  "depthHasLevels": false,
  "fallbackSource": "ticker24h",
  "activePosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "0.0010000000000000",
    "entryPrice": "66291.0000000000000000",
    "unrealizedPnl": "0.01695",
    "realizedPnl": "0.0",
    "marginRatio": "20.04601258",
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
  "orderId": "286688314260459520",
  "side": "SELL",
  "price": "72993.02010000",
  "cancelResponse": {
    "orderId": "286688314260459520",
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
  "error": "Aggressive open order not FILLED, status=FROZEN, query={'orderId': '286688487363579904', 'clientOrderId': 'acc-open-1772419061134', 'symbol': 'BTCUSDT', 'side': 'BUY', 'type': 'LIMIT', 'price': '79628.7492', 'quantity': '0.001', 'filledQuantity': '0', 'status': 'FROZEN', 'reasonCode': 'FROZEN', 'reasonMsg': 'Fund frozen', 'executionMode': 'CFD_DEALER', 'liquiditySource': None, 'referenceTopic': None, 'referenceOffset': None, 'referenceEventTime': None, 'referenceBestBid': None, 'referenceBestAsk': None, 'referenceVwapPrice': None, 'slippageBps': None, 'createTime': 1772419061802}"
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close did not reduce target side position, sizeBefore=0.0010000000000000, sizeAfter=0.0010000000000000, positionAfterClose={'symbol': 'BTCUSDT', 'side': 'LONG', 'positionSide': 1, 'size': '0.0010000000000000', 'entryPrice': '66291.0000000000000000', 'unrealizedPnl': 0.01965, 'realizedPnl': 0.0, 'leverage': 10, 'marginRatio': '20.05333985', 'liquidationPrice': '59961.7085427100000000', 'updatedAt': 1772419026514}"
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
  "error": "Command '['docker', 'exec', '-e', 'KAFKA_BROKER=localhost:9092', '-e', 'KAFKA_TOPIC=liquidation-trigger-topic', '-e', 'KAFKA_MESSAGE=BTCUSDT:{\"userId\":104,\"positionId\":56,\"symbol\":\"BTCUSDT\",\"marginMode\":\"CROSS\",\"triggerType\":\"MANUAL_TEST\",\"marginRatio\":200533,\"liquidationThreshold\":10000,\"markPrice\":5936209145728,\"liquidationPrice\":5996170854271,\"bankruptcyPrice\":5996170854271,\"positionSide\":1,\"positionQty\":50000,\"entryPrice\":6629100000000,\"currentMargin\":100000000,\"maintenanceMargin\":5000000,\"unrealizedPnl\":1965000,\"leverage\":10,\"priority\":1,\"timestamp\":1772419211945,\"sequence\":1772419211945,\"remark\":\"ACC_LIQ_1772419211945_56\"}', 'kafka-1', 'sh', '-lc', 'printf \\'%s\\\\n\\' \"$KAFKA_MESSAGE\" | kafka-console-producer --bootstrap-server \"$KAFKA_BROKER\" --topic \"$KAFKA_TOPIC\" --property parse.key=true --property key.separator=: --producer-property acks=1 --producer-property linger.ms=0 --producer-property request.timeout.ms=5000 --producer-property max.block.ms=10000']' timed out after 20 seconds"
}
```
