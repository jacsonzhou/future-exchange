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
 * B3 约束：
 * 1) 仅基于 index 事件计算；
 * 2) 不允许随机溢价/假价回退；
 * 3) markPriceId 与 indexPriceId 建立一一关联。
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

    private static final String REDIS_KEY_PREFIX = "mark:price:";
    private static final long REDIS_CACHE_TTL_SECONDS = 30;

    // 最新指数输入缓存（由 index-price-update 消费驱动）
    private final Map<String, IndexReference> latestIndexRefBySymbol = new ConcurrentHashMap<>();
    // 去重：同一 indexPriceId 仅处理一次
    private final Map<String, String> lastProcessedIndexIdBySymbol = new ConcurrentHashMap<>();

    @Override
    public MarkPriceDTO getLatestMarkPrice(String symbol) {
        if (redisTemplate != null) {
            Object cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
            if (cached != null) {
                return (MarkPriceDTO) cached;
            }
        }

        MarkPrice markPrice = markPriceMapper.selectLatestBySymbol(symbol);
        if (markPrice == null) {
            return null;
        }

        MarkPriceDTO dto = convertToDTO(markPrice);
        cacheLatest(symbol, dto);
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
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalizedSymbol = symbol.trim().toUpperCase();
        IndexReference ref = latestIndexRefBySymbol.get(normalizedSymbol);
        if (ref == null) {
            log.warn("Index reference not available for symbol: {}", normalizedSymbol);
            return;
        }
        upsertMarkPrice(normalizedSymbol, ref);
    }

    @Override
    public void batchCalculateMarkPrices() {
        for (Map.Entry<String, IndexReference> entry : latestIndexRefBySymbol.entrySet()) {
            String symbol = entry.getKey();
            try {
                upsertMarkPrice(symbol, entry.getValue());
            } catch (Exception e) {
                log.error("Failed to calculate mark price for {}", symbol, e);
            }
        }
    }

    @Override
    @Transactional
    public void onIndexPriceUpdate(String symbol, Long indexPrice, String indexPriceId,
                                   Long sourceEventTime, String sourceTopic, Long sourceOffset) {
        if (symbol == null || symbol.isBlank() || indexPrice == null || indexPrice <= 0) {
            log.warn("Ignore invalid index update: symbol={}, indexPrice={}", symbol, indexPrice);
            return;
        }

        String normalizedSymbol = symbol.trim().toUpperCase();
        String normalizedIndexId = (indexPriceId == null || indexPriceId.isBlank())
                ? normalizedSymbol + "-" + (sourceEventTime == null ? System.currentTimeMillis() : sourceEventTime)
                : indexPriceId.trim();

        IndexReference ref = new IndexReference(
                indexPrice,
                normalizedIndexId,
                sourceEventTime != null ? sourceEventTime : System.currentTimeMillis(),
                sourceTopic,
                sourceOffset != null ? sourceOffset : -1L
        );
        latestIndexRefBySymbol.put(normalizedSymbol, ref);
        upsertMarkPrice(normalizedSymbol, ref);
    }

    private void upsertMarkPrice(String symbol, IndexReference ref) {
        if (ref == null || ref.indexPrice() == null || ref.indexPrice() <= 0) {
            return;
        }

        if (isDuplicateIndexId(symbol, ref.indexPriceId())) {
            log.debug("Skip duplicate mark price update: symbol={}, indexPriceId={}", symbol, ref.indexPriceId());
            return;
        }

        long markPrice = calculateDeterministicMarkPrice(ref.indexPrice());
        long now = System.currentTimeMillis();
        String markPriceId = buildMarkPriceId(symbol, ref);

        MarkPrice entity = new MarkPrice();
        entity.setSymbol(symbol);
        entity.setMarkPrice(markPrice);
        entity.setIndexPrice(ref.indexPrice());
        entity.setFundingRate(getCurrentFundingRate(symbol));
        entity.setNextFundingTime(getNextFundingTime(symbol));
        entity.setTimestamp(now);
        markPriceMapper.insert(entity);

        MarkPriceDTO dto = new MarkPriceDTO();
        dto.setMarkPriceId(markPriceId);
        dto.setIndexPriceId(ref.indexPriceId());
        dto.setSymbol(symbol);
        dto.setMarkPrice(markPrice);
        dto.setIndexPrice(ref.indexPrice());
        dto.setFundingRate(entity.getFundingRate());
        dto.setNextFundingTime(entity.getNextFundingTime());
        dto.setSource("index_price_event");
        dto.setSourceEventTime(ref.sourceEventTime());
        dto.setSourceTopic(ref.sourceTopic());
        dto.setSourceOffset(ref.sourceOffset());
        dto.setTimestamp(now);

        cacheLatest(symbol, dto);
        publishMarkPriceEvent(dto);
        markIndexIdProcessed(symbol, ref.indexPriceId());

        log.debug("Updated mark price: symbol={}, markPrice={}, indexPrice={}, indexPriceId={}",
                symbol, markPrice, ref.indexPrice(), ref.indexPriceId());
    }

    private long calculateDeterministicMarkPrice(Long indexPrice) {
        // B3: 去随机化，直接使用 index price 作为 mark price。
        return indexPrice;
    }

    private boolean isDuplicateIndexId(String symbol, String indexPriceId) {
        if (indexPriceId == null || indexPriceId.isBlank()) {
            return false;
        }
        String previous = lastProcessedIndexIdBySymbol.get(symbol);
        return indexPriceId.equals(previous);
    }

    private void markIndexIdProcessed(String symbol, String indexPriceId) {
        if (indexPriceId == null || indexPriceId.isBlank()) {
            return;
        }
        lastProcessedIndexIdBySymbol.put(symbol, indexPriceId);
    }

    private String buildMarkPriceId(String symbol, IndexReference ref) {
        return symbol + "-" + ref.sourceEventTime() + "-" + ref.sourceOffset();
    }

    private void cacheLatest(String symbol, MarkPriceDTO dto) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, dto,
                REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    private Long getCurrentFundingRate(String symbol) {
        return 10000L;
    }

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

    private void publishMarkPriceEvent(MarkPriceDTO dto) {
        MarkPriceUpdateEvent event = new MarkPriceUpdateEvent();
        event.setEventTime(System.currentTimeMillis());

        MarkPriceUpdateEvent.MarkPriceData data = new MarkPriceUpdateEvent.MarkPriceData();
        data.setMarkPriceId(dto.getMarkPriceId());
        data.setIndexPriceId(dto.getIndexPriceId());
        data.setSymbol(dto.getSymbol());
        data.setMarkPrice(dto.getMarkPrice());
        data.setIndexPrice(dto.getIndexPrice());
        data.setFundingRate(dto.getFundingRate());
        data.setNextFundingTime(dto.getNextFundingTime());
        data.setSource(dto.getSource());
        data.setSourceEventTime(dto.getSourceEventTime());
        data.setSourceTopic(dto.getSourceTopic());
        data.setSourceOffset(dto.getSourceOffset());
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

    private record IndexReference(Long indexPrice, String indexPriceId, Long sourceEventTime,
                                  String sourceTopic, Long sourceOffset) {
    }
}
