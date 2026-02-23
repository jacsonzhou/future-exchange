package com.exchange.privatepush.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.privatepush.service.MessageDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 账户资金变化 Kafka 消费者
 * 
 * 🔥 核心职责：
 * 1. 消费 private-account-change Topic
 * 2. 按 userId 路由消息
 * 3. 调用 MessageDispatcher 发送到用户会话
 * 
 * Topic: private-account-change
 * 分区: userId % partitionCount
 * 
 * 事件来源：
 * - Ledger Core: 资金冻结、解冻、结算
 * - Snapshot Account Core: 余额快照更新
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class AccountChangeConsumer {
    
    @Autowired
    private MessageDispatcher messageDispatcher;
    
    /**
     * 消费账户资金变化事件
     * 
     * 消息格式:
     * {
     *   "userId": 12345,
     *   "eventType": "BALANCE_UPDATE",
     *   "timestamp": 1708326400000,
     *   "balances": [
     *     {
     *       "asset": "USDT",
     *       "available": "10000.00000000",
     *       "frozen": "5000.00000000"
     *     }
     *   ],
     *   "changes": [
     *     {
     *       "asset": "USDT",
     *       "change": "100.00000000",
     *       "changeType": "FREEZE",
     *       "bizType": "ORDER",
     *       "bizId": "order_123"
     *     }
     *   ]
     * }
     */
    @KafkaListener(
        topics = "${private.push.kafka.account-change-topic:private-account-change}",
        groupId = "${private.push.kafka.consumer-group-id:private-push-service}",
        concurrency = "5"  // 5个消费者并发处理
    )
    public void consumeAccountChange(
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        String key = record.key();
        String value = record.value();
        
        log.debug("[AccountChangeConsumer] Received message, topic={}, partition={}, offset={}, key={}",
                topic, partition, offset, key);
        
        try {
            // 解析消息
            JSONObject event = JSON.parseObject(value);
            
            if (event == null || !event.containsKey("userId")) {
                log.warn("[AccountChangeConsumer] Invalid message format, offset={}", offset);
                return;
            }
            
            Long userId = event.getLong("userId");
            String eventType = event.getString("eventType");
            
            // 构建账户更新消息（兼容Binance格式）
            JSONObject accountUpdate = buildAccountUpdate(event);
            
            // 分发消息
            messageDispatcher.sendToUser(userId, "account", accountUpdate);
            
            log.info("[AccountChangeConsumer] Processed account change, userId={}, eventType={}, seq={}",
                    userId, eventType, accountUpdate.getLong("seq"));
                    
        } catch (Exception e) {
            log.error("[AccountChangeConsumer] Failed to process message, topic={}, offset={}",
                    topic, offset, e);
            // 不抛异常，避免消息堆积
        }
    }
    
    /**
     * 构建账户更新消息（兼容Binance格式）
     * 
     * 格式:
     * {
     *   "e": "account",
     *   "E": 1708326400000,
     *   "u": 1708326400000,
     *   "B": [
     *     {
     *       "a": "USDT",
     *       "f": "10000.00000000",
     *       "l": "5000.00000000"
     *     }
     *   ]
     * }
     */
    private JSONObject buildAccountUpdate(JSONObject event) {
        JSONObject accountUpdate = new JSONObject();
        accountUpdate.put("e", "account");
        accountUpdate.put("E", System.currentTimeMillis());
        accountUpdate.put("u", event.getLong("timestamp"));
        
        // 生成序列号
        long seq = System.currentTimeMillis() * 1000 + (System.nanoTime() % 1000);
        accountUpdate.put("seq", seq);
        
        // 构建余额列表
        if (event.containsKey("balances")) {
            accountUpdate.put("B", event.getJSONArray("balances"));
        }
        
        // 构建变化列表（可选）
        if (event.containsKey("changes")) {
            accountUpdate.put("C", event.getJSONArray("changes"));
        }
        
        return accountUpdate;
    }
}

