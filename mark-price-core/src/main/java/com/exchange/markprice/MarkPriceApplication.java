package com.exchange.markprice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 标记价格服务启动类
 * 
 * 职责：
 * - 基于指数价格和OrderBook计算标记价格
 * - 使用EMA平滑处理
 * - 发布标记价格更新事件
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.markprice", "com.exchange.common"})
@EnableScheduling
public class MarkPriceApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MarkPriceApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8086");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           标记价格服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
