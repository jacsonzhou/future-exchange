package com.exchange.snapshot.service.impl;

import com.exchange.snapshot.client.LedgerClient;
import com.exchange.snapshot.dto.LedgerEntryDto;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    
    @Autowired(required = false)
    private LedgerClient ledgerClient;
    
    private static final String REPLAY_PROGRESS_KEY_PREFIX = "replay:progress:";
    private static final int LEDGER_REPLAY_PAGE_SIZE = 5000;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;
    
    /**
     * 按Symbol重放
     */
    @Override
    public String replaySymbol(String symbol) {
        String replayId = "REPLAY_" + symbol + "_" + System.currentTimeMillis();
        
        log.info("[ReplayService] ========== Replay symbol start ==========");
        log.info("[ReplayService] replayId={}, symbol={}", replayId, symbol);
        
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        Set<Long> affectedUsers = new HashSet<>();
        
        try {
            // 1. 创建Kafka Consumer（从头消费）
            KafkaConsumer<String, String> consumer = createReplayConsumer(replayId);

            // 2. 按 topic 顺序重放（SYSTEM 兼容新旧通道）
            for (String topic : resolveReplayTopics(symbol)) {
                TopicPartition partition = new TopicPartition(topic, 0);
                consumer.assign(Collections.singletonList(partition));
                consumer.seekToBeginning(Collections.singletonList(partition));

                log.info("[ReplayService] Start consuming from beginning, topic={}", topic);

                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));

                    if (records.isEmpty()) {
                        log.info("[ReplayService] No more records for topic={}, continue next topic", topic);
                        break;
                    }

                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            // 解析事件
                            TradeEntryEvent event = parseEvent(record.value());

                            // 收集受影响用户（用于后续重建 unrealizedPnl）
                            if (event.getEntries() != null) {
                                for (TradeEntryEvent.LedgerEntry entry : event.getEntries()) {
                                    if (entry.getUserId() != null && entry.getUserId() > 0) {
                                        affectedUsers.add(entry.getUserId());
                                    }
                                }
                            }

                            // 重放到AccountSnapshotService
                            accountSnapshotService.onTradeEntryEvent(event);

                            processedCount++;

                            // 更新进度
                            if (processedCount % 100 == 0) {
                                updateProgress(replayId, processedCount);
                                log.info("[ReplayService] Replay progress: {}", processedCount);
                            }

                        } catch (Exception e) {
                            log.error("[ReplayService] ❌ Replay record error, topic={}, offset={}",
                                topic, record.offset(), e);
                        }
                    }
                }
            }
            
            consumer.close();
            
            // 3. 重建 unrealizedPnl（trade-entry 不包含估值数据）
            log.info("[ReplayService] Rebuilding unrealizedPnl for {} affected users", affectedUsers.size());
            int rebuildCount = 0;
            for (Long userId : affectedUsers) {
                try {
                    accountSnapshotService.rebuildUnrealizedPnl(userId);
                    rebuildCount++;
                } catch (Exception e) {
                    log.error("[ReplayService] Failed to rebuild unrealizedPnl for userId={}", userId, e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("[ReplayService] ========== Replay symbol completed ==========");
            log.info("[ReplayService] replayId={}, processed={}, affectedUsers={}, rebuiltUpnl={}, duration={}ms",
                replayId, processedCount, affectedUsers.size(), rebuildCount, duration);
            
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
    
    /**
     * 🔥 从 Ledger 数据库直接 Replay（绕过 Kafka retention 限制）
     *
     * 修复：当 Kafka trade-entry 消息 retention 到期后，Replay 无法读取历史事件。
     * 本方法通过 Feign Client 调用 ledger-core 分页查询 t_ledger_entry 表，
     * 按 ref_trade_id 聚合为 TradeEntryEvent 后重建 snapshot。
     *
     * 🔥 异步执行设计：
     * 1. 方法立即返回 replayId，实际重放在后台线程执行
     * 2. 通过 Redis 追踪任务状态和进度
     * 3. 调用方通过 GET /replay/progress/{replayId} 查询进度
     *
     * 性能设计：
     * 1. 分页查询，每页 5000 条 entries，避免内存溢出
     * 2. 按 ref_trade_id 聚合为 TradeEntryEvent，减少 snapshot 更新次数
     * 3. 批量重建 unrealizedPnl（从 position snapshot 镜像读取）
     * 4. 支持按 userId 过滤，大数据量时可做增量恢复
     */
    @Override
    public String replayFromLedgerDb(String symbol, Long userId, Long startBizSeq, Long endBizSeq) {
        String replayId = "LEDGER_REPLAY_" + symbol + "_" + System.currentTimeMillis();
        log.info("[ReplayService] ========== Replay from Ledger DB start (async) ==========");
        log.info("[ReplayService] replayId={}, symbol={}, userId={}, startBizSeq={}, endBizSeq={}",
            replayId, symbol, userId, startBizSeq, endBizSeq);
        
        if (ledgerClient == null) {
            log.warn("[ReplayService] LedgerClient not available, fallback to Kafka replay");
            return replaySymbol(symbol);
        }
        
        // 标记任务启动（Redis 状态追踪）
        updateReplayStatus(replayId, "RUNNING", 0);
        
        // 提交异步任务，立即返回 replayId
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            executeLedgerReplay(replayId, symbol, userId, startBizSeq, endBizSeq);
        });
        
        return replayId;
    }
    
    /**
     * 实际执行 Ledger DB Replay（在后台线程中运行）
     */
    private void executeLedgerReplay(String replayId, String symbol, Long userId,
                                      Long startBizSeq, Long endBizSeq) {
        long startTime = System.currentTimeMillis();
        long currentSeq = (startBizSeq != null) ? startBizSeq : 0L;
        Set<Long> affectedUsers = new HashSet<>();
        int processedEvents = 0;
        int totalEntries = 0;
        
        try {
            while (true) {
                // 1. 调用 ledger-core 查询一页 entries（支持 userId 过滤）
                LedgerClient.QueryLedgerEntriesRequest request =
                    new LedgerClient.QueryLedgerEntriesRequest(userId, currentSeq, LEDGER_REPLAY_PAGE_SIZE);
                List<LedgerEntryDto> entries = ledgerClient.queryLedgerEntries(request);
                
                if (entries == null || entries.isEmpty()) {
                    log.info("[ReplayService] No more ledger entries from bizSeq={}", currentSeq);
                    break;
                }
                
                // 2. 按 ref_trade_id 聚合为 TradeEntryEvent
                List<TradeEntryEvent> events = aggregateEntriesToEvents(entries);
                
                // 3. 应用到 snapshot
                for (TradeEntryEvent event : events) {
                    try {
                        accountSnapshotService.onTradeEntryEvent(event);
                        processedEvents++;
                        
                        if (event.getEntries() != null) {
                            for (TradeEntryEvent.LedgerEntry entry : event.getEntries()) {
                                if (entry.getUserId() != null && entry.getUserId() > 0) {
                                    affectedUsers.add(entry.getUserId());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("[ReplayService] ❌ Apply ledger event error, tradeId={}",
                            event.getTradeId(), e);
                    }
                }
                
                totalEntries += entries.size();
                
                // 更新进度（Redis）
                if (processedEvents % 100 == 0) {
                    updateProgress(replayId, processedEvents);
                    updateReplayStatus(replayId, "RUNNING", processedEvents);
                    log.info("[ReplayService] Ledger replay progress: events={}, entries={}, currentSeq={}",
                        processedEvents, totalEntries, currentSeq);
                }
                
                // 4. 分页：用最后一条 entry 的 biz_seq + 1 作为下一页起点
                long lastBizSeq = entries.get(entries.size() - 1).getBizSeq();
                if (lastBizSeq < currentSeq) {
                    log.error("[ReplayService] Invalid pagination: lastBizSeq={} < currentSeq={}", lastBizSeq, currentSeq);
                    break;
                }
                currentSeq = lastBizSeq + 1;
                
                // 如果返回条数不足一页，说明已到最后
                if (entries.size() < LEDGER_REPLAY_PAGE_SIZE) {
                    break;
                }
                
                // 如果指定了 endBizSeq，检查是否已超出
                if (endBizSeq != null && currentSeq > endBizSeq) {
                    break;
                }
            }
            
            // 5. 重建 unrealizedPnl（ledger entry 不包含估值数据）
            log.info("[ReplayService] Rebuilding unrealizedPnl for {} affected users", affectedUsers.size());
            int rebuildCount = 0;
            for (Long uid : affectedUsers) {
                try {
                    accountSnapshotService.rebuildUnrealizedPnl(uid);
                    rebuildCount++;
                } catch (Exception e) {
                    log.error("[ReplayService] Failed to rebuild unrealizedPnl for userId={}", uid, e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            // 标记任务完成
            updateReplayStatus(replayId, "COMPLETED", processedEvents);
            
            log.info("[ReplayService] ========== Replay from Ledger DB completed ==========");
            log.info("[ReplayService] replayId={}, events={}, entries={}, affectedUsers={}, rebuiltUpnl={}, duration={}ms",
                replayId, processedEvents, totalEntries, affectedUsers.size(), rebuildCount, duration);
            
        } catch (Exception e) {
            log.error("[ReplayService] ❌ Replay from Ledger DB error, replayId={}", replayId, e);
            updateReplayStatus(replayId, "FAILED", processedEvents);
        }
    }
    
    /**
     * 更新 Replay 状态到 Redis（用于异步任务追踪）
     */
    private void updateReplayStatus(String replayId, String status, int processedCount) {
        if (redisTemplate == null) return;
        try {
            String key = "replay:status:" + replayId;
            java.util.Map<String, String> statusMap = new java.util.HashMap<>();
            statusMap.put("status", status);
            statusMap.put("processed", String.valueOf(processedCount));
            statusMap.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().putAll(key, statusMap);
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[ReplayService] Update replay status failed, replayId={}", replayId, e);
        }
    }
    
    /**
     * 将 LedgerEntryDto 列表按 ref_trade_id 聚合为 TradeEntryEvent 列表
     */
    private List<TradeEntryEvent> aggregateEntriesToEvents(List<LedgerEntryDto> entries) {
        Map<String, List<LedgerEntryDto>> tradeMap = new LinkedHashMap<>();
        
        for (LedgerEntryDto entry : entries) {
            String refTradeId = entry.getRefTradeId();
            if (refTradeId == null || refTradeId.isBlank()) {
                refTradeId = "UNKNOWN_" + entry.getEntryId();
            }
            tradeMap.computeIfAbsent(refTradeId, k -> new ArrayList<>()).add(entry);
        }
        
        List<TradeEntryEvent> events = new ArrayList<>();
        for (Map.Entry<String, List<LedgerEntryDto>> tradeEntry : tradeMap.entrySet()) {
            List<LedgerEntryDto> tradeEntries = tradeEntry.getValue();
            if (tradeEntries.isEmpty()) continue;
            
            LedgerEntryDto first = tradeEntries.get(0);
            
            TradeEntryEvent event = new TradeEntryEvent();
            event.setTradeId(tradeEntry.getKey());
            event.setBizSeq(first.getBizSeq());
            event.setEventTime(first.getCreatedAt());
            // symbol 在 LedgerEntry 中不存在，不影响 snapshot 更新逻辑
            
            List<TradeEntryEvent.LedgerEntry> ledgerEntries = new ArrayList<>();
            for (LedgerEntryDto dto : tradeEntries) {
                TradeEntryEvent.LedgerEntry le = new TradeEntryEvent.LedgerEntry();
                le.setEntryId(dto.getEntryId());
                le.setUserId(dto.getUserId());
                le.setAccountType(dto.getAccountType());
                le.setCurrency(dto.getCurrency());
                le.setDebit(dto.getDebit());
                le.setCredit(dto.getCredit());
                le.setBalanceBefore(dto.getBalanceBefore());
                le.setBalanceAfter(dto.getBalanceAfter());
                le.setBusinessType(dto.getBusinessType());
                le.setRefTradeId(dto.getRefTradeId());
                le.setRefOrderId(dto.getRefOrderId());
                le.setPairEntryId(dto.getPairEntryId());
                le.setBizSeq(dto.getBizSeq());
                le.setIdempotentKey(dto.getIdempotentKey());
                le.setCreatedAt(dto.getCreatedAt());
                ledgerEntries.add(le);
            }
            event.setEntries(ledgerEntries);
            events.add(event);
        }
        
        return events;
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 创建Replay专用Consumer
     */
    private KafkaConsumer<String, String> createReplayConsumer(String replayId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
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

    /**
     * SYSTEM 账务事件历史上存在双通道：
     * - 旧: trade-entry-SYSTEM
     * - 新: account-entry-SYSTEM
     * 为兼容历史数据，Replay SYSTEM 时按两个 topic 顺序重放。
     */
    private java.util.List<String> resolveReplayTopics(String symbol) {
        if ("SYSTEM".equalsIgnoreCase(symbol)) {
            return java.util.List.of("trade-entry-SYSTEM", "account-entry-SYSTEM");
        }
        return java.util.List.of("trade-entry-" + symbol);
    }
}
