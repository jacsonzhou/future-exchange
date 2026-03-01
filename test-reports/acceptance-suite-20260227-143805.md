# E2E Acceptance Suite Report

- Time: 2026-02-27 14:38:05
- DurationSec: 235.97
- Passed: 10
- Failed: 3

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| maker_login | PASS | Maker account login success |
| test_state_reset | PASS | Test account state reset to clean baseline |
| maker_state_reset | PASS | Maker account state reset to clean baseline |
| context_snapshot | PASS | Fetched positions and orderbook depth |
| cancel_flow | PASS | Passive order canceled successfully |
| fill_flow | PASS | Aggressive order matched with seeded counterparty |
| manual_close_flow | FAIL | Manual close flow failed |
| liquidation_price_formula | PASS | Liquidation price formula matched |
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
  "activeOrderCountBefore": 2,
  "canceledOrders": [
    {
      "orderId": "285659175667634176",
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
      "orderId": "285659145657389056",
      "finalStatus": "CANCELED",
      "cancelResponse": {
        "orderId": "285659145657389056",
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
  "orderId": "285661218507591680",
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
- Result: PASS
- Message: Aggressive order matched with seeded counterparty
- Details:
```json
{
  "orderId": "285661267610308608",
  "side": "BUY",
  "price": "824160.00000000",
  "qtyInt": 100000000,
  "finalStatus": "FILLED",
  "seedOrderId": "285661246684925952",
  "seedOrderSide": "SELL",
  "seedOrderStatus": "FILLED",
  "preSize": "0",
  "seedFilledQuantity": "1",
  "postFillPosition": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "positionSide": 1,
    "size": "1.0000000000000000",
    "entryPrice": "824160.0000000000000000",
    "unrealizedPnl": "-774157.76873189",
    "realizedPnl": "0.0",
    "marginRatio": "-2766.84360353",
    "liquidationPrice": "745471.3567839200000000"
  }
}
```

### manual_close_flow
- Result: FAIL
- Message: Manual close flow failed
- Details:
```json
{
  "error": "Manual close did not reduce target side position, sizeBefore=1.0000000000000000, sizeAfter=1.0000000000000000, positionAfterClose={'symbol': 'BTCUSDT', 'side': 'LONG', 'positionSide': 1, 'size': '1.0000000000000000', 'entryPrice': '824160.0000000000000000', 'unrealizedPnl': -774158.94339617, 'realizedPnl': 0.0, 'leverage': 10, 'marginRatio': '-2766.91330296', 'liquidationPrice': '745471.3567839200000000', 'updatedAt': 1772174182424}"
}
```

### liquidation_price_formula
- Result: PASS
- Message: Liquidation price formula matched
- Details:
```json
{
  "side": "LONG",
  "entryPrice": "824160.0000000000000000",
  "leverage": "10",
  "actualLiquidationPrice": "745471.3567839200000000",
  "expectedLiquidationPrice": "745471.35678392",
  "diff": "0E-16",
  "tolerance": "745.47135678392"
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
      "stdout_tail": "[14:36:25] Login\n[14:36:29] Login ok, userId=38\n[14:36:29] Selected position: symbol=BTCUSDT, side=LONG, size=1.0000000000000000, entry=824160.0000000000000000, up=-774160.09471757\n[14:36:30] Target mark price=50999.90338808 (PRICE_MOVE_BPS=200)",
      "stderr_tail": "_MESSAGE=BTCUSDT:{\"eventType\":\"MARK_PRICE_UPDATE\",\"eventTime\":1772174197384,\"data\":{\"markPriceId\":\"manual-mark-1772174197384\",\"symbol\":\"BTCUSDT\",\"markPrice\":\"50999.90338808\",\"indexPrice\":\"50999.90338808\",\"timestamp\":1772174197384}}', 'kafka-1', 'sh', '-lc', 'printf \\'%s\\\\n\\' \"$KAFKA_MESSAGE\" | kafka-console-producer --bootstrap-server \"$KAFKA_BROKER\" --topic \"$KAFKA_TOPIC\" --property parse.key=true --property key.separator=: --producer-property acks=1 --producer-property linger.ms=0 --producer-property request.timeout.ms=5000 --producer-property max.block.ms=10000']' timed out after 20 seconds"
    },
    {
      "attempt": 2,
      "returncode": 1,
      "baselineUp": null,
      "currentUp": null,
      "markPrice": null,
      "stdout_tail": "[14:37:07] Login\n[14:37:09] Login ok, userId=38\n[14:37:09] Selected position: symbol=BTCUSDT, side=LONG, size=1.0000000000000000, entry=824160.0000000000000000, up=-774162.96825423\n[14:37:09] Target mark price=50996.97238069 (PRICE_MOVE_BPS=200)",
      "stderr_tail": "_MESSAGE=BTCUSDT:{\"eventType\":\"MARK_PRICE_UPDATE\",\"eventTime\":1772174234726,\"data\":{\"markPriceId\":\"manual-mark-1772174234726\",\"symbol\":\"BTCUSDT\",\"markPrice\":\"50996.97238069\",\"indexPrice\":\"50996.97238069\",\"timestamp\":1772174234726}}', 'kafka-1', 'sh', '-lc', 'printf \\'%s\\\\n\\' \"$KAFKA_MESSAGE\" | kafka-console-producer --bootstrap-server \"$KAFKA_BROKER\" --topic \"$KAFKA_TOPIC\" --property parse.key=true --property key.separator=: --producer-property acks=1 --producer-property linger.ms=0 --producer-property request.timeout.ms=5000 --producer-property max.block.ms=10000']' timed out after 20 seconds"
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
  "error": "Command '['docker', 'exec', '-e', 'KAFKA_BROKER=localhost:9092', '-e', 'KAFKA_TOPIC=liquidation-trigger-topic', '-e', 'KAFKA_MESSAGE=BTCUSDT:{\"userId\":38,\"positionId\":8,\"symbol\":\"BTCUSDT\",\"marginMode\":\"CROSS\",\"triggerType\":\"MANUAL_TEST\",\"marginRatio\":-27669941,\"liquidationThreshold\":10000,\"markPrice\":73801664321608,\"liquidationPrice\":74547135678392,\"bankruptcyPrice\":74547135678392,\"positionSide\":1,\"positionQty\":50000000,\"entryPrice\":82416000000000,\"currentMargin\":100000000,\"maintenanceMargin\":5000000,\"unrealizedPnl\":-77416030492317,\"leverage\":10,\"priority\":1,\"timestamp\":1772174265374,\"sequence\":1772174265374,\"remark\":\"ACC_LIQ_1772174265374_8\"}', 'kafka-1', 'sh', '-lc', 'printf \\'%s\\\\n\\' \"$KAFKA_MESSAGE\" | kafka-console-producer --bootstrap-server \"$KAFKA_BROKER\" --topic \"$KAFKA_TOPIC\" --property parse.key=true --property key.separator=: --producer-property acks=1 --producer-property linger.ms=0 --producer-property request.timeout.ms=5000 --producer-property max.block.ms=10000']' timed out after 20 seconds"
}
```
