package com.exchange.position.controller;

import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.service.PositionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Position 对外查询 Controller（双向持仓模式Hedge Mode）
 * 
 * 🔒 安全设计原则：
 * 1. 所有接口必须通过 JWT 认证（不在 Gateway 白名单中）
 * 2. 用户ID必须从 Header X-User-Id 获取（由 API Gateway 从 JWT Token 解析并透传）
 * 3. 禁止用户通过 Query 参数指定 userId，防止越权查询他人持仓
 * 4. 持仓数据属于敏感金融数据，必须确保用户只能查询自己的持仓
 * 
 * 🔥 核心职责：
 * 1. 供前端查询用户持仓列表（支持双向持仓）
 * 2. 供前端查询单个持仓详情（需指定方向）
 * 3. 返回格式兼容前端展示需求
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/position")
public class PositionController {
    
    @Autowired
    private PositionService positionService;
    
    /**
     * 查询当前登录用户所有持仓（双向持仓模式）
     * 
     * GET /api/v1/position/list
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传，禁止客户端伪造)
     * 
     * 🔒 安全：用户只能查询自己的持仓，userId 从认证信息中获取
     * 
     * 返回：用户在各个交易对上的所有持仓（可能同时包含LONG和SHORT）
     */
    @GetMapping("/list")
    public PositionListResponse listPositions(@RequestHeader("X-User-Id") Long userId) {
        log.info("[PositionController] List positions, userId={}", userId);
        
        List<PositionSnapshot> positions = positionService.queryAllPositions(userId);
        
        List<PositionVO> positionVOs = positions.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        
        PositionListResponse response = new PositionListResponse();
        response.setPositions(positionVOs);
        response.setTotal(positionVOs.size());
        
        // 计算总未实现盈亏
        BigDecimal totalUnrealizedPnl = positionVOs.stream()
            .map(PositionVO::getUnrealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setTotalUnrealizedPnl(totalUnrealizedPnl.toPlainString());
        
        // 统计多空持仓数量
        long longCount = positionVOs.stream().filter(p -> "LONG".equals(p.getSide())).count();
        long shortCount = positionVOs.stream().filter(p -> "SHORT".equals(p.getSide())).count();
        response.setLongCount((int) longCount);
        response.setShortCount((int) shortCount);
        
        log.info("[PositionController] List positions success, userId={}, total={}, longCount={}, shortCount={}", 
            userId, positionVOs.size(), longCount, shortCount);
        
        return response;
    }
    
    /**
     * 查询当前登录用户在某个交易对上的所有持仓（双向持仓模式）
     * 
     * GET /api/v1/position/list-by-symbol?symbol=BTCUSDT
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传)
     * 
     * 🔒 安全：用户只能查询自己的持仓
     * 
     * 可能返回：
     * - 空列表（无持仓）
     * - 1条记录（只有多头或只有空头）
     * - 2条记录（同时持有多头和空头）
     */
    @GetMapping("/list-by-symbol")
    public PositionListResponse listPositionsBySymbol(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("symbol") String symbol) {
        log.info("[PositionController] List positions by symbol, userId={}, symbol={}", userId, symbol);
        
        List<PositionSnapshot> positions = positionService.queryPositionsBySymbol(userId, symbol);
        
        List<PositionVO> positionVOs = positions.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        
        PositionListResponse response = new PositionListResponse();
        response.setPositions(positionVOs);
        response.setTotal(positionVOs.size());
        
        // 计算净持仓
        BigDecimal netPosition = positionService.calculateNetPosition(userId, symbol);
        response.setNetPosition(netPosition.toPlainString());
        
        BigDecimal totalUnrealizedPnl = positionVOs.stream()
            .map(PositionVO::getUnrealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setTotalUnrealizedPnl(totalUnrealizedPnl.toPlainString());
        
        log.info("[PositionController] List positions by symbol success, userId={}, symbol={}, count={}, netPosition={}", 
            userId, symbol, positionVOs.size(), netPosition);
        
        return response;
    }
    
    /**
     * 查询当前登录用户的单个持仓（双向持仓模式 - 必须指定方向）
     * 
     * GET /api/v1/position/detail?symbol=BTCUSDT&positionSide=1
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传)
     * 
     * 🔒 安全：用户只能查询自己的持仓
     * 
     * positionSide: 1=LONG(多头), 2=SHORT(空头)
     */
    @GetMapping("/detail")
    public PositionVO getPosition(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("symbol") String symbol,
            @RequestParam("positionSide") Integer positionSide) {
        log.info("[PositionController] Get position, userId={}, symbol={}, positionSide={}", 
            userId, symbol, positionSide);
        
        PositionSnapshot position = positionService.queryPosition(userId, symbol, positionSide);
        if (position == null) {
            log.warn("[PositionController] Position not found, userId={}, symbol={}, positionSide={}", 
                userId, symbol, positionSide);
            return null;
        }
        
        return convertToVO(position);
    }
    
    /**
     * 查询当前登录用户的所有多头持仓
     * 
     * GET /api/v1/position/long-list
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传)
     * 
     * 🔒 安全：用户只能查询自己的持仓
     */
    @GetMapping("/long-list")
    public PositionListResponse listLongPositions(@RequestHeader("X-User-Id") Long userId) {
        log.info("[PositionController] List long positions, userId={}", userId);
        
        List<PositionSnapshot> positions = positionService.queryLongPositions(userId);
        
        List<PositionVO> positionVOs = positions.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        
        PositionListResponse response = new PositionListResponse();
        response.setPositions(positionVOs);
        response.setTotal(positionVOs.size());
        
        BigDecimal totalUnrealizedPnl = positionVOs.stream()
            .map(PositionVO::getUnrealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setTotalUnrealizedPnl(totalUnrealizedPnl.toPlainString());
        
        return response;
    }
    
    /**
     * 查询当前登录用户的所有空头持仓
     * 
     * GET /api/v1/position/short-list
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传)
     * 
     * 🔒 安全：用户只能查询自己的持仓
     */
    @GetMapping("/short-list")
    public PositionListResponse listShortPositions(@RequestHeader("X-User-Id") Long userId) {
        log.info("[PositionController] List short positions, userId={}", userId);
        
        List<PositionSnapshot> positions = positionService.queryShortPositions(userId);
        
        List<PositionVO> positionVOs = positions.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        
        PositionListResponse response = new PositionListResponse();
        response.setPositions(positionVOs);
        response.setTotal(positionVOs.size());
        
        BigDecimal totalUnrealizedPnl = positionVOs.stream()
            .map(PositionVO::getUnrealizedPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setTotalUnrealizedPnl(totalUnrealizedPnl.toPlainString());
        
        return response;
    }
    
    /**
     * 查询当前登录用户的净持仓（双向持仓模式）
     * 
     * GET /api/v1/position/net?symbol=BTCUSDT
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传)
     * 
     * 🔒 安全：用户只能查询自己的持仓
     * 
     * 返回：净持仓数量（正数=净多头，负数=净空头，0=对冲）
     */
    @GetMapping("/net")
    public NetPositionResponse getNetPosition(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("symbol") String symbol) {
        log.info("[PositionController] Get net position, userId={}, symbol={}", userId, symbol);
        
        BigDecimal netPosition = positionService.calculateNetPosition(userId, symbol);
        
        NetPositionResponse response = new NetPositionResponse();
        response.setUserId(userId);
        response.setSymbol(symbol);
        response.setNetPosition(netPosition.toPlainString());
        response.setSide(netPosition.compareTo(BigDecimal.ZERO) > 0 ? "LONG" : 
                        netPosition.compareTo(BigDecimal.ZERO) < 0 ? "SHORT" : "NEUTRAL");
        
        return response;
    }
    
    /**
     * 转换为视图对象（双向持仓模式）
     */
    private PositionVO convertToVO(PositionSnapshot position) {
        PositionVO vo = new PositionVO();
        vo.setSymbol(position.getSymbol());
        vo.setSide(position.isLong() ? "LONG" : "SHORT");
        vo.setPositionSide(position.getPositionSide());
        vo.setSize(position.getSize().toPlainString());
        vo.setEntryPrice(position.getEntryPrice().toPlainString());
        vo.setUnrealizedPnl(position.getUnrealizedPnl() != null ? 
            position.getUnrealizedPnl() : BigDecimal.ZERO);
        vo.setRealizedPnl(position.getRealizedPnl() != null ? 
            position.getRealizedPnl() : BigDecimal.ZERO);
        vo.setLeverage(10); // 默认10倍杠杆，实际应该从订单或配置中获取
        vo.setMarginRatio(position.getMarginRatio() != null ? 
            position.getMarginRatio().toPlainString() : "0");
        vo.setLiquidationPrice(position.getLiquidationPrice() != null ? 
            position.getLiquidationPrice().toPlainString() : "0");
        vo.setUpdatedAt(position.getUpdatedAt());
        return vo;
    }
    
    // ==================== DTO ====================
    
    @Data
    public static class PositionListResponse {
        private List<PositionVO> positions;
        private Integer total;
        private String totalUnrealizedPnl;
        private Integer longCount;      // 多头持仓数量
        private Integer shortCount;     // 空头持仓数量
        private String netPosition;     // 净持仓（双向持仓模式下有用）
    }
    
    @Data
    public static class PositionVO {
        private String symbol;
        private String side;            // LONG / SHORT
        private Integer positionSide;   // 1=LONG, 2=SHORT
        private String size;            // 持仓数量（始终为正）
        private String entryPrice;      // 开仓均价
        private BigDecimal unrealizedPnl;  // 未实现盈亏
        private BigDecimal realizedPnl;    // 已实现盈亏
        private Integer leverage;       // 杠杆倍数
        private String marginRatio;     // 保证金率
        private String liquidationPrice;  // 强平价
        private Long updatedAt;
    }
    
    @Data
    public static class NetPositionResponse {
        private Long userId;
        private String symbol;
        private String netPosition;     // 净持仓（正数=净多头，负数=净空头，0=对冲）
        private String side;            // LONG / SHORT / NEUTRAL
    }
}
