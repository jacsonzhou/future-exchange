package com.exchange.market.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * ClickHouse 配置类
 * 
 * 用于 K 线数据存储
 */
@Slf4j
@Configuration
public class ClickHouseConfig {

    @Value("${clickhouse.url:jdbc:clickhouse://localhost:8123/exchange_kline}")
    private String url;

    @Value("${clickhouse.user:default}")
    private String user;

    @Value("${clickhouse.password:clickhouse123456}")
    private String password;

    @Value("${clickhouse.socket-timeout:30000}")
    private int socketTimeout;

    @Bean
    public DataSource clickHouseDataSource() throws Exception {
        log.info("[ClickHouseConfig] Initializing ClickHouse DataSource, url={}", url);
        
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("socket_timeout", String.valueOf(socketTimeout));
        // 压缩设置
        properties.setProperty("compress", "true");
        // 使用 HTTP 接口
        properties.setProperty("ssl", "false");
        
        ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

        // 启动阶段仅做健康探测，不因 ClickHouse 短暂不可用阻断行情服务启动
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            if (rs.next()) {
                log.info("[ClickHouseConfig] ClickHouse connection test passed");
            }
        } catch (Exception e) {
            log.warn("[ClickHouseConfig] ClickHouse probe failed, service will run in degraded mode. reason={}",
                    e.getMessage());
        }
        
        return dataSource;
    }
}
