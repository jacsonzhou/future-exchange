# 面试核心流程详解 - 强平ADL完整时序

> 本文档是面试必考内容的"标准答案"，建议熟读并画图练习

---

## 1. 订单全链路流程（基础必会）

### 1.1 流程图

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  Client │───▶│ API GW  │───▶│ OMS-Core│───▶│Hard-Risk│───▶│   Kafka │───▶│  Match  │
│         │    │  (8080) │    │  (8081) │    │ (8082)  │    │         │    │Engine   │
└─────────┘    └─────────┘    └────┬────┘    └─────────┘    └─────────┘    │ (8083)  │
                                   │                                        └────┬────┘
                                   │                                             │
                                   │◀────────────────────────────────────────────┤
                                   │              订单状态回传                    │
                                   │                                             │
                              ┌────▼────┐                                        │
                              │ MySQL   │    ┌─────────┐    ┌─────────┐         │
                              │ t_order │◀───│ Ledger  │◀───│  Trade  │◀────────┘
                              └─────────┘    │ (8084)  │    │  Event  │
                                             └─────────┘    └─────────┘
```

### 1.2 详细步骤

```
Step 1: Client下单 (0ms)
POST /api/order/create
{
    "userId": 1001,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000
}

Step 2: API Gateway (2ms)
├── 参数校验
├── 限流检查
├── 设置TraceId
└── 转发到OMS

Step 3: OMS-Core (10ms)
├── 生成orderId (雪花算法)
├── 持久化订单 (MySQL)
├── 查询用户余额
├── 计算所需保证金
└── 调Hard Risk检查

Step 4: Hard Risk (15ms)
├── 检查余额充足性
├── 检查杠杆倍数
├── 检查黑名单
└── 返回检查结果

Step 5: OMS-Core (20ms)
├── 执行保证金预扣 (Redis)
├── 更新订单状态: RISK_PASSED
├── 发布到Kafka (order-topic)
└── 返回客户端

Step 6: Match Engine (25ms)
├── 消费Kafka订单
├── 写入RingBuffer
├── 单线程撮合
├── 生成成交事件
├── 发布trade-event
└── 回传订单状态

Step 7: Ledger-Core (30ms)
├── 消费trade-event
├── 生成双录分录
├── 记账到MySQL
└── 发布资金变更事件

Step 8: Snapshot (35ms)
├── 消费资金变更
├── 更新Redis缓存
└── 更新MySQL快照

Step 9: Client收到响应 (40ms)
{
    "success": true,
    "orderId": 123456789,
    "status": "SENT_TO_MATCH"
}
```

### 1.3 面试话术

```
面试官："订单从发起到成交的全流程是怎样的？"

你回答：
"我分6个阶段来讲：

阶段1：接入层（API Gateway）
- 参数校验、限流、认证
- 设置分布式追踪TraceId

阶段2：订单层（OMS-Core）
- 生成全局唯一订单ID（雪花算法）
- 持久化订单到MySQL
- 查询用户余额和持仓
- 计算所需保证金

阶段3：风控层（Hard Risk）
- 同步检查余额充足性
- 检查杠杆倍数是否超限
- 检查用户是否在黑名单

阶段4：预扣层
- Redis预扣保证金（防止并发超卖）
- 更新订单状态为RISK_PASSED

阶段5：撮合层（Match Engine）
- Kafka双通道传输
- Disruptor无锁队列
- 单线程撮合保证顺序
- 生成成交事件

阶段6：结算层（Ledger）
- 消费成交事件
- 双录分录记账（借贷必平衡）
- 更新账户快照

整个链路延迟约40ms，其中撮合<1ms。"
```

---

## 2. 强平ADL完整流程（核心必会）

### 2.1 触发条件

```
何时触发强平？
├── 当保证金率 < 维持保证金率(10%)
│
保证金率计算公式：
├── 保证金率 = (保证金 + 未实现盈亏) / 持仓名义价值
│
举例：
├── 用户A：多仓 1 BTC @ $50,000
├── 杠杆：10x
├── 初始保证金：$5,000
├── 当前价格：$45,000
├── 未实现盈亏：-$5,000
├── 保证金率 = ($5,000 - $5,000) / $45,000 = 0% ❌
└── 触发强平！
```

### 2.2 强平ADL时序图（面试要画这个）

```
时间轴    Margin-Mode    Liquidation    Match Engine    Insurance    ADL-Core    Ledger
  │         (8088)         (8086)         (8083)          Fund        (8089)      (8084)
  │           │              │              │               │           │          │
T+0         价格暴跌 $50K → $42K (-16%)
  │           │              │              │               │           │          │
  │           │              │              │               │           │          │
T+5         扫描保证金率
  │           │              │              │               │           │          │
  │           ├─ 用户B: 7.5% < 10% ⚠️
  │           ├─ 用户C: 9.8% < 10% ⚠️
  │           └─ 用户D: 6.2% < 10% ⚠️
  │           │              │              │               │           │          │
T+10        发布强平触发事件
  │           ──────────────────────────────▶                │           │          │
  │                        liquidation-trigger-topic
  │           │              │              │               │           │          │
T+15                       消费事件
  │           │              │              │               │           │          │
T+20                       幂等性检查 (Redis)
  │           │              │              │               │           │          │
T+25                       创建强平订单
  │           │              │              │               │           │          │
  │           │              ├─ orderId: 8000001
  │           │              ├─ userId: 1002
  │           │              ├─ side: SELL (与持仓反向)
  │           │              ├─ type: MARKET
  │           │              └─ qty: 20 BTC
  │           │              │              │               │           │          │
T+30                       调用OMS (跳过风控)
  │           │              ───────────────▶                │           │          │
  │           │              │              │               │           │          │
T+80                                      撮合
  │           │              │              │               │           │          │
  │           │              │              ├─ 成交18 BTC @ $42,000
  │           │              │              └─ 剩余2 BTC (深度不足)
  │           │              │              │               │           │          │
T+100                      部分成交处理
  │           │              │              │               │           │          │
  │           │              ├─ 计算已成交盈亏: -$144,000
  │           │              ├─ 计算穿仓损失: $36,000
  │           │              │              │               │           │          │
T+120                                    申请保险基金赔付
  │           │              │              │               │           │          │
  │           │              │              ────────────────▶           │          │
  │           │              │              │               │           │          │
T+150                                    赔付完成
  │           │              │              │◀──────────────┤           │          │
  │           │              │              │   赔付$36,000 │           │          │
  │           │              │              │               │           │          │
  │           │              │◀─────────────                │           │          │
  │           │              │  继续处理剩余2 BTC
  │           │              │              │               │           │          │
  │           │              │  (循环处理直到全部成交)
  │           │              │              │               │           │          │
  │           │              │              │               │           │          │
T+500                      用户V强平完成
  │           │              │              │               │           │          │
  │           │              ├─ 穿仓损失: $500,000
  │           │              ├─ 保险基金余额: $182,000
  │           │              ├─ 实际赔付: $182,000
  │           │              └─ 剩余损失: $318,000 ❌
  │           │              │              │               │           │          │
T+520                                    发布强平完成事件
  │           │              │              │               │           │          │
  │           │              ───────────────────────────────────────────▶          │
  │                        liquidation-completed (adlRequired=true)
  │           │              │              │               │           │          │
T+550                                  ADL-Core消费事件
  │           │              │              │               │           │          │
T+600                                  查询ADL排名队列
  │           │              │              │               │           │          │
  │           │              │              │               │           ├─ WHALE: Rank 1, 得分15000
  │           │              │              │               │           ├─ 用户D: Rank 2, 得分8000
  │           │              │              │               │           └─ 用户E: Rank 3, 得分5000
  │           │              │              │               │           │          │
T+650                                  计算分摊比例
  │           │              │              │               │           │          │
  │           │              │              │               │           ├─ WHALE: 53.57% → $170,357
  │           │              │              │               │           ├─ 用户D: 28.57% → $90,857
  │           │              │              │               │           └─ 用户E: 17.86% → $56,786
  │           │              │              │               │           │          │
T+700                                  执行ADL减仓
  │           │              │              │               │           │          │
  │           │              │              │               │           ├─ WHALE减仓4.06 BTC @ $42K
  │           │              │              │               │           │          │
T+750                                  调用Clearing记账
  │           │              │              │               │           │          │
  │           │              │              │               │           └──────────▶
  │           │              │              │               │                      │
T+800                                                          双录分录记账
  │           │              │              │               │                      │
  │           │              │              │               │                      ├─ 借：WHALE持仓 $170,357
  │           │              │              │               │                      └─ 贷：系统持仓 $170,357
  │           │              │              │               │                      │
  │           │              │              │               │           ◀──────────┤
  │           │              │              │               │           │  记账完成
  │           │              │              │               │           │          │
T+900                                  通知Position更新
  │           │              │              │               │           │          │
T+950                                  继续执行用户D、E的ADL
  │           │              │              │               │           │          │
  │           │              │              │               │           │          │
T+1100                                 ADL完成
  │           │              │              │               │           │          │
  │           │              │              │               │           ├─ 总计减仓: 7.57 BTC
  │           │              │              │               │           ├─ 总计分摊: $318,000
  │           │              │              │               │           └─ 发布ADL完成事件
  │           │              │              │               │           │          │
  │           │              │              │               │           │          │
  └───────────┴──────────────┴──────────────┴───────────────┴───────────┴──────────┘
                              ✅ 闭环完成，资金零损失
```

### 2.3 关键代码逻辑

#### 2.3.1 强平订单跳过风控

```java
// 普通订单流程
public void createNormalOrder(OrderRequest req) {
    // 1. 风控检查
    if (!riskService.check(req)) {
        throw new RiskException("Risk check failed");
    }
    // 2. 保证金预扣
    if (!marginService.preHold(req)) {
        throw new MarginException("Insufficient margin");
    }
    // 3. 发送到撮合
    sendToMatchEngine(req);
}

// 强平订单流程（关键区别）
public void createLiquidationOrder(LiquidationRequest req) {
    // ⚠️ 强平订单跳过风控！
    // 原因：用户已经爆仓，无法通过正常风控
    // 强平是系统保护机制，不是用户主动交易
    
    // 1. 直接创建订单
    Order order = new Order();
    order.setStatus(OrderStatus.LIQUIDATION_PENDING);
    order.setUserId(req.getUserId());
    order.setSide(reverse(req.getPositionSide())); // 反向
    order.setOrderType(OrderType.MARKET);
    order.setQuantity(req.getQty());
    
    // 2. 跳过风控检查
    // skip: riskService.check()
    
    // 3. 跳过保证金预扣
    // skip: marginService.preHold()
    
    // 4. 直接发送到撮合
    sendToMatchEngine(order);
}
```

#### 2.3.2 部分成交处理

```java
public class PartialLiquidationHandler {
    
    public void handlePartialFill(Long liquidationOrderId, FillResult fill) {
        // 1. 计算已成交比例
        BigDecimal fillRatio = new BigDecimal(fill.getFilledQty())
            .divide(new BigDecimal(fill.getTotalQty()), 8, HALF_UP);
        
        // 2. 计算已成交部分盈亏
        Long filledPnl = calculatePnl(
            fill.getFilledQty(),
            fill.getAvgPrice(),
            fill.getEntryPrice(),
            fill.getSide()
        );
        
        // 3. 计算已成交部分穿仓损失
        Long filledMargin = calculateMargin(fill.getFilledQty());
        Long bankruptcyLoss = filledPnl + filledMargin;
        
        // 4. 按比例申请保险基金赔付
        Long insuranceClaim = bankruptcyLoss.multiply(fillRatio).longValue();
        
        if (insuranceClaim > 0) {
            insuranceFundService.claim(
                liquidationOrderId,
                insuranceClaim,
                "Partial fill coverage"
            );
        }
        
        // 5. 剩余仓位继续强平
        Long remainingQty = fill.getTotalQty() - fill.getFilledQty();
        if (remainingQty > MIN_SPLIT_QTY) {
            createRemainingLiquidationOrder(remainingQty);
        }
    }
}
```

#### 2.3.3 ADL分摊计算

```java
public class AdlDistributionCalculator {
    
    public List<AdlDistribution> calculate(
            String symbol, 
            String oppositeSide, 
            BigDecimal remainingLoss) {
        
        // 1. 获取ADL排名队列
        List<AdlRanking> rankings = adlRankingService
            .getRankings(symbol, oppositeSide);
        
        // 2. 计算总ADL得分
        BigDecimal totalScore = rankings.stream()
            .map(AdlRanking::getAdlScore)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // 3. 按比例分摊
        List<AdlDistribution> distributions = new ArrayList<>();
        for (AdlRanking ranking : rankings) {
            BigDecimal ratio = ranking.getAdlScore()
                .divide(totalScore, 8, HALF_UP);
            BigDecimal share = remainingLoss.multiply(ratio);
            
            distributions.add(new AdlDistribution(
                ranking.getUserId(),
                share,
                ratio
            ));
        }
        
        return distributions;
    }
}

// ADL得分计算公式（面试要问）
public class AdlScoreCalculator {
    
    public BigDecimal calculate(Position position) {
        // 得分 = 盈利比例 × 杠杆 × 持仓权重
        // 
        // 为什么盈利多的先被选？
        // 因为盈利多的用户承担能力强，先分摊给他们公平
        //
        // 为什么杠杆高的先被选？
        // 因为杠杆高意味着风险高，先分摊给高风险用户合理
        
        BigDecimal pnlRatio = position.getUnrealizedPnl()
            .divide(position.getNotionalValue(), 8, HALF_UP);
        
        BigDecimal leverage = position.getLeverage();
        
        BigDecimal positionWeight = calculatePositionWeight(position);
        
        return pnlRatio.abs()
            .multiply(leverage)
            .multiply(positionWeight);
    }
}
```

### 2.4 面试标准回答

```
面试官："请详细说明强平和ADL的流程"

你回答：
"强平和ADL是风险控制的最后防线，我分5个阶段详细说明：

阶段1：触发（Margin-Mode-Core）
- 当标记价格变化时，扫描所有仓位保证金率
- 公式：保证金率 = (保证金 + 未实现盈亏) / 名义价值
- 当保证金率 < 10%时，发布liquidation-trigger-topic

阶段2：处理（Liquidation-Core）
- 消费触发事件，做幂等性检查（Redis 5分钟窗口）
- 创建强平订单：市价单、与持仓反向
- ⚠️ 关键：跳过风控和保证金预扣（用户已爆仓）
- 提交OMS，进入撮合队列

阶段3：成交（Match Engine）
- 市价单撮合，可能部分成交
- 实时推送成交状态
- 如果买盘深度不足，会多次部分成交

阶段4：结算（Liquidation-Core）
- 计算已实现盈亏
- 计算穿仓损失 = 已实现亏损 + 初始保证金
- 申请保险基金赔付（按成交比例）
- 如果保险基金不足，剩余损失触发ADL

阶段5：ADL（ADL-Core）
- 消费强平完成事件（adlRequired=true）
- 查询ADL排名队列（按盈利+杠杆排序）
- 按比例分摊剩余损失
- 执行减仓，调用Clearing记账
- 发布ADL完成事件

整个流程约2-3秒，确保穿仓损失由保险基金和ADL共同覆盖，
最终实现资金零损失。"
```

---

## 3. 双录分录（资金安全核心）

### 3.1 原理

```
什么是双录分录？
├── 每笔业务生成两条分录
├── 一条借方，一条贷方
├── 借贷金额相等
└── 保证：Σ借方 = Σ贷方

为什么用双录？
├── 可追溯：每笔资金变动都有记录
├── 可审计：借贷平衡，数学上可验证
├── 防篡改：分录不可修改，只能冲正
└── 金融标准：银行、证券都用的方案
```

### 3.2 示例分录

```
场景1：用户买入开仓 1 BTC @ $50,000
┌─────────────┬─────────┬─────────┬─────────────┐
│ 科目        │ 借方    │ 贷方    │ 说明        │
├─────────────┼─────────┼─────────┼─────────────┤
│ 持仓资产    │ 50,000  │         │ 增加多头持仓│
│ 保证金      │         │ 50,000  │ 冻结保证金  │
└─────────────┴─────────┴─────────┴─────────────┘
借贷平衡：50,000 = 50,000 ✅

场景2：用户卖出平仓 1 BTC @ $51,000 (盈利$1,000)
┌─────────────┬─────────┬─────────┬─────────────┐
│ 科目        │ 借方    │ 贷方    │ 说明        │
├─────────────┼─────────┼─────────┼─────────────┤
│ 保证金      │ 51,000  │         │ 收回保证金  │
│ 持仓资产    │         │ 50,000  │ 减少持仓    │
│ 已实现盈亏  │         │ 1,000   │ 记录盈利    │
└─────────────┴─────────┴─────────┴─────────────┘
借贷平衡：51,000 = 50,000 + 1,000 ✅

场景3：强平成交（穿仓$1,000）
┌─────────────┬─────────┬─────────┬─────────────┐
│ 科目        │ 借方    │ 贷方    │ 说明        │
├─────────────┼─────────┼─────────┼─────────────┤
│ 保证金      │ 49,000  │         │ 收回剩余    │
│ 持仓资产    │         │ 50,000  │ 减少持仓    │
│ 穿仓损失    │ 1,000   │         │ 记录损失    │
└─────────────┴─────────┴─────────┴─────────────┘
借贷平衡：49,000 + 1,000 = 50,000 ✅

场景4：保险基金赔付
┌─────────────┬─────────┬─────────┬─────────────┐
│ 科目        │ 借方    │ 贷方    │ 说明        │
├─────────────┼─────────┼─────────┼─────────────┤
│ 穿仓损失    │         │ 1,000   │ 冲减损失    │
│ 保险基金    │ 1,000   │         │ 基金支出    │
└─────────────┴─────────┴─────────┴─────────────┘
借贷平衡：1,000 = 1,000 ✅

场景5：ADL减仓
┌─────────────┬─────────┬─────────┬─────────────┤
│ 科目        │ 借方    │ 贷方    │ 说明        │
├─────────────┼─────────┼─────────┼─────────────┤
│ 对手持仓    │ 1,000   │         │ 增加对手持仓│
│ 目标持仓    │         │ 1,000   │ 减少目标持仓│
└─────────────┴─────────┴─────────┴─────────────┘
借贷平衡：1,000 = 1,000 ✅
```

### 3.3 面试回答

```
面试官："怎么保证资金安全？"

你回答：
"我们采用双录分录机制，这是金融行业的标准做法：

1. 原理
- 每笔业务生成两条分录：一条借方，一条贷方
- 借贷金额必须相等
- 数学上可验证：Σ借方 = Σ贷方

2. 示例
以强平为例：
- 借：保证金 $49,000
- 借：穿仓损失 $1,000  
- 贷：持仓资产 $50,000
- 平衡：49,000 + 1,000 = 50,000

3. 保障
- 分录不可修改（Immutable）
- 可审计可追溯
- 对账任务定时检查借贷平衡
- 不平衡立即报警，人工介入

4. 实际效果
- 上线至今资金零差错
- 通过多次财务审计
- 支持完整的历史追溯"
```

---

## 4. 性能指标（面试必问）

### 4.1 核心指标表

| 指标 | 目标值 | 达成值 | 优化手段 |
|-----|--------|--------|----------|
| 撮合延迟 | < 1ms | 0.8ms | Disruptor+内存OrderBook |
| 下单链路延迟 | < 50ms | 40ms | Kafka+异步处理 |
| 强平处理延迟 | < 100ms | 80ms | 批量处理+限流 |
| ADL执行延迟 | < 3s | 2.5s | 并行处理+缓存 |
| 系统TPS | > 10万 | 12万 | 水平扩展 |
| 订单峰值 | 5万/秒 | 6万/秒 | 削峰填谷 |

### 4.2 面试回答

```
面试官："系统性能指标怎么样？"

你回答：
"我们从四个维度来衡量：

1. 撮合性能
- 延迟：< 1ms（P99）
- 实现：Disruptor无锁队列 + 内存OrderBook
- 对比：用TreeMap要5ms，用数组+PriceLevel只要0.8ms

2. 下单链路
- 延迟：40ms（端到端）
- 分解：API(2ms) + OMS(15ms) + Risk(5ms) + Kafka(5ms) + 撮合(1ms) + 结算(12ms)
- 优化：关键路径同步，非关键路径异步化

3. 吞吐量
- TPS：12万/秒
- 可水平扩展到百万级
- 通过分区（按symbol）实现并行

4. 强平ADL
- 强平：每秒处理100个订单
- ADL：2-3秒完成一批次
- 保险基金赔付：实时到账

这些指标都经过JMH压测验证，有完整的性能报告。"
```

---

## 5. 故障处理（加分项）

### 5.1 常见故障场景

```
场景1：撮合引擎宕机
├── 检测：健康检查失败/心跳超时
├── 切换：备用节点自动接管
├── 恢复：从WAL日志重放恢复状态
└── 验证：对账检查后再开放交易

场景2：Kafka消息堆积
├── 检测：consumer lag超过阈值
├── 处理：自动扩容消费者
├── 保护：限流防止雪崩
└── 兜底：降级到直连模式（Feign）

场景3：MySQL主从延迟
├── 检测：show slave status
├── 处理：强制走主库读
├── 优化：拆分大事务
└── 兜底：缓存抗读

场景4：Redis集群故障
├── 检测：哨兵自动发现
├── 切换：自动主从切换
├── 恢复：从RDB+AOF恢复
└── 保护：本地缓存兜底
```

### 5.2 面试回答

```
面试官："如果撮合引擎挂了怎么办？"

你回答：
"我们有多层保障机制：

1. 故障检测
- 健康检查接口每秒探测
- 业务指标异常检测（延迟突增、错误率上升）
- 心跳超时检测

2. 自动切换
- 主备架构，备用节点实时同步
- 故障检测后10秒内自动切换
- 客户端自动重连

3. 状态恢复
- WAL（Write Ahead Log）记录所有输入
- 新节点从WAL重放恢复内存状态
- 重放完成后对外服务

4. 数据一致性
- 幂等性保证（bizSeq去重）
- 对账任务定时检查
- 不一致自动补偿

5. 业务影响
- RTO（恢复时间目标）：< 30秒
- RPO（数据丢失目标）：< 1秒
- 故障期间暂停交易，恢复后公告

6. 演练
- 每月一次故障演练
- 验证切换流程
- 持续优化恢复时间"
```

---

## 🎯 面试前必做

1. **画3遍架构图** - 能手绘出来
2. **背熟性能数字** - 延迟、TPS、可用性
3. **理解双录分录** - 能举例说明借贷平衡
4. **演练强平ADL** - 能说清5个阶段
5. **准备故障案例** - 结合实际讲处理过程

**祝你面试成功！🎉**
