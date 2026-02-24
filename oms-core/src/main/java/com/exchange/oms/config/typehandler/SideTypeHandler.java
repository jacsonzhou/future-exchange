package com.exchange.oms.config.typehandler;

import com.exchange.common.core.enums.Side;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 兼容 side 字段的历史存储格式：
 * - 数值: 0/1 或 1/2
 * - 字符串: BUY/SELL
 */
@MappedTypes(Side.class)
@MappedJdbcTypes({
    JdbcType.TINYINT,
    JdbcType.SMALLINT,
    JdbcType.INTEGER,
    JdbcType.CHAR,
    JdbcType.VARCHAR
})
public class SideTypeHandler extends BaseTypeHandler<Side> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Side parameter, JdbcType jdbcType)
        throws SQLException {
        // 与当前 OMS 新链路保持一致：BUY=0, SELL=1
        ps.setInt(i, parameter == Side.BUY ? 0 : 1);
    }

    @Override
    public Side getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        return parse(value, columnName);
    }

    @Override
    public Side getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        return parse(value, String.valueOf(columnIndex));
    }

    @Override
    public Side getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object value = cs.getObject(columnIndex);
        return parse(value, String.valueOf(columnIndex));
    }

    private Side parse(Object value, String column) throws SQLException {
        if (value == null) {
            return null;
        }

        try {
            if (value instanceof Number number) {
                return Side.of(number.intValue());
            }

            String raw = value.toString().trim();
            if (raw.isEmpty()) {
                return null;
            }

            if ("BUY".equalsIgnoreCase(raw)) {
                return Side.BUY;
            }
            if ("SELL".equalsIgnoreCase(raw)) {
                return Side.SELL;
            }

            return Side.of(Integer.parseInt(raw));
        } catch (Exception e) {
            throw new SQLException("Failed to parse side from column " + column + ", raw value=" + value, e);
        }
    }
}
