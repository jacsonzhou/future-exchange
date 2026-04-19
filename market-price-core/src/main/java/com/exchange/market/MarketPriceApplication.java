package com.exchange.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 行情数据服务启动类
 * 
 * 职责：
 * - K线数据计算与存储
 * - OrderBook深度聚合
 * - 24小时统计数据维护
 * - WebSocket实时推送
 * 
 * 端口：8095
 * 
 * @author Exchange Team
 * @version 2.0
 */
@SpringBootApplication(
        scanBasePackages = {"com.exchange.market", "com.exchange.common"},
        exclude = {DataSourceAutoConfiguration.class}
)
@EnableScheduling
@EnableFeignClients(basePackages = "com.exchange.market.client")
public class MarketPriceApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MarketPriceApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8095");
        System.out.println("""
            
            ╔═══════════════════════════════════════════════════════════════════╗
            ║                                                                   ║
            ║           行情数据服务启动成功                                     ║
            ║                                                                   ║
            ║           端口: %s                                               ║
            ║           API: http://localhost:%s/api/v1                        ║
            ║           WebSocket: ws://localhost:%s/ws/market                 ║
            ║                                                                   ║
            ╚═══════════════════════════════════════════════════════════════════╝
            """.formatted(port, port, port));
    }
}
