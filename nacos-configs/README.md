# Nacos 配置中心文件

本目录包含交易所核心系统所有微服务的 Nacos 外部化配置文件。

## 目录结构

```
nacos-configs/
├── README.md                        # 本文件
├── shared-dev.yml                   # 公共配置（dev环境）
├── api-gateway-dev.yml              # API网关 (8080)
├── oms-core-dev.yml                 # 订单管理系统 (8081)
├── match-engine-core-dev.yml        # 撮合引擎 (8083)
├── ledger-core-dev.yml              # 账本服务 (8084)
├── hard-risk-core-dev.yml           # 硬风控服务 (8082)
├── snapshot-account-core-dev.yml    # 账户快照服务 (8085)
├── position-snapshot-core-dev.yml   # 持仓快照服务 (8086)
├── market-price-service-dev.yml     # 行情生成服务 (8095)
├── public-push-service-dev.yml      # 公有推送服务 (8096)
├── private-push-core-dev.yml        # 私有推送服务 (8099)
├── user-core-dev.yml                # 用户服务 (8099)
├── liquidation-core-dev.yml         # 强平服务 (8088)
├── adl-core-dev.yml                 # 自动减仓服务 (8091)
├── funding-rate-core-dev.yml        # 资金费率服务 (8088)
├── index-price-service-dev.yml      # 指数价格服务 (8093)
├── mark-price-service-dev.yml       # 标记价格服务 (8094)
├── tp-sl-core-dev.yml               # 止盈止损服务 (8089)
├── margin-mode-core-dev.yml         # 保证金模式服务 (8090)
├── market-maker-core-dev.yml        # 做市商服务 (8092)
├── replay-core-dev.yml              # 重放服务 (8087)
└── binance-data-source-dev.yml      # 币安数据源服务 (8099)
```

## 配置说明

### 1. 公共配置 (shared-dev.yml)

包含所有服务共享的配置项：
- Nacos 服务发现与配置中心
- Redis 连接池配置
- Kafka 公共配置
- MyBatis Plus 公共配置
- 监控与链路追踪配置
- 日志公共配置
- Druid 连接池公共配置

### 2. 服务特定配置

每个服务的配置文件包含：
- Server 端口
- 应用名称
- 数据源配置
- Kafka 消费者/生产者特定配置
- 服务业务配置
- 日志级别配置

## 使用方法

### 方式一：Nacos 控制台手动导入

1. 登录 Nacos 控制台 (http://localhost:8848/nacos)
2. 创建命名空间 `exchange`（如果不存在）
3. 进入 配置管理 -> 配置列表
4. 选择 `exchange` 命名空间
5. 点击 "导入配置"，选择本目录下的 YAML 文件

### 方式二：Nacos API 导入

```bash
# 导入共享配置
curl -X POST 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=shared-dev.yml' \
  -d 'group=DEFAULT_GROUP' \
  -d 'namespaceId=exchange' \
  -d 'content='"$(cat shared-dev.yml)"

# 导入服务配置（以 oms-core 为例）
curl -X POST 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=oms-core-dev.yml' \
  -d 'group=DEFAULT_GROUP' \
  -d 'namespaceId=dev' \
  -d 'content='"$(cat oms-core-dev.yml)"
```

### 方式三：使用 Nacos 配置导入脚本

```bash
# 创建导入脚本
./import-to-nacos.sh
```

## 服务端口对照表

| 服务 | 端口 | Data ID |
|------|------|---------|
| api-gateway | 8080 | api-gateway-dev.yml |
| oms-core | 8081 | oms-core-dev.yml |
| hard-risk-core | 8082 | hard-risk-core-dev.yml |
| match-engine-core | 8083 | match-engine-core-dev.yml |
| ledger-core | 8084 | ledger-core-dev.yml |
| snapshot-account-core | 8085 | snapshot-account-core-dev.yml |
| position-snapshot-core | 8086 | position-snapshot-core-dev.yml |
| replay-core | 8087 | replay-core-dev.yml |
| liquidation-core | 8088 | liquidation-core-dev.yml |
| tp-sl-core | 8089 | tp-sl-core-dev.yml |
| margin-mode-core | 8090 | margin-mode-core-dev.yml |
| adl-core | 8091 | adl-core-dev.yml |
| market-maker-core | 8092 | market-maker-core-dev.yml |
| index-price-core | 8093 | index-price-service-dev.yml |
| mark-price-core | 8094 | mark-price-service-dev.yml |
| market-price-core | 8095 | market-price-service-dev.yml |
| public-push-core | 8096 | public-push-service-dev.yml |
| user-core | 8099 | user-core-dev.yml |
| private-push-core | 8099 | private-push-core-dev.yml |
| binance-data-source | 8099 | binance-data-source-dev.yml |

> **注意**：`market-price-service`、`public-push-service`、`index-price-service`、`mark-price-service` 的 Data ID 与服务名保持一致，而非模块名。

## 配置优先级

Nacos 配置优先级高于本地 `application.yml`：

1. Nacos 配置 (最高优先级)
2. 本地 `application-{profile}.yml`
3. 本地 `application.yml` (最低优先级)

## 配置热更新

Nacos 支持配置热更新，修改配置后无需重启服务即可生效。

需要在代码中使用 `@RefreshScope` 注解：

```java
@RestController
@RefreshScope
public class ConfigController {
    
    @Value("${exchange.oms.submit-mode:kafka}")
    private String submitMode;
    
    @GetMapping("/config")
    public String getConfig() {
        return submitMode;
    }
}
```

## 环境区分

- `shared-dev.yml` - 开发环境公共配置
- `shared-test.yml` - 测试环境公共配置
- `shared-prod.yml` - 生产环境公共配置

同理，每个服务的配置文件也按照环境区分：
- `{service}-dev.yml` - 开发环境
- `{service}-test.yml` - 测试环境
- `{service}-prod.yml` - 生产环境

## 注意事项

1. **敏感信息**：密码等敏感信息使用 `${}` 占位符，通过环境变量注入
2. **数据库密码**：生产环境必须修改默认密码
3. **Kafka acks**：金融级别服务（ledger）使用 `acks=all`，其他服务可使用 `acks=1`
4. **连接池**：根据实际并发调整连接池大小
5. **日志级别**：生产环境建议将 DEBUG 改为 INFO
