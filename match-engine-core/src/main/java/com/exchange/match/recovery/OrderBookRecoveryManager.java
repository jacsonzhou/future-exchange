package com.exchange.match.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.exchange.match.event.OrderCommand;
import com.exchange.match.model.Order;
import com.exchange.match.orderbook.OrderBook;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单簿恢复管理器：快照 + WAL 增量回放
 */
@Slf4j
@Component
public class OrderBookRecoveryManager {

    private static final BigDecimal MONEY_SCALE = BigDecimal.valueOf(100_000_000L);

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${match.recovery.enabled:true}")
    private boolean recoveryEnabled;

    @Value("${match.recovery.wal-file:./data/wal/match.log}")
    private String walFilePath;

    @Value("${match.recovery.snapshot-file:./data/wal/orderbook.snapshot.json}")
    private String snapshotFilePath;

    @Value("${match.recovery.max-commands:10000000}")
    private long maxReplayCommands;

    private volatile OrderBook latestOrderBook;
    private volatile long latestAppliedSequence;

    public synchronized RecoveryStats recover(OrderBook orderBook) {
        RecoveryStats stats = new RecoveryStats();
        stats.setEnabled(recoveryEnabled);
        this.latestOrderBook = orderBook;
        this.latestAppliedSequence = 0L;

        if (!recoveryEnabled) {
            log.info("[Recovery] disabled by config");
            return stats;
        }

        long startNs = System.nanoTime();
        long fromSequence = 0L;

        SnapshotFile snapshot = loadSnapshot(orderBook);
        if (snapshot != null) {
            List<Order> restoredOrders = toOrders(snapshot.getOrders(), orderBook.getSymbol());
            orderBook.restoreOpenOrders(restoredOrders);
            fromSequence = Math.max(0L, snapshot.getLastAppliedSequence());
            stats.setSnapshotLoaded(true);
            stats.setSnapshotOrderCount(restoredOrders.size());
            stats.setSnapshotSequence(fromSequence);
            latestAppliedSequence = fromSequence;
            log.info("[Recovery] snapshot loaded, symbol={}, orders={}, lastSeq={}",
                orderBook.getSymbol(), restoredOrders.size(), fromSequence);
        } else {
            orderBook.clearOrderBook();
            log.info("[Recovery] snapshot not found or invalid, replay from WAL head, symbol={}", orderBook.getSymbol());
        }

        long replayed = replayWal(orderBook, fromSequence, stats);
        latestAppliedSequence = Math.max(latestAppliedSequence, fromSequence + replayed);
        stats.setReplayCommands(replayed);
        stats.setRecoveredOrderCount(orderBook.getOrderCount());
        stats.setDurationMs((System.nanoTime() - startNs) / 1_000_000L);

        persistSnapshot(orderBook);

        log.info("[Recovery] completed, symbol={}, replayed={}, restoredOrders={}, depth={}, duration={}ms",
            orderBook.getSymbol(), replayed, orderBook.getOrderCount(), orderBook.getDepth(), stats.getDurationMs());
        return stats;
    }

    public synchronized void persistSnapshot(OrderBook orderBook) {
        if (!recoveryEnabled || orderBook == null) {
            return;
        }
        try {
            Path snapshotPath = Paths.get(snapshotFilePath);
            Files.createDirectories(snapshotPath.getParent());

            SnapshotFile snapshot = new SnapshotFile();
            snapshot.setSymbol(orderBook.getSymbol());
            snapshot.setSavedAt(System.currentTimeMillis());
            snapshot.setLastAppliedSequence(latestAppliedSequence);
            snapshot.setOrders(fromOrders(orderBook.snapshotOpenOrders()));

            String json = objectMapper.writeValueAsString(snapshot);
            Files.writeString(snapshotPath, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log.info("[Recovery] snapshot persisted, file={}, orders={}, seq={}",
                snapshotFilePath, snapshot.getOrders().size(), snapshot.getLastAppliedSequence());
        } catch (Exception e) {
            log.error("[Recovery] persist snapshot failed", e);
        }
    }

    @PreDestroy
    public void onShutdown() {
        persistSnapshot(latestOrderBook);
    }

    private SnapshotFile loadSnapshot(OrderBook orderBook) {
        try {
            Path snapshotPath = Paths.get(snapshotFilePath);
            if (!Files.exists(snapshotPath)) {
                return null;
            }
            String json = Files.readString(snapshotPath);
            SnapshotFile snapshot = objectMapper.readValue(json, SnapshotFile.class);
            if (snapshot == null || snapshot.getOrders() == null) {
                return null;
            }
            if (snapshot.getSymbol() != null && !snapshot.getSymbol().equals(orderBook.getSymbol())) {
                log.warn("[Recovery] snapshot symbol mismatch, fileSymbol={}, currentSymbol={}",
                    snapshot.getSymbol(), orderBook.getSymbol());
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            log.error("[Recovery] load snapshot failed", e);
            return null;
        }
    }

    private long replayWal(OrderBook orderBook, long fromSequenceExclusive, RecoveryStats stats) {
        Path walPath = Paths.get(walFilePath);
        if (!Files.exists(walPath)) {
            return 0L;
        }

        long replayed = 0L;
        long skipped = 0L;

        try (BufferedReader reader = Files.newBufferedReader(walPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }

                int p1 = line.indexOf('|');
                if (p1 < 0) {
                    continue;
                }
                int p2 = line.indexOf('|', p1 + 1);
                if (p2 < 0) {
                    continue;
                }
                int p3 = line.indexOf('|', p2 + 1);
                if (p3 < 0) {
                    continue;
                }

                String recordType = line.substring(p1 + 1, p2);
                if (!"COMMAND".equals(recordType)) {
                    continue;
                }

                long sequence;
                try {
                    sequence = Long.parseLong(line.substring(p2 + 1, p3));
                } catch (NumberFormatException ignore) {
                    continue;
                }

                if (sequence <= fromSequenceExclusive) {
                    skipped++;
                    continue;
                }

                String payload = line.substring(p3 + 1);
                OrderCommand command;
                try {
                    command = objectMapper.readValue(payload, OrderCommand.class);
                } catch (Exception e) {
                    stats.setMalformedCommands(stats.getMalformedCommands() + 1);
                    continue;
                }

                normalizeCommand(command);
                applyCommand(orderBook, command);
                replayed++;
                latestAppliedSequence = sequence;

                if (replayed >= maxReplayCommands) {
                    log.warn("[Recovery] replay truncated by maxReplayCommands={}", maxReplayCommands);
                    break;
                }
            }
        } catch (IOException e) {
            log.error("[Recovery] replay WAL failed, file={}", walFilePath, e);
        }

        stats.setSkippedCommands(skipped);
        return replayed;
    }

    private void normalizeCommand(OrderCommand command) {
        if (command == null) {
            return;
        }
        if (command.getEventType() == null || command.getEventType().isBlank()) {
            if (command.getSide() != null || command.getOrderType() != null
                || command.getPrice() != null || command.getQuantity() != null) {
                command.setEventType("ORDER_SUBMIT");
            } else {
                command.setEventType("ORDER_CANCEL");
            }
        }
        if (command.getOrderType() == null || command.getOrderType().isBlank()) {
            command.setOrderType("LIMIT");
        }
    }

    private void applyCommand(OrderBook orderBook, OrderCommand command) {
        if (command == null || command.getOrderId() == null) {
            return;
        }
        String eventType = command.getEventType();
        if ("ORDER_CANCEL".equals(eventType) || "ORDER_FORCE_CANCEL".equals(eventType)) {
            orderBook.cancelOrder(command.getOrderId());
            return;
        }
        if (!"ORDER_SUBMIT".equals(eventType)) {
            return;
        }
        if (command.getSide() == null || command.getQuantity() == null || command.getOrderType() == null) {
            return;
        }

        Order order = new Order();
        order.setOrderId(command.getOrderId());
        order.setUserId(command.getUserId());
        order.setSymbol(command.getSymbol());
        order.setSide("BUY".equals(command.getSide()) ? 0 : 1);
        order.setType("LIMIT".equals(command.getOrderType()) ? 0 : 1);
        if (command.getPrice() != null) {
            BigDecimal price = normalizeFromCommand(command.getPrice());
            order.setPrice(price);
            order.setPriceScaled(price.multiply(MONEY_SCALE).longValue());
        }
        BigDecimal qty = normalizeFromCommand(command.getQuantity());
        order.setQuantity(qty);
        order.setRemainingQuantity(qty);
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setCreateTimeNano(System.nanoTime());

        if (order.isLimit() && order.getPrice() != null) {
            orderBook.addOrder(order);
        }
    }

    private BigDecimal normalizeFromCommand(String raw) {
        BigDecimal value = new BigDecimal(raw);
        if (raw.indexOf('.') < 0 && value.abs().compareTo(MONEY_SCALE) >= 0) {
            return value.divide(MONEY_SCALE, 8, RoundingMode.HALF_UP);
        }
        return value;
    }

    private List<SnapshotOrder> fromOrders(List<Order> orders) {
        List<SnapshotOrder> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            SnapshotOrder s = new SnapshotOrder();
            s.setOrderId(order.getOrderId());
            s.setUserId(order.getUserId());
            s.setSymbol(order.getSymbol());
            s.setSide(order.getSide());
            s.setType(order.getType());
            s.setPrice(order.getPrice() == null ? null : order.getPrice().toPlainString());
            s.setQuantity(order.getQuantity() == null ? null : order.getQuantity().toPlainString());
            s.setRemainingQuantity(order.getRemainingQuantity() == null ? null : order.getRemainingQuantity().toPlainString());
            s.setFilledQuantity(order.getFilledQuantity() == null ? null : order.getFilledQuantity().toPlainString());
            s.setSequence(order.getSequence());
            s.setCreateTimeNano(order.getCreateTimeNano());
            result.add(s);
        }
        return result;
    }

    private List<Order> toOrders(List<SnapshotOrder> snapshots, String symbol) {
        List<Order> orders = new ArrayList<>(snapshots.size());
        for (SnapshotOrder snapshot : snapshots) {
            if (snapshot == null || snapshot.getOrderId() == null || snapshot.getRemainingQuantity() == null) {
                continue;
            }
            Order order = new Order();
            order.setOrderId(snapshot.getOrderId());
            order.setUserId(snapshot.getUserId());
            order.setSymbol(snapshot.getSymbol() == null ? symbol : snapshot.getSymbol());
            order.setSide(snapshot.getSide());
            order.setType(snapshot.getType());
            if (snapshot.getPrice() != null) {
                BigDecimal price = new BigDecimal(snapshot.getPrice());
                order.setPrice(price);
                order.setPriceScaled(price.multiply(MONEY_SCALE).longValue());
            }
            if (snapshot.getQuantity() != null) {
                order.setQuantity(new BigDecimal(snapshot.getQuantity()));
            }
            order.setRemainingQuantity(new BigDecimal(snapshot.getRemainingQuantity()));
            if (snapshot.getFilledQuantity() != null) {
                order.setFilledQuantity(new BigDecimal(snapshot.getFilledQuantity()));
            } else {
                order.setFilledQuantity(BigDecimal.ZERO);
            }
            order.setSequence(snapshot.getSequence());
            order.setCreateTimeNano(snapshot.getCreateTimeNano());
            orders.add(order);
        }
        return orders;
    }

    @Data
    public static class RecoveryStats {
        private boolean enabled;
        private boolean snapshotLoaded;
        private int snapshotOrderCount;
        private long snapshotSequence;
        private long replayCommands;
        private long skippedCommands;
        private long malformedCommands;
        private int recoveredOrderCount;
        private long durationMs;
    }

    @Data
    private static class SnapshotFile {
        private String symbol;
        private long savedAt;
        private long lastAppliedSequence;
        private List<SnapshotOrder> orders = new ArrayList<>();
    }

    @Data
    private static class SnapshotOrder {
        private Long orderId;
        private Long userId;
        private String symbol;
        private Integer side;
        private Integer type;
        private String price;
        private String quantity;
        private String remainingQuantity;
        private String filledQuantity;
        private Long sequence;
        private Long createTimeNano;
    }
}

