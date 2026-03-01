# E2E Acceptance Suite Report

- Time: 2026-03-01 11:19:07
- DurationSec: 0.16
- Passed: 0
- Failed: 1

| Step | Result | Message |
|------|--------|---------|
| runtime_schema_fix | FAIL | Runtime schema fix failed |

## Details

### runtime_schema_fix
- Result: FAIL
- Message: Runtime schema fix failed
- Details:
```json
{
  "error": "MySQL command failed (exit=1), container=web3-mysql, stdout=, stderr=permission denied while trying to connect to the docker API at unix:///Users/zhoufan/.docker/run/docker.sock\n"
}
```
