package com.exchange.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 幂等key实体
 */
@Data
@TableName("t_idempotent_key")
public class OmsIdempotentKey {
    
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
     * 幂等key
     */
    private String idemKey;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 请求hash
     */
    private String requestHash;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}






