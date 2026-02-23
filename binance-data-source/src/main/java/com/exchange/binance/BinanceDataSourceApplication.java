package com.exchange.binance;

import com.exchange.binance.config.BinanceDataSourceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 币安数据源服务启动类
 * 
 * 端口: 8099
 * 
 * 功能：
 * - 连接币安WebSocket实时行情
 * - 接收深度数据（OrderBook L2）
 * - 接收实时成交数据
 * - 发布到Kafka供内部系统消费
 */
@SpringBootApplication(scanBasePackages = {
        "com.exchange.binance",
        "com.exchange.common.core"  // 复用common-core中的组件
})
@EnableConfigurationProperties(BinanceDataSourceConfig.class)
@EnableAsync
@EnableScheduling
public class BinanceDataSourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BinanceDataSourceApplication.class, args);
    }
}
