package com.exchange.market.engine;

import com.exchange.market.model.PriceLevel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存订单簿（OrderBook）
 * 
 * 核心特性：
 * - 每个symbol独立OrderBook
 * - 侵入式链表实现PriceLevel，避免GC
 * - 支持增量更新和快照生成
 * - 读写锁保证线程安全
 * - 顺序号检查保证一致性
 * 
 * 对标：Binance/OKX 的L2深度实现
 * 
 * 性能目标：
 * - 查询BBO: < 1μs
 * - 更新深度: < 5μs  
 * - 生成快照: < 50μs (100档)
 */
@Slf4j
public class OrderBook {

    /**
     * 交易对
     */
    private final String symbol;
    
    /**
     * 买盘 price -> PriceLevel (跳表结构，价格降序)
     */
    private final Map<Long, PriceLevel> bidLevels;
    
    /**
     * 卖盘 price -> PriceLevel (跳表结构，价格升序)
     */
    private final Map<Long, PriceLevel> askLevels;
    
    /**
     * 最高买价（链表头）
     */
    private volatile PriceLevel bidHead;
    
    /**
     * 最低卖价（链表头）
     */
    private volatile PriceLevel askHead;
    
    /**
     * 最后更新序号（严格递增）
     */
    private final AtomicLong lastUpdateId;
    
    /**
     * 最后消息输出时间（用于对账）
     */
    private volatile long lastMessageOutputTime;
    
    /**
     * 读写锁（读多写少场景）
     */
    private final ReentrantReadWriteLock lock;
    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    
    /**
     * 价格精度（小数位数）
     */
    private final int pricePrecision;
    
    /**
     * 数量精度（小数位数）
     */
    private final int qtyPrecision;

    public OrderBook(String symbol, int pricePrecision, int qtyPrecision) {
        this.symbol = symbol;
        this.pricePrecision = pricePrecision;
        this.qtyPrecision = qtyPrecision;
        this.bidLevels = new ConcurrentHashMap<>(1024);
        this.askLevels = new ConcurrentHashMap<>(1024);
        this.lastUpdateId = new AtomicLong(0);
        this.lastMessageOutputTime = System.currentTimeMillis();
        this.lock = new ReentrantReadWriteLock();
        this.readLock = lock.readLock();
        this.writeLock = lock.writeLock();
    }

    /**
     * 应用深度增量更新
     * 
     * @param bids 买盘变化 [[price, qty], ...]  qty=0表示删除
     * @param asks 卖盘变化 [[price, qty], ...]
     * @param updateId 本次更新的序号
     * @param timestamp 时间戳
     * @return 是否成功应用
     */
    public boolean applyDelta(List<long[]> bids, List<long[]> asks, long updateId, long timestamp) {
        // 序号检查：必须是连续的
        long lastId = lastUpdateId.get();
        if (updateId <= lastId) {
            // 重复消息，忽略
            return true;
        }
        if (lastId == 0) {
            // 🔥 FIX: 首次消息，直接接受（重建模式）
            log.info("[OrderBook] {} First depth message, accepting updateId={}", symbol, updateId);
        } else if (updateId > lastId + 1) {
            // 序号不连续，需要重连
            log.warn("[OrderBook] {} sequence gap detected: last={}, current={}", 
                    symbol, lastId, updateId);
            return false;
        }

        writeLock.lock();
        try {
            // 更新买盘
            if (bids != null) {
                for (long[] bid : bids) {
                    long price = bid[0];
                    long qty = bid[1];
                    updateBidLevel(price, qty);
                }
            }
            
            // 更新卖盘
            if (asks != null) {
                for (long[] ask : asks) {
                    long price = ask[0];
                    long qty = ask[1];
                    updateAskLevel(price, qty);
                }
            }
            
            // 更新序号和时间
            lastUpdateId.set(updateId);
            lastMessageOutputTime = timestamp;
            
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 更新买盘档位
     */
    private void updateBidLevel(long price, long qty) {
        log.info("[OrderBook] updateBidLevel: price={}, qty={}", price, qty);
        PriceLevel level = bidLevels.get(price);
        
        if (qty == 0) {
            // 删除档位
            if (level != null) {
                removeBidLevel(level);
                bidLevels.remove(price);
            }
        } else {
            if (level == null) {
                // 新建档位
                level = new PriceLevel(price, qty);
                bidLevels.put(price, level);
                insertBidLevel(level);
                log.info("[OrderBook] New bid level created: price={}, qty={}, bidHead={}", price, qty, bidHead != null);
            } else {
                // 更新数量
                level.setQuantity(qty);
                level.setUpdateTime(System.currentTimeMillis());
            }
        }
    }

    /**
     * 更新卖盘档位
     */
    private void updateAskLevel(long price, long qty) {
        PriceLevel level = askLevels.get(price);
        
        if (qty == 0) {
            // 删除档位
            if (level != null) {
                removeAskLevel(level);
                askLevels.remove(price);
            }
        } else {
            if (level == null) {
                // 新建档位
                level = new PriceLevel(price, qty);
                askLevels.put(price, level);
                insertAskLevel(level);
            } else {
                // 更新数量
                level.setQuantity(qty);
                level.setUpdateTime(System.currentTimeMillis());
            }
        }
    }

    /**
     * 插入买盘档位（按价格降序）
     */
    private void insertBidLevel(PriceLevel newLevel) {
        if (bidHead == null || newLevel.getPrice() > bidHead.getPrice()) {
            // 新的最高价
            newLevel.setNext(bidHead);
            if (bidHead != null) {
                bidHead.setPrev(newLevel);
            }
            bidHead = newLevel;
            return;
        }
        
        PriceLevel current = bidHead;
        while (current.getNext() != null && current.getNext().getPrice() > newLevel.getPrice()) {
            current = current.getNext();
        }
        
        // 插入到current之后
        newLevel.setNext(current.getNext());
        newLevel.setPrev(current);
        if (current.getNext() != null) {
            current.getNext().setPrev(newLevel);
        }
        current.setNext(newLevel);
    }

    /**
     * 插入卖盘档位（按价格升序）
     */
    private void insertAskLevel(PriceLevel newLevel) {
        if (askHead == null || newLevel.getPrice() < askHead.getPrice()) {
            // 新的最低价
            newLevel.setNext(askHead);
            if (askHead != null) {
                askHead.setPrev(newLevel);
            }
            askHead = newLevel;
            return;
        }
        
        PriceLevel current = askHead;
        while (current.getNext() != null && current.getNext().getPrice() < newLevel.getPrice()) {
            current = current.getNext();
        }
        
        // 插入到current之后
        newLevel.setNext(current.getNext());
        newLevel.setPrev(current);
        if (current.getNext() != null) {
            current.getNext().setPrev(newLevel);
        }
        current.setNext(newLevel);
    }

    /**
     * 移除买盘档位
     */
    private void removeBidLevel(PriceLevel level) {
        if (level.getPrev() != null) {
            level.getPrev().setNext(level.getNext());
        } else {
            bidHead = level.getNext();
        }
        if (level.getNext() != null) {
            level.getNext().setPrev(level.getPrev());
        }
        level.clearLinks();
    }

    /**
     * 移除卖盘档位
     */
    private void removeAskLevel(PriceLevel level) {
        if (level.getPrev() != null) {
            level.getPrev().setNext(level.getNext());
        } else {
            askHead = level.getNext();
        }
        if (level.getNext() != null) {
            level.getNext().setPrev(level.getPrev());
        }
        level.clearLinks();
    }

    /**
     * 获取BBO（Best Bid/Offer）
     * 
     * @return [bidPrice, bidQty, askPrice, askQty]
     */
    public long[] getBBO() {
        readLock.lock();
        try {
            long bidPrice = bidHead != null ? bidHead.getPrice() : 0;
            long bidQty = bidHead != null ? bidHead.getQuantity() : 0;
            long askPrice = askHead != null ? askHead.getPrice() : Long.MAX_VALUE;
            long askQty = askHead != null ? askHead.getQuantity() : 0;
            return new long[]{bidPrice, bidQty, askPrice, askQty};
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取深度快照
     * 
     * @param limit 档位数（5, 10, 20, 50, 100, 500, 1000）
     * @return 深度快照
     */
    public DepthSnapshot getSnapshot(int limit) {
        readLock.lock();
        try {
            DepthSnapshot snapshot = new DepthSnapshot();
            snapshot.setSymbol(symbol);
            snapshot.setLastUpdateId(lastUpdateId.get());
            snapshot.setMessageOutputTime(lastMessageOutputTime);
            
            // 买盘（价格降序）
            List<long[]> bids = new ArrayList<>(limit);
            PriceLevel currentBid = bidHead;
            int count = 0;
            while (currentBid != null && count < limit) {
                bids.add(new long[]{currentBid.getPrice(), currentBid.getQuantity()});
                currentBid = currentBid.getNext();
                count++;
            }
            snapshot.setBids(bids);
            
            // 卖盘（价格升序）
            List<long[]> asks = new ArrayList<>(limit);
            PriceLevel currentAsk = askHead;
            count = 0;
            while (currentAsk != null && count < limit) {
                asks.add(new long[]{currentAsk.getPrice(), currentAsk.getQuantity()});
                currentAsk = currentAsk.getNext();
                count++;
            }
            snapshot.setAsks(asks);
            
            return snapshot;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 重建订单簿（用于故障恢复）
     * 
     * @param bids 买盘 [[price, qty], ...]
     * @param asks 卖盘 [[price, qty], ...]
     * @param lastId 最后的updateId
     * @param timestamp 时间戳
     */
    public void rebuild(List<long[]> bids, List<long[]> asks, long lastId, long timestamp) {
        writeLock.lock();
        try {
            // 清空现有数据
            bidLevels.clear();
            askLevels.clear();
            bidHead = null;
            askHead = null;
            
            // 重建买盘
            if (bids != null) {
                for (long[] bid : bids) {
                    if (bid[1] > 0) { // qty > 0
                        PriceLevel level = new PriceLevel(bid[0], bid[1]);
                        bidLevels.put(bid[0], level);
                        insertBidLevel(level);
                    }
                }
            }
            
            // 重建卖盘
            if (asks != null) {
                for (long[] ask : asks) {
                    if (ask[1] > 0) { // qty > 0
                        PriceLevel level = new PriceLevel(ask[0], ask[1]);
                        askLevels.put(ask[0], level);
                        insertAskLevel(level);
                    }
                }
            }
            
            lastUpdateId.set(lastId);
            lastMessageOutputTime = timestamp;
            
            log.info("[OrderBook] {} rebuilt, bids={}, asks={}, lastUpdateId={}", 
                    symbol, bidLevels.size(), askLevels.size(), lastId);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取当前序号
     */
    public long getLastUpdateId() {
        return lastUpdateId.get();
    }

    /**
     * 获取最后更新时间
     */
    public long getLastMessageOutputTime() {
        return lastMessageOutputTime;
    }

    /**
     * 获取买盘档位数
     */
    public int getBidLevelCount() {
        readLock.lock();
        try {
            return bidLevels.size();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取卖盘档位数
     */
    public int getAskLevelCount() {
        readLock.lock();
        try {
            return askLevels.size();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 深度快照DTO
     */
    @Data
    public static class DepthSnapshot {
        private String symbol;
        private long lastUpdateId;
        private long messageOutputTime;
        private List<long[]> bids;  // [[price, qty], ...]
        private List<long[]> asks;  // [[price, qty], ...]
    }
}
