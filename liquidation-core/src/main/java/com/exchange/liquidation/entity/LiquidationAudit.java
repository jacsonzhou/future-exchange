package com.exchange.liquidation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 强平审计日志表
 * 用于满足金融监管要求，记录所有强平操作
 */
@Data
@TableName("t_liquidation_audit")
public class LiquidationAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 审计ID (唯一)
     */
    private String auditId;

    /**
     * 强平ID
     */
    private String liquidationId;

    /**
     * 操作类型
     */
    private String operation;

    /**
     * 操作人ID (NULL表示系统自动)
     */
    private Long operatorId;

    /**
     * 操作类型: AUTO/MANUAL
     */
    private String operatorType;

    /**
     * 操作原因
     */
    private String reason;

    /**
     * 变更前数据JSON
     */
    private String beforeData;

    /**
     * 变更后数据JSON
     */
    private String afterData;

    /**
     * 是否成功: 0=否, 1=是
     */
    private Integer success;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private Long createdAt;

    /**
     * 操作类型枚举
     */
    public static class Operation {
        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String CANCEL = "CANCEL";
        public static final String RETRY = "RETRY";
    }

    /**
     * 操作人类型枚举
     */
    public static class OperatorType {
        public static final String AUTO = "AUTO";
        public static final String MANUAL = "MANUAL";
    }
}
