# 分布式会话共享实现文档

> **方案**: JWT + Redis 混合模式  
> **适用场景**: 分布式微服务架构下的用户认证与会话管理

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                        分布式会话架构                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   ┌──────────────┐                                                 │
│   │    Client    │                                                 │
│   │  (Web/App)   │                                                 │
│   └──────┬───────┘                                                 │
│          │ 1. 登录请求                                               │
│          ▼                                                          │
│   ┌──────────────┐    2. 生成Token    ┌──────────────┐            │
│   │  API Gateway │ ─────────────────→ │  user-core   │            │
│   │              │                    │   (8099)     │            │
│   └──────┬───────┘                    └──────┬───────┘            │
│          │                                   │                     │
│          │ 3. 存储Token                      │ 3. 存储到Redis      │
│          │    到Redis                        │                     │
│          │                                   ▼                     │
│          │                          ┌──────────────┐              │
│          │                          │    Redis     │              │
│          │                          │   DB 2       │              │
│          │                          └──────────────┘              │
│          │                                                         │
│          │ 4. 返回Token                                            │
│          ▼                                                         │
│   ┌──────────────┐                                                 │
│   │    Client    │  ← 保存Token (LocalStorage/Keychain)           │
│   └──────────────┘                                                 │
│                                                                     │
│   ┌────────────────────────────────────────────────────────────┐  │
│   │                     后续请求流程                            │  │
│   └────────────────────────────────────────────────────────────┘  │
│                                                                     │
│   ┌──────────────┐                                                 │
│   │    Client    │  5. 携带Token请求                              │
│   │  (Web/App)   │    Authorization: Bearer {token}               │
│   └──────┬───────┘                                                 │
│          ▼                                                          │
│   ┌──────────────┐    6. 转发到user-core   ┌──────────────┐      │
│   │  API Gateway │  ←────────────────────→ │  user-core   │      │
│   │              │    验证Token有效性      │   (8099)     │      │
│   └──────┬───────┘                        └──────┬───────┘      │
│          │                                        │               │
│          │                                        │ 7. 查Redis    │
│          │                                        │    验证Token  │
│          │                                        │    不在黑名单 │
│          │                                        ▼               │
│          │                               ┌──────────────┐        │
│          │                               │    Redis     │        │
│          │                               └──────────────┘        │
│          │                                                         │
│          │ 8. 返回业务数据                                        │
│          ▼                                                         │
│   ┌──────────────┐                                                 │
│   │    Client    │                                                 │
│   └──────────────┘                                                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔑 Redis Key 设计

| Key 模式 | 类型 | 说明 | TTL |
|---------|------|------|-----|
| `user:tokens:{userId}` | Hash | 存储用户所有设备的Token | 24h |
| `token:blacklist:{token}` | String | 已失效的Token黑名单 | JWT剩余时间 |
| `token:refresh:{token}` | String | Token刷新映射 | 24h |
| `device:info:{token}` | String | 设备信息JSON | 24h |

### 示例

```bash
# 查看用户的所有登录设备
HGETALL user:tokens:10001
# 返回: { "WEB": "eyJhbGciOiJIUzI1NiIs...", "APP": "eyJhbGciOiJIUzI1NiIs..." }

# 检查Token是否在黑名单
EXISTS token:blacklist:eyJhbGciOiJIUzI1NiIs...
# 返回: 1 (在黑名单) 或 0 (有效)

# 查看设备信息
GET device:info:eyJhbGciOiJIUzI1NiIs...
# 返回: { "deviceType": "WEB", "ip": "192.168.1.1", "loginTime": 1705310400000 }
```

---

## 📚 API 接口

### 1. 登录（自动存储Token到Redis）

```http
POST /api/v1/user/login
Content-Type: application/json
X-Device-Type: WEB          # 可选：WEB/APP/API
X-Device-Id: device-001     # 可选：设备ID
X-Device-Name: Chrome-120   # 可选：设备名称

{
    "username": "trader001",
    "password": "123456"
}
```

**流程**:
1. 验证用户名密码
2. 生成JWT Token
3. **存储到Redis**: `user:tokens:{userId}` = {deviceType: token}
4. **存储设备信息**: `device:info:{token}`
5. 返回Token给客户端

---

### 2. Token刷新

```http
POST /api/v1/auth/refresh
Authorization: Bearer {old_token}
```

**流程**:
1. 验证旧Token有效
2. 检查旧Token不在黑名单
3. 生成新Token
4. **旧Token加入黑名单**: `token:blacklist:{old_token}`
5. **存储新Token到Redis**
6. 返回新Token

---

### 3. 登出（当前设备）

```http
POST /api/v1/auth/logout
Authorization: Bearer {token}
```

**流程**:
1. 从请求头提取Token
2. **从Redis移除**: `user:tokens:{userId}` 中的该设备
3. **加入黑名单**: `token:blacklist:{token}`
4. 删除设备信息

---

### 4. 登出所有设备

```http
POST /api/v1/auth/logout/all
Authorization: Bearer {token}
```

**流程**:
1. 获取用户所有设备Token
2. 将所有Token加入黑名单
3. 删除 `user:tokens:{userId}`

---

### 5. 查询在线设备

```http
GET /api/v1/auth/devices
Authorization: Bearer {token}
```

**响应**:
```json
{
    "code": 200,
    "data": [
        {
            "deviceType": "WEB",
            "deviceName": "Chrome-120",
            "ip": "192.168.1.100",
            "loginTime": 1705310400000,
            "current": true
        },
        {
            "deviceType": "APP",
            "deviceName": "iPhone-15",
            "ip": "192.168.1.101",
            "loginTime": 1705300000000,
            "current": false
        }
    ]
}
```

---

### 6. 踢掉其他设备

```http
POST /api/v1/auth/devices/kick-others
Authorization: Bearer {token}
```

**场景**: 保持当前设备登录，其他设备强制下线

---

### 7. 踢掉指定设备

```http
POST /api/v1/auth/devices/kick/APP
Authorization: Bearer {token}
```

---

## 🔧 核心组件

### TokenManager

```java
// 存储Token
void storeToken(Long userId, String token, String deviceType, DeviceInfo info);

// 验证Token（JWT格式 + 未过期 + 不在黑名单）
boolean validateToken(String token);

// 使Token失效
void invalidateToken(String token, Long userId, String deviceType);

// 刷新Token
String refreshToken(String oldToken);

// 获取用户所有Token
Map<Object, Object> getUserTokens(Long userId);

// 强制登出所有设备
void invalidateAllUserTokens(Long userId);

// 踢掉其他设备
void kickOtherDevices(Long userId, String keepDeviceType);
```

### UserContext

```java
// 在Controller/Service中获取当前登录用户
Long userId = UserContext.getCurrentUserId();
Long accountId = UserContext.getCurrentAccountId();
UserContext.UserInfo userInfo = UserContext.getCurrentUser();
```

---

## 🌐 分布式场景下的会话共享

### 场景1：用户服务集群

```
用户A登录 → user-core-1 (生成Token → Redis)
        ↓
用户A请求 → user-core-2 (查Redis验证Token) ✓
        ↓
用户A请求 → user-core-3 (查Redis验证Token) ✓
```

**关键点**: Token存储在Redis，所有节点共享

---

### 场景2：Token刷新同步

```
用户A刷新Token:
  APP 请求 /api/v1/auth/refresh
    ↓
  旧Token加入黑名单 (Redis)
  生成新Token
    ↓
  其他设备: 旧Token失效 (查黑名单) ✓
```

---

### 场景3：强制登出

```
运营后台踢掉用户:
  POST /api/v1/auth/logout/all (userId=10001)
    ↓
  查询 Redis: user:tokens:10001
    ↓
  所有Token加入黑名单
    ↓
  所有设备被迫下线
```

---

## 🚀 使用 ThreadLocal 获取当前用户

在 Controller/Service 中无需从Request提取Token：

```java
@RestController
public class OrderController {
    
    @PostMapping("/order/create")
    public Result<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // 直接从ThreadLocal获取用户信息
        Long userId = UserContext.getCurrentUserId();
        Long accountId = UserContext.getCurrentAccountId();
        
        // 下单逻辑...
    }
}
```

**原理**: TokenFilter 在请求开始时将用户信息存入 ThreadLocal

---

## 📝 最佳实践

### 1. Token有效期设置

```yaml
jwt:
  expiration: 86400000  # 24小时

# Redis TTL 与 JWT 过期时间保持一致
```

### 2. 刷新Token时机

```javascript
// 客户端在Token过期前自动刷新
// 例如：Token有效期24小时，在23小时时刷新

function shouldRefreshToken(token) {
    const payload = jwt.decode(token);
    const expiresIn = payload.exp * 1000 - Date.now();
    return expiresIn < 3600000; // 少于1小时刷新
}
```

### 3. 多设备登录策略

```java
// 单设备登录（登录时踢掉其他设备）
@Service
public class UserServiceImpl {
    public Result<UserResponse> login(...) {
        // ... 验证成功后
        tokenManager.kickOtherDevices(userId, deviceType);
        tokenManager.storeToken(userId, token, deviceType, deviceInfo);
    }
}
```

### 4. 黑名单清理策略

```java
// Token过期后自动从黑名单删除（通过Redis TTL）
// 无需手动清理
```

---

## 🔒 安全建议

1. **使用HTTPS**: Token在Header中传输，必须加密
2. **Token存储**: 
   - Web: HttpOnly Cookie 或 LocalStorage
   - App: Keychain/Keystore
3. **防止CSRF**: 配合CSRF Token使用
4. **限流**: 登录接口限流，防止暴力破解
5. **IP绑定**: 敏感操作验证IP是否变化

---

## 🐛 常见问题

### Q1: JWT本身无状态，为什么还要Redis？

**A**: JWT无法主动失效（登出后Token仍然有效直到过期）。
Redis黑名单机制解决了这个问题。

### Q2: 为什么不用Spring Session？

**A**: 
- Spring Session 需要序列化Session对象
- 不适合微服务间的Token传递
- JWT + Redis 更轻量，更适合API场景

### Q3: Token被盗怎么办？

**A**: 
1. 用户发现后调用 `/api/v1/auth/logout/all`
2. 所有Token失效
3. 修改密码

### Q4: Redis挂了怎么办？

**A**: 
- JWT仍然可以验证（无状态）
- 但无法验证黑名单（可能导致已登出Token仍然有效）
- 建议Redis集群部署，保证高可用

---

*文档版本: v1.0*  
*最后更新: 2026-02-19*
