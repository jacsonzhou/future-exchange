package com.exchange.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.risk.entity.RiskCheckLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 风控审计日志Mapper
 */
@Mapper
public interface RiskCheckLogMapper extends BaseMapper<RiskCheckLog> {
    
}

