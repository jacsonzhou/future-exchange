package com.exchange.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 任务调度配置
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
    
    /**
     * 异步任务线程池
     */
    @Bean(name = "replayExecutor")
    public Executor replayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("replay-");
        executor.initialize();
        return executor;
    }
}



