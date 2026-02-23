package com.exchange.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 风控黑白名单实体
 */
@Data
@TableName("risk_user_list")
public class RiskUserList {
    
    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 名单类型 0=BLACK 1=WHITE
     */
    private Integer listType;
    
    /**
     * 原因
     */
    private String reason;
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 是否黑名单
     */
    public boolean isBlackList() {
        return listType != null && listType == 0;
    }
    
    /**
     * 是否白名单
     */
    public boolean isWhiteList() {
        return listType != null && listType == 1;
    }
}

