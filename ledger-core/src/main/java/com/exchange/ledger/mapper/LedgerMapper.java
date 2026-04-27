package com.exchange.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.ledger.entity.LedgerEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账本Mapper
 */
@Mapper
public interface LedgerMapper extends BaseMapper<LedgerEntry> {
}







