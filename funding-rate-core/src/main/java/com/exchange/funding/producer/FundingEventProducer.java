package com.exchange.funding.producer;

import com.exchange.funding.event.FundingRateCalcEvent;
import com.exchange.funding.event.FundingSettlementEvent;
import com.exchange.funding.event.UserFundingFeeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 资金费率事件生产者
 */
@Slf4j
@Component
public class FundingEventProducer {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${funding-rate.kafka.topics.funding-rate-calc:funding-rate-calc}")
    private String fundingRateCalcTopic;

    @Value("${funding-rate.kafka.topics.funding-settlement:funding-settlement}")
    private String fundingSettlementTopic;

    @Value("${funding-rate.kafka.topics.user-funding-fee:user-funding-fee}")
    private String userFundingFeeTopic;

    /**
     * 发布资金费率计算完成事件
     */
    public void publishFundingRateCalcEvent(FundingRateCalcEvent event) {
        log.info("Publishing funding rate calc event: symbol={}, rate={}",
                event.getData().getSymbol(), event.getData().getFundingRate());

        sendEvent(fundingRateCalcTopic, event.getData().getSymbol(), event);
    }

    /**
     * 发布资金费用结算完成事件
     */
    public void publishFundingSettlementEvent(FundingSettlementEvent event) {
        log.info("Publishing funding settlement event: symbol={}, users={}",
                event.getData().getSymbol(), event.getData().getTotalUsers());

        sendEvent(fundingSettlementTopic, event.getData().getSymbol(), event);
    }

    /**
     * 发布用户资金费用事件
     */
    public void publishUserFundingFeeEvent(UserFundingFeeEvent event) {
        log.debug("Publishing user funding fee event: userId={}, symbol={}, fee={}",
                event.getData().getUserId(), event.getData().getSymbol(), event.getData().getFundingFee());

        sendEvent(userFundingFeeTopic, event.getData().getSymbol(), event);
    }

    /**
     * 发送事件到Kafka
     */
    private void sendEvent(String topic, String key, Object event) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, event);
        future.whenComplete((result, ex) -> {
            if (ex == null && result != null) {
                log.debug("Event sent successfully to topic={}, partition={}, offset={}",
                        topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                return;
            }
            log.error("Failed to send event to topic={}, key={}", topic, key, ex);
            // TODO: 添加到重试队列
        });
    }
}
