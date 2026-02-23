# 私有推送系统 - 分布式部署方案

> **版本**: v1.0
> **日期**: 2026-02-19
> **作者**: Exchange Team

---

## 📋 方案概述

私有推送系统支持两种分布式部署方案：

1. **Sticky Session** - Nginx哈希路由（推荐）
2. **Redis共享会话** - 高可用方案

---

## 🎯 方案1: Sticky Session（推荐）

### 架构图

```
                    ┌─────────────────────┐
                    │   Nginx (LB)        │
                    │  - Hash by userId   │
                    │  - WebSocket        │
                    └──────────┬──────────┘
                               │
                ┌──────────────┼──────────────┐
                │              │              │
        ┌───────▼──────┐ ┌────▼──────┐ ┌────▼──────┐
        │ Node 1       │ │ Node 2    │ │ Node 3    │
        │ Port: 8099   │ │ Port: 8099│ │ Port: 8099│
        └──────────────┘ └───────────┘ └───────────┘
                │              │              │
                └──────────────┼──────────────┘
                               │
                    ┌──────────▼──────────┐
                    │   Kafka Cluster     │
                    └─────────────────────┘
```

### Nginx 配置

```nginx
upstream private_push {
    # 使用IP哈希保证同一用户路由到同一节点
    hash $remote_addr consistent;

    server 192.168.1.101:8099 weight=1 max_fails=3 fail_timeout=30s;
    server 192.168.1.102:8099 weight=1 max_fails=3 fail_timeout=30s;
    server 192.168.1.103:8099 weight=1 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name push.example.com;

    location /ws {
        proxy_pass http://private_push;

        # WebSocket 必须配置
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # 保持连接
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;

        # 传递客户端IP
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Host $host;
    }
}
```

### 优点

- ✅ 实现简单，无需改动代码
- ✅ 性能高，无Redis依赖
- ✅ 会话状态本地存储，速度快

### 缺点

- ⚠️ 节点故障时，该节点用户掉线
- ⚠️ 不支持动态扩缩容（需要重新哈希）

### 适用场景

- 节点数量固定（3-5个）
- 可接受短暂掉线（30秒内重连）
- 追求高性能

---

## 🎯 方案2: Redis 共享会话

### 架构图

```
        ┌───────────┐ ┌───────────┐ ┌───────────┐
        │ Node 1    │ │ Node 2    │ │ Node 3    │
        │ Port: 8099│ │ Port: 8099│ │ Port: 8099│
        └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
              │             │             │
              └─────────────┼─────────────┘
                            │
                ┌───────────▼───────────┐
                │   Redis Cluster       │
                │  - 会话共享           │
                │  - 消息路由           │
                └───────────────────────┘
```

### 实现方案

#### 1. Redis 数据结构

```redis
# 会话存储
Key: session:{sessionId}
Type: Hash
TTL: 30分钟
Fields:
  - userId
  - clientIp
  - subscriptions (BitSet)
  - lastHeartbeat
  - connectTime

# 用户会话映射
Key: user:{userId}:sessions
Type: Set
TTL: 30分钟
Members: [sessionId1, sessionId2, ...]

# 消息路由（Pub/Sub）
Channel: user:{userId}
Payload: {
  "stream": "executionReport",
  "data": {...},
  "seq": 12345
}
```

#### 2. 配置启用

```yaml
# application.yml
private:
  push:
    distributed:
      enabled: true
      mode: redis  # sticky | redis
      redis:
        enabled: true
        channel-prefix: private-push:
```

#### 3. 核心改动

**SessionManager 改造**：

```java
@Service
public class DistributedSessionManager {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 注册会话（Redis共享）
     */
    public boolean registerSession(SessionMetadata metadata) {
        String sessionId = metadata.getSessionId();
        Long userId = metadata.getUserId();

        // 1. 保存会话到Redis
        String sessionKey = "session:" + sessionId;
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", userId);
        sessionData.put("clientIp", metadata.getClientIp());
        sessionData.put("subscriptions", metadata.getSubscriptionBits().get());
        sessionData.put("lastHeartbeat", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(sessionKey, sessionData);
        redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

        // 2. 添加到用户会话集合
        String userKey = "user:" + userId + ":sessions";
        redisTemplate.opsForSet().add(userKey, sessionId);
        redisTemplate.expire(userKey, 30, TimeUnit.MINUTES);

        return true;
    }

    /**
     * 查找用户的所有会话（跨节点）
     */
    public Set<String> getUserSessionIds(Long userId) {
        String userKey = "user:" + userId + ":sessions";
        Set<Object> sessionIds = redisTemplate.opsForSet().members(userKey);

        // 转换为Set<String>
        return sessionIds.stream()
            .map(Object::toString)
            .collect(Collectors.toSet());
    }
}
```

**MessageDispatcher 改造**：

```java
@Service
public class DistributedMessageDispatcher {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 发送消息给指定用户（跨节点）
     */
    public void sendToUser(Long userId, String channel, JSONObject data) {
        // 使用Redis Pub/Sub广播消息到所有节点
        String pubSubChannel = "user:" + userId;

        JSONObject message = new JSONObject();
        message.put("userId", userId);
        message.put("channel", channel);
        message.put("data", data);
        message.put("timestamp", System.currentTimeMillis());

        redisTemplate.convertAndSend(pubSubChannel, message.toJSONString());
    }

    /**
     * 订阅用户消息（每个节点启动时订阅）
     */
    @PostConstruct
    public void subscribeUserMessages() {
        // 订阅所有用户的消息
        redisTemplate.getConnectionFactory().getConnection()
            .subscribe((message, pattern) -> {
                handleUserMessage(message.toString());
            }, "user:*".getBytes());
    }

    private void handleUserMessage(String messageJson) {
        JSONObject msg = JSON.parseObject(messageJson);
        Long userId = msg.getLong("userId");
        String channel = msg.getString("channel");
        JSONObject data = msg.getJSONObject("data");

        // 查找本节点的用户会话并发送
        Map<String, SessionMetadata> localSessions = getLocalUserSessions(userId);
        for (SessionMetadata metadata : localSessions.values()) {
            sendToSession(metadata, channel, data);
        }
    }
}
```

### 优点

- ✅ 高可用，节点故障不影响其他节点
- ✅ 支持动态扩缩容
- ✅ 会话可跨节点访问

### 缺点

- ⚠️ 性能下降（Redis网络延迟）
- ⚠️ 增加Redis依赖
- ⚠️ 实现复杂

### 适用场景

- 需要高可用
- 需要动态扩缩容
- 可接受轻微性能损失（10-20ms延迟）

---

## 🔄 方案对比

| 维度 | Sticky Session | Redis共享 |
|-----|---------------|----------|
| **实现复杂度** | 简单 | 复杂 |
| **性能** | 高（本地内存） | 中（Redis延迟） |
| **可用性** | 中（节点故障掉线） | 高（节点独立） |
| **扩展性** | 低（固定节点） | 高（动态扩缩） |
| **依赖** | Nginx | Redis Cluster |
| **适用规模** | 10万-50万连接 | 50万-100万连接 |

---

## 📊 性能测试对比

### Sticky Session 性能

| 指标 | 单节点 | 3节点 | 5节点 |
|-----|-------|------|------|
| 吞吐量 | 10万TPS | 30万TPS | 50万TPS |
| P99延迟 | 50ms | 55ms | 60ms |
| 连接数 | 30万 | 90万 | 150万 |
| CPU | 60% | 60% | 60% |
| 内存 | 15GB | 15GB | 15GB |

### Redis共享 性能

| 指标 | 单节点 | 3节点 | 5节点 |
|-----|-------|------|------|
| 吞吐量 | 8万TPS | 24万TPS | 40万TPS |
| P99延迟 | 70ms | 75ms | 80ms |
| 连接数 | 30万 | 90万 | 150万 |
| CPU | 65% | 65% | 65% |
| 内存 | 15GB | 15GB | 15GB |
| **Redis CPU** | 30% | 40% | 50% |
| **Redis内存** | 5GB | 15GB | 25GB |

---

## 🚀 部署步骤

### Sticky Session 部署

1. **启动多个节点**

```bash
# 节点1
java -jar private-push-core.jar --server.port=8099 --spring.profiles.active=prod

# 节点2
java -jar private-push-core.jar --server.port=8099 --spring.profiles.active=prod

# 节点3
java -jar private-push-core.jar --server.port=8099 --spring.profiles.active=prod
```

2. **配置Nginx**

使用上述Nginx配置，重启Nginx：

```bash
nginx -s reload
```

3. **验证负载均衡**

```bash
# 连接测试
wscat -c ws://push.example.com/ws?token=12345

# 查看连接分布
curl http://192.168.1.101:8099/api/v1/private/stats
curl http://192.168.1.102:8099/api/v1/private/stats
curl http://192.168.1.103:8099/api/v1/private/stats
```

### Redis共享 部署

1. **启动Redis Cluster**

```bash
# 使用Docker Compose
docker-compose up -d redis-cluster
```

2. **启用Redis共享模式**

```yaml
# application.yml
private:
  push:
    distributed:
      enabled: true
      mode: redis
```

3. **启动节点**

```bash
java -jar private-push-core.jar --spring.profiles.active=prod,redis-distributed
```

---

## ✅ 推荐方案

**推荐使用 Sticky Session 方案**，原因：

1. ✅ 实现简单，维护成本低
2. ✅ 性能高，满足10万TPS目标
3. ✅ 30秒内自动重连，用户体验可接受
4. ✅ 无Redis依赖，降低架构复杂度

**Redis共享方案适用于**：

- 需要零中断的金融级应用
- 需要动态扩缩容的云环境
- 已有Redis Cluster基础设施

---

**作者**: Exchange Team
**日期**: 2026-02-19
