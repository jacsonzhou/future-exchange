# Agent-Native 策略训练基础设施 PRD（v0.2）

## 1. 文档信息
- 文档版本: `v0.2`
- 创建日期: `2026-03-03`
- 对齐文档:
  - `docs/requirements/08_ai_cotraining_trading_prd_v0.1.md`
  - `docs/requirements/09_sprint_wireframe_and_api_breakdown_v0.1.md`
  - `docs/requirements/10_module_design_and_execution_backlog_v0.1.md`
- 产品新定位: `人和AI的策略训练基础设施（Agent-Native Trading Training Infra）`

## 2. 新定位与边界

### 2.1 一句话定位
`不是交易平台 + AI建议，而是面向人类与Agent的策略训练操作系统。`

### 2.2 核心价值闭环
1. 定义策略（Skill/模板/参数）。
2. 受控执行（Policy Engine硬约束）。
3. 自动复盘（决策-执行-结果证据链）。
4. 能力增量证明（Skill Score + 成长曲线 + 版本对比）。

### 2.3 非目标（当前阶段）
1. 不做“LLM直持密钥自动真仓下单”。
2. 不做平台对赌对手盘。
3. 不做仅凭大模型主观结论的黑箱评分。

## 3. 目标客户（双客群）

### 3.1 Agent-Native 客群（一等公民）
1. 使用 `Claude Code / Codex / Kimi K2` 的策略开发者。
2. 量化工程师与半自动交易用户。
3. 需要 API/SDK/CLI、可版本化策略、可复现实验数据的人群。

核心诉求:
1. 标准化输入输出契约。
2. 可回放可审计的执行证据。
3. 可比较的策略版本表现（A/B）。

### 3.2 传统交易客群（并行服务）
1. 需要训练路径的新手用户。
2. 需要纪律、风控与复盘体系的进阶用户。

核心诉求:
1. 先训练再实战。
2. 看得懂的AI解释。
3. 看到自身能力是否持续提升。

## 4. 产品差异化（对比交易所/FTMO）
1. 交易所提供流动性与执行，不提供系统化能力增长证明。
2. FTMO提供筛选规则，不提供“策略迭代基础设施”。
3. 本系统提供统一能力尺子 + 策略版本实验 + 决策证据链。

差异化结论:
`我们不是筛人，也不只是给信号，我们提供“变强的工程化路径”。`

## 5. 核心能力模型

### 5.1 双引擎评估
1. 规则评分引擎（Score Engine）:
   - 负责可审计、可复现的能力评分。
2. 模型解释引擎（LLM Coach）:
   - 负责解释、归因、训练建议。

原则:
1. 评分以规则为准。
2. 模型只做解释与建议，不做最终裁决。

### 5.2 Skill Score v1（建议）
`Skill Score = 35%风险调整收益 + 25%回撤控制 + 20%执行一致性 + 20%策略稳定性 - 违规惩罚`

### 5.3 能力维度（v1）
1. 入场质量。
2. 风险纪律。
3. 仓位管理。
4. 执行一致性。
5. 连亏恢复能力。
6. 策略稳定性。
7. 复盘闭环率。

## 6. Agent 在系统中的正确角色

### 6.1 角色分层
1. 决策层（Claude/Codex/Kimi）:
   - 输入 ContextPack，输出 DecisionIntent。
2. 执行层（Execution Agent）:
   - 执行前必须通过 Policy Engine。
3. 风控层（Policy Engine）:
   - 杠杆、名义、日损、连亏冷却硬约束。
4. 审计层（Audit Trail）:
   - 决策、校验、执行、回放全链路可追溯。

### 6.2 硬约束
1. LLM 不持有用户交易密钥。
2. 决策输出必须结构化，禁止自由文本直下单。
3. 所有关键动作带 `trace_id + decision_id + order_id`。

## 7. 信息架构与导航栏（最终建议）

## 7.1 设计原则
1. 主导航只放“闭环关键路径”。
2. 面向 Agent 的能力必须一眼可见。
3. 不把导航做成交易所式信息堆叠。

## 7.2 主导航（建议 9 项）
1. `交易终端` (`/trade`)。
2. `BTC周期看板` (`/btc-dashboard`)。
3. `模拟训练` (`/training`)。
4. `策略工坊` (`/skills`)。
5. `AI决策台` (`/ai-console`)。
6. `复盘实验室` (`/replay-lab`)。
7. `AI竞技场` (`/arena/live`)。
8. `Skill榜` (`/skillboard`)。
9. `我的成长` (`/my-growth`)。

## 7.3 次导航（右上角入口）
1. `规则` (`/rules`)。
2. `开发者` (`/developers`)。
3. `个人中心` (`/profile`)。

说明:
1. `规则`长期保留高可见，保证透明度。
2. `开发者`是 Agent-Native 差异化入口，不能藏太深。

## 7.4 为什么不用“交易所式导航”
1. 如果主导航被“市场/合约/跟单/学院”占满，会弱化训练主线。
2. 本产品应突出“定义策略 -> 执行 -> 复盘 -> 证明成长”。

## 8. 页面职责（按导航）
1. 交易终端: 手动/AI建议填单，最终确认执行。
2. BTC周期看板: 市场状态与周期证据面板。
3. 模拟训练: 任务化训练与风险护栏。
4. 策略工坊: Skill 编写、版本管理、A/B 对照。
5. AI决策台: 候选建议、解释、风控预校验。
6. 复盘实验室: 单笔回放与批量归因报告。
7. AI竞技场: AI vs AI / AI vs Human 实时比较。
8. Skill榜: 人机同榜，统一评分对比。
9. 我的成长: 个人能力曲线与下周行动计划。
10. 规则: 评分、竞技、风控、申诉透明化。
11. 开发者: API/SDK/CLI 文档与沙箱。

## 9. 数据与对象契约（v0.2）

### 9.1 Skill 对象
```json
{
  "skillId": "skill_trend_pullback",
  "version": "1.0.0",
  "ownerType": "USER_OR_AGENT",
  "inputSchema": "ContextPack@v1",
  "guardrails": {
    "maxLeverage": 10,
    "maxOrderNotional": 20000,
    "dailyLossLimit": 500
  },
  "evalTargets": {
    "minSkillScore": 75,
    "maxDrawdownPct": 6
  }
}
```

### 9.2 DecisionIntent（统一输出）
```json
{
  "decisionId": "dec_20260303_0001",
  "skillId": "skill_trend_pullback",
  "action": "OPEN_LONG",
  "symbol": "BTCUSDT",
  "entryMin": 66120.0,
  "entryMax": 66250.0,
  "quantity": 0.02,
  "leverage": 8,
  "stopLoss": 65890.0,
  "takeProfit": 66780.0,
  "confidence": 0.64,
  "reasons": [
    "价格接近区间中下部",
    "主动买盘增强",
    "风险暴露未超阈值"
  ]
}
```

### 9.3 CapabilityEvidence（能力证据）
```json
{
  "accountId": 1001,
  "period": "2026-W10",
  "skillScore": 85.5,
  "dimensions": {
    "entryQuality": 84.1,
    "riskDiscipline": 90.2,
    "executionConsistency": 87.0,
    "stability": 82.7
  },
  "deltaVsLastPeriod": 2.3,
  "evidenceIds": ["ord_1002", "dec_2044", "rep_332"]
}
```

## 10. 里程碑路线图（3个月）

### 月份1: 基础设施化
1. 上线 Skill Registry v0。
2. 上线 ContextPack / DecisionIntent 标准契约。
3. 上线策略工坊（基础版）+ 决策日志。

### 月份2: 评估与复盘强化
1. 上线规则评分引擎 v1。
2. 上线复盘实验室（单笔回放 + 批量归因）。
3. 上线“能力增量证明”卡片。

### 月份3: Agent-Native 扩展
1. 开放 Developer Portal（API/SDK/CLI）。
2. 上线 AI竞技场 v1 与 Skill榜联动。
3. 小流量灰度“受控自动化”（白名单）。

## 11. 北极星与关键指标

### 11.1 北极星指标
`每周能力增量证明用户数（Skill Score 连续2周提升且有复盘证据）`

### 11.2 核心指标
1. 策略版本周迭代率。
2. 决策采纳后正向改进率。
3. 复盘完成率与复盘后参数修改率。
4. Agent API 周活跃调用账户数。

## 12. 风险与应对
1. 风险: 评分被质疑黑箱。
   - 应对: 规则版本公开 + 证据链可下载。
2. 风险: LLM建议越权。
   - 应对: Policy Engine 硬阻断 + 审计报警。
3. 风险: 导航过重影响传统用户上手。
   - 应对: 提供“普通模式/开发者模式”导航视图切换。

## 13. 立即执行清单（本周）
1. 确认主导航与次导航最终文案与路由。
2. 补齐 `策略工坊`、`复盘实验室`、`开发者` 三页前端占位。
3. 产出 Skill 对象与 DecisionIntent 的后端 DTO 草案。
4. 定义 Skill Score 的字段级口径文档（便于审计与复现）。

