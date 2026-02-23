# 监控与链路追踪配置指南

> 本文档说明如何配置 Prometheus + Grafana 监控和 Spring Cloud Sleuth + Zipkin 链路追踪。

## 1. Prometheus + Grafana 监控

### 1.1 启用监控端点

所有服务已配置 Actuator 和 Prometheus 端点，默认暴露以下端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
    metrics:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

### 1.2 访问监控端点

| 端点 | 用途 | 示例 |
|------|------|------|
| `/actuator/health` | 健康检查 | http://localhost:8080/actuator/health |
| `/actuator/info` | 服务信息 | http://localhost:8080/actuator/info |
| `/actuator/metrics` | 指标列表 | http://localhost:8080/actuator/metrics |
| `/actuator/prometheus` | Prometheus格式指标 | http://localhost:8080/actuator/prometheus |

### 1.3 关键监控指标

#### JVM指标
- `jvm_memory_used_bytes` - JVM内存使用
- `jvm_gc_pause_seconds` - GC暂停时间
- `jvm_threads_live` - 活跃线程数

#### Kafka指标
- `kafka_consumer_records_consumed_total` - 消费消息总数
- `kafka_consumer_records_lag_max` - 消费延迟
- `kafka_producer_record_send_total` - 发送消息总数

#### 业务指标
- `http_server_requests_seconds_count` - HTTP请求数
- `http_server_requests_seconds_sum` - HTTP请求耗时
- `order_created_total` - 订单创建数（自定义）
- `trade_executed_total` - 成交数（自定义）

### 1.4 Prometheus 配置示例

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'exchange-services'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
        - 'localhost:8080'  # api-gateway
        - 'localhost:8081'  # oms-core
        - 'localhost:8082'  # hard-risk-core
        - 'localhost:8083'  # match-engine-core
        - 'localhost:8084'  # ledger-core
        - 'localhost:8085'  # snapshot-account-core
        - 'localhost:8086'  # position-snapshot-core
        - 'localhost:8088'  # liquidation-core
        - 'localhost:8090'  # margin-mode-core
        - 'localhost:8095'  # market-price-core
        - 'localhost:8096'  # public-push-core
```

### 1.5 Grafana 大盘配置

导入以下 Dashboard JSON：

- **JVM监控**: Grafana ID 4701
- **Spring Boot监控**: Grafana ID 10280
- **Kafka监控**: Grafana ID 7589

## 2. Zipkin 链路追踪

### 2.1 启用链路追踪

所有服务已配置 Micrometer Tracing + Zipkin：

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 采样率：1.0=100%，生产环境建议0.1
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### 2.2 访问 Zipkin UI

Zipkin UI: http://localhost:9411

### 2.3 日志格式

配置后，日志将自动包含 traceId 和 spanId：

```
2024-02-19 15:30:45.123 [traceId=abc123,spanId=def456] [http-nio-8081-exec-1] INFO c.e.o.OrderController - Order created: 12345
```

### 2.4 日志配置示例

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId}] [%X{spanId}] [%thread] %-5level %logger{36} - %msg%n"
```

## 3. 告警规则

### 3.1 Prometheus AlertManager 规则

```yaml
# alert-rules.yml
groups:
  - name: exchange-alerts
    rules:
      # 服务宕机告警
      - alert: ServiceDown
        expr: up{job="exchange-services"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Service {{ $labels.instance }} is down"
          
      # Kafka消费延迟告警
      - alert: KafkaConsumerLag
        expr: kafka_consumer_records_lag_max > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka consumer lag is high"
          
      # JVM内存告警
      - alert: JvmMemoryHigh
        expr: jvm_memory_used_bytes / jvm_memory_max_bytes > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "JVM memory usage is high"
          
      # 撮合延迟告警
      - alert: MatchLatencyHigh
        expr: histogram_quantile(0.99, rate(match_engine_latency_seconds_bucket[5m])) > 0.001
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Match engine latency is high"
```

## 4. 快速开始

### 4.1 启动 Prometheus

```bash
docker run -d \
  --name prometheus \
  -p 9090:9090 \
  -v $(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml \
  prom/prometheus
```

### 4.2 启动 Grafana

```bash
docker run -d \
  --name grafana \
  -p 3000:3000 \
  -e GF_SECURITY_ADMIN_PASSWORD=admin \
  grafana/grafana
```

### 4.3 启动 Zipkin

```bash
docker run -d \
  --name zipkin \
  -p 9411:9411 \
  openzipkin/zipkin
```

### 4.4 配置 Grafana 数据源

1. 访问 http://localhost:3000
2. 登录：admin / admin
3. 添加数据源：Prometheus
4. URL: http://prometheus:9090

## 5. 参考文档

- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Prometheus](https://micrometer.io/docs/registry/prometheus)
- [Zipkin](https://zipkin.io/)
