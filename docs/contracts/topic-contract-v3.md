# Topic Contract V3

## 1. Scope

This document freezes the V3 Kafka topic contract for the execution chain.

- Effective phase: B0 + B3 (dual-write, no traffic cutover)
- Compatibility rule: legacy topics must remain available during migration
- Partition strategy: `topic + partition`, key by `symbol` unless explicitly noted

## 2. Shared Topics (V3)

| Topic | Purpose | Producer | Consumer | Key | Recommended Partitions | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| `ex.order.command.v1` | Unified order command bus | `oms-core` | `cfd-dealer-core` / `match-engine-core` | `symbol` | 12 (dev) / 48+ (prod) | 1 day |
| `ex.order.state.v1` | Unified order state bus | `cfd-dealer-core` / `match-engine-core` | `oms-core` / `liquidation-core` | `symbol` | 12 (dev) / 48+ (prod) | 1 day |
| `ex.trade.v1` | Unified trade bus | `cfd-dealer-core` / `match-engine-core` | `ledger-core` / `market-price-core` | `symbol` | 12 (dev) / 48+ (prod) | 7 days |
| `acc.trade.entry.v1` | Unified ledger entry bus | `ledger-core` | `snapshot-account-core` / `position-snapshot-core` | `symbol` | 12 (dev) / 48+ (prod) | 7 days |

## 3. Legacy Topics (must keep during migration)

| Stage | Legacy Topic Pattern | Status |
| --- | --- | --- |
| Order command | `cfd-order-command-{symbol}` | Keep writable/consumable |
| Order state | `order-state-{symbol}` | Keep writable/consumable |
| Trade | `trade-event-{symbol}` | Keep writable/consumable |
| Ledger entry | `trade-entry-{symbol}` / `account-entry-SYSTEM` | Keep writable/consumable |

## 4. Topic Mode Contract

`execution.topic-mode` controls producer behavior:

| Mode | Legacy Topics | Shared Topics | Notes |
| --- | --- | --- | --- |
| `LEGACY_ONLY` | Write | Do not write | Rollback mode |
| `DUAL_WRITE` | Write | Write | Default for B0+B3 |
| `SHARED_ONLY` | Do not write | Write | For later cutover batches |

## 5. Constraints

- Do not delete legacy topics before B7 cutover completion.
- New shared topic events must follow `docs/contracts/event-envelope-v1.md`.
- Producers must keep deterministic keys:
  - `ex.order.command.v1`: `symbol`
  - `ex.order.state.v1`: `symbol`
  - `ex.trade.v1`: `symbol`
  - `acc.trade.entry.v1`: `symbol`

## 6. Validation Baseline

- Infrastructure: `init_kafka.sh` / `init_kafka_new.sh` create both legacy + shared topics.
- Gate script: `scripts/cfd/test_c1_schema_contract.sh` validates topic coexistence.
- Functional scripts: `test_c3_oms_route.sh`, `test_c4_market_fill.sh`, `test_c6_ledger_balance.sh` validate no regression on legacy path under dual-write.
