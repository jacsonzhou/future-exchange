package com.exchange.match.disruptor;

import com.exchange.match.event.OrderCommand;
import com.exchange.match.event.OrderCommandEvent;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Disruptor 撮合引擎
 * 
 * 特点：
 * 1. 使用 RingBuffer 实现无锁队列
 * 2. 单线程处理保证顺序性
 * 3. 高性能事件驱动
 */
@Slf4j
@Component
public class MatchDisruptorEngine {
    
    /**
     * RingBuffer大小（必须是2的幂）
     */
    private static final int BUFFER_SIZE = 1024 * 1024;
    
    @Autowired
    private MatchEventHandler matchEventHandler;
    
    private Disruptor<OrderCommandEvent> disruptor;
    private RingBuffer<OrderCommandEvent> ringBuffer;
    
    @PostConstruct
    public void start() {
        log.info("Starting Disruptor match engine...");
        
        // 创建Disruptor
        disruptor = new Disruptor<>(
            OrderCommandEvent::new,
            BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE
        );
        
        // 设置事件处理器
        disruptor.handleEventsWith(matchEventHandler);
        
        // 启动
        ringBuffer = disruptor.start();
        
        log.info("Disruptor match engine started. Buffer size: {}", BUFFER_SIZE);
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Disruptor match engine...");
        if (disruptor != null) {
            disruptor.shutdown();
        }
        log.info("Disruptor match engine stopped.");
    }
    
    /**
     * 提交订单命令
     */
    public void submit(OrderCommand command) {
        if (ringBuffer == null) {
            throw new IllegalStateException("Disruptor not started");
        }
        
        // 获取下一个序列号
        long sequence = ringBuffer.next();
        
        try {
            // 获取事件对象
            OrderCommandEvent event = ringBuffer.get(sequence);
            
            // 设置数据
            event.setCommand(command);
            
        } finally {
            // 发布事件
            ringBuffer.publish(sequence);
        }
        
        log.debug("Order command submitted: orderId={}, seq={}", 
            command.getOrderId(), sequence);
    }
    
    /**
     * 获取当前序列号（用于监控）
     */
    public long getCurrentSequence() {
        return ringBuffer != null ? ringBuffer.getCursor() : -1;
    }
}

