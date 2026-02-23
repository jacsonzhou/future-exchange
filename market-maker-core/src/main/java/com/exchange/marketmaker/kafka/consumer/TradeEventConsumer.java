package com.exchange.marketmaker.kafka.consumer;

import com.alibaba.fastjson.JSON;
import com.exchange.marketmaker.entity.MmFeeLog;
import com.exchange.marketmaker.service.MmFeeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 成交事件消费者
 * 用于记录做市商费率流水
 */
@Slf4j
@Component
public class TradeEventConsumer {

    @Autowired
    private MmFeeService mmFeeService;

    /**
     * 消费成交事件，记录做市商费率
     */
    @KafkaListener(topics = "trade-topic", groupId = "market-maker-group")
    public void consumeTradeEvent(String message) {
        try {
            log.debug("[MM-Kafka] Receive trade event: {}", message);

            // TODO: 解析成交事件
            // TradeEvent event = JSON.parseObject(message, TradeEvent.class);

            // TODO: 判断是否为做市商
            // Long makerFeeRate = mmFeeService.calculateMakerFeeRate(event.getUserId());
            // Long takerFeeRate = mmFeeService.calculateTakerFeeRate(event.getUserId());

            // TODO: 记录费率流水
            // MmFeeLog feeLog = new MmFeeLog();
            // feeLog.setUserId(event.getUserId());
            // feeLog.setTradeId(event.getTradeId());
            // feeLog.setFeeType(event.isMaker() ? "MAKER_REBATE" : "TAKER_FEE");
            // feeLog.setFeeAmount(calculateFeeAmount(event, feeRate));
            // feeLog.setFeeRate(feeRate);
            // feeLog.setSymbol(event.getSymbol());
            // feeLog.setSide(event.getSide());
            // feeLog.setPrice(event.getPrice());
            // feeLog.setQuantity(event.getQuantity());
            // mmFeeService.recordFeeLog(feeLog);

        } catch (Exception e) {
            log.error("[MM-Kafka] Consume trade event failed", e);
        }
    }
}
