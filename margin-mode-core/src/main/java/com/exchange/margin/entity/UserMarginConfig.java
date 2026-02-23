package com.exchange.margin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户保证金配置实体
 *
 * 用于存储用户的保证金模式偏好和杠杆配置
 */
@Data
@TableName("t_user_margin_config")
public class UserMarginConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 交易对（symbol维度配置）
     */
    private String symbol;

    // ==================== 默认模式 ====================

    /**
     * 默认保证金模式：CROSS/ISOLATED
     */
    private String defaultMarginMode;

    // ==================== 全仓配置 ====================

    /**
     * 全仓默认杠杆
     */
    private Integer crossLeverage;

    /**
     * 全仓最大杠杆
     */
    private Integer crossMaxLeverage;

    // ==================== 逐仓配置 ====================

    /**
     * 逐仓默认杠杆
     */
    private Integer isolatedLeverage;

    /**
     * 逐仓最大杠杆
     */
    private Integer isolatedMaxLeverage;

    // ==================== 风控配置 ====================

    /**
     * 最大仓位数量（风控限制）
     */
    private Integer maxPositionNum;

    // ==================== 时间戳 ====================

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
