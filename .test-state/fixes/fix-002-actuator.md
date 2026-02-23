# 修复记录 - 添加 Actuator 健康检查

- 迭代: 2
- 修复时间: 2026-02-20T09:50:00Z

## 问题
TC-MATCH-001 和 TC-LEDGER-001 失败，HTTP 404，缺少健康检查端点

## 修复内容

### 1. match-engine-core/pom.xml
添加依赖:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 2. match-engine-core/application.yml
添加配置:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  server:
    port: 8083
```

### 3. ledger-core/pom.xml
添加相同 actuator 依赖

### 4. ledger-core/application.yml
添加相同 actuator 配置，端口 8084

## 验证方式
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health
