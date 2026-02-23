# 构建问题修复记录

## 修复日期
2026-02-17

## 问题与修复

### 1. 父 POM 配置错误
**问题**: 父 POM artifactId 为 `exchange-core`，子模块引用为 `future-exchange`
**修复**: 统一为 `future-exchange`，并添加 `<relativePath>../pom.xml</relativePath>`

### 2. Protobuf 版本不匹配
**问题**: protoc 33.4 生成的代码需要 protobuf-java 4.33.x，但依赖为 3.25.1
**修复**: 更新 protobuf-java 依赖到 4.33.4

### 3. Protobuf 文件位置
**问题**: match-engine-core 和 common-proto 都有 protobuf 定义，导致类重复
**修复**: 将 proto 文件移到 common-proto 模块，统一生成

### 4. javax.annotation 迁移
**问题**: Spring Boot 3.x 使用 jakarta 命名空间
**修复**: 将 `javax.annotation` 改为 `jakarta.annotation`

### 5. Spring Kafka API 变更
**问题**: `ListenableFuture` 被 `CompletableFuture` 替代
**修复**: 更新 TradePublisher 使用 `CompletableFuture.whenComplete()`

### 6. KafkaHeaders 常量名变更
**问题**: `RECEIVED_PARTITION_ID` 和 `RECEIVED_MESSAGE_KEY` 已改名
**修复**: 改为 `RECEIVED_PARTITION` 和 `RECEIVED_KEY`

### 7. IdGenerator 静态方法问题
**问题**: `nextId()` 是静态方法但访问非静态成员
**修复**: 改为实例方法

### 8. OrderCommand 类型不一致
**问题**: 多处使用 `com.exchange.common.proto.event.OrderCommand` 和 `com.exchange.match.event.OrderCommand` 混用
**修复**: 统一使用内部 `com.exchange.match.event.OrderCommand`

### 9. 依赖重复声明
**问题**: common-core 在 match-engine-core pom.xml 中声明两次
**修复**: 移除重复的 test scope 依赖

## 成功构建命令
```bash
cd /Users/zhoufan/project/future-exchange
mvn clean install -pl common-core,common-proto,match-engine-core -DskipTests
```
