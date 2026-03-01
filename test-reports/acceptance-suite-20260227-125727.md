# E2E Acceptance Suite Report

- Time: 2026-02-27 12:57:27
- DurationSec: 1.44
- Passed: 2
- Failed: 1

| Step | Result | Message |
|------|--------|---------|
| service_check | PASS | Required services are listening |
| login | PASS | Login success |
| context_snapshot | FAIL | Failed to fetch context |

## Details

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

### context_snapshot
- Result: FAIL
- Message: Failed to fetch context
- Details:
```json
{
  "error": "Depth empty for BTCUSDT: {'symbol': 'BTCUSDT', 'asks': [], 'bids': [['80800.00000000', '0.10000000'], ['68270.00000000', '0.90000000'], ['65100.00000000', '1.00000000'], ['65000.00000000', '1.25000000'], ['61443.00000000', '2.00000000'], ['60000.00000000', '2.00000000'], ['50000.00000000', '1.22000000'], ['40000.00000000', '6.00000000'], ['30000.00000000', '3.00000000'], ['20000.00000000', '1.00000000'], ['10000.00000000', '1.00000000']], 'timestamp': 1772168247767}"
}
```
