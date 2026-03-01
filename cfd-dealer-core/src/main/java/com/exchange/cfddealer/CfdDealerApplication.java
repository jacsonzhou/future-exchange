package com.exchange.cfddealer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.exchange.cfddealer", "com.exchange.common"})
@EnableDiscoveryClient
@EnableKafka
@EnableScheduling
@MapperScan("com.exchange.cfddealer.mapper")
public class CfdDealerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CfdDealerApplication.class, args);
    }
}
