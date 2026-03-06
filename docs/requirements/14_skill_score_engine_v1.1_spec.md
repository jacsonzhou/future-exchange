# Skill Score 引擎执行规范（v1.1）

## 1. 文档信息
- 文档版本: `v1.1`
- 创建日期: `2026-03-04`
- 对齐文档:
  - `docs/requirements/12_agent_native_growth_prd_v1.0.md`
  - `docs/requirements/13_agent_training_backend_contract_and_event_flow_v1.0.md`
- 目标: 将“训练闭环”升级为“可证明成长闭环”，支持 HUMAN 与 AGENT 同尺评分

## 2. 本轮 Shadow-Agent 体验结论（实测）

### 2.1 实测结论
1. 决策闭环通路有效:
   - `preview -> validate -> adopt -> replay` 可连续执行。
2. 行为训练有效:
   - 能强制形成“先校验再执行”的流程纪律。
3. 价值尚未完全释放:
   - 当前 `score/profile` 偏静态，缺少后验收益与执行质量回流后的动态评分。

### 2.2 关键缺口
1. 缺少后验事实层（decision 对应的持仓/平仓结果）。
2. 缺少评分解释层（每个维度为何升降、哪些行为贡献了增量）。
3. 缺少版本对照层（同用户/同Agent 不同策略版本的增量证明）。

## 3. 评分目标与边界

### 3.1 评分目标
1. 能回答“本周是否变强”。
2. 能回答“是收益变强，还是纪律/执行变强”。
3. 能回答“人类和Agent在同一把尺子下谁更稳”。

### 3.2 非目标（v1.1 不做）
1. 不直接评价绝对资金规模。
2. 不将一次性暴利直接映射为高分。
3. 不接入真实下单密钥与交易所账户托管。

## 4. Skill Score 计算模型（v1.1）

### 4.1 总分公式
`SkillScore = 0.30*RAR + 0.20*DDC + 0.15*EXEC + 0.15*CONS + 0.10*RISK + 0.10*REPLAY - PENALTY`

分值范围统一 `0~100`，最终结果 `clamp(0, 100)`。

### 4.2 维度定义与计算

1. `RAR`（风险调整收益）
   - 输入指标:
     - `net_r`: 周净R收益
     - `profit_factor`: 盈亏比
     - `win_rate_pct`: 胜率
   - 计算:
     - `rar_raw = 50 + 25*net_r_norm + 15*profit_factor_norm + 10*win_rate_norm`
     - `RAR = clamp(rar_raw, 0, 100)`

2. `DDC`（回撤控制）
   - 输入指标:
     - `max_drawdown_pct`
     - `ulcer_index`
     - `recovery_hours`
   - 计算:
     - `ddc_raw = 100 - 1.8*max_drawdown_pct - 2.5*ulcer_index - 0.08*recovery_hours`
     - `DDC = clamp(ddc_raw, 0, 100)`

3. `EXEC`（执行质量）
   - 输入指标:
     - `avg_entry_slippage_bps`
     - `avg_exit_slippage_bps`
     - `plan_drift_pct`（计划参数偏离率）
   - 计算:
     - `exec_raw = 100 - 0.45*avg_entry_slippage_bps - 0.35*avg_exit_slippage_bps - 0.7*plan_drift_pct`
     - `EXEC = clamp(exec_raw, 0, 100)`

4. `CONS`（稳定性）
   - 输入指标:
     - `daily_pnl_std_r`
     - `scenario_score_std`（跨场景分数标准差）
   - 计算:
     - `cons_raw = 100 - 14*daily_pnl_std_r - 18*scenario_score_std`
     - `CONS = clamp(cons_raw, 0, 100)`

5. `RISK`（风险纪律）
   - 输入指标:
     - `guardrail_violation_count`
     - `stop_loss_missing_rate_pct`
     - `risk_exposure_overshoot_pct`
   - 计算:
     - `risk_raw = 100 - 8*guardrail_violation_count - 0.9*stop_loss_missing_rate_pct - 0.8*risk_exposure_overshoot_pct`
     - `RISK = clamp(risk_raw, 0, 100)`

6. `REPLAY`（复盘闭环）
   - 输入指标:
     - `replay_completion_rate_pct`
     - `action_close_rate_pct`（复盘行动闭环率）
   - 计算:
     - `replay_raw = 20 + 0.45*replay_completion_rate_pct + 0.35*action_close_rate_pct`
     - `REPLAY = clamp(replay_raw, 0, 100)`

7. `PENALTY`（惩罚）
   - `critical_violation_count * 20 + bypass_policy_count * 30 + manual_override_without_reason_count * 5`
   - 上限 `40`，且 `critical_violation_count > 0` 时 `scoreStatus` 最低降为 `TEMP`。

### 4.3 样本门槛与评分状态
1. `effectiveSampleSize < 20` 或 `activeDays < 5`:
   - `scoreStatus = TEMP`
2. `effectiveSampleSize >= 20` 且无 critical 违规:
   - `scoreStatus = FORMAL`
3. `FORMAL` 才可进入公开榜单。

## 5. 数据落库设计（MySQL）

> 表前缀遵循 `t_` 规范，金额与价格统一 8 位精度 long 或 decimal 表示。

### 5.1 新增表清单（v1.1）
1. `t_agent_decision_fact`
2. `t_agent_execution_fact`
3. `t_agent_replay_fact`
4. `t_agent_score_snapshot`
5. `t_agent_score_dimension_snapshot`
6. `t_agent_score_event`

### 5.2 字段字典（核心）

#### t_agent_decision_fact
1. `decision_id`: 决策唯一ID
2. `subject_type`: `HUMAN/AGENT`
3. `subject_id`: 用户ID或Agent标识
4. `strategy_version`: 策略版本
5. `symbol`, `interval`, `action`
6. `confidence_pct`, `risk_exposure_pct`
7. `status`: `PREVIEWED/VALIDATED/REJECTED/ADOPTED/EXPIRED`
8. `created_at`, `updated_at`

#### t_agent_execution_fact
1. `decision_id`, `paper_order_id`
2. `symbol`, `side`
3. `entry_price_plan`, `entry_price_exec`, `exit_price_exec`
4. `entry_slippage_bps`, `exit_slippage_bps`
5. `plan_drift_pct`
6. `realized_pnl_r`, `hold_seconds`
7. `risk_violation_count`, `stop_loss_missing`
8. `closed_at`, `updated_at`

#### t_agent_replay_fact
1. `replay_id`, `decision_id`, `subject_id`
2. `signal_score`, `execution_score`, `risk_score`
3. `action_items_total`, `action_items_closed`
4. `replay_completed`（0/1）
5. `created_at`, `updated_at`

#### t_agent_score_snapshot
1. `subject_type`, `subject_id`, `period`
2. `skill_score`
3. `delta_vs_last_period`
4. `effective_sample_size`, `active_days`
5. `score_status`
6. `created_at`, `updated_at`

#### t_agent_score_dimension_snapshot
1. `subject_type`, `subject_id`, `period`
2. `rar`, `ddc`, `exec_score`, `cons`, `risk`, `replay`
3. `penalty`
4. `raw_metrics_json`（用于解释与审计）
5. `created_at`, `updated_at`

#### t_agent_score_event
1. `event_id`, `subject_type`, `subject_id`, `period`
2. `trigger_type`: `ORDER_CLOSED/REPLAY_DONE/SCHEDULED_REBUILD`
3. `before_score`, `after_score`
4. `delta_score`
5. `trace_id`
6. `created_at`

## 6. 接口契约（v1.1）

### 6.1 维持兼容接口
1. `GET /api/v1/agent/score/profile`
2. `GET /api/v1/agent/score/leaderboard`

### 6.2 新增接口（建议）
1. `GET /api/v1/agent/score/history?subjectType=&subjectId=&periods=8`
2. `GET /api/v1/agent/score/explain?subjectType=&subjectId=&period=2026-W10`
3. `POST /api/v1/agent/score/recompute`（内部管理接口）

### 6.3 ScoreExplainResponse（新增）
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "subjectType": "AGENT",
    "subjectId": "codex-agent",
    "period": "2026-W10",
    "skillScore": 87.4,
    "weights": {"RAR": 0.30, "DDC": 0.20, "EXEC": 0.15, "CONS": 0.15, "RISK": 0.10, "REPLAY": 0.10},
    "dimensions": {"RAR": 89.1, "DDC": 84.4, "EXEC": 86.3, "CONS": 82.9, "RISK": 91.6, "REPLAY": 79.8},
    "penalty": 2.0,
    "topContributors": [
      {"name": "低滑点执行", "impact": 1.7},
      {"name": "复盘行动闭环", "impact": 1.2}
    ],
    "topDrags": [
      {"name": "跨场景稳定性", "impact": -0.9}
    ]
  },
  "timestamp": 1772600000000,
  "success": true
}
```

## 7. Kafka 事件流（评分相关）

### 7.1 Topic 清单（新增/细化）
1. `agent.decision.adopted`
2. `agent.execution.closed`
3. `agent.replay.completed`
4. `agent.score.compute.request`
5. `agent.score.compute.result`
6. `agent.score.updated`

### 7.2 计算触发策略
1. 准实时触发:
   - `execution.closed` 或 `replay.completed` 到达后，触发对应主体增量重算。
2. 定时重算:
   - 每日 `00:05` 重算近 7 日滚动分；
   - 每周一 `00:10` 固化上周 `Wxx` 快照。

## 8. 研发拆分建议（2 Sprint）

### Sprint 1（先可用）
1. 落库事实层: `decision_fact/execution_fact/replay_fact`
2. 新增 `score_snapshot + dimension_snapshot`
3. 计算任务: 每小时批量计算（先批后流）
4. `GET /score/profile` 返回动态数据

### Sprint 2（再增强）
1. 事件驱动增量计算（Kafka）
2. `score/explain + score/history`
3. 排行榜筛选与降噪（最小样本过滤）
4. 策略版本增量对比（`v1.2 -> v1.3`）

## 9. 验收标准（必须）
1. 同一主体一周内至少一次分数更新可追溯到 `score_event`。
2. `score/profile` 与 `score/explain` 的维度分和总分可复算一致（误差 < 0.01）。
3. 任一维度异常下降可定位到具体行为指标（滑点、违规、复盘缺失等）。
4. 样本不足主体不会进入公开榜单。

## 10. 风险与防作弊
1. 高置信度刷单:
   - 仅采纳“平仓完结+可归因样本”计分。
2. 刻意小仓刷胜率:
   - `effectiveSample` 按风险暴露加权。
3. 复盘走过场:
   - `action_items_closed/action_items_total` 低于阈值，`REPLAY` 维度不加分。

## 11. 快速验证（本地）
1. 启动最小链路:
   - `agent-training-core`
   - `api-gateway`
2. 运行体验脚本:
   - `bash scripts/agent/shadow_agent_eval.sh`
3. 核验输出:
   - `drill.log` 中应看到 `passed=true` 且 `adopt=accepted`
   - `decision_log_status_summary.json` 应出现 `accepted` 计数增长
