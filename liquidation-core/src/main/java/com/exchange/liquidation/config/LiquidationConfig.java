package com.exchange.liquidation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 强平服务配置
 * 
 * 启用异步处理和定时任务
 */
@Configuration
@EnableAsync
@EnableScheduling
public class LiquidationConfig {
    
    /**
     * 异步任务线程池
     * 
     * 用于处理订单成交后的盈亏计算、保险基金交互等异步操作
     */
    @Bean(name = "liquidationTaskExecutor")
    public Executor liquidationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("liquidation-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}




