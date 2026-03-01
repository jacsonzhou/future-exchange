# E2E Acceptance Suite Report

- Time: 2026-02-27 16:40:33
- DurationSec: 0.43
- Passed: 1
- Failed: 1

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | PASS | Liquidation runtime schema aligned |
| service_check | FAIL | Service check failed |

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
- Result: FAIL
- Message: Service check failed
- Details:
```json
{
  "error": "Required services are not listening: [('liquidation-core', 8102)]"
}
```
