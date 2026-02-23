# TokenFilter 迁移到 Gateway 完整总结

> **迁移状态**: ✅ 已完成  
> **涉及模块**: api-gateway, user-core, common-core  
> **迁移时间**: 2026-02-19

---

## 📋 改造背景

### 问题
- Token 验证逻辑分散在各服务（user-core, oms-core 等）
- 每个服务都需实现 Filter，重复代码
- 下游服务需要解析 JWT，增加计算开销

### 目标
- **API Gateway** 统一验证 Token
- **下游服务**从 Header 读取用户信息
- **common-core** 提供共享工具类

---

## 🏗️ 最终架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户端请求                                   │
└─────────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────────┐
│                    API Gateway (8080)                                │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  AuthFilter (统一认证)                                       │   │
│  │  ├─ 验证 JWT 签名（使用 common-core/JwtUtil）                │   │
│  │  ├─ 查 Redis 黑名单                                          │   │
│  │  ├─ 解析 userId, accountId, username                         │   │
│  │  └─ 添加到请求 Header                                        │   │
│  │     X-User-Id: 10001                                         │   │
│  │     X-Account-Id: 20001                                      │   │
│  │     X-Username: trader001                                    │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                              ↓ 转发（带上 Header）
┌─────────────────────────────────────────────────────────────────────┐
│                    下游微服务（user-core, oms-core...）               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  使用 GatewayHeaders 获取用户信息（无需 Filter）              │   │
│  │                                                              │   │
│  │  Long userId = GatewayHeaders.getUserId();                   │   │
│  │  Long accountId = GatewayHeaders.getAccountId();             │   │
│  │  String username = GatewayHeaders.getUsername();             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 📁 文件变更清单

### 1. common-core（共享基础设施）

#### 新增文件
```
src/main/java/com/exchange/common/core/
├── util/JwtUtil.java                    # 共享 JWT 工具
└── context/GatewayHeaders.java          # 共享 Header 获取工具
```

**说明**:
- `JwtUtil`: 被 api-gateway 和 user-core 共用
- `GatewayHeaders`: 所有下游服务使用，从 Header 读取用户信息

---

### 2. api-gateway（统一入口）

#### 修改文件
```
src/main/java/com/exchange/gateway/filter/AuthFilter.java
```

**变更内容**:
```java
// 改造前：模拟验证
private UserContext validateJwtToken(String token) {
    UserContext context = new UserContext();
    context.setUserId(1L);  // 模拟
    return context;
}

// 改造后：真实验证 + Header 透传
private void authenticate(HttpServletRequest request) {
    // 1. 使用共享 JwtUtil 验证
    if (!jwtUtil.validateToken(token)) {
        throw new UnauthorizedException("Invalid token");
    }
    
    // 2. 查 Redis 黑名单
    if (isTokenBlacklisted(token)) {
        throw new UnauthorizedException("Token revoked");
    }
    
    // 3. 解析用户信息
    Long userId = jwtUtil.getUserIdFromToken(token);
    Long accountId = jwtUtil.getAccountIdFromToken(token);
    String username = jwtUtil.getUsernameFromToken(token);
    
    // 4. 添加到 Header 透传
    request.setHeader("X-User-Id", userId.toString());
    request.setHeader("X-Account-Id", accountId.toString());
    request.setHeader("X-Username", username);
}
```

---

### 3. user-core（用户服务）

#### 修改/删除文件
```
src/main/java/com/exchange/user/
├── config/SecurityConfig.java           # 修改：移除 TokenFilter 注册
├── security/
│   ├── TokenAuthenticationFilter.java   # 已移除（Gateway 处理）
│   └── GatewayUserContext.java          # 已移除（使用 common-core）
└── controller/
    └── UserInfoController.java          # 修改：使用 GatewayHeaders
```

**说明**:
- TokenFilter 已移除，认证在 Gateway 统一处理
- GatewayUserContext 已删除，改用 `common-core/GatewayHeaders`

---

## 🚀 使用方式对比

### 改造前（每个服务自己验证）

```java
// user-core: TokenAuthenticationFilter
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        // 1. 从 Header 提取 Token
        String token = extractToken(request);
        
        // 2. 验证 JWT
        if (!jwtUtil.validateToken(token)) {
            sendError(response, 401, "Invalid token");
            return;
        }
        
        // 3. 查 Redis 黑名单
        if (tokenManager.isTokenBlacklisted(token)) {
            sendError(response, 401, "Token revoked");
            return;
        }
        
        // 4. 解析用户信息
        Long userId = jwtUtil.getUserIdFromToken(token);
        
        // 5. 存入 ThreadLocal
        UserContext.setCurrentUser(userId, ...);
        
        chain.doFilter(request, response);
    }
}

// Controller 使用
@RestController
public class OrderController {
    @GetMapping("/orders")
    public Result<List<Order>> getOrders() {
        Long userId = UserContext.getCurrentUserId();  // 从 ThreadLocal
        // ...
    }
}
```

### 改造后（Gateway 统一验证，Header 透传）

```java
// api-gateway: AuthFilter（仅此一处验证）
@Component
public class AuthFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        // 验证 Token...（略）
        
        // 将用户信息放入 Header
        HttpServletRequest wrappedRequest = new UserInfoRequestWrapper(
            httpRequest, userId, accountId, username);
        
        chain.doFilter(wrappedRequest, response);
    }
}

// user-core: Controller 使用（所有服务一样）
@RestController
public class OrderController {
    @GetMapping("/orders")
    public Result<List<Order>> getOrders() {
        // 从 Gateway Header 获取（无需 ThreadLocal）
        Long userId = GatewayHeaders.getUserId();
        Long accountId = GatewayHeaders.getAccountId();
        // ...
    }
}
```

---

## 📊 优势对比

| 特性 | 改造前 | 改造后 | 说明 |
|-----|--------|--------|------|
| **Token 验证位置** | 每个服务各自验证 | Gateway 统一验证 | 减少重复代码 |
| **JWT 解析次数** | N 次（N个服务） | 1 次 | 性能提升 |
| **Redis 查询次数** | N 次 | 1 次 | 性能提升 |
| **代码复杂度** | 高（每个服务实现 Filter） | 低（直接使用 GatewayHeaders） | 维护简单 |
| **安全性** | 服务暴露，可能绕过 | 必须通过 Gateway | 更安全 |
| **调试难度** | 高（到处验证） | 低（统一入口） | 易于排查 |

---

## 🧪 测试验证

### 1. 登录获取 Token

```bash
curl -X POST http://localhost:8080/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"trader001","password":"123456"}'

# 返回
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "userId": 10001,
    "accountId": 20001
  }
}
```

### 2. 通过 Gateway 访问（成功）

```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  http://localhost:8080/api/v1/userinfo/from-gateway

# 返回
{
  "code": 200,
  "data": {
    "userId": 10001,
    "accountId": 20001,
    "username": "trader001",
    "source": "API Gateway Header (via GatewayHeaders)"
  }
}
```

### 3. 直接访问 user-core（失败，预期行为）

```bash
curl http://localhost:8099/api/v1/userinfo/from-gateway

# 返回
{
  "code": 401,
  "message": "Unauthorized - Please access through API Gateway (8080)"
}
```

---

## ⚠️ 重要注意事项

### 1. JWT Secret 一致性

**api-gateway** 和 **user-core** 必须使用相同的 `jwt.secret`：

```yaml
# api-gateway/application.yml
jwt:
  secret: future-exchange-user-core-secret-key-2024

# user-core/application.yml
jwt:
  secret: future-exchange-user-core-secret-key-2024  # 必须相同
```

### 2. Redis 共享

黑名单检查需要访问同一个 Redis：

```yaml
# api-gateway
spring:
  redis:
    host: localhost
    port: 6379
    database: 0  # 与 user-core 相同
```

### 3. 网络隔离（生产环境）

生产环境应禁止直接访问下游服务：

```
互联网  --(只允许)-->  API Gateway (8080)  --(内网)-->  user-core (8099)
                          ↓
                     其他下游服务
```

### 4. Feign 调用 Header 透传

服务间调用需要透传 Header：

```java
@Configuration
public class FeignConfig {
    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                
                // 透传 Gateway 设置的 Header
                String userId = request.getHeader(GatewayHeaders.HEADER_USER_ID);
                if (userId != null) {
                    requestTemplate.header(GatewayHeaders.HEADER_USER_ID, userId);
                }
                // ... 其他 Header
            }
        };
    }
}
```

---

## 📚 相关文档

| 文档 | 路径 | 说明 |
|-----|------|------|
| Gateway 认证架构 | `docs/analysis/GATEWAY_AUTH_ARCHITECTURE.md` | 完整架构说明 |
| GatewayHeaders 使用 | `common-core/USAGE_GATEWAY_HEADERS.md` | 下游服务使用指南 |
| User Core README | `user-core/README.md` | user-core 模块文档 |

---

## ✅ 检查清单

- [x] `common-core/JwtUtil.java` - 共享 JWT 工具
- [x] `common-core/GatewayHeaders.java` - 共享 Header 获取工具
- [x] `api-gateway/AuthFilter.java` - Gateway 统一认证
- [x] `user-core/SecurityConfig.java` - 移除 TokenFilter
- [x] `user-core/UserInfoController.java` - 使用 GatewayHeaders
- [x] 文档更新完成
- [ ] 其他服务（oms-core, ledger-core...）集成 GatewayHeaders

---

## 🚀 下一步工作

1. **其他服务集成**:
   ```
   oms-core: 使用 GatewayHeaders 获取 userId/accountId
   ledger-core: 使用 GatewayHeaders 获取操作人信息
   position-snapshot-core: 使用 GatewayHeaders 获取账户ID
   ```

2. **Feign 配置**:
   ```
   添加 FeignConfig，透传 Gateway Headers
   ```

3. **生产环境配置**:
   ```
   网络隔离：禁止直接访问下游服务端口
   ```

---

*迁移完成时间: 2026-02-19*  
*文档版本: v1.0*
