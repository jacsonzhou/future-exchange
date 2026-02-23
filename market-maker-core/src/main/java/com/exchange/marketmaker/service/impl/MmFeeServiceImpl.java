package com.exchange.marketmaker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.marketmaker.dto.response.FeeLogResponse;
import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.entity.MmFeeLog;
import com.exchange.marketmaker.mapper.MarketMakerMapper;
import com.exchange.marketmaker.mapper.MmFeeLogMapper;
import com.exchange.marketmaker.service.MmFeeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 做市商费率服务实现
 */
@Slf4j
@Service
public class MmFeeServiceImpl implements MmFeeService {

    @Autowired
    private MmFeeLogMapper mmFeeLogMapper;

    @Autowired
    private MarketMakerMapper marketMakerMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void recordFeeLog(MmFeeLog feeLog) {
        log.info("[MM-Fee] Record fee log, userId={}, tradeId={}, feeType={}, feeAmount={}",
                feeLog.getUserId(), feeLog.getTradeId(), feeLog.getFeeType(), feeLog.getFeeAmount());

        mmFeeLogMapper.insert(feeLog);
    }

    @Override
    public FeeLogResponse queryFeeLog(Long userId, String startTime, String endTime) {
        log.info("[MM-Fee] Query fee log, userId={}, startTime={}, endTime={}",
                userId, startTime, endTime);

        // 查询费率流水
        LambdaQueryWrapper<MmFeeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MmFeeLog::getUserId, userId);
        wrapper.ge(MmFeeLog::getCreatedAt, LocalDateTime.parse(startTime, DATE_TIME_FORMATTER));
        wrapper.le(MmFeeLog::getCreatedAt, LocalDateTime.parse(endTime, DATE_TIME_FORMATTER));
        wrapper.orderByDesc(MmFeeLog::getCreatedAt);

        List<MmFeeLog> feeLogs = mmFeeLogMapper.selectList(wrapper);

        // 统计总返佣
        Long totalRebate = mmFeeLogMapper.sumRebateByUserIdAndTimeRange(userId, startTime, endTime);

        // 构建响应
        FeeLogResponse response = new FeeLogResponse();
        response.setTotalRebate(formatAmount(Math.abs(totalRebate)) + " USDT");

        List<FeeLogResponse.FeeLogItem> logs = new ArrayList<>();
        for (MmFeeLog feeLog : feeLogs) {
            FeeLogResponse.FeeLogItem item = new FeeLogResponse.FeeLogItem();
            item.setTradeId(feeLog.getTradeId());
            item.setSymbol(feeLog.getSymbol());
            item.setSide(feeLog.getSide());
            item.setPrice(formatAmount(feeLog.getPrice()));
            item.setQuantity(formatAmount(feeLog.getQuantity()));
            item.setFeeRate(formatPercentage(feeLog.getFeeRate()));
            item.setFeeAmount(formatAmount(feeLog.getFeeAmount()) + " USDT");
            item.setTime(feeLog.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            logs.add(item);
        }
        response.setLogs(logs);

        return response;
    }

    @Override
    public Long calculateMakerFeeRate(Long userId) {
        MarketMaker mm = marketMakerMapper.selectOne(
                new LambdaQueryWrapper<MarketMaker>()
                        .eq(MarketMaker::getUserId, userId)
        );

        if (mm != null && "ACTIVE".equals(mm.getStatus())) {
            return mm.getMakerFeeRate();
        }

        // 默认普通用户费率
        return 10_000L;  // 0.01%
    }

    @Override
    public Long calculateTakerFeeRate(Long userId) {
        MarketMaker mm = marketMakerMapper.selectOne(
                new LambdaQueryWrapper<MarketMaker>()
                        .eq(MarketMaker::getUserId, userId)
        );

        if (mm != null && "ACTIVE".equals(mm.getStatus())) {
            return mm.getTakerFeeRate();
        }

        // 默认普通用户费率
        return 50_000L;  // 0.05%
    }

    private String formatPercentage(long value) {
        return String.format("%.2f%%", value / 1000000.0);
    }

    private String formatAmount(long value) {
        return String.format("%.2f", value / 100000000.0);
    }
}
