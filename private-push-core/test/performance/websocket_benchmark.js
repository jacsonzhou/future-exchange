#!/usr/bin/env node

/**
 * WebSocket 性能压测工具
 *
 * 功能：
 * 1. 连接数测试（10万、50万、100万）
 * 2. 吞吐量测试（1万、5万、10万TPS）
 * 3. 延迟测试（P50、P99、P999）
 * 4. 生成压测报告
 *
 * 使用方法：
 * node websocket_benchmark.js --connections=10000 --duration=60
 *
 * @author Exchange Team
 * @version 1.0.0
 */

const WebSocket = require('ws');
const { performance } = require('perf_hooks');
const fs = require('fs');

// ==================== 配置参数 ====================

const config = {
    // WebSocket服务器地址
    wsUrl: process.env.WS_URL || 'ws://localhost:8099/ws',

    // 连接数
    connections: parseInt(process.env.CONNECTIONS) || 10000,

    // 测试时长（秒）
    duration: parseInt(process.env.DURATION) || 60,

    // 连接建立速率（每秒）
    connectRate: parseInt(process.env.CONNECT_RATE) || 1000,

    // 消息发送间隔（毫秒）
    messageInterval: parseInt(process.env.MESSAGE_INTERVAL) || 1000,

    // 模拟用户ID范围
    userIdStart: 1,
    userIdEnd: 1000000,

    // 报告输出路径
    reportPath: './benchmark_report.json'
};

// ==================== 统计数据 ====================

const stats = {
    // 连接统计
    totalConnections: 0,
    successConnections: 0,
    failedConnections: 0,
    activeConnections: 0,

    // 消息统计
    messagesSent: 0,
    messagesReceived: 0,
    messagesLost: 0,

    // 延迟统计
    latencies: [],

    // 时间戳
    startTime: 0,
    endTime: 0,

    // 错误统计
    errors: {}
};

// ==================== 工具函数 ====================

/**
 * 生成随机用户ID
 */
function randomUserId() {
    return Math.floor(
        Math.random() * (config.userIdEnd - config.userIdStart) + config.userIdStart
    );
}

/**
 * 休眠函数
 */
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * 记录延迟
 */
function recordLatency(latency) {
    stats.latencies.push(latency);
}

/**
 * 记录错误
 */
function recordError(errorType, error) {
    if (!stats.errors[errorType]) {
        stats.errors[errorType] = 0;
    }
    stats.errors[errorType]++;
}

/**
 * 计算延迟百分位数
 */
function calculatePercentile(latencies, percentile) {
    if (latencies.length === 0) return 0;

    const sorted = latencies.slice().sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * sorted.length) - 1;
    return sorted[index];
}

// ==================== WebSocket 客户端 ====================

class BenchmarkClient {
    constructor(userId) {
        this.userId = userId;
        this.ws = null;
        this.connected = false;
        this.sentMessages = 0;
        this.receivedMessages = 0;
        this.pendingMessages = new Map(); // seq -> sendTime
    }

    /**
     * 连接WebSocket
     */
    async connect() {
        return new Promise((resolve, reject) => {
            try {
                // 使用userId作为token（简化）
                const url = `${config.wsUrl}?token=${this.userId}`;
                this.ws = new WebSocket(url);

                // 连接成功
                this.ws.on('open', () => {
                    this.connected = true;
                    stats.successConnections++;
                    stats.activeConnections++;
                    resolve();
                });

                // 接收消息
                this.ws.on('message', (data) => {
                    this.handleMessage(data);
                });

                // 连接错误
                this.ws.on('error', (error) => {
                    recordError('connection', error);
                    reject(error);
                });

                // 连接关闭
                this.ws.on('close', () => {
                    this.connected = false;
                    stats.activeConnections--;
                });

            } catch (error) {
                recordError('connection', error);
                reject(error);
            }
        });
    }

    /**
     * 处理接收到的消息
     */
    handleMessage(data) {
        try {
            const message = JSON.parse(data);

            // 连接确认
            if (message.e === 'connectionAck') {
                console.log(`✅ User ${this.userId} connected`);
                this.subscribe();
                return;
            }

            // 订阅确认
            if (message.result !== undefined) {
                console.log(`✅ User ${this.userId} subscribed`);
                return;
            }

            // 推送消息
            if (message.stream) {
                stats.messagesReceived++;
                this.receivedMessages++;

                const seq = message.seq;
                const sendTime = this.pendingMessages.get(seq);

                if (sendTime) {
                    const latency = performance.now() - sendTime;
                    recordLatency(latency);
                    this.pendingMessages.delete(seq);

                    // 发送ACK
                    this.sendAck(seq);
                }
            }

        } catch (error) {
            recordError('message_parse', error);
        }
    }

    /**
     * 订阅频道
     */
    subscribe() {
        if (!this.connected) return;

        const message = {
            method: 'SUBSCRIBE',
            params: ['executionReport', 'account', 'position'],
            id: 1
        };

        this.ws.send(JSON.stringify(message));
    }

    /**
     * 发送ACK
     */
    sendAck(seq) {
        if (!this.connected) return;

        const message = {
            method: 'ACK',
            seq: seq,
            id: Date.now()
        };

        this.ws.send(JSON.stringify(message));
    }

    /**
     * 发送心跳
     */
    sendPing() {
        if (!this.connected) return;

        const message = {
            method: 'PING',
            id: Date.now()
        };

        this.ws.send(JSON.stringify(message));
    }

    /**
     * 关闭连接
     */
    close() {
        if (this.ws) {
            this.ws.close();
        }
    }
}

// ==================== 压测逻辑 ====================

/**
 * 运行连接数测试
 */
async function runConnectionTest() {
    console.log(`🚀 Starting connection test: ${config.connections} connections`);
    console.log(`   Connect rate: ${config.connectRate} conn/s`);

    const clients = [];
    const intervalMs = 1000 / config.connectRate;

    for (let i = 0; i < config.connections; i++) {
        const userId = randomUserId();
        const client = new BenchmarkClient(userId);

        try {
            await client.connect();
            clients.push(client);
            stats.totalConnections++;

            // 控制连接速率
            if ((i + 1) % config.connectRate === 0) {
                await sleep(1000);
            }

        } catch (error) {
            stats.failedConnections++;
            recordError('connect_failed', error);
        }

        // 打印进度
        if ((i + 1) % 1000 === 0) {
            console.log(`   Progress: ${i + 1}/${config.connections} (${Math.floor((i + 1) / config.connections * 100)}%)`);
        }
    }

    console.log(`✅ Connection test completed`);
    console.log(`   Success: ${stats.successConnections}`);
    console.log(`   Failed: ${stats.failedConnections}`);
    console.log(`   Active: ${stats.activeConnections}`);

    return clients;
}

/**
 * 运行吞吐量测试
 */
async function runThroughputTest(clients) {
    console.log(`🚀 Starting throughput test: ${config.duration}s`);

    const startTime = performance.now();
    stats.startTime = Date.now();

    // 定时心跳
    const heartbeatInterval = setInterval(() => {
        clients.forEach(client => client.sendPing());
    }, 30000);

    // 等待测试时长
    await sleep(config.duration * 1000);

    clearInterval(heartbeatInterval);

    stats.endTime = Date.now();
    const duration = (performance.now() - startTime) / 1000;

    console.log(`✅ Throughput test completed`);
    console.log(`   Duration: ${duration.toFixed(2)}s`);
    console.log(`   Messages received: ${stats.messagesReceived}`);
    console.log(`   Throughput: ${Math.floor(stats.messagesReceived / duration)} msg/s`);
}

/**
 * 运行延迟测试
 */
function runLatencyTest() {
    console.log(`🚀 Starting latency analysis`);

    if (stats.latencies.length === 0) {
        console.log(`⚠️  No latency data collected`);
        return;
    }

    const p50 = calculatePercentile(stats.latencies, 50);
    const p90 = calculatePercentile(stats.latencies, 90);
    const p99 = calculatePercentile(stats.latencies, 99);
    const p999 = calculatePercentile(stats.latencies, 99.9);

    const avg = stats.latencies.reduce((a, b) => a + b, 0) / stats.latencies.length;
    const min = Math.min(...stats.latencies);
    const max = Math.max(...stats.latencies);

    console.log(`✅ Latency analysis completed`);
    console.log(`   Samples: ${stats.latencies.length}`);
    console.log(`   Min: ${min.toFixed(2)}ms`);
    console.log(`   Max: ${max.toFixed(2)}ms`);
    console.log(`   Avg: ${avg.toFixed(2)}ms`);
    console.log(`   P50: ${p50.toFixed(2)}ms`);
    console.log(`   P90: ${p90.toFixed(2)}ms`);
    console.log(`   P99: ${p99.toFixed(2)}ms`);
    console.log(`   P999: ${p999.toFixed(2)}ms`);

    return { p50, p90, p99, p999, avg, min, max };
}

/**
 * 生成压测报告
 */
function generateReport(latencyStats) {
    const duration = (stats.endTime - stats.startTime) / 1000;
    const throughput = Math.floor(stats.messagesReceived / duration);

    const report = {
        config: config,
        timestamp: new Date().toISOString(),
        duration: duration,
        connections: {
            total: stats.totalConnections,
            success: stats.successConnections,
            failed: stats.failedConnections,
            active: stats.activeConnections
        },
        messages: {
            sent: stats.messagesSent,
            received: stats.messagesReceived,
            lost: stats.messagesLost,
            throughput: throughput
        },
        latency: latencyStats || {},
        errors: stats.errors
    };

    // 保存报告
    fs.writeFileSync(config.reportPath, JSON.stringify(report, null, 2));

    console.log(`\n📊 Benchmark Report`);
    console.log(`==========================================`);
    console.log(`Duration: ${duration.toFixed(2)}s`);
    console.log(`Connections: ${stats.successConnections}/${stats.totalConnections}`);
    console.log(`Messages Received: ${stats.messagesReceived}`);
    console.log(`Throughput: ${throughput} msg/s`);
    if (latencyStats) {
        console.log(`Latency P99: ${latencyStats.p99.toFixed(2)}ms`);
    }
    console.log(`Report saved to: ${config.reportPath}`);
    console.log(`==========================================\n`);

    return report;
}

/**
 * 清理资源
 */
async function cleanup(clients) {
    console.log(`🧹 Cleaning up ${clients.length} connections...`);

    for (const client of clients) {
        client.close();
    }

    await sleep(1000);

    console.log(`✅ Cleanup completed`);
}

// ==================== 主函数 ====================

async function main() {
    console.log(`\n╔══════════════════════════════════════════╗`);
    console.log(`║  WebSocket Performance Benchmark Tool   ║`);
    console.log(`╚══════════════════════════════════════════╝\n`);

    console.log(`Configuration:`);
    console.log(`  - WebSocket URL: ${config.wsUrl}`);
    console.log(`  - Connections: ${config.connections}`);
    console.log(`  - Duration: ${config.duration}s`);
    console.log(`  - Connect Rate: ${config.connectRate} conn/s`);
    console.log(``);

    try {
        // 1. 连接数测试
        const clients = await runConnectionTest();
        await sleep(5000); // 等待连接稳定

        // 2. 吞吐量测试
        await runThroughputTest(clients);

        // 3. 延迟测试
        const latencyStats = runLatencyTest();

        // 4. 生成报告
        generateReport(latencyStats);

        // 5. 清理资源
        await cleanup(clients);

        console.log(`\n✅ Benchmark completed successfully!\n`);
        process.exit(0);

    } catch (error) {
        console.error(`\n❌ Benchmark failed:`, error);
        process.exit(1);
    }
}

// 运行主函数
main();
