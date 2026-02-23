# 冒烟测试报告

**测试时间**: 2026-02-20
**测试人员**: Claude Code
**测试环境**: 本地开发环境

---

## 📋 测试环境检查

### 基础设施状态

| 组件 | 端口 | 状态 | 备注 |
|------|------|------|------|
| MySQL | 3306 | ✅ 运行中 | Docker |
| Redis | 6379 | ✅ 运行中 | Docker |
| Kafka | 9092 | ✅ 运行中 | Docker |
| Nacos | 8848 | ✅ 运行中 | 服务注册中心 |

### 核心服务状态

| 服务 | 端口 | 状态 | 备注 |
|------|------|------|------|
| API Gateway | 8080 | ❌ 未运行 | 可直接访问微服务 |
| User Core | 8099 | ✅ 运行中 | - |
| OMS Core | 8081 | ✅ 运行中 | - |
| Hard Risk Core | 8082 | ✅ 运行中 | - |
| Match Engine Core | 8083 | ✅ 运行中 | - |
| Ledger Core | 8084 | ✅ 运行中 | - |
| Snapshot Account Core | 8085 | ✅ 运行中 | - |
| Position Snapshot Core | 8086 | ❌ 未运行 | **影响持仓查询** |
| Liquidation Core | 8088 | ✅ 运行中 | - |
| Market Price Core | 8095 | ❌ 未运行 | **影响行情推送** |
| Public Push Core | 8096 | ✅ 运行中 | - |
| Index Price Core | 8097 | ✅ 运行中 | - |

**结论**:
- ✅ 核心交易链路服务完备（User、OMS、Risk、Match、Ledger、Account）
- ⚠️ 缺少 Position Snapshot Core，持仓查询功能受限
- ⚠️ 缺少 Market Price Core，行情计算和推送受限
- ℹ️ API Gateway 未运行，直接访问各微服务进行测试

---

## 🧪 测试执行记录

### Phase 1: 基础服务检查

开始执行时间: 准备中...

