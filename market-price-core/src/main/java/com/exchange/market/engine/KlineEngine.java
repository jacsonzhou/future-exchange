package com.exchange.market.engine;

import com.exchange.market.model.Kline;
import com.exchange.market.publisher.MarketDataPublisher;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K线生成器（Kline Engine）
 * 
 * 职责：
 * - 实时生成多周期K线
 * - 支持标准K线周期
 * - 实时推送 + 持久化
 * 
 * 支持周期：
 * 1s, 1m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M
 * 
 * 性能目标：
 * - 更新延迟 < 50ms
 * - 支持1000+交易对
 * 
 * 对标：Binance/OKX Kline API
 */
@Slf4j
public class KlineEngine {

    private final String symbol;
    private final MarketDataPublisher publisher;
    
    // 各周期的K线生成器
    private final Map<String, KlineGenerator> generators;
    
    // 支持的周期列表
    public static final String[] INTERVALS = {
        "1s", "1m", "5m", "15m", "30m", 
        "1h", "2h", "4h", "6h", "8h", "12h",
        "1d", "3d", "1w", "1M"
    };

    public KlineEngine(String symbol, MarketDataPublisher publisher) {
        this.symbol = symbol;
        this.publisher = publisher;
        this.generators = new ConcurrentHashMap<>();
        
        // 初始化各周期的生成器
        for (String interval : INTERVALS) {
            generators.put(interval, new KlineGenerator(symbol, interval));
        }
    }

    /**
     * 处理成交事件，更新K线
     * 
     * @param price 成交价
     * @param quantity 成交量
     * @param timestamp 成交时间戳
     * @param isBuyerMaker 是否买方 maker
     */
    public void onTrade(long price, long quantity, long timestamp, boolean isBuyerMaker) {
        for (KlineGenerator generator : generators.values()) {
            generator.onTrade(price, quantity, timestamp, isBuyerMaker);
        }
    }

    /**
     * 获取指定周期的当前K线
     */
    public Kline getCurrentKline(String interval) {
        KlineGenerator generator = generators.get(interval);
        if (generator == null) {
            return null;
        }
        return generator.getCurrentKline();
    }

    /**
     * 获取指定周期的历史K线
     */
    public List<Kline> getKlines(String interval, int limit) {
        KlineGenerator generator = generators.get(interval);
        if (generator == null) {
            return new ArrayList<>();
        }
        return generator.getRecentKlines(limit);
    }

    /**
     * 强制关闭当前K线（用于测试或特殊场景）
     */
    public void forceCloseKline(String interval) {
        KlineGenerator generator = generators.get(interval);
        if (generator != null) {
            generator.forceClose();
        }
    }

    /**
     * 单个周期的K线生成器
     */
    private class KlineGenerator {
        private static final BigDecimal SCALE_BD = BigDecimal.valueOf(100_000_000L);
        
        private final String symbol;
        private final String interval;
        private final long intervalMs;
        
        // 当前K线
        private volatile Kline currentKline;
        
        // 历史K线（RingBuffer，最近1000根）
        private final List<Kline> historyKlines;
        private static final int MAX_HISTORY = 1000;
        private int historyIndex = 0;
        
        // 统计计数
        private volatile long takerBuyVolume = 0;
        private volatile long takerBuyQuoteVolume = 0;

        KlineGenerator(String symbol, String interval) {
            this.symbol = symbol;
            this.interval = interval;
            this.intervalMs = parseInterval(interval);
            this.historyKlines = new ArrayList<>(MAX_HISTORY);
            this.currentKline = null;
        }

        void onTrade(long price, long quantity, long timestamp, boolean isBuyerMaker) {
            // 计算当前K线的时间窗口
            long openTime = (timestamp / intervalMs) * intervalMs;
            
            synchronized (this) {
                // 检查是否需要新建K线
                if (currentKline == null || currentKline.getOpenTime() != openTime) {
                    // 保存旧K线
                    if (currentKline != null) {
                        closeCurrentKline();
                    }
                    // 创建新K线
                    createNewKline(openTime, price, quantity, isBuyerMaker);
                } else {
                    // 更新当前K线
                    updateCurrentKline(price, quantity, isBuyerMaker);
                }
            }
            
            // 异步推送（不阻塞撮合线程）
            if (currentKline != null) {
                publisher.publishKline(symbol, interval, currentKline);
            }
        }

        void createNewKline(long openTime, long price, long quantity, boolean isBuyerMaker) {
            currentKline = new Kline();
            currentKline.setSymbol(symbol);
            currentKline.setInterval(interval);
            currentKline.setOpenTime(openTime);
            currentKline.setCloseTime(openTime + intervalMs - 1);
            currentKline.setOpenPrice(price);
            currentKline.setHighPrice(price);
            currentKline.setLowPrice(price);
            currentKline.setClosePrice(price);
            currentKline.setVolume(quantity);
            currentKline.setQuoteVolume(multiplyScaled(price, quantity));
            currentKline.setTradeCount(1);
            
            // 重置主动买入统计
            if (isBuyerMaker) {
                takerBuyVolume = quantity;
                takerBuyQuoteVolume = multiplyScaled(price, quantity);
            } else {
                takerBuyVolume = 0;
                takerBuyQuoteVolume = 0;
            }
            currentKline.setTakerBuyVolume(takerBuyVolume);
            currentKline.setTakerBuyQuoteVolume(takerBuyQuoteVolume);
        }

        void updateCurrentKline(long price, long quantity, boolean isBuyerMaker) {
            // 更新高低价
            if (price > currentKline.getHighPrice()) {
                currentKline.setHighPrice(price);
            }
            if (price < currentKline.getLowPrice()) {
                currentKline.setLowPrice(price);
            }
            
            // 更新收盘价、成交量
            currentKline.setClosePrice(price);
            currentKline.setVolume(currentKline.getVolume() + quantity);
            currentKline.setQuoteVolume(currentKline.getQuoteVolume() + multiplyScaled(price, quantity));
            currentKline.setTradeCount(currentKline.getTradeCount() + 1);
            
            // 更新主动买入统计（taker是买方时）
            if (isBuyerMaker) {
                takerBuyVolume += quantity;
                takerBuyQuoteVolume += multiplyScaled(price, quantity);
                currentKline.setTakerBuyVolume(takerBuyVolume);
                currentKline.setTakerBuyQuoteVolume(takerBuyQuoteVolume);
            }
        }

        private long multiplyScaled(long price, long quantity) {
            return BigDecimal.valueOf(price)
                    .multiply(BigDecimal.valueOf(quantity))
                    .divide(SCALE_BD, 0, RoundingMode.DOWN)
                    .longValue();
        }

        void closeCurrentKline() {
            if (currentKline == null) return;
            
            // 添加到历史
            if (historyKlines.size() < MAX_HISTORY) {
                historyKlines.add(currentKline);
            } else {
                historyKlines.set(historyIndex, currentKline);
                historyIndex = (historyIndex + 1) % MAX_HISTORY;
            }
            
            // 推送完成的K线
            publisher.publishKlineClosed(symbol, interval, currentKline);
            
            log.debug("[KlineEngine] {} {} Kline closed: open={}, close={}, high={}, low={}, vol={}",
                    symbol, interval, 
                    currentKline.getOpenPrice(), currentKline.getClosePrice(),
                    currentKline.getHighPrice(), currentKline.getLowPrice(),
                    currentKline.getVolume());
        }

        void forceClose() {
            synchronized (this) {
                closeCurrentKline();
                currentKline = null;
            }
        }

        Kline getCurrentKline() {
            return currentKline;
        }

        List<Kline> getRecentKlines(int limit) {
            synchronized (historyKlines) {
                int size = historyKlines.size();
                int start = Math.max(0, size - limit);
                return new ArrayList<>(historyKlines.subList(start, size));
            }
        }

        long parseInterval(String interval) {
            char unit = interval.charAt(interval.length() - 1);
            int value = Integer.parseInt(interval.substring(0, interval.length() - 1));
            
            return switch (unit) {
                case 's' -> value * 1000L;
                case 'm' -> value * 60 * 1000L;
                case 'h' -> value * 60 * 60 * 1000L;
                case 'd' -> value * 24 * 60 * 60 * 1000L;
                case 'w' -> value * 7 * 24 * 60 * 60 * 1000L;
                case 'M' -> value * 30L * 24 * 60 * 60 * 1000L;
                default -> throw new IllegalArgumentException("Invalid interval: " + interval);
            };
        }
    }

    /**
     * 将Kline转换为数组格式（用于API返回）
     */
    public static Object[] toArray(Kline kline) {
        return new Object[] {
            kline.getOpenTime(),
            kline.getOpenPrice(),
            kline.getHighPrice(),
            kline.getLowPrice(),
            kline.getClosePrice(),
            kline.getVolume(),
            kline.getCloseTime(),
            kline.getQuoteVolume(),
            kline.getTradeCount(),
            kline.getTakerBuyVolume(),
            kline.getTakerBuyQuoteVolume()
        };
    }
}
