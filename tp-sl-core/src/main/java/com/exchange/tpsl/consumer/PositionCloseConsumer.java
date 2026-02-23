package com.exchange.tpsl.consumer;

import com.exchange.tpsl.service.TpSlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 持仓平仓事件消费者
 *
 * 监听持仓平仓事件，自动撤销关联的TP/SL订单
 */
@Slf4j
@Component
public class PositionCloseConsumer {

    @Autowired
    private TpSlService tpSlService;

    @KafkaListener(topics = "position-closed", groupId = "tp-sl-position-close-group")
    public void onPositionClosed(Map<String, Object> message) {
        try {
            Long positionId = ((Number) message.get("positionId")).longValue();
            String symbol = (String) message.get("symbol");
            Long userId = ((Number) message.get("userId")).longValue();

            log.info("Received position closed event: positionId={}, symbol={}, userId={}",
                positionId, symbol, userId);

            // 撤销该持仓关联的所有活跃TP/SL订单
            tpSlService.cancelByPositionClose(positionId);

        } catch (Exception e) {
            log.error("Failed to process position closed event", e);
        }
    }
}
