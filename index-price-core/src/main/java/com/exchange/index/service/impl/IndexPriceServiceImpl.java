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
import com.exchange.index.service.support.ExternalMarketStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private ExternalMarketStateStore marketStateStore;

    @Autowired
    private IndexPriceProducer eventProducer;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${index-price.calculation.source:binance_kafka}")
    private String calculationSource;

    @Value("${index-price.persistence.enabled:true}")
    private boolean persistenceEnabled;

    private static final String REDIS_KEY_PREFIX = "index:price:";
    private static final long REDIS_CACHE_TTL_SECONDS = 60;
    private final Map<String, String> lastPublishedIndexPriceId = new ConcurrentHashMap<>();

    @Override
    public IndexPriceDTO getLatestIndexPrice(String symbol) {
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
            if (cached != null) {
                return (IndexPriceDTO) cached;
            }
        }

        IndexPrice indexPrice = indexPriceMapper.selectLatestBySymbol(symbol);
        if (indexPrice == null) {
            return null;
        }

        IndexPriceDTO dto = convertToDTO(indexPrice);
        cacheLatest(symbol, dto);
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
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalizedSymbol = symbol.trim().toUpperCase();

        if (isBinanceKafkaSource()) {
            calculateWithExternalMarketState(normalizedSymbol);
            return;
        }

        calculateWithConfiguredComponents(normalizedSymbol);
    }

    private void calculateWithConfiguredComponents(String symbol) {
        IndexPriceConfig config = configMapper.selectBySymbol(symbol);
        if (config == null) {
            log.warn("No index price config found for symbol: {}", symbol);
            return;
        }

        String[] exchanges = config.getComponents().split(",");
        Map<String, Long> exchangePrices = priceFetcher.fetchPricesFromMultipleExchanges(exchanges, symbol);

        if (exchangePrices.isEmpty()) {
            log.error("Failed to fetch prices from all exchanges for symbol: {}", symbol);
            return;
        }

        long weightedSum = 0;
        int totalWeight = 0;
        int validCount = 0;
        List<IndexPriceDTO.ComponentDTO> componentDTOs = new ArrayList<>();

        for (Map.Entry<String, Long> entry : exchangePrices.entrySet()) {
            String exchange = entry.getKey();
            Long price = entry.getValue();

            if (!isPriceValid(price, exchangePrices)) {
                log.warn("Price validation failed for {}:{}", exchange, symbol);
                continue;
            }

            int weight = getExchangeWeight(exchange);
            weightedSum += price * weight;
            totalWeight += weight;
            validCount++;

            saveComponent(symbol, exchange, price, weight, System.currentTimeMillis());

            IndexPriceDTO.ComponentDTO compDTO = new IndexPriceDTO.ComponentDTO();
            compDTO.setExchange(exchange);
            compDTO.setPrice(price);
            compDTO.setWeight(weight);
            compDTO.setValid(true);
            componentDTOs.add(compDTO);
        }

        int minValidComponents = config.getMinValidComponents() == null ? 1 : config.getMinValidComponents();
        if (validCount < minValidComponents) {
            log.error("Not enough valid components for {}. Required: {}, Got: {}",
                    symbol, minValidComponents, validCount);
            return;
        }

        long indexPrice = weightedSum / totalWeight;
        long timestamp = System.currentTimeMillis();

        IndexPrice priceEntity = new IndexPrice();
        priceEntity.setSymbol(symbol);
        priceEntity.setPrice(indexPrice);
        priceEntity.setSource("WEIGHTED_AVERAGE");
        priceEntity.setWeight(totalWeight);
        priceEntity.setTimestamp(timestamp);
        persistIndexPrice(symbol, priceEntity);

        IndexPriceDTO dto = new IndexPriceDTO();
        dto.setSymbol(symbol);
        dto.setPrice(indexPrice);
        dto.setTimestamp(timestamp);
        dto.setSource("weighted_average");
        dto.setSourceEventTime(timestamp);
        dto.setIndexPriceId(buildIndexPriceId(symbol, timestamp, timestamp));
        dto.setComponents(componentDTOs);

        cacheLatest(symbol, dto);
        publishIndexPriceEvent(dto);

        log.debug("Updated index price for {}: {}", symbol, indexPrice);
    }

    private void calculateWithExternalMarketState(String symbol) {
        ExternalMarketStateStore.MarketSnapshot snapshot = marketStateStore.getSnapshot(symbol);
        if (snapshot == null) {
            log.warn("No external market snapshot available for symbol: {}", symbol);
            return;
        }

        long indexPrice = resolveIndexPrice(snapshot);
        if (indexPrice <= 0) {
            log.warn("Invalid external market snapshot for symbol={}, bid={}, ask={}, trade={}",
                    symbol, snapshot.getBestBid(), snapshot.getBestAsk(), snapshot.getLastTradePrice());
            return;
        }

        long timestamp = snapshot.getSourceEventTime() > 0
                ? snapshot.getSourceEventTime()
                : System.currentTimeMillis();
        long sourceOffset = snapshot.getSourceOffset();
        String indexPriceId = buildIndexPriceId(symbol, timestamp, sourceOffset);
        if (isDuplicateIndexPrice(symbol, indexPriceId)) {
            log.debug("Skip duplicate index price event: symbol={}, indexPriceId={}", symbol, indexPriceId);
            return;
        }

        saveComponent(symbol, "binance", indexPrice, 100, timestamp);

        IndexPrice priceEntity = new IndexPrice();
        priceEntity.setSymbol(symbol);
        priceEntity.setPrice(indexPrice);
        priceEntity.setSource("BINANCE_KAFKA");
        priceEntity.setWeight(100);
        priceEntity.setTimestamp(timestamp);
        persistIndexPrice(symbol, priceEntity);

        IndexPriceDTO.ComponentDTO componentDTO = new IndexPriceDTO.ComponentDTO();
        componentDTO.setExchange("binance");
        componentDTO.setPrice(indexPrice);
        componentDTO.setWeight(100);
        componentDTO.setValid(true);

        IndexPriceDTO dto = new IndexPriceDTO();
        dto.setSymbol(symbol);
        dto.setPrice(indexPrice);
        dto.setTimestamp(timestamp);
        dto.setIndexPriceId(indexPriceId);
        dto.setSource("binance");
        dto.setSourceEventTime(timestamp);
        dto.setSourceTopic(snapshot.getSourceTopic());
        dto.setSourceOffset(sourceOffset);
        dto.setBestBid(snapshot.getBestBid());
        dto.setBestAsk(snapshot.getBestAsk());
        dto.setLastTradePrice(snapshot.getLastTradePrice());
        dto.setComponents(List.of(componentDTO));

        cacheLatest(symbol, dto);
        publishIndexPriceEvent(dto);

        log.debug("Updated index price from Binance Kafka snapshot: symbol={}, price={}, topic={}, offset={}",
                symbol, indexPrice, snapshot.getSourceTopic(), snapshot.getSourceOffset());
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

    private boolean isPriceValid(Long price, Map<String, Long> allPrices) {
        if (price == null || price <= 0) {
            return false;
        }

        List<Long> sortedPrices = allPrices.values().stream()
                .filter(p -> p != null && p > 0)
                .sorted()
                .collect(Collectors.toList());

        if (sortedPrices.isEmpty()) {
            return false;
        }

        long median = sortedPrices.get(sortedPrices.size() / 2);
        long deviation = Math.abs(price - median) * 100 / median;
        return deviation <= 5;
    }

    private int getExchangeWeight(String exchange) {
        return switch (exchange.toLowerCase()) {
            case "binance" -> 40;
            case "okx" -> 35;
            case "coinbase" -> 25;
            default -> 10;
        };
    }

    private boolean isBinanceKafkaSource() {
        return "binance_kafka".equalsIgnoreCase(calculationSource);
    }

    private String buildIndexPriceId(String symbol, long sourceEventTime, long sourceOffset) {
        return symbol + "-" + sourceEventTime + "-" + sourceOffset;
    }

    private boolean isDuplicateIndexPrice(String symbol, String indexPriceId) {
        String previous = lastPublishedIndexPriceId.put(symbol, indexPriceId);
        return indexPriceId.equals(previous);
    }

    private long resolveIndexPrice(ExternalMarketStateStore.MarketSnapshot snapshot) {
        long bestBid = snapshot.getBestBid();
        long bestAsk = snapshot.getBestAsk();
        if (bestBid > 0 && bestAsk > 0 && bestAsk >= bestBid) {
            return bestBid + (bestAsk - bestBid) / 2;
        }
        if (snapshot.getLastTradePrice() > 0) {
            return snapshot.getLastTradePrice();
        }
        if (bestBid > 0) {
            return bestBid;
        }
        if (bestAsk > 0) {
            return bestAsk;
        }
        return 0L;
    }

    private void saveComponent(String symbol, String exchange, Long price, int weight, long timestamp) {
        if (!persistenceEnabled) {
            return;
        }
        IndexPriceComponent component = new IndexPriceComponent();
        component.setSymbol(symbol);
        component.setExchange(exchange);
        component.setRawPrice(price);
        component.setWeight(weight);
        component.setValid(1);
        component.setTimestamp(timestamp);
        try {
            componentMapper.insert(component);
        } catch (Exception e) {
            log.warn("Skip component persistence, symbol={}, exchange={}", symbol, exchange, e);
        }
    }

    private void persistIndexPrice(String symbol, IndexPrice priceEntity) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            indexPriceMapper.insert(priceEntity);
        } catch (Exception e) {
            log.warn("Skip index-price persistence, symbol={}", symbol, e);
        }
    }

    private void cacheLatest(String symbol, IndexPriceDTO dto) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto,
                REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    private void publishIndexPriceEvent(IndexPriceDTO dto) {
        IndexPriceUpdateEvent event = new IndexPriceUpdateEvent();
        event.setEventTime(System.currentTimeMillis());

        IndexPriceUpdateEvent.IndexPriceData data = new IndexPriceUpdateEvent.IndexPriceData();
        data.setIndexPriceId(dto.getIndexPriceId());
        data.setSymbol(dto.getSymbol());
        data.setPrice(dto.getPrice());
        data.setTimestamp(dto.getTimestamp());
        data.setSource(dto.getSource());
        data.setSourceEventTime(dto.getSourceEventTime());
        data.setSourceTopic(dto.getSourceTopic());
        data.setSourceOffset(dto.getSourceOffset());
        data.setBestBid(dto.getBestBid());
        data.setBestAsk(dto.getBestAsk());
        data.setLastTradePrice(dto.getLastTradePrice());

        List<IndexPriceDTO.ComponentDTO> sourceComponents = dto.getComponents() == null
                ? List.of()
                : dto.getComponents();

        List<IndexPriceUpdateEvent.ComponentData> components = sourceComponents.stream()
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
