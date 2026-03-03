package com.exchange.agent.service;

import com.exchange.agent.repository.AgentTrainingPersistenceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 训练域内存数据服务。
 * 说明：
 * 1. 当前为可联调版本，优先打通前端交互与后端契约。
 * 2. 数据存储为内存，后续可平滑替换为 MySQL/ClickHouse。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTrainingService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentTrainingPersistenceRepository persistenceRepository;
    private final Map<String, List<SkillItem>> skillsByScenario = new ConcurrentHashMap<>();
    private final Map<String, List<ReplayItem>> replaysByScenario = new ConcurrentHashMap<>();
    private final Map<String, List<ApiDocItem>> apisByDataset = new ConcurrentHashMap<>();
    private final Map<String, DecisionItem> decisionMap = new ConcurrentHashMap<>();
    private final List<DecisionLogItem> decisionLogs = new CopyOnWriteArrayList<>();
    private final Map<Long, List<String>> weeklyPlans = new ConcurrentHashMap<>();
    private volatile boolean persistenceReady = false;

    @PostConstruct
    public void init() {
        seedSkills();
        seedReplays();
        seedApiDocs();
        seedDecisions();
        seedDecisionLogs();
        seedGrowthPlans();
        initPersistence();
    }

    private void initPersistence() {
        try {
            persistenceRepository.ensureSchema();
            persistenceReady = persistenceRepository.ping();
            if (!persistenceReady) {
                log.warn("[AgentTrainingService] Persistence ping failed, fallback to memory mode");
                return;
            }
            syncSeedSkillsToDb();
            syncSeedDecisionLogsToDb();
            syncSeedWeeklyPlanToDb();
            log.info("[AgentTrainingService] Persistence enabled with MySQL");
        } catch (Exception e) {
            persistenceReady = false;
            log.error("[AgentTrainingService] Persistence init failed, fallback to memory mode", e);
        }
    }

    public Map<String, Object> buildContext(String symbol, String interval, Integer depthLevel, Integer windowMinutes) {
        String safeSymbol = isBlank(symbol) ? "BTCUSDT" : symbol.toUpperCase(Locale.ROOT);
        String safeInterval = isBlank(interval) ? "1m" : interval;
        int safeDepth = depthLevel == null ? 20 : Math.max(5, Math.min(depthLevel, 200));
        int safeWindow = windowMinutes == null ? 60 : Math.max(5, Math.min(windowMinutes, 1440));

        Map<String, Object> kline = new LinkedHashMap<>();
        kline.put("open", "66317.60000000");
        kline.put("high", "66389.00000000");
        kline.put("low", "66088.90000000");
        kline.put("close", "66317.60000000");

        Map<String, Object> depthTop = new LinkedHashMap<>();
        depthTop.put("bid", "66317.50000000");
        depthTop.put("ask", "66317.60000000");
        depthTop.put("depthLevel", safeDepth);

        Map<String, Object> ticker24h = new LinkedHashMap<>();
        ticker24h.put("high", "66988.00000000");
        ticker24h.put("low", "65220.00000000");
        ticker24h.put("volume", "1063239.29280001");
        ticker24h.put("windowMinutes", safeWindow);

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("equity", "10000.00000000");
        account.put("available", "8200.00000000");
        account.put("riskExposurePct", "40.6");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contextId", "ctx_" + System.currentTimeMillis());
        data.put("symbol", safeSymbol);
        data.put("interval", safeInterval);
        data.put("market", Map.of("kline", kline, "depthTop", depthTop, "ticker24h", ticker24h));
        data.put("account", account);
        data.put("position", Map.of("summary", List.of(
                Map.of("symbol", safeSymbol, "side", "LONG", "qty", "0.02000000", "entryPrice", "65888.00000000")
        )));
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    public List<SkillItem> listSkills(String scenario) {
        String key = isBlank(scenario) ? "baseline" : scenario.toLowerCase(Locale.ROOT);
        if (persistenceReady) {
            try {
                List<AgentTrainingPersistenceRepository.SkillRow> rows = persistenceRepository.listSkills(key);
                if (!CollectionUtils.isEmpty(rows)) {
                    return rows.stream().map(this::toSkillItem).collect(Collectors.toList());
                }
            } catch (Exception e) {
                log.warn("[AgentTrainingService] listSkills fallback to memory, scenario={}", key, e);
            }
        }
        List<SkillItem> items = skillsByScenario.getOrDefault(key, skillsByScenario.get("baseline"));
        return new ArrayList<>(items == null ? List.of() : items);
    }

    public SkillItem createSkill(CreateSkillRequest req) {
        String skillId = isBlank(req.skillId()) ? "skill_" + System.currentTimeMillis() : req.skillId();
        String owner = isBlank(req.owner()) ? "user_1001" : req.owner();
        String ownerType = normalizeOwnerType(req.ownerType());
        SkillItem item = new SkillItem(skillId, "v1.0.0", owner, ownerType, 75.0, -5.0, "stable", "稳定");
        upsertSkillInMemory("baseline", item);
        if (persistenceReady) {
            try {
                long now = nowMillis();
                persistenceRepository.saveSkill(new AgentTrainingPersistenceRepository.SkillRow(
                        item.skillId(),
                        item.version(),
                        item.owner(),
                        item.ownerType(),
                        "baseline",
                        item.score(),
                        item.drawdown(),
                        item.status(),
                        item.label(),
                        now,
                        now
                ));
            } catch (Exception e) {
                log.warn("[AgentTrainingService] createSkill write DB failed, keep memory only, skillId={}", skillId, e);
            }
        }
        return item;
    }

    public SkillVersionResult createSkillVersion(String skillId, CreateSkillVersionRequest req) {
        String version = isBlank(req.version()) ? "v1.0.0" : req.version();
        String scenario = "baseline";
        SkillItem source = getSkill(skillId).orElse(null);
        if (source != null) {
            scenario = "baseline";
            SkillItem cloned = new SkillItem(
                    source.skillId(),
                    version,
                    source.owner(),
                    source.ownerType(),
                    source.score(),
                    source.drawdown(),
                    "stable",
                    "稳定"
            );
            upsertSkillInMemory(scenario, cloned);
        }

        if (persistenceReady) {
            try {
                Optional<AgentTrainingPersistenceRepository.SkillRow> sourceOpt = persistenceRepository.findSkill(skillId);
                if (sourceOpt.isPresent()) {
                    AgentTrainingPersistenceRepository.SkillRow src = sourceOpt.get();
                    long now = nowMillis();
                    persistenceRepository.saveSkill(new AgentTrainingPersistenceRepository.SkillRow(
                            src.skillId(),
                            version,
                            src.owner(),
                            src.ownerType(),
                            src.scenario(),
                            src.skillScore(),
                            src.drawdownPct(),
                            "stable",
                            "稳定",
                            now,
                            now
                    ));
                    upsertSkillInMemory(src.scenario(), new SkillItem(
                            src.skillId(),
                            version,
                            src.owner(),
                            src.ownerType(),
                            src.skillScore(),
                            src.drawdownPct(),
                            "stable",
                            "稳定"
                    ));
                }
            } catch (Exception e) {
                log.warn("[AgentTrainingService] createSkillVersion DB failed, skillId={}, version={}", skillId, version, e);
            }
        }
        return new SkillVersionResult(skillId, version, false, "version-created");
    }

    public SkillVersionResult publishSkillVersion(String skillId, PublishSkillRequest req) {
        String version = isBlank(req.version()) ? "latest" : req.version();
        if (persistenceReady && !"latest".equalsIgnoreCase(version)) {
            try {
                persistenceRepository.updateSkillStatus(skillId, version, "passed", "可晋级", nowMillis());
                syncPublishToMemory(skillId, version);
            } catch (Exception e) {
                log.warn("[AgentTrainingService] publishSkillVersion DB failed, skillId={}, version={}", skillId, version, e);
            }
        }
        return new SkillVersionResult(skillId, version, true, "published");
    }

    public Optional<SkillItem> getSkill(String skillId) {
        if (isBlank(skillId)) return Optional.empty();
        if (persistenceReady) {
            try {
                Optional<AgentTrainingPersistenceRepository.SkillRow> row = persistenceRepository.findSkill(skillId);
                if (row.isPresent()) return row.map(this::toSkillItem);
            } catch (Exception e) {
                log.warn("[AgentTrainingService] getSkill fallback to memory, skillId={}", skillId, e);
            }
        }
        return skillsByScenario.values().stream()
                .flatMap(Collection::stream)
                .filter(item -> skillId.equals(item.skillId()))
                .findFirst();
    }

    public List<ReplayItem> listReplays(String scenario) {
        String key = isBlank(scenario) ? "normal" : scenario.toLowerCase(Locale.ROOT);
        List<ReplayItem> items = replaysByScenario.getOrDefault(key, replaysByScenario.get("normal"));
        return new ArrayList<>(items);
    }

    public ReplayItem createReplay(CreateReplayRequest req) {
        String replayId = "rep_" + (3000 + new Random().nextInt(900));
        DecisionItem decision = decisionMap.get(req.decisionId());
        String symbol = !isBlank(req.symbol()) ? req.symbol() : (decision == null ? "BTCUSDT" : decision.symbol());
        String side = decision == null ? "WAIT" : toReplaySide(decision.action());
        String summary = decision == null
                ? "演示复盘会话，等待关联决策。"
                : "来自 " + decision.decisionId() + " 的决策回放，建议执行偏差可控。";

        ReplayItem item = new ReplayItem(
                replayId,
                symbol,
                side,
                "+0.24R",
                summary,
                List.of(
                        new TimelinePoint(nowTime(), "创建复盘会话并绑定决策。"),
                        new TimelinePoint(nowTime(), "回放执行参数与实际成交偏差。"),
                        new TimelinePoint(nowTime(), "生成下轮改进建议。")
                ),
                List.of(
                        new ScorePoint("信号充分度", 84.6),
                        new ScorePoint("执行偏差控制", 86.9),
                        new ScorePoint("风险纪律", 91.2)
                )
        );
        replaysByScenario.computeIfAbsent("normal", k -> new CopyOnWriteArrayList<>()).add(0, item);
        return item;
    }

    public Optional<ReplayItem> getReplay(String replayId, String scenario) {
        String key = isBlank(scenario) ? "normal" : scenario.toLowerCase(Locale.ROOT);
        return replaysByScenario.getOrDefault(key, List.of()).stream()
                .filter(item -> replayId.equals(item.id()))
                .findFirst();
    }

    public List<ApiDocItem> listApiDocs(String dataset) {
        String key = isBlank(dataset) ? "sandbox" : dataset.toLowerCase(Locale.ROOT);
        List<ApiDocItem> items = apisByDataset.getOrDefault(key, apisByDataset.get("sandbox"));
        return new ArrayList<>(items);
    }

    public List<DecisionItem> listDecisions(String symbol, String strategyVersion) {
        return decisionMap.values().stream()
                .filter(item -> isBlank(symbol) || item.symbol().equalsIgnoreCase(symbol))
                .filter(item -> isBlank(strategyVersion) || item.strategyVersion().equalsIgnoreCase(strategyVersion))
                .sorted(Comparator.comparing(DecisionItem::decisionId).reversed())
                .collect(Collectors.toList());
    }

    public Optional<DecisionItem> getDecision(String decisionId) {
        return Optional.ofNullable(decisionMap.get(decisionId));
    }

    public DecisionItem previewDecision(CreateDecisionRequest req) {
        String symbol = isBlank(req.symbol()) ? "BTCUSDT" : req.symbol().toUpperCase(Locale.ROOT);
        String strategyVersion = isBlank(req.strategyVersion()) ? "ai-momentum-v1.2.3" : req.strategyVersion();
        String action = "OPEN_SHORT";
        double confidence = 54;
        double entryMin = 69470.79;
        double entryMax = 69763.58;
        double exposure = 40.6;
        double stop = 70153.95;
        double take = 68202.07;

        if ("ETHUSDT".equals(symbol)) {
            action = "OPEN_LONG";
            confidence = 59;
            entryMin = 3488.12;
            entryMax = 3510.44;
            exposure = 37.8;
            stop = 3449.20;
            take = 3588.60;
        }

        String decisionId = "dec_" + System.currentTimeMillis();
        DecisionItem created = new DecisionItem(
                decisionId,
                strategyVersion,
                action,
                symbol,
                confidence,
                entryMin,
                entryMax,
                exposure,
                stop,
                take,
                List.of("价格靠近关键区间边缘。", "流动性结构支持当前方向。", "风险暴露在护栏内。"),
                "PREVIEWED"
        );
        decisionMap.put(decisionId, created);
        return created;
    }

    public ValidationResult validateDecision(ValidateDecisionRequest req) {
        DecisionItem decision = decisionMap.get(req.decisionId());
        if (decision == null) {
            return new ValidationResult(req.decisionId(), false, List.of("decision-not-found"), "决策不存在");
        }

        List<String> violations = new ArrayList<>();
        if (decision.exposure() >= 48) {
            violations.add("risk_exposure_guard");
        }
        if (decision.confidence() < 45) {
            violations.add("confidence_too_low");
        }

        boolean pass = violations.isEmpty();
        String nextStatus = pass ? "VALIDATED" : "REJECTED";
        decisionMap.put(decision.decisionId(), decision.withStatus(nextStatus));
        return new ValidationResult(
                decision.decisionId(),
                pass,
                violations,
                pass ? "校验通过，可采纳填单。" : "校验未通过，请调整参数。"
        );
    }

    public AdoptResult adoptDecision(AdoptDecisionRequest req) {
        DecisionItem decision = decisionMap.get(req.decisionId());
        if (decision == null) {
            return new AdoptResult(req.decisionId(), "failed", "决策不存在");
        }
        decision = decision.withStatus("ADOPTED");
        decisionMap.put(decision.decisionId(), decision);

        DecisionLogItem logItem = new DecisionLogItem(
                decision.decisionId(),
                decision.strategyVersion(),
                decision.action(),
                String.format(Locale.ROOT, "%.0f%%", decision.confidence()),
                "accepted",
                "采纳并填单（未自动执行）",
                nowTime()
        );
        decisionLogs.add(0, logItem);
        trimLogs();
        if (persistenceReady) {
            try {
                persistenceRepository.insertDecisionLog(new AgentTrainingPersistenceRepository.DecisionLogRow(
                        logItem.id(),
                        logItem.strategy(),
                        logItem.action(),
                        parseConfidencePct(logItem.conf()),
                        logItem.status(),
                        logItem.feedback(),
                        nowMillis()
                ));
            } catch (Exception e) {
                log.warn("[AgentTrainingService] adoptDecision write log failed, decisionId={}", decision.decisionId(), e);
            }
        }
        return new AdoptResult(decision.decisionId(), "accepted", "已采纳并写入填单参数。");
    }

    public List<DecisionLogItem> listDecisionLogs() {
        if (persistenceReady) {
            try {
                List<AgentTrainingPersistenceRepository.DecisionLogRow> rows = persistenceRepository.listDecisionLogs(20);
                if (!CollectionUtils.isEmpty(rows)) {
                    return rows.stream().map(this::toDecisionLogItem).collect(Collectors.toList());
                }
            } catch (Exception e) {
                log.warn("[AgentTrainingService] listDecisionLogs fallback to memory", e);
            }
        }
        return new ArrayList<>(decisionLogs);
    }

    public ScoreProfile getScoreProfile(Long userId, String subjectType, String subjectId) {
        Map<String, Double> dimensions = new LinkedHashMap<>();
        dimensions.put("RAR", 84.3);
        dimensions.put("DDC", 86.5);
        dimensions.put("EXEC", 88.8);
        dimensions.put("CONS", 82.6);
        dimensions.put("RISK", 91.4);
        dimensions.put("REPLAY", 79.2);

        return new ScoreProfile(
                normalizeSubjectType(subjectType),
                isBlank(subjectId) ? "user_" + (userId == null ? 1001 : userId) : subjectId,
                "2026-W10",
                85.5,
                dimensions,
                2.3,
                37,
                "FORMAL"
        );
    }

    public List<LeaderboardItem> getLeaderboard() {
        return List.of(
                new LeaderboardItem(1, "AGENT", "codex-agent", 91.8, 5.2, 2.4, 48),
                new LeaderboardItem(2, "AGENT", "claude-code", 89.7, 4.6, 2.9, 45),
                new LeaderboardItem(3, "HUMAN", "user_1021", 88.4, 4.3, 3.1, 42),
                new LeaderboardItem(4, "AGENT", "kimi-k2", 84.1, 3.4, 4.8, 39),
                new LeaderboardItem(5, "HUMAN", "user_2304", 82.9, 2.8, 4.2, 35)
        );
    }

    public WeeklyPlan getWeeklyPlan(Long userId) {
        long uid = userId == null ? 1001L : userId;
        String week = currentWeekCode();
        if (persistenceReady) {
            try {
                List<String> items = persistenceRepository.listWeeklyPlanItems(uid, week);
                if (CollectionUtils.isEmpty(items)) {
                    items = defaultWeeklyPlan();
                    persistenceRepository.replaceWeeklyPlan(uid, week, items, nowMillis());
                }
                return new WeeklyPlan(uid, week, items);
            } catch (Exception e) {
                log.warn("[AgentTrainingService] getWeeklyPlan fallback to memory, userId={}", uid, e);
            }
        }

        List<String> items = weeklyPlans.getOrDefault(uid, defaultWeeklyPlan());
        return new WeeklyPlan(uid, week, items);
    }

    public WeeklyPlan saveWeeklyPlan(Long userId, UpdateWeeklyPlanRequest req) {
        long uid = userId == null ? 1001L : userId;
        List<String> items = req.items() == null || req.items().isEmpty() ? defaultWeeklyPlan() : req.items();
        String week = currentWeekCode();
        weeklyPlans.put(uid, new CopyOnWriteArrayList<>(items));
        if (persistenceReady) {
            try {
                persistenceRepository.replaceWeeklyPlan(uid, week, items, nowMillis());
            } catch (Exception e) {
                log.warn("[AgentTrainingService] saveWeeklyPlan DB failed, userId={}", uid, e);
            }
        }
        return new WeeklyPlan(uid, week, new ArrayList<>(items));
    }

    private void seedSkills() {
        skillsByScenario.put("baseline", new CopyOnWriteArrayList<>(List.of(
                new SkillItem("skill_momentum", "v3.2.1", "codex-agent", "agent", 91.8, -2.4, "passed", "可晋级"),
                new SkillItem("skill_range", "v1.7.4", "claude-code", "agent", 86.2, -3.6, "stable", "稳定"),
                new SkillItem("skill_breakout", "v0.9.8", "kimi-k2", "agent", 79.5, -6.8, "optimize", "需优化"),
                new SkillItem("skill_manual_hybrid", "v2.0.0", "user_1021", "human", 88.1, -3.1, "stable", "稳定"),
                new SkillItem("skill_pullback", "v1.3.0", "user_2304", "human", 82.7, -4.4, "stable", "稳定"),
                new SkillItem("skill_scalp_guard", "v0.8.2", "codex-agent", "agent", 76.9, -7.2, "optimize", "需优化"),
                new SkillItem("skill_trend_slow", "v2.4.3", "claude-code", "agent", 89.3, -2.9, "passed", "可晋级"),
                new SkillItem("skill_break_retest", "v1.1.5", "user_8819", "human", 84.8, -3.9, "stable", "稳定")
        )));

        skillsByScenario.put("volatile", new CopyOnWriteArrayList<>(List.of(
                new SkillItem("skill_momentum", "v3.2.1", "codex-agent", "agent", 85.6, -5.8, "stable", "稳定"),
                new SkillItem("skill_range", "v1.7.4", "claude-code", "agent", 74.2, -8.6, "optimize", "需优化"),
                new SkillItem("skill_breakout", "v0.9.8", "kimi-k2", "agent", 81.7, -6.1, "stable", "稳定"),
                new SkillItem("skill_manual_hybrid", "v2.0.0", "user_1021", "human", 79.4, -7.2, "optimize", "需优化"),
                new SkillItem("skill_pullback", "v1.3.0", "user_2304", "human", 77.9, -7.5, "optimize", "需优化"),
                new SkillItem("skill_scalp_guard", "v0.8.2", "codex-agent", "agent", 82.1, -5.4, "stable", "稳定"),
                new SkillItem("skill_trend_slow", "v2.4.3", "claude-code", "agent", 84.3, -5.1, "stable", "稳定"),
                new SkillItem("skill_break_retest", "v1.1.5", "user_8819", "human", 80.2, -6.4, "stable", "稳定")
        )));
    }

    private void seedReplays() {
        List<ReplayItem> normal = List.of(
                new ReplayItem("rep_3021", "BTCUSDT", "SHORT", "+0.56R", "上沿回撤做空，分批止盈执行良好。",
                        List.of(
                                new TimelinePoint("07:10:03", "观察到24h上沿压力与成交衰减。"),
                                new TimelinePoint("07:10:26", "采纳AI建议填单，人工下调仓位20%。"),
                                new TimelinePoint("07:13:11", "触发第一止盈，执行部分减仓。"),
                                new TimelinePoint("07:18:09", "波动收敛后平仓，锁定收益。")
                        ),
                        List.of(new ScorePoint("信号充分度", 88.2), new ScorePoint("执行偏差控制", 91.1), new ScorePoint("风险纪律", 93.2))
                ),
                new ReplayItem("rep_3018", "BTCUSDT", "LONG", "-0.31R", "入场略早，止损执行到位。",
                        List.of(
                                new TimelinePoint("06:42:55", "看到买盘增强但未确认突破。"),
                                new TimelinePoint("06:43:10", "手动提前入场，偏离策略等待规则。"),
                                new TimelinePoint("06:45:48", "价格转弱触发止损，亏损受控。"),
                                new TimelinePoint("06:47:02", "记录偏差：等待确认不足。")
                        ),
                        List.of(new ScorePoint("信号充分度", 72.4), new ScorePoint("执行偏差控制", 79.1), new ScorePoint("风险纪律", 90.4))
                ),
                new ReplayItem("rep_3013", "ETHUSDT", "WAIT", "+0.00R", "观望决策，避免噪音行情。",
                        List.of(
                                new TimelinePoint("05:58:20", "方向不明，波动压缩。"),
                                new TimelinePoint("05:59:10", "AI与人工均选择观望。"),
                                new TimelinePoint("06:10:33", "后续出现假突破，观望有效。")
                        ),
                        List.of(new ScorePoint("信号充分度", 86.7), new ScorePoint("执行偏差控制", 88.8), new ScorePoint("风险纪律", 92.1))
                ),
                new ReplayItem("rep_3009", "SOLUSDT", "SHORT", "+0.27R", "建议有效，止盈过早。",
                        List.of(
                                new TimelinePoint("05:22:08", "空头信号成立，进入计划仓位。"),
                                new TimelinePoint("05:24:12", "小幅盈利后提前离场。"),
                                new TimelinePoint("05:30:01", "原计划止盈位后续触达，收益漏掉。")
                        ),
                        List.of(new ScorePoint("信号充分度", 83.4), new ScorePoint("执行偏差控制", 74.5), new ScorePoint("风险纪律", 89.6))
                )
        );
        replaysByScenario.put("normal", new CopyOnWriteArrayList<>(normal));

        List<ReplayItem> volatileItems = normal.stream()
                .map(item -> {
                    double delta = item.id().hashCode() % 2 == 0 ? -0.18 : -0.42;
                    String pnl = shiftR(item.pnl(), delta);
                    List<ScorePoint> points = item.scores().stream()
                            .map(p -> new ScorePoint(p.k(), Math.max(58, p.v() - 5.2)))
                            .toList();
                    return new ReplayItem(item.id(), item.symbol(), item.side(), pnl, item.summary() + "（高波动场景）", item.events(), points);
                })
                .collect(Collectors.toList());
        replaysByScenario.put("volatile", new CopyOnWriteArrayList<>(volatileItems));
    }

    private void seedApiDocs() {
        List<ApiDocItem> sandbox = List.of(
                new ApiDocItem("POST", "/api/v1/agent/decisions/preview", "生成候选决策", "JWT", "decision"),
                new ApiDocItem("POST", "/api/v1/agent/decisions/validate", "风控预校验", "JWT", "decision"),
                new ApiDocItem("POST", "/api/v1/agent/decisions/adopt", "采纳建议并填单", "JWT", "decision"),
                new ApiDocItem("GET", "/api/v1/agent/decisions/logs", "查询决策日志", "JWT", "decision"),
                new ApiDocItem("GET", "/api/v1/agent/score/profile", "能力评分详情", "JWT", "skill"),
                new ApiDocItem("GET", "/api/v1/agent/score/leaderboard", "Skill榜单", "JWT", "skill"),
                new ApiDocItem("POST", "/api/v1/agent/replays", "创建复盘会话", "JWT", "replay"),
                new ApiDocItem("GET", "/api/v1/agent/replays", "查询复盘会话", "JWT", "replay"),
                new ApiDocItem("GET", "/api/v1/agent/context", "获取上下文快照", "NONE", "market")
        );
        apisByDataset.put("sandbox", new CopyOnWriteArrayList<>(sandbox));
        apisByDataset.put("prod", new CopyOnWriteArrayList<>(sandbox.stream()
                .map(item -> new ApiDocItem(item.method(), item.path(), item.usage() + "（生产）", "NONE".equals(item.auth()) ? "JWT" : item.auth(), item.group()))
                .toList()));
    }

    private void seedDecisions() {
        List<DecisionItem> seeds = List.of(
                new DecisionItem("dec_20260302_1001", "ai-momentum-v1.2.3", "OPEN_SHORT", "BTCUSDT", 54, 69470.79, 69763.58, 40.6, 70153.95, 68202.07,
                        List.of("价格靠近24h区间上沿，倾向回撤确认。", "成交结构出现上冲衰减。", "风险暴露在阈值内，可小仓试错。"), "PREVIEWED"),
                new DecisionItem("dec_20260302_1002", "ai-range-v0.9.7", "OPEN_LONG", "BTCUSDT", 61, 66120.2, 66240.6, 35.2, 65880.4, 66790.2,
                        List.of("价格回落至区间中下部。", "主动买盘比上个窗口增强。", "止损距离合理，盈亏比约2.1。"), "PREVIEWED"),
                new DecisionItem("dec_20260302_1003", "ai-hybrid-v2.0.1", "WAIT", "BTCUSDT", 47, 0, 0, 31.4, 0, 0,
                        List.of("方向不清晰，等待突破后确认。", "波动率压缩，性价比偏低。", "建议减少试错频率。"), "PREVIEWED"),
                new DecisionItem("dec_20260302_1004", "ai-momentum-v1.2.3", "OPEN_SHORT", "BTCUSDT", 58, 66510.1, 66668.4, 44.7, 67008.2, 65860.3,
                        List.of("短周期顶背离，动能减弱。", "挂单簿卖墙加强。", "建议分两档入场，控制滑点。"), "PREVIEWED")
        );
        for (DecisionItem seed : seeds) {
            decisionMap.put(seed.decisionId(), seed);
        }
    }

    private void seedDecisionLogs() {
        decisionLogs.addAll(List.of(
                new DecisionLogItem("dec_20260302_0988", "ai-momentum-v1.2.2", "OPEN_LONG", "63%", "accepted", "已填单，用户调整后下单", "07:02:14"),
                new DecisionLogItem("dec_20260302_0989", "ai-range-v0.9.7", "WAIT", "44%", "expired", "用户未采纳", "07:03:57"),
                new DecisionLogItem("dec_20260302_0990", "ai-hybrid-v2.0.1", "OPEN_SHORT", "57%", "rejected", "风险暴露超阈值，策略阻断", "07:05:08"),
                new DecisionLogItem("dec_20260302_0991", "ai-momentum-v1.2.3", "OPEN_LONG", "60%", "accepted", "已采纳并加入复盘", "07:07:31")
        ));
    }

    private void seedGrowthPlans() {
        weeklyPlans.put(1001L, new CopyOnWriteArrayList<>(defaultWeeklyPlan()));
    }

    private void syncSeedSkillsToDb() {
        for (Map.Entry<String, List<SkillItem>> entry : skillsByScenario.entrySet()) {
            String scenario = entry.getKey();
            long count = persistenceRepository.countSkillsByScenario(scenario);
            if (count > 0) continue;
            long now = nowMillis();
            for (SkillItem item : entry.getValue()) {
                persistenceRepository.saveSkill(new AgentTrainingPersistenceRepository.SkillRow(
                        item.skillId(),
                        item.version(),
                        item.owner(),
                        item.ownerType(),
                        scenario,
                        item.score(),
                        item.drawdown(),
                        item.status(),
                        item.label(),
                        now,
                        now
                ));
            }
        }
    }

    private void syncSeedDecisionLogsToDb() {
        if (persistenceRepository.countDecisionLogs() > 0) return;
        long baseTs = nowMillis();
        int offset = 0;
        for (DecisionLogItem item : decisionLogs) {
            persistenceRepository.insertDecisionLog(new AgentTrainingPersistenceRepository.DecisionLogRow(
                    item.id(),
                    item.strategy(),
                    item.action(),
                    parseConfidencePct(item.conf()),
                    item.status(),
                    item.feedback(),
                    baseTs - (offset++ * 1000L)
            ));
        }
    }

    private void syncSeedWeeklyPlanToDb() {
        String week = currentWeekCode();
        for (Map.Entry<Long, List<String>> entry : weeklyPlans.entrySet()) {
            List<String> existing = persistenceRepository.listWeeklyPlanItems(entry.getKey(), week);
            if (!CollectionUtils.isEmpty(existing)) continue;
            persistenceRepository.replaceWeeklyPlan(entry.getKey(), week, entry.getValue(), nowMillis());
        }
    }

    private void trimLogs() {
        while (decisionLogs.size() > 20) {
            decisionLogs.remove(decisionLogs.size() - 1);
        }
    }

    private List<String> defaultWeeklyPlan() {
        return List.of(
                "执行偏差控制在 12% 以内（至少 8 笔样本）",
                "每次决策必须带止损并在下单前完成 validate",
                "本周完成 2 次有效复盘并输出下周改进项"
        );
    }

    private void upsertSkillInMemory(String scenario, SkillItem item) {
        List<SkillItem> list = skillsByScenario.computeIfAbsent(scenario, k -> new CopyOnWriteArrayList<>());
        list.removeIf(existing -> existing.skillId().equals(item.skillId()) && existing.version().equals(item.version()));
        list.add(0, item);
    }

    private void syncPublishToMemory(String skillId, String version) {
        for (Map.Entry<String, List<SkillItem>> entry : skillsByScenario.entrySet()) {
            List<SkillItem> next = entry.getValue().stream()
                    .map(item -> {
                        if (!skillId.equals(item.skillId()) || !version.equals(item.version())) return item;
                        return new SkillItem(
                                item.skillId(),
                                item.version(),
                                item.owner(),
                                item.ownerType(),
                                item.score(),
                                item.drawdown(),
                                "passed",
                                "可晋级"
                        );
                    })
                    .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
            skillsByScenario.put(entry.getKey(), next);
        }
    }

    private SkillItem toSkillItem(AgentTrainingPersistenceRepository.SkillRow row) {
        return new SkillItem(
                row.skillId(),
                row.version(),
                row.owner(),
                normalizeOwnerType(row.ownerType()),
                row.skillScore(),
                row.drawdownPct(),
                safeSkillStatus(row.status()),
                isBlank(row.label()) ? statusLabel(row.status()) : row.label()
        );
    }

    private DecisionLogItem toDecisionLogItem(AgentTrainingPersistenceRepository.DecisionLogRow row) {
        return new DecisionLogItem(
                row.decisionId(),
                row.strategyVersion(),
                row.action(),
                String.format(Locale.ROOT, "%.0f%%", row.confidencePct()),
                row.status(),
                row.feedback(),
                formatEpochMillis(row.eventTime())
        );
    }

    private static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    private static String currentWeekCode() {
        LocalDate today = LocalDate.now(SHANGHAI_ZONE);
        WeekFields wf = WeekFields.ISO;
        int weekYear = today.get(wf.weekBasedYear());
        int weekNo = today.get(wf.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%d-W%02d", weekYear, weekNo);
    }

    private static double parseConfidencePct(String conf) {
        if (isBlank(conf)) return 0D;
        String normalized = conf.replace("%", "").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (Exception e) {
            return 0D;
        }
    }

    private static String formatEpochMillis(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(SHANGHAI_ZONE)
                .format(TIME_FMT);
    }

    private static String safeSkillStatus(String status) {
        if (isBlank(status)) return "stable";
        String v = status.toLowerCase(Locale.ROOT);
        if ("passed".equals(v) || "stable".equals(v) || "optimize".equals(v)) return v;
        return "stable";
    }

    private static String statusLabel(String status) {
        String s = safeSkillStatus(status);
        if ("passed".equals(s)) return "可晋级";
        if ("optimize".equals(s)) return "需优化";
        return "稳定";
    }

    private static String toReplaySide(String action) {
        if ("OPEN_LONG".equals(action)) return "LONG";
        if ("OPEN_SHORT".equals(action)) return "SHORT";
        return "WAIT";
    }

    private static String normalizeOwnerType(String ownerType) {
        if (isBlank(ownerType)) return "human";
        String normalized = ownerType.toLowerCase(Locale.ROOT);
        return "agent".equals(normalized) ? "agent" : "human";
    }

    private static String normalizeSubjectType(String subjectType) {
        if (isBlank(subjectType)) return "HUMAN";
        String normalized = subjectType.toUpperCase(Locale.ROOT);
        return "AGENT".equals(normalized) ? "AGENT" : "HUMAN";
    }

    private static String shiftR(String input, double delta) {
        if (isBlank(input) || !input.endsWith("R")) return input;
        try {
            double base = Double.parseDouble(input.replace("R", ""));
            double next = base + delta;
            return String.format(Locale.ROOT, "%s%.2fR", next >= 0 ? "+" : "", next);
        } catch (Exception e) {
            return input;
        }
    }

    private static String nowTime() {
        return LocalTime.now().format(TIME_FMT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public record SkillItem(
            String skillId,
            String version,
            String owner,
            String ownerType,
            double score,
            double drawdown,
            String status,
            String label
    ) {
    }

    public record TimelinePoint(String t, String d) {
    }

    public record ScorePoint(String k, double v) {
    }

    public record ReplayItem(
            String id,
            String symbol,
            String side,
            String pnl,
            String summary,
            List<TimelinePoint> events,
            List<ScorePoint> scores
    ) {
    }

    public record ApiDocItem(
            String method,
            String path,
            String usage,
            String auth,
            String group
    ) {
    }

    public record DecisionItem(
            String decisionId,
            String strategyVersion,
            String action,
            String symbol,
            double confidence,
            double entryMin,
            double entryMax,
            double exposure,
            double stop,
            double take,
            List<String> reasons,
            String status
    ) {
        public DecisionItem withStatus(String nextStatus) {
            return new DecisionItem(
                    decisionId,
                    strategyVersion,
                    action,
                    symbol,
                    confidence,
                    entryMin,
                    entryMax,
                    exposure,
                    stop,
                    take,
                    reasons,
                    nextStatus
            );
        }
    }

    public record DecisionLogItem(
            String id,
            String strategy,
            String action,
            String conf,
            String status,
            String feedback,
            String time
    ) {
    }

    public record ValidationResult(
            String decisionId,
            boolean passed,
            List<String> violations,
            String message
    ) {
    }

    public record AdoptResult(
            String decisionId,
            String status,
            String message
    ) {
    }

    public record ScoreProfile(
            String subjectType,
            String subjectId,
            String period,
            double skillScore,
            Map<String, Double> dimensions,
            double deltaVsLastPeriod,
            int effectiveSampleSize,
            String scoreStatus
    ) {
    }

    public record LeaderboardItem(
            int rank,
            String subjectType,
            String subjectId,
            double skillScore,
            double weeklyPnlPct,
            double maxDrawdownPct,
            int effectiveSampleSize
    ) {
    }

    public record WeeklyPlan(
            long userId,
            String week,
            List<String> items
    ) {
    }

    public record CreateSkillRequest(String skillId, String owner, String ownerType) {
    }

    public record CreateSkillVersionRequest(String version) {
    }

    public record PublishSkillRequest(String version) {
    }

    public record SkillVersionResult(String skillId, String version, boolean published, String status) {
    }

    public record CreateDecisionRequest(String symbol, String strategyVersion, String interval, String marketState) {
    }

    public record ValidateDecisionRequest(String decisionId) {
    }

    public record AdoptDecisionRequest(String decisionId) {
    }

    public record CreateReplayRequest(String decisionId, String paperOrderId, String symbol, String notes) {
    }

    public record UpdateWeeklyPlanRequest(List<String> items) {
    }
}
