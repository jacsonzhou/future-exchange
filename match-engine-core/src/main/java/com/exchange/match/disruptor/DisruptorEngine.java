package com.exchange.match.disruptor;

import com.exchange.match.event.MatchEvent;
import com.exchange.match.processor.MatchingProcessor;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Disruptor引擎（核心并发模型）
 * 
 * 设计要点：
 * 1. 单线程消费（无锁）
 * 2. RingBuffer高性能队列
 * 3. 顺序确定（可重放）
 * 4. Cache Friendly
 * 5. 低GC
 */
@Slf4j
@Component
public class DisruptorEngine {
    
    @Autowired
    private MatchingProcessor matchingProcessor;
    
    /**
     * Disruptor实例
     */
    private Disruptor<MatchEvent> disruptor;
    
    /**
     * RingBuffer
     */
    private RingBuffer<MatchEvent> ringBuffer;
    
    /**
     * RingBuffer大小（必须是2的幂）
     */
    private static final int RING_BUFFER_SIZE = 1024 * 64;
    
    @PostConstruct
    public void init() {
        log.info("[DisruptorEngine] Initializing...");
        
        // 创建线程工厂
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        
        // 创建Disruptor
        disruptor = new Disruptor<>(
            MatchEvent::new,
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE, // 单生产者（Kafka Consumer是单线程）
            new BlockingWaitStrategy() // 等待策略
        );
        
        // 设置事件处理器
        disruptor.handleEventsWith(matchingProcessor);
        
        // 设置异常处理器
        disruptor.setDefaultExceptionHandler(new MatchExceptionHandler());
        
        // 启动Disruptor
        ringBuffer = disruptor.start();
        
        log.info("[DisruptorEngine] Started successfully, ringBufferSize={}", RING_BUFFER_SIZE);
    }
    
    /**
     * 提交订单事件
     */
    public void submitOrderCommand(com.exchange.match.event.OrderCommand command) {
        ringBuffer.publishEvent((event, sequence) -> {
            event.setOrderCommand(command);
            event.setSequence(sequence);
        });
    }
    
    /**
     * 获取RingBuffer剩余容量
     */
    public long getRemainingCapacity() {
        return ringBuffer.remainingCapacity();
    }
    
    /**
     * 获取RingBuffer大小
     */
    public long getBufferSize() {
        return ringBuffer.getBufferSize();
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("[DisruptorEngine] Shutting down...");
        if (disruptor != null) {
            disruptor.shutdown();
        }
        log.info("[DisruptorEngine] Shutdown completed");
    }
    
    /**
     * 异常处理器
     */
    private static class MatchExceptionHandler implements com.lmax.disruptor.ExceptionHandler<MatchEvent> {
        
        @Override
        public void handleEventException(Throwable ex, long sequence, MatchEvent event) {
            log.error("[DisruptorEngine] Event processing error, seq={}, orderId={}", 
                sequence, 
                event.getOrderCommand() != null ? event.getOrderCommand().getOrderId() : null, 
                ex);
        }
        
        @Override
        public void handleOnStartException(Throwable ex) {
            log.error("[DisruptorEngine] Start error", ex);
        }
        
        @Override
        public void handleOnShutdownException(Throwable ex) {
            log.error("[DisruptorEngine] Shutdown error", ex);
        }
    }
}



