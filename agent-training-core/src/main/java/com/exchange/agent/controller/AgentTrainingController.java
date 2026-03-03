package com.exchange.agent.controller;

import com.exchange.agent.service.AgentTrainingService;
import com.exchange.common.core.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 训练域统一接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent")
public class AgentTrainingController {

    private final AgentTrainingService service;

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK");
    }

    @GetMapping("/context")
    public ApiResponse<Map<String, Object>> getContext(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer depthLevel,
            @RequestParam(required = false) Integer windowMinutes) {
        return ApiResponse.success(service.buildContext(symbol, interval, depthLevel, windowMinutes));
    }

    @GetMapping("/skills")
    public ApiResponse<List<AgentTrainingService.SkillItem>> getSkills(
            @RequestParam(required = false, defaultValue = "baseline") String scenario) {
        return ApiResponse.success(service.listSkills(scenario));
    }

    @PostMapping("/skills")
    public ApiResponse<AgentTrainingService.SkillItem> createSkill(
            @RequestBody AgentTrainingService.CreateSkillRequest request) {
        return ApiResponse.success(service.createSkill(request));
    }

    @PostMapping("/skills/{skillId}/versions")
    public ApiResponse<AgentTrainingService.SkillVersionResult> createSkillVersion(
            @PathVariable String skillId,
            @RequestBody(required = false) AgentTrainingService.CreateSkillVersionRequest request) {
        AgentTrainingService.CreateSkillVersionRequest req = request == null
                ? new AgentTrainingService.CreateSkillVersionRequest("v1.0.0")
                : request;
        return ApiResponse.success(service.createSkillVersion(skillId, req));
    }

    @PostMapping("/skills/{skillId}/publish")
    public ApiResponse<AgentTrainingService.SkillVersionResult> publishSkillVersion(
            @PathVariable String skillId,
            @RequestBody(required = false) AgentTrainingService.PublishSkillRequest request) {
        AgentTrainingService.PublishSkillRequest req = request == null
                ? new AgentTrainingService.PublishSkillRequest("latest")
                : request;
        return ApiResponse.success(service.publishSkillVersion(skillId, req));
    }

    @GetMapping("/skills/{skillId}")
    public ApiResponse<?> getSkill(@PathVariable String skillId) {
        return service.getSkill(skillId)
                .<ApiResponse<?>>map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "skill not found"));
    }

    @GetMapping("/replays")
    public ApiResponse<List<AgentTrainingService.ReplayItem>> getReplays(
            @RequestParam(required = false, defaultValue = "normal") String scenario) {
        return ApiResponse.success(service.listReplays(scenario));
    }

    @PostMapping("/replays")
    public ApiResponse<AgentTrainingService.ReplayItem> createReplay(
            @RequestBody AgentTrainingService.CreateReplayRequest request) {
        return ApiResponse.success(service.createReplay(request));
    }

    @GetMapping("/replays/{replayId}")
    public ApiResponse<?> getReplay(
            @PathVariable String replayId,
            @RequestParam(required = false, defaultValue = "normal") String scenario) {
        return service.getReplay(replayId, scenario)
                .<ApiResponse<?>>map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "replay not found"));
    }

    @GetMapping("/developer/apis")
    public ApiResponse<List<AgentTrainingService.ApiDocItem>> getDeveloperApis(
            @RequestParam(required = false, defaultValue = "sandbox") String dataset) {
        return ApiResponse.success(service.listApiDocs(dataset));
    }

    @GetMapping("/decisions")
    public ApiResponse<List<AgentTrainingService.DecisionItem>> getDecisions(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategyVersion) {
        return ApiResponse.success(service.listDecisions(symbol, strategyVersion));
    }

    @GetMapping("/decisions/{decisionId}")
    public ApiResponse<?> getDecision(@PathVariable String decisionId) {
        return service.getDecision(decisionId)
                .<ApiResponse<?>>map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "decision not found"));
    }

    @PostMapping("/decisions/preview")
    public ApiResponse<AgentTrainingService.DecisionItem> previewDecision(
            @RequestBody(required = false) AgentTrainingService.CreateDecisionRequest request) {
        AgentTrainingService.CreateDecisionRequest req = request == null
                ? new AgentTrainingService.CreateDecisionRequest("BTCUSDT", "ai-momentum-v1.2.3", "1m", "trend")
                : request;
        return ApiResponse.success(service.previewDecision(req));
    }

    @PostMapping("/decisions/validate")
    public ApiResponse<AgentTrainingService.ValidationResult> validateDecision(
            @RequestBody AgentTrainingService.ValidateDecisionRequest request) {
        return ApiResponse.success(service.validateDecision(request));
    }

    @PostMapping("/decisions/adopt")
    public ApiResponse<AgentTrainingService.AdoptResult> adoptDecision(
            @RequestBody AgentTrainingService.AdoptDecisionRequest request) {
        return ApiResponse.success(service.adoptDecision(request));
    }

    @GetMapping("/decisions/logs")
    public ApiResponse<List<AgentTrainingService.DecisionLogItem>> getDecisionLogs() {
        return ApiResponse.success(service.listDecisionLogs());
    }

    @GetMapping("/score/profile")
    public ApiResponse<AgentTrainingService.ScoreProfile> getScoreProfile(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId) {
        return ApiResponse.success(service.getScoreProfile(userId, subjectType, subjectId));
    }

    @GetMapping("/score/leaderboard")
    public ApiResponse<List<AgentTrainingService.LeaderboardItem>> getLeaderboard() {
        return ApiResponse.success(service.getLeaderboard());
    }

    @GetMapping("/growth/weekly-plan")
    public ApiResponse<AgentTrainingService.WeeklyPlan> getWeeklyPlan(
            @RequestParam(required = false) Long userId) {
        return ApiResponse.success(service.getWeeklyPlan(userId));
    }

    @PostMapping("/growth/weekly-plan")
    public ApiResponse<AgentTrainingService.WeeklyPlan> saveWeeklyPlan(
            @RequestParam(required = false) Long userId,
            @RequestBody AgentTrainingService.UpdateWeeklyPlanRequest request) {
        return ApiResponse.success(service.saveWeeklyPlan(userId, request));
    }
}
