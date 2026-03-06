# AI共训交易系统 功能设计与执行工单（v0.1）

## 1. 文档信息
- 版本: `v0.1`
- 日期: `2026-03-02`
- 对齐文档:
  - `docs/requirements/08_ai_cotraining_trading_prd_v0.1.md`
  - `docs/requirements/09_sprint_wireframe_and_api_breakdown_v0.1.md`
- 目标: 输出可直接进入研发排期的模块设计与任务拆分

## 2. 产品主线（先统一口径）
系统主线不是“再做一个交易页”，而是做“人机共训闭环”:

1. 交易或模拟执行。
2. AI给结构化建议并可解释。
3. 用户采纳或拒绝建议并执行。
4. 系统记录决策与执行偏差。
5. Skill Score评估能力。
6. 复盘实验室给出下一轮改进动作。

一句话: `让用户和AI都在同一套尺子下持续进化。`

## 3. Sprint 1（2周）- 模拟训练 + AI决策台 + 一键填单 + 决策日志

### 3.1 模块A: 模拟训练（`/training`）
#### 用户目标
1. 在可控风险下训练开平仓和止损纪律。
2. 每次训练都能得到可量化的反馈。

#### 页面组件（组件级）
1. 训练任务栏:
   - 任务类型: 趋势日、震荡日、突发行情日
   - 完成条件: 例如“回撤 < 2%，完成3笔交易”
2. 风险护栏卡:
   - 最大杠杆
   - 单笔最大风险
   - 日内最大亏损
3. 交易执行区（复用现有交易面板）:
   - K线、盘口、成交、下单面板
4. AI建议区（复用`AI 共训助手`）:
   - 建议方向、置信度、进场区间、止损止盈
   - `采纳建议并填单`
5. 训练结果区:
   - 训练Skill Score
   - 错误标签（追涨杀跌、止损延迟、过度交易）
   - `加入复盘实验室`

#### 核心交互
1. 用户开始训练会话 -> 系统锁定风险边界。
2. 用户手动下单或采纳AI填单。
3. 用户结束训练 -> 系统生成会话总结和错误标签。

#### 最小验收
1. 可创建训练会话并结束。
2. 结束后可看到会话级得分和错误标签。
3. 可一键跳转复盘。

### 3.2 模块B: AI决策台（`/ai-console`）
#### 用户目标
1. 看懂AI为什么给这个建议。
2. 一键填单但保留人工确认权。

#### 页面组件（组件级）
1. 决策候选列表:
   - action、confidence、entry range、SL/TP
2. 决策证据面板:
   - 24h高低位置
   - 买卖流偏向
   - 波动率状态
3. 风控预校验面板:
   - 通过/拒绝
   - 拒绝原因（如风险暴露超阈值）
4. 执行动作:
   - `采纳建议并填单`
   - 手动修改参数再下单
5. 决策日志Tab:
   - decision_id、strategy_version、status、trace_id

#### 核心交互
1. 拉取上下文并生成候选决策。
2. 用户点选决策查看解释。
3. 预校验通过后允许填单。
4. 执行结果回写日志。

#### 最小验收
1. 每次建议都带理由和置信度。
2. 决策可追溯到执行结果。
3. 一键填单不自动下单。

### 3.3 模块C: 决策日志（`/ai-console?tab=logs`）
#### 用户目标
1. 快速定位“建议对不对、执行有没有走样”。

#### 页面组件（组件级）
1. 筛选器:
   - symbol / strategy / status / 时间段
2. 日志表格:
   - `decision_id`
   - 建议参数 vs 实际下单参数
   - 状态（accepted/rejected/expired）
3. 详情抽屉:
   - ContextPack快照
   - Policy check链路
   - Execution report

#### 最小验收
1. 订单可反查决策。
2. 决策可反查订单。

### 3.4 Sprint 1 工单拆分（建议）
| 编号 | 端 | 模块 | 任务 | 优先级 |
|---|---|---|---|---|
| S1-FE-01 | FE | 模拟训练 | 新增训练会话UI与结束结算卡 | P0 |
| S1-FE-02 | FE | AI决策台 | 决策列表+详情+风控状态 | P0 |
| S1-FE-03 | FE | 交易页 | 一键填单与日志跳转联动 | P0 |
| S1-FE-04 | FE | 决策日志 | 日志列表+详情抽屉 | P1 |
| S1-BE-01 | BE | user-core | `training/context` 聚合接口 | P0 |
| S1-BE-02 | BE | ai-decision-core | preview/validate/log 接口 | P0 |
| S1-BE-03 | BE | replay-core | replay create/get 业务接口 | P1 |
| S1-BE-04 | BE | 网关/鉴权 | 新路由（Nacos）+鉴权策略 | P0 |

## 4. Sprint 2（2周）- Skill榜 + 我的成长 + 规则 + AI竞技场v1

### 4.1 模块D: Skill榜（`/skillboard`）
#### 用户目标
1. 看自己和AI差距在哪里，不只看收益率。

#### 页面组件（组件级）
1. 榜单维度:
   - 人机同榜 / 人类榜 / AI榜
2. 评分分解:
   - 收益质量、回撤控制、执行一致性、稳定性
3. 趋势信息:
   - 7日变化、30日变化
4. 详情抽屉:
   - 分数构成和最近风险事件

#### 最小验收
1. 同一账户在榜单页与个人页得分一致。
2. 支持按周期切换（日/周/月）。

### 4.2 模块E: 我的成长（`/my-growth`）
#### 用户目标
1. 知道“下一步怎么变强”，而不是只看历史。

#### 页面组件（组件级）
1. 能力总览卡:
   - 当前Skill Score
   - 与目标等级差距
2. 能力雷达:
   - 方向判断、仓位纪律、风控执行、策略稳定
3. 成长时间轴:
   - 关键交易、关键复盘、关键参数修改
4. 下周计划:
   - 训练主题
   - 建议参数改动

#### 最小验收
1. 可见能力雷达和时间轴。
2. 输出3条可执行改进建议。

### 4.3 模块F: 规则（`/rules`）
#### 用户目标
1. 透明知道分数和竞技规则，避免“黑箱”。

#### 页面组件（组件级）
1. 评分规则区
2. 竞技规则区
3. 风控与处罚区
4. 规则版本与变更记录

#### 最小验收
1. 所有规则有版本号。
2. 历史变更可追溯。

### 4.4 模块G: AI竞技场v1（`/arena/live`）
#### 用户目标
1. 观察AI与人类在同市场下的策略表现差异。

#### 页面组件（组件级）
1. 对战大厅:
   - AI vs AI
   - AI vs Human
2. 实时曲线:
   - PnL
   - 回撤
   - Skill Score
3. 策略画像:
   - 风格标签
   - 当前风险暴露
4. 围观动作:
   - 模拟跟随
   - 加入复盘

#### 最小验收
1. 有实时对战列表和详情。
2. 用户可发起模拟跟随。

### 4.5 Sprint 2 工单拆分（建议）
| 编号 | 端 | 模块 | 任务 | 优先级 |
|---|---|---|---|---|
| S2-FE-01 | FE | Skill榜 | 榜单切换/分页/详情抽屉 | P0 |
| S2-FE-02 | FE | 我的成长 | 雷达图+时间轴+行动建议 | P0 |
| S2-FE-03 | FE | 规则 | 规则版本页+变更记录 | P1 |
| S2-FE-04 | FE | 竞技场 | 对战列表+详情+模拟跟随 | P1 |
| S2-BE-01 | BE | skill-score-core | leaderboard/profile 接口 | P0 |
| S2-BE-02 | BE | growth（先user-core） | growth overview/timeline | P0 |
| S2-BE-03 | BE | arena-core | live/match/follow 接口 | P1 |
| S2-BE-04 | BE | rules | current/changelog 接口 | P1 |

## 5. API清单（按现有微服务拆分）

## 5.1 复用现有接口（已存在）
| 服务 | 方法 | 路径 | 用途 |
|---|---|---|---|
| market-price-core | GET | `/api/v1/klines` | K线与复盘 |
| market-price-core | GET | `/api/v1/depth` | 盘口快照 |
| market-price-core | GET | `/api/v1/trades` | 主动买卖流 |
| market-price-core | GET | `/api/v1/ticker/24hr` | 24h上下沿证据 |
| market-price-core | GET | `/api/v1/bookTicker` | 最优买卖价 |
| oms-core | POST | `/api/order/create` | 最终下单 |
| oms-core | POST | `/api/order/cancel` | 撤单 |
| oms-core | GET | `/api/v1/oms/order/list` | 历史订单 |
| snapshot-account-core | GET | `/api/v1/account/balance` | 余额与权益 |
| position-snapshot-core | GET | `/api/v1/position/list` | 持仓风险 |
| user-core | GET | `/api/v1/trading/dashboard` | 首页交易聚合 |
| user-core | GET | `/api/v1/user/profile/overview` | 成长概览 |
| user-core | GET | `/api/v1/user/profile/trades` | 历史交易 |

## 5.2 Sprint 1 新增接口契约

### A. `ai-decision-core`（新增）
1. `POST /api/v1/ai/decision/preview`

请求:
```json
{
  "symbol": "BTCUSDT",
  "interval": "1m",
  "context": {
    "market": {
      "last": 66320.5,
      "high24h": 66888.0,
      "low24h": 65210.2,
      "buyFlowStrength": 2
    },
    "account": {
      "equity": 12000.0,
      "riskExposure": 0.406
    },
    "risk": {
      "maxLeverage": 20,
      "maxOrderNotional": 20000.0
    }
  }
}
```

响应:
```json
{
  "code": 0,
  "data": [
    {
      "decisionId": "dec_20260302_1001",
      "strategyId": "ai-momentum-v1",
      "strategyVersion": "1.2.3",
      "action": "OPEN_SHORT",
      "confidence": 0.54,
      "entryMin": 69470.79,
      "entryMax": 69763.58,
      "stopLoss": 70153.95,
      "takeProfit": 68202.07,
      "riskExposureAfter": 0.486,
      "reasons": [
        "价格靠近24h区间上沿，倾向回撤确认",
        "短周期成交流偏买盘，波动放大",
        "当前风险暴露在可控范围内"
      ]
    }
  ],
  "msg": "success"
}
```

2. `POST /api/v1/ai/decision/validate`

请求:
```json
{
  "decisionId": "dec_20260302_1001",
  "symbol": "BTCUSDT",
  "action": "OPEN_SHORT",
  "quantity": 0.02,
  "leverage": 10
}
```

响应:
```json
{
  "code": 0,
  "data": {
    "passed": true,
    "checks": [
      {"name": "max_leverage", "passed": true},
      {"name": "max_daily_loss", "passed": true}
    ],
    "rejectReason": ""
  },
  "msg": "success"
}
```

3. `POST /api/v1/ai/decision/log`
4. `GET /api/v1/ai/decision/logs`

### B. `user-core`（增强）
1. `GET /api/v1/training/context?symbol=BTCUSDT`
2. `POST /api/v1/training/session/start`
3. `POST /api/v1/training/session/{sessionId}/finish`
4. `GET /api/v1/training/session/{sessionId}/summary`

### C. `replay-core`（新增业务接口）
1. `POST /api/v1/replay/create`
2. `GET /api/v1/replay/{replayId}`
3. `POST /api/v1/replay/{replayId}/notes`

## 5.3 Sprint 2 新增接口契约

### A. `skill-score-core`（新增）
1. `GET /api/v1/skill/leaderboard?scope=all&period=week&type=all&page=1&size=20`
2. `GET /api/v1/skill/profile/{accountId}`
3. `GET /api/v1/skill/compare?left={accountIdOrStrategyId}&right={id}`

### B. `growth`（先落`user-core`，后拆服务）
1. `GET /api/v1/growth/overview`
2. `GET /api/v1/growth/timeline`
3. `GET /api/v1/growth/next-actions`

### C. `arena-core`（新增）
1. `GET /api/v1/arena/live`
2. `GET /api/v1/arena/match/{matchId}`
3. `POST /api/v1/arena/follow-sim`

### D. `rules`（先静态配置，后独立服务）
1. `GET /api/v1/rules/current`
2. `GET /api/v1/rules/changelog`

## 6. 事件流设计（Kafka）
建议主题:

1. `ai-decision-request`
2. `ai-decision-result`
3. `ai-decision-log`
4. `training-session-event`
5. `skill-score-snapshot`
6. `arena-match-event`
7. `replay-created`

关键原则:
1. 决策事件和交易事件分流，避免耦合。
2. 关键事件包含 `trace_id`, `decision_id`, `order_id`。
3. 所有风险拦截都产生事件，用于后续模型纠偏。

## 7. Claude/Codex 正确角色（落地版）
系统内的角色严格分层:

1. 决策层:
   - 输入ContextPack，输出DecisionIntent
   - 可建议，不可执行
2. 执行层:
   - 仅接受结构化订单参数
   - 必须通过Policy Engine
3. 风控层:
   - 硬约束杠杆、名义、日损、连亏冷却
4. 审计层:
   - 记录“建议 -> 采纳 -> 下单 -> 结果”全链路

落地约束:
1. 模型不持有API Key。
2. 前端不直连模型密钥。
3. 自动化下单能力必须后置到白名单阶段。

## 8. 不该做什么（当前阶段）
1. 不做“AI一键自动真仓执行”。
2. 不做“平台直接对赌”。
3. 不做“高频多策略并行自动交易”。
4. 不先做花哨社交功能，先保交易闭环和评分可信。

## 9. 发布门槛（Release Gate）
Sprint 1 上线门槛:
1. 一键填单全链路可用，且不会自动发单。
2. 决策日志可追溯率达到100%。
3. 训练会话结束可稳定产出评分摘要。

Sprint 2 上线门槛:
1. Skill榜与个人页分数一致率100%。
2. 竞技场数据延迟在可接受范围内（建议<3秒）。
3. 规则页版本和变更可追溯。

## 10. 研发排期建议（两周一个Sprint）
1. 第1周:
   - 完成S1-BE-01/02与S1-FE-01/02的主链路
2. 第2周:
   - 打通日志和复盘，完成S1 DoD
3. 第3周:
   - 完成Skill榜与我的成长主接口
4. 第4周:
   - 完成竞技场v1和规则透明页，达成S2 DoD

