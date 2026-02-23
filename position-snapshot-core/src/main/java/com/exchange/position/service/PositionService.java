package com.exchange.position.service;

import com.exchange.position.dto.MarkPriceEvent;
import com.exchange.position.dto.TradeEntryEvent;
import com.exchange.position.dto.TradeEvent;
import com.exchange.position.entity.PositionSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * Position Service（生产级 - 双向持仓模式Hedge Mode）
 * 
 * 🔥 核心变化（双向持仓模式）：
 * 1. 同一个用户可以在同一交易对上同时持有多头和空头
 * 2. 新增 positionSide 参数（1=LONG, 2=SHORT）
 * 3. size 始终为正数，方向由 positionSide 决定
 * 
 * 🔥 核心职责：
 * 1. 消费TradeEvent更新持仓
 * 2. 消费MarkPriceEvent更新估值
 * 3. 计算未实现盈亏
 * 4. 计算保证金率和强平价
 * 5. 发布风险事件
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
public interface PositionService {
    
    /**
     * 消费TradeEvent更新持仓（核心方法 - 已弃用）
     * 
     * ⚠️ 废弃原因：直接消费撮合事件会破坏Ledger作为唯一事实源的原则
     * 请使用 onTradeEntryEvent(TradeEntryEvent) 替代
     * 
     * @param trade 成交事件
     * @deprecated 使用 {@link #onTradeEntryEvent(TradeEntryEvent)} 替代
     */
    @Deprecated
    void onTrade(TradeEvent trade);
    
    /**
     * 消费TradeEntryEvent更新持仓（核心方法 - 推荐）
     * 
     * 🔥 双向持仓模式支持：
     * - 根据订单方向（BUY/SELL）和持仓情况自动判断是LONG还是SHORT
     * - 同一个交易对可以同时持有多头和空头
     * 
     * 流程：
     * 1. 解析TradeEntryEvent
     * 2. 确定持仓方向（LONG/SHORT）
     * 3. 更新对应方向的持仓
     * 4. 计算已实现盈亏
     * 5. 更新MySQL和Redis
     * 
     * @param event 账本分录事件
     */
    void onTradeEntryEvent(TradeEntryEvent event);
    
    /**
     * 消费MarkPriceEvent更新估值（核心方法）
     * 
     * 双向持仓模式：
     * - 同时更新LONG和SHORT两个方向的持仓估值
     * 
     * @param markPrice 标记价格事件
     */
    void onMarkPrice(MarkPriceEvent markPrice);
    
    /**
     * 查询用户持仓快照（双向持仓模式）
     * 
     * 🔥 必须指定持仓方向（1=LONG, 2=SHORT）
     * 
     * 查询顺序：
     * 1. 先查Redis
     * 2. Redis Miss则查MySQL
     * 3. 回写Redis
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param positionSide 持仓方向（1=LONG, 2=SHORT）
     * @return PositionSnapshot
     */
    PositionSnapshot queryPosition(Long userId, String symbol, Integer positionSide);
    
    /**
     * 查询用户在某个交易对上的所有持仓（双向持仓模式）
     * 
     * 可能返回：
     * - 空列表（无持仓）
     * - 1条记录（只有多头或只有空头）
     * - 2条记录（同时持有多头和空头）
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @return List<PositionSnapshot>
     */
    List<PositionSnapshot> queryPositionsBySymbol(Long userId, String symbol);
    
    /**
     * 查询用户所有持仓（双向持仓模式）
     * 
     * @param userId 用户ID
     * @return List<PositionSnapshot> 包含所有方向的持仓
     */
    List<PositionSnapshot> queryAllPositions(Long userId);
    
    /**
     * 查询用户的所有多头持仓
     * 
     * @param userId 用户ID
     * @return List<PositionSnapshot> LONG持仓列表
     */
    List<PositionSnapshot> queryLongPositions(Long userId);
    
    /**
     * 查询用户的所有空头持仓
     * 
     * @param userId 用户ID
     * @return List<PositionSnapshot> SHORT持仓列表
     */
    List<PositionSnapshot> queryShortPositions(Long userId);
    
    /**
     * 计算未实现盈亏（双向持仓模式）
     * 
     * 多头：(markPrice - entryPrice) * size
     * 空头：(entryPrice - markPrice) * size
     * 
     * @param position 持仓快照
     * @param markPrice 标记价格
     * @return 未实现盈亏
     */
    BigDecimal calculateUnrealizedPnl(PositionSnapshot position, BigDecimal markPrice);
    
    /**
     * 计算保证金率
     * 
     * marginRatio = equity / maintenanceMargin
     * 
     * @param position 持仓快照
     * @param equity 权益
     * @param maintenanceMarginRate 维持保证金率
     * @return 保证金率
     */
    BigDecimal calculateMarginRatio(PositionSnapshot position, BigDecimal equity, BigDecimal maintenanceMarginRate);
    
    /**
     * 计算强平价
     * 
     * 多头：entryPrice - (equity - maintenanceMargin) / size
     * 空头：entryPrice + (equity - maintenanceMargin) / size
     * 
     * @param position 持仓快照
     * @param equity 权益
     * @param maintenanceMarginRate 维持保证金率
     * @return 强平价
     */
    BigDecimal calculateLiquidationPrice(PositionSnapshot position, BigDecimal equity, BigDecimal maintenanceMarginRate);
    
    /**
     * 更新Redis快照（双向持仓模式）
     * 
     * @param snapshot 持仓快照
     */
    void updateRedisSnapshot(PositionSnapshot snapshot);
    
    /**
     * 从Redis获取快照（双向持仓模式）
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param positionSide 持仓方向（1=LONG, 2=SHORT）
     * @return PositionSnapshot or null
     */
    PositionSnapshot getFromRedis(Long userId, String symbol, Integer positionSide);
    
    /**
     * 计算净持仓（双向持仓模式）
     * 
     * netPosition = LONG - SHORT
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 净持仓（正数=净多头，负数=净空头，0=对冲）
     */
    BigDecimal calculateNetPosition(Long userId, String symbol);
}
