package com.exchange.privatepush;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 私有推送服务启动类
 * 
 * 🔥 核心职责：
 * 1. WebSocket 连接管理 (用户认证 + Session绑定)
 * 2. 私有数据实时推送 (订单/资金/持仓)
 * 3. ACK 确认机制
 * 4. 集群支持 (Redis Pub/Sub)
 * 
 * 端口: 8099
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.exchange.privatepush", "com.exchange.common"})
public class PrivatePushApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PrivatePushApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8097");
        log.info("╔════════════════════════════════════════════════════════╗");
        log.info("║           私有推送服务启动成功                          ║");
        log.info("║           端口: {}", port);
        log.info("╚════════════════════════════════════════════════════════╝");
    }
}
