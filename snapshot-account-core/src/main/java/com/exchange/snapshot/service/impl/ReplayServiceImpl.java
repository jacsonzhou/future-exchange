package com.exchange.snapshot.service.impl;

import com.exchange.snapshot.dto.TradeEntryEvent;
import com.exchange.snapshot.service.AccountSnapshotService;
import com.exchange.snapshot.service.ReplayService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Replay Service 实现（灾备核心）
 * 
 * 🔥 核心流程：
 * 1. 创建独立Kafka Consumer
 * 2. 从earliest开始消费
 * 3. 顺序重放到AccountSnapshotService
 * 4. 记录进度
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class ReplayServiceImpl implements ReplayService {
    
    @Autowired
    private AccountSnapshotService accountSnapshotService;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    private static final String KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String REPLAY_PROGRESS_KEY_PREFIX = "replay:progress:";
    
    /**
     * 按Symbol重放（异步）
     */
    @Override
    @Async
    public String replaySymbol(String symbol) {
        String replayId = "REPLAY_" + symbol + "_" + System.currentTimeMillis();
        
        log.info("[ReplayService] ========== Replay symbol start ==========");
        log.info("[ReplayService] replayId={}, symbol={}", replayId, symbol);
        
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        
        try {
            // 1. 创建Kafka Consumer（从头消费）
            KafkaConsumer<String, String> consumer = createReplayConsumer(replayId);
            
            // 2. 订阅Topic
            String topic = "trade-entry-" + symbol;
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(Collections.singletonList(partition));
            
            // 3. 从头开始
            consumer.seekToBeginning(Collections.singletonList(partition));
            
            log.info("[ReplayService] Start consuming from beginning, topic={}", topic);
            
            // 4. 消费并重放
            boolean running = true;
            
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                
                if (records.isEmpty()) {
                    log.info("[ReplayService] No more records, replay completed");
                    break;
                }
                
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        // 解析事件
                        TradeEntryEvent event = parseEvent(record.value());
                        
                        // 重放到AccountSnapshotService
                        accountSnapshotService.onTradeEntryEvent(event);
                        
                        processedCount++;
                        
                        // 更新进度
                        if (processedCount % 100 == 0) {
                            updateProgress(replayId, processedCount);
                            log.info("[ReplayService] Replay progress: {}", processedCount);
                        }
                        
                    } catch (Exception e) {
                        log.error("[ReplayService] ❌ Replay record error, offset={}", 
                            record.offset(), e);
                    }
                }
            }
            
            consumer.close();
            
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("[ReplayService] ========== Replay symbol completed ==========");
            log.info("[ReplayService] replayId={}, processed={}, duration={}ms",
                replayId, processedCount, duration);
            
            return replayId;
            
        } catch (Exception e) {
            log.error("[ReplayService] ❌ Replay symbol error", e);
            throw new RuntimeException("Replay failed", e);
        }
    }
    
    /**
     * 按时间区间重放
     */
    @Override
    @Async
    public String replayRange(String symbol, Long startTs, Long endTs) {
        // TODO: 实现按时间区间过滤
        return replaySymbol(symbol);
    }
    
    /**
     * 查询Replay进度
     */
    @Override
    public Integer getReplayProgress(String replayId) {
        if (redisTemplate != null) {
            Object progress = redisTemplate.opsForValue().get(REPLAY_PROGRESS_KEY_PREFIX + replayId);
            return progress != null ? (Integer) progress : 0;
        }
        return 0;
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 创建Replay专用Consumer
     */
    private KafkaConsumer<String, String> createReplayConsumer(String replayId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-" + replayId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        
        return new KafkaConsumer<>(props);
    }
    
    /**
     * 解析事件
     */
    private TradeEntryEvent parseEvent(String message) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = 
            new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.readValue(message, TradeEntryEvent.class);
    }
    
    /**
     * 更新进度
     */
    private void updateProgress(String replayId, int count) {
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(
                REPLAY_PROGRESS_KEY_PREFIX + replayId, 
                count, 
                1, 
                TimeUnit.HOURS
            );
        }
    }
}

