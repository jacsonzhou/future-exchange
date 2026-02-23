package com.exchange.marketmaker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 做市商费率流水实体
 */
@Data
@TableName("t_mm_fee_log")
public class MmFeeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long tradeId;

    // 费率信息
    private String feeType;  // MAKER_REBATE, MAKER_FEE, TAKER_FEE
    private Long feeAmount;  // 手续费金额(负数=返佣)
    private Long feeRate;

    // 成交信息
    private String symbol;
    private String side;
    private Long price;
    private Long quantity;

    private LocalDateTime createdAt;
}
