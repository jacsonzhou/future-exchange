package com.exchange.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Agent 训练服务启动类。
 */
@SpringBootApplication(scanBasePackages = {"com.exchange.agent", "com.exchange.common"})
@EnableDiscoveryClient
public class AgentTrainingApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AgentTrainingApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8107");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║        Agent Training 服务启动成功                     ║");
        System.out.println("║        端口: " + port + "                                       ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
