# SQL Schema 汇总文档

> 本文档汇总了所有模块的数据库表结构，统一存放在各模块的 `src/main/resources/sql/schema.sql` 中。

---

## 模块清单

| 模块 | 数据库名 | 职责 | 表数量 |
|------|---------|------|--------|
| user-core | exchange_user | 用户注册登录、身份认证 | 5 |
| oms-core | exchange_oms | 订单管理、订单生命周期 | 6 |
| hard-risk-core | exchange_risk | 硬风控、余额检查 | 6 |
| match-engine-core | exchange_match | 撮合引擎、WAL日志 | 6 |
| ledger-core | exchange_ledger | 双录账本、资金结算 | 9 |
| snapshot-account-core | exchange_snapshot | 账户快照 | 5 |
| position-snapshot-core | exchange_snapshot | 持仓快照 | 6 |
| market-price-core | exchange_market | 行情生成、K线计算 | 6 |
| public-push-core | exchange_push | WebSocket推送 | 5 |
| index-price-core | exchange_market | 指数价格计算 | 5 |
| mark-price-core | exchange_market | 标记价格计算 | 3 |
| funding-rate-core | exchange_funding | 资金费率结算 | 6 |
| tp-sl-core | exchange_tpsl | 止盈止损订单 | 4 |
| margin-mode-core | exchange_margin | 保证金模式管理 | 5 |
| liquidation-core | exchange_liquidation | 强平执行 | 5 |
| adl-core | exchange_adl | 自动减仓 | 5 |
| market-maker-core | exchange_mm | 做市商管理 | 6 |
| replay-core | exchange_replay | WAL重放、数据恢复 | 7 |

---

## 各模块详细表结构

### 1. user-core (exchange_user)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_user | 用户主表 | username, password_hash, email, phone, status, kyc_level |
| t_trading_account | 交易账户表 | user_id, account_type, margin_mode, default_leverage |
| t_user_api_key | API密钥表 | user_id, api_key, permissions, ip_whitelist |
| t_login_log | 登录日志表 | user_id, login_ip, success, created_at |
| t_funding_adjustment | 资金调整记录表 | user_id, amount, reason, ledger_entry_id |

### 2. oms-core (exchange_oms)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_order | 订单主表 | user_id, symbol, side, type, price, quantity, status, leverage, margin_mode |
| t_order_event | 订单事件表 | order_id, event_type, event_payload |
| t_order_state_log | 订单状态日志表 | order_id, from_status, to_status, reason_code |
| t_trade | 成交记录表 | trade_id, buy_order_id, sell_order_id, price, quantity |
| t_idempotent_key | 幂等Key表 | user_id, idem_key, order_id |
| t_conditional_order | 条件订单表 | user_id, condition_type, trigger_price, triggered_order_type |

### 3. hard-risk-core (exchange_risk)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_risk_account_snapshot | 账户风险快照表 | user_id, equity, margin_ratio, available_margin, risk_level |
| t_risk_position_snapshot | 持仓风险快照表 | user_id, symbol, liquidation_price, margin_ratio |
| t_risk_user_list | 风控黑白名单表 | user_id, list_type, restrict_trade, restrict_withdraw |
| t_risk_check_log | 风控审计日志表 | order_id, result, reject_reason, required_margin |
| t_risk_symbol_config | 交易对风控配置表 | symbol, max_leverage, min_order_qty, max_order_qty, maintenance_margin_rate |
| t_risk_rate_limit | 用户限流配置表 | user_id, order_limit_per_second, cancel_limit_per_second |

### 4. match-engine-core (exchange_match)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_match_sequence | 撮合序列号表 | symbol, current_seq |
| t_match_trade | 撮合成交记录表 | trade_id, symbol, price, quantity, match_sequence |
| t_orderbook_snapshot | OrderBook快照表 | symbol, last_sequence, bids_json, asks_json |
| t_match_order | 内存订单表 | order_id, symbol, side, price, quantity, status |
| t_match_statistics | 撮合统计表 | symbol, stat_date, total_orders, total_trades |
| t_wal_index | WAL日志索引表 | wal_file_name, start_sequence, end_sequence |

### 5. ledger-core (exchange_ledger)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_global_sequence | 全局序列号表 | seq_name, current_value |
| t_ledger_account | 账户主表 | account_id, user_id, account_type, currency |
| t_ledger_entry | 双录分录表 | entry_id, debit, credit, balance_before, balance_after, business_type, biz_seq |
| t_account_balance | 账户余额快照表 | user_id, available, frozen, position_margin, unrealized_pnl, equity |
| t_position | 持仓表 | position_id, user_id, symbol, side, quantity, entry_price |
| t_fee_record | 手续费记录表 | trade_id, fee_type, fee_amount, fee_rate |
| t_reconciliation_log | 对账记录表 | check_date, user_id, ledger_balance, snapshot_balance, diff_amount |
| t_replay_log | Replay重放记录表 | replay_type, start_biz_seq, end_biz_seq, affected_users |

### 6. snapshot-account-core (exchange_snapshot)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_account_snapshot | 账户快照主表 | user_id, available, frozen, position_margin, equity, last_biz_seq |
| t_account_snapshot_history | 账户快照历史表 | user_id, snapshot_date, available, frozen |
| t_account_change_log | 账户变更流水表 | user_id, change_type, amount, ref_id |
| t_sync_offset | 同步偏移表 | consumer_group, topic, partition_num, current_offset |
| t_snapshot_sync_status | 快照同步状态表 | user_id, sync_status, lag_ms |

### 7. position-snapshot-core (exchange_snapshot)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_position_snapshot | 持仓快照主表 | user_id, symbol, side, size, entry_price, liquidation_price, adl_rank |
| t_position_snapshot_history | 持仓历史表 | user_id, symbol, snapshot_date, size, entry_price |
| t_position_change_log | 持仓变更流水表 | position_id, change_type, size_change, realized_pnl |
| t_cross_position_summary | 全仓账户汇总表 | user_id, total_position_value, margin_ratio, liquidation_status |
| t_position_statistics | 持仓统计表 | symbol, stat_date, long_position_count, short_position_count |

### 8. market-price-core (exchange_market)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_kline | K线历史表 | symbol, interval_val, open_time, open_price, high_price, low_price, close_price, volume |
| t_trade_history | 成交历史表 | trade_id, symbol, price, quantity, trade_time |
| t_ticker_24h | 24小时统计表 | symbol, price_change, last_price, high_price, low_price, volume |
| t_depth_snapshot | 深度快照表 | symbol, last_update_id, bids_json, asks_json |
| t_mark_price_history | 标记价格历史表 | symbol, mark_price, index_price, funding_rate |
| t_symbol_config | 交易对配置表 | symbol, price_precision, quantity_precision, min_qty, max_qty |

### 9. public-push-core (exchange_push)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_ws_connection | WebSocket连接表 | connection_id, user_id, client_ip, status, connected_at |
| t_subscription | 订阅记录表 | connection_id, user_id, channel, symbol, status |
| t_push_message_log | 推送消息日志表 | message_id, channel, symbol, target_connections |
| t_ip_rate_limit | IP限流记录表 | client_ip, connection_count, blocked |
| t_push_statistics | 推送统计表 | stat_date, total_connections, messages_sent |

### 10. index-price-core (exchange_market)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_index_price_config | 指数价格配置表 | symbol, components, update_interval_ms |
| t_index_price | 指数价格历史表 | symbol, price, timestamp |
| t_index_price_component | 指数价格成分表 | symbol, exchange, raw_price, weight |
| t_exchange_data_source | 交易所数据源配置表 | exchange_code, rest_base_url, ws_base_url |
| t_price_anomaly | 价格异常记录表 | symbol, anomaly_type, severity, description |

### 11. mark-price-core (exchange_market)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_mark_price | 标记价格历史表 | symbol, mark_price, index_price, funding_rate |
| t_mark_price_config | 标记价格配置表 | symbol, basis_rate_cap, smoothing_factor |
| t_mark_price_statistics | 标记价格统计表 | symbol, stat_date, avg_mark_price, avg_basis_rate |

### 12. funding-rate-core (exchange_funding)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_funding_rate_config | 资金费率配置表 | symbol, settlement_interval, max_rate, min_rate |
| t_funding_rate_history | 资金费率历史表 | symbol, funding_time, funding_rate, mark_price |
| t_user_funding_fee | 用户资金费用明细表 | user_id, symbol, funding_time, funding_fee |
| t_funding_rate_estimate | 预估资金费率实时表 | symbol, next_funding_time, estimated_rate |
| t_funding_settlement_task | 资金费率结算任务表 | symbol, funding_time, status, processed_users |
| t_funding_statistics | 资金费用统计表 | symbol, stat_date, total_long_fee, total_short_fee |

### 13. tp-sl-core (exchange_tpsl)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_tp_sl_order | 止盈止损订单表 | order_id, position_id, order_type, trigger_price, exec_type, status |
| t_tp_sl_exec_log | TP/SL执行日志表 | tp_sl_order_id, trigger_price, exec_result |
| t_tp_sl_trigger | TP/SL触发记录表 | tp_sl_order_id, trigger_type, trigger_price, status |
| t_user_tpsl_config | 用户TP/SL配置表 | user_id, default_tp_type, default_sl_type, max_tp_sl_orders |

### 14. margin-mode-core (exchange_margin)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_user_margin_config | 用户保证金配置表 | user_id, symbol, default_margin_mode, cross_leverage, isolated_leverage |
| t_position_margin_detail | 仓位保证金详情表 | position_id, margin_mode, isolated_margin, margin_ratio, liquidation_price |
| t_margin_change_log | 保证金变动流水表 | user_id, change_type, margin_mode, amount |
| t_cross_margin_snapshot | 全仓账户风险快照表 | user_id, total_position_value, margin_balance, margin_ratio, risk_level |
| t_margin_mode_switch_log | 保证金模式切换记录表 | user_id, from_mode, to_mode, from_leverage, to_leverage |

### 15. liquidation-core (exchange_liquidation)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_liquidation_execution | 强平执行记录表 | liquidation_id, user_id, position_id, status, insurance_cover, adl_required |
| t_liquidation_event | 强平事件表 | event_id, liquidation_id, event_type, send_status |
| t_liquidation_audit | 强平审计日志表 | audit_id, liquidation_id, operation, before_data, after_data |
| t_bankruptcy_record | 穿仓记录表 | liquidation_id, bankrupt_loss, handle_type, insurance_cover, adl_cover |
| t_liquidation_config | 强平配置表 | symbol, liquidation_fee_rate, insurance_fee_rate |

### 16. adl-core (exchange_adl)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_adl_ranking_queue | ADL排名队列表 | position_id, adl_score, adl_rank, risk_level |
| t_adl_execution | ADL执行记录表 | adl_execution_id, target_user_id, source_user_id, adl_price, adl_qty |
| t_insurance_fund | 保险基金表 | symbol, currency, balance, safe_threshold, warning_threshold, danger_threshold |
| t_insurance_fund_log | 保险基金流水表 | change_type, amount, balance_before, balance_after |
| t_bankruptcy_record | 穿仓处理记录表 | liquidation_id, bankrupt_loss, handle_type, insurance_cover, adl_cover |

### 17. market-maker-core (exchange_mm)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_mm_application | 做市商申请表 | user_id, company_name, status, level |
| t_market_maker | 做市商信息表 | user_id, level, maker_fee_rate, taker_fee_rate, api_limit_per_sec |
| t_mm_performance | 做市商考核指标表 | user_id, symbol, period_date, quote_time_ratio, score, is_qualified |
| t_mm_fee_log | 做市商费率流水表 | trade_id, fee_type, fee_amount, fee_rate |
| t_mm_batch_order | 批量订单记录表 | batch_id, user_id, order_count, success_count |
| t_mm_level_config | 做市商等级配置表 | level, maker_fee_rate, taker_fee_rate, min_quote_time_ratio |

### 18. replay-core (exchange_replay)

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| t_replay_task | 重放任务表 | task_id, task_type, start_biz_seq, end_biz_seq, status, progress_pct |
| t_replay_detail | 重放明细表 | task_id, biz_seq, status, process_time_ms |
| t_wal_file_index | WAL文件索引表 | file_name, start_biz_seq, end_biz_seq, status |
| t_verify_task | 数据校验任务表 | task_id, verify_type, status, match_count, mismatch_count |
| t_verify_result | 数据校验结果表 | task_id, verify_target, is_match, diff_detail |
| t_biz_seq_tracking | 业务序列号追踪表 | seq_name, current_value |
| t_replay_config | 重放配置表 | config_key, config_value |

---

## 核心业务流程

### 注册登录流程
```
t_user -> t_trading_account -> t_account_balance (初始化)
```

### 下单流程
```
t_order (创建) -> t_risk_check_log (风控检查) -> t_match_trade (撮合成交)
    -> t_trade (OMS记录) -> t_ledger_entry (双录分录) 
    -> t_account_balance (更新) -> t_position (更新)
```

### 强平流程
```
t_position (保证金不足) -> t_liquidation_execution (创建强平)
    -> t_bankruptcy_record (如有穿仓) -> t_adl_execution (如需ADL)
    -> t_insurance_fund_log (保险基金变动)
```

---

## 初始化脚本

```bash
# 按顺序执行各模块SQL
mysql -u root -p < user-core/src/main/resources/sql/schema.sql
mysql -u root -p < oms-core/src/main/resources/sql/schema.sql
mysql -u root -p < hard-risk-core/src/main/resources/sql/schema.sql
mysql -u root -p < match-engine-core/src/main/resources/sql/schema.sql
mysql -u root -p < ledger-core/src/main/resources/sql/schema.sql
mysql -u root -p < snapshot-account-core/src/main/resources/sql/schema.sql
mysql -u root -p < position-snapshot-core/src/main/resources/sql/schema.sql
mysql -u root -p < market-price-core/src/main/resources/sql/schema.sql
mysql -u root -p < index-price-core/src/main/resources/sql/schema.sql
mysql -u root -p < mark-price-core/src/main/resources/sql/schema.sql
mysql -u root -p < funding-rate-core/src/main/resources/sql/schema.sql
mysql -u root -p < tp-sl-core/src/main/resources/sql/schema.sql
mysql -u root -p < margin-mode-core/src/main/resources/sql/schema.sql
mysql -u root -p < liquidation-core/src/main/resources/sql/schema.sql
mysql -u root -p < adl-core/src/main/resources/sql/schema.sql
mysql -u root -p < market-maker-core/src/main/resources/sql/schema.sql
mysql -u root -p < replay-core/src/main/resources/sql/schema.sql
```

---

## 注意事项

1. **分库分表**: ledger_entry 表按用户ID分库，按月分表
2. **时间戳统一**: 所有时间戳使用 BIGINT 存储毫秒时间戳
3. **金额精度**: 所有金额使用 DECIMAL(32,16) 或 BIGINT（8位精度）
4. **幂等性**: 关键表使用唯一键保证幂等性
5. **乐观锁**: 使用 version 字段实现乐观锁
