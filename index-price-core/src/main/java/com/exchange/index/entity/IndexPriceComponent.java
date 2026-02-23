package com.exchange.index.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指数价格成分数据
 * 记录各交易所的价格成分
 */
@Data
@TableName("t_index_price_component")
public class IndexPriceComponent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 交易所代码
     */
    private String exchange;

    /**
     * 交易所原始价格
     */
    private Long rawPrice;

    /**
     * 权重
     */
    private Integer weight;

    /**
     * 是否有效
     */
    private Integer valid;

    /**
     * 失效原因
     */
    private String invalidReason;

    /**
     * 数据时间戳
     */
    private Long timestamp;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
