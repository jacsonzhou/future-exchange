package com.exchange.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易账户实体
 * 
 * 一个用户可以有一个或多个交易账户
 */
@Data
@TableName("t_trading_account")
public class TradingAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 账户类型: STANDARD-标准 MARGIN-保证金 FUTURES-合约
     */
    private String accountType;

    /**
     * 保证金模式: CROSS-全仓 ISOLATED-逐仓
     */
    private String marginMode;

    /**
     * 默认杠杆倍数
     */
    private Integer defaultLeverage;

    /**
     * 状态: 0-冻结 1-正常
     */
    private Integer status;

    /**
     * 是否已初始化资金
     */
    @TableField("is_funded")
    private Boolean funded;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
