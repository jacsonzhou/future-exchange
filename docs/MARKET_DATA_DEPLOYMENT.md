# 📦 行情服务部署指南

> 版本：v2.0  
> 最后更新：2026-02-18

---

## 一、环境准备

### 1.1 硬件要求

| 组件 | 最低配置 | 推荐配置 |
|------|---------|---------|
| CPU | 4 cores | 8+ cores |
| 内存 | 8GB | 16GB+ |
| 磁盘 | 50GB SSD | 200GB+ NVMe |
| 网络 | 1Gbps | 10Gbps |

### 1.2 软件依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | OpenJDK或Oracle JDK |
| MySQL | 8.0+ | 行情数据持久化 |
| Redis | 6.0+ | 缓存和Pub/Sub |
| Kafka | 3.x+ | 消息总线 |
| Maven | 3.8+ | 构建工具 |

---

## 二、数据库初始化

### 2.1 创建数据库

```bash
mysql -u root -p

CREATE DATABASE exchange_market 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;
```

### 2.2 执行初始化脚本

```bash
cd /path/to/market-price-core
mysql -u root -p exchange_market < src/main/resources/db/schema.sql
```

---

## 三、Kafka Topic初始化

```bash
# 创建成交事件Topic（多分区）
kafka-topics.sh --create \
    --topic match-event-topic \
    --partitions 10 \
    --replication-factor 3 \
    --bootstrap-server localhost:9092

# 创建深度事件Topic（每个symbol独立）
# 注意：实际生产环境中，深度事件通常是动态Topic
# 这里创建示例Topic
kafka-topics.sh --create \
    --topic orderbook-delta-BTCUSDT \
    --partitions 1 \
    --replication-factor 3 \
    --bootstrap-server localhost:9092

# 创建指数价格Topic
kafka-topics.sh --create \
    --topic index-price-topic \
    --partitions 3 \
    --replication-factor 3 \
    --bootstrap-server localhost:9092

# 验证
kafka-topics.sh --list --bootstrap-server localhost:9092
```

---

## 四、应用配置

### 4.1 配置文件

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_market?useSSL=false&serverTimezone=UTC
    username: root
    password: your_password
  
  redis:
    host: localhost
    port: 6379
    password: your_password
  
  kafka:
    bootstrap-servers: localhost:9092
```

### 4.2 环境变量

```bash
# JVM参数
export JAVA_OPTS="-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 应用配置
export MYSQL_PASSWORD=your_password
export REDIS_PASSWORD=your_password
export KAFKA_SERVERS=localhost:9092
```

---

## 五、构建部署

### 5.1 本地构建

```bash
cd /path/to/future-exchange

# 编译打包（跳过测试）
mvn clean package -DskipTests -pl market-price-core -am

# 生成的jar包位置
# market-price-core/target/market-price-core-1.0-SNAPSHOT.jar
```

### 5.2 启动服务

```bash
cd market-price-core/target

# 启动服务
java $JAVA_OPTS -jar market-price-core-1.0-SNAPSHOT.jar

# 或使用配置文件
java $JAVA_OPTS \
    -Dspring.config.location=file:./application.yml \
    -jar market-price-core-1.0-SNAPSHOT.jar
```

### 5.3 Docker部署

```dockerfile
# Dockerfile
FROM openjdk:17-jdk-slim

WORKDIR /app

COPY target/market-price-core-1.0-SNAPSHOT.jar app.jar

EXPOSE 8095

ENV JAVA_OPTS="-Xms4g -Xmx4g -XX:+UseG1GC"

ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
```

```bash
# 构建镜像
docker build -t exchange/market-price-service:2.0 .

# 运行容器
docker run -d \
    --name market-price-service \
    -p 8095:8095 \
    -e MYSQL_PASSWORD=your_password \
    -e REDIS_PASSWORD=your_password \
    -e KAFKA_SERVERS=kafka:9092 \
    exchange/market-price-service:2.0
```

---

## 六、Kubernetes部署

### 6.1 ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: market-price-config
  namespace: exchange
data:
  application.yml: |
    server:
      port: 8095
    spring:
      datasource:
        url: jdbc:mysql://mysql-service:3306/exchange_market?useSSL=false
        username: root
        password: ${MYSQL_PASSWORD}
      redis:
        host: redis-service
        port: 6379
      kafka:
        bootstrap-servers: kafka-service:9092
```

### 6.2 Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: market-price-service
  namespace: exchange
spec:
  replicas: 3
  selector:
    matchLabels:
      app: market-price-service
  template:
    metadata:
      labels:
        app: market-price-service
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values:
                        - market-price-service
                topologyKey: kubernetes.io/hostname
      containers:
        - name: market-price
          image: exchange/market-price-service:2.0
          ports:
            - containerPort: 8095
          resources:
            requests:
              memory: "4Gi"
              cpu: "2"
            limits:
              memory: "8Gi"
              cpu: "4"
          env:
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: mysql-secret
                  key: password
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: redis-secret
                  key: password
            - name: JAVA_OPTS
              value: "-Xms4g -Xmx4g -XX:+UseG1GC"
          volumeMounts:
            - name: config
              mountPath: /app/config
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: 8095
            initialDelaySeconds: 60
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: 8095
            initialDelaySeconds: 30
            periodSeconds: 5
      volumes:
        - name: config
          configMap:
            name: market-price-config
```

### 6.3 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: market-price-service
  namespace: exchange
spec:
  selector:
    app: market-price-service
  ports:
    - name: http
      port: 8095
      targetPort: 8095
  type: ClusterIP
```

### 6.4 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: market-price-service-hpa
  namespace: exchange
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: market-price-service
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---

## 七、验证测试

### 7.1 健康检查

```bash
# 健康检查
curl http://localhost:8095/api/v1/health

# 预期响应：
# {"code":0,"msg":"success","data":"OK","timestamp":1712345678123}
```

### 7.2 深度查询

```bash
# 获取深度
curl "http://localhost:8095/api/v1/depth?symbol=BTCUSDT&limit=10"

# 预期响应：
# {
#   "code": 0,
#   "data": {
#     "symbol": "BTCUSDT",
#     "lastUpdateId": 123456,
#     "bids": [["50000", "1.5"], ["49999", "2.0"]],
#     "asks": [["50001", "0.8"], ["50002", "1.2"]]
#   }
# }
```

### 7.3 K线查询

```bash
# 获取K线
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"
```

### 7.4 WebSocket测试

```bash
# 使用wscat测试
npm install -g wscat

# 连接WebSocket
wscat -c ws://localhost:8095/ws/market

# 订阅深度
> {"method": "SUBSCRIBE", "params": ["depth.BTCUSDT"], "id": 1}

# 订阅成交
> {"method": "SUBSCRIBE", "params": ["trade.BTCUSDT"], "id": 2}

# 心跳
> {"ping": 1712345678123}
```

---

## 八、常见问题

### Q1: 服务启动报错 "Cannot connect to Kafka"

**解决方法**：
```bash
# 检查Kafka是否运行
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# 检查Topic是否存在
kafka-topics.sh --list --bootstrap-server localhost:9092
```

### Q2: WebSocket连接数限制

**解决方法**：
```bash
# 修改系统参数
ulimit -n 65535  # 文件描述符限制

# 修改JVM参数
-Dserver.tomcat.max-connections=100000
```

### Q3: 内存不足

**解决方法**：
```bash
# 增加JVM堆内存
export JAVA_OPTS="-Xms8g -Xmx8g"

# 或优化缓存配置
market-price:
  kline:
    cache-size: 500  # 减少缓存数量
```

---

## 九、监控配置

### 9.1 Prometheus指标

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

### 9.2 Grafana Dashboard

导入 Dashboard ID: `1860`（Node Exporter）和自定义行情服务Dashboard。

---

## 十、联系支持

如有问题，请联系：
- 技术支持：core-team@exchange.com
- 紧急热线：+86 xxx-xxxx-xxxx

---

*本文档适用于行情服务 v2.0 版本。*
