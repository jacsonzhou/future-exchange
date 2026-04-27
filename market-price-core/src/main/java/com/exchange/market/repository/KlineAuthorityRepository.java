package com.exchange.market.repository;

import com.alibaba.fastjson2.JSON;
import com.exchange.market.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * K 线权威仓库（ClickHouse 持久化）。
 *
 * 设计目标：
 * 1. 权威数据跨重启可恢复
 * 2. watermark 跨重启可恢复
 * 3. 冲突可审计
 */
@Slf4j
@Repository
public class KlineAuthorityRepository {

    private static final HexFormat HEX = HexFormat.of();

    private final DataSource clickHouseDataSource;

    public enum SaveResult {
        INSERTED,
        UPDATED,
        DUPLICATE,
        CONFLICT
    }

    public KlineAuthorityRepository(DataSource clickHouseDataSource) {
        this.clickHouseDataSource = clickHouseDataSource;
    }

    @PostConstruct
    public void ensureSchema() {
        try (Connection conn = clickHouseDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kline_authority (
                        source String,
                        symbol String,
                        interval String,
                        open_time DateTime64(3, 'UTC'),
                        close_time DateTime64(3, 'UTC'),
                        open_price Decimal64(8),
                        high_price Decimal64(8),
                        low_price Decimal64(8),
                        close_price Decimal64(8),
                        volume Decimal64(8),
                        quote_volume Decimal64(8),
                        trade_count UInt32,
                        taker_buy_volume Decimal64(8),
                        taker_buy_quote_volume Decimal64(8),
                        candle_closed UInt8,
                        payload_hash String,
                        first_seen_at DateTime64(3, 'UTC'),
                        last_seen_at DateTime64(3, 'UTC'),
                        updated_at_ms UInt64
                    )
                    ENGINE = ReplacingMergeTree(updated_at_ms)
                    PARTITION BY toYYYYMM(open_time)
                    ORDER BY (source, symbol, interval, open_time)
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kline_backfill_watermark (
                        source String,
                        symbol String,
                        interval String,
                        last_closed_open_time_ms UInt64,
                        last_event_time_ms UInt64,
                        updated_at_ms UInt64
                    )
                    ENGINE = ReplacingMergeTree(updated_at_ms)
                    ORDER BY (source, symbol, interval)
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS kline_authority_conflict (
                        source String,
                        symbol String,
                        interval String,
                        open_time_ms UInt64,
                        existing_hash String,
                        incoming_hash String,
                        detected_at_ms UInt64
                    )
                    ENGINE = MergeTree
                    PARTITION BY toYYYYMM(toDateTime(detected_at_ms / 1000))
                    ORDER BY (source, symbol, interval, open_time_ms, detected_at_ms)
                    """);

            log.info("[KlineAuthorityRepository] ClickHouse authority schema ensured");
        } catch (Exception e) {
            log.warn("[KlineAuthorityRepository] Failed to ensure authority schema, reason={}", e.getMessage());
        }
    }

    public SaveResult save(String source, Kline kline, boolean candleClosed) {
        return save(source, kline, candleClosed, null);
    }

    public synchronized SaveResult save(String source, Kline kline, boolean candleClosed, String rawPayloadJson) {
        if (kline == null) {
            throw new IllegalArgumentException("kline must not be null");
        }

        String normalizedSource = normalizeSource(source);
        String symbol = normalizeSymbol(kline.getSymbol());
        String interval = normalizeInterval(kline.getInterval());
        if (symbol == null || interval == null) {
            throw new IllegalArgumentException("symbol/interval must not be blank");
        }

        long now = System.currentTimeMillis();
        String payloadHash = hash(buildCanonicalPayload(kline));

        AuthorityRow existing = findAuthorityRow(normalizedSource, symbol, interval, kline.getOpenTime());
        if (existing == null) {
            AuthorityRow inserted = AuthorityRow.from(normalizedSource, symbol, interval, kline, candleClosed, payloadHash, now, now);
            insertAuthorityRow(inserted);
            return SaveResult.INSERTED;
        }

        if (payloadHash.equals(existing.payloadHash)) {
            existing.closeTime = Math.max(existing.closeTime, kline.getCloseTime());
            existing.candleClosed = existing.candleClosed || candleClosed;
            existing.lastSeenAtMs = now;
            existing.updatedAtMs = now;
            insertAuthorityRow(existing);
            return SaveResult.DUPLICATE;
        }

        if (existing.candleClosed) {
            insertConflict(normalizedSource, symbol, interval, kline.getOpenTime(), existing.payloadHash, payloadHash, now);
            return SaveResult.CONFLICT;
        }

        existing.closeTime = kline.getCloseTime();
        existing.openPrice = kline.getOpenPrice();
        existing.highPrice = kline.getHighPrice();
        existing.lowPrice = kline.getLowPrice();
        existing.closePrice = kline.getClosePrice();
        existing.volume = kline.getVolume();
        existing.quoteVolume = kline.getQuoteVolume();
        existing.tradeCount = kline.getTradeCount();
        existing.takerBuyVolume = kline.getTakerBuyVolume();
        existing.takerBuyQuoteVolume = kline.getTakerBuyQuoteVolume();
        existing.candleClosed = candleClosed;
        existing.payloadHash = payloadHash;
        existing.lastSeenAtMs = now;
        existing.updatedAtMs = now;
        insertAuthorityRow(existing);
        return SaveResult.UPDATED;
    }

    public Long findLatestOpenTime(String source, String symbol, String interval) {
        String sql = """
                SELECT count() AS c, max(toUnixTimestamp64Milli(open_time)) AS ts
                FROM kline_authority FINAL
                WHERE source = ? AND symbol = ? AND interval = ?
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeSource(source));
            ps.setString(2, normalizeSymbol(symbol));
            ps.setString(3, normalizeInterval(interval));
            ResultSet rs = ps.executeQuery();
            if (!rs.next() || rs.getLong("c") == 0) {
                return null;
            }
            return parseLong(rs.getObject("ts"));
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to query latest open time, source={}, symbol={}, interval={}",
                    source, symbol, interval, e);
            throw new RuntimeException("failed to query latest authority open time", e);
        }
    }

    public Long findEarliestOpenTime(String source, String symbol, String interval) {
        String sql = """
                SELECT count() AS c, min(toUnixTimestamp64Milli(open_time)) AS ts
                FROM kline_authority FINAL
                WHERE source = ? AND symbol = ? AND interval = ?
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeSource(source));
            ps.setString(2, normalizeSymbol(symbol));
            ps.setString(3, normalizeInterval(interval));
            ResultSet rs = ps.executeQuery();
            if (!rs.next() || rs.getLong("c") == 0) {
                return null;
            }
            return parseLong(rs.getObject("ts"));
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to query earliest open time, source={}, symbol={}, interval={}",
                    source, symbol, interval, e);
            throw new RuntimeException("failed to query earliest authority open time", e);
        }
    }

    public Long findWatermark(String source, String symbol, String interval) {
        WatermarkRow row = findWatermarkRow(source, symbol, interval);
        return row == null ? null : row.lastClosedOpenTimeMs;
    }

    public synchronized void upsertWatermark(String source,
                                             String symbol,
                                             String interval,
                                             long lastClosedOpenTime,
                                             long lastEventTime) {
        String normalizedSource = normalizeSource(source);
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(interval);
        if (normalizedSymbol == null || normalizedInterval == null) {
            throw new IllegalArgumentException("symbol/interval must not be blank");
        }

        long now = System.currentTimeMillis();
        WatermarkRow current = findWatermarkRow(normalizedSource, normalizedSymbol, normalizedInterval);
        long finalClosed = current == null
                ? Math.max(0L, lastClosedOpenTime)
                : Math.max(current.lastClosedOpenTimeMs, Math.max(0L, lastClosedOpenTime));
        long finalEvent = current == null
                ? Math.max(0L, lastEventTime)
                : Math.max(current.lastEventTimeMs, Math.max(0L, lastEventTime));

        String sql = """
                INSERT INTO kline_backfill_watermark (
                    source, symbol, interval,
                    last_closed_open_time_ms, last_event_time_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizedSource);
            ps.setString(2, normalizedSymbol);
            ps.setString(3, normalizedInterval);
            ps.setLong(4, finalClosed);
            ps.setLong(5, finalEvent);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to upsert watermark, source={}, symbol={}, interval={}",
                    source, symbol, interval, e);
            throw new RuntimeException("failed to upsert watermark", e);
        }
    }

    public List<Long> listOpenTimes(String source,
                                    String symbol,
                                    String interval,
                                    long startInclusive,
                                    long endInclusive,
                                    int limit) {
        if (startInclusive > endInclusive || limit <= 0) {
            return List.of();
        }

        String sql = """
                SELECT DISTINCT toUnixTimestamp64Milli(open_time) AS open_time_ms
                FROM kline_authority FINAL
                WHERE source = ? AND symbol = ? AND interval = ?
                  AND open_time >= toDateTime64(?, 3, 'UTC')
                  AND open_time <= toDateTime64(?, 3, 'UTC')
                ORDER BY open_time_ms ASC
                LIMIT ?
                """;
        List<Long> result = new ArrayList<>();
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeSource(source));
            ps.setString(2, normalizeSymbol(symbol));
            ps.setString(3, normalizeInterval(interval));
            ps.setDouble(4, startInclusive / 1000.0);
            ps.setDouble(5, endInclusive / 1000.0);
            ps.setInt(6, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Long value = parseLong(rs.getObject("open_time_ms"));
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to list authority open times, source={}, symbol={}, interval={}",
                    source, symbol, interval, e);
            throw new RuntimeException("failed to list authority open times", e);
        }
    }

    public long countConflicts(String source, String symbol, String interval, long openTime) {
        String sql = """
                SELECT count() AS c
                FROM kline_authority_conflict
                WHERE source = ? AND symbol = ? AND interval = ? AND open_time_ms = ?
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeSource(source));
            ps.setString(2, normalizeSymbol(symbol));
            ps.setString(3, normalizeInterval(interval));
            ps.setLong(4, openTime);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong("c");
            }
            return 0L;
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to count conflicts, source={}, symbol={}, interval={}, openTime={}",
                    source, symbol, interval, openTime, e);
            throw new RuntimeException("failed to count conflicts", e);
        }
    }

    private AuthorityRow findAuthorityRow(String source, String symbol, String interval, long openTime) {
        String sql = """
                SELECT
                    source, symbol, interval,
                    toUnixTimestamp64Milli(open_time) AS open_time_ms,
                    toUnixTimestamp64Milli(close_time) AS close_time_ms,
                    open_price, high_price, low_price, close_price,
                    volume, quote_volume, trade_count, taker_buy_volume, taker_buy_quote_volume,
                    candle_closed, payload_hash,
                    toUnixTimestamp64Milli(first_seen_at) AS first_seen_at_ms,
                    toUnixTimestamp64Milli(last_seen_at) AS last_seen_at_ms,
                    updated_at_ms
                FROM kline_authority FINAL
                WHERE source = ? AND symbol = ? AND interval = ?
                  AND open_time = toDateTime64(?, 3, 'UTC')
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, symbol);
            ps.setString(3, interval);
            ps.setDouble(4, openTime / 1000.0);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            AuthorityRow row = new AuthorityRow();
            row.source = source;
            row.symbol = symbol;
            row.interval = interval;
            row.openTime = parseLongOrDefault(rs.getObject("open_time_ms"));
            row.closeTime = parseLongOrDefault(rs.getObject("close_time_ms"));
            row.openPrice = readScaledLong(rs, "open_price");
            row.highPrice = readScaledLong(rs, "high_price");
            row.lowPrice = readScaledLong(rs, "low_price");
            row.closePrice = readScaledLong(rs, "close_price");
            row.volume = readScaledLong(rs, "volume");
            row.quoteVolume = readScaledLong(rs, "quote_volume");
            row.tradeCount = rs.getInt("trade_count");
            row.takerBuyVolume = readScaledLong(rs, "taker_buy_volume");
            row.takerBuyQuoteVolume = readScaledLong(rs, "taker_buy_quote_volume");
            row.candleClosed = rs.getInt("candle_closed") == 1;
            row.payloadHash = rs.getString("payload_hash");
            row.firstSeenAtMs = parseLongOrDefault(rs.getObject("first_seen_at_ms"));
            row.lastSeenAtMs = parseLongOrDefault(rs.getObject("last_seen_at_ms"));
            row.updatedAtMs = parseLongOrDefault(rs.getObject("updated_at_ms"));
            return row;
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to query authority row, source={}, symbol={}, interval={}, openTime={}",
                    source, symbol, interval, openTime, e);
            throw new RuntimeException("failed to query authority row", e);
        }
    }

    private void insertAuthorityRow(AuthorityRow row) {
        String sql = """
                INSERT INTO kline_authority (
                    source, symbol, interval, open_time, close_time,
                    open_price, high_price, low_price, close_price,
                    volume, quote_volume, trade_count, taker_buy_volume, taker_buy_quote_volume,
                    candle_closed, payload_hash, first_seen_at, last_seen_at, updated_at_ms
                )
                VALUES (
                    ?, ?, ?, toDateTime64(?, 3, 'UTC'), toDateTime64(?, 3, 'UTC'),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, toDateTime64(?, 3, 'UTC'),
                    toDateTime64(?, 3, 'UTC'), ?
                )
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.source);
            ps.setString(2, row.symbol);
            ps.setString(3, row.interval);
            ps.setDouble(4, row.openTime / 1000.0);
            ps.setDouble(5, row.closeTime / 1000.0);
            ps.setBigDecimal(6, toScaledDecimal(row.openPrice));
            ps.setBigDecimal(7, toScaledDecimal(row.highPrice));
            ps.setBigDecimal(8, toScaledDecimal(row.lowPrice));
            ps.setBigDecimal(9, toScaledDecimal(row.closePrice));
            ps.setBigDecimal(10, toScaledDecimal(row.volume));
            ps.setBigDecimal(11, toScaledDecimal(row.quoteVolume));
            ps.setInt(12, row.tradeCount);
            ps.setBigDecimal(13, toScaledDecimal(row.takerBuyVolume));
            ps.setBigDecimal(14, toScaledDecimal(row.takerBuyQuoteVolume));
            ps.setInt(15, row.candleClosed ? 1 : 0);
            ps.setString(16, row.payloadHash);
            ps.setDouble(17, row.firstSeenAtMs / 1000.0);
            ps.setDouble(18, row.lastSeenAtMs / 1000.0);
            ps.setLong(19, row.updatedAtMs);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to insert authority row, source={}, symbol={}, interval={}, openTime={}",
                    row.source, row.symbol, row.interval, row.openTime, e);
            throw new RuntimeException("failed to insert authority row", e);
        }
    }

    private WatermarkRow findWatermarkRow(String source, String symbol, String interval) {
        String sql = """
                SELECT last_closed_open_time_ms, last_event_time_ms, updated_at_ms
                FROM kline_backfill_watermark FINAL
                WHERE source = ? AND symbol = ? AND interval = ?
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeSource(source));
            ps.setString(2, normalizeSymbol(symbol));
            ps.setString(3, normalizeInterval(interval));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            WatermarkRow row = new WatermarkRow();
            row.lastClosedOpenTimeMs = parseLongOrDefault(rs.getObject("last_closed_open_time_ms"));
            row.lastEventTimeMs = parseLongOrDefault(rs.getObject("last_event_time_ms"));
            row.updatedAtMs = parseLongOrDefault(rs.getObject("updated_at_ms"));
            return row;
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to query watermark, source={}, symbol={}, interval={}",
                    source, symbol, interval, e);
            throw new RuntimeException("failed to query watermark", e);
        }
    }

    private void insertConflict(String source,
                                String symbol,
                                String interval,
                                long openTime,
                                String existingHash,
                                String incomingHash,
                                long detectedAtMs) {
        String sql = """
                INSERT INTO kline_authority_conflict (
                    source, symbol, interval, open_time_ms, existing_hash, incoming_hash, detected_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, symbol);
            ps.setString(3, interval);
            ps.setLong(4, openTime);
            ps.setString(5, existingHash == null ? "" : existingHash);
            ps.setString(6, incomingHash == null ? "" : incomingHash);
            ps.setLong(7, detectedAtMs);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[KlineAuthorityRepository] Failed to insert conflict row, source={}, symbol={}, interval={}, openTime={}",
                    source, symbol, interval, openTime, e);
            throw new RuntimeException("failed to insert conflict row", e);
        }
    }

    private BigDecimal toScaledDecimal(long value) {
        // 项目规范：long存储8位小数（实际值 × 10^8）
        // 插入ClickHouse Decimal64(8)需要除以10^8还原真实值
        return new BigDecimal(value).divide(new BigDecimal("100000000"), 8, java.math.RoundingMode.HALF_UP);
    }

    private long readScaledLong(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        if (value == null) {
            return 0L;
        }
        // ClickHouse存储真实值（Decimal64(8)），Java需要×10^8转换为long
        return value.multiply(new BigDecimal("100000000")).longValue();
    }

    private String buildCanonicalPayload(Kline kline) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", normalizeSymbol(kline.getSymbol()));
        payload.put("interval", normalizeInterval(kline.getInterval()));
        payload.put("openTime", kline.getOpenTime());
        payload.put("closeTime", kline.getCloseTime());
        payload.put("openPrice", kline.getOpenPrice());
        payload.put("highPrice", kline.getHighPrice());
        payload.put("lowPrice", kline.getLowPrice());
        payload.put("closePrice", kline.getClosePrice());
        payload.put("volume", kline.getVolume());
        payload.put("quoteVolume", kline.getQuoteVolume());
        payload.put("tradeCount", kline.getTradeCount());
        payload.put("takerBuyVolume", kline.getTakerBuyVolume());
        payload.put("takerBuyQuoteVolume", kline.getTakerBuyQuoteVolume());
        return JSON.toJSONString(payload);
    }

    private String hash(String canonicalPayload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash kline payload", e);
        }
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "binance";
        }
        return source.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSymbol(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeInterval(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private long parseLongOrDefault(Object raw) {
        Long parsed = parseLong(raw);
        return parsed == null ? 0L : parsed;
    }

    private static class AuthorityRow {
        private String source;
        private String symbol;
        private String interval;
        private long openTime;
        private long closeTime;
        private long openPrice;
        private long highPrice;
        private long lowPrice;
        private long closePrice;
        private long volume;
        private long quoteVolume;
        private int tradeCount;
        private long takerBuyVolume;
        private long takerBuyQuoteVolume;
        private boolean candleClosed;
        private String payloadHash;
        private long firstSeenAtMs;
        private long lastSeenAtMs;
        private long updatedAtMs;

        static AuthorityRow from(String source,
                                 String symbol,
                                 String interval,
                                 Kline kline,
                                 boolean candleClosed,
                                 String payloadHash,
                                 long firstSeenAtMs,
                                 long updatedAtMs) {
            AuthorityRow row = new AuthorityRow();
            row.source = source;
            row.symbol = symbol;
            row.interval = interval;
            row.openTime = kline.getOpenTime();
            row.closeTime = kline.getCloseTime();
            row.openPrice = kline.getOpenPrice();
            row.highPrice = kline.getHighPrice();
            row.lowPrice = kline.getLowPrice();
            row.closePrice = kline.getClosePrice();
            row.volume = kline.getVolume();
            row.quoteVolume = kline.getQuoteVolume();
            row.tradeCount = kline.getTradeCount();
            row.takerBuyVolume = kline.getTakerBuyVolume();
            row.takerBuyQuoteVolume = kline.getTakerBuyQuoteVolume();
            row.candleClosed = candleClosed;
            row.payloadHash = payloadHash;
            row.firstSeenAtMs = firstSeenAtMs;
            row.lastSeenAtMs = updatedAtMs;
            row.updatedAtMs = updatedAtMs;
            return row;
        }
    }

    private static class WatermarkRow {
        private long lastClosedOpenTimeMs;
        private long lastEventTimeMs;
        private long updatedAtMs;
    }
}
