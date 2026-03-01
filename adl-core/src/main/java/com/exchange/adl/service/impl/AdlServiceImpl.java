package com.exchange.adl.service.impl;

import com.exchange.adl.entity.AdlRanking;
import com.exchange.adl.mapper.AdlRankingMapper;
import com.exchange.adl.service.AdlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ADL服务实现
 */
@Slf4j
@Service
public class AdlServiceImpl implements AdlService {
    
    @Autowired
    private AdlRankingMapper adlRankingMapper;
    
    @Override
    public void onLiquidationCompleted(String liquidationId, Long userId, String symbol, String side,
                                        Long bankruptPrice, Long bankruptQty, Long bankruptLoss) {
        log.info("Processing liquidation event: liquidationId={}, symbol={}, loss={}", 
                liquidationId, symbol, bankruptLoss);
        
        // 检查保险基金是否足够
        Long insuranceFund = getInsuranceFundBalance(symbol, "USDT");
        if (insuranceFund >= bankruptLoss) {
            // 使用保险基金赔付
            log.info("Using insurance fund to cover loss: {}", bankruptLoss);
        } else {
            // 触发ADL
            Long remainingLoss = bankruptLoss - insuranceFund;
            log.info("Triggering ADL for remaining loss: {}", remainingLoss);
            executeAdl(symbol, "LONG".equals(side) ? "SHORT" : "LONG", remainingLoss, liquidationId, userId);
        }
    }
    
    @Override
    public void calculateAdlRanking(String symbol, String side) {
        // 计算ADL排名
        log.debug("Calculating ADL ranking for symbol={}, side={}", symbol, side);
    }
    
    @Override
    public List<AdlRanking> getAdlRankings(String symbol, String side, int limit) {
        return adlRankingMapper.selectBySymbolAndSide(symbol, side, limit);
    }
    
    @Override
    public AdlRanking getUserAdlRank(Long userId, String symbol) {
        return adlRankingMapper.selectByUserAndSymbol(userId, symbol);
    }
    
    @Override
    public void executeAdl(String symbol, String oppositeSide, Long requiredQty, String sourceLiquidationId, Long sourceUserId) {
        // 获取ADL候选人
        List<AdlRanking> candidates = adlRankingMapper.selectBySymbolAndSide(symbol, oppositeSide, 10);
        
        for (AdlRanking candidate : candidates) {
            if (requiredQty <= 0) {
                break;
            }
            
            // 执行ADL减仓
            Long adlQty = Math.min(candidate.getQty(), requiredQty);
            log.info("Executing ADL: userId={}, qty={}", candidate.getUserId(), adlQty);
            
            requiredQty -= adlQty;
        }
    }
    
    @Override
    public Long getInsuranceFundBalance(String symbol, String currency) {
        // 实际应从数据库查询
        return 100000000000L; // 默认返回1000 USDT
    }
    
    @Override
    public boolean isInAdlZone(Long userId, String symbol) {
        AdlRanking ranking = adlRankingMapper.selectByUserAndSymbol(userId, symbol);
        if (ranking == null) {
            return false;
        }
        // 排名在前20%认为在ADL危险区
        return ranking.getAdlRank() <= 20;
    }
}
