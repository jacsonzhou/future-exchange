package com.exchange.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.ledger.entity.LedgerAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ledger Account Mapper
 */
@Mapper
public interface LedgerAccountMapper extends BaseMapper<LedgerAccount> {
}

