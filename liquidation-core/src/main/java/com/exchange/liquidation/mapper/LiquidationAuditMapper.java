package com.exchange.liquidation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.liquidation.entity.LiquidationAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 强平审计日志 Mapper
 */
@Mapper
public interface LiquidationAuditMapper extends BaseMapper<LiquidationAudit> {
}
