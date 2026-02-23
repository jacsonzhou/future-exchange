package com.exchange.liquidation.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.liquidation.service.OrderMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单状态消费者
 * 
 * 消费OMS发布的订单状态变更事件，更新强平订单状态
 * 
 * Topic: order-state-{symbol}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateConsumer {
    
    private final OrderMonitorService orderMonitorService;
    
    /**
     * 消费订单状态变更事件
     * 
     * 监听所有symbol的订单状态事件
     */
    @KafkaListener(
        topics = {
            "${kafka.topic.order-status-btc:order-state-BTCUSDT}",
            "${kafka.topic.order-status-eth:order-state-ETHUSDT}",
            "${kafka.topic.order-status-xrp:order-state-XRPUSDT}"
        },
        groupId = "${spring.kafka.consumer.group-id:liquidation-order-state-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        
        for (ConsumerRecord<String, String> record : records) {
            try {
                log.debug("[OrderStateConsumer] Received order state event, " +
                        "partition={}, offset={}, key={}",
                        record.partition(), record.offset(), record.key());
                
                // 解析事件
                JSONObject event = JSON.parseObject(record.value());
                
                Long orderId = event.getLong("orderId");
                String status = event.getString("status");
                Long filledQty = event.getLong("filledQuantityDelta");
                Long avgPrice = event.getLong("avgPrice"); // 平均成交价格
                
                // 只处理强平订单（orderSource=LIQUIDATION）
                String orderSource = event.getString("orderSource");
                if (!"LIQUIDATION".equals(orderSource)) {
                    continue; // 跳过非强平订单
                }
                
                log.info("[OrderStateConsumer] Processing liquidation order state, " +
                        "orderId={}, status={}, filledQty={}, avgPrice={}",
                        orderId, status, filledQty, avgPrice);
                
                // 处理订单状态变更
                orderMonitorService.handleOrderStatusChange(orderId, status, filledQty, avgPrice);
                
                successCount++;
                
            } catch (Exception e) {
                failCount++;
                log.error("[OrderStateConsumer] Failed to process order state event, " +
                        "partition={}, offset={}, error={}",
                        record.partition(), record.offset(), e.getMessage(), e);
                // 不抛出异常，继续处理其他消息
            }
        }
        
        // 手动确认
        ack.acknowledge();
        
        long duration = System.currentTimeMillis() - startTime;
        if (records.size() > 0) {
            log.info("[OrderStateConsumer] Batch processed, total={}, success={}, failed={}, duration={}ms",
                    records.size(), successCount, failCount, duration);
        }
    }
}

