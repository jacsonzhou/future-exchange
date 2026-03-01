package com.exchange.cfddealer.service.impl;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.ReferenceBookSnapshot;
import com.exchange.cfddealer.service.ReferencePricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferencePricingServiceImpl implements ReferencePricingService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;

    @Override
    public ReferenceBookSnapshot getReferenceBook(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String redisKey = properties.getReferenceRedisPrefix() + normalizedSymbol;
        String raw = stringRedisTemplate.opsForValue().get(redisKey);
        if (raw == null) {
            return null;
        }

        try {
            ReferenceBookSnapshot snapshot = objectMapper.readValue(raw, ReferenceBookSnapshot.class);
            if (snapshot.getStalenessMs() == null && snapshot.getEventTime() != null && snapshot.getEventTime() > 0) {
                snapshot.setStalenessMs(Math.max(0L, System.currentTimeMillis() - snapshot.getEventTime()));
            }
            return snapshot;
        } catch (Exception e) {
            log.error("[CFD-DEALER] parse reference book failed, symbol={}, key={}", normalizedSymbol, redisKey, e);
            return null;
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
