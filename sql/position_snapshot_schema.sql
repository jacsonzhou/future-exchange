-- Position Snapshot Schema（持仓快照表 - 双向持仓模式Hedge Mode）

-- 持仓快照表
-- 🔥 支持双向持仓模式：同一个用户可以在同一交易对上同时持有多头和空头
CREATE TABLE IF NOT EXISTS position_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户ID',
  symbol VARCHAR(32) NOT NULL COMMENT '交易对',
  
  -- 🔥 新增：持仓方向（1=LONG多头，2=SHORT空头）
  position_side TINYINT NOT NULL DEFAULT 1 COMMENT '持仓方向：1=LONG多头，2=SHORT空头',
  
  -- 持仓信息
  size DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓数量（始终为正数，方向由position_side决定）',
  entry_price DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓均价',
  
  -- 盈亏信息
  unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
  realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
  
  -- 风控指标
  margin_ratio DECIMAL(10,4) COMMENT '保证金率',
  liquidation_price DECIMAL(32,16) COMMENT '强平价',
  
  -- 同步位点
  last_trade_id VARCHAR(64) COMMENT '最后处理的tradeId',
  last_mark_price_id VARCHAR(64) COMMENT '最后处理的markPriceId',
  last_update_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后更新序列号',
  
  -- 并发控制
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  
  -- 时间戳
  created_at BIGINT NOT NULL COMMENT '创建时间',
  updated_at BIGINT NOT NULL COMMENT '更新时间',
  
  -- 🔥 修改：唯一索引包含持仓方向，支持双向持仓
  UNIQUE KEY uk_user_symbol_side (user_id, symbol, position_side),
  KEY idx_last_update_seq (last_update_seq),
  KEY idx_margin_ratio (margin_ratio),
  KEY idx_user_symbol (user_id, symbol)  -- 用于查询用户的所有持仓
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓快照表 - 双向持仓模式';

-- 初始化测试数据（双向持仓模式示例）
-- 用户1001同时持有BTCUSDT的多头和空头
INSERT INTO position_snapshot (
  user_id, symbol, position_side, size, entry_price, unrealized_pnl, realized_pnl, 
  margin_ratio, liquidation_price, last_update_seq, version, 
  created_at, updated_at
) VALUES (
  1001, 'BTCUSDT', 1, 1.5, 50000.00, 0, 0, 
  2.50, 48000.00, 0, 0,
  UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
) ON DUPLICATE KEY UPDATE updated_at = UNIX_TIMESTAMP() * 1000;

-- 同一用户的空头持仓
INSERT INTO position_snapshot (
  user_id, symbol, position_side, size, entry_price, unrealized_pnl, realized_pnl, 
  margin_ratio, liquidation_price, last_update_seq, version, 
  created_at, updated_at
) VALUES (
  1001, 'BTCUSDT', 2, 0.5, 55000.00, 0, 0, 
  3.00, 58000.00, 0, 0,
  UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
) ON DUPLICATE KEY UPDATE updated_at = UNIX_TIMESTAMP() * 1000;

INSERT INTO position_snapshot (
  user_id, symbol, position_side, size, entry_price, unrealized_pnl, realized_pnl, 
  margin_ratio, liquidation_price, last_update_seq, version, 
  created_at, updated_at
) VALUES (
  1002, 'ETHUSDT', 2, 10.0, 3000.00, 0, 0, 
  2.80, 3100.00, 0, 0,
  UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
) ON DUPLICATE KEY UPDATE updated_at = UNIX_TIMESTAMP() * 1000;



