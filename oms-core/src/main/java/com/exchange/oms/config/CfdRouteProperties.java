package com.exchange.oms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CFD 路由配置
 *
 * 配置示例：
 * oms:
 *   execution:
 *     default-mode: MATCH_ENGINE
 *     symbol-mode:
 *       BTCUSDT: CFD_DEALER
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oms.execution")
public class CfdRouteProperties {

    private static final String MODE_MATCH_ENGINE = "MATCH_ENGINE";
    private static final String MODE_CFD_DEALER = "CFD_DEALER";

    /**
     * 默认执行模式
     */
    private String defaultMode = MODE_MATCH_ENGINE;

    /**
     * 按交易对覆盖执行模式（key 建议大写 symbol）
     */
    private Map<String, String> symbolMode = new HashMap<>();

    public String resolveMode(String symbol, String requestedMode) {
        String explicitMode = normalizeMode(requestedMode);
        if (explicitMode != null) {
            return explicitMode;
        }

        if (symbol != null && !symbol.isBlank()) {
            String mappedMode = symbolMode.get(symbol.trim().toUpperCase(Locale.ROOT));
            String normalizedMapped = normalizeMode(mappedMode);
            if (normalizedMapped != null) {
                return normalizedMapped;
            }
        }

        String normalizedDefault = normalizeMode(defaultMode);
        return normalizedDefault == null ? MODE_MATCH_ENGINE : normalizedDefault;
    }

    public boolean isCfdDealer(String mode) {
        return MODE_CFD_DEALER.equalsIgnoreCase(mode);
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (MODE_MATCH_ENGINE.equals(normalized) || MODE_CFD_DEALER.equals(normalized)) {
            return normalized;
        }
        return null;
    }
}
