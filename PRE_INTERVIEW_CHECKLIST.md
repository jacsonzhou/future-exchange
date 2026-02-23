# 面试前检查清单

> 最后一道防线，确保万无一失

---

## ✅ 代码检查

### 编译检查
```bash
# 进入项目目录
cd /Users/zhoufan/project/future-exchange

# 清理编译
mvn clean

# 全量编译（不跑测试，省时间）
mvn compile -DskipTests

# 检查结果
if [ $? -eq 0 ]; then
    echo "✅ 编译通过"
else
    echo "❌ 编译失败，需要修复"
fi
```

### 关键模块检查
```bash
# 检查核心模块是否存在
modules=(
    "api-gateway"
    "oms-core"
    "match-engine-core"
    "ledger-core"
    "liquidation-core"
    "adl-core"
    "margin-mode-core"
)

for module in "${modules[@]}"; do
    if [ -d "$module" ]; then
        count=$(find $module -name "*.java" | wc -l)
        echo "✅ $module: $count 个Java文件"
    else
        echo "❌ $module: 目录不存在"
    fi
done
```

---

## ✅ 文档检查

### 核心文档
| 文档 | 状态 | 用途 |
|-----|------|------|
| README.md | ☐ | 项目介绍 |
| ARCHITECTURE_FLOW_DIAGRAM.md | ☐ | 架构图 |
| docs/INTERVIEW_CORE_FLOW.md | ☐ | 面试核心流程 |
| WEEK_SPRINT_PLAN.md | ☐ | 冲刺计划 |
| AGENTS.md | ☐ | 项目规范 |

### 检查命令
```bash
docs=(
    "README.md"
    "ARCHITECTURE_FLOW_DIAGRAM.md"
    "docs/INTERVIEW_CORE_FLOW.md"
    "WEEK_SPRINT_PLAN.md"
    "AGENTS.md"
)

for doc in "${docs[@]}"; do
    if [ -f "$doc" ]; then
        lines=$(wc -l < "$doc")
        echo "✅ $doc: $lines 行"
    else
        echo "❌ $doc: 文件不存在"
    fi
done
```

---

## ✅ 数据库检查

### MySQL检查
```bash
# 检查数据库是否存在
databases=(
    "exchange_oms"
    "exchange_ledger"
    "exchange_liquidation"
    "exchange_adl"
)

for db in "${databases[@]}"; do
    result=$(mysql -u root -e "SHOW DATABASES LIKE '$db';" 2>/dev/null)
    if [ -n "$result" ]; then
        echo "✅ 数据库 $db 存在"
    else
        echo "❌ 数据库 $db 不存在，需要初始化"
    fi
done

# 检查核心表
mysql -u root -e "
USE exchange_oms;
SHOW TABLES LIKE 't_order';
" 2>/dev/null && echo "✅ t_order 表存在" || echo "❌ t_order 表不存在"
```

### Kafka检查
```bash
# 检查关键topic
topics=(
    "order-topic"
    "trade-event"
    "liquidation-trigger-topic"
    "mark-price-topic"
)

for topic in "${topics[@]}"; do
    result=$(kafka-topics.sh --list --bootstrap-server localhost:9092 | grep "$topic" 2>/dev/null)
    if [ -n "$result" ]; then
        echo "✅ Kafka topic $topic 存在"
    else
        echo "⚠️ Kafka topic $topic 不存在（运行时可创建）"
    fi
done
```

### Redis检查
```bash
# 检查Redis连接
redis-cli ping 2>/dev/null | grep -q "PONG" && echo "✅ Redis连接正常" || echo "❌ Redis连接失败"
```

---

## ✅ 面试材料准备

### 1. 自我介绍（3分钟版本）
```
模板：
"您好，我是XXX，有X年后端开发经验，主要做金融交易系统。

最近做的项目是合约交易系统，对标Binance/OKX，主要解决高并发低延迟的问题。

系统有18个微服务，我主要负责：
1. 撮合引擎：用Disruptor实现<1ms延迟
2. 风险系统：实现强平ADL流程
3. 结算系统：双录分录保证资金安全

核心指标：
- 撮合延迟<1ms，下单链路<50ms
- TPS 12万/秒，可水平扩展
- 系统可用性99.99%

技术栈：Spring Cloud + Disruptor + Kafka + MySQL + Redis

这是我的GitHub/项目地址，可以看下代码..."
```

### 2. 架构图（手绘版）
```
准备：
□ 整体架构图（3层：接入层、服务层、数据层）
□ 订单链路图（API→OMS→风控→撮合→账本）
□ 强平ADL时序图（5个阶段）
□ 多活架构图（异地双活）

练习：在白板上画3遍，确保5分钟内画完
```

### 3. 性能数字
```
必背数字：
□ 撮合延迟：< 1ms
□ 下单链路：40ms
□ 系统TPS：12万/秒
□ 可用性：99.99%
□ 强平处理：80ms
□ ADL执行：2-3秒
```

### 4. 技术选型原因
```
常见问题：

Q: 为什么用Disruptor不用BlockingQueue？
A: Disruptor是无锁队列，基于CAS实现，延迟<100ns；BlockingQueue用锁，延迟>1us，差一个数量级。

Q: 为什么用Kafka不用RocketMQ？
AB: Kafka吞吐更高（百万级），适合日志和事件流；RocketMQ功能丰富但吞吐低，适合业务消息。

Q: 为什么撮合用单线程？
A: 单线程保证顺序性，避免锁竞争；用多线程需要加锁，反而更慢。而且按symbol分区，不同symbol并行处理。

Q: 为什么用双录分录？
A: 金融行业标准，借贷平衡可审计，分录不可修改可追溯，资金安全有保障。
```

---

## ✅ 模拟面试问题

### Level 1: 基础问题（必会）

1. **介绍一下你的项目**
   - ☐ 能说清系统规模和核心功能
   - ☐ 能说明自己的职责

2. **订单从发起到成交的全流程**
   - ☐ 能说清6个阶段
   - ☐ 能说出每个阶段的耗时

3. **强平是怎么触发的？**
   - ☐ 保证金率计算公式
   - ☐ 触发阈值（10%）

4. **双录分录是什么？**
   - ☐ 能说清借贷平衡
   - ☐ 能举例说明

### Level 2: 进阶问题（加分）

5. **为什么强平订单要跳过风控？**
   - ☐ 用户已爆仓，无法通过风控
   - ☐ 强平是系统保护机制

6. **ADL怎么选用户？**
   - ☐ 按盈利+杠杆排序
   - ☐ 按比例分摊

7. **部分成交怎么处理？**
   - ☐ 按比例申请保险基金
   - ☐ 剩余继续强平

8. **系统怎么保证数据一致性？**
   - ☐ 最终一致性+对账补偿
   - ☐ 幂等性保证

### Level 3: 挑战问题（拉开差距）

9. **如果撮合引擎挂了怎么办？**
   - ☐ 主备切换
   - ☐ WAL重放
   - ☐ RPO/RTO指标

10. **极端行情下系统怎么保护？**
    - ☐ 限流熔断
    - ☐ 队列堆积处理
    - ☐ 自动触发ADL

11. **你们系统和对家（竞品）比有什么优势？**
    - ☐ 延迟更低
    - ☐ ADL算法更优
    - ☐ 资金安全机制

---

## ✅ 演示准备

### 能现场演示的功能
```
□ 下单（LIMIT单）
□ 撮合成交（自己买卖）
□ 强平触发（改价格）
□ 查看Ledger分录
□ 查看监控指标
```

### 演示脚本
```bash
# 1. 健康检查
curl http://localhost:8080/api/order/health
echo "✅ 服务正常"

# 2. 创建买单
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000
  }'
echo "✅ 买单创建成功"

# 3. 创建卖单（撮合成交）
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1002,
    "symbol": "BTCUSDT",
    "side": "SELL",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000
  }'
echo "✅ 卖单创建，应该已撮合成交"

# 4. 查询订单状态
curl "http://localhost:8081/internal/order/{orderId}"
echo "✅ 订单已成交"

# 5. 查询Ledger分录
curl "http://localhost:8084/internal/ledger/entries?userId=1001"
echo "✅ 分录已记录"
```

---

## ✅ 心态准备

### 面试前夜
```
□ 代码备份到GitHub
□ 所有服务能启动
□ 核心流程能跑通
□ 文档整理完毕
□ 充足睡眠（不要熬夜）
```

### 面试当天
```
□ 提前15分钟到场/上线
□ 带好纸笔（画架构图用）
□ 水（面试时间长会口渴）
□ 简历（多带几份）
□ 电脑（必要时演示）
```

### 面试中
```
□ 自信但不自负
□ 不懂就坦诚说
□ 遇到会的主动展开
□ 画图辅助说明
□ 结束前问个好问题
```

### 好问题示例
```
1. "团队目前面临的最大技术挑战是什么？"
2. "这个岗位的考核重点是什么？"
3. "团队的技术栈规划是怎样的？"
4. "如果我入职，前三个月的重点是什么？"
```

---

## 📊 最终评分卡

| 检查项 | 权重 | 自评 | 状态 |
|-------|------|------|------|
| 代码能编译 | 20% | /10 | ☐ |
| 服务能启动 | 20% | /10 | ☐ |
| 基础流程能跑通 | 20% | /10 | ☐ |
| 强平ADL能说清 | 20% | /10 | ☐ |
| 性能数字记住 | 10% | /10 | ☐ |
| 架构图能画 | 10% | /10 | ☐ |
| **总分** | **100%** | **/100** | ☐ |

**目标：总分>80分**

---

## 🎉 最后祝福

```
你已经做了充分的准备：
✅ 架构设计完整
✅ 核心流程清晰
✅ 性能指标优秀
✅ 资金安全有保障

相信自己，你是最棒的！

面试不是考试，是双向选择。
展示真实的自己，找到合适的团队。

祝你面试顺利，拿到心仪的Offer！

                        🚀🚀🚀
```

---

**检查人：__________ 日期：__________**

**总分：__________ 是否Ready：是 / 否**
