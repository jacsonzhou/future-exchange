package com.exchange.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Ledger Replay Log 重放记录（生产级）
 * 
 * 用途：记录Replay操作历史
 */
@Data
@TableName("ledger_replay_log")
public class LedgerReplayLog {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * Replay ID
     */
    private String replayId;
    
    /**
     * Replay类型：FULL=全量,INCREMENTAL=增量
     */
    private String replayType;
    
    /**
     * 备注
     */
    private String remark;
    
    /**
     * 起始biz_seq
     */
    private Long startBizSeq;
    
    /**
     * 结束biz_seq
     */
    private Long endBizSeq;
    
    /**
     * 影响用户数
     */
    private Integer affectedUsers;
    
    /**
     * 处理分录数
     */
    private Integer processedEntries;
    
    /**
     * 状态：0=进行中,1=成功,2=失败
     */
    private Integer status;
    
    /**
     * 错误信息
     */
    private String errorMsg;
    
    /**
     * 开始时间
     */
    private Long startTime;
    
    /**
     * 结束时间
     */
    private Long endTime;
    
    /**
     * 耗时（毫秒）
     */
    private Long durationMs;
    
    /**
     * 操作人
     */
    private String operator;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}



