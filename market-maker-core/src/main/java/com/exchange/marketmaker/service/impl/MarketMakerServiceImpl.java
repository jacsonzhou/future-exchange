package com.exchange.marketmaker.service.impl;

import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.entity.MmPerformance;
import com.exchange.marketmaker.mapper.MarketMakerMapper;
import com.exchange.marketmaker.service.MarketMakerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 做市商服务实现
 */
@Slf4j
@Service
public class MarketMakerServiceImpl implements MarketMakerService {
    
    @Autowired
    private MarketMakerMapper marketMakerMapper;
    
    @Override
    public MarketMaker applyMarketMaker(MarketMaker marketMaker) {
        marketMakerMapper.insert(marketMaker);
        return marketMaker;
    }
    
    @Override
    public MarketMaker approveMarketMaker(Long userId, boolean approved, Integer level) {
        MarketMaker maker = marketMakerMapper.selectByUserId(userId);
        if (maker == null) {
            return null;
        }
        maker.setStatus(approved ? "ACTIVE" : "REJECTED");
        maker.setLevel(level);
        marketMakerMapper.updateById(maker);
        return maker;
    }
    
    @Override
    public MarketMaker getMarketMaker(Long userId) {
        return marketMakerMapper.selectByUserId(userId);
    }
    
    @Override
    public boolean updateStatus(Long userId, Integer newStatus) {
        return false;
    }
    
    @Override
    public MarketMaker adjustLevel(Long userId, Integer newLevel) {
        MarketMaker maker = marketMakerMapper.selectByUserId(userId);
        if (maker == null) {
            return null;
        }
        maker.setLevel(newLevel);
        marketMakerMapper.updateById(maker);
        return maker;
    }
    
    @Override
    public List<MarketMaker> getAllActiveMakers() {
        return marketMakerMapper.selectAllActive();
    }
    
    @Override
    public List<MarketMaker> getMakersBySymbol(String symbol) {
        return marketMakerMapper.selectActiveMakersBySymbol(symbol);
    }
    
    @Override
    public MmPerformance calculatePerformance(Long userId, String symbol, Integer periodDate) {
        return null;
    }
    
    @Override
    public MmPerformance getPerformance(Long userId, String symbol, Integer periodDate) {
        return null;
    }
    
    @Override
    public List<MmPerformance> getPerformanceHistory(Long userId, Integer limit) {
        return null;
    }
    
    @Override
    public Long settleReward(Long userId, Integer periodDate) {
        return 0L;
    }
    
    @Override
    public Integer checkQualification(Long userId, String symbol, Integer periodDate) {
        return 1;
    }
    
    @Override
    public boolean updateFeeRate(Long userId, Long makerFeeRate, Long takerFeeRate) {
        return false;
    }
    
    @Override
    public int batchCalculateDailyPerformance(Integer periodDate) {
        return 0;
    }
}
