package com.exchange.margin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保证金变动流水实体
 *
 * 记录所有保证金变动，用于审计和对账
 */
@Data
@TableName("t_margin_change_log")
public class MarginChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 仓位ID（如果关联仓位）
     */
    private Long positionId;

    // ==================== 变动信息 ====================

    /**
     * 变动类型
     * @see com.exchange.margin.enums.ChangeType
     */
    private String changeType;

    /**
     * 保证金模式：CROSS/ISOLATED
     */
    private String marginMode;

    /**
     * 变动金额（正=增加，负=减少）
     * 精度8位小数
     */
    private Long amount;

    /**
     * 变动前金额
     */
    private Long beforeAmount;

    /**
     * 变动后金额
     */
    private Long afterAmount;

    // ==================== 关联信息 ====================

    /**
     * 业务ID（订单ID、结算ID等）
     */
    private Long bizId;

    /**
     * 业务类型
     */
    private String bizType;

    /**
     * 备注信息
     */
    private String remark;

    // ==================== 时间戳 ====================

    private LocalDateTime createdAt;
}
