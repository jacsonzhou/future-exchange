package com.exchange.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 用户服务启动类
 * 
 * 职责：
 * 1. 用户注册/登录
 * 2. 自动初始化交易账户
 * 3. 自动注入初始资金
 * 
 * 端口：8099
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.user", "com.exchange.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.exchange.user.service.client", "com.exchange.user.controller"})
@MapperScan("com.exchange.user.mapper")
public class UserCoreApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(UserCoreApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8099");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           用户核心服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
