package com.exchange.tpsl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * TP/SL执行日志实体
 */
@Data
@TableName("t_tp_sl_exec_log")
public class TpSlExecLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tpSlOrderId;
    private Long userId;
    private String symbol;

    private Long triggerPrice;
    private Long markPrice;
    private String execType;
    private Long quantity;

    private Long execOrderId;
    private String execResult; // SUCCESS/FAIL
    private String errorMsg;

    private Long createTime;
    private LocalDateTime createdAt;
}
