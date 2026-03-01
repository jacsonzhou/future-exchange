# E2E Acceptance Suite Report

- Time: 2026-02-27 13:08:43
- DurationSec: 0.03
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
  "error": "Required services are not listening: [('oms-core', 8081), ('match-engine-core', 8083)]"
}
```
