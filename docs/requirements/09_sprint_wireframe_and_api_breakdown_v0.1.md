# AI共训交易系统 Sprint拆解（页面线框 + 接口清单）

## 1. 文档信息
- 版本: `v0.1`
- 日期: `2026-03-02`
- 对齐文档: `08_ai_cotraining_trading_prd_v0.1.md`
- 目标: 将PRD转化为可研发拆分的页面与接口方案

## 2. 总体设计原则
1. 优先闭环，不优先功能数量。
2. 决策层与执行层强隔离，LLM不持密钥。
3. 所有用户动作可审计（decision_id / trace_id / order_id 可关联）。
4. 页面以“训练-执行-复盘-迭代”链路组织，而非信息堆叠。

## 3. Sprint 1 页面线框（组件级）
目标范围: `模拟训练 + AI决策台 + 一键填单 + 决策日志`

### 3.1 页面A: 模拟训练（`/training`）
#### 页面目标
让用户在低风险场景中完成可度量训练任务。

#### 组件结构
1. 顶部栏
- 训练模式切换: `自由模拟 | 场景训练 | 任务训练`
- 交易对选择
- 训练状态: `进行中/已完成`

2. 左侧任务区
- 训练任务卡（目标、约束、完成条件）
- 风险约束卡（杠杆上限、单笔风险、最大日损）
- 训练计时器

3. 中央交易区（复用现有交易页核心组件）
- K线 + 深度 + 成交
- 下单面板
- 一键填单入口（AI建议）

4. 右侧评分区
- 当前训练Skill Score（实时）
- 分项分数（收益质量/风控纪律/执行稳定）
- 训练进度条

5. 底部结算区
- 训练结果摘要
- 错误标签（过度交易、止损延迟、追价）
- “进入复盘实验室”按钮

#### 核心交互
1. 选择任务 -> 锁定风险参数 -> 开始训练。
2. 使用手动或AI建议完成交易。
3. 点击“结束训练”生成结果卡。
4. 结果卡可一键进入复盘。

#### 埋点（最小集）
1. `training_started`
2. `training_order_submitted`
3. `training_finished`
4. `training_to_replay_clicked`

### 3.2 页面B: AI决策台（`/ai-console`）
#### 页面目标
让AI建议可解释、可采纳、可追踪。

#### 组件结构
1. 顶部过滤条
- 交易对
- 时间周期
- 市场状态标签（趋势/震荡/高波动）

2. 候选决策列表
- 决策方向、置信度、建议进场区间、止损止盈
- 风险暴露变化（执行前/后）
- 适用条件提示

3. 决策详情面板
- 决策理由列表（至少3条）
- 证据快照（24h高低、成交偏向、波动）
- 决策版本（strategy_version）

4. 执行面板
- “采纳建议并填单”按钮（仅填充，不自动下单）
- 参数编辑区（价格、数量、杠杆）
- 风控校验结果提示（通过/阻断原因）

5. 决策日志Tab
- 列表字段: `decision_id, symbol, action, confidence, status, created_at`
- 点击查看完整请求/响应快照

#### 核心交互
1. 用户选择候选建议。
2. 系统展示解释与风险影响。
3. 用户点击一键填单，参数写入交易面板。
4. 用户确认提交后产生执行记录，回流日志。

#### 埋点（最小集）
1. `ai_decision_viewed`
2. `ai_suggestion_applied`
3. `ai_order_submitted`
4. `ai_decision_rejected_by_policy`

### 3.3 一键填单（嵌入交易页）
#### 组件要求
1. 当前已存在按钮保留:
- `采纳建议并填单`
- `加入复盘实验室`
2. 新增显示:
- 当前决策ID（短ID）
- 风控校验状态

#### 交互要求
1. 点击一键填单只更新表单字段，不直接发单。
2. 用户修改参数后仍可提交。
3. 风控拒绝时给出明确拒绝原因和建议动作。

### 3.4 决策日志（可做AI决策台二级Tab）
#### 组件结构
1. 日志筛选: symbol / strategy / status / date range。
2. 日志表格: 决策、执行、结果三段信息。
3. 详情抽屉: JSON快照、风险校验链路、trace_id。

#### 验收口径
1. 任一订单可追溯到决策日志。
2. 任一决策可追溯到是否提交执行与执行结果。

## 4. Sprint 2 页面线框（组件级）
目标范围: `Skill榜 + 我的成长 + 规则透明化 + AI竞技场v1`

### 4.1 页面C: Skill榜（`/skillboard`）
#### 组件结构
1. 榜单分组切换: `总榜 | 人类榜 | AI榜 | 新手榜 | 稳健榜`
2. 时间维度切换: `日 | 周 | 月`
3. 榜单表格字段:
- rank
- account/strategy
- skill_score
- 子分项（收益/风控/执行/稳定）
- 本周变化
4. 条目详情抽屉:
- 分数构成
- 近7天曲线
- 风险事件

### 4.2 页面D: 我的成长（`/my-growth`）
#### 组件结构
1. 顶部能力总览
- 当前Skill Score
- 周变化
- 与目标分差
2. 能力雷达图
- 方向判断
- 风险纪律
- 执行一致性
- 策略稳定性
3. 成长时间轴
- 关键交易
- 关键复盘
- 策略版本变更点
4. 下周计划卡
- 建议训练主题
- 建议参数调整
- 预计收益/风险影响（区间）

### 4.3 页面E: 规则（`/rules`）
#### 组件结构
1. 评分规则
2. 竞技规则
3. 风控规则
4. 审计与申诉入口

#### 要求
1. 规则版本号可见。
2. 变更记录可见。

### 4.4 页面F: AI竞技场v1（`/arena/live`）
#### 组件结构
1. 对战大厅
- AI vs AI
- AI vs 人
2. 实时战绩看板
- PnL曲线
- 回撤曲线
- Skill Score曲线
3. 策略画像卡
- 风格标签（激进/稳健）
- 核心参数
4. 围观操作
- 关注
- 模拟跟随
- 加入复盘

## 5. 后端接口清单（按现有微服务拆分）

## 5.1 可直接复用的现有接口
| 域 | 服务 | 方法 | 路径 | 用途 |
|---|---|---|---|---|
| 行情深度 | market-price-core | GET | `/api/v1/depth` | 训练/决策上下文 |
| 最近成交 | market-price-core | GET | `/api/v1/trades` | 成交流分析 |
| K线 | market-price-core | GET | `/api/v1/klines` | 周期分析/复盘 |
| 24h统计 | market-price-core | GET | `/api/v1/ticker/24hr` | 决策证据 |
| 最优盘口 | market-price-core | GET | `/api/v1/bookTicker` | 执行参考 |
| 下单 | oms-core（经网关） | POST | `/api/order/create` | 提交订单 |
| 撤单 | oms-core（经网关） | POST | `/api/order/cancel` | 撤单 |
| 订单列表 | oms-core（经网关） | GET | `/api/v1/oms/order/list` | 训练回看 |
| 账户余额 | snapshot-account-core | GET | `/api/v1/account/balance` | 风险暴露计算 |
| 持仓列表 | position-snapshot-core | GET | `/api/v1/position/list` | 持仓风险 |
| 交易聚合数据 | user-core | GET | `/api/v1/trading/dashboard` | 页面首屏聚合 |
| 个人中心概览 | user-core | GET | `/api/v1/user/profile/overview` | 我的成长基础数据 |
| 历史交易 | user-core | GET | `/api/v1/user/profile/trades` | 复盘和成长曲线 |
| 公共推送统计 | public-push-core | GET | `/api/v1/stats` | 竞技场健康指标 |

> 网关路径以 Nacos `api-gateway-dev.yml` 为准，新增路由必须走Nacos配置，不写死代码。

## 5.2 Sprint 1 新增接口（建议）
### A. `ai-decision-core`（新增服务）
1. `POST /api/v1/ai/decision/preview`
- 输入: `ContextPack`
- 输出: `DecisionIntent[]`
- 用途: AI候选建议生成

2. `POST /api/v1/ai/decision/validate`
- 输入: `DecisionIntent`
- 输出: `PolicyCheckResult`
- 用途: 风控预校验

3. `POST /api/v1/ai/decision/log`
- 输入: 决策与执行结果
- 输出: `decision_id`
- 用途: 决策审计落库

4. `GET /api/v1/ai/decision/logs`
- 参数: symbol, strategyId, status, page
- 输出: 决策日志列表
- 用途: 决策日志Tab

### B. `user-core`（增强聚合）
1. `GET /api/v1/training/context`
- 聚合行情 + 账户 + 持仓 + 风险阈值
- 供AI决策台、模拟训练调用

2. `GET /api/v1/training/session/{sessionId}/summary`
- 返回训练结算摘要与错误标签

### C. `replay-core`（新增业务接口，区别于当前运维replay）
1. `POST /api/v1/replay/create`
- 输入: `decision_id` 或 `order_id`
- 输出: `replay_id`

2. `GET /api/v1/replay/{replay_id}`
- 输出: 市场快照 + 决策 + 执行 + 结果归因

## 5.3 Sprint 2 新增接口（建议）
### A. `skill-score-core`（新增服务）
1. `GET /api/v1/skill/leaderboard`
- 参数: scope, period, type(human/ai/all)
- 输出: 榜单分页

2. `GET /api/v1/skill/profile/{accountId}`
- 输出: 当前分数、子分项、近7/30天趋势

3. `POST /api/v1/skill/recalculate`
- 用途: 管理端重算（灰度阶段）

### B. `growth-core`（可先放user-core，后续拆分）
1. `GET /api/v1/growth/overview`
- 输出: 能力雷达 + 周变化 + 差距分析

2. `GET /api/v1/growth/timeline`
- 输出: 关键事件时间轴（交易/复盘/改参）

3. `GET /api/v1/growth/next-actions`
- 输出: 下周训练建议

### C. `arena-core`（新增服务）
1. `GET /api/v1/arena/live`
- 输出: 当前对战列表、实时分数、PnL

2. `GET /api/v1/arena/match/{matchId}`
- 输出: 单场详情（曲线、事件、策略信息）

3. `POST /api/v1/arena/follow-sim`
- 输入: matchId/strategyId
- 输出: 跟随会话ID（模拟）

### D. `rule-core`（可先放静态配置+user-core读取）
1. `GET /api/v1/rules/current`
2. `GET /api/v1/rules/changelog`

## 6. 鉴权与安全约束（接口层）
1. 所有交易、持仓、账户接口必须依赖网关JWT鉴权并透传 `X-User-Id`。
2. 决策接口分级:
- 预览可匿名/登录（按产品策略）
- 填单、日志写入必须登录
3. LLM调用必须走服务端代理，不允许前端直连模型密钥。
4. 关键事件必须写审计:
- decision_created
- policy_checked
- order_submitted
- order_rejected
- replay_created

## 7. 事件总线建议（Kafka）
1. `ai-decision-request`
2. `ai-decision-result`
3. `training-session-event`
4. `skill-score-snapshot`
5. `arena-match-event`
6. `replay-created`

## 8. Sprint级交付清单
### Sprint 1 DoD
1. 模拟训练页面可创建并结束训练会话。
2. AI决策台可展示候选建议与解释。
3. 一键填单只填参数，不自动发单。
4. 决策日志可查询且可追溯到订单。

### Sprint 2 DoD
1. Skill榜可按人类/AI/混合查看并分页。
2. 我的成长可输出能力雷达和时间轴。
3. 规则页可展示版本与变更记录。
4. 竞技场v1可展示实时对战与详情。

## 9. 研发排期建议（2周/Sprint）
### Sprint 1（2周）
1. Week 1: 训练会话 + 决策预览 + 一键填单
2. Week 2: 决策日志 + 训练结算 + 端到端联调

### Sprint 2（2周）
1. Week 1: Skill榜 + 我的成长接口与页面
2. Week 2: 规则页 + 竞技场v1 + 联调验收

## 10. 作为“资深交易员 + AI使用者”的系统进化方法
1. 每周固定一个训练主题（只攻一个弱项）。
2. 每天执行“AI建议 10 笔 + 手动对照 10 笔”。
3. 每天收盘复盘3笔关键交易并记录改进动作。
4. 每周以Skill分项变化衡量是否进步，而非只看收益率。
5. 每两周升级策略版本，保留回滚能力。

