package com.exchange.liquidation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 本地事件表
 * 用于保证事件发布的可靠性，防止Kafka发送失败导致事件丢失
 */
@Data
@TableName("t_liquidation_event")
public class LiquidationEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事件ID (唯一)
     */
    private String eventId;

    /**
     * 强平ID
     */
    private String liquidationId;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * Kafka Topic
     */
    private String topic;

    /**
     * 事件内容JSON
     */
    private String eventData;

    /**
     * 发送状态: PENDING/SENT/FAILED
     */
    private String sendStatus;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetry;

    /**
     * 下次重试时间
     */
    private Long nextRetryTime;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private Long createdAt;

    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 发送成功时间
     */
    private Long sentAt;

    /**
     * 发送状态枚举
     */
    public static class SendStatus {
        public static final String PENDING = "PENDING";
        public static final String SENT = "SENT";
        public static final String FAILED = "FAILED";
    }

    /**
     * 事件类型枚举
     */
    public static class EventType {
        public static final String LIQUIDATION_COMPLETED = "LIQUIDATION_COMPLETED";
    }
}
