package com.exchange.index;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 指数价格服务启动类
 * 
 * 职责：
 * - 从多个外部交易所采集现货价格
 * - 计算加权平均指数价格
 * - 发布指数价格更新事件
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.index", "com.exchange.common"})
@EnableScheduling
public class IndexPriceApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(IndexPriceApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8087");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           指数价格服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
