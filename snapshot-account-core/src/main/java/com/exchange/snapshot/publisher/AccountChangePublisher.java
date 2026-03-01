package com.exchange.snapshot.publisher;

import com.exchange.snapshot.entity.AccountSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户变化事件发布器（供 private-push-core 推送账户更新）。
 */
@Slf4j
@Component
public class AccountChangePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${snapshot.kafka.account-change-topic:private-account-change}")
    private String accountChangeTopic;

    public AccountChangePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishSnapshotUpdate(AccountSnapshot snapshot, String eventType, String changeType, String bizId) {
        if (snapshot == null || snapshot.getUserId() == null) {
            return;
        }

        try {
            long now = System.currentTimeMillis();
            Map<String, Object> event = new HashMap<>();
            event.put("userId", snapshot.getUserId());
            event.put("eventType", eventType == null || eventType.isBlank() ? "ACCOUNT_SNAPSHOT_UPDATE" : eventType);
            event.put("timestamp", now);

            List<Map<String, Object>> balances = new ArrayList<>();
            Map<String, Object> balance = new HashMap<>();
            balance.put("asset", snapshot.getCurrency() == null ? "USDT" : snapshot.getCurrency());
            balance.put("available", decimal(snapshot.getAvailable()));
            balance.put("frozen", decimal(snapshot.getFrozen()));
            balance.put("positionMargin", decimal(snapshot.getPositionMargin()));
            balance.put("unrealizedPnl", decimal(snapshot.getUnrealizedPnl()));
            balance.put("realizedPnl", decimal(snapshot.getRealizedPnl()));
            balance.put("equity", decimal(snapshot.getEquity()));
            balances.add(balance);
            event.put("balances", balances);

            if (changeType != null && !changeType.isBlank()) {
                List<Map<String, Object>> changes = new ArrayList<>();
                Map<String, Object> change = new HashMap<>();
                change.put("asset", balance.get("asset"));
                change.put("change", "0");
                change.put("changeType", changeType);
                change.put("bizType", "ACCOUNT_SNAPSHOT");
                change.put("bizId", bizId);
                changes.add(change);
                event.put("changes", changes);
            }

            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(accountChangeTopic, String.valueOf(snapshot.getUserId()), payload);
        } catch (Exception e) {
            log.error("[AccountChangePublisher] Failed to publish account change, userId={}",
                snapshot.getUserId(), e);
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
