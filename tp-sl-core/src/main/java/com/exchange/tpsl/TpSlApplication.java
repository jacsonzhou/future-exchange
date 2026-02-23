package com.exchange.tpsl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 止盈止损服务启动类
 * 
 * 端口: 8089
 * 职责:
 * - TP/SL订单管理
 * - 价格监控与触发
 * - 移动止损计算
 * - 订单生命周期管理
 */
@SpringBootApplication
public class TpSlApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TpSlApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8089");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           止盈止损服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
