# CFD 模式实施任务单 V2-C2（保险基金闭环）

> 更新时间：2026-03-02  
> 目标：打通 `liquidation-core -> insurance-fund` 的可用闭环，确保穿仓赔付与强平盈余注资均可调用、幂等可控、异常不阻断强平完成。  
> 当前分层：`liquidation-core` 调用方，`adl-core` 作为保险基金服务提供方（内部接口）。

---

## 1. 问题基线（As-Is）

1. `liquidation-core` 的 Feign 服务名为 `insurance-fund-service`，与现网 `adl-core` 不一致。
2. `adl-core` 缺少 `/internal/insurance-fund/*` 内部契约接口。
3. 保险基金调用失败会抛异常，可能导致强平完成流程中断，`liquidation-completed-topic` 无法发布。
4. 缺少保险基金契约级回归脚本（income/expense/幂等重放）。

---

## 2. 代码落地清单

| 任务ID | 事项 | 状态 |
|---|---|---|
| C2-B1 | 对齐 Feign 服务发现（`liquidation-core` -> `adl-core`） | 已完成 |
| C2-B2 | 新增 `adl-core` 内部保险基金接口：`balance/expense/income` | 已完成 |
| C2-B3 | 赔付接口支持 `bizSeq` 幂等（重放不重复扣减） | 已完成 |
| C2-B4 | 保险基金业务失败/不足资金时返回 0 赔付，不阻断强平主流程 | 已完成 |
| C2-B5 | 增加 C9 契约脚本验证 income/expense/幂等 | 已完成 |
| C2-B6 | 将 C9 接入全链路入口（开关控制） | 已完成（`RUN_C9=false` 默认关闭） |
| C2-B7 | 强平成交后自动注资保险基金（`realizedPnl + initialMargin > 0`） | 已完成 |

---

## 3. 首批改造内容

## 3.1 adl-core（服务提供方）

1. 新增内部控制器：
   - `/internal/insurance-fund/balance`
   - `/internal/insurance-fund/expense?bizSeq=...`
   - `/internal/insurance-fund/income?bizSeq=...`
2. `expense/income` 均基于 `ref_id=bizSeq` 做幂等重放识别。
3. 金额契约统一使用 8 位精度 long（与 liquidation 保持一致）。

涉及文件：
- `adl-core/src/main/java/com/exchange/adl/controller/InsuranceFundInternalController.java`

## 3.2 liquidation-core（调用方）

1. `InsuranceFundClient` 服务名改为 `adl-core`，路径保留 `/internal/insurance-fund`。
2. `InsuranceFundServiceImpl` 改为“业务失败降级”：
   - `success=false` 返回 0 赔付，继续后续 ADL 判定；
   - 网络异常保留重试，重试耗尽后 `@Recover` 返回 0。
3. 新增“强平盈余自动注资”：
   - `LiquidationServiceImpl` 在成交后调用 `injectLiquidationSurplus`；
   - `bizSeq={liquidationId}_income` 幂等防重放；
   - 注资失败降级为 0，不阻断强平完成事件发布。

涉及文件：
- `liquidation-core/src/main/java/com/exchange/liquidation/client/InsuranceFundClient.java`
- `liquidation-core/src/main/java/com/exchange/liquidation/service/InsuranceFundService.java`
- `liquidation-core/src/main/java/com/exchange/liquidation/service/impl/InsuranceFundServiceImpl.java`
- `liquidation-core/src/main/java/com/exchange/liquidation/service/impl/LiquidationServiceImpl.java`

## 3.3 回归脚本

1. 新增 C9 契约脚本：
   - 验证 `income -> expense -> expense幂等重放`
2. 全链路入口增加 `RUN_C9` 开关（默认关闭，按需开启）。

涉及文件：
- `scripts/cfd/test_c9_insurance_fund_contract.sh`
- `scripts/cfd/run_full_chain_regression.sh`

---

## 4. 首批验收结果

1. 编译通过：`mvn -pl adl-core,liquidation-core -am package -DskipTests`
2. C9 契约通过：`bash scripts/cfd/test_c9_insurance_fund_contract.sh`
3. 验收日志关键结果：
   - `[PASS] income accepted`
   - `[PASS] expense accepted`
   - `[PASS] expense idempotent replay verified`
4. 新增代码路径：
   - 强平成交后若存在盈余（`realizedPnl + initialMargin > 0`）会调用 `POST /internal/insurance-fund/income` 自动注资。

---

## 5. 下一批（C2-Phase2）建议

1. 增加“强平盈余注资”回归脚本（构造正盈余强平，断言 insurance fund `incomeAmount` 与余额变化）。
2. 增加按 `symbol/currency` 的基金初始化与阈值巡检任务（启动即自愈）。
3. 增加 `liquidation-completed` 事件中保险基金字段一致性断言（报告化）。
4. 将 C9 纳入 CI 门禁（建议默认开启于预发）。
