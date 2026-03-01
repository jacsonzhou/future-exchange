# E2E Acceptance Suite Report

- Time: 2026-02-27 12:46:40
- DurationSec: 0.01
- Passed: 0
- Failed: 1

| Step | Result | Message |
|------|--------|---------|
| service_check | FAIL | Service check failed |

## Details

### service_check
- Result: FAIL
- Message: Service check failed
- Details:
```json
{
  "error": "Required services are not listening: [('api-gateway', 8082), ('oms-core', 8081), ('match-engine-core', 8083), ('position-snapshot-core', 8086), ('user-core', 8100), ('private-push-core', 8097), ('liquidation-core', 8102)]"
}
```
