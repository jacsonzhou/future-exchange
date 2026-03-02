# CFD 全链路回归任务清单 V2

> 更新时间：2026-03-02  
> 目标：把 CFD 模式关键交易链路收敛成“可执行、可复盘、可门禁”的回归流程。  
> 链路范围：生成账户 -> 初始化资金 -> 下单资金暂扣 -> 撤单释放 -> 成交扣款 -> 持仓更新 -> PNL 推送 -> 强平执行。

---

## 1. 回归目标与判定标准

## 1.1 目标

1. 用新注册用户在同一轮测试中完成全流程，避免“历史脏数据”干扰。
2. 每个关键阶段都有可验证断言（HTTP/Kafka/DB/日志至少一种）。
3. 形成一键执行入口，输出统一报告，支持 CI 门禁。

## 1.2 通过标准（必须全部满足）

1. 注册后可登录，账户 `equity > 0`（初始资金到账）。
2. LIMIT 下单后 `frozen` 增加（资金暂扣）。
3. 撤单后状态 `CANCELED` 且 `frozen` 回落（释放暂扣）。
4. MARKET 成交后订单 `FILLED`，并出现有效持仓（`size > 0`）。
5. 成交后账户出现资金占用变化（`available` 下降或 `positionMargin` 上升）。
6. PNL 推送步骤通过（WebSocket 持仓 upnl 变化）。
7. 强平步骤通过（触发并落地到 liquidation 执行结果）。

---

## 2. 覆盖矩阵（现有+新增）

| 阶段 | 校验点 | 脚本 | 状态 |
|---|---|---|---|
| 账户生成 | 注册成功返回 `userId` | `scripts/cfd/run_full_chain_regression.sh` | 已通过 |
| 初始化资金 | 登录后 `equity > 0` | `scripts/cfd/run_full_chain_regression.sh` | 已通过 |
| 资金暂扣 | LIMIT 下单后 `frozen` 上升 | `scripts/cfd/test_c8_funding_prehold_flow.sh` | 已通过 |
| 撤单释放 | 撤单后 `frozen` 回落 | `scripts/cfd/test_c8_funding_prehold_flow.sh` | 已通过 |
| 成交扣款 | MARKET FILLED 后账户占用变化 | `scripts/cfd/test_c8_funding_prehold_flow.sh` | 已通过 |
| 持仓更新 | 成交后存在活动持仓 | `scripts/cfd/test_c8_funding_prehold_flow.sh` | 已通过 |
| PNL 推送 | 私有 WS 持仓 upnl 变化 | `scripts/test_position_up_mark_push.py` | 已通过 |
| 强平 | liquidation 触发并执行 | `scripts/e2e_acceptance_suite.py --with-liquidation --strict-liquidation` | 已通过 |

---

## 3. 执行顺序

1. 创建 taker/maker 新用户（同轮回归专用）。
2. 校验两账户初始资金到账。
3. 执行 C8（资金暂扣/撤单/成交/持仓）：
   - `scripts/cfd/test_c8_funding_prehold_flow.sh`
4. 执行全量验收（含 PNL 推送 + 强平）：
   - `scripts/e2e_acceptance_suite.py --with-liquidation --strict-liquidation`
5. 汇总报告并作为门禁结果。

---

## 4. 一键回归入口

```bash
bash scripts/cfd/run_full_chain_regression.sh
```

可选开关：

1. `RUN_ACCEPTANCE=false`：仅跑账户资金+暂扣链路。
2. `RUN_PNL_PUSH=true`：额外独立跑 `scripts/test_position_up_mark_push.py`。
3. `WITH_LIQUIDATION=false`：跳过强平阶段（本地调试用，不建议门禁）。
4. `STRICT_LIQUIDATION=false`：强平失败不阻断（本地排障用，不建议门禁）。

---

## 5. 门禁建议

## 5.1 预发门禁（强制）

1. `scripts/cfd/run_full_chain_regression.sh` 全通过。
2. `--with-liquidation --strict-liquidation` 必须开启。

## 5.2 开发自测（建议）

1. 先跑 `RUN_ACCEPTANCE=false` 快速验证账户资金与预扣。
2. 再跑全量回归，确认 PNL/强平链路。

---

## 6. 已知风险与后续优化

1. PNL 推送依赖私有 WS 与 Kafka/mark-price 联动，建议在 CI 中固定依赖版本并保留重试窗口。
2. 保险基金闭环（V2-C2）已进入实施中后段，当前全链路门禁尚未覆盖“强平盈余自动注资”断言。

---

## 7. V2 子任务状态（2026-03-02）

| 子任务 | 内容 | 状态 | 备注 |
|---|---|---|---|
| V2-C1 | 强平单路由改到 CFD（`CFD_DEALER`） | 已完成 | OMS 已按 `executionMode=CFD_DEALER` 下发到 `cfd-order-command-{symbol}` |
| V2-C1-补充 | R6 幂等与服务依赖收敛 | 已完成 | 增加 Redis 强平幂等键清理；验收必需服务补齐 `cfd-dealer-core:8106` |
| V2-C2 | 保险基金闭环 | 进行中（首批增强） | 已补齐 internal 契约、幂等与强平盈余自动注资，详见 `docs/CFD_INSURANCE_FUND_C2_TASKLIST_V1.md` |
| V2-C3 | ADL 闭环 | 待开始 | 不在当前回归门禁范围 |

## 8. 最新回归结果

1. 全链路回归脚本：`scripts/cfd/run_full_chain_regression.sh`
2. 执行时间：2026-03-02 11:13:35 ~ 11:16:07
3. 结果：`PASS`
4. 验收报告：
   - `test-reports/acceptance-suite-20260302-111607.json`
   - `test-reports/acceptance-suite-20260302-111607.md`
