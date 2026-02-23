package com.exchange.replay.controller;

import com.exchange.replay.service.ReplayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 重放控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/replay")
public class ReplayController {
    
    @Autowired
    private ReplayService replayService;
    
    /**
     * 重放撮合日志
     */
    @PostMapping("/match")
    public Map<String, Object> replayMatch(@RequestParam String walFile) {
        log.info("Received replay match request: walFile={}", walFile);
        
        try {
            replayService.replayMatch(walFile);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Replay match completed");
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to replay match", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            
            return result;
        }
    }
    
    /**
     * 重放账本日志
     */
    @PostMapping("/ledger")
    public Map<String, Object> replayLedger(@RequestParam String walFile) {
        log.info("Received replay ledger request: walFile={}", walFile);
        
        try {
            replayService.replayLedger(walFile);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Replay ledger completed");
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to replay ledger", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            
            return result;
        }
    }
    
    /**
     * 完整重放
     */
    @PostMapping("/all")
    public Map<String, Object> replayAll() {
        log.info("Received replay all request");
        
        try {
            replayService.replayAll();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Full replay completed");
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to replay all", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            
            return result;
        }
    }
    
    /**
     * 重建快照
     */
    @PostMapping("/rebuild-snapshot")
    public Map<String, Object> rebuildSnapshot() {
        log.info("Received rebuild snapshot request");
        
        try {
            replayService.rebuildSnapshot();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Snapshot rebuild completed");
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to rebuild snapshot", e);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            
            return result;
        }
    }
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}




