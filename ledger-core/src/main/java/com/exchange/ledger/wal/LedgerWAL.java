package com.exchange.ledger.wal;

import com.alibaba.fastjson2.JSON;
import com.exchange.ledger.entity.LedgerEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 账本 WAL（Write-Ahead Log）
 * 
 * 作用：
 * 1. 持久化所有账本分录
 * 2. 用于灾备恢复
 * 3. 对账审计
 */
@Slf4j
@Component
public class LedgerWAL {
    
    private static final String WAL_DIR = "./data/wal";
    private static final String LEDGER_LOG = "ledger.log";
    
    private Path walFile;
    private BufferedWriter writer;
    
    @PostConstruct
    public void init() {
        try {
            // 创建WAL目录
            Files.createDirectories(Paths.get(WAL_DIR));
            
            // 打开WAL文件
            walFile = Paths.get(WAL_DIR, LEDGER_LOG);
            writer = Files.newBufferedWriter(
                walFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND
            );
            
            log.info("LedgerWAL initialized: {}", walFile.toAbsolutePath());
            
        } catch (IOException e) {
            log.error("Failed to initialize LedgerWAL", e);
        }
    }
    
    /**
     * 追加账本分录
     */
    public synchronized void append(List<LedgerEntry> entries) {
        if (writer == null || entries == null || entries.isEmpty()) {
            return;
        }
        
        try {
            long timestamp = System.currentTimeMillis();
            
            for (LedgerEntry entry : entries) {
                String line = String.format("%d|ENTRY|%s%n", 
                    timestamp, JSON.toJSONString(entry));
                writer.write(line);
            }
            
            // 刷盘
            writer.flush();
            
        } catch (IOException e) {
            log.error("Failed to append to LedgerWAL", e);
        }
    }
    
    /**
     * 关闭WAL
     */
    public void close() {
        if (writer != null) {
            try {
                writer.close();
                log.info("LedgerWAL closed");
            } catch (IOException e) {
                log.error("Failed to close LedgerWAL", e);
            }
        }
    }
}







