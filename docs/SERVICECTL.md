# 服务统一启停脚本

脚本路径：`scripts/servicectl.sh`
一键全量重启入口：`./start_all_services.sh`（包含 `binance-data-source`）
一键交易所需服务入口：`./start_required_services.sh`（包含 `binance-data-source`）

## 设计目标

- 支持一条命令管理服务：`start | stop | restart | status | list`
- 支持：
  - 全量服务：`all`
  - 指定多个服务：空格分隔或逗号分隔
  - 单个服务
- **端口与服务名以 Nacos 配置为准（Source of Truth）**
  - 优先实时读取 Nacos 配置
  - Nacos 不可用时，自动回退 `nacos-configs/*.yml`
- 启动方式统一为低内存 `java -jar` 直启（不再使用 `mvn spring-boot:run`）
- 启动/重启前自动检测端口冲突（例如同端口服务不会误启动）
- 启动失败快速返回：启动进程提前退出时，不再等待满 `START_TIMEOUT_SEC`

## 用法

```bash
# 一键重启交易所需服务（推荐）
./start_required_services.sh

# 一键重启全部服务（含 binance-data-source）
./start_all_services.sh

# 查看全部服务状态
./scripts/servicectl.sh status all

# 重启全部服务
./scripts/servicectl.sh restart all

# 重启多个服务（空格写法）
./scripts/servicectl.sh restart oms-core api-gateway market-price-core

# 重启多个服务（逗号写法）
./scripts/servicectl.sh restart oms-core,api-gateway,market-price-core

# 启动/停止单个服务
./scripts/servicectl.sh start public-push-core
./scripts/servicectl.sh stop public-push-core

# 列出脚本支持的服务与端口解析来源
./scripts/servicectl.sh list
```

## 支持的服务名

- 模块名：`api-gateway`, `oms-core`, `match-engine-core` ...
- 别名（`spring.application.name`）：
  - `public-push-service` -> `public-push-core`
  - `index-price-service` -> `index-price-core`
  - `mark-price-service` -> `mark-price-core`
  - `market-price-service` -> `market-price-core`

## 环境变量

```bash
NACOS_ADDR=http://localhost:8848/nacos
NACOS_USERNAME=nacos
NACOS_PASSWORD=nacos
NACOS_GROUP=DEFAULT_GROUP
NACOS_NAMESPACE=
JAVA_CMD=java
JAVA_LOW_MEM_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
START_TIMEOUT_SEC=90
STOP_TIMEOUT_SEC=20
```

## 约定

- 脚本日志目录：`logs/servicectl/`（每个服务独立一个日志文件，如 `logs/servicectl/liquidation-core.log`）
- PID 文件目录：`logs/servicectl/pids/`
- 启动方式：模块目录执行 `java -jar target/{module}-*.jar`
- 脚本会按依赖顺序启动、逆序停止，减少“忘记启动”或“先后顺序错误”的情况
