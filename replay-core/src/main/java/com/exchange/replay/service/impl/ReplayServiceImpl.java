package com.exchange.replay.service.impl;

import com.alibaba.fastjson2.JSON;
import com.exchange.common.proto.event.OrderCommand;
import com.exchange.common.proto.event.TradeEvent;
import com.exchange.replay.service.ReplayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 重放服务实现
 * 
 * 工作原理：
 * 1. 读取WAL日志文件
 * 2. 按顺序重放每条记录
 * 3. 重建系统状态
 * 
 * 使用场景：
 * - 系统崩溃后恢复
 * - 数据迁移
 * - 对账审计
 */
@Slf4j
@Service
public class ReplayServiceImpl implements ReplayService {
    
    private static final String WAL_DIR = "./data/wal";
    private static final String MATCH_LOG = "match.log";
    private static final String LEDGER_LOG = "ledger.log";
    
    @Override
    public void replayMatch(String walFile) {
        log.info("Starting replay match from: {}", walFile);
        
        Path path = Paths.get(walFile);
        if (!Files.exists(path)) {
            log.warn("WAL file not found: {}", walFile);
            return;
        }
        
        int commandCount = 0;
        int tradeCount = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }
                
                long timestamp = Long.parseLong(parts[0]);
                String type = parts[1];
                String json = parts[2];
                
                if ("COMMAND".equals(type)) {
                    OrderCommand command = JSON.parseObject(json, OrderCommand.class);
                    replayOrderCommand(command);
                    commandCount++;
                } else if ("TRADE".equals(type)) {
                    TradeEvent trade = JSON.parseObject(json, TradeEvent.class);
                    replayTrade(trade);
                    tradeCount++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to replay match", e);
        }
        
        log.info("Replay match completed: commands={}, trades={}", commandCount, tradeCount);
    }
    
    @Override
    public void replayLedger(String walFile) {
        log.info("Starting replay ledger from: {}", walFile);
        
        Path path = Paths.get(walFile);
        if (!Files.exists(path)) {
            log.warn("WAL file not found: {}", walFile);
            return;
        }
        
        int entryCount = 0;
        
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }
                
                long timestamp = Long.parseLong(parts[0]);
                String type = parts[1];
                String json = parts[2];
                
                if ("ENTRY".equals(type)) {
                    // TODO: 解析并重放账本分录
                    entryCount++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to replay ledger", e);
        }
        
        log.info("Replay ledger completed: entries={}", entryCount);
    }
    
    @Override
    public void replayAll() {
        log.info("Starting full replay...");
        
        // 1. 重放撮合日志
        String matchFile = Paths.get(WAL_DIR, MATCH_LOG).toString();
        replayMatch(matchFile);
        
        // 2. 重放账本日志
        String ledgerFile = Paths.get(WAL_DIR, LEDGER_LOG).toString();
        replayLedger(ledgerFile);
        
        // 3. 重建快照
        rebuildSnapshot();
        
        log.info("Full replay completed");
    }
    
    @Override
    public void rebuildSnapshot() {
        log.info("Rebuilding snapshot...");
        
        // TODO: 从账本重建账户快照
        // TODO: 从成交记录重建持仓快照
        
        log.info("Snapshot rebuild completed");
    }
    
    /**
     * 重放订单命令
     */
    private void replayOrderCommand(OrderCommand command) {
        log.debug("Replaying order command: orderId={}, type={}", 
            command.getOrderId(), command.getCommandType());
        
        // TODO: 重新执行撮合逻辑
        // 注意：需要保证撮合结果的确定性
    }
    
    /**
     * 重放成交
     */
    private void replayTrade(TradeEvent trade) {
        log.debug("Replaying trade: tradeId={}, symbol={}, price={}, qty={}", 
            trade.getTradeId(), trade.getSymbol(), trade.getPrice(), trade.getQuantity());
        
        // TODO: 重新执行结算逻辑
        // TODO: 更新持仓快照
    }
}







