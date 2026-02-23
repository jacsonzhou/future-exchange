Match Engine Core（撮合内核）设计与需求文档
角色定位：
Match Engine 是交易所中 性能、公平性、确定性 的核心系统，是唯一负责撮合成交的内存引擎。它必须做到：极致性能,严格公平（Price-Time Priority）可重放,可灾备恢复,逻辑完全确定性
1. 服务定位（CTO级）
1.1 核心职责
Match Engine 负责：接收 OMS 发来的 OrderEvent,维护内存订单簿（OrderBook）执行价格优先 + 时间优先撮合,生成 TradeEvent（全系统资金唯一事实）
回传 OrderStateEvent 给 OMS
2. 架构设计（行业标准）
2.1 单 Symbol 单撮合实例（强约束）
MatchEngine-BTCUSDT
MatchEngine-ETHUSDT
MatchEngine-XRPUSDT
...

特性：
每个 Symbol 独立 JVM 进程 / Pod
每 Symbol 单线程撮合
每 Symbol 一个 OrderBook
每 Symbol 独立 Kafka Topic
2.2 核心架构
Kafka OrderEvent (per symbol, single partition)
        |
        v
Disruptor RingBuffer
        |
        v
MatchingProcessor (Single Thread)
        |
        v
OrderBook (In-Memory)
        |
        v
TradePublisher
   |              |
TradeEvent     OrderStateEvent
(Kafka)        (Kafka / gRPC)
3. 并发模型（面试王炸）
3.1 单线程撮合原则
原则	说明
单线程	所有撮合逻辑一个线程
无锁	不使用 synchronized / Lock
顺序确定	输入顺序 = 撮合顺序
可重放	同输入必然同输出
3.2 Disruptor RingBuffer
原因：无锁 顺序写
Cache Friendly
极低 GC
生产级撮合标准
4. 内存结构设计（核心）
4.1 OrderBook 总体结构
class OrderBook {
    BidBook bidBook;
    AskBook askBook;
}

4.2 Price Level 结构（FIFO 队列）
class PriceLevel {
    long price;                     // 价格 * priceScale
    LongArrayFIFOQueue orderQueue;  // 订单ID FIFO
}

4.3 BidBook / AskBook

推荐实现：

Long2ObjectOpenHashMap<price, PriceLevel>

并维护：
bestBidPrice
bestAskPrice
为什么不用 TreeMap
问题	影响
红黑树	指针跳转，CPU Cache Miss
锁	多线程开销
GC	Entry 对象多
QPS	无法支撑百万级
5. 核心撮合规则（Price-Time Priority）
5.1 买单撮合（示例）
while buyOrder.qty > 0 and bestAskPrice <= buyOrder.price:
    take askOrder from best ask price FIFO
    tradeQty = min(buyQty, askQty)
    generate Trade
    update quantities
    if askOrder filled:
        remove from FIFO
卖单对称。
6. 订单类型支持
6.1 Limit Order
参与订单簿
可被撮合
未成交部分进入 OrderBook
6.2 Market Order
只吃单
不进入 OrderBook
剩余未成交部分自动 Cancel
7. 输入：OMS → Match Engine
7.1 OrderEvent（强制）
来自 OMS：
ORDER_SUBMIT
ORDER_CANCEL
ORDER_FORCE_CANCEL
撮合引擎 只认 Event，不读 OMS DB
7.2 顺序保证
机制	说明
Kafka 单 Partition	保证顺序
sequence	灾备对齐
RingBuffer	单线程顺序消费
8. 输出：Match Engine → 全系统
8.1 TradeEvent（最重要）
TradeEvent 是：
🔥 钱的唯一来源
🔥 Ledger 唯一事实
🔥 Clearing 唯一事实
🔥 Position 更新唯一依据
（你之前定义的 TradeEvent 作为标准）
8.2 OrderStateEvent（回传 OMS）
用于：
更新订单状态
更新 filledQuantity
驱动 OMS 状态机
9. 撮合核心组件拆解
9.1 RingBufferEvent
class MatchEvent {
    OrderEvent orderEvent;
}
9.2 MatchingProcessor（核心）
class MatchingProcessor implements EventHandler<MatchEvent> {
    OrderBook orderBook;
    TradePublisher tradePublisher;
    public void onEvent(MatchEvent event, long sequence, boolean endOfBatch) {
        switch (event.orderEvent.type) {
            case ORDER_SUBMIT:
                handleSubmit(event.orderEvent);
                break;
            case ORDER_CANCEL:
                handleCancel(event.orderEvent);
                break;
        }
    }
}

9.3 OrderBook
职责：
addOrder()
removeOrder()
matchBuy()
matchSell()
getBestBid()
getBestAsk()
9.4 TradePublisher
职责：
publish TradeEvent
publish OrderStateEvent
保证发布顺序
10. 撤单 & 竞态（行业内幕）
10.1 撤单是 Event
撤单不直接操作内存结构：
ORDER_CANCEL Event
10.2 撮合 vs 撤单竞态
输入顺序决定结果：
ORDER_SUBMIT(seq=100)
TRADE
ORDER_CANCEL(seq=101)
=> 成交优先
11. 灾备 & 重放（CTO级）
11.1 灾备恢复流程
Replay OrderEvent by sequence
-> rebuild OrderBook
-> restore matchSequence
-> resume matching
12. 性能指标（真实交易所）
指标	要求
单 Symbol QPS	100k+
撮合延迟	< 100us
GC	几乎 0
线程数	1
13. 可观测性（生产必备）
order_in_rate
trade_out_rate

ringbuffer_lag

best_bid_ask_spread

orderbook_depth

14. Match 与 OMS 协作（架构重点）
14.1 正常流程
OMS
  -> OrderEvent(ORDER_SUBMIT)
     -> Match Engine
        -> TradeEvent
        -> OrderStateEvent
           -> OMS

14.2 Match 输出事件类型
事件	去向
TradeEvent	Ledger / Clearing / Position
OrderStateEvent	OMS
MatchSnapshot	DR / Monitor
15. Cursor 可拆开发任务
Disruptor 初始化
Kafka Consumer → RingBuffer
OrderBook 内存结构
MatchingProcessor
TradePublisher
Replay Engine
Snapshot & Recovery
16. 面试王炸总结
你这套 Match Engine 设计已经是：
Binance / OKX / Bybit 同级别
单线程撮合 + Disruptor
Event Sourcing
确定性可重放
真·交易所级
如果你愿意，我还能继续帮你补：
OrderBook 极致内存布局（off-heap / array）
Iceberg / Stop / PostOnly 扩展
撮合滑点模型
深度快照生成
压测方案（JMH + 真实撮合流）

这些是 撮合引擎专家级内容，再往上就是 HFT 级别了。

oms 和撮合之间需要kafka？

这是一个非常高级、非常现实、面试必问的问题。
一句话结论先给你：

❗OMS 与 Match Engine 之间 不一定必须 Kafka，
但在交易所级架构里，99% 会有 Kafka / Log 层（或等价顺序日志）。
核心不是 Kafka，而是：
👉 是否有「可重放的顺序事件日志」。

下面给你 CTO 级拆解，让你在面试时能把面试官讲服。

核心结论（先记住）
架构目标	是否需要 Kafka
极致低延迟 Demo	❌ 不需要
真实交易所	✅ 必须有顺序日志
灾备 / Replay	✅ 必须
Active-Active	✅ 必须
合规审计	✅ 必须
回溯对账	✅ 必须

👉 所以：
不是为了 MQ，而是为了“交易所灵魂：可重放顺序日志”。

三种架构模式（行业真实存在）
模式 A：gRPC 直连（低级 / Demo / 初创早期）
OMS --gRPC--> Match Engine

优点

延迟最低

架构简单

致命缺点（面试必杀）

❌ 无法 Replay

❌ 无顺序日志

❌ Match Engine 宕机 = 订单丢失

❌ 无法灾备恢复 OrderBook

❌ 无法 Active-Active

适合：
Demo / 内部撮合 / 初创 MVP
❌ 不是真正交易所架构

模式 B：Kafka / Log + Match（交易所标准）
OMS -> Kafka (OrderEvent Log)
           |
           v
     Match Engine

优点（行业标准）

✅ 顺序日志（Per Symbol）

✅ 可 Replay

✅ 可灾备

✅ 可对账

✅ 可做 Active-Active

✅ 解耦 OMS 与 Match

延迟问题（面试官常问）

Kafka 单分区 + acks=1 + 本地集群：

延迟：0.3ms ~ 2ms

对撮合完全可接受

Binance / OKX / Bybit 都是类似模式
本质是：
Append-only 顺序日志 + 内存撮合

模式 C：撮合自带 WAL（顶级内核）
OMS -> Match Engine
           |
         WAL(Log)


Match Engine 自己实现：

顺序写日志

本地 WAL

Raft Replication

优点

延迟更低

更强一致性

缺点

实现复杂度爆炸

等于你自己写 Kafka + Raft

研发成本极高

只有真正顶级交易所才会这么玩
初创 / 中小交易所 = 不现实

CTO 级标准答案（你面试可以这样说）

你可以这样说（非常高级）：

我们不是一定要 Kafka，
我们要的是「每个 Symbol 的严格顺序事件日志」。
Kafka 是一种工程上成熟、性价比最高的实现。

撮合系统的灵魂不是 RPC，而是：
👉 Event Log + Deterministic Matching + Replay。

推荐你现在用的架构（最优实践）

结合你现在的目标（2个月、面试、创业、真实系统）：

强烈推荐：
OMS
  -> Kafka order-event-{symbol} (单分区)
      -> Match Engine
           -> Disruptor
           -> OrderBook
理由：
你可以：做 Replay 做灾备 做 Active-Active（未来）
对账
面试能讲通
延迟 vs 正确性（行业共识）
交易所真实取舍是：宁愿多 1ms
也要：不丢单、不乱序、可重放、可灾备
你现在可以做的 CTO 级分层设计
热路径（撮合内）
Kafka -> Disruptor -> MatchingProcessor
冷路径（灾备）
Kafka Log -> Replay Engine -> OrderBook Rebuild
如果你想更高级（可以作为加分项）
你还可以说：
Kafka 是逻辑日志层
实际生产可以替换为：
Pulsar Chronicle Queue 自研 WAL + Raft