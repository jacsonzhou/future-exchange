package com.exchange.binance.manager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 订单簿管理器
 *
 * 职责：
 * - 维护本地订单簿（增量更新）
 * - 序号连续性检查（防止数据丢失）
 * - 触发快照重建
 * - 提供BBO查询
 *
 * 设计原则：
 * - 单线程操作（每个symbol一个实例）
 * - 序号零容忍（检测到Gap立即重建）
 * - 状态机管理（INIT → ACTIVE ⇄ STALE/REBUILDING）
 */
@Slf4j
public class OrderBookManager {

    @Getter
    private final String symbol;

    /**
     * 订单簿状态
     * INIT: 初始化状态，等待首次快照
     * ACTIVE: 正常运行，序号连续
     * STALE: 检测到Gap，数据可能不准
     * REBUILDING: 正在重建中
     */
    @Getter
    private volatile OrderBookStatus status = OrderBookStatus.INIT;

    /**
     * 最后处理的更新序号
     */
    @Getter
    private volatile long lastUpdateId = 0;

    /**
     * 买盘：价格 -> 数量（降序排列）
     * 使用 ConcurrentSkipListMap 支持并发读取
     */
    private final ConcurrentSkipListMap<Long, Long> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /**
     * 卖盘：价格 -> 数量（升序排列）
     */
    private final ConcurrentSkipListMap<Long, Long> asks =
            new ConcurrentSkipListMap<>();

    /**
     * 重建期间缓存的增量更新
     */
    private final List<DepthUpdate> bufferedUpdates =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 统计指标
     */
    private long updateCount = 0;
    private long gapCount = 0;
    private long rebuildCount = 0;
    private long priceAnomalyCount = 0;
    private long staleUpdateCount = 0;

    /**
     * 最大缓存深度（防止内存溢出）
     */
    private static final int MAX_DEPTH = 1000;

    /**
     * 重建期间最大增量缓存条数
     */
    private static final int MAX_BUFFERED_UPDATES = 20_000;

    public OrderBookManager(String symbol) {
        this.symbol = symbol;
        log.info("[OrderBook-{}] Initialized", symbol);
    }

    /**
     * 处理深度更新
     *
     * 币安序号规则：
     * - U: firstUpdateId（本次更新的第一个序号）
     * - u: lastUpdateId（本次更新的最后一个序号）
     * - 正常情况：新消息的 U <= lastUpdateId + 1 && u >= lastUpdateId + 1
     *
     * @return true=更新成功，false=需要重建
     */
    public synchronized boolean onDepthUpdate(long firstUpdateId, long lastUpdateId,
                                              List<long[]> bidUpdates,
                                              List<long[]> askUpdates) {
        updateCount++;

        // Case 1: 初始化状态，需要先获取快照
        if (status == OrderBookStatus.INIT) {
            log.warn("[OrderBook-{}] Received update in INIT state (U={}, u={}), need snapshot first",
                    symbol, firstUpdateId, lastUpdateId);
            return false;  // 触发重建
        }

        // Case 2: 重建中，缓存更新
        if (status == OrderBookStatus.REBUILDING) {
            if (bufferedUpdates.size() >= MAX_BUFFERED_UPDATES) {
                // 丢弃最旧的一条，避免重建期间无限堆积
                bufferedUpdates.remove(0);
            }
            bufferedUpdates.add(new DepthUpdate(firstUpdateId, lastUpdateId, bidUpdates, askUpdates));
            log.debug("[OrderBook-{}] Buffered update during rebuilding: U={}, u={}, buffered={}",
                    symbol, firstUpdateId, lastUpdateId, bufferedUpdates.size());
            return false;  // 不发布数据
        }

        // Case 3: 序号检查
        if (firstUpdateId <= this.lastUpdateId + 1 && lastUpdateId >= this.lastUpdateId + 1) {
            // 正常更新
            applyUpdate(bidUpdates, askUpdates);
            this.lastUpdateId = lastUpdateId;
            this.status = OrderBookStatus.ACTIVE;

            log.trace("[OrderBook-{}] Applied update: U={}, u={}, last={}",
                    symbol, firstUpdateId, lastUpdateId, this.lastUpdateId);
            return true;

        } else if (lastUpdateId < this.lastUpdateId + 1) {
            // 旧消息，丢弃
            staleUpdateCount++;
            log.trace("[OrderBook-{}] Discard stale update: u={}, last={}",
                    symbol, lastUpdateId, this.lastUpdateId);
            return true;  // 不需要重建，返回true避免重复发布

        } else {
            // Gap detected - 严重问题
            log.error("[OrderBook-{}] ⚠️ SEQUENCE GAP DETECTED! U={}, u={}, last={}, gap={}",
                    symbol, firstUpdateId, lastUpdateId, this.lastUpdateId,
                    firstUpdateId - this.lastUpdateId - 1);
            gapCount++;
            status = OrderBookStatus.STALE;
            return false;  // 触发重建
        }
    }

    /**
     * 显式进入重建状态（单飞重建控制）
     *
     * @return true=成功进入重建态；false=已经在重建态
     */
    public synchronized boolean markRebuilding() {
        if (status == OrderBookStatus.REBUILDING) {
            return false;
        }
        status = OrderBookStatus.REBUILDING;
        return true;
    }

    /**
     * 应用增量更新到本地订单簿
     */
    private void applyUpdate(List<long[]> bidUpdates, List<long[]> askUpdates) {
        int bidChanges = 0;
        int askChanges = 0;

        // 更新买盘
        for (long[] bid : bidUpdates) {
            long price = bid[0];
            long qty = bid[1];

            if (qty == 0) {
                bids.remove(price);  // 删除档位
                bidChanges++;
            } else {
                bids.put(price, qty);
                bidChanges++;
            }
        }

        // 更新卖盘
        for (long[] ask : askUpdates) {
            long price = ask[0];
            long qty = ask[1];

            if (qty == 0) {
                asks.remove(price);
                askChanges++;
            } else {
                asks.put(price, qty);
                askChanges++;
            }
        }

        // 限制深度，防止内存溢出
        trimDepth();

        log.trace("[OrderBook-{}] Applied changes: bids={}, asks={}, totalBids={}, totalAsks={}",
                symbol, bidChanges, askChanges, bids.size(), asks.size());
    }

    /**
     * 限制订单簿深度（防止内存溢出）
     */
    private void trimDepth() {
        if (bids.size() > MAX_DEPTH) {
            // 保留最优的MAX_DEPTH档
            Iterator<Map.Entry<Long, Long>> it = bids.entrySet().iterator();
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
                if (count > MAX_DEPTH) {
                    it.remove();
                }
            }
        }

        if (asks.size() > MAX_DEPTH) {
            Iterator<Map.Entry<Long, Long>> it = asks.entrySet().iterator();
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
                if (count > MAX_DEPTH) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 从快照初始化订单簿
     *
     * @param lastUpdateId 快照的lastUpdateId
     * @param snapshotBids 快照买盘
     * @param snapshotAsks 快照卖盘
     */
    public synchronized void initFromSnapshot(long lastUpdateId,
                                              List<long[]> snapshotBids,
                                              List<long[]> snapshotAsks) {
        log.info("[OrderBook-{}] Starting rebuild from snapshot: lastUpdateId={}",
                symbol, lastUpdateId);

        status = OrderBookStatus.REBUILDING;

        // 清空旧数据
        bids.clear();
        asks.clear();

        // 加载快照
        for (long[] bid : snapshotBids) {
            if (bid[1] > 0) {  // 只加载qty > 0的档位
                bids.put(bid[0], bid[1]);
            }
        }
        for (long[] ask : snapshotAsks) {
            if (ask[1] > 0) {
                asks.put(ask[0], ask[1]);
            }
        }

        this.lastUpdateId = lastUpdateId;

        // 应用缓存的增量更新（序号 > snapshot.lastUpdateId）
        int appliedCount = 0;
        int skippedCount = 0;

        for (DepthUpdate update : bufferedUpdates) {
            // 只应用序号连续的更新
            if (update.firstUpdateId <= this.lastUpdateId + 1 &&
                update.lastUpdateId >= this.lastUpdateId + 1) {
                applyUpdate(update.bidUpdates, update.askUpdates);
                this.lastUpdateId = update.lastUpdateId;
                appliedCount++;
            } else {
                skippedCount++;
            }
        }

        bufferedUpdates.clear();
        status = OrderBookStatus.ACTIVE;
        rebuildCount++;

        log.info("[OrderBook-{}] ✅ Rebuilt successfully: lastUpdateId={}, bids={}, asks={}, " +
                        "appliedBuffered={}, skippedBuffered={}",
                symbol, lastUpdateId, bids.size(), asks.size(), appliedCount, skippedCount);

        // 数据质量检查
        validateOrderBook();
    }

    /**
     * 验证订单簿数据质量
     */
    private void validateOrderBook() {
        BBO bbo = getBBO();

        // 检查1：买价必须低于卖价
        if (bbo.bidPrice > 0 && bbo.askPrice > 0 && bbo.bidPrice >= bbo.askPrice) {
            log.error("[OrderBook-{}] ❌ PRICE ANOMALY: bid={} >= ask={}",
                    symbol, bbo.bidPrice, bbo.askPrice);
            priceAnomalyCount++;
            status = OrderBookStatus.STALE;
        }

        // 检查2：订单簿不能为空
        if (bids.isEmpty() && asks.isEmpty()) {
            log.warn("[OrderBook-{}] ⚠️ OrderBook is EMPTY after rebuild", symbol);
            status = OrderBookStatus.STALE;
        }
    }

    /**
     * 获取BBO（最优买卖价）
     */
    public BBO getBBO() {
        Map.Entry<Long, Long> bestBid = bids.firstEntry();
        Map.Entry<Long, Long> bestAsk = asks.firstEntry();

        return new BBO(
                bestBid != null ? bestBid.getKey() : 0,
                bestBid != null ? bestBid.getValue() : 0,
                bestAsk != null ? bestAsk.getKey() : 0,
                bestAsk != null ? bestAsk.getValue() : 0
        );
    }

    /**
     * 获取深度快照（限制档位）
     */
    public Snapshot getSnapshot(int depth) {
        List<long[]> bidList = bids.entrySet().stream()
                .limit(depth)
                .map(e -> new long[]{e.getKey(), e.getValue()})
                .toList();

        List<long[]> askList = asks.entrySet().stream()
                .limit(depth)
                .map(e -> new long[]{e.getKey(), e.getValue()})
                .toList();

        return new Snapshot(symbol, lastUpdateId, bidList, askList, status);
    }

    /**
     * 获取统计指标
     */
    public OrderBookMetrics getMetrics() {
        return new OrderBookMetrics(
                symbol,
                status,
                lastUpdateId,
                bids.size(),
                asks.size(),
                updateCount,
                gapCount,
                rebuildCount,
                priceAnomalyCount,
                staleUpdateCount
        );
    }

    /**
     * 重置为初始状态（用于测试或手动重建）
     */
    public synchronized void reset() {
        log.warn("[OrderBook-{}] Manual reset triggered", symbol);
        status = OrderBookStatus.INIT;
        lastUpdateId = 0;
        bids.clear();
        asks.clear();
        bufferedUpdates.clear();
    }

    // ========== 内部类 ==========

    /**
     * 订单簿状态枚举
     */
    public enum OrderBookStatus {
        INIT,          // 初始化，等待快照
        ACTIVE,        // 正常运行
        STALE,         // 数据过期（检测到Gap或价格异常）
        REBUILDING     // 重建中
    }

    /**
     * 深度更新数据结构
     */
    @Getter
    public static class DepthUpdate {
        private final long firstUpdateId;
        private final long lastUpdateId;
        private final List<long[]> bidUpdates;
        private final List<long[]> askUpdates;

        public DepthUpdate(long firstUpdateId, long lastUpdateId,
                          List<long[]> bidUpdates, List<long[]> askUpdates) {
            this.firstUpdateId = firstUpdateId;
            this.lastUpdateId = lastUpdateId;
            this.bidUpdates = new ArrayList<>(bidUpdates);
            this.askUpdates = new ArrayList<>(askUpdates);
        }
    }

    /**
     * BBO（最优买卖价）
     */
    @Getter
    public static class BBO {
        private final long bidPrice;
        private final long bidQty;
        private final long askPrice;
        private final long askQty;

        public BBO(long bidPrice, long bidQty, long askPrice, long askQty) {
            this.bidPrice = bidPrice;
            this.bidQty = bidQty;
            this.askPrice = askPrice;
            this.askQty = askQty;
        }

        public long getSpread() {
            if (bidPrice > 0 && askPrice > 0) {
                return askPrice - bidPrice;
            }
            return 0;
        }

        @Override
        public String toString() {
            return String.format("BBO[bid=%d@%d, ask=%d@%d, spread=%d]",
                    bidPrice, bidQty, askPrice, askQty, getSpread());
        }
    }

    /**
     * 订单簿快照
     */
    @Getter
    public static class Snapshot {
        private final String symbol;
        private final long lastUpdateId;
        private final List<long[]> bids;
        private final List<long[]> asks;
        private final OrderBookStatus status;

        public Snapshot(String symbol, long lastUpdateId,
                       List<long[]> bids, List<long[]> asks,
                       OrderBookStatus status) {
            this.symbol = symbol;
            this.lastUpdateId = lastUpdateId;
            this.bids = bids;
            this.asks = asks;
            this.status = status;
        }
    }

    /**
     * 订单簿统计指标
     */
    @Getter
    public static class OrderBookMetrics {
        private final String symbol;
        private final OrderBookStatus status;
        private final long lastUpdateId;
        private final int bidLevels;
        private final int askLevels;
        private final long updateCount;
        private final long gapCount;
        private final long rebuildCount;
        private final long priceAnomalyCount;
        private final long staleUpdateCount;

        public OrderBookMetrics(String symbol, OrderBookStatus status,
                               long lastUpdateId, int bidLevels, int askLevels,
                               long updateCount, long gapCount, long rebuildCount,
                               long priceAnomalyCount, long staleUpdateCount) {
            this.symbol = symbol;
            this.status = status;
            this.lastUpdateId = lastUpdateId;
            this.bidLevels = bidLevels;
            this.askLevels = askLevels;
            this.updateCount = updateCount;
            this.gapCount = gapCount;
            this.rebuildCount = rebuildCount;
            this.priceAnomalyCount = priceAnomalyCount;
            this.staleUpdateCount = staleUpdateCount;
        }

        @Override
        public String toString() {
            return String.format("OrderBookMetrics[%s, status=%s, lastId=%d, levels=%d/%d, " +
                            "updates=%d, gaps=%d, rebuilds=%d, anomalies=%d, stale=%d]",
                    symbol, status, lastUpdateId, bidLevels, askLevels,
                    updateCount, gapCount, rebuildCount, priceAnomalyCount, staleUpdateCount);
        }
    }
}
