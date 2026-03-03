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
}
