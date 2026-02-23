API Gateway 服务详细需求文档（交易所级）
1. 服务定位
1.1 服务职责
API Gateway 是交易所对外的 统一入口与流量控制核心，承担：

统一外部接入

身份认证与授权

全局与用户级限流

Symbol 级交易开关

风控前置拦截

灰度与熔断

链路打标与审计

1.2 核心原则（交易所级）

❌ 不做业务决策

❌ 不维护业务状态

❌ 不缓存订单/资金

✅ 只做：入口治理 + 安全 + 流控 + 编排转发

2. 模块结构设计
2.1 包结构
gateway/
 ├── filter/
 │    ├── AuthFilter
 │    ├── RateLimitFilter
 │    ├── AclFilter
 │    ├── SymbolSwitchFilter
 │    ├── CircuitBreakerFilter
 │    ├── IdempotencyFilter
 │    ├── TraceContextFilter
 │
 ├── controller/
 │    └── OrderRouteController
 │
 ├── client/
 │    ├── OmsClient
 │    ├── RiskClient
 │
 ├── config/
 │    ├── GatewayProperties
 │    ├── FilterChainConfig
 │
 ├── model/
 │    ├── OrderRequest
 │    ├── OrderResponse
 │    ├── UserContext
 │
 └── audit/
      └── ApiAuditLogger

3. Filter 链设计（极重要）
3.1 标准 Filter 顺序（生产级）
TraceContextFilter
   ↓
AuthFilter
   ↓
AclFilter
   ↓
RateLimitFilter
   ↓
SymbolSwitchFilter
   ↓
IdempotencyFilter
   ↓
CircuitBreakerFilter
   ↓
OrderRouteController


这是面试与生产都通用的标准顺序。

4. 各 Filter 详细职责
4.1 TraceContextFilter（链路上下文）
职责

生成 traceId

生成 requestId

绑定 userId 到 MDC

透传到下游服务

核心字段
X-Trace-Id
X-Request-Id
X-User-Id

要求

所有日志必须带 traceId

所有下游 RPC 必须透传

4.2 AuthFilter（认证）
职责

JWT / OAuth2 校验

Token 有效性

用户身份解析

输出
UserContext {
   long userId;
   String role;
   Set<String> permissions;
}

拒绝条件

Token 失效

用户被封禁

风控冻结账户

4.3 AclFilter（权限控制）
职责

API 级权限

角色访问控制

高危接口控制

示例规则
POST /api/v1/order   -> ROLE_TRADER
POST /api/v1/admin/* -> ROLE_ADMIN

4.4 RateLimitFilter（限流）
限流维度（交易所级）
维度	说明
IP	防攻击
userId	防刷单
apiKey	API 用户
symbol	热点品种保护
限流算法

Token Bucket

Redis + 本地滑窗

Sentinel / Envoy 可选

示例配置
rate-limit:
  perUser:
    submitOrder: 50/s
  perIP:
    submitOrder: 20/s
  perSymbol:
    BTC-USDT: 200/s

4.5 SymbolSwitchFilter（交易对开关）
职责

支持单 Symbol 级交易暂停

支持全站交易暂停

支持只允许平仓

典型场景

极端行情

风控接管

运维维护

配置来源

Nacos / Config Center

4.6 IdempotencyFilter（幂等）
适用接口

submitOrder

cancelOrder

withdraw

幂等 Key
X-Idempotency-Key

实现

Redis SETNX

TTL 30s~120s

防止重试重复下单

4.7 CircuitBreakerFilter（熔断）
职责

OMS 不可用时快速失败

防止级联雪崩

自动降级

策略

熔断 OMS

返回系统繁忙

不重试撮合链路

5. OrderRouteController 设计
5.1 职责

只负责转发

不做业务逻辑

不改订单状态

5.2 示例代码（生产风格）
@RestController
@RequestMapping("/api/v1/order")
public class OrderRouteController {

    private final OmsClient omsClient;

    @PostMapping("/submit")
    public OrderResponse submitOrder(@RequestBody OrderRequest req,
                                     UserContext userContext) {

        req.setUserId(userContext.getUserId());
        req.setTraceId(TraceContext.getTraceId());

        return omsClient.submit(req);
    }
}

6. 与 OMS 的契约（强约束）
6.1 不允许 Gateway 做的事

❌ 不冻结资金

❌ 不改订单状态

❌ 不生成订单号

❌ 不写 DB

6.2 必须透传字段
traceId
requestId
userId
clientOrderId

7. 风控联动（交易所级）
7.1 Gateway 级拦截

黑名单用户

黑名单 IP

高频异常

7.2 不做风控决策

Gateway 不判断：

保证金是否足够

持仓是否允许

这些必须在：

Hard Risk Gate
Account Service

8. 审计与合规
8.1 ApiAuditLogger
记录字段

userId

api

symbol

qty

price

ip

latency

resultCode

用途

合规审计

异常交易追踪

纠纷取证

9. 失败与重试策略（极重要）
9.1 不重试的场景

submitOrder

cancelOrder

原因：

下单接口必须由客户端幂等控制，
Gateway 不做自动重试。

9.2 允许重试的场景

查询类接口

行情类接口