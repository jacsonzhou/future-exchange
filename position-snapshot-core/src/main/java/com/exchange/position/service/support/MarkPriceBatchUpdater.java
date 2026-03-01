package com.exchange.position.service.support;

import com.exchange.position.entity.PositionSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * mark-price 场景的批量落库器。
 *
 * 仅更新估值相关字段，避免每条消息逐行更新带来的数据库开销。
 */
@Component
public class MarkPriceBatchUpdater {

    private static final String UPDATE_MARK_FIELDS_SQL =
        "UPDATE position_snapshot " +
        "SET unrealized_pnl = ?, " +
        "    margin_ratio = ?, " +
        "    liquidation_price = ?, " +
        "    last_mark_price_id = ?, " +
        "    last_update_seq = ?, " +
        "    version = version + 1, " +
        "    updated_at = ? " +
        "WHERE id = ? AND version = ?";

    private final JdbcTemplate jdbcTemplate;

    private final int batchSize;

    public MarkPriceBatchUpdater(
        JdbcTemplate jdbcTemplate,
        @Value("${position.mark.batch-size:500}") int batchSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = Math.max(1, batchSize);
    }

    public int[] batchUpdateMarkFields(List<PositionSnapshot> positions) {
        if (positions == null || positions.isEmpty()) {
            return new int[0];
        }

        int[] results = new int[positions.size()];
        int offset = 0;

        while (offset < positions.size()) {
            int end = Math.min(offset + batchSize, positions.size());
            List<PositionSnapshot> chunk = positions.subList(offset, end);

            int[] chunkResult = jdbcTemplate.batchUpdate(
                UPDATE_MARK_FIELDS_SQL,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        PositionSnapshot position = chunk.get(i);
                        ps.setBigDecimal(1, position.getUnrealizedPnl());
                        ps.setBigDecimal(2, position.getMarginRatio());
                        ps.setBigDecimal(3, position.getLiquidationPrice());
                        ps.setString(4, position.getLastMarkPriceId());
                        ps.setLong(5, position.getLastUpdateSeq());
                        ps.setLong(6, position.getUpdatedAt());
                        ps.setLong(7, position.getId());
                        ps.setInt(8, position.getVersion());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunk.size();
                    }
                }
            );

            System.arraycopy(chunkResult, 0, results, offset, chunkResult.length);
            offset = end;
        }

        return Arrays.copyOf(results, results.length);
    }
}
