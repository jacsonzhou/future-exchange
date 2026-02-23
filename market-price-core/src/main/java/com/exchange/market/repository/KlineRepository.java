package com.exchange.market.repository;

import com.exchange.market.model.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
}
