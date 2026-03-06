# Agent Training 后端接口与事件流规范（v1.0）

## 1. 文档信息
- 文档版本: `v1.0`
- 创建日期: `2026-03-03`
- 对齐文档:
  - `docs/requirements/12_agent_native_growth_prd_v1.0.md`
  - `docs/requirements/11_agent_native_strategy_training_infra_prd_v0.2.md`
- 目标: 给研发提供可直接开工的后端接口字段字典与 Kafka Topic 级事件流

## 2. 核心约束（必须遵守）
1. 新能力由独立服务承载，不进入 OMS 下单链路。
2. 所有入口只走 `api-gateway`，不对外暴露新服务直连地址。
3. 新服务不生产/消费 `order-event-*`、`order-state-*`、`trade-entry-*`。
4. Agent 只做决策建议，不直接持有用户 API Key 执行真仓交易。
5. 所有关键动作必须带 `traceId + decisionId + replayId` 可审计关联。

## 3. 服务拆分与边界

### 3.1 新服务定义
| 项目 | 规格 |
|---|---|
| 服务名 | `agent-training-core` |
| 建议端口 | `8107` |
| 注册中心 | Nacos (`DEFAULT_GROUP`) |
| 对外入口 | `api-gateway` 路由转发 |
| 数据库 | 独立库（建议 `exchange_agent_training`） |
| 运行职责 | 模拟训练、策略版本、AI 决策、复盘、评分、成长任务 |

### 3.2 与现有服务关系（按职责）
| 服务 | 调用方式 | 方向 | 说明 |
|---|---|---|---|
| `market-price-core` | Feign (lb://) + Kafka只读 | 读 | 读取 K线/深度/ticker 作为 ContextPack |
| `snapshot-account-core` | Feign (lb://) | 读 | 读取账户快照用于训练上下文 |
| `position-snapshot-core` | Feign (lb://) | 读 | 读取持仓快照用于风险暴露计算 |
| `public-push-core` | Kafka（可选） | 写 | 推送训练域实时事件（可选） |
| `oms-core` | 禁止 | 无 | 与真仓下单链路完全隔离 |
| `match-engine-core` | 禁止 | 无 | 不复用真实撮合链路 |
| `ledger-core` | 禁止 | 无 | 不写入真实账本 |

## 4. API Gateway 接入（Nacos 配置）

> 按 AGENTS 规范，路由配置写入 Nacos `api-gateway-dev.yml`，不在代码里硬编码。

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: agent-training-core
          uri: lb://agent-training-core
          predicates:
            - Path=/api/v1/agent/**
```

### 4.1 认证建议
1. `/api/v1/agent/public/**` 可白名单（如规则、公开榜单）。
2. 其余接口走 JWT 鉴权（读取 `userId/accountId`）。
3. 强制透传请求头:
   - `X-Trace-Id`
   - `X-User-Id`
   - `X-Account-Id`

## 5. 统一响应规范

### 5.1 响应包装（与现有 `ApiResponse` 对齐）
```json
{
  "code": 0,
  "msg": "success",
  "data": {},
  "timestamp": 1772500000000
}
```

### 5.2 错误码（训练域）
| code | 含义 |
|---|---|
| 0 | 成功 |
| 4001 | 参数校验失败 |
| 4002 | 风险校验不通过 |
| 4003 | Skill 未发布或版本不可用 |
| 4004 | Decision 状态不允许当前操作 |
| 4005 | 会话不存在 |
| 4006 | 数据样本不足，无法产出正式评分 |
| 5001 | 内部处理异常 |

## 6. 接口字段字典（v1.0）

## 6.1 ContextPack（市场/账户上下文）

### 接口
- `GET /api/v1/agent/context`

### Query 参数
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| symbol | string | Y | 交易对，如 `BTCUSDT` |
| interval | string | Y | K线周期，如 `1m/5m/1h/1d` |
| depthLevel | int | N | 深度档位，默认 `20` |
| windowMinutes | int | N | 回看窗口，默认 `60` |

### data 字段
| 字段 | 类型 | 说明 |
|---|---|---|
| contextId | string | 上下文ID |
| symbol | string | 交易对 |
| interval | string | 周期 |
| market.kline | object | 最新K线摘要 |
| market.depthTop | object | 最优盘口 |
| market.ticker24h | object | 24h指标 |
| account.equity | string | 总权益（字符串防精度损失） |
| account.available | string | 可用余额 |
| account.riskExposurePct | string | 风险暴露百分比 |
| position.summary | array | 当前持仓摘要 |
| serverTime | long | 服务时间戳(ms) |

## 6.2 策略工坊（Skill）

### 接口清单
1. `POST /api/v1/agent/skills`
2. `POST /api/v1/agent/skills/{skillId}/versions`
3. `POST /api/v1/agent/skills/{skillId}/publish`
4. `GET /api/v1/agent/skills`
5. `GET /api/v1/agent/skills/{skillId}`

### Skill 字段字典
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| skillId | string | Y | 全局唯一，如 `skill_trend_pullback` |
| name | string | Y | 策略名称 |
| ownerType | enum | Y | `HUMAN/AGENT` |
| ownerId | string | Y | 用户ID或Agent标识 |
| status | enum | Y | `DRAFT/PUBLISHED/ARCHIVED` |
| tags | string[] | N | 标签 |
| defaultSymbol | string | N | 默认交易对 |
| createdAt | long | Y | 创建时间 |

### SkillVersion 字段字典
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| version | string | Y | 语义化版本，如 `1.0.0` |
| inputSchema | string | Y | 如 `ContextPack@v1` |
| outputSchema | string | Y | 固定 `DecisionIntent@v1` |
| guardrails.maxLeverage | int | Y | 最大杠杆 |
| guardrails.maxOrderNotional | string | Y | 单笔名义上限 |
| guardrails.dailyLossLimit | string | Y | 日损上限 |
| evalTargets.minSkillScore | number | N | 目标分 |
| evalTargets.maxDrawdownPct | number | N | 目标回撤 |
| changelog | string | N | 版本说明 |
| isPublished | boolean | Y | 是否已发布 |

## 6.3 AI决策台（Decision）

### 接口清单
1. `POST /api/v1/agent/decisions/preview`
2. `POST /api/v1/agent/decisions/validate`
3. `POST /api/v1/agent/decisions/adopt`
4. `GET /api/v1/agent/decisions/{decisionId}`
5. `GET /api/v1/agent/decisions`

### DecisionPreviewRequest
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| skillId | string | Y | 策略ID |
| version | string | Y | 策略版本 |
| symbol | string | Y | 交易对 |
| contextId | string | N | 指定上下文；为空则服务端实时生成 |
| scenario | enum | N | `BASELINE/VOLATILE/TREND/RANGE` |

### DecisionIntent（核心输出）
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| decisionId | string | Y | 决策ID |
| skillId | string | Y | 策略ID |
| version | string | Y | 策略版本 |
| action | enum | Y | `OPEN_LONG/OPEN_SHORT/CLOSE/WAIT` |
| symbol | string | Y | 交易对 |
| entryMin | string | N | 入场区间下沿 |
| entryMax | string | N | 入场区间上沿 |
| quantity | string | N | 建议数量 |
| leverage | int | N | 建议杠杆 |
| stopLoss | string | N | 止损价 |
| takeProfit | string | N | 止盈价 |
| confidence | number | Y | 0~1 |
| reasons | string[] | Y | 决策理由 |
| riskExposurePct | string | N | 预估风险暴露 |
| status | enum | Y | `PREVIEWED/VALIDATED/REJECTED/ADOPTED/EXPIRED` |
| createdAt | long | Y | 创建时间 |

### ValidateResult
| 字段 | 类型 | 说明 |
|---|---|---|
| decisionId | string | 对应决策ID |
| passed | boolean | 是否通过 |
| violations | array | 违规列表 |
| normalizedIntent | object | 校验后标准化决策（可用于填单） |

## 6.4 模拟执行（Paper Execution）

### 接口清单
1. `POST /api/v1/agent/paper/orders`
2. `POST /api/v1/agent/paper/orders/{paperOrderId}/cancel`
3. `GET /api/v1/agent/paper/orders/{paperOrderId}`
4. `GET /api/v1/agent/paper/orders`

### PaperOrderRequest
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| decisionId | string | Y | 必须来自 `VALIDATED` 决策 |
| symbol | string | Y | 交易对 |
| side | enum | Y | `BUY/SELL` |
| orderType | enum | Y | `LIMIT/MARKET` |
| price | string | N | LIMIT必填 |
| quantity | string | Y | 下单数量 |
| leverage | int | N | 杠杆 |
| mode | enum | Y | 固定 `PAPER` |

### PaperOrder
| 字段 | 类型 | 说明 |
|---|---|---|
| paperOrderId | string | 模拟订单ID |
| decisionId | string | 决策ID |
| status | enum | `NEW/PARTIALLY_FILLED/FILLED/CANCELED/REJECTED` |
| executedQty | string | 已成交数量 |
| avgPrice | string | 成交均价 |
| realizedPnl | string | 已实现PnL |
| fee | string | 模拟手续费 |
| updatedAt | long | 更新时间 |

## 6.5 复盘实验室（Replay）

### 接口清单
1. `POST /api/v1/agent/replays`
2. `GET /api/v1/agent/replays`
3. `GET /api/v1/agent/replays/{replayId}`

### ReplayCreateRequest
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| decisionId | string | Y | 决策ID |
| paperOrderId | string | Y | 模拟订单ID |
| symbol | string | Y | 交易对 |
| notes | string | N | 用户备注 |

### ReplayReport
| 字段 | 类型 | 说明 |
|---|---|---|
| replayId | string | 复盘ID |
| decisionId | string | 决策ID |
| paperOrderId | string | 模拟订单ID |
| metrics.entryQuality | number | 入场质量 |
| metrics.executionDriftPct | number | 执行偏差 |
| metrics.riskDiscipline | number | 风险纪律 |
| timeline | array | 关键事件时间线 |
| nextActions | string[] | 下次改进动作 |
| createdAt | long | 创建时间 |

## 6.6 Skill榜与我的成长（Score/Growth）

### 接口清单
1. `GET /api/v1/agent/score/profile`
2. `GET /api/v1/agent/score/leaderboard`
3. `GET /api/v1/agent/growth/weekly-plan`
4. `POST /api/v1/agent/growth/weekly-plan`

### ScoreProfile
| 字段 | 类型 | 说明 |
|---|---|---|
| subjectType | enum | `HUMAN/AGENT` |
| subjectId | string | 用户或Agent |
| period | string | 周期，如 `2026-W10` |
| skillScore | number | 总分 |
| dimensions | object | RAR/DDC/EXEC/CONS/RISK/REPLAY |
| deltaVsLastPeriod | number | 较上期变化 |
| effectiveSampleSize | int | 有效样本数 |
| scoreStatus | enum | `TEMP/FORMAL` |

### LeaderboardItem
| 字段 | 类型 | 说明 |
|---|---|---|
| rank | int | 排名 |
| subjectType | enum | HUMAN/AGENT |
| subjectId | string | 标识 |
| skillScore | number | 分数 |
| weeklyPnlPct | number | 周收益率 |
| maxDrawdownPct | number | 最大回撤 |
| effectiveSampleSize | int | 有效样本 |

## 7. Kafka 事件总线规范（Topic级）

### 7.1 事件封装（统一 Envelope）
```json
{
  "eventId": "evt_20260303_00001",
  "eventType": "DecisionValidated",
  "eventVersion": "v1",
  "traceId": "tr_abc123",
  "sourceService": "agent-training-core",
  "occurredAt": 1772500000000,
  "userId": 1001,
  "accountId": 2001,
  "data": {}
}
```

### 7.2 Topic 清单（训练域）

| Topic | Key | 分区建议 | 生产者 | 消费者 | 说明 |
|---|---|---|---|---|---|
| `agent.decision.created` | `decisionId` | 6 | agent-training-core | replay/score模块 | 决策创建 |
| `agent.decision.validated` | `decisionId` | 6 | agent-training-core | paper-exec模块 | 决策校验结果 |
| `agent.paper.order.command.{symbol}` | `paperOrderId` | symbol单分区 | agent-training-core | paper-exec模块 | 模拟下单指令 |
| `agent.paper.order.state.{symbol}` | `paperOrderId` | symbol单分区 | paper-exec模块 | replay/score模块 | 模拟订单状态变更 |
| `agent.paper.fill.{symbol}` | `paperOrderId` | symbol单分区 | paper-exec模块 | replay/score模块 | 模拟成交事件 |
| `agent.replay.generated` | `replayId` | 6 | replay模块 | growth/score模块 | 复盘报告生成 |
| `agent.score.updated` | `subjectId` | 12 | score模块 | leaderboard/growth模块 | 分数更新 |
| `agent.growth.task.updated` | `userId` | 12 | growth模块 | my-growth推送模块 | 成长任务更新 |
| `agent.arena.session.event` | `sessionId` | 6 | arena模块 | leaderboard模块 | 竞技场生命周期 |
| `agent.skill.version.event` | `skillId` | 6 | skill模块 | decision模块 | 策略版本发布/回滚 |

### 7.3 市场只读消费 Topic（现有）

| Topic Pattern | 生产者 | 用途 |
|---|---|---|
| `market.kline.{symbol}.{interval}` | market-price-core | 决策上下文 |
| `market.depth.{symbol}` | market-price-core | 模拟撮合参考深度 |
| `market.ticker.{symbol}` | market-price-core | 24h统计与波动状态 |
| `market.trade.{symbol}` | market-price-core | 交易流强度特征 |

说明:
1. 训练域仅读取以上市场 Topic，不向真实交易 Topic 回写。
2. 训练域与真实 OMS/撮合/账本 Topic 逻辑隔离，保证生产安全边界。

## 8. 事件字段字典（核心）

## 8.1 `DecisionValidated.data`
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| decisionId | string | Y | 决策ID |
| passed | boolean | Y | 是否通过 |
| violations | array | N | 未通过原因 |
| normalizedIntent | object | Y | 标准化后可执行参数 |

## 8.2 `PaperOrderStateChanged.data`
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| paperOrderId | string | Y | 模拟订单ID |
| decisionId | string | Y | 决策ID |
| symbol | string | Y | 交易对 |
| status | string | Y | NEW/FILLED 等 |
| executedQty | string | N | 已成交量 |
| avgPrice | string | N | 均价 |
| realizedPnl | string | N | 已实现盈亏 |
| eventTime | long | Y | 事件时间 |

## 8.3 `ReplayGenerated.data`
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| replayId | string | Y | 复盘ID |
| decisionId | string | Y | 决策ID |
| paperOrderId | string | Y | 订单ID |
| scoreBreakdown | object | Y | 各维度评分 |
| nextActions | string[] | Y | 改进动作 |

## 8.4 `ScoreUpdated.data`
| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| subjectType | string | Y | HUMAN/AGENT |
| subjectId | string | Y | 主体标识 |
| period | string | Y | 周期 |
| skillScore | number | Y | 总分 |
| dimensions | object | Y | 分维度分数 |
| effectiveSampleSize | int | Y | 有效样本 |
| scoreStatus | string | Y | TEMP/FORMAL |

## 9. 训练域关键时序（可直接实现）

### 9.1 AI建议填单
1. `POST /api/v1/agent/decisions/preview` -> 写 `agent.decision.created`
2. `POST /api/v1/agent/decisions/validate` -> 写 `agent.decision.validated`
3. `POST /api/v1/agent/decisions/adopt` -> 生产 `agent.paper.order.command.{symbol}`
4. paper-exec 消费后产出 `agent.paper.order.state.{symbol}` 与 `agent.paper.fill.{symbol}`
5. replay 模块消费成交事件并生成 `agent.replay.generated`
6. score 模块更新分数并写 `agent.score.updated`

### 9.2 复盘到成长计划
1. `POST /api/v1/agent/replays` 触发复盘生成
2. replay 产出 `nextActions`
3. growth 模块订阅 `agent.replay.generated`，生成周任务
4. 发布 `agent.growth.task.updated` 给成长页

## 10. 数据库最小表清单（新服务）
1. `t_agent_skill`
2. `t_agent_skill_version`
3. `t_agent_decision`
4. `t_agent_decision_validation`
5. `t_agent_paper_order`
6. `t_agent_paper_fill`
7. `t_agent_replay_report`
8. `t_agent_score_snapshot`
9. `t_agent_growth_task`

## 11. 与真实下单链路隔离清单（上线前检查）
1. 禁止依赖 `oms-core` 下单接口。
2. 禁止生产 `order-event-*`、`trade-event`、`trade-entry-*`。
3. 禁止写真实账本相关表。
4. 服务鉴权 scope 独立（建议 `SCOPE_AGENT_TRAINING`）。
5. 灰度环境先开 `PAPER_ONLY=true`。

## 12. 研发拆分建议（可直接拉任务）

### 12.1 agent-training-core 内部分工
1. `Skill模块`: 策略定义、版本发布、回滚。
2. `Decision模块`: 预览、校验、采纳、状态机。
3. `PaperExec模块`: 模拟订单状态机与成交仿真。
4. `Replay模块`: 复盘报告与改进行动输出。
5. `Score模块`: Skill Score 聚合计算。
6. `Growth模块`: 周计划与成长任务。
7. `Arena模块`: 会话化竞赛与榜单事件。

### 12.2 外围协同任务
1. `api-gateway`: 增加 `/api/v1/agent/** -> lb://agent-training-core` 路由（Nacos）。
2. `init_kafka.sh`: 增加训练域 Topic 创建段。
3. `前端`: 将 `skills/replay-lab/ai-console/skillboard/my-growth` 接口指向新域。
4. `监控`: 增加训练域 Topic 延迟与消费积压监控。

## 13. 本周落地顺序（建议）
1. 先落 `ContextPack + Skill + Decision(validate)` 三组接口。
2. 再落 `PaperExec`（仅 LIMIT/MARKET 两种模式）。
3. 再落 `Replay + Score` 最小闭环。
4. 最后接入 `Growth + Arena`。
