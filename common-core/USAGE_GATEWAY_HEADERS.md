# GatewayHeaders 使用指南

> 所有下游服务（oms-core, ledger-core 等）获取当前用户信息的统一方式

---

## 📦 依赖

所有服务已依赖 `common-core`，直接使用：

```java
import com.exchange.common.core.context.GatewayHeaders;
```

---

## 🚀 快速开始

### 1. 在 Controller 中获取用户信息

```java
@RestController
@RequestMapping("/api/v1/order")
public class OrderController {
    
    @PostMapping("/create")
    public Result<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // 获取当前用户ID（从 Gateway Header）
        Long userId = GatewayHeaders.getUserId();
        Long accountId = GatewayHeaders.getAccountId();
        String username = GatewayHeaders.getUsername();
        
        log.info("Creating order for user: {}, account: {}", userId, accountId);
        
        // 执行业务逻辑...
        return orderService.createOrder(userId, accountId, request);
    }
}
```

### 2. 在 Service 中获取用户信息

```java
@Service
public class OrderServiceImpl implements OrderService {
    
    @Override
    public OrderResponse createOrder(CreateOrderRequest request) {
        // 同样可以直接获取，不需要从 Controller 传递
        Long userId = GatewayHeaders.getUserId();
        
        // 或者强制要求登录（未登录会抛异常）
        long requiredUserId = GatewayHeaders.requireUserId();
        
        // 业务逻辑...
    }
}
```

### 3. 使用带默认值的方式

```java
@Service
public class SomeService {
    
    public void doSomething() {
        // 如果未登录，使用默认值 0（系统操作）
        long userId = GatewayHeaders.getUserIdOrDefault(0L);
        
        // 业务逻辑...
    }
}
```

### 4. 获取完整用户信息

```java
GatewayHeaders.UserInfo userInfo = GatewayHeaders.getUserInfo();
if (userInfo != null) {
    System.out.println(userInfo.getUserId());
    System.out.println(userInfo.getAccountId());
    System.out.println(userInfo.getUsername());
}
```

---

## 📋 方法列表

| 方法 | 返回值 | 说明 |
|-----|--------|------|
| `getUserId()` | `Long` | 用户ID，未登录返回 null |
| `getAccountId()` | `Long` | 账户ID，未设置返回 null |
| `getUsername()` | `String` | 用户名，未设置返回 null |
| `getUserInfo()` | `UserInfo` | 完整用户信息 |
| `getUserIdOrDefault(long)` | `long` | 用户ID，带默认值 |
| `getAccountIdOrDefault(long)` | `long` | 账户ID，带默认值 |
| `isAuthenticated()` | `boolean` | 是否已登录 |
| `isAnonymous()` | `boolean` | 是否未登录 |
| `requireUserId()` | `long` | 强制获取用户ID，未登录抛异常 |
| `requireAccountId()` | `long` | 强制获取账户ID，未设置抛异常 |

---

## ⚠️ 重要提示

### 1. 必须通过 API Gateway 访问

```bash
# ✅ 正确：通过 Gateway（8080）
curl -H "Authorization: Bearer {token}" \
  http://localhost:8080/api/v1/order/create

# ❌ 错误：直接访问服务（8091）
curl http://localhost:8091/api/v1/order/create
# 结果：GatewayHeaders.getUserId() 返回 null
```

### 2. Header 常量

```java
// Gateway 设置的 Header 名称
GatewayHeaders.HEADER_USER_ID      // "X-User-Id"
GatewayHeaders.HEADER_ACCOUNT_ID   // "X-Account-Id"
GatewayHeaders.HEADER_USERNAME     // "X-Username"
```

### 3. Feign 调用自动透传

如果使用 Feign 在服务间调用，需要配置 RequestInterceptor：

```java
@Configuration
public class FeignConfig {
    
    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            // 从当前请求的 Header 中获取用户信息
            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
            if (requestAttributes instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
                
                String userId = request.getHeader(GatewayHeaders.HEADER_USER_ID);
                String accountId = request.getHeader(GatewayHeaders.HEADER_ACCOUNT_ID);
                String username = request.getHeader(GatewayHeaders.HEADER_USERNAME);
                
                // 透传给下游服务
                if (userId != null) {
                    requestTemplate.header(GatewayHeaders.HEADER_USER_ID, userId);
                }
                if (accountId != null) {
                    requestTemplate.header(GatewayHeaders.HEADER_ACCOUNT_ID, accountId);
                }
                if (username != null) {
                    requestTemplate.header(GatewayHeaders.HEADER_USERNAME, username);
                }
            }
        };
    }
}
```

---

## 🔧 各服务集成示例

### oms-core（订单服务）

```java
@RestController
@RequestMapping("/api/v1/order")
public class OrderController {
    
    @PostMapping("/create")
    public Result<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // 从 Gateway Header 获取用户信息
        long userId = GatewayHeaders.requireUserId();
        long accountId = GatewayHeaders.requireAccountId();
        
        // 创建订单...
        Order order = new Order();
        order.setUserId(userId);
        order.setAccountId(accountId);
        order.setSymbol(request.getSymbol());
        // ...
        
        return Result.success(order);
    }
}
```

### ledger-core（账本服务）

```java
@Service
public class LedgerServiceImpl implements LedgerService {
    
    @Transactional
    public void applyTrade(Trade trade) {
        // 获取当前操作用户（用于审计）
        Long operatorId = GatewayHeaders.getUserId();
        String operatorName = GatewayHeaders.getUsername();
        
        // 记录账本，同时记录操作人
        LedgerEntry entry = new LedgerEntry();
        entry.setAmount(trade.getAmount());
        entry.setOperatorId(operatorId);
        entry.setOperatorName(operatorName);
        // ...
    }
}
```

### position-snapshot-core（持仓服务）

```java
@RestController
@RequestMapping("/api/v1/position")
public class PositionController {
    
    @GetMapping("/list")
    public Result<List<Position>> getPositions(@RequestParam String symbol) {
        // 获取当前用户账户
        Long accountId = GatewayHeaders.getAccountId();
        
        if (accountId == null) {
            return Result.error(401, "Not authenticated");
        }
        
        // 查询持仓
        List<Position> positions = positionService.getPositions(accountId, symbol);
        return Result.success(positions);
    }
}
```

---

## 🧪 测试

### 单元测试

```java
@SpringBootTest
class OrderControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testCreateOrder() throws Exception {
        mockMvc.perform(post("/api/v1/order/create")
                .header("X-User-Id", "10001")      // 模拟 Gateway 透传
                .header("X-Account-Id", "20001")
                .header("X-Username", "testuser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\"}"))
            .andExpect(status().isOk());
    }
}
```

---

## 📝 总结

1. **所有下游服务**都使用 `GatewayHeaders` 获取用户信息
2. **必须通过 API Gateway** 访问，Header 才会被设置
3. **Feign 调用**需要配置 Interceptor 透传 Header
4. **单元测试**需要手动设置 Header 模拟 Gateway

---

*文档版本: v1.0*  
*最后更新: 2026-02-19*
