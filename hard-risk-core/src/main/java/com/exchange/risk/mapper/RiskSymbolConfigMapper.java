package com.exchange.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.risk.entity.RiskSymbolConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 交易对风控配置Mapper
 */
@Mapper
public interface RiskSymbolConfigMapper extends BaseMapper<RiskSymbolConfig> {
    
    /**
     * 根据交易对查询配置
     */
    @Select("SELECT * FROM risk_symbol_config WHERE symbol = #{symbol} AND status = 1 LIMIT 1")
    RiskSymbolConfig selectBySymbol(@Param("symbol") String symbol);
}

