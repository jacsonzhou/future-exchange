package com.exchange.market.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * 
 * 行情服务使用异步处理：
 * - 数据发布（Redis Pub/Sub）
 * - K线计算
 * - WebSocket推送
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 行情数据任务执行器
     * 
     * 用于数据发布、计算等CPU密集型任务
     */
    @Bean("marketDataTaskExecutor")
    public Executor marketDataTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("market-data-");
        
        // 拒绝策略：由调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        log.info("[AsyncConfig] marketDataTaskExecutor initialized");
        return executor;
    }

    /**
     * WebSocket推送执行器
     * 
     * 用于WebSocket消息推送（IO密集型）
     */
    @Bean("webSocketTaskExecutor")
    public Executor webSocketTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(50000);
        executor.setThreadNamePrefix("websocket-");
        
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        log.info("[AsyncConfig] webSocketTaskExecutor initialized");
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return marketDataTaskExecutor();
    }
}
