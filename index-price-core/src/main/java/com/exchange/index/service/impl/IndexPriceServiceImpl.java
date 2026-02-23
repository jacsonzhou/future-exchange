package com.exchange.index.service.impl;

import com.exchange.index.component.ExternalPriceFetcher;
import com.exchange.index.dto.IndexPriceDTO;
import com.exchange.index.entity.IndexPrice;
import com.exchange.index.entity.IndexPriceComponent;
import com.exchange.index.entity.IndexPriceConfig;
import com.exchange.index.event.IndexPriceUpdateEvent;
import com.exchange.index.mapper.IndexPriceComponentMapper;
import com.exchange.index.mapper.IndexPriceConfigMapper;
import com.exchange.index.mapper.IndexPriceMapper;
import com.exchange.index.producer.IndexPriceProducer;
import com.exchange.index.service.IndexPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 指数价格服务实现
 */
@Slf4j
@Service
public class IndexPriceServiceImpl implements IndexPriceService {

    @Autowired
    private IndexPriceMapper indexPriceMapper;

    @Autowired
    private IndexPriceComponentMapper componentMapper;

    @Autowired
    private IndexPriceConfigMapper configMapper;

    @Autowired
    private ExternalPriceFetcher priceFetcher;

    @Autowired
    private IndexPriceProducer eventProducer;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private static final String REDIS_KEY_PREFIX = "index:price:";
    private static final long REDIS_CACHE_TTL_SECONDS = 60;

    @Override
    public IndexPriceDTO getLatestIndexPrice(String symbol) {
        // 1. 先尝试从Redis获取
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
            if (cached != null) {
                return (IndexPriceDTO) cached;
            }
        }

        // 2. 从数据库获取
        IndexPrice indexPrice = indexPriceMapper.selectLatestBySymbol(symbol);
        if (indexPrice == null) {
            return null;
        }

        IndexPriceDTO dto = convertToDTO(indexPrice);
        
        // 3. 缓存到Redis
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto, 
                    REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        return dto;
    }

    @Override
    public List<IndexPriceDTO> getAllLatestIndexPrices() {
        List<IndexPrice> prices = indexPriceMapper.selectAllLatest();
        return prices.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void calculateAndUpdateIndexPrice(String symbol) {
        IndexPriceConfig config = configMapper.selectBySymbol(symbol);
        if (config == null) {
            log.warn("No index price config found for symbol: {}", symbol);
            return;
        }

        // 1. 获取成分交易所列表
        String[] exchanges = config.getComponents().split(",");

        // 2. 从各交易所获取价格
        Map<String, Long> exchangePrices = priceFetcher.fetchPricesFromMultipleExchanges(exchanges, symbol);

        if (exchangePrices.isEmpty()) {
            log.error("Failed to fetch prices from all exchanges for symbol: {}", symbol);
            return;
        }

        // 3. 计算加权平均价格
        long weightedSum = 0;
        int totalWeight = 0;
        int validCount = 0;
        List<IndexPriceDTO.ComponentDTO> componentDTOs = new ArrayList<>();

        for (Map.Entry<String, Long> entry : exchangePrices.entrySet()) {
            String exchange = entry.getKey();
            Long price = entry.getValue();
            
            // 验证价格有效性
            if (!isPriceValid(price, exchangePrices)) {
                log.warn("Price validation failed for {}:{}", exchange, symbol);
                continue;
            }

            int weight = getExchangeWeight(exchange);
            weightedSum += price * weight;
            totalWeight += weight;
            validCount++;

            // 保存成分数据
            saveComponent(symbol, exchange, price, weight);

            // 构建DTO
            IndexPriceDTO.ComponentDTO compDTO = new IndexPriceDTO.ComponentDTO();
            compDTO.setExchange(exchange);
            compDTO.setPrice(price);
            compDTO.setWeight(weight);
            compDTO.setValid(true);
            componentDTOs.add(compDTO);
        }

        // 4. 检查有效成分数
        if (validCount < config.getMinValidComponents()) {
            log.error("Not enough valid components for {}. Required: {}, Got: {}", 
                    symbol, config.getMinValidComponents(), validCount);
            return;
        }

        // 5. 计算最终指数价格
        long indexPrice = weightedSum / totalWeight;

        // 6. 保存到数据库
        IndexPrice priceEntity = new IndexPrice();
        priceEntity.setSymbol(symbol);
        priceEntity.setPrice(indexPrice);
        priceEntity.setSource("WEIGHTED_AVERAGE");
        priceEntity.setWeight(totalWeight);
        priceEntity.setTimestamp(System.currentTimeMillis());
        indexPriceMapper.insert(priceEntity);

        // 7. 更新Redis缓存
        IndexPriceDTO dto = new IndexPriceDTO();
        dto.setSymbol(symbol);
        dto.setPrice(indexPrice);
        dto.setTimestamp(priceEntity.getTimestamp());
        dto.setComponents(componentDTOs);

        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto, 
                    REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        // 8. 发布事件
        publishIndexPriceEvent(dto);

        log.debug("Updated index price for {}: {}", symbol, indexPrice);
    }

    @Override
    public void batchCalculateIndexPrices() {
        List<IndexPriceConfig> configs = configMapper.selectActiveConfigs();
        for (IndexPriceConfig config : configs) {
            try {
                calculateAndUpdateIndexPrice(config.getSymbol());
            } catch (Exception e) {
                log.error("Failed to calculate index price for {}", config.getSymbol(), e);
            }
        }
    }

    /**
     * 验证价格是否有效（检查是否偏离过多）
     */
    private boolean isPriceValid(Long price, Map<String, Long> allPrices) {
        if (price == null || price <= 0) {
            return false;
        }

        // 计算中位数
        List<Long> sortedPrices = allPrices.values().stream()
                .filter(p -> p != null && p > 0)
                .sorted()
                .collect(Collectors.toList());

        if (sortedPrices.isEmpty()) {
            return false;
        }

        long median = sortedPrices.get(sortedPrices.size() / 2);
        
        // 偏离不超过5%
        long deviation = Math.abs(price - median) * 100 / median;
        return deviation <= 5;
    }

    /**
     * 获取交易所权重
     */
    private int getExchangeWeight(String exchange) {
        return switch (exchange.toLowerCase()) {
            case "binance" -> 40;
            case "okx" -> 35;
            case "coinbase" -> 25;
            default -> 10;
        };
    }

    /**
     * 保存成分数据
     */
    private void saveComponent(String symbol, String exchange, Long price, int weight) {
        IndexPriceComponent component = new IndexPriceComponent();
        component.setSymbol(symbol);
        component.setExchange(exchange);
        component.setRawPrice(price);
        component.setWeight(weight);
        component.setValid(1);
        component.setTimestamp(System.currentTimeMillis());
        componentMapper.insert(component);
    }

    /**
     * 发布指数价格事件
     */
    private void publishIndexPriceEvent(IndexPriceDTO dto) {
        IndexPriceUpdateEvent event = new IndexPriceUpdateEvent();
        event.setEventTime(System.currentTimeMillis());
        
        IndexPriceUpdateEvent.IndexPriceData data = new IndexPriceUpdateEvent.IndexPriceData();
        data.setSymbol(dto.getSymbol());
        data.setPrice(dto.getPrice());
        data.setTimestamp(dto.getTimestamp());
        
        List<IndexPriceUpdateEvent.ComponentData> components = dto.getComponents().stream()
                .map(c -> {
                    IndexPriceUpdateEvent.ComponentData comp = new IndexPriceUpdateEvent.ComponentData();
                    comp.setExchange(c.getExchange());
                    comp.setPrice(c.getPrice());
                    comp.setWeight(c.getWeight());
                    return comp;
                })
                .collect(Collectors.toList());
        data.setComponents(components);
        
        event.setData(data);
        eventProducer.publishIndexPriceUpdate(event);
    }

    private IndexPriceDTO convertToDTO(IndexPrice entity) {
        IndexPriceDTO dto = new IndexPriceDTO();
        dto.setSymbol(entity.getSymbol());
        dto.setPrice(entity.getPrice());
        dto.setTimestamp(entity.getTimestamp());
        return dto;
    }
}
