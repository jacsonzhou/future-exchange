package com.exchange.markprice.service.impl;

import com.exchange.markprice.dto.MarkPriceDTO;
import com.exchange.markprice.entity.MarkPrice;
import com.exchange.markprice.event.MarkPriceUpdateEvent;
import com.exchange.markprice.mapper.MarkPriceMapper;
import com.exchange.markprice.producer.MarkPriceProducer;
import com.exchange.markprice.service.MarkPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 标记价格服务实现
 * 
 * 标记价格计算逻辑：
 * 1. 基于指数价格 + 溢价指数
 * 2. 使用EMA平滑处理，防止价格剧烈波动
 */
@Slf4j
@Service
public class MarkPriceServiceImpl implements MarkPriceService {

    @Autowired
    private MarkPriceMapper markPriceMapper;

    @Autowired
    private MarkPriceProducer eventProducer;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // EMA平滑系数 (α = 2/(N+1), N=60)
    private static final double EMA_ALPHA = 0.0328;
    
    // Redis缓存键前缀
    private static final String REDIS_KEY_PREFIX = "mark:price:";
    private static final String EMA_KEY_PREFIX = "mark:ema:";
    private static final long REDIS_CACHE_TTL_SECONDS = 30;

    // 内存中的EMA缓存
    private final Map<String, Double> emaCache = new ConcurrentHashMap<>();

    @Override
    public MarkPriceDTO getLatestMarkPrice(String symbol) {
        // 1. 先尝试从Redis获取
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
            if (cached != null) {
                return (MarkPriceDTO) cached;
            }
        }

        // 2. 从数据库获取
        MarkPrice markPrice = markPriceMapper.selectLatestBySymbol(symbol);
        if (markPrice == null) {
            return null;
        }

        MarkPriceDTO dto = convertToDTO(markPrice);
        
        // 3. 缓存到Redis
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto, 
                    REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        return dto;
    }

    @Override
    public List<MarkPriceDTO> getAllLatestMarkPrices() {
        List<MarkPrice> prices = markPriceMapper.selectAllLatest();
        return prices.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void calculateAndUpdateMarkPrice(String symbol) {
        // 1. 获取当前指数价格
        Long indexPrice = getCurrentIndexPrice(symbol);
        if (indexPrice == null) {
            log.warn("Index price not available for symbol: {}", symbol);
            return;
        }

        // 2. 计算理论标记价格（基于指数价格 + 溢价）
        Long fairPrice = calculateFairPrice(symbol, indexPrice);
        
        // 3. EMA平滑处理
        Long smoothedPrice = applyEMASmoothing(symbol, fairPrice);

        // 4. 保存到数据库
        MarkPrice markPrice = new MarkPrice();
        markPrice.setSymbol(symbol);
        markPrice.setMarkPrice(smoothedPrice);
        markPrice.setIndexPrice(indexPrice);
        markPrice.setFundingRate(getCurrentFundingRate(symbol));
        markPrice.setNextFundingTime(getNextFundingTime(symbol));
        markPrice.setTimestamp(System.currentTimeMillis());
        markPriceMapper.insert(markPrice);

        // 5. 更新Redis缓存
        MarkPriceDTO dto = convertToDTO(markPrice);
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto, 
                    REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        // 6. 发布事件
        publishMarkPriceEvent(dto);

        log.debug("Updated mark price for {}: markPrice={}, indexPrice={}", 
                symbol, smoothedPrice, indexPrice);
    }

    @Override
    public void batchCalculateMarkPrices() {
        // 获取所有支持的交易对
        String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        for (String symbol : symbols) {
            try {
                calculateAndUpdateMarkPrice(symbol);
            } catch (Exception e) {
                log.error("Failed to calculate mark price for {}", symbol, e);
            }
        }
    }

    @Override
    public void onIndexPriceUpdate(String symbol, Long indexPrice) {
        // 指数价格更新时，触发标记价格重新计算
        calculateAndUpdateMarkPrice(symbol);
    }

    /**
     * 获取当前指数价格
     */
    private Long getCurrentIndexPrice(String symbol) {
        // 优先从Redis获取
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get("index:price:" + symbol);
            if (cached != null) {
                return ((MarkPriceDTO) cached).getMarkPrice();
            }
        }
        
        // 返回模拟数据
        return "BTCUSDT".equals(symbol) ? 50000_00000000L : 
               ("ETHUSDT".equals(symbol) ? 3000_00000000L : 100_00000000L);
    }

    /**
     * 计算公允价格（基于OrderBook买一卖一）
     * 实际生产环境需要获取OrderBook数据
     */
    private Long calculateFairPrice(String symbol, Long indexPrice) {
        // 模拟基于OrderBook的公允价格计算
        // 实际应该获取买一卖一的中间价
        double premium = (Math.random() - 0.5) * 0.001; // ±0.05%的溢价
        return (long) (indexPrice * (1 + premium));
    }

    /**
     * EMA平滑处理
     */
    private Long applyEMASmoothing(String symbol, Long newPrice) {
        Double prevEMA = emaCache.get(symbol);
        
        if (prevEMA == null) {
            // 尝试从Redis获取
            if (redisTemplate != null) {
                Object cached = redisTemplate.opsForValue().get(EMA_KEY_PREFIX + symbol);
                if (cached != null) {
                    prevEMA = ((Number) cached).doubleValue();
                }
            }
        }

        double currentEMA;
        if (prevEMA == null) {
            currentEMA = newPrice;
        } else {
            currentEMA = EMA_ALPHA * newPrice + (1 - EMA_ALPHA) * prevEMA;
        }

        // 更新缓存
        emaCache.put(symbol, currentEMA);
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(EMA_KEY_PREFIX + symbol, currentEMA, 60, TimeUnit.SECONDS);
        }

        return (long) currentEMA;
    }

    /**
     * 获取当前资金费率
     */
    private Long getCurrentFundingRate(String symbol) {
        // 从Redis或配置获取
        return 10000L; // 0.01%
    }

    /**
     * 获取下次结算时间
     */
    private Long getNextFundingTime(String symbol) {
        long now = System.currentTimeMillis();
        long dayStart = (now / (24 * 60 * 60 * 1000)) * (24 * 60 * 60 * 1000);
        long[] fundingTimes = {
            dayStart,
            dayStart + 8 * 60 * 60 * 1000,
            dayStart + 16 * 60 * 60 * 1000
        };

        for (long fundingTime : fundingTimes) {
            if (fundingTime > now) {
                return fundingTime;
            }
        }
        return dayStart + 24 * 60 * 60 * 1000;
    }

    /**
     * 发布标记价格事件
     */
    private void publishMarkPriceEvent(MarkPriceDTO dto) {
        MarkPriceUpdateEvent event = new MarkPriceUpdateEvent();
        event.setEventTime(System.currentTimeMillis());
        
        MarkPriceUpdateEvent.MarkPriceData data = new MarkPriceUpdateEvent.MarkPriceData();
        data.setSymbol(dto.getSymbol());
        data.setMarkPrice(dto.getMarkPrice());
        data.setIndexPrice(dto.getIndexPrice());
        data.setFundingRate(dto.getFundingRate());
        data.setNextFundingTime(dto.getNextFundingTime());
        data.setTimestamp(dto.getTimestamp());
        
        event.setData(data);
        eventProducer.publishMarkPriceUpdate(event);
    }

    private MarkPriceDTO convertToDTO(MarkPrice entity) {
        MarkPriceDTO dto = new MarkPriceDTO();
        dto.setSymbol(entity.getSymbol());
        dto.setMarkPrice(entity.getMarkPrice());
        dto.setIndexPrice(entity.getIndexPrice());
        dto.setFundingRate(entity.getFundingRate());
        dto.setNextFundingTime(entity.getNextFundingTime());
        dto.setTimestamp(entity.getTimestamp());
        return dto;
    }
}
