package com.exchange.margin.service.impl;

import com.exchange.margin.calculator.MarginCalculator;
import com.exchange.margin.client.AccountServiceClient;
import com.exchange.margin.client.LedgerServiceClient;
import com.exchange.margin.client.MarkPriceServiceClient;
import com.exchange.margin.client.OrderServiceClient;
import com.exchange.margin.client.PositionServiceClient;
import com.exchange.margin.entity.CrossMarginSnapshot;
import com.exchange.margin.entity.PositionMarginDetail;
import com.exchange.margin.enums.RiskLevel;
import com.exchange.margin.mapper.CrossMarginSnapshotMapper;
import com.exchange.margin.mapper.PositionMarginDetailMapper;
import com.exchange.margin.producer.MarginChangeProducer;
import com.exchange.margin.service.MarginModeService;
import com.exchange.margin.util.DistributedLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 保证金模式服务实现（生产级）
 */
@Slf4j
@Service
public class MarginModeServiceImpl implements MarginModeService {

    @Autowired
    private PositionMarginDetailMapper positionMarginDetailMapper;

    @Autowired
    private CrossMarginSnapshotMapper crossMarginSnapshotMapper;

    @Autowired
    private MarginCalculator marginCalculator;

    @Autowired
    private AccountServiceClient accountServiceClient;

    @Autowired
    private PositionServiceClient positionServiceClient;

    @Autowired
    private MarkPriceServiceClient markPriceServiceClient;

    @Autowired
    private LedgerServiceClient ledgerServiceClient;

    @Autowired
    private OrderServiceClient orderServiceClient;

    @Autowired
    private DistributedLockUtil distributedLockUtil;

    @Autowired
    private MarginChangeProducer marginChangeProducer;

    @Override
    @Transactional
    public PositionMarginDetail createPositionMargin(Long userId, Long positionId, String symbol,
                                                      Integer side, String marginMode,
                                                      Integer leverage, Long isolatedMargin) {
        log.info("[MarginModeService] Create position margin, userId={}, positionId={}, " +
                        "symbol={}, marginMode={}, leverage={}, isolatedMargin={}",
                userId, positionId, symbol, marginMode, leverage, isolatedMargin);

        PositionMarginDetail detail = new PositionMarginDetail();
        detail.setPositionId(positionId);
        detail.setUserId(userId);
        detail.setSymbol(symbol);
        detail.setSide(side);
        detail.setMarginMode(marginMode);
        detail.setLeverage(leverage);
        detail.setIsolatedMargin(isolatedMargin);
        detail.setPositionMargin(isolatedMargin);

        positionMarginDetailMapper.insert(detail);

        // 逐仓模式需要记账
        if ("ISOLATED".equalsIgnoreCase(marginMode) && isolatedMargin != null && isolatedMargin > 0) {
            boolean ledgerSuccess = ledgerServiceClient.recordIsolatedOpen(
                    userId, positionId, symbol, isolatedMargin
            );
            if (!ledgerSuccess) {
                log.warn("[MarginModeService] Ledger recording failed, but position margin created, " +
                        "positionId={}", positionId);
            }
        }

        // 发布开仓事件
        marginChangeProducer.publishOpenEvent(detail);

        log.info("[MarginModeService] Position margin created, positionId={}", positionId);
        return detail;
    }
    
    @Override
    public PositionMarginDetail getPositionMargin(Long positionId) {
        return positionMarginDetailMapper.selectById(positionId);
    }
    
    @Override
    public List<PositionMarginDetail> getUserPositionMargins(Long userId) {
        return positionMarginDetailMapper.selectByUserId(userId);
    }
    
    @Override
    public List<PositionMarginDetail> getUserCrossPositions(Long userId) {
        return positionMarginDetailMapper.selectByUserIdAndMode(userId, "CROSS");
    }
    
    @Override
    public List<PositionMarginDetail> getUserIsolatedPositions(Long userId) {
        return positionMarginDetailMapper.selectByUserIdAndMode(userId, "ISOLATED");
    }
    
    @Override
    @Transactional
    public void deletePositionMargin(Long positionId) {
        log.info("[MarginModeService] Delete position margin, positionId={}", positionId);

        // 查询仓位保证金详情
        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position margin not found, positionId={}", positionId);
            return;
        }

        // 保存平仓前保证金用于事件发布
        Long beforeMargin = detail.getIsolatedMargin() != null ? detail.getIsolatedMargin() : 0L;

        // 逐仓模式需要记账（归还保证金）
        if (detail.isIsolated() && detail.getIsolatedMargin() != null &&
                detail.getIsolatedMargin() > 0) {
            Long realizedPnl = detail.getRealizedPnl() != null ? detail.getRealizedPnl() : 0L;
            boolean ledgerSuccess = ledgerServiceClient.recordIsolatedClose(
                    detail.getUserId(),
                    positionId,
                    detail.getSymbol(),
                    detail.getIsolatedMargin(),
                    realizedPnl
            );
            if (!ledgerSuccess) {
                log.warn("[MarginModeService] Ledger recording failed for position close, " +
                        "positionId={}", positionId);
            }
        }

        // 发布平仓事件
        marginChangeProducer.publishCloseEvent(detail, beforeMargin);

        // 删除仓位保证金详情
        positionMarginDetailMapper.deleteById(positionId);

        log.info("[MarginModeService] Position margin deleted, positionId={}", positionId);
    }
    
    @Override
    @Transactional
    public PositionMarginDetail switchMarginMode(Long positionId, String targetMode, Long isolatedMargin) {
        log.info("[MarginModeService] Switch margin mode, positionId={}, targetMode={}, isolatedMargin={}",
                positionId, targetMode, isolatedMargin);

        // 先获取仓位信息以获取userId
        PositionMarginDetail tempDetail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (tempDetail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return null;
        }

        // 使用分布式锁（用户级锁）
        String lockKey = DistributedLockUtil.getUserLockKey(tempDetail.getUserId());
        return distributedLockUtil.executeWithLock(lockKey, () -> {
            return doSwitchMarginMode(positionId, targetMode, isolatedMargin);
        });
    }

    /**
     * 执行模式切换（内部方法，已被分布式锁保护）
     */
    private PositionMarginDetail doSwitchMarginMode(Long positionId, String targetMode, Long isolatedMargin) {
        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return null;
        }

        String currentMode = detail.getMarginMode();
        if (currentMode.equalsIgnoreCase(targetMode)) {
            log.warn("[MarginModeService] Already in target mode, positionId={}, mode={}",
                    positionId, targetMode);
            return detail;
        }

        // 检查是否可以切换
        if (!canSwitchMarginMode(positionId, targetMode)) {
            log.warn("[MarginModeService] Cannot switch margin mode, positionId={}, targetMode={}",
                    positionId, targetMode);
            return null;
        }

        // 保存切换前的状态用于事件发布
        String beforeMode = detail.getMarginMode();
        Long beforeMargin = detail.getIsolatedMargin() != null ? detail.getIsolatedMargin() : 0L;

        // 全仓转逐仓
        if ("ISOLATED".equalsIgnoreCase(targetMode)) {
            if (isolatedMargin == null || isolatedMargin <= 0) {
                log.warn("[MarginModeService] Invalid isolated margin, positionId={}, isolatedMargin={}",
                        positionId, isolatedMargin);
                return null;
            }

            // 检查用户账户余额是否充足
            Long availableBalance = getAvailableMargin(detail.getUserId(), "ISOLATED");
            if (availableBalance < isolatedMargin) {
                log.warn("[MarginModeService] Insufficient balance to switch to isolated, " +
                                "userId={}, required={}, available={}",
                        detail.getUserId(), isolatedMargin, availableBalance);
                return null;
            }

            detail.setMarginMode("ISOLATED");
            detail.setIsolatedMargin(isolatedMargin);
            detail.setAddedMargin(isolatedMargin);

            log.info("[MarginModeService] Switched from CROSS to ISOLATED, positionId={}, isolatedMargin={}",
                    positionId, isolatedMargin);
        }
        // 逐仓转全仓
        else if ("CROSS".equalsIgnoreCase(targetMode)) {
            Long returnMargin = detail.getIsolatedMargin();

            detail.setMarginMode("CROSS");
            detail.setIsolatedMargin(0L);
            detail.setIsolatedAvailable(0L);
            detail.setAddedMargin(0L);
            detail.setReducedMargin(0L);

            log.info("[MarginModeService] Switched from ISOLATED to CROSS, positionId={}, returnMargin={}",
                    positionId, returnMargin);
        }

        // 重新计算强平价格
        detail = calculatePositionMargin(positionId, null);

        // 更新数据库
        positionMarginDetailMapper.updateById(detail);

        // Ledger记账
        if ("ISOLATED".equalsIgnoreCase(targetMode)) {
            // 全仓转逐仓：扣除可用余额，转入逐仓保证金
            ledgerServiceClient.recordSwitchToIsolated(
                    detail.getUserId(),
                    positionId,
                    detail.getSymbol(),
                    isolatedMargin
            );
        } else if ("CROSS".equalsIgnoreCase(targetMode)) {
            // 逐仓转全仓：归还逐仓保证金到可用余额
            Long returnMargin = detail.getIsolatedMargin();
            if (returnMargin != null && returnMargin > 0) {
                ledgerServiceClient.recordSwitchToCross(
                        detail.getUserId(),
                        positionId,
                        detail.getSymbol(),
                        returnMargin
                );
            }
        }

        // 发布模式切换事件
        Long afterMargin = detail.getIsolatedMargin() != null ? detail.getIsolatedMargin() : 0L;
        marginChangeProducer.publishSwitchEvent(detail, beforeMode, beforeMargin, afterMargin);

        log.info("[MarginModeService] Margin mode switched, positionId={}, newMode={}",
                positionId, targetMode);

        return detail;
    }

    @Override
    public boolean canSwitchMarginMode(Long positionId, String targetMode) {
        log.info("[MarginModeService] Check can switch margin mode, positionId={}, targetMode={}",
                positionId, targetMode);

        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            return false;
        }

        // 检查是否有挂单（有挂单时禁止切换）
        boolean hasOpenOrders = orderServiceClient.hasOpenOrders(
                detail.getUserId(), detail.getSymbol()
        );
        if (hasOpenOrders) {
            log.warn("[MarginModeService] Cannot switch mode when user has open orders, " +
                            "userId={}, symbol={}",
                    detail.getUserId(), detail.getSymbol());
            return false;
        }

        // 检查风险：切换后不能立即触发强平
        if ("CROSS".equalsIgnoreCase(targetMode)) {
            // 逐仓转全仓：检查账户保证金率
            CrossMarginSnapshot snapshot = getCrossMarginSnapshot(detail.getUserId());
            if (snapshot != null && snapshot.getMarginRatio() != null) {
                // 保证金率必须 > 维持保证金率 × 1.5
                Long safeThreshold = (MarginCalculator.DEFAULT_MAINTENANCE_MARGIN_RATE * 15) / 10;
                if (snapshot.getMarginRatio() < safeThreshold) {
                    log.warn("[MarginModeService] Risk too high to switch to CROSS, " +
                                    "userId={}, marginRatio={}",
                            detail.getUserId(), snapshot.getMarginRatio());
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    @Transactional
    public PositionMarginDetail addIsolatedMargin(Long positionId, Long amount) {
        log.info("[MarginModeService] Add isolated margin, positionId={}, amount={}",
                positionId, amount);

        if (amount == null || amount <= 0) {
            log.warn("[MarginModeService] Invalid amount, positionId={}, amount={}", positionId, amount);
            return null;
        }

        // 先获取仓位信息以获取userId
        PositionMarginDetail tempDetail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (tempDetail == null || !tempDetail.isIsolated()) {
            log.warn("[MarginModeService] Position not found or not isolated, positionId={}",
                    positionId);
            return null;
        }

        // 使用分布式锁（用户级锁）
        String lockKey = DistributedLockUtil.getUserLockKey(tempDetail.getUserId());
        return distributedLockUtil.executeWithLock(lockKey, () -> {
            return doAddIsolatedMargin(positionId, amount);
        });
    }

    /**
     * 执行追加逐仓保证金（内部方法，已被分布式锁保护）
     */
    private PositionMarginDetail doAddIsolatedMargin(Long positionId, Long amount) {
        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null || !detail.isIsolated()) {
            log.warn("[MarginModeService] Position not found or not isolated, positionId={}",
                    positionId);
            return null;
        }

        // 检查用户账户余额是否充足
        Long availableBalance = getAvailableMargin(detail.getUserId(), "ISOLATED");
        if (availableBalance < amount) {
            log.warn("[MarginModeService] Insufficient balance, userId={}, required={}, available={}",
                    detail.getUserId(), amount, availableBalance);
            return null;
        }

        // 保存追加前的保证金用于事件发布
        Long beforeMargin = detail.getIsolatedMargin() != null ? detail.getIsolatedMargin() : 0L;

        // 追加保证金
        Long currentMargin = detail.getIsolatedMargin() != null ? detail.getIsolatedMargin() : 0L;
        Long newMargin = currentMargin + amount;
        detail.setIsolatedMargin(newMargin);

        Long addedMargin = detail.getAddedMargin() != null ? detail.getAddedMargin() : 0L;
        detail.setAddedMargin(addedMargin + amount);

        // 重新计算强平价格
        detail = calculatePositionMargin(positionId, null);

        // 更新数据库
        int updated = positionMarginDetailMapper.updateIsolatedMargin(
                positionId,
                detail.getIsolatedMargin(),
                detail.getAddedMargin(),
                detail.getReducedMargin(),
                detail.getPositionMargin(),
                detail.getLiquidationPrice(),
                System.currentTimeMillis(),
                detail.getVersion()
        );

        if (updated == 0) {
            log.warn("[MarginModeService] Update isolated margin failed (version conflict), positionId={}",
                    positionId);
            return null;
        }

        // Ledger记账：扣除可用余额，增加逐仓保证金
        boolean ledgerSuccess = ledgerServiceClient.recordAddIsolatedMargin(
                detail.getUserId(),
                positionId,
                detail.getSymbol(),
                amount
        );
        if (!ledgerSuccess) {
            log.warn("[MarginModeService] Ledger recording failed for add margin, positionId={}",
                    positionId);
        }

        // 发布追加保证金事件
        marginChangeProducer.publishAddMarginEvent(detail, beforeMargin, amount);

        log.info("[MarginModeService] Add isolated margin success, positionId={}, newMargin={}, newLiqPrice={}",
                positionId, newMargin, detail.getLiquidationPrice());

        return detail;
    }

    @Override
    @Transactional
    public PositionMarginDetail reduceIsolatedMargin(Long positionId, Long amount) {
        log.info("[MarginModeService] Reduce isolated margin, positionId={}, amount={}",
                positionId, amount);

        if (amount == null || amount <= 0) {
            log.warn("[MarginModeService] Invalid amount, positionId={}, amount={}", positionId, amount);
            return null;
        }

        // 先获取仓位信息以获取userId
        PositionMarginDetail tempDetail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (tempDetail == null || !tempDetail.isIsolated()) {
            log.warn("[MarginModeService] Position not found or not isolated, positionId={}",
                    positionId);
            return null;
        }

        // 使用分布式锁（用户级锁）
        String lockKey = DistributedLockUtil.getUserLockKey(tempDetail.getUserId());
        return distributedLockUtil.executeWithLock(lockKey, () -> {
            return doReduceIsolatedMargin(positionId, amount);
        });
    }

    /**
     * 执行减少逐仓保证金（内部方法，已被分布式锁保护）
     */
    private PositionMarginDetail doReduceIsolatedMargin(Long positionId, Long amount) {
        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null || !detail.isIsolated()) {
            log.warn("[MarginModeService] Position not found or not isolated, positionId={}",
                    positionId);
            return null;
        }

        // 计算最大可取出保证金
        Long maxRemovable = marginCalculator.calculateMaxRemovableMargin(detail);
        if (amount > maxRemovable) {
            log.warn("[MarginModeService] Amount exceeds max removable, positionId={}, " +
                            "amount={}, maxRemovable={}",
                    positionId, amount, maxRemovable);
            return null;
        }

        // 保存减少前的保证金用于事件发布
        Long beforeMargin = detail.getIsolatedMargin();

        // 减少保证金
        Long currentMargin = detail.getIsolatedMargin();
        Long newMargin = currentMargin - amount;
        detail.setIsolatedMargin(newMargin);

        Long reducedMargin = detail.getReducedMargin() != null ? detail.getReducedMargin() : 0L;
        detail.setReducedMargin(reducedMargin + amount);

        // 重新计算强平价格
        detail = calculatePositionMargin(positionId, null);

        // 更新数据库
        int updated = positionMarginDetailMapper.updateIsolatedMargin(
                positionId,
                detail.getIsolatedMargin(),
                detail.getAddedMargin(),
                detail.getReducedMargin(),
                detail.getPositionMargin(),
                detail.getLiquidationPrice(),
                System.currentTimeMillis(),
                detail.getVersion()
        );

        if (updated == 0) {
            log.warn("[MarginModeService] Update isolated margin failed (version conflict), positionId={}",
                    positionId);
            return null;
        }

        // Ledger记账：减少逐仓保证金，增加可用余额
        boolean ledgerSuccess = ledgerServiceClient.recordReduceIsolatedMargin(
                detail.getUserId(),
                positionId,
                detail.getSymbol(),
                amount
        );
        if (!ledgerSuccess) {
            log.warn("[MarginModeService] Ledger recording failed for reduce margin, positionId={}",
                    positionId);
        }

        // 发布减少保证金事件
        marginChangeProducer.publishReduceMarginEvent(detail, beforeMargin, amount);

        log.info("[MarginModeService] Reduce isolated margin success, positionId={}, newMargin={}, newLiqPrice={}",
                positionId, newMargin, detail.getLiquidationPrice());

        return detail;
    }

    @Override
    @Transactional
    public PositionMarginDetail adjustLeverage(Long positionId, Integer newLeverage) {
        log.info("[MarginModeService] Adjust leverage, positionId={}, newLeverage={}",
                positionId, newLeverage);

        if (newLeverage == null || newLeverage < 1 || newLeverage > 125) {
            log.warn("[MarginModeService] Invalid leverage, positionId={}, leverage={}",
                    positionId, newLeverage);
            return null;
        }

        // 先获取仓位信息以获取userId
        PositionMarginDetail tempDetail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (tempDetail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return null;
        }

        // 使用分布式锁（用户级锁）
        String lockKey = DistributedLockUtil.getUserLockKey(tempDetail.getUserId());
        return distributedLockUtil.executeWithLock(lockKey, () -> {
            return doAdjustLeverage(positionId, newLeverage);
        });
    }

    /**
     * 执行调整杠杆（内部方法，已被分布式锁保护）
     */
    private PositionMarginDetail doAdjustLeverage(Long positionId, Integer newLeverage) {
        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return null;
        }

        Integer oldLeverage = detail.getLeverage();
        if (oldLeverage.equals(newLeverage)) {
            log.info("[MarginModeService] Leverage unchanged, positionId={}, leverage={}",
                    positionId, newLeverage);
            return detail;
        }

        // 检查是否有挂单（有挂单时禁止调整杠杆）
        boolean hasOpenOrders = orderServiceClient.hasOpenOrders(
                detail.getUserId(), detail.getSymbol()
        );
        if (hasOpenOrders) {
            log.warn("[MarginModeService] Cannot adjust leverage when user has open orders, " +
                            "userId={}, symbol={}",
                    detail.getUserId(), detail.getSymbol());
            return null;
        }

        // 仅逐仓模式支持调整杠杆
        if (!detail.isIsolated()) {
            log.warn("[MarginModeService] Cannot adjust leverage in CROSS mode, positionId={}",
                    positionId);
            return null;
        }

        // 保存调整前的状态用于事件发布
        Integer beforeLeverage = detail.getLeverage();
        Long beforeMargin = detail.getPositionMargin();
        Long beforeLiqPrice = detail.getLiquidationPrice();
        Long beforeMarginRatio = detail.getMarginRatio();

        detail.setLeverage(newLeverage);

        // 重新计算保证金和强平价格
        detail = calculatePositionMargin(positionId, null);

        // 检查新杠杆下不会立即触发强平
        if (detail.needsLiquidation()) {
            log.warn("[MarginModeService] New leverage will trigger liquidation, positionId={}, newLeverage={}",
                    positionId, newLeverage);
            return null;
        }

        // 更新数据库
        int updated = positionMarginDetailMapper.updateLeverage(
                positionId,
                newLeverage,
                detail.getPositionMargin(),
                detail.getLiquidationPrice(),
                System.currentTimeMillis(),
                detail.getVersion()
        );

        if (updated == 0) {
            log.warn("[MarginModeService] Update leverage failed (version conflict), positionId={}",
                    positionId);
            return null;
        }

        // 发布杠杆调整事件
        marginChangeProducer.publishLeverageAdjustEvent(
                detail, beforeLeverage, beforeMargin, beforeLiqPrice, beforeMarginRatio
        );

        log.info("[MarginModeService] Adjust leverage success, positionId={}, oldLeverage={}, " +
                        "newLeverage={}, newLiqPrice={}",
                positionId, oldLeverage, newLeverage, detail.getLiquidationPrice());

        return detail;
    }
    
    @Override
    public PositionMarginDetail calculatePositionMargin(Long positionId, Long currentPrice) {
        log.info("[MarginModeService] Calculate position margin, positionId={}, currentPrice={}",
                positionId, currentPrice);

        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return null;
        }

        // 查询仓位信息
        PositionServiceClient.PositionInfo position = positionServiceClient.getPosition(positionId);
        if (position == null) {
            log.warn("[MarginModeService] Position info not found from position service, positionId={}",
                    positionId);
            return detail;
        }

        // 使用传入的价格或标记价格
        Long markPrice = currentPrice != null ? currentPrice : position.getMarkPrice();

        // 计算仓位价值
        Long positionValue = marginCalculator.calculatePositionValue(
                position.getPositionQty(), markPrice);
        detail.setPositionValue(positionValue);
        detail.setMarkPrice(markPrice);

        // 计算未实现盈亏
        Long unrealizedPnl = marginCalculator.calculateUnrealizedPnl(
                position.getSide(),
                position.getPositionQty(),
                position.getEntryPrice(),
                markPrice
        );
        detail.setUnrealizedPnl(unrealizedPnl);

        // 计算保证金
        Long positionMargin = marginCalculator.calculatePositionMargin(
                positionValue,
                detail.getLeverage(),
                detail.getMarginMode(),
                detail.getIsolatedMargin()
        );
        detail.setPositionMargin(positionMargin);

        // 计算维持保证金
        Long maintMargin = marginCalculator.calculateMaintenanceMargin(
                positionValue,
                detail.getMaintMarginRate()
        );
        detail.setMaintMargin(maintMargin);

        // 逐仓模式：计算保证金率
        if (detail.isIsolated()) {
            Long marginRatio = marginCalculator.calculateMarginRatio(
                    detail.getIsolatedMargin(),
                    positionValue
            );
            detail.setMarginRatio(marginRatio);
        }

        // 计算强平价格
        Long liquidationPrice = marginCalculator.calculateLiquidationPrice(detail);
        detail.setLiquidationPrice(liquidationPrice);

        // 计算破产价格
        Long bankruptcyPrice = marginCalculator.calculateBankruptcyPrice(detail);
        detail.setBankruptcyPrice(bankruptcyPrice);

        return detail;
    }

    @Override
    public Long calculateLiquidationPrice(Long positionId) {
        log.info("[MarginModeService] Calculate liquidation price, positionId={}", positionId);

        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return 0L;
        }

        return marginCalculator.calculateLiquidationPrice(detail);
    }

    @Override
    public Long calculateBankruptcyPrice(Long positionId) {
        log.info("[MarginModeService] Calculate bankruptcy price, positionId={}", positionId);

        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null) {
            log.warn("[MarginModeService] Position not found, positionId={}", positionId);
            return 0L;
        }

        return marginCalculator.calculateBankruptcyPrice(detail);
    }

    @Override
    @Transactional
    public void updatePositionMarginByPrice(Long positionId, Long markPrice) {
        log.info("[MarginModeService] Update position margin by price, positionId={}, markPrice={}",
                positionId, markPrice);

        PositionMarginDetail detail = calculatePositionMargin(positionId, markPrice);
        if (detail == null) {
            return;
        }

        // 更新数据库（乐观锁）
        int updated = positionMarginDetailMapper.updatePositionMargin(
                positionId,
                detail.getPositionValue(),
                detail.getPositionMargin(),
                detail.getUnrealizedPnl(),
                detail.getLiquidationPrice(),
                detail.getMarginRatio(),
                markPrice,
                System.currentTimeMillis(),
                detail.getVersion()
        );

        if (updated == 0) {
            log.warn("[MarginModeService] Update position margin failed (version conflict), positionId={}",
                    positionId);
        } else {
            log.info("[MarginModeService] Update position margin success, positionId={}, marginRatio={}",
                    positionId, detail.getMarginRatio());
        }
    }
    
    @Override
    public CrossMarginSnapshot getOrCreateCrossSnapshot(Long userId) {
        log.info("[MarginModeService] Get or create cross snapshot, userId={}", userId);

        CrossMarginSnapshot snapshot = crossMarginSnapshotMapper.selectByUserId(userId);
        if (snapshot == null) {
            // 创建新快照
            snapshot = new CrossMarginSnapshot();
            snapshot.setUserId(userId);
            snapshot.setWalletBalance(0L);
            snapshot.setAvailableBalance(0L);
            snapshot.setFrozenBalance(0L);
            snapshot.setUsedMargin(0L);
            snapshot.setTotalPositionValue(0L);
            snapshot.setLongPositionValue(0L);
            snapshot.setShortPositionValue(0L);
            snapshot.setPositionCount(0);
            snapshot.setTotalUnrealizedPnl(0L);
            snapshot.setTodayRealizedPnl(0L);
            snapshot.setTotalMaintenanceMargin(0L);
            snapshot.setInitialMarginRequirement(0L);
            snapshot.setMaintenanceMarginRate(0);
            snapshot.setMarginBalance(0L);
            snapshot.setMarginRatio(MarginCalculator.RATIO_BASE); // 默认100%
            snapshot.setAvailableMargin(0L);
            snapshot.setMaxOpenPositionValue(0L);
            snapshot.setRiskLevel(RiskLevel.SAFE.getCode());
            snapshot.setRiskLevelName(RiskLevel.SAFE.getName());
            snapshot.setCanTrade(true);
            snapshot.setCanWithdraw(true);
            snapshot.setEstimatedLiquidationPrice(0L);
            snapshot.setLiquidationGap(0L);
            snapshot.setLiquidationStatus(0);
            snapshot.setLiquidationStartTime(0L);
            snapshot.setCreatedAt(System.currentTimeMillis());
            snapshot.setUpdatedAt(System.currentTimeMillis());
            snapshot.setVersion(0);

            crossMarginSnapshotMapper.insert(snapshot);
            log.info("[MarginModeService] Created new cross snapshot, userId={}", userId);
        }

        return snapshot;
    }

    @Override
    public CrossMarginSnapshot getCrossMarginSnapshot(Long userId) {
        log.info("[MarginModeService] Get cross snapshot, userId={}", userId);
        return crossMarginSnapshotMapper.selectByUserId(userId);
    }

    @Override
    @Transactional
    public CrossMarginSnapshot calculateCrossSnapshot(Long userId) {
        log.info("[MarginModeService] Calculate cross snapshot, userId={}", userId);

        // 使用分布式锁（用户级锁）
        String lockKey = DistributedLockUtil.getUserLockKey(userId);
        return distributedLockUtil.executeWithLock(lockKey, () -> {
            return doCalculateCrossSnapshot(userId);
        });
    }

    /**
     * 执行计算全仓快照（内部方法，已被分布式锁保护）
     */
    private CrossMarginSnapshot doCalculateCrossSnapshot(Long userId) {
        // 获取或创建快照
        CrossMarginSnapshot snapshot = getOrCreateCrossSnapshot(userId);

        // 查询账户余额
        AccountServiceClient.AccountBalance accountBalance =
                accountServiceClient.getAccountBalance(userId);
        if (accountBalance != null) {
            snapshot.setWalletBalance(accountBalance.getWalletBalance());
            snapshot.setAvailableBalance(accountBalance.getAvailableBalance());
            snapshot.setFrozenBalance(accountBalance.getFrozenBalance());
            snapshot.setTodayRealizedPnl(accountBalance.getTodayRealizedPnl());
        }

        // 查询用户的全仓仓位
        List<PositionMarginDetail> crossPositions = getUserCrossPositions(userId);

        if (crossPositions == null || crossPositions.isEmpty()) {
            // 没有全仓仓位，重置为默认值
            snapshot.setTotalPositionValue(0L);
            snapshot.setLongPositionValue(0L);
            snapshot.setShortPositionValue(0L);
            snapshot.setPositionCount(0);
            snapshot.setTotalUnrealizedPnl(0L);
            snapshot.setTotalMaintenanceMargin(0L);
            snapshot.setMarginBalance(snapshot.getWalletBalance());
            snapshot.setMarginRatio(MarginCalculator.RATIO_BASE); // 100%
            snapshot.setAvailableMargin(snapshot.getAvailableBalance());
            snapshot.setRiskLevel(RiskLevel.SAFE.getCode());
            snapshot.setRiskLevelName(RiskLevel.SAFE.getName());
            snapshot.setCanTrade(true);
            snapshot.setCanWithdraw(true);
        } else {
            // 聚合所有全仓仓位数据
            long totalPositionValue = 0L;
            long longPositionValue = 0L;
            long shortPositionValue = 0L;
            long totalUnrealizedPnl = 0L;
            long totalMaintenanceMargin = 0L;

            for (PositionMarginDetail position : crossPositions) {
                // 更新仓位保证金信息（基于最新标记价格）
                PositionMarginDetail updated = calculatePositionMargin(
                        position.getPositionId(), null);

                if (updated != null) {
                    totalPositionValue += (updated.getPositionValue() != null ?
                            updated.getPositionValue() : 0L);

                    if (updated.isLong()) {
                        longPositionValue += (updated.getPositionValue() != null ?
                                updated.getPositionValue() : 0L);
                    } else if (updated.isShort()) {
                        shortPositionValue += (updated.getPositionValue() != null ?
                                updated.getPositionValue() : 0L);
                    }

                    totalUnrealizedPnl += (updated.getUnrealizedPnl() != null ?
                            updated.getUnrealizedPnl() : 0L);
                    totalMaintenanceMargin += (updated.getMaintMargin() != null ?
                            updated.getMaintMargin() : 0L);
                }
            }

            snapshot.setTotalPositionValue(totalPositionValue);
            snapshot.setLongPositionValue(longPositionValue);
            snapshot.setShortPositionValue(shortPositionValue);
            snapshot.setPositionCount(crossPositions.size());
            snapshot.setTotalUnrealizedPnl(totalUnrealizedPnl);
            snapshot.setTotalMaintenanceMargin(totalMaintenanceMargin);

            // 计算保证金余额 = 钱包余额 + 未实现盈亏
            long marginBalance = snapshot.getWalletBalance() + totalUnrealizedPnl;
            snapshot.setMarginBalance(marginBalance);

            // 计算保证金率 = 保证金余额 / 总仓位价值 * 10000
            long marginRatio = totalPositionValue > 0 ?
                    (marginBalance * MarginCalculator.RATIO_BASE) / totalPositionValue :
                    MarginCalculator.RATIO_BASE;
            snapshot.setMarginRatio(marginRatio);

            // 计算可用保证金 = 保证金余额 - 总维持保证金
            long availableMargin = marginBalance - totalMaintenanceMargin;
            snapshot.setAvailableMargin(Math.max(0, availableMargin));

            // 计算风险等级
            RiskLevel riskLevel = RiskLevel.fromMarginRatio(marginRatio);
            snapshot.setRiskLevel(riskLevel.getCode());
            snapshot.setRiskLevelName(riskLevel.getName());
            snapshot.setCanTrade(!riskLevel.needsLiquidation());
            snapshot.setCanWithdraw(riskLevel == RiskLevel.SAFE);

            // 计算强平缺口
            long liquidationThreshold = totalPositionValue > 0 ?
                    (MarginCalculator.DEFAULT_MAINTENANCE_MARGIN_RATE * totalPositionValue) /
                            MarginCalculator.RATIO_BASE : 0L;
            long liquidationGap = marginBalance - liquidationThreshold;
            snapshot.setLiquidationGap(liquidationGap);
        }

        snapshot.setUpdatedAt(System.currentTimeMillis());

        // 更新数据库
        int updated = crossMarginSnapshotMapper.updateSnapshot(
                userId,
                snapshot.getWalletBalance(),
                snapshot.getAvailableBalance(),
                snapshot.getFrozenBalance(),
                snapshot.getUsedMargin(),
                snapshot.getTotalPositionValue(),
                snapshot.getLongPositionValue(),
                snapshot.getShortPositionValue(),
                snapshot.getPositionCount(),
                snapshot.getTotalUnrealizedPnl(),
                snapshot.getTodayRealizedPnl(),
                snapshot.getTotalMaintenanceMargin(),
                snapshot.getInitialMarginRequirement(),
                snapshot.getMaintenanceMarginRate(),
                snapshot.getMarginBalance(),
                snapshot.getMarginRatio(),
                snapshot.getAvailableMargin(),
                snapshot.getMaxOpenPositionValue(),
                snapshot.getRiskLevel(),
                snapshot.getRiskLevelName(),
                snapshot.getCanTrade(),
                snapshot.getCanWithdraw(),
                snapshot.getEstimatedLiquidationPrice(),
                snapshot.getLiquidationGap(),
                snapshot.getUpdatedAt(),
                snapshot.getVersion()
        );

        if (updated == 0) {
            log.warn("[MarginModeService] Update cross snapshot failed (version conflict), userId={}",
                    userId);
        } else {
            log.info("[MarginModeService] Calculate cross snapshot success, userId={}, marginRatio={}",
                    userId, snapshot.getMarginRatio());
        }

        return snapshot;
    }

    @Override
    public List<CrossMarginSnapshot> batchCalculateCrossSnapshot(List<Long> userIds) {
        log.info("[MarginModeService] Batch calculate cross snapshot, userCount={}", userIds.size());

        List<CrossMarginSnapshot> snapshots = new ArrayList<>();
        for (Long userId : userIds) {
            try {
                CrossMarginSnapshot snapshot = calculateCrossSnapshot(userId);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (Exception e) {
                log.error("[MarginModeService] Failed to calculate cross snapshot, userId={}",
                        userId, e);
            }
        }

        return snapshots;
    }
    
    @Override
    public boolean checkLiquidationNeeded(Long positionId, Long currentPrice) {
        log.info("[MarginModeService] Check liquidation needed, positionId={}, currentPrice={}",
                positionId, currentPrice);

        PositionMarginDetail detail = positionMarginDetailMapper.selectByPositionId(positionId);
        if (detail == null || !detail.isIsolated()) {
            return false;
        }

        return marginCalculator.checkLiquidationNeeded(detail, currentPrice);
    }

    @Override
    public boolean checkAccountLiquidationNeeded(Long userId) {
        log.info("[MarginModeService] Check account liquidation needed, userId={}", userId);

        CrossMarginSnapshot snapshot = getCrossMarginSnapshot(userId);
        if (snapshot == null) {
            return false;
        }

        // 账户保证金率 <= 维持保证金率 × 1.2 触发强平
        Long liquidationThreshold = (MarginCalculator.DEFAULT_MAINTENANCE_MARGIN_RATE * 12) / 10;
        return snapshot.getMarginRatio() != null &&
                snapshot.getMarginRatio() <= liquidationThreshold;
    }

    @Override
    public List<PositionMarginDetail> getLiquidationCandidates() {
        log.info("[MarginModeService] Get liquidation candidates (positions)");

        // 查询保证金率低于维持保证金率的逐仓仓位
        Long threshold = MarginCalculator.DEFAULT_MAINTENANCE_MARGIN_RATE;
        return positionMarginDetailMapper.selectHighRiskIsolatedPositions(threshold);
    }

    @Override
    public List<CrossMarginSnapshot> getAccountLiquidationCandidates() {
        log.info("[MarginModeService] Get liquidation candidates (accounts)");

        // 查询保证金率低于阈值的账户
        return crossMarginSnapshotMapper.selectLiquidationCandidates();
    }

    @Override
    public Long getAvailableMargin(Long userId, String marginMode) {
        log.info("[MarginModeService] Get available margin, userId={}, marginMode={}",
                userId, marginMode);

        // 全仓模式：查询全仓快照的可用保证金
        if ("CROSS".equalsIgnoreCase(marginMode)) {
            CrossMarginSnapshot snapshot = getCrossMarginSnapshot(userId);
            return snapshot != null ? snapshot.getAvailableMargin() : 0L;
        }
        // 逐仓模式：查询账户可用余额
        else if ("ISOLATED".equalsIgnoreCase(marginMode)) {
            AccountServiceClient.AccountBalance balance =
                    accountServiceClient.getAccountBalance(userId);
            return balance != null ? balance.getAvailableBalance() : 0L;
        }

        return 0L;
    }

    @Override
    public boolean validateMarginSufficient(Long userId, String symbol, Integer side,
                                             String marginMode, Long requiredMargin) {
        log.info("[MarginModeService] Validate margin sufficient, userId={}, symbol={}, " +
                        "marginMode={}, requiredMargin={}",
                userId, symbol, marginMode, requiredMargin);

        if (requiredMargin == null || requiredMargin == 0) {
            return true;
        }

        Long availableMargin = getAvailableMargin(userId, marginMode);

        // 检查可用保证金是否充足
        return availableMargin >= requiredMargin;
    }

    @Override
    public List<CrossMarginSnapshot> getHighRiskAccounts(Long threshold) {
        log.info("[MarginModeService] Get high risk accounts, threshold={}", threshold);

        if (threshold == null || threshold == 0) {
            threshold = 1000L; // 默认10%
        }

        return crossMarginSnapshotMapper.selectHighRiskUsers(threshold);
    }

    @Override
    public Integer getAccountRiskLevel(Long userId) {
        log.info("[MarginModeService] Get account risk level, userId={}", userId);

        CrossMarginSnapshot snapshot = getCrossMarginSnapshot(userId);
        if (snapshot == null) {
            return RiskLevel.SAFE.getCode();
        }

        return snapshot.getRiskLevel();
    }

    @Override
    public Long getAccountMarginRatio(Long userId) {
        log.info("[MarginModeService] Get account margin ratio, userId={}", userId);

        CrossMarginSnapshot snapshot = getCrossMarginSnapshot(userId);
        if (snapshot == null) {
            return MarginCalculator.RATIO_BASE; // 默认100%
        }

        return snapshot.getMarginRatio();
    }
}
