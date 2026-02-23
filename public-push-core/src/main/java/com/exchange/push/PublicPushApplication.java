package com.exchange.push;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 公有推送系统启动类
 * 
 * 职责：
 * - 消费行情数据（来自Market Data Engine）
 * - WebSocket连接管理
 * - 订阅管理
 * - 数据广播推送
 * 
 * 端口：8096
 * 
 * 架构特点：
 * - 无状态设计，可水平扩容
 * - 纯IO密集型，不做任何计算
 * - 批量聚合发送，降低网络开销
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.push", "com.exchange.common"})
@EnableScheduling
public class PublicPushApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PublicPushApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8096");
        System.out.println("""
            
            ╔═══════════════════════════════════════════════════════════════════╗
            ║                                                                   ║
            ║           公有行情推送服务启动成功                                 ║
            ║                                                                   ║
            ║           端口: %s                                               ║
            ║           WebSocket: ws://localhost:%s/ws/market                 ║
            ║                                                                   ║
            ╚═══════════════════════════════════════════════════════════════════╝
            """.formatted(port, port));
    }
}
