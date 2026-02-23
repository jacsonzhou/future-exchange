# API Gateway 统一认证架构

> **目标**: 将 Token 验证从 user-core 迁移到 API Gateway，实现统一入口认证
> **涉及服务**: api-gateway (8080), user-core (8099)

---

## 🏗️ 改造后架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户端 (Web/App)                             │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ Authorization: Bearer {token}
┌─────────────────────────────────────────────────────────────────────┐
│                    API Gateway (8080)                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Filter Chain:                                               │   │
│  │  1. TraceFilter      - 生成TraceId                          │   │
│  │  2. AuthFilter       - ✅ JWT验证 + Redis黑名单检查         │   │
│  │  3. RateLimitFilter  - 限流                                 │   │
│  │  4. SymbolSwitchFilter - 交易开关                           │   │
│  │  5. 路由到下游服务                                           │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  AuthFilter 职责：                                                  │
│  - 验证JWT格式和签名（共享 JwtUtil）                                │
│  - 查Redis黑名单（token:blacklist:{token}）                         │
│  - 解析 userId, accountId, username                                │
│  - 将用户信息添加到请求Header（透传给下游）                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ X-User-Id, X-Account-Id, X-Username
┌─────────────────────────────────────────────────────────────────────┐
│                    User Core (8099)                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  TokenFilter: ✅ 已移除（不再验证Token）                            │
│                                                                     │
│  获取用户信息方式：                                                  │
│  - 从 Header 获取：X-User-Id, X-Account-Id, X-Username               │
│  - 使用 GatewayUserContext 工具类                                   │
│                                                                     │
│  职责：                                                             │
│  - Token 生成（登录时）                                             │
│  - Token 管理（存储到Redis、黑名单、刷新）                          │
│  - 用户信息查询                                                     │
│  - ❌ 不再验证Token（Gateway已验证）                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔑 数据流转

### 登录流程

```
客户端 → POST http://localhost:8080/api/v1/user/login
              ↓
         API Gateway
              ↓ (白名单，跳过认证)
         转发到 user-core:8099
              ↓
         user-core 验证用户名密码
         生成 JWT Token
         存储到 Redis (user:tokens:{userId})
              ↓
         返回 Token 给客户端
```

### 业务请求流程

```
客户端 → GET http://localhost:8080/api/v1/trading/dashboard
              Header: Authorization: Bearer {token}
              ↓
         API Gateway AuthFilter
              - 验证 JWT 签名 ✓
              - 查 Redis 黑名单 ✓
              - 解析 userId=10001, accountId=20001
              - 添加 Header: X-User-Id=10001, X-Account-Id=20001
              ↓
         转发到 user-core:8099
              (带上了 X-User-Id 等 Header)
              ↓
         user-core Controller
              - GatewayUserContext.getCurrentUserId() → 10001
              - 无需解析 Token！
              - 执行业务逻辑
              ↓
         返回数据
```

---

## 📝 关键改造点

### 1. API Gateway (api-gateway)

#### 添加依赖（pom.xml）
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
</dependency>
```

#### 配置（application.yml）
```yaml
jwt:
  secret: future-exchange-user-core-secret-key-2024  # 与 user-core 相同
  expiration: 86400000  # 24小时，与 user-core 相同
```

#### AuthFilter 改造
- 使用共享的 `JwtUtil` 验证 Token
- 查 Redis 黑名单
- 将用户信息通过 Header 透传

### 2. User Core (user-core)

#### 移除 TokenFilter
```java
// SecurityConfig.java - TokenFilter 已移除
@Configuration
public class SecurityConfig {
    // Token认证在 Gateway 处理
}
```

#### 新增 GatewayUserContext
```java
// 从 Header 获取用户信息
Long userId = GatewayUserContext.getCurrentUserId();
Long accountId = GatewayUserContext.getCurrentAccountId();
String username = GatewayUserContext.getCurrentUsername();
```

---

## 🌐 访问方式

### 正确方式（通过 Gateway）

```bash
# 登录（白名单，无需Token）
curl -X POST http://localhost:8080/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"trader001","password":"123456"}'

# 业务请求（Gateway验证Token）
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/v1/trading/dashboard?symbol=BTCUSDT
```

### 错误方式（直接访问 user-core）

```bash
# ❌ 不要直接访问 user-core:8099
curl http://localhost:8099/api/v1/userinfo/from-gateway
# 返回: 401 Unauthorized - Please access through API Gateway (8080)
```

**原因**: 直接访问 user-core 没有经过 Gateway 认证，没有 X-User-Id Header

---

## 🔧 服务间调用

### 内部服务调用（Feign）

如果服务间需要调用，可以通过 Feign 客户端，Token 会自动透传：

```java
// api-gateway 的 AuthFilter 已经设置了 Header
// Feign 会自动带上这些 Header

@FeignClient(name = "user-core", path = "/api/v1")
public interface UserClient {
    
    @GetMapping("/trading/dashboard")
    Result<DashboardResponse> getDashboard(@RequestParam String symbol);
    // 自动带上 X-User-Id, X-Account-Id Header
}
```

### 用户ID获取

```java
@RestController
public class OrderController {
    
    @PostMapping("/order/create")
    public Result<?> createOrder(@RequestBody OrderRequest request) {
        // 方式1：从 GatewayUserContext 获取
        Long userId = GatewayUserContext.getCurrentUserId();
        
        // 方式2：从 Header 直接获取
        // @RequestHeader("X-User-Id") Long userId
        
        // 执行业务逻辑...
    }
}
```

---

## 🎯 优势对比

| 特性 | 改造前 (TokenFilter in user-core) | 改造后 (TokenFilter in Gateway) |
|-----|-----------------------------------|--------------------------------|
| **认证位置** | 每个服务各自验证 | Gateway 统一验证 |
| **Token解析次数** | 每个服务都解析 | 只解析1次 |
| **Redis查询** | 每个服务都查 | 只查1次 |
| **服务实现复杂度** | 每个服务都要实现 Filter | 服务只读 Header |
| **安全性** | 服务直接暴露，可能绕过认证 | 必须通过 Gateway |
| **灵活性** | 高（服务可自定义） | 中（统一规则） |

---

## ⚠️ 注意事项

1. **JWT Secret 必须一致**  
   api-gateway 和 user-core 使用相同的 `jwt.secret`

2. **Redis 共享**  
   黑名单检查需要访问同一个 Redis

3. **白名单配置**  
   Gateway 和 user-core 的白名单需要保持一致

4. **直接访问 user-core**  
   生产环境应禁止直接访问 user-core (8099)，只允许通过 Gateway (8080)

---

## 🚀 启动顺序

```bash
# 1. 启动基础设施
redis-server
mysql
nacos

# 2. 启动 user-core（提供登录/注册服务）
cd user-core && mvn spring-boot:run

# 3. 启动 api-gateway（统一入口，依赖 user-core）
cd api-gateway && mvn spring-boot:run

# 4. 其他服务...
```

---

## 📝 测试命令

```bash
# 1. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"trader001","password":"123456"}' | jq -r .data.token)

echo "Token: $TOKEN"

# 2. 通过 Gateway 访问（成功）
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/userinfo/from-gateway | jq

# 3. 直接访问 user-core（失败，返回401）
curl http://localhost:8099/api/v1/userinfo/from-gateway | jq
```

---

*文档版本: v1.0*  
*最后更新: 2026-02-19*
