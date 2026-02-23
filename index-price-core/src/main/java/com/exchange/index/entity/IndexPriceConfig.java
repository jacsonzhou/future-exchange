package com.exchange.index.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指数价格配置
 */
@Data
@TableName("t_index_price_config")
public class IndexPriceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 成分交易所列表，逗号分隔
     */
    private String components;

    /**
     * 更新间隔（毫秒）
     */
    private Integer updateIntervalMs;

    /**
     * 价格偏差阈值（超过此阈值视为异常）
     */
    private Long deviationThreshold;

    /**
     * 最小有效成分数
     */
    private Integer minValidComponents;

    /**
     * 状态：1启用 0禁用
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
