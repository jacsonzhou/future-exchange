package com.exchange.funding.controller;

import com.exchange.common.core.Result;
import com.exchange.funding.dto.FundingRateDTO;
import com.exchange.funding.dto.FundingRateEstimateDTO;
import com.exchange.funding.dto.UserFundingFeeDTO;
import com.exchange.funding.entity.FundingRateHistory;
import com.exchange.funding.entity.UserFundingFee;
import com.exchange.funding.service.FundingRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 资金费率控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/funding-rate")
public class FundingRateController {
    
    @Autowired
    private FundingRateService fundingRateService;
    
    /**
     * 查询资金费率历史
     */
    @GetMapping("/history")
    public Result<List<FundingRateDTO>> getHistory(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "100") int limit) {
        
        List<FundingRateHistory> list = fundingRateService.getFundingRateHistory(symbol, limit);
        List<FundingRateDTO> dtos = list.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        
        return Result.success(dtos);
    }
    
    /**
     * 查询预估资金费率
     */
    @GetMapping("/estimate")
    public Result<FundingRateEstimateDTO> getEstimate(@RequestParam String symbol) {
        Long estimatedRate = fundingRateService.getEstimatedFundingRate(symbol);
        Long nextFundingTime = fundingRateService.getNextFundingTime(symbol);
        
        FundingRateEstimateDTO dto = new FundingRateEstimateDTO();
        dto.setSymbol(symbol);
        dto.setEstimatedRate(estimatedRate);
        dto.setNextFundingTime(nextFundingTime);
        
        return Result.success(dto);
    }
    
    /**
     * 查询所有symbol当前资金费率
     */
    @GetMapping("/current")
    public Result<List<FundingRateEstimateDTO>> getCurrentAll() {
        // 实际应从缓存获取所有symbol的当前费率
        // 这里简化处理
        return Result.success(List.of());
    }
    
    /**
     * 查询用户资金费用记录
     */
    @GetMapping("/user-fees")
    public Result<List<UserFundingFeeDTO>> getUserFees(
            @RequestParam Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        
        List<UserFundingFee> list = fundingRateService.getUserFundingFees(userId, symbol, startTime, endTime);
        List<UserFundingFeeDTO> dtos = list.stream()
                .map(this::convertToFeeDTO)
                .collect(Collectors.toList());
        
        return Result.success(dtos);
    }
    
    private FundingRateDTO convertToDTO(FundingRateHistory history) {
        FundingRateDTO dto = new FundingRateDTO();
        dto.setSymbol(history.getSymbol());
        dto.setFundingTime(history.getFundingTime());
        dto.setFundingRate(history.getFundingRate());
        dto.setMarkPrice(history.getMarkPrice());
        dto.setIndexPrice(history.getIndexPrice());
        dto.setPremiumIndex(history.getPremiumIndex());
        return dto;
    }
    
    private UserFundingFeeDTO convertToFeeDTO(UserFundingFee fee) {
        UserFundingFeeDTO dto = new UserFundingFeeDTO();
        dto.setSymbol(fee.getSymbol());
        dto.setFundingTime(fee.getFundingTime());
        dto.setSide(fee.getSide());
        dto.setPositionQty(fee.getPositionQty());
        dto.setFundingRate(fee.getFundingRate());
        dto.setFundingFee(fee.getFundingFee());
        dto.setMarginMode(fee.getMarginMode());
        return dto;
    }
}
