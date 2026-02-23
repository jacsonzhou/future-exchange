# OMS双通道架构文档导航

## 📖 文档概览

本文档集合提供了 OMS → Match Engine 双通道架构的完整说明。

**实施日期**：2026-02-18
**版本号**：2.0.0
**状态**：✅ 代码实现完成，编译通过，待测试验证

---

## 🚀 快速开始

**新手推荐**：先阅读 [快速开始指南](quickstart/OMS_DUAL_CHANNEL_QUICKSTART.md)，5分钟体验双通道架构。

```bash
# 克隆项目
cd /path/to/future-exchange

# 阅读快速开始指南
cat docs/quickstart/OMS_DUAL_CHANNEL_QUICKSTART.md

# Kafka模式启动
cd oms-core
java -jar target/oms-core.jar

# Feign模式启动
java -jar target/oms-core.jar --spring.profiles.active=feign
```

---

## 📚 文档清单

### 1. 核心文档

#### 🏗️ [架构设计文档](architecture/OMS_MATCH_ENGINE_DUAL_CHANNEL.md)
**适合**：架构师、高级开发
**内容**：
- 完整架构设计
- 双通道对比分析（Kafka vs Feign）
- 业界最佳实践（币安、OKX）
- 性能指标和监控
- 代码实现细节

**关键章节**：
- 架构设计图
- 性能对比表
- Kafka配置优化
- 业界对标

---

#### 🔧 [操作切换指南](ops/OMS_SUBMIT_MODE_SWITCH_GUIDE.md)
**适合**：运维、DevOps
**内容**：
- 模式切换操作步骤
- 配置验证方法
- 紧急降级场景
- 故障排查手册
- 常用命令参考

**关键章节**：
- 快速切换（4种方法）
- 切换检查清单
- 紧急降级场景
- 故障排查

---

#### 🚀 [快速开始指南](quickstart/OMS_DUAL_CHANNEL_QUICKSTART.md)
**适合**：新手、测试
**内容**：
- 5分钟快速体验
- 启动步骤
- 测试验证
- 性能对比测试
- 灾备演示

**关键章节**：
- 启动步骤
- 测试验证
- 性能对比测试
- 典型场景

---

#### 📊 [实施状态文档](architecture/IMPLEMENTATION_STATUS.md)
**适合**：项目经理、开发
**内容**：
- 实施完成情况
- 代码审查要点
- 测试计划
- 后续优化规划

**关键章节**：
- 实施清单
- 代码审查
- 后续规划

---

#### 📝 [更新日志](../CHANGELOG_OMS_DUAL_CHANNEL.md)
**适合**：所有人
**内容**：
- 详细变更记录
- 新增功能
- 修改文件
- 兼容性说明
- 风险评估

**关键章节**：
- 新增功能
- 代码变更统计
- 兼容性说明

---

#### ✅ [实施总结](summary/OMS_DUAL_CHANNEL_SUMMARY.md)
**适合**：管理层、架构师
**内容**：
- 实施概览
- 完成清单
- 架构设计
- 代码统计
- 业务价值

**关键章节**：
- 完成清单
- 双通道对比
- 业务价值
- 验收标准

---

## 🗺️ 阅读路径

### 路径1：快速体验（新手）

```
1. 快速开始指南（5分钟）
   ↓
2. 操作切换指南（了解如何切换）
   ↓
3. 架构设计文档（深入理解）
```

### 路径2：深入理解（架构师）

```
1. 实施总结（整体了解）
   ↓
2. 架构设计文档（详细设计）
   ↓
3. 实施状态文档（代码审查）
   ↓
4. 更新日志（变更详情）
```

### 路径3：运维部署（运维）

```
1. 操作切换指南（切换步骤）
   ↓
2. 快速开始指南（验证方法）
   ↓
3. 架构设计文档（监控指标）
```

### 路径4：开发测试（开发）

```
1. 快速开始指南（环境搭建）
   ↓
2. 实施状态文档（代码审查）
   ↓
3. 架构设计文档（实现细节）
```

---

## 🎯 核心概念

### 双通道架构

```
OMS → 配置开关 → {
    Kafka通道（生产推荐）
    Feign通道（降级/测试）
} → Match Engine
```

### 配置切换

```yaml
exchange:
  oms:
    submit-mode: kafka  # 或 feign
```

### 日志标识

- **Kafka模式**：`[Kafka Mode] Submitting order via Kafka`
- **Feign模式**：`[Feign Mode] Submitting order via Feign`

---

## 📊 核心指标

### 性能对比

| 指标 | Kafka模式 | Feign模式 |
|-----|----------|----------|
| 延迟（P50） | 1ms | 0.1ms |
| 延迟（P99） | 3ms | 0.5ms |
| TPS | 10000+ | 20000+ |
| 可靠性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| 灾备 | ✅ | ❌ |
| 审计 | ✅ | ❌ |

### 适用场景

- **Kafka模式**：生产环境、监管合规、灾备要求
- **Feign模式**：测试环境、紧急降级、低延迟要求

---

## 🔗 相关资源

### 代码文件

```
oms-core/src/main/java/com/exchange/oms/
├── config/
│   └── OmsSubmitModeConfig.java          # 配置类
├── service/impl/
│   └── OrderServiceImpl.java             # 双通道实现
└── publisher/
    └── OrderEventPublisher.java          # Kafka发布器

oms-core/src/main/resources/
├── application.yml                       # 主配置（Kafka）
└── application-feign.yml                 # Feign配置
```

### 外部依赖

- **Kafka**：消息队列（Kafka通道）
- **Match Engine**：撮合引擎（两种通道都依赖）
- **Nacos**：配置中心（可选，支持动态切换）

---

## 🧪 测试验证

### 功能测试

```bash
# 提交测试订单
curl -X POST http://localhost:8081/api/order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "price": "50000",
    "quantity": "1"
  }'

# 观察日志
tail -f logs/oms-core.log | grep "Mode"
```

### 性能测试

```bash
# 压测工具：wrk
wrk -t4 -c100 -d60s --latency \
  -s order_submit.lua \
  http://localhost:8081/api/order
```

---

## 🚨 常见问题

### Q1: 如何切换模式？

**方法1**：配置文件
```yaml
exchange.oms.submit-mode: kafka  # 或 feign
```

**方法2**：启动参数
```bash
java -jar oms-core.jar --exchange.oms.submit-mode=feign
```

**方法3**：Profile
```bash
java -jar oms-core.jar --spring.profiles.active=feign
```

### Q2: Kafka模式和Feign模式的主要区别？

| 维度 | Kafka | Feign |
|-----|-------|-------|
| 延迟 | 1-2ms | 0.1-0.5ms |
| 可靠性 | 高（灾备） | 低（无灾备） |
| 解耦 | 强 | 弱 |
| 适用 | 生产 | 测试/降级 |

### Q3: 生产环境推荐哪种模式？

**强烈推荐：Kafka模式**

理由：
- ✅ 监管合规（审计追溯）
- ✅ 灾备恢复（7天保留）
- ✅ 削峰填谷（缓冲保护）
- ✅ 业界标准（币安、OKX）

### Q4: 切换模式需要重启吗？

**当前需要重启**。

后续可集成Nacos配置中心实现动态切换（无需重启）。

### Q5: 如何验证当前使用的模式？

```bash
# 方法1：查看日志
tail -f logs/oms-core.log | grep "Mode"

# 方法2：提交订单观察
curl -X POST http://localhost:8081/api/order ...
tail -f logs/oms-core.log
```

---

## 📞 联系方式

### 技术支持

- **架构组**：架构设计、技术答疑
- **开发组**：代码Review、功能开发
- **运维组**：部署配置、监控告警
- **测试组**：功能测试、性能测试

### 文档贡献

欢迎提交文档改进建议：
1. Fork项目
2. 修改文档
3. 提交PR

---

## 📅 更新记录

| 日期 | 版本 | 更新内容 |
|-----|------|---------|
| 2026-02-18 | 2.0.0 | 初始版本，双通道架构实现 |

---

## 🎯 下一步

### 开发者

1. 阅读 [快速开始指南](quickstart/OMS_DUAL_CHANNEL_QUICKSTART.md)
2. 本地启动验证
3. 编写单元测试

### 运维

1. 阅读 [操作切换指南](ops/OMS_SUBMIT_MODE_SWITCH_GUIDE.md)
2. 准备监控告警
3. 制定切换预案

### 架构师

1. 阅读 [架构设计文档](architecture/OMS_MATCH_ENGINE_DUAL_CHANNEL.md)
2. Code Review
3. 技术分享会

### 测试

1. 阅读 [快速开始指南](quickstart/OMS_DUAL_CHANNEL_QUICKSTART.md)
2. 功能测试
3. 性能压测

---

**文档维护**：Architecture Team
**最后更新**：2026-02-18
