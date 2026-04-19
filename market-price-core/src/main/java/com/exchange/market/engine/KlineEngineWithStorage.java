package com.exchange.market.engine;

import com.exchange.market.model.Kline;
import com.exchange.market.publisher.MarketDataPublisher;
import com.exchange.market.service.KlineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * K 线生成器（带 ClickHouse 存储）
 * 
 * 职责：
 * - 实时生成多周期 K 线
 * - 自动保存到 ClickHouse
 * - 支持历史数据查询
 * 
 * 支持周期：
 * 1s, 1m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M
 * 
 * 存储策略：
 * - 实时 K 线：写入 ClickHouse kline_realtime 表（ReplacingMergeTree）
 * - 完成的 K 线：写入 ClickHouse kline_data 表（MergeTree）
 * 
 * 对标：Binance/OKX Kline API
 */
@Slf4j
@Component
public class KlineEngineWithStorage {

    @Autowired
    private KlineService klineService;

    @Autowired
    private MarketDataPublisher publisher;

    // 各交易对的 K 线生成器
    private final ConcurrentHashMap<String, SymbolKlineEngine> symbolEngines;

    // 支持的周期列表
    public static final String[] INTERVALS = {
        "1s", "1m", "5m", "15m", "30m",
        "1h", "2h", "4h", "6h", "8h", "12h",
        "1d", "3d", "1w", "1M"
    };

    // 定期保存定时器
    private ScheduledExecutorService saveScheduler;

    public KlineEngineWithStorage() {
        this.symbolEngines = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        // 启动定期保存任务（每秒保存一次实时 K 线）
        saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kline-save-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        saveScheduler.scheduleAtFixedRate(this::saveRealtimeKlines, 1, 1, TimeUnit.SECONDS);
        
        log.info("[KlineEngineWithStorage] Initialized with intervals: {}", 
            String.join(", ", INTERVALS));
    }

    /**
     * 获取或创建交易对的 K 线引擎
     */
    private SymbolKlineEngine getOrCreateEngine(String symbol) {
        return symbolEngines.computeIfAbsent(symbol, 
            s -> new SymbolKlineEngine(s, klineService, publisher));
    }

    /**
     * 处理成交事件（兼容旧接口）
     */
    public void onTrade(String symbol, long price, long quantity, long timestamp, boolean isBuyerMaker) {
        onTrade(symbol, price, quantity, timestamp, isBuyerMaker, -1);
    }

    /**
     * 处理成交事件（带sequence去重，防止Kafka消息重投导致重复累加）
     */
    public void onTrade(String symbol, long price, long quantity, long timestamp, boolean isBuyerMaker, long sequence) {
        SymbolKlineEngine engine = getOrCreateEngine(symbol);
        engine.onTrade(price, quantity, timestamp, isBuyerMaker, sequence);
    }

    /**
     * 获取当前 K 线
     */
    public Kline getCurrentKline(String symbol, String interval) {
        SymbolKlineEngine engine = symbolEngines.get(symbol);
        if (engine == null) {
            return null;
        }
        return engine.getCurrentKline(interval);
    }

    /**
     * 获取历史 K 线（从 ClickHouse 查询）
     */
    public List<Kline> getKlines(String symbol, String interval, int limit) {
        // 先从内存获取最新的
        SymbolKlineEngine engine = symbolEngines.get(symbol);
        Kline current = engine != null ? engine.getCurrentKline(interval) : null;

        // 服务重启后内存可能为空，回退到 ClickHouse realtime 读取当前未收线
        if (current == null) {
            current = klineService.getLatestKline(symbol, interval);
        }
        
        // 从 ClickHouse 查询历史数据
        List<Kline> history = klineService.getRecentKlines(symbol, interval, limit);
        
        // 如果当前 K 线存在且不在历史列表中，添加到头部
        if (current != null) {
            final Kline finalCurrent = current;
            boolean exists = history.stream()
                .anyMatch(k -> k.getOpenTime() == finalCurrent.getOpenTime());
            if (!exists) {
                history.add(0, finalCurrent);
            }
        }

        // 统一按 openTime 倒序，避免 current 与历史数据源时间基准不一致导致乱序。
        history.sort((a, b) -> Long.compare(b.getOpenTime(), a.getOpenTime()));
        
        // 限制返回数量
        if (history.size() > limit) {
            return history.subList(0, limit);
        }
        
        return history;
    }

    /**
     * 从 ClickHouse 查询 K 线（时间范围）
     */
    public List<Kline> queryKlines(String symbol, String interval, 
                                    Long startTime, Long endTime, Integer limit) {
        return klineService.getKlines(symbol, interval, startTime, endTime, limit);
    }

    /**
     * 定期保存实时 K 线到 ClickHouse
     */
    private void saveRealtimeKlines() {
        try {
            for (SymbolKlineEngine engine : symbolEngines.values()) {
                engine.saveRealtimeKlines();
            }
        } catch (Exception e) {
            log.error("[KlineEngineWithStorage] Failed to save realtime klines", e);
        }
    }

    /**
     * 将 Kline 转换为数组格式（用于 API 返回）
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

    // ============================================
    // 内部类定义
    // ============================================

    /**
     * 单个交易对的 K 线引擎
     */
    private static class SymbolKlineEngine {
        
        private final String symbol;
        private final KlineService klineService;
        private final MarketDataPublisher publisher;
        
        // 各周期的 K 线生成器
        private final Map<String, KlineGenerator> generators;
        
        // 消费去重（防止Kafka消息重投导致重复累加）
        private volatile long lastProcessedSequence = -1;

        SymbolKlineEngine(String symbol, KlineService klineService, MarketDataPublisher publisher) {
            this.symbol = symbol;
            this.klineService = klineService;
            this.publisher = publisher;
            this.generators = new ConcurrentHashMap<>();
            
            // 初始化各周期生成器
            for (String interval : INTERVALS) {
                generators.put(interval, new KlineGenerator(symbol, interval, klineService, publisher));
            }
        }

        void onTrade(long price, long quantity, long timestamp, boolean isBuyerMaker, long sequence) {
            if (sequence > 0 && sequence <= lastProcessedSequence) {
                log.debug("[SymbolKlineEngine] Duplicate trade ignored, symbol={}, sequence={}", symbol, sequence);
                return;
            }
            if (sequence > 0) {
                lastProcessedSequence = sequence;
            }
            for (KlineGenerator generator : generators.values()) {
                generator.onTrade(price, quantity, timestamp, isBuyerMaker);
            }
        }

        Kline getCurrentKline(String interval) {
            KlineGenerator generator = generators.get(interval);
            return generator != null ? generator.getCurrentKline() : null;
        }

        void saveRealtimeKlines() {
            for (KlineGenerator generator : generators.values()) {
                generator.saveToClickHouse();
            }
        }
    }

    /**
     * 单个周期的 K 线生成器
     */
    private static class KlineGenerator {
        private static final BigDecimal SCALE_BD = BigDecimal.valueOf(100_000_000L);
        
        private final String symbol;
        private final String interval;
        private final long intervalMs;
        private final KlineService klineService;
        private final MarketDataPublisher publisher;
        
        // 当前 K 线
        private volatile Kline currentKline;
        
        // 统计计数
        private volatile long takerBuyVolume = 0;
        private volatile long takerBuyQuoteVolume = 0;
        
        // 待保存标记
        private volatile boolean needSave = false;

        KlineGenerator(String symbol, String interval, KlineService klineService, MarketDataPublisher publisher) {
            this.symbol = symbol;
            this.interval = interval;
            this.klineService = klineService;
            this.publisher = publisher;
            this.intervalMs = parseInterval(interval);
            this.currentKline = null;
        }

        void onTrade(long price, long quantity, long timestamp, boolean isBuyerMaker) {
            long openTime = (timestamp / intervalMs) * intervalMs;
            
            synchronized (this) {
                if (currentKline == null || currentKline.getOpenTime() != openTime) {
                    // 新周期开始，先保存旧 K 线
                    if (currentKline != null) {
                        closeKline();
                    }
                    createNewKline(openTime, price, quantity, isBuyerMaker);
                } else {
                    updateCurrentKline(price, quantity, isBuyerMaker);
                }
                needSave = true;
            }
            
            // 发布实时 K 线（不阻塞）
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
            
            if (isBuyerMaker) {
                takerBuyVolume = quantity;
                takerBuyQuoteVolume = multiplyScaled(price, quantity);
            } else {
                takerBuyVolume = 0;
                takerBuyQuoteVolume = 0;
            }
            currentKline.setTakerBuyVolume(takerBuyVolume);
            currentKline.setTakerBuyQuoteVolume(takerBuyQuoteVolume);
            
            log.debug("[KlineGenerator] Created new kline: {} {} @ {}", 
                symbol, interval, openTime);
        }

        void updateCurrentKline(long price, long quantity, boolean isBuyerMaker) {
            if (price > currentKline.getHighPrice()) {
                currentKline.setHighPrice(price);
            }
            if (price < currentKline.getLowPrice()) {
                currentKline.setLowPrice(price);
            }
            
            currentKline.setClosePrice(price);
            currentKline.setVolume(currentKline.getVolume() + quantity);
            currentKline.setQuoteVolume(currentKline.getQuoteVolume() + multiplyScaled(price, quantity));
            currentKline.setTradeCount(currentKline.getTradeCount() + 1);
            
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

        void closeKline() {
            if (currentKline == null) return;
            
            // 保存到 ClickHouse
            try {
                klineService.closeKline(currentKline);
                publisher.publishKlineClosed(symbol, interval, currentKline);
                log.debug("[KlineGenerator] Closed kline: {} {} @ {}", 
                    symbol, interval, currentKline.getOpenTime());
            } catch (Exception e) {
                log.error("[KlineGenerator] Failed to close kline", e);
            }
        }

        void saveToClickHouse() {
            if (needSave && currentKline != null) {
                synchronized (this) {
                    if (needSave && currentKline != null) {
                        try {
                            klineService.saveRealtimeKline(currentKline);
                            needSave = false;
                        } catch (Exception e) {
                            log.error("[KlineGenerator] Failed to save realtime kline", e);
                        }
                    }
                }
            }
        }

        Kline getCurrentKline() {
            return currentKline;
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
}
