# 修复记录 - Market Price Nacos 注册

- 迭代: 3+
- 修复时间: 2026-02-20T12:05:00Z
- 问题: market-price-core 未注册到 Nacos

## 根因分析
market-price-core/pom.xml 中缺少 Nacos Discovery 依赖：
- 只有 `spring-cloud-starter-alibaba-nacos-config` (配置中心)
- 缺少 `spring-cloud-starter-alibaba-nacos-discovery` (服务发现)

## 修复内容

### market-price-core/pom.xml
添加依赖:
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

## 验证日志
```
[REGISTER-SERVICE] public registering service market-price-service
nacos registry, DEFAULT_GROUP market-price-service 192.168.1.5:8095 register finished
```

## 后续建议
检查其他服务是否也有同样问题：
- position-snapshot-core (已检查: 有 discovery)
- 其他核心服务
