package com.exchange.market.repository;

import com.exchange.market.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * K 线数据 ClickHouse 存储仓库
 * 
 * 高性能 K 线数据存取
 */
@Slf4j
@Repository
public class KlineRepository {

    @Autowired
    private DataSource clickHouseDataSource;

    // 批量插入大小
    private static final int BATCH_SIZE = 1000;

    private static final Map<String, IntervalAggregationSpec> AGGREGATION_SPECS = Map.ofEntries(
            Map.entry("5m", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 5 MINUTE)",
                    "addMilliseconds(addMinutes(bucket_open, 5), -1)",
                    5L * 60_000L
            )),
            Map.entry("15m", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 15 MINUTE)",
                    "addMilliseconds(addMinutes(bucket_open, 15), -1)",
                    15L * 60_000L
            )),
            Map.entry("30m", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 30 MINUTE)",
                    "addMilliseconds(addMinutes(bucket_open, 30), -1)",
                    30L * 60_000L
            )),
            Map.entry("1h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 1 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 1), -1)",
                    3_600_000L
            )),
            Map.entry("2h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 2 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 2), -1)",
                    2L * 3_600_000L
            )),
            Map.entry("4h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 4 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 4), -1)",
                    4L * 3_600_000L
            )),
            Map.entry("6h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 6 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 6), -1)",
                    6L * 3_600_000L
            )),
            Map.entry("8h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 8 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 8), -1)",
                    8L * 3_600_000L
            )),
            Map.entry("12h", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 12 HOUR)",
                    "addMilliseconds(addHours(bucket_open, 12), -1)",
                    12L * 3_600_000L
            )),
            Map.entry("1d", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 1 DAY)",
                    "addMilliseconds(addDays(bucket_open, 1), -1)",
                    86_400_000L
            )),
            Map.entry("3d", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 3 DAY)",
                    "addMilliseconds(addDays(bucket_open, 3), -1)",
                    3L * 86_400_000L
            )),
            Map.entry("1w", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 1 WEEK)",
                    "addMilliseconds(addWeeks(bucket_open, 1), -1)",
                    7L * 86_400_000L
            )),
            Map.entry("1M", new IntervalAggregationSpec(
                    "toStartOfInterval(open_time, INTERVAL 1 MONTH)",
                    "addMilliseconds(addMonths(bucket_open, 1), -1)",
                    30L * 86_400_000L
            ))
    );

    /**
     * 保存 K 线数据（单条）
     */
    public void save(Kline kline) {
        String sql = "INSERT INTO kline_data (symbol, interval, open_time, close_time, " +
                "open_price, high_price, low_price, close_price, volume, quote_volume, " +
                "trade_count, taker_buy_volume, taker_buy_quote_volume) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            setKlineParams(ps, kline);
            ps.executeUpdate();

            log.debug("[KlineRepository] Saved kline: {} {} @ {}", 
                kline.getSymbol(), kline.getInterval(), kline.getOpenTime());

        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to save kline: {}", kline, e);
            throw new RuntimeException("Failed to save kline", e);
        }
    }

    /**
     * 幂等保存 K 线（同 symbol + interval + open_time 仅插入一次）。
     */
    public void saveIfAbsent(Kline kline) {
        String sql = "INSERT INTO kline_data (symbol, interval, open_time, close_time, " +
                "open_price, high_price, low_price, close_price, volume, quote_volume, " +
                "trade_count, taker_buy_volume, taker_buy_quote_volume) " +
                "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? " +
                "WHERE NOT EXISTS (" +
                "  SELECT 1 FROM kline_data WHERE symbol = ? AND interval = ? " +
                "  AND open_time = toDateTime64(?, 3) LIMIT 1" +
                ")";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setKlineParams(ps, kline);
            ps.setString(14, kline.getSymbol());
            ps.setString(15, kline.getInterval());
            ps.setDouble(16, kline.getOpenTime() / 1000.0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to save-if-absent kline: {}", kline, e);
            throw new RuntimeException("Failed to save-if-absent kline", e);
        }
    }

    /**
     * 批量保存 K 线数据
     */
    public void saveBatch(List<Kline> klines) {
        if (klines == null || klines.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO kline_data (symbol, interval, open_time, close_time, " +
                "open_price, high_price, low_price, close_price, volume, quote_volume, " +
                "trade_count, taker_buy_volume, taker_buy_quote_volume) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int count = 0;
            for (Kline kline : klines) {
                setKlineParams(ps, kline);
                ps.addBatch();
                count++;

                if (count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                    log.debug("[KlineRepository] Batch saved {} klines", count);
                }
            }

            // 执行剩余的
            if (count % BATCH_SIZE != 0) {
                ps.executeBatch();
            }

            log.info("[KlineRepository] Total saved {} klines", count);

        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to batch save klines", e);
            throw new RuntimeException("Failed to batch save klines", e);
        }
    }

    /**
     * 保存实时 K 线（未完成）
     */
    public void saveRealtime(Kline kline) {
        String sql = "INSERT INTO kline_realtime (symbol, interval, open_time, close_time, " +
                "open_price, high_price, low_price, close_price, volume, quote_volume, " +
                "trade_count, taker_buy_volume, taker_buy_quote_volume, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            setKlineParams(ps, kline);
            ps.setLong(14, System.currentTimeMillis());
            ps.executeUpdate();

        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to save realtime kline: {}", kline, e);
            throw new RuntimeException("Failed to save realtime kline", e);
        }
    }

    /**
     * 查询 K 线数据
     */
    public List<Kline> query(String symbol, String interval, 
                             Long startTime, Long endTime, Integer limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT symbol, interval, open_time, close_time, " +
            "open_price, high_price, low_price, close_price, volume, quote_volume, " +
            "trade_count, taker_buy_volume, taker_buy_quote_volume " +
            "FROM kline_data " +
            "WHERE symbol = ? AND interval = ? "
        );

        List<Object> params = new ArrayList<>();
        params.add(symbol);
        params.add(interval);

        if (startTime != null) {
            sql.append("AND open_time >= toDateTime64(?, 3) ");
            params.add(startTime / 1000.0);
        }

        if (endTime != null) {
            sql.append("AND open_time <= toDateTime64(?, 3) ");
            params.add(endTime / 1000.0);
        }

        sql.append("ORDER BY open_time DESC ");

        if (limit != null && limit > 0) {
            sql.append("LIMIT ?");
            params.add(limit);
        }

        List<Kline> result = new ArrayList<>();

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapResultSetToKline(rs));
            }

            log.debug("[KlineRepository] Queried {} klines for {} {}", 
                result.size(), symbol, interval);

        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to query klines", e);
            throw new RuntimeException("Failed to query klines", e);
        }

        return result;
    }

    /**
     * 从 1m K 线聚合生成更大周期 K 线。
     */
    public List<Kline> queryAggregatedFrom1m(String symbol, String interval,
                                             Long startTime, Long endTime, Integer limit) {
        IntervalAggregationSpec spec = resolveAggregationSpec(interval);
        if (spec == null) {
            return List.of();
        }

        long safeEndTime = endTime != null ? endTime : System.currentTimeMillis();
        Long safeStartTime = startTime;
        if (safeStartTime == null && limit != null && limit > 0) {
            long lookbackIntervals = Math.max(limit + 5L, (long) Math.ceil(limit * 1.5d));
            long lookback = spec.approxIntervalMs * lookbackIntervals;
            safeStartTime = Math.max(0L, safeEndTime - lookback);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT symbol, ? AS interval, bucket_open AS open_time, " +
                        spec.closeTimeExpr + " AS close_time, " +
                        "argMin(open_price, open_time) AS open_price, " +
                        "max(high_price) AS high_price, " +
                        "min(low_price) AS low_price, " +
                        "argMax(close_price, open_time) AS close_price, " +
                        "sum(volume) AS volume, " +
                        "sum(quote_volume) AS quote_volume, " +
                        "toUInt32(sum(trade_count)) AS trade_count, " +
                        "sum(taker_buy_volume) AS taker_buy_volume, " +
                        "sum(taker_buy_quote_volume) AS taker_buy_quote_volume " +
                        "FROM (" +
                        "SELECT symbol, open_time, open_price, high_price, low_price, close_price, " +
                        "volume, quote_volume, trade_count, taker_buy_volume, taker_buy_quote_volume, " +
                        spec.bucketExpr + " AS bucket_open " +
                        "FROM kline_data WHERE symbol = ? AND interval = '1m' "
        );

        List<Object> params = new ArrayList<>();
        params.add(interval);
        params.add(symbol);

        if (safeStartTime != null) {
            sql.append("AND open_time >= toDateTime64(?, 3) ");
            params.add(safeStartTime / 1000.0);
        }
        sql.append("AND open_time <= toDateTime64(?, 3) ");
        params.add(safeEndTime / 1000.0);

        sql.append(") t GROUP BY symbol, bucket_open ORDER BY bucket_open DESC ");
        if (limit != null && limit > 0) {
            sql.append("LIMIT ?");
            params.add(limit);
        }

        List<Kline> result = new ArrayList<>();
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapResultSetToKline(rs));
            }

            log.debug("[KlineRepository] Aggregated {} klines from 1m for {} {}",
                    result.size(), symbol, interval);
            return result;
        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to aggregate klines from 1m, symbol={}, interval={}",
                    symbol, interval, e);
            throw new RuntimeException("Failed to aggregate klines from 1m", e);
        }
    }

    public boolean supports1mAggregation(String interval) {
        return resolveAggregationSpec(interval) != null;
    }

    /**
     * 获取最新 K 线
     */
    public Kline getLatest(String symbol, String interval) {
        String sql = "SELECT * FROM kline_realtime FINAL " +
                "WHERE symbol = ? AND interval = ? " +
                "ORDER BY open_time DESC LIMIT 1";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, symbol);
            ps.setString(2, interval);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapResultSetToKline(rs);
            }

        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to get latest kline", e);
        }

        return null;
    }

    /**
     * 获取最近 N 条 K 线
     */
    public List<Kline> getRecent(String symbol, String interval, int limit) {
        return query(symbol, interval, null, null, limit);
    }

    /**
     * 查询历史表最新 open_time（毫秒）。
     */
    public Long getLatestOpenTime(String symbol, String interval) {
        String sql = "SELECT max(open_time) AS latest_open_time " +
                "FROM kline_data WHERE symbol = ? AND interval = ?";
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("latest_open_time");
                return ts != null ? ts.getTime() : null;
            }
            return null;
        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to query latest open time, symbol={}, interval={}",
                    symbol, interval, e);
            throw new RuntimeException("Failed to query latest open time", e);
        }
    }

    /**
     * 查询历史表最早 open_time（毫秒）。
     */
    public Long getEarliestOpenTime(String symbol, String interval) {
        String sql = "SELECT min(open_time) AS earliest_open_time " +
                "FROM kline_data WHERE symbol = ? AND interval = ?";
        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp("earliest_open_time");
                return ts != null ? ts.getTime() : null;
            }
            return null;
        } catch (SQLException e) {
            log.error("[KlineRepository] Failed to query earliest open time, symbol={}, interval={}",
                    symbol, interval, e);
            throw new RuntimeException("Failed to query earliest open time", e);
        }
    }

    /**
     * 设置 PreparedStatement 参数
     */
    private void setKlineParams(PreparedStatement ps, Kline kline) throws SQLException {
        ps.setString(1, kline.getSymbol());
        ps.setString(2, kline.getInterval());
        ps.setTimestamp(3, new Timestamp(kline.getOpenTime()));
        ps.setTimestamp(4, new Timestamp(kline.getCloseTime()));
        ps.setBigDecimal(5, new java.math.BigDecimal(kline.getOpenPrice()));
        ps.setBigDecimal(6, new java.math.BigDecimal(kline.getHighPrice()));
        ps.setBigDecimal(7, new java.math.BigDecimal(kline.getLowPrice()));
        ps.setBigDecimal(8, new java.math.BigDecimal(kline.getClosePrice()));
        ps.setBigDecimal(9, new java.math.BigDecimal(kline.getVolume()));
        ps.setBigDecimal(10, new java.math.BigDecimal(kline.getQuoteVolume()));
        ps.setInt(11, kline.getTradeCount());
        ps.setBigDecimal(12, new java.math.BigDecimal(kline.getTakerBuyVolume()));
        ps.setBigDecimal(13, new java.math.BigDecimal(kline.getTakerBuyQuoteVolume()));
    }

    /**
     * 将 ResultSet 映射为 Kline
     */
    private Kline mapResultSetToKline(ResultSet rs) throws SQLException {
        Kline kline = new Kline();
        kline.setSymbol(rs.getString("symbol"));
        kline.setInterval(rs.getString("interval"));
        kline.setOpenTime(rs.getTimestamp("open_time").getTime());
        kline.setCloseTime(rs.getTimestamp("close_time").getTime());
        kline.setOpenPrice(rs.getBigDecimal("open_price").longValue());
        kline.setHighPrice(rs.getBigDecimal("high_price").longValue());
        kline.setLowPrice(rs.getBigDecimal("low_price").longValue());
        kline.setClosePrice(rs.getBigDecimal("close_price").longValue());
        kline.setVolume(rs.getBigDecimal("volume").longValue());
        kline.setQuoteVolume(rs.getBigDecimal("quote_volume").longValue());
        kline.setTradeCount(rs.getInt("trade_count"));
        kline.setTakerBuyVolume(rs.getBigDecimal("taker_buy_volume").longValue());
        kline.setTakerBuyQuoteVolume(rs.getBigDecimal("taker_buy_quote_volume").longValue());
        return kline;
    }

    private IntervalAggregationSpec resolveAggregationSpec(String interval) {
        if (interval == null || interval.isBlank()) {
            return null;
        }
        String raw = interval.trim();
        IntervalAggregationSpec direct = AGGREGATION_SPECS.get(raw);
        if (direct != null) {
            return direct;
        }
        if (raw.endsWith("M")) {
            return null;
        }
        return AGGREGATION_SPECS.get(raw.toLowerCase(Locale.ROOT));
    }

    private static class IntervalAggregationSpec {
        private final String bucketExpr;
        private final String closeTimeExpr;
        private final long approxIntervalMs;

        private IntervalAggregationSpec(String bucketExpr, String closeTimeExpr, long approxIntervalMs) {
            this.bucketExpr = bucketExpr;
            this.closeTimeExpr = closeTimeExpr;
            this.approxIntervalMs = approxIntervalMs;
        }
    }
}
