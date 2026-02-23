package com.exchange.marketmaker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.marketmaker.entity.MmQuoteSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 做市商报价快照Mapper
 */
@Mapper
public interface MmQuoteSnapshotMapper extends BaseMapper<MmQuoteSnapshot> {
}
