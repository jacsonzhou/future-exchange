package com.exchange.match.config;

import org.springframework.context.annotation.Configuration;

/**
 * 撮合引擎配置
 *
 * 注：OrderBook 不再作为 Spring Bean 管理，改由 MatchEngine 统一管理。
 * 每个 symbol 对应一个 OrderBook，通过 MatchEngine.getOrderBook(symbol) 获取。
 */
@Configuration
public class MatchEngineConfig {
}

