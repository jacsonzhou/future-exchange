package com.exchange.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Hard Risk Gate 启动类
 * 
 * 功能：
 * 1. 同步硬风控检查
 * 2. 保证金校验
 * 3. 杠杆限制
 * 4. 仓位限制
 * 5. ReduceOnly规则
 * 6. 价格有效性
 * 7. 黑白名单
 * 8. 账户状态检查
 */
@SpringBootApplication
@EnableDiscoveryClient
public class HardRiskApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HardRiskApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8082");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           硬风控服务启动成功                            ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
