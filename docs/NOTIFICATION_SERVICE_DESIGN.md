# Notification Service 设计文档

## 1. 服务定位

Notification Service 是统一消息通知中心，负责：
- 接收各类系统事件并转换为通知
- 支持多种通知渠道（站内信、App Push、短信、邮件）
- 管理用户通知偏好设置
- 实现通知限流和去重

## 2. 架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                   Notification Service (Port: 8097)                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Input Layer (事件输入层)                                            │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │
│  │ Order Event │ │ Risk Event  │ │ System Event│ │ Admin Event │   │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘   │
│         │               │               │               │          │
│         └───────────────┴───────────────┴───────────────┘          │
│                             │                                       │
│                             ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              Notification Router (规则引擎)                  │   │
│  │                                                             │   │
│  │  路由规则:                                                   │   │
│  │  - ORDER_FILLED → Push + InApp                              │   │
│  │  - LIQUIDATION_WARNING → Push + SMS + Email                 │   │
│  │  - FUNDING_FEE_SETTLED → Push + InApp                       │   │
│  │  - LARGE_DEPOSIT → Push + SMS + Email                       │   │
│  │  - LOGIN_ANOMALY → Push + SMS + Email                       │   │
│  └────────────────────────┬────────────────────────────────────┘   │
│                           │                                         │
│           ┌───────────────┼───────────────┐                        │
│           ▼               ▼               ▼                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │  In-App     │  │   Push      │  │   SMS       │                 │
│  │  (站内信)    │  │  (App推送)   │  │  (短信)      │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## 3. 通知类型

### 3.1 业务通知

| 通知类型 | 触发条件 | 优先级 | 渠道 | 模板ID |
|---------|---------|--------|------|--------|
| ORDER_CREATED | 订单创建 | 低 | InApp | T001 |
| ORDER_FILLED | 订单成交 | 中 | InApp, Push | T002 |
| ORDER_CANCELLED | 订单撤销 | 低 | InApp | T003 |
| POSITION_OPENED | 仓位开启 | 中 | InApp | T004 |
| POSITION_CLOSED | 仓位关闭 | 中 | InApp, Push | T005 |
| FUNDING_FEE_PAID | 支付资金费 | 低 | InApp | T006 |
| FUNDING_FEE_RECEIVED | 收到资金费 | 低 | InApp | T007 |

### 3.2 风控通知

| 通知类型 | 触发条件 | 优先级 | 渠道 | 模板ID |
|---------|---------|--------|------|--------|
| MARGIN_CALL_WARNING | 保证金率<10% | 高 | Push, SMS, Email, InApp | T101 |
| MARGIN_CALL_CRITICAL | 保证金率<5% | 紧急 | Push, SMS, Email, InApp | T102 |
| LIQUIDATION_WARNING | 即将强平 | 紧急 | Push, SMS, Email, InApp | T103 |
| LIQUIDATION_EXECUTED | 强平执行 | 高 | Push, SMS, Email, InApp | T104 |
| ADL_EXECUTED | ADL减仓 | 高 | Push, Email, InApp | T105 |
| TAKE_PROFIT_TRIGGERED | 止盈触发 | 中 | Push, InApp | T106 |
| STOP_LOSS_TRIGGERED | 止损触发 | 中 | Push, InApp | T107 |

### 3.3 账户安全通知

| 通知类型 | 触发条件 | 优先级 | 渠道 | 模板ID |
|---------|---------|--------|------|--------|
| LOGIN_SUCCESS | 登录成功 | 低 | InApp | T201 |
| LOGIN_FAILURE | 登录失败 | 中 | Push, Email | T202 |
| LOGIN_ANOMALY | 异地登录 | 高 | Push, SMS, Email | T203 |
| PASSWORD_CHANGED | 密码修改 | 高 | SMS, Email | T204 |
| WITHDRAWAL_REQUEST | 提币申请 | 高 | Email | T205 |
| WITHDRAWAL_SUCCESS | 提币成功 | 高 | Push, Email | T206 |
| LARGE_DEPOSIT | 大额充值 | 中 | Push, Email | T207 |

## 4. 核心API

### 4.1 用户通知设置

```java
@RestController
@RequestMapping("/api/v1/notification")
public class NotificationController {
    
    /**
     * 获取用户通知设置
     */
    @GetMapping("/settings")
    public Result<NotificationSettings> getSettings(@RequestParam Long userId);
    
    /**
     * 更新通知设置
     */
    @PostMapping("/settings")
    public Result<Void> updateSettings(@RequestBody NotificationSettings settings);
    
    /**
     * 查询通知历史
     */
    @GetMapping("/history")
    public Result<Page<Notification>> getHistory(
            @RequestParam Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size);
    
    /**
     * 标记已读
     */
    @PostMapping("/read")
    public Result<Void> markAsRead(@RequestParam Long notificationId);
    
    /**
     * 发送测试通知
     */
    @PostMapping("/test")
    public Result<Void> sendTestNotification(@RequestBody TestNotificationRequest request);
}
```

## 5. Kafka 消费者实现

```java
@Component
public class NotificationConsumer {
    
    @Autowired
    private NotificationRouter router;
    
    /**
     * 处理订单事件
     */
    @KafkaListener(topics = "order-state-topic")
    public void onOrderEvent(OrderStateEvent event) {
        Notification notification = new Notification();
        notification.setUserId(event.getUserId());
        notification.setType(NotificationType.ORDER);
        
        switch (event.getStatus()) {
            case "FILLED":
                notification.setTemplateId("T002");
                notification.setTitle("Order Filled");
                notification.setContent(String.format(
                    "Your order %d has been filled at price %s", 
                    event.getOrderId(), event.getPrice()));
                notification.setChannels(Arrays.asList(Channel.IN_APP, Channel.PUSH));
                break;
            // ...
        }
        
        router.route(notification);
    }
    
    /**
     * 处理风控事件
     */
    @KafkaListener(topics = "risk-alert-topic")
    public void onRiskEvent(RiskAlertEvent event) {
        Notification notification = new Notification();
        notification.setUserId(event.getUserId());
        notification.setType(NotificationType.RISK);
        notification.setPriority(Priority.HIGH);
        
        switch (event.getAlertType()) {
            case "MARGIN_CALL_WARNING":
                notification.setTemplateId("T101");
                notification.setChannels(Arrays.asList(
                    Channel.IN_APP, Channel.PUSH, Channel.SMS, Channel.EMAIL));
                break;
            // ...
        }
        
        router.route(notification);
    }
}
```

## 6. 通知渠道实现

### 6.1 站内信 (In-App)

```java
@Component
public class InAppChannel implements NotificationChannel {
    
    @Autowired
    private NotificationRepository repository;
    
    @Autowired
    private WebSocketNotifier wsNotifier;
    
    @Override
    public void send(Notification notification) {
        // 1. 保存到数据库
        repository.save(notification);
        
        // 2. 推送到WebSocket
        wsNotifier.sendToUser(notification.getUserId(), "notification", notification);
    }
    
    @Override
    public Channel getType() {
        return Channel.IN_APP;
    }
}
```

### 6.2 App Push

```java
@Component
public class PushChannel implements NotificationChannel {
    
    @Autowired
    private PushService pushService;
    
    @Override
    public void send(Notification notification) {
        // 获取用户设备token
        List<String> deviceTokens = getDeviceTokens(notification.getUserId());
        
        for (String token : deviceTokens) {
            PushMessage message = PushMessage.builder()
                .token(token)
                .title(notification.getTitle())
                .body(notification.getContent())
                .data(notification.getExtraData())
                .build();
                
            pushService.send(message);
        }
    }
}
```

### 6.3 短信 (SMS)

```java
@Component
public class SmsChannel implements NotificationChannel {
    
    @Autowired
    private SmsProvider smsProvider;
    
    @Override
    public void send(Notification notification) {
        // 获取用户手机号
        String phone = getUserPhone(notification.getUserId());
        
        // 限流检查
        if (!rateLimiter.allow(phone)) {
            log.warn("SMS rate limit exceeded for user: {}", notification.getUserId());
            return;
        }
        
        SmsMessage message = SmsMessage.builder()
            .phone(phone)
            .templateCode(notification.getTemplateId())
            .params(notification.getTemplateParams())
            .build();
            
        smsProvider.send(message);
    }
}
```

## 7. 限流与去重

### 7.1 限流策略

```java
@Component
public class NotificationRateLimiter {
    
    @Autowired
    private RedisTemplate<String, String> redis;
    
    // 短信限流：每个手机号每小时最多5条
    public boolean allowSms(String phone) {
        String key = "sms:limit:" + phone + ":" + LocalDateTime.now().getHour();
        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, 1, TimeUnit.HOURS);
        }
        return count <= 5;
    }
    
    // Push限流：每个设备每分钟最多10条
    public boolean allowPush(String deviceToken) {
        String key = "push:limit:" + deviceToken + ":" + (System.currentTimeMillis() / 60000);
        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, 1, TimeUnit.MINUTES);
        }
        return count <= 10;
    }
}
```

### 7.2 去重策略

```java
@Component
public class NotificationDeduplicator {
    
    @Autowired
    private RedisTemplate<String, String> redis;
    
    // 5分钟内相同类型的通知去重
    public boolean isDuplicate(Notification notification) {
        String key = String.format("notify:dup:%d:%s:%s",
            notification.getUserId(),
            notification.getType(),
            notification.getBusinessId());
            
        Boolean exists = redis.hasKey(key);
        if (!exists) {
            redis.opsForValue().set(key, "1", 5, TimeUnit.MINUTES);
        }
        return exists;
    }
}
```

## 8. 数据库设计

```sql
-- 通知记录表
CREATE TABLE t_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    type VARCHAR(32) NOT NULL COMMENT '通知类型',
    template_id VARCHAR(16) COMMENT '模板ID',
    title VARCHAR(256) NOT NULL COMMENT '标题',
    content TEXT COMMENT '内容',
    channels VARCHAR(128) COMMENT '发送渠道',
    status TINYINT DEFAULT 0 COMMENT '状态: 0待发送 1已发送 2发送失败',
    read_status TINYINT DEFAULT 0 COMMENT '阅读状态: 0未读 1已读',
    business_id VARCHAR(64) COMMENT '业务ID(用于去重)',
    extra_data JSON COMMENT '扩展数据',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP COMMENT '发送时间',
    read_at TIMESTAMP COMMENT '阅读时间',
    KEY idx_user_id (user_id),
    KEY idx_type (type),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='通知记录表';

-- 用户通知设置表
CREATE TABLE t_notification_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    channel VARCHAR(32) NOT NULL COMMENT '渠道',
    notification_type VARCHAR(32) NOT NULL COMMENT '通知类型',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_channel_type (user_id, channel, notification_type)
) ENGINE=InnoDB COMMENT='用户通知设置表';

-- 通知模板表
CREATE TABLE t_notification_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id VARCHAR(16) NOT NULL COMMENT '模板ID',
    name VARCHAR(64) NOT NULL COMMENT '模板名称',
    type VARCHAR(32) NOT NULL COMMENT '通知类型',
    channel VARCHAR(32) NOT NULL COMMENT '渠道',
    title_template VARCHAR(256) COMMENT '标题模板',
    content_template TEXT COMMENT '内容模板',
    variables JSON COMMENT '变量列表',
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_id (template_id)
) ENGINE=InnoDB COMMENT='通知模板表';
```

## 9. 配置参数

```yaml
notification:
  service:
    port: 8097
    
  channels:
    in-app:
      enabled: true
    push:
      enabled: true
      provider: firebase  # firebase, apns, jpush
    sms:
      enabled: true
      provider: twilio    # twilio, aliyun
      rate-limit: 5/hour  # 每手机号每小时限制
    email:
      enabled: true
      provider: sendgrid  # sendgrid, aws-ses
      
  deduplication:
    enabled: true
    window-minutes: 5
    
  retry:
    max-attempts: 3
    backoff-seconds: 60
    
  kafka:
    topics:
      - order-state-topic
      - risk-alert-topic
      - funding-settlement
      - account-security-topic
```

---

*文档版本: v1.0*  
*更新日期: 2026-02-18*
