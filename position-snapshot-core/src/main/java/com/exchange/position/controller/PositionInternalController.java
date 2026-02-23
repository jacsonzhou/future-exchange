package com.exchange.position.controller;

import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.service.PositionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Position Internal Controller（生产级 - 双向持仓模式Hedge Mode）
 * 
 * 🔥 核心职责：
 * 1. 供风控查询持仓快照
 * 2. 供API查询持仓信息
 * 3. 支持双向持仓模式（同时查询LONG和SHORT）
 */
@Slf4j
@RestController
@RequestMapping("/internal/position")
public class PositionInternalController {
    
    @Autowired
    private PositionService positionService;
    
    /**
     * 查询用户特定方向的持仓（风控高频调用）
     * 
     * GET /internal/position/{userId}/{symbol}?positionSide=1
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param positionSide 持仓方向（1=LONG, 2=SHORT），默认1
     */
    @GetMapping("/{userId}/{symbol}")
    public PositionSnapshot getPosition(
            @PathVariable Long userId, 
            @PathVariable String symbol,
            @RequestParam(value = "positionSide", defaultValue = "1") Integer positionSide) {
        log.info("[PositionInternalController] Query position, userId={}, symbol={}, positionSide={}", 
            userId, symbol, positionSide);
        return positionService.queryPosition(userId, symbol, positionSide);
    }
    
    /**
     * 查询用户在某个交易对上的所有持仓（双向持仓模式）
     * 
     * GET /internal/position/{userId}/{symbol}/all
     * 
     * 可能返回LONG和SHORT两条记录
     */
    @GetMapping("/{userId}/{symbol}/all")
    public List<PositionSnapshot> getPositionsBySymbol(
            @PathVariable Long userId, 
            @PathVariable String symbol) {
        log.info("[PositionInternalController] Query positions by symbol, userId={}, symbol={}", 
            userId, symbol);
        return positionService.queryPositionsBySymbol(userId, symbol);
    }
    
    /**
     * 查询用户所有持仓
     * 
     * GET /internal/position/{userId}/all
     */
    @GetMapping("/{userId}/all")
    public List<PositionSnapshot> getAllPositions(@PathVariable Long userId) {
        log.info("[PositionInternalController] Query all positions, userId={}", userId);
        return positionService.queryAllPositions(userId);
    }
    
    /**
     * 查询净持仓（双向持仓模式）
     * 
     * GET /internal/position/{userId}/{symbol}/net
     * 
     * @return 净持仓（正数=净多头，负数=净空头，0=对冲）
     */
    @GetMapping("/{userId}/{symbol}/net")
    public BigDecimal getNetPosition(
            @PathVariable Long userId, 
            @PathVariable String symbol) {
        log.info("[PositionInternalController] Query net position, userId={}, symbol={}", 
            userId, symbol);
        return positionService.calculateNetPosition(userId, symbol);
    }
    
    /**
     * 查询用户的所有多头持仓
     * 
     * GET /internal/position/{userId}/long
     */
    @GetMapping("/{userId}/long")
    public List<PositionSnapshot> getLongPositions(@PathVariable Long userId) {
        log.info("[PositionInternalController] Query long positions, userId={}", userId);
        return positionService.queryLongPositions(userId);
    }
    
    /**
     * 查询用户的所有空头持仓
     * 
     * GET /internal/position/{userId}/short
     */
    @GetMapping("/{userId}/short")
    public List<PositionSnapshot> getShortPositions(@PathVariable Long userId) {
        log.info("[PositionInternalController] Query short positions, userId={}", userId);
        return positionService.queryShortPositions(userId);
    }
}
