package com.exchange.marketmaker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 做市商申请实体
 */
@Data
@TableName("t_market_maker_application")
public class MarketMakerApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    // 申请信息
    private String companyName;
    private String contactName;
    private String contactEmail;
    private String contactPhone;

    // 资质证明
    private String licenseNo;
    private String licenseDoc;

    // 历史业绩
    private String otherExchanges;
    private Long monthlyVolume;

    // 申请状态
    private String status;  // PENDING, APPROVED, REJECTED, REVOKED
    private Integer level;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
