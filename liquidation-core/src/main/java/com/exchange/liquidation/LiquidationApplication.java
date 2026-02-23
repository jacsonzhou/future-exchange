package com.exchange.liquidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liquidation Service 启动类
 * 
 * 强制平仓服务 - 合约交易风险控制的最后一道防线
 * 
 * 核心职责：
 * 1. 消费强平触发事件
 * 2. 创建并执行强平订单
 * 3. 计算盈亏和穿仓损失
 * 4. 协调保险基金赔付
 * 5. 触发ADL减仓
 * 
 * 对标: Binance / OKX / Bybit 强平系统
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.liquidation", "com.exchange.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.exchange.liquidation.client")
@EnableKafka
@EnableScheduling
public class LiquidationApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(LiquidationApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8085");
        System.out.println("""
            
            ╔══════════════════════════════════════════════════════════════╗
            ║                                                              ║
            ║           强平服务启动成功                                    ║
            ║                                                              ║
            ║           端口: %s                                           ║
            ║           职责: 强制平仓 / 穿仓处理 / ADL触发                 ║
            ║                                                              ║
            ╚══════════════════════════════════════════════════════════════╝
            """.formatted(port));
    }
}
