package com.exchange.agent.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Agent Training 持久化仓储（MySQL）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AgentTrainingPersistenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean ping() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    public void ensureSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_skill (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    skill_id VARCHAR(64) NOT NULL,
                    version VARCHAR(32) NOT NULL,
                    owner VARCHAR(64) NOT NULL,
                    owner_type VARCHAR(16) NOT NULL,
                    scenario VARCHAR(32) NOT NULL DEFAULT 'baseline',
                    skill_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    drawdown_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL DEFAULT 'stable',
                    label VARCHAR(32) NOT NULL DEFAULT '稳定',
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_skill_version_scenario (skill_id, version, scenario),
                    KEY idx_scenario_score (scenario, skill_score),
                    KEY idx_owner (owner),
                    KEY idx_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent训练Skill定义'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_decision_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    decision_id VARCHAR(64) NOT NULL,
                    strategy_version VARCHAR(64) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    confidence_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL,
                    feedback VARCHAR(255) DEFAULT NULL,
                    event_time BIGINT NOT NULL,
                    KEY idx_event_time (event_time),
                    KEY idx_decision_id (decision_id),
                    KEY idx_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent决策日志'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_weekly_plan (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    week_code VARCHAR(16) NOT NULL,
                    item_index INT NOT NULL,
                    item_text VARCHAR(255) NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_user_week_item (user_id, week_code, item_index),
                    KEY idx_user_week (user_id, week_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent成长周计划'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_decision_fact (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    decision_id VARCHAR(64) NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    strategy_version VARCHAR(64) NOT NULL,
                    symbol VARCHAR(32) NOT NULL,
                    interval_val VARCHAR(16) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    confidence_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
                    risk_exposure_pct DECIMAL(5,2) NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_decision_id (decision_id),
                    KEY idx_subject_period (subject_type, subject_id, created_at),
                    KEY idx_symbol_time (symbol, created_at),
                    KEY idx_status_time (status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent决策事实表'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_execution_fact (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    decision_id VARCHAR(64) NOT NULL,
                    paper_order_id VARCHAR(64) NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    symbol VARCHAR(32) NOT NULL,
                    side VARCHAR(16) NOT NULL,
                    entry_price_plan BIGINT NOT NULL DEFAULT 0,
                    entry_price_exec BIGINT NOT NULL DEFAULT 0,
                    exit_price_exec BIGINT NOT NULL DEFAULT 0,
                    entry_slippage_bps DECIMAL(8,2) NOT NULL DEFAULT 0,
                    exit_slippage_bps DECIMAL(8,2) NOT NULL DEFAULT 0,
                    plan_drift_pct DECIMAL(8,4) NOT NULL DEFAULT 0,
                    realized_pnl_r DECIMAL(10,4) NOT NULL DEFAULT 0,
                    hold_seconds INT NOT NULL DEFAULT 0,
                    risk_violation_count INT NOT NULL DEFAULT 0,
                    stop_loss_missing TINYINT(1) NOT NULL DEFAULT 0,
                    closed_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_paper_order_id (paper_order_id),
                    KEY idx_decision_id (decision_id),
                    KEY idx_subject_time (subject_type, subject_id, closed_at),
                    KEY idx_symbol_time (symbol, closed_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent执行事实表'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_replay_fact (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    replay_id VARCHAR(64) NOT NULL,
                    decision_id VARCHAR(64) NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    signal_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    execution_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    risk_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    action_items_total INT NOT NULL DEFAULT 0,
                    action_items_closed INT NOT NULL DEFAULT 0,
                    replay_completed TINYINT(1) NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_replay_id (replay_id),
                    KEY idx_decision_id (decision_id),
                    KEY idx_subject_time (subject_type, subject_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent复盘事实表'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_score_snapshot (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    period VARCHAR(16) NOT NULL,
                    skill_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    delta_vs_last_period DECIMAL(6,2) NOT NULL DEFAULT 0,
                    effective_sample_size INT NOT NULL DEFAULT 0,
                    active_days INT NOT NULL DEFAULT 0,
                    score_status VARCHAR(16) NOT NULL DEFAULT 'TEMP',
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_subject_period (subject_type, subject_id, period),
                    KEY idx_period_score (period, skill_score),
                    KEY idx_status_score (score_status, skill_score)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent评分总分快照'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_score_dimension_snapshot (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    period VARCHAR(16) NOT NULL,
                    rar DECIMAL(5,2) NOT NULL DEFAULT 0,
                    ddc DECIMAL(5,2) NOT NULL DEFAULT 0,
                    exec_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    cons_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    risk_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    replay_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    penalty DECIMAL(5,2) NOT NULL DEFAULT 0,
                    raw_metrics_json JSON NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_dim_subject_period (subject_type, subject_id, period),
                    KEY idx_period_subject (period, subject_type, subject_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent评分维度快照'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_agent_score_event (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    event_id VARCHAR(64) NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_id VARCHAR(64) NOT NULL,
                    period VARCHAR(16) NOT NULL,
                    trigger_type VARCHAR(32) NOT NULL,
                    before_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    after_score DECIMAL(5,2) NOT NULL DEFAULT 0,
                    delta_score DECIMAL(6,2) NOT NULL DEFAULT 0,
                    trace_id VARCHAR(64) DEFAULT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE KEY uk_event_id (event_id),
                    KEY idx_subject_period (subject_type, subject_id, period),
                    KEY idx_trigger_time (trigger_type, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent评分事件审计表'
                """);
    }

    public long countSkillsByScenario(String scenario) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM t_agent_skill WHERE scenario = ?",
                Long.class,
                scenario
        );
        return count == null ? 0L : count;
    }

    public void saveSkill(SkillRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_skill
                        (skill_id, version, owner, owner_type, scenario, skill_score, drawdown_pct, status, label, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          owner = VALUES(owner),
                          owner_type = VALUES(owner_type),
                          skill_score = VALUES(skill_score),
                          drawdown_pct = VALUES(drawdown_pct),
                          status = VALUES(status),
                          label = VALUES(label),
                          updated_at = VALUES(updated_at)
                        """,
                row.skillId(),
                row.version(),
                row.owner(),
                row.ownerType(),
                row.scenario(),
                row.skillScore(),
                row.drawdownPct(),
                row.status(),
                row.label(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    public List<SkillRow> listSkills(String scenario) {
        return jdbcTemplate.query("""
                        SELECT skill_id, version, owner, owner_type, scenario, skill_score, drawdown_pct, status, label, created_at, updated_at
                        FROM t_agent_skill
                        WHERE scenario = ?
                        ORDER BY skill_score DESC, updated_at DESC
                        """,
                (rs, rowNum) -> new SkillRow(
                        rs.getString("skill_id"),
                        rs.getString("version"),
                        rs.getString("owner"),
                        rs.getString("owner_type"),
                        rs.getString("scenario"),
                        rs.getDouble("skill_score"),
                        rs.getDouble("drawdown_pct"),
                        rs.getString("status"),
                        rs.getString("label"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")
                ),
                scenario
        );
    }

    public Optional<SkillRow> findSkill(String skillId) {
        List<SkillRow> rows = jdbcTemplate.query("""
                        SELECT skill_id, version, owner, owner_type, scenario, skill_score, drawdown_pct, status, label, created_at, updated_at
                        FROM t_agent_skill
                        WHERE skill_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new SkillRow(
                        rs.getString("skill_id"),
                        rs.getString("version"),
                        rs.getString("owner"),
                        rs.getString("owner_type"),
                        rs.getString("scenario"),
                        rs.getDouble("skill_score"),
                        rs.getDouble("drawdown_pct"),
                        rs.getString("status"),
                        rs.getString("label"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")
                ),
                skillId
        );
        return rows.stream().findFirst();
    }

    public Optional<SkillRow> findSkillVersion(String skillId, String version) {
        List<SkillRow> rows = jdbcTemplate.query("""
                        SELECT skill_id, version, owner, owner_type, scenario, skill_score, drawdown_pct, status, label, created_at, updated_at
                        FROM t_agent_skill
                        WHERE skill_id = ? AND version = ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new SkillRow(
                        rs.getString("skill_id"),
                        rs.getString("version"),
                        rs.getString("owner"),
                        rs.getString("owner_type"),
                        rs.getString("scenario"),
                        rs.getDouble("skill_score"),
                        rs.getDouble("drawdown_pct"),
                        rs.getString("status"),
                        rs.getString("label"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")
                ),
                skillId,
                version
        );
        return rows.stream().findFirst();
    }

    public void updateSkillStatus(String skillId, String version, String status, String label, long updatedAt) {
        jdbcTemplate.update("""
                        UPDATE t_agent_skill
                        SET status = ?, label = ?, updated_at = ?
                        WHERE skill_id = ? AND version = ?
                        """,
                status,
                label,
                updatedAt,
                skillId,
                version
        );
    }

    public long countDecisionLogs() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_agent_decision_log", Long.class);
        return count == null ? 0L : count;
    }

    public void insertDecisionLog(DecisionLogRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_decision_log
                        (decision_id, strategy_version, action, confidence_pct, status, feedback, event_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                row.decisionId(),
                row.strategyVersion(),
                row.action(),
                row.confidencePct(),
                row.status(),
                row.feedback(),
                row.eventTime()
        );
    }

    public List<DecisionLogRow> listDecisionLogs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query("""
                        SELECT decision_id, strategy_version, action, confidence_pct, status, feedback, event_time
                        FROM t_agent_decision_log
                        ORDER BY event_time DESC, id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new DecisionLogRow(
                        rs.getString("decision_id"),
                        rs.getString("strategy_version"),
                        rs.getString("action"),
                        rs.getDouble("confidence_pct"),
                        rs.getString("status"),
                        rs.getString("feedback"),
                        rs.getLong("event_time")
                ),
                safeLimit
        );
    }

    public List<String> listWeeklyPlanItems(long userId, String weekCode) {
        return jdbcTemplate.query("""
                        SELECT item_text
                        FROM t_agent_weekly_plan
                        WHERE user_id = ? AND week_code = ?
                        ORDER BY item_index ASC
                        """,
                (rs, rowNum) -> rs.getString("item_text"),
                userId,
                weekCode
        );
    }

    public void upsertDecisionFact(DecisionFactRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_decision_fact
                        (decision_id, subject_type, subject_id, strategy_version, symbol, interval_val, action,
                         confidence_pct, risk_exposure_pct, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          subject_type = VALUES(subject_type),
                          subject_id = VALUES(subject_id),
                          strategy_version = VALUES(strategy_version),
                          symbol = VALUES(symbol),
                          interval_val = VALUES(interval_val),
                          action = VALUES(action),
                          confidence_pct = VALUES(confidence_pct),
                          risk_exposure_pct = VALUES(risk_exposure_pct),
                          status = VALUES(status),
                          updated_at = VALUES(updated_at)
                        """,
                row.decisionId(),
                row.subjectType(),
                row.subjectId(),
                row.strategyVersion(),
                row.symbol(),
                row.intervalVal(),
                row.action(),
                row.confidencePct(),
                row.riskExposurePct(),
                row.status(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    public void updateDecisionFactStatus(String decisionId, String status, long updatedAt) {
        jdbcTemplate.update("""
                        UPDATE t_agent_decision_fact
                        SET status = ?, updated_at = ?
                        WHERE decision_id = ?
                        """,
                status,
                updatedAt,
                decisionId
        );
    }

    public void insertExecutionFact(ExecutionFactRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_execution_fact
                        (decision_id, paper_order_id, subject_type, subject_id, symbol, side,
                         entry_price_plan, entry_price_exec, exit_price_exec,
                         entry_slippage_bps, exit_slippage_bps, plan_drift_pct, realized_pnl_r,
                         hold_seconds, risk_violation_count, stop_loss_missing, closed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          decision_id = VALUES(decision_id),
                          subject_type = VALUES(subject_type),
                          subject_id = VALUES(subject_id),
                          symbol = VALUES(symbol),
                          side = VALUES(side),
                          entry_price_plan = VALUES(entry_price_plan),
                          entry_price_exec = VALUES(entry_price_exec),
                          exit_price_exec = VALUES(exit_price_exec),
                          entry_slippage_bps = VALUES(entry_slippage_bps),
                          exit_slippage_bps = VALUES(exit_slippage_bps),
                          plan_drift_pct = VALUES(plan_drift_pct),
                          realized_pnl_r = VALUES(realized_pnl_r),
                          hold_seconds = VALUES(hold_seconds),
                          risk_violation_count = VALUES(risk_violation_count),
                          stop_loss_missing = VALUES(stop_loss_missing),
                          closed_at = VALUES(closed_at),
                          updated_at = VALUES(updated_at)
                        """,
                row.decisionId(),
                row.paperOrderId(),
                row.subjectType(),
                row.subjectId(),
                row.symbol(),
                row.side(),
                row.entryPricePlan(),
                row.entryPriceExec(),
                row.exitPriceExec(),
                row.entrySlippageBps(),
                row.exitSlippageBps(),
                row.planDriftPct(),
                row.realizedPnlR(),
                row.holdSeconds(),
                row.riskViolationCount(),
                row.stopLossMissing(),
                row.closedAt(),
                row.updatedAt()
        );
    }

    public void upsertReplayFact(ReplayFactRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_replay_fact
                        (replay_id, decision_id, subject_type, subject_id, signal_score, execution_score, risk_score,
                         action_items_total, action_items_closed, replay_completed, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          decision_id = VALUES(decision_id),
                          subject_type = VALUES(subject_type),
                          subject_id = VALUES(subject_id),
                          signal_score = VALUES(signal_score),
                          execution_score = VALUES(execution_score),
                          risk_score = VALUES(risk_score),
                          action_items_total = VALUES(action_items_total),
                          action_items_closed = VALUES(action_items_closed),
                          replay_completed = VALUES(replay_completed),
                          updated_at = VALUES(updated_at)
                        """,
                row.replayId(),
                row.decisionId(),
                row.subjectType(),
                row.subjectId(),
                row.signalScore(),
                row.executionScore(),
                row.riskScore(),
                row.actionItemsTotal(),
                row.actionItemsClosed(),
                row.replayCompleted(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    public DecisionStats aggregateDecisionStats(String subjectType, String subjectId, long from, long to) {
        return jdbcTemplate.queryForObject("""
                        SELECT
                          COUNT(1) AS total_count,
                          SUM(CASE WHEN status = 'ADOPTED' THEN 1 ELSE 0 END) AS adopted_count,
                          SUM(CASE WHEN status = 'REJECTED' THEN 1 ELSE 0 END) AS rejected_count,
                          AVG(confidence_pct) AS avg_confidence_pct,
                          AVG(risk_exposure_pct) AS avg_risk_exposure_pct,
                          COUNT(DISTINCT DATE(FROM_UNIXTIME(created_at / 1000))) AS active_days
                        FROM t_agent_decision_fact
                        WHERE subject_type = ?
                          AND subject_id = ?
                          AND created_at BETWEEN ? AND ?
                        """,
                (rs, rowNum) -> new DecisionStats(
                        rs.getLong("total_count"),
                        rs.getLong("adopted_count"),
                        rs.getLong("rejected_count"),
                        rs.getDouble("avg_confidence_pct"),
                        rs.getDouble("avg_risk_exposure_pct"),
                        rs.getInt("active_days")
                ),
                subjectType,
                subjectId,
                from,
                to
        );
    }

    public ReplayStats aggregateReplayStats(String subjectType, String subjectId, long from, long to) {
        return jdbcTemplate.queryForObject("""
                        SELECT
                          COUNT(1) AS total_count,
                          SUM(CASE WHEN replay_completed = 1 THEN 1 ELSE 0 END) AS completed_count,
                          AVG(signal_score) AS avg_signal_score,
                          AVG(execution_score) AS avg_execution_score,
                          AVG(risk_score) AS avg_risk_score,
                          AVG(CASE
                                WHEN action_items_total > 0
                                THEN action_items_closed * 100.0 / action_items_total
                                ELSE NULL
                              END) AS avg_action_close_rate_pct
                        FROM t_agent_replay_fact
                        WHERE subject_type = ?
                          AND subject_id = ?
                          AND created_at BETWEEN ? AND ?
                        """,
                (rs, rowNum) -> new ReplayStats(
                        rs.getLong("total_count"),
                        rs.getLong("completed_count"),
                        rs.getDouble("avg_signal_score"),
                        rs.getDouble("avg_execution_score"),
                        rs.getDouble("avg_risk_score"),
                        rs.getDouble("avg_action_close_rate_pct")
                ),
                subjectType,
                subjectId,
                from,
                to
        );
    }

    public ExecutionStats aggregateExecutionStats(String subjectType, String subjectId, long from, long to) {
        return jdbcTemplate.queryForObject("""
                        SELECT
                          COUNT(1) AS total_count,
                          AVG(entry_slippage_bps) AS avg_entry_slippage_bps,
                          AVG(exit_slippage_bps) AS avg_exit_slippage_bps,
                          AVG(plan_drift_pct) AS avg_plan_drift_pct,
                          AVG(realized_pnl_r) AS avg_realized_pnl_r,
                          SUM(risk_violation_count) AS risk_violation_count,
                          AVG(stop_loss_missing) * 100 AS stop_loss_missing_rate_pct
                        FROM t_agent_execution_fact
                        WHERE subject_type = ?
                          AND subject_id = ?
                          AND closed_at BETWEEN ? AND ?
                        """,
                (rs, rowNum) -> new ExecutionStats(
                        rs.getLong("total_count"),
                        rs.getDouble("avg_entry_slippage_bps"),
                        rs.getDouble("avg_exit_slippage_bps"),
                        rs.getDouble("avg_plan_drift_pct"),
                        rs.getDouble("avg_realized_pnl_r"),
                        rs.getLong("risk_violation_count"),
                        rs.getDouble("stop_loss_missing_rate_pct")
                ),
                subjectType,
                subjectId,
                from,
                to
        );
    }

    public Optional<ScoreSnapshotRow> findLatestScoreSnapshotBefore(String subjectType, String subjectId, String period) {
        List<ScoreSnapshotRow> rows = jdbcTemplate.query("""
                        SELECT subject_type, subject_id, period, skill_score, delta_vs_last_period,
                               effective_sample_size, active_days, score_status, created_at, updated_at
                        FROM t_agent_score_snapshot
                        WHERE subject_type = ? AND subject_id = ? AND period < ?
                        ORDER BY period DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new ScoreSnapshotRow(
                        rs.getString("subject_type"),
                        rs.getString("subject_id"),
                        rs.getString("period"),
                        rs.getDouble("skill_score"),
                        rs.getDouble("delta_vs_last_period"),
                        rs.getInt("effective_sample_size"),
                        rs.getInt("active_days"),
                        rs.getString("score_status"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")
                ),
                subjectType,
                subjectId,
                period
        );
        return rows.stream().findFirst();
    }

    public void upsertScoreSnapshot(ScoreSnapshotRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_score_snapshot
                        (subject_type, subject_id, period, skill_score, delta_vs_last_period,
                         effective_sample_size, active_days, score_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          skill_score = VALUES(skill_score),
                          delta_vs_last_period = VALUES(delta_vs_last_period),
                          effective_sample_size = VALUES(effective_sample_size),
                          active_days = VALUES(active_days),
                          score_status = VALUES(score_status),
                          updated_at = VALUES(updated_at)
                        """,
                row.subjectType(),
                row.subjectId(),
                row.period(),
                row.skillScore(),
                row.deltaVsLastPeriod(),
                row.effectiveSampleSize(),
                row.activeDays(),
                row.scoreStatus(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    public void upsertScoreDimensionSnapshot(ScoreDimensionSnapshotRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_score_dimension_snapshot
                        (subject_type, subject_id, period, rar, ddc, exec_score, cons_score, risk_score,
                         replay_score, penalty, raw_metrics_json, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          rar = VALUES(rar),
                          ddc = VALUES(ddc),
                          exec_score = VALUES(exec_score),
                          cons_score = VALUES(cons_score),
                          risk_score = VALUES(risk_score),
                          replay_score = VALUES(replay_score),
                          penalty = VALUES(penalty),
                          raw_metrics_json = VALUES(raw_metrics_json),
                          updated_at = VALUES(updated_at)
                        """,
                row.subjectType(),
                row.subjectId(),
                row.period(),
                row.rar(),
                row.ddc(),
                row.execScore(),
                row.consScore(),
                row.riskScore(),
                row.replayScore(),
                row.penalty(),
                row.rawMetricsJson(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    public void insertScoreEvent(ScoreEventRow row) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_score_event
                        (event_id, subject_type, subject_id, period, trigger_type, before_score, after_score,
                         delta_score, trace_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          after_score = VALUES(after_score),
                          delta_score = VALUES(delta_score),
                          trace_id = VALUES(trace_id),
                          created_at = VALUES(created_at)
                        """,
                row.eventId(),
                row.subjectType(),
                row.subjectId(),
                row.period(),
                row.triggerType(),
                row.beforeScore(),
                row.afterScore(),
                row.deltaScore(),
                row.traceId(),
                row.createdAt()
        );
    }

    @Transactional
    public void replaceWeeklyPlan(long userId, String weekCode, List<String> items, long updatedAt) {
        jdbcTemplate.update(
                "DELETE FROM t_agent_weekly_plan WHERE user_id = ? AND week_code = ?",
                userId,
                weekCode
        );
        int idx = 0;
        for (String item : items) {
            jdbcTemplate.update("""
                            INSERT INTO t_agent_weekly_plan
                            (user_id, week_code, item_index, item_text, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    userId,
                    weekCode,
                    idx++,
                    item,
                    updatedAt
            );
        }
    }

    public record SkillRow(
            String skillId,
            String version,
            String owner,
            String ownerType,
            String scenario,
            double skillScore,
            double drawdownPct,
            String status,
            String label,
            long createdAt,
            long updatedAt
    ) {
    }

    public record DecisionLogRow(
            String decisionId,
            String strategyVersion,
            String action,
            double confidencePct,
            String status,
            String feedback,
            long eventTime
    ) {
    }

    public record DecisionFactRow(
            String decisionId,
            String subjectType,
            String subjectId,
            String strategyVersion,
            String symbol,
            String intervalVal,
            String action,
            double confidencePct,
            double riskExposurePct,
            String status,
            long createdAt,
            long updatedAt
    ) {
    }

    public record ExecutionFactRow(
            String decisionId,
            String paperOrderId,
            String subjectType,
            String subjectId,
            String symbol,
            String side,
            long entryPricePlan,
            long entryPriceExec,
            long exitPriceExec,
            double entrySlippageBps,
            double exitSlippageBps,
            double planDriftPct,
            double realizedPnlR,
            int holdSeconds,
            long riskViolationCount,
            int stopLossMissing,
            long closedAt,
            long updatedAt
    ) {
    }

    public record ReplayFactRow(
            String replayId,
            String decisionId,
            String subjectType,
            String subjectId,
            double signalScore,
            double executionScore,
            double riskScore,
            int actionItemsTotal,
            int actionItemsClosed,
            int replayCompleted,
            long createdAt,
            long updatedAt
    ) {
    }

    public record DecisionStats(
            long totalCount,
            long adoptedCount,
            long rejectedCount,
            double avgConfidencePct,
            double avgRiskExposurePct,
            int activeDays
    ) {
    }

    public record ReplayStats(
            long totalCount,
            long completedCount,
            double avgSignalScore,
            double avgExecutionScore,
            double avgRiskScore,
            double avgActionCloseRatePct
    ) {
    }

    public record ExecutionStats(
            long totalCount,
            double avgEntrySlippageBps,
            double avgExitSlippageBps,
            double avgPlanDriftPct,
            double avgRealizedPnlR,
            long riskViolationCount,
            double stopLossMissingRatePct
    ) {
    }

    public record ScoreSnapshotRow(
            String subjectType,
            String subjectId,
            String period,
            double skillScore,
            double deltaVsLastPeriod,
            int effectiveSampleSize,
            int activeDays,
            String scoreStatus,
            long createdAt,
            long updatedAt
    ) {
    }

    public record ScoreDimensionSnapshotRow(
            String subjectType,
            String subjectId,
            String period,
            double rar,
            double ddc,
            double execScore,
            double consScore,
            double riskScore,
            double replayScore,
            double penalty,
            String rawMetricsJson,
            long createdAt,
            long updatedAt
    ) {
    }

    public record ScoreEventRow(
            String eventId,
            String subjectType,
            String subjectId,
            String period,
            String triggerType,
            double beforeScore,
            double afterScore,
            double deltaScore,
            String traceId,
            long createdAt
    ) {
    }
}
