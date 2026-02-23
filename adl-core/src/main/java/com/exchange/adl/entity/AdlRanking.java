package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ADL排名实体
 */
@Data
@TableName("t_adl_ranking_queue")
public class AdlRanking {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long userId;
    private String symbol;
    private Long positionId;
    
    // 仓位信息
    private String side;
    private Long qty;
    private Long entryPrice;
    private Long positionValue;
    
    // 排名指标
    private Long margin;
    private Long unrealizedPnl;
    private Long pnlRatio;
    private Integer effectiveLeverage;
    private Long adlScore;
    private Integer adlRank;
    
    // 状态: ACTIVE/EXECUTED/REMOVED
    private String status;
    
    private Long calcTime;
    private LocalDateTime updatedAt;
}
