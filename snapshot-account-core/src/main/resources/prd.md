• ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
📋 RocksDB + Checkpoint + MinIO 快照恢复方案 - 完整实施计划（V3 最终版）
═══════════════════════════════════════════════════════════

📊 项目概览

维度       详情
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
目标       设计金融级快照恢复方案，保证强一致性，支持秒级重建
范围       Account Snapshot + Position Snapshot 双服务
核心组件   RocksDB + Kafka + MinIO + MySQL + Redis + WAL
预期效果   重建时间从 4-7天 → 1-30分钟（全量<1小时）
一致性     强一致性（RocksDB = MySQL = Redis，三层校验100%通过）
交付物     设计方案 + 代码评审（不编写业务代码）

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🚨 关键设计原则（新增）

┌─────────────────────────────────────────────────────────────────────────┐
│                    数据一致性核心原则                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. 【单一真相源】RocksDB 是恢复过程中的唯一真相源                        │
│     - 恢复时先加载 RocksDB Checkpoint                                   │
│     - 重放 WAL → 重放 Kafka → 达到最终一致                              │
│     - MySQL/Redis 只是 RocksDB 的副本                                   │
│                                                                         │
│  2. 【幂等性设计】所有操作必须幂等，支持重复执行                          │
│     - Checkpoint ID 全局唯一（时间戳+symbol+random）                    │
│     - 重复加载同一 Checkpoint 无副作用                                  │
│     - Kafka 消息按 bizSeq 去重                                          │
│                                                                         │
│  3. 【原子性边界】Checkpoint 创建是原子操作                               │
│     - 成功：元数据文件完整写入                                          │
│     - 失败：回滚到上一个有效 Checkpoint                                 │
│     - 不存在"半完成"状态                                                │
│                                                                         │
│  4. 【校验即真相】一致性校验是恢复完成的唯一标准                          │
│     - 三层校验全部通过才算成功                                          │
│     - 任一层失败立即回滚                                                │
│     - 校验结果持久化，可追溯                                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 2: 技术方案设计与评审（Week 1）

2.1 整体架构设计

┌─────────────────────────────────────────────────────────────────────────┐
│                    快照恢复系统架构（强一致性保证）                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                        Kafka (trade-entry)                        │  │
│  │  ┌──────────────────────────────────────────────────────────┐   │  │
│  │  │  分区0: [====|@@@@|$$$$|%%%%]                             │   │  │
│  │  │       ↑    ↑    ↑    ↑                                   │   │  │
│  │  │     cp1  wal   当前 offset                              │   │  │
│  │  │    (硬链) (顺序写)                                        │   │  │
│  │  └──────────────────────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│  ┌───────────────────────────▼──────────────────────────────────┐     │
│  │                    Consumer (单线程/分区)                       │     │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐               │     │
│  │  │   1. WAL   │→│ 2. RocksDB │→│ 3. Async   │               │     │
│  │  │   写入     │  │   写入     │  │  MySQL写入 │               │     │
│  │  └────────────┘  └────────────┘  └────────────┘               │     │
│  └────────────────────────────────────────────────────────────────┘     │
│                              │                                          │
│           ┌──────────────────┼──────────────────┐                      │
│           ▼                  ▼                  ▼                      │
│  ┌────────────────┐ ┌────────────────┐ ┌────────────────┐             │
│  │   WAL文件      │ │  RocksDB       │ │   MySQL        │             │
│  │   (顺序写)     │ │  (真相源)       │ │  (副本/查询)   │             │
│  │                │ │                │ │                │             │
│  │  [bizSeq+k+v] │ │  [user→snapshot]│ │  [t_snapshot]  │             │
│  └───────┬────────┘ └───────┬────────┘ └────────────────┘             │
│          │                  │                                         │
│          │    ┌─────────────┘                                         │
│          │    ▼                                                        │
│          │ ┌──────────────────────────────────────────────────┐       │
│          │ │         Checkpoint Manager (原子性创建)           │       │
│          │ │                                                    │       │
│          │ │  暂停消费 → 获取offset → 创建硬链接Checkpoint      │       │
│          │ │       ↓                                              │       │
│          │ │  写入CHECKPOINT_METADATA (offset+bizSeq+checksum)   │       │
│          │ │       ↓                                              │       │
│          │ │  恢复消费 → ZSTD压缩 → 限速上传MinIO                │       │
│          │ └──────────────────────────────────────────────────┘       │
│          │                                                            │
│          └──────────────────────┐                                     │
│                                 ▼                                     │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                    MinIO (分布式/EC)                          │    │
│  │  /local/checkpoint-{id}.tar.zst (含CHECKPOINT_METADATA)      │    │
│  │  /incremental/backup-{id}/ (增量SST)                         │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                         │
│  ═══════════════════════════════════════════════════════════════════   │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                    Recovery Service (恢复流程)                │    │
│  │                                                              │    │
│  │  1. 下载Checkpoint + CHECKPOINT_METADATA                    │    │
│  │  2. 校验CHECKPOINT_METADATA完整性                           │    │
│  │  3. 加载RocksDB Checkpoint                                  │    │
│  │  4. 【幂等重放】WAL (bizSeq > checkpoint.endBizSeq)         │    │
│  │  5. 【幂等重放】Kafka (offset > checkpoint.kafkaOffset)     │    │
│  │  6. 【强一致性校验】Count + Checksum + Sample              │    │
│  │  7. 【原子写入】MySQL (事务批量写入)                        │    │
│  │  8. 【原子写入】Redis (Pipeline批量写入)                    │    │
│  │  9. 【最终校验】RocksDB = MySQL = Redis                    │    │
│  │                                                              │    │
│  │  ⚠️ 任一步骤失败 → 自动回滚到上一个Checkpoint               │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

2.2 数据流与一致性保证

正常流程（实时写入 - 先WAL后RocksDB保证不丢数据）:
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  Kafka  │───→│ Consumer│───→│   WAL   │───→│ RocksDB │───→│  MySQL  │
│  消息   │    │  接收   │    │  顺序写 │    │  真相源 │    │  副本   │
└─────────┘    └─────────┘    └────┬────┘    └─────────┘    └─────────┘
                                   │
                                   └── fsync 每100ms/1000条

【关键保证】
- WAL 先于 RocksDB 写入：崩溃后可从 WAL 恢复
- RocksDB 先于 MySQL 写入：MySQL 只是异步副本
- 单线程消费：保证消息顺序，避免并发问题

Checkpoint流程（原子性保证）:
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ 暂停消费    │───→│ 获取offset  │───→│ 创建硬链接  │───→│ 写入元数据  │
│ (处理完当前)│    │ (内存位置)  │    │ Checkpoint  │    │ + checksum  │
└─────────────┘    └─────────────┘    └─────────────┘    └──────┬──────┘
                                                                 │
┌─────────────┐    ┌─────────────┐    ┌─────────────┐           │
│ 恢复消费    │←───│ 异步上传    │←───│ ZSTD压缩    │←──────────┘
│ (finally)   │    │ MinIO       │    │             │
└─────────────┘    └─────────────┘    └─────────────┘

【原子性保证】
- 暂停消费期间不丢消息：Kafka offset 不提交，消息会重发（幂等去重）
- 元数据文件与 SST 一起打包：不可分割
- 恢复消费在 finally 中：确保一定会执行

恢复流程（幂等 + 强一致性）:
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ 下载        │───→│ 验证元数据  │───→│ 加载RocksDB │───→│ 重放WAL     │
│ Checkpoint  │    │ 完整性      │    │ Checkpoint  │    │ (幂等)      │
└─────────────┘    └─────────────┘    └─────────────┘    └──────┬──────┘
                                                                │
┌─────────────┐    ┌─────────────┐    ┌─────────────┐           │
│ 强一致性    │←───│ 写入MySQL   │←───│ 重放Kafka   │←──────────┘
│ 校验        │    │ + Redis     │    │ (幂等)      │
└──────┬──────┘    └─────────────┘    └─────────────┘
       │
   [通过？]
   /      \
  是      否
  ↓       ↓
完成    回滚

2.3 技术选型对比

组件       选型       备选            选型理由
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
本地存储   RocksDB    LevelDB/MapDB   支持CF、Checkpoint、Backup、WAL
对象存储   MinIO      OSS/S3          开源、EC容错、S3兼容
压缩格式   ZSTD       GZIP/Snappy     高压缩比、分级压缩支持
序列化     Protobuf   JSON/Kryo       体积小、Schema版本管理
定时任务   XXL-Job    Quartz/Spring   分布式调度、幂等锁
WAL        自定义     Chronicle Queue 顺序写、fsync可控

2.4 Checkpoint策略设计

┌─────────────────────────────────────────────────────────────────────────┐
│                      Checkpoint 分层策略                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  【本地快速Checkpoint】每15分钟                                           │
│  ├── 实现：RocksDB.createCheckpoint() (硬链接)                          │
│  ├── 速度：秒级创建                                                     │
│  ├── 存储：本地磁盘 (几乎0额外空间)                                     │
│  ├── 用途：WAL截断、快速本地恢复                                        │
│  └── 保留：24小时                                                       │
│                                                                         │
│  【远程增量Backup】每4小时                                               │
│  ├── 实现：RocksDB BackupEngine.createNewBackup()                       │
│  ├── 速度：分钟级（取决于变更数据量）                                   │
│  ├── 存储：MinIO (只传新增SST文件)                                      │
│  ├── 用途：灾难恢复、跨机房备份                                         │
│  └── 保留：7天                                                          │
│                                                                         │
│  【全量归档Backup】每日凌晨2点                                           │
│  ├── 实现：全量SST打包                                                  │
│  ├── 速度：小时级                                                       │
│  ├── 存储：MinIO冷存储                                                  │
│  ├── 用途：合规审计、长期存储                                           │
│  └── 保留：90天                                                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

对比说明：
┌──────────┬────────────────────┬────────────────────────────┬────────────┐
│   特性   │ RocksDB Checkpoint │ RocksDB Incremental Backup │ Full Backup│
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 实现方式 │ 硬链接 SST 文件    │ 复制新增/修改的 SST        │ 全量打包   │
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 创建速度 │ 秒级               │ 分钟级                     │ 小时级     │
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 磁盘占用 │ 几乎为0 (硬链接)   │ 增量大小                   │ 全量大小   │
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 网络传输 │ 无                 │ 增量上传                   │ 全量上传   │
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 恢复速度 │ 最快 (本地)        │ 快 (下载增量)              │ 慢 (下载全量)│
├──────────┼────────────────────┼────────────────────────────┼────────────┤
│ 适用场景 │ 本地快速恢复       │ 远程灾备                   │ 长期归档   │
└──────────┴────────────────────┴────────────────────────────┴────────────┘

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 3: RocksDB 本地状态存储集成（Week 2）

3.1 模块结构规划

snapshot-account-core/
├── src/main/java/com/exchange/snapshot/
│   ├── rocksdb/
│   │   ├── RocksDBConfig.java                # 配置类（分级压缩）
│   │   ├── RocksDBManager.java               # 管理器（含幂等写入）
│   │   ├── ColumnFamilyManager.java          # ColumnFamily管理
│   │   ├── AccountSnapshotStore.java         # 账户存储（带版本）
│   │   ├── PositionSnapshotStore.java        # 持仓存储（带版本）
│   │   ├── MetadataStore.java                # 元数据存储
│   │   ├── AtomicCheckpointManager.java      # 【核心】原子性Checkpoint
│   │   └── WALManager.java                   # 【核心】WAL管理（fsync）
│   ├── recovery/
│   │   ├── RecoveryService.java              # 恢复服务
│   │   ├── WALReplayer.java                  # WAL重放（幂等）
│   │   ├── KafkaReplayer.java                # Kafka重放（幂等）
│   │   ├── ConsistencyChecker.java           # 【核心】强一致性校验
│   │   └── RecoveryRollbackManager.java      # 回滚管理
│   └── consistency/
│       ├── ChecksumCalculator.java           # Checksum计算
│       ├── SampleVerifier.java               # 抽样校验
│       └── FinalConsistencyVerifier.java     # 最终一致性
└── pom.xml

3.2 【核心】WAL Manager 设计（保证不丢数据）

@Service
@Slf4j
public class WALManager {
    
    private FileChannel walChannel;
    private ByteBuffer writeBuffer;
    private volatile long lastFsyncTime;
    private volatile long entryCount = 0;
    private final Object fsyncLock = new Object();
    
    // WAL条目格式：
    // [magic:4][version:4][bizSeq:8][keyLen:4][key][valueLen:4][value][crc32:4]
    private static final int MAGIC = 0x57414C21;  // "WAL!"
    private static final int VERSION = 1;
    
    /**
     * 【关键】写入WAL - 顺序写 + 定期fsync
     * 
     * 保证：
     * 1. WAL写入成功后，数据不丢失
     * 2. 批量fsync减少IO压力
     * 3. 崩溃后可从WAL恢复
     */
    public void append(long bizSeq, byte[] key, byte[] value) throws IOException {
        // 计算CRC32
        CRC32 crc32 = new CRC32();
        crc32.update(key);
        crc32.update(value);
        long crc = crc32.getValue();
        
        // 序列化
        ByteBuffer buffer = ByteBuffer.allocate(
            4 + 4 + 8 + 4 + key.length + 4 + value.length + 4);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(bizSeq);
        buffer.putInt(key.length);
        buffer.put(key);
        buffer.putInt(value.length);
        buffer.put(value);
        buffer.putInt((int) crc);
        buffer.flip();
        
        // 写入文件（同步锁保证顺序）
        synchronized (walChannel) {
            walChannel.write(buffer);
            entryCount++;
            
            // 触发fsync条件：每1000条或每100ms
            long now = System.currentTimeMillis();
            if (entryCount % 1000 == 0 || now - lastFsyncTime > 100) {
                fsync();
            }
        }
    }
    
    /**
     * 【关键】从WAL重放 - 支持幂等
     * 
     * 只重放 bizSeq > fromBizSeq 的条目
     * 幂等性保证：相同bizSeq重复写入RocksDB结果一致
     */
    public void replay(long fromBizSeq, Consumer<WALEntry> consumer) throws IOException {
        Path walFile = Paths.get(walPath, "current.wal");
        if (!Files.exists(walFile)) {
            log.warn("[WAL] No WAL file found");
            return;
        }
        
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.READ)) {
            ByteBuffer headerBuffer = ByteBuffer.allocate(16);  // magic + version + bizSeq
            long replayedCount = 0;
            long skippedCount = 0;
            
            while (channel.position() < channel.size()) {
                // 读取头部
                headerBuffer.clear();
                channel.read(headerBuffer);
                headerBuffer.flip();
                
                int magic = headerBuffer.getInt();
                if (magic != MAGIC) {
                    log.error("[WAL] Invalid magic number, possible corruption");
                    break;
                }
                
                headerBuffer.getInt();  // version
                long bizSeq = headerBuffer.getLong();
                
                // 读取key
                ByteBuffer keyLenBuffer = ByteBuffer.allocate(4);
                channel.read(keyLenBuffer);
                keyLenBuffer.flip();
                int keyLen = keyLenBuffer.getInt();
                ByteBuffer keyBuffer = ByteBuffer.allocate(keyLen);
                channel.read(keyBuffer);
                byte[] key = keyBuffer.array();
                
                // 读取value
                ByteBuffer valueLenBuffer = ByteBuffer.allocate(4);
                channel.read(valueLenBuffer);
                valueLenBuffer.flip();
                int valueLen = valueLenBuffer.getInt();
                ByteBuffer valueBuffer = ByteBuffer.allocate(valueLen);
                channel.read(valueBuffer);
                byte[] value = valueBuffer.array();
                
                // 读取CRC并校验
                ByteBuffer crcBuffer = ByteBuffer.allocate(4);
                channel.read(crcBuffer);
                crcBuffer.flip();
                int storedCrc = crcBuffer.getInt();
                
                CRC32 crc32 = new CRC32();
                crc32.update(key);
                crc32.update(value);
                if ((int) crc32.getValue() != storedCrc) {
                    log.error("[WAL] CRC mismatch for bizSeq={}", bizSeq);
                    continue;
                }
                
                // 幂等过滤：只处理大于fromBizSeq的条目
                if (bizSeq > fromBizSeq) {
                    consumer.accept(new WALEntry(bizSeq, key, value));
                    replayedCount++;
                } else {
                    skippedCount++;
                }
            }
            
            log.info("[WAL] Replay complete: replayed={}, skipped={}", 
                replayedCount, skippedCount);
        }
    }
    
    /**
     * 【关键】Checkpoint后截断WAL
     * 
     * 创建新的WAL文件，删除旧的（原子重命名）
     */
    public void truncate(long checkpointBizSeq) throws IOException {
        Path currentWal = Paths.get(walPath, "current.wal");
        Path archivedWal = Paths.get(walPath, "archived-" + checkpointBizSeq + ".wal");
        
        // 先fsync确保数据落盘
        fsync();
        
        // 原子重命名（创建新的current.wal）
        Files.move(currentWal, archivedWal, StandardCopyOption.ATOMIC_MOVE);
        
        // 创建新的WAL文件
        walChannel = FileChannel.open(currentWal, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
        
        log.info("[WAL] Truncated at bizSeq={}, archived to {}", 
            checkpointBizSeq, archivedWal);
    }
    
    private void fsync() throws IOException {
        synchronized (fsyncLock) {
            walChannel.force(false);  // 只刷数据，不刷元数据（更快）
            lastFsyncTime = System.currentTimeMillis();
        }
    }
}

3.3 【核心】原子性 Checkpoint Manager

@Service
@Slf4j
public class AtomicCheckpointManager {
    
    private static final String METADATA_FILENAME = "CHECKPOINT_METADATA";
    private final AtomicBoolean checkpointInProgress = new AtomicBoolean(false);
    
    /**
     * 【核心】创建原子性Checkpoint
     * 
     * 原子性保证：
     * 1. 暂停消费后，当前处理的消息完成，offset不提交
     * 2. 获取的offset是"下一个要处理的消息"的位置
     * 3. 元数据文件和SST文件一起打包，不可分割
     * 4. 恢复消费在finally中，确保一定会执行
     * 5. 即使上传MinIO失败，本地Checkpoint仍然有效
     * 
     * 幂等性保证：
     * - checkpointId 包含时间戳和随机数，全局唯一
     * - 重复创建同一checkpointId 会覆盖（但业务上不会重复）
     */
    public CheckpointResult createAtomicCheckpoint(
            String symbol, 
            KafkaConsumer<String, byte[]> consumer) {
        
        // 防重入
        if (!checkpointInProgress.compareAndSet(false, true)) {
            log.warn("[Checkpoint] Another checkpoint in progress, skipping");
            return CheckpointResult.skipped("Another checkpoint in progress");
        }
        
        String checkpointId = generateCheckpointId(symbol);
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[Checkpoint] Starting atomic checkpoint: id={}, symbol={}", 
                checkpointId, symbol);
            
            // 1. 【关键】暂停消费 - 当前消息处理完成后暂停
            consumer.pause();
            
            // 等待当前处理的消息完成（最多等5秒）
            waitForCurrentProcessing(5000);
            
            // 2. 【关键】获取当前Kafka offset（内存中的位置）
            Set<TopicPartition> partitions = consumer.assignment();
            Map<TopicPartition, OffsetAndMetadata> currentOffsets = 
                consumer.committed(new HashSet<>(partitions));
            
            // 计算各分区的offset（取最小值保证不丢消息）
            Map<Integer, Long> partitionOffsets = new HashMap<>();
            long minOffset = Long.MAX_VALUE;
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata metadata = currentOffsets.get(partition);
                long offset = metadata != null ? metadata.offset() : 0;
                partitionOffsets.put(partition.partition(), offset);
                minOffset = Math.min(minOffset, offset);
            }
            
            // 3. 获取当前bizSeq
            long currentBizSeq = getCurrentBizSeq();
            
            // 4. 创建RocksDB Checkpoint（硬链接，秒级）
            Path checkpointPath = Paths.get(checkpointDir, checkpointId);
            rocksDB.createCheckpoint(checkpointPath.toString());
            
            // 5. 【关键】计算数据Checksum（遍历所有SST文件）
            String dataChecksum = calculateCheckpointChecksum(checkpointPath);
            
            // 6. 【关键】构建并写入元数据文件（与SST一起）
            CheckpointMetadata metadata = CheckpointMetadata.builder()
                .checkpointId(checkpointId)
                .symbol(symbol)
                .checkpointType(CheckpointType.LOCAL.getCode())
                .kafkaTopic("trade-entry-" + symbol)
                .partitionOffsets(partitionOffsets)
                .minKafkaOffset(minOffset)
                .bizSeq(currentBizSeq)
                .dataChecksum(dataChecksum)
                .accountSchemaVersion(CURRENT_ACCOUNT_SCHEMA_VERSION)
                .positionSchemaVersion(CURRENT_POSITION_SCHEMA_VERSION)
                .createdAt(startTime)
                .build();
            
            writeMetadataFile(checkpointPath, metadata);
            
            // 7. 验证元数据文件可读
            CheckpointMetadata verifyMetadata = readMetadataFile(checkpointPath);
            if (!verifyMetadata.getCheckpointId().equals(checkpointId)) {
                throw new CheckpointException("Metadata file verification failed");
            }
            
            // 8. 【关键】截断WAL（因为Checkpoint已包含这部分数据）
            walManager.truncate(currentBizSeq);
            
            // 9. 异步上传到MinIO（不影响本地Checkpoint）
            asyncUploadToMinIO(checkpointPath, metadata);
            
            log.info("[Checkpoint] Created successfully: id={}, duration={}ms, offset={}",
                checkpointId, System.currentTimeMillis() - startTime, minOffset);
            
            return CheckpointResult.success(checkpointId, metadata);
            
        } catch (Exception e) {
            log.error("[Checkpoint] Failed: id={}", checkpointId, e);
            // 清理失败的Checkpoint
            cleanupFailedCheckpoint(checkpointId);
            return CheckpointResult.failure(checkpointId, e.getMessage());
            
        } finally {
            // 10. 【关键】恢复消费（必须在finally中）
            try {
                consumer.resume();
                log.info("[Checkpoint] Consumer resumed");
            } catch (Exception e) {
                log.error("[Checkpoint] Failed to resume consumer", e);
                // 这里需要告警，人工介入
                alertService.sendAlert("Consumer resume failed after checkpoint");
            }
            checkpointInProgress.set(false);
        }
    }
    
    /**
     * 生成全局唯一的Checkpoint ID
     */
    private String generateCheckpointId(String symbol) {
        return String.format("%s-%d-%s",
            symbol,
            System.currentTimeMillis(),
            RandomStringUtils.randomAlphanumeric(8));
    }
    
    /**
     * 计算Checkpoint的Checksum（SHA256）
     */
    private String calculateCheckpointChecksum(Path checkpointPath) throws IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        // 遍历所有SST文件
        try (Stream<Path> paths = Files.walk(checkpointPath)) {
            paths.filter(Files::isRegularFile)
                .sorted()  // 保证顺序一致
                .forEach(file -> {
                    try {
                        byte[] content = Files.readAllBytes(file);
                        digest.update(content);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
        
        return Hex.encodeHexString(digest.digest());
    }
}

3.4 ColumnFamily 设计

@Configuration
public class ColumnFamilyConfig {
    
    public static final String ACCOUNT_CF = "account_snapshot";
    public static final String POSITION_CF = "position_snapshot";
    public static final String METADATA_CF = "metadata";
    
    public List<ColumnFamilyDescriptor> createColumnFamilies() {
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        
        // 1. 账户快照 CF - 热数据，LZ4快速压缩
        ColumnFamilyOptions accountOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.LZ4_COMPRESSION)
            .setWriteBufferSize(256 * 1024 * 1024)  // 256MB
            .setTargetFileSizeBase(128 * 1024 * 1024);
        descriptors.add(new ColumnFamilyDescriptor(
            ACCOUNT_CF.getBytes(StandardCharsets.UTF_8), accountOptions));
        
        // 2. 持仓快照 CF - 温数据，ZSTD高压缩比
        ColumnFamilyOptions positionOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setWriteBufferSize(128 * 1024 * 1024)
            .setTargetFileSizeBase(128 * 1024 * 1024);
        descriptors.add(new ColumnFamilyDescriptor(
            POSITION_CF.getBytes(StandardCharsets.UTF_8), positionOptions));
        
        // 3. 元数据 CF - 小数据，不压缩
        ColumnFamilyOptions metadataOptions = new ColumnFamilyOptions()
            .setCompressionType(CompressionType.NO_COMPRESSION);
        descriptors.add(new ColumnFamilyDescriptor(
            METADATA_CF.getBytes(StandardCharsets.UTF_8), metadataOptions));
        
        return descriptors;
    }
}

3.5 依赖配置

<dependencies>
    <dependency>
        <groupId>org.rocksdb</groupId>
        <artifactId>rocksdbjni</artifactId>
        <version>8.9.1</version>
    </dependency>
    <dependency>
        <groupId>com.github.luben</groupId>
        <artifactId>zstd-jni</artifactId>
        <version>1.5.5-5</version>
    </dependency>
    <dependency>
        <groupId>com.google.guava</groupId>
        <artifactId>guava</artifactId>
        <version>32.1.3-jre</version>
    </dependency>
</dependencies>

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 4: 元数据表与分区设计（Week 2）

4.1 数据库 Schema

-- ============================================
-- Checkpoint 元数据表（按月分区）
-- ============================================

CREATE TABLE snapshot_checkpoint (
    checkpoint_id       VARCHAR(64) PRIMARY KEY COMMENT '全局唯一ID',
    checkpoint_type     TINYINT NOT NULL COMMENT '1=LOCAL,2=INCREMENTAL,3=FULL',
    service_type        VARCHAR(32) NOT NULL COMMENT 'ACCOUNT/POSITION',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 序列号范围
    start_biz_seq       BIGINT NOT NULL COMMENT '起始bizSeq',
    end_biz_seq         BIGINT NOT NULL COMMENT '结束bizSeq (= checkpoint时的bizSeq)',
    
    -- Kafka offset信息（JSON格式存储多分区）
    kafka_topic         VARCHAR(64) NOT NULL,
    partition_offsets   JSON NOT NULL COMMENT '{"0":1234,"1":5678}',
    min_kafka_offset    BIGINT NOT NULL COMMENT '最小offset（用于恢复定位）',
    
    -- Schema版本
    account_schema_version INT DEFAULT 1,
    position_schema_version INT DEFAULT 1,
    
    -- 统计信息
    user_count          INT NOT NULL DEFAULT 0,
    data_size_bytes     BIGINT NOT NULL DEFAULT 0,
    compressed_size_bytes BIGINT NOT NULL DEFAULT 0,
    compression_ratio   DECIMAL(4,2),
    
    -- 存储信息
    storage_bucket      VARCHAR(64) NOT NULL,
    storage_path        VARCHAR(512) NOT NULL,
    storage_url         VARCHAR(1024),
    
    -- 校验信息（关键）
    data_checksum       VARCHAR(64) NOT NULL COMMENT 'SHA256',
    
    -- 状态
    status              TINYINT NOT NULL DEFAULT 0 COMMENT '0=CREATING,1=COMPLETED,2=FAILED,3=DELETED',
    error_message       VARCHAR(1024),
    
    -- 时间戳
    created_at          BIGINT NOT NULL,
    completed_at        BIGINT,
    expires_at          BIGINT,
    
    -- 索引
    INDEX idx_symbol_type_time (symbol, checkpoint_type, created_at),
    INDEX idx_biz_seq (end_biz_seq),
    INDEX idx_status_time (status, created_at)
    
) ENGINE=InnoDB
PARTITION BY RANGE (created_at) (
    PARTITION p202401 VALUES LESS THAN (1706745600000),
    PARTITION p202402 VALUES LESS THAN (1709251200000),
    PARTITION p202403 VALUES LESS THAN (1711929600000),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- ============================================
-- 恢复历史记录表（完整追溯）
-- ============================================

CREATE TABLE snapshot_recovery_history (
    recovery_id         VARCHAR(64) PRIMARY KEY,
    checkpoint_id       VARCHAR(64) NOT NULL,
    recovery_type       VARCHAR(32) NOT NULL COMMENT 'FAST/POINT_IN_TIME/FULL',
    
    service_type        VARCHAR(32) NOT NULL,
    symbol              VARCHAR(32) NOT NULL,
    
    -- 恢复范围
    start_biz_seq       BIGINT NOT NULL,
    end_biz_seq         BIGINT NOT NULL,
    processed_events    BIGINT NOT NULL DEFAULT 0,
    
    -- 性能指标（毫秒）
    download_duration_ms        BIGINT,
    checkpoint_load_duration_ms BIGINT,
    wal_replay_duration_ms      BIGINT,
    kafka_replay_duration_ms    BIGINT,
    mysql_sync_duration_ms      BIGINT,
    redis_sync_duration_ms      BIGINT,
    consistency_check_duration_ms BIGINT,
    total_duration_ms           BIGINT NOT NULL,
    
    -- 一致性校验结果（关键）
    consistency_check_passed    BOOLEAN NOT NULL DEFAULT FALSE,
    count_check_passed         BOOLEAN,
    checksum_check_passed      BOOLEAN,
    sample_check_passed        BOOLEAN,
    final_check_passed         BOOLEAN COMMENT 'RocksDB=MySQL=Redis',
    rocks_count                BIGINT,
    mysql_count                BIGINT,
    redis_count                BIGINT,
    checksum_details           JSON,
    
    -- 状态
    status              TINYINT NOT NULL COMMENT '0=PENDING,1=RUNNING,2=SUCCESS,3=FAILED,4=ROLLED_BACK',
    error_message       TEXT,
    rollback_reason     VARCHAR(512),
    
    -- 操作信息
    triggered_by        VARCHAR(64) COMMENT 'USER/SYSTEM/AUTO',
    operator            VARCHAR(64),
    
    -- 时间戳
    start_time          BIGINT NOT NULL,
    end_time            BIGINT,
    created_at          BIGINT NOT NULL,
    
    INDEX idx_checkpoint (checkpoint_id),
    INDEX idx_symbol_time (symbol, start_time),
    INDEX idx_status (status)
    
) ENGINE=InnoDB;

-- ============================================
-- 清理历史
-- ============================================

CREATE TABLE snapshot_checkpoint_cleanup (
    cleanup_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    checkpoint_id       VARCHAR(64) NOT NULL,
    cleanup_action      VARCHAR(32) NOT NULL COMMENT 'DELETE/ARCHIVE',
    cleaned_at          BIGINT NOT NULL,
    cleaned_by          VARCHAR(64) NOT NULL,
    
    INDEX idx_checkpoint (checkpoint_id)
) ENGINE=InnoDB;

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 5: Checkpoint 定时任务（Week 3-4）

5.1 任务调度设计

@Component
@Slf4j
public class CheckpointScheduler {
    
    @Autowired
    private AtomicCheckpointManager checkpointManager;
    
    // 本地Checkpoint：每15分钟
    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "localCheckpoint", lockAtMostFor = "14m")
    public void localCheckpoint() {
        for (String symbol : getActiveSymbols()) {
            try {
                CheckpointResult result = checkpointManager.createAtomicCheckpoint(
                    symbol, kafkaConsumer);
                if (!result.isSuccess()) {
                    alertService.alert("Checkpoint failed: " + result.getMessage());
                }
            } catch (Exception e) {
                log.error("[Scheduler] Checkpoint failed for symbol={}", symbol, e);
            }
        }
    }
    
    // 增量Backup：每4小时
    @Scheduled(cron = "0 0 */4 * * *")
    @SchedulerLock(name = "incrementalBackup", lockAtMostFor = "3h")
    public void incrementalBackup() {
        // 使用RocksDB BackupEngine创建增量备份
    }
    
    // 全量Backup：每日凌晨2点
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "fullBackup", lockAtMostFor = "23h")
    public void fullBackup() {
        // 创建全量归档
    }
    
    // 清理过期Checkpoint
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "cleanupCheckpoint", lockAtMostFor = "30m")
    public void cleanupExpiredCheckpoints() {
        cleanupService.cleanupExpired();
    }
}

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 6: MinIO 部署（Week 3）

6.1 分布式部署

# docker-compose.minio-cluster.yml
version: '3.8'

services:
  minio1:
    image: minio/minio:latest
    hostname: minio1
    volumes:
      - minio1-data:/data
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    command: server http://minio{1...4}/data --console-address ":9001"
    
  minio2:
    image: minio/minio:latest
    hostname: minio2
    volumes:
      - minio2-data:/data
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    command: server http://minio{1...4}/data --console-address ":9001"
    
  minio3:
    image: minio/minio:latest
    hostname: minio3
    volumes:
      - minio3-data:/data
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    command: server http://minio{1...4}/data --console-address ":9001"
    
  minio4:
    image: minio/minio:latest
    hostname: minio4
    volumes:
      - minio4-data:/data
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    command: server http://minio{1...4}/data --console-address ":9001"
    
  nginx:
    image: nginx:alpine
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro

volumes:
  minio1-data:
  minio2-data:
  minio3-data:
  minio4-data:

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 7: 【核心】恢复服务实现（Week 4-5）

7.1 【核心】强一致性恢复流程

@Service
@Slf4j
public class ConsistencyRecoveryService {
    
    @Autowired
    private RocksDBManager rocksDBManager;
    @Autowired
    private WALManager walManager;
    @Autowired
    private MinIOService minioService;
    @Autowired
    private ConsistencyChecker consistencyChecker;
    @Autowired
    private RecoveryHistoryMapper recoveryHistoryMapper;
    
    /**
     * 【核心】快速恢复 - 保证强一致性
     * 
     * 恢复流程：
     * 1. 下载Checkpoint
     * 2. 验证元数据完整性
     * 3. 加载RocksDB Checkpoint
     * 4. 【幂等】重放WAL
     * 5. 【幂等】重放Kafka
     * 6. 【强一致性校验】三层校验
     * 7. 【原子写入】MySQL
     * 8. 【原子写入】Redis
     * 9. 【最终校验】三存储一致性
     * 
     * 失败处理：
     * - 任一步骤失败立即回滚
     * - 记录失败原因
     * - 可重试或人工介入
     */
    public RecoveryResult fastRecovery(String symbol, ServiceType serviceType) {
        String recoveryId = generateRecoveryId();
        long totalStartTime = System.currentTimeMillis();
        
        // 记录恢复开始
        recoveryHistoryMapper.insert(RecoveryHistory.builder()
            .recoveryId(recoveryId)
            .symbol(symbol)
            .serviceType(serviceType)
            .status(RecoveryStatus.RUNNING)
            .startTime(totalStartTime)
            .build());
        
        try {
            // 1. 获取最近完成的Checkpoint
            SnapshotCheckpoint checkpoint = checkpointMapper
                .selectLatestCompleted(symbol, serviceType);
            
            if (checkpoint == null) {
                throw new RecoveryException("No available checkpoint found");
            }
            
            log.info("[Recovery] Using checkpoint: id={}, symbol={}, bizSeq={}",
                checkpoint.getCheckpointId(), symbol, checkpoint.getEndBizSeq());
            
            // 2. 下载Checkpoint
            long downloadStart = System.currentTimeMillis();
            Path checkpointFile = minioService.download(
                checkpoint.getStorageBucket(),
                checkpoint.getStoragePath());
            long downloadDuration = System.currentTimeMillis() - downloadStart;
            
            // 3. 解压
            Path checkpointDir = decompressCheckpoint(checkpointFile);
            
            // 4. 验证元数据文件
            CheckpointMetadata metadata = readMetadataFile(checkpointDir);
            if (!metadata.getCheckpointId().equals(checkpoint.getCheckpointId())) {
                throw new RecoveryException("Checkpoint metadata mismatch");
            }
            
            // 5. 【关键】加载RocksDB Checkpoint
            long loadStart = System.currentTimeMillis();
            rocksDBManager.loadCheckpoint(checkpointDir);
            long loadDuration = System.currentTimeMillis() - loadStart;
            
            // 6. 【关键】【幂等】重放WAL
            long walStart = System.currentTimeMillis();
            AtomicLong walReplayedCount = new AtomicLong(0);
            walManager.replay(metadata.getEndBizSeq(), entry -> {
                rocksDBManager.put(entry.getKey(), entry.getValue());
                walReplayedCount.incrementAndGet();
            });
            long walDuration = System.currentTimeMillis() - walStart;
            
            // 7. 【关键】【幂等】重放Kafka
            long kafkaStart = System.currentTimeMillis();
            long kafkaReplayedCount = replayKafkaEvents(
                symbol, 
                metadata.getMinKafkaOffset(),
                metadata.getEndBizSeq());
            long kafkaDuration = System.currentTimeMillis() - kafkaStart;
            
            // 8. 【关键】强一致性校验
            long checkStart = System.currentTimeMillis();
            ConsistencyReport consistencyReport = consistencyChecker.verify(symbol);
            long checkDuration = System.currentTimeMillis() - checkStart;
            
            if (!consistencyReport.isFullyConsistent()) {
                // 校验失败，回滚
                rollbackRecovery(symbol);
                
                RecoveryResult failure = RecoveryResult.failure(recoveryId, 
                    "Consistency check failed: " + consistencyReport.getErrors());
                
                saveRecoveryFailure(recoveryId, failure, consistencyReport);
                return failure;
            }
            
            // 9. 【关键】原子写入MySQL
            long mysqlStart = System.currentTimeMillis();
            syncToMySQL(symbol);
            long mysqlDuration = System.currentTimeMillis() - mysqlStart;
            
            // 10. 【关键】原子写入Redis
            long redisStart = System.currentTimeMillis();
            syncToRedis(symbol);
            long redisDuration = System.currentTimeMillis() - redisStart;
            
            // 11. 【关键】最终一致性校验（RocksDB = MySQL = Redis）
            FinalConsistencyReport finalReport = consistencyChecker
                .verifyFinalConsistency(symbol);
            
            if (!finalReport.isConsistent()) {
                rollbackRecovery(symbol);
                throw new RecoveryException("Final consistency check failed");
            }
            
            // 12. 记录成功
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            
            RecoveryResult success = RecoveryResult.success(
                recoveryId, 
                totalDuration,
                checkpoint.getEndBizSeq(),
                walReplayedCount.get(),
                kafkaReplayedCount);
            
            saveRecoverySuccess(recoveryId, success, checkpoint, 
                downloadDuration, loadDuration, walDuration, kafkaDuration,
                mysqlDuration, redisDuration, checkDuration, consistencyReport);
            
            log.info("[Recovery] Success: id={}, duration={}ms", recoveryId, totalDuration);
            
            return success;
            
        } catch (Exception e) {
            log.error("[Recovery] Failed: id={}", recoveryId, e);
            rollbackRecovery(symbol);
            
            RecoveryResult failure = RecoveryResult.failure(recoveryId, e.getMessage());
            saveRecoveryFailure(recoveryId, failure, e);
            return failure;
        }
    }
    
    /**
     * 【关键】Kafka重放 - 幂等设计
     * 
     * 幂等保证：
     * - 按bizSeq去重，相同bizSeq只处理一次
     * - 即使消息重复消费，结果一致
     */
    private long replayKafkaEvents(String symbol, long fromOffset, long fromBizSeq) {
        KafkaConsumer<String, byte[]> consumer = createConsumer();
        
        TopicPartition partition = new TopicPartition("trade-entry-" + symbol, 0);
        consumer.assign(Collections.singletonList(partition));
        consumer.seek(partition, fromOffset);
        
        long replayedCount = 0;
        long lastBizSeq = fromBizSeq;
        
        while (true) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));
            
            if (records.isEmpty()) {
                // 检查是否消费到最新
                long currentOffset = consumer.position(partition);
                long endOffset = consumer.endOffsets(Collections.singletonList(partition))
                    .get(partition);
                if (currentOffset >= endOffset - 1) {
                    break;
                }
                continue;
            }
            
            for (ConsumerRecord<String, byte[]> record : records) {
                TradeEvent event = deserialize(record.value());
                
                // 【关键】幂等去重：只处理大于fromBizSeq的事件
                if (event.getBizSeq() > fromBizSeq) {
                    applyEventToRocksDB(event);
                    replayedCount++;
                    lastBizSeq = Math.max(lastBizSeq, event.getBizSeq());
                }
            }
            
            if (replayedCount % 10000 == 0) {
                log.info("[Recovery] Kafka replay progress: replayed={}, lastBizSeq={}",
                    replayedCount, lastBizSeq);
            }
        }
        
        consumer.close();
        return replayedCount;
    }
}

7.2 【核心】强一致性校验器

@Service
@Slf4j
public class ConsistencyChecker {
    
    /**
     * 【核心】三层一致性校验
     * 
     * Level 1: 数量校验（快速）
     * Level 2: Checksum校验（全量）  
     * Level 3: 抽样校验（1%，验证内容）
     * 
     * 全部通过才算一致
     */
    public ConsistencyReport verify(String symbol) {
        ConsistencyReport report = new ConsistencyReport();
        
        // Level 1: 数量校验
        long rocksCount = rocksDBManager.count(symbol);
        long mysqlCount = mysqlMapper.countBySymbol(symbol);
        report.setCountCheck(rocksCount == mysqlCount);
        report.setRocksCount(rocksCount);
        report.setMysqlCount(mysqlCount);
        
        if (!report.isCountCheck()) {
            report.addError("Count mismatch: RocksDB={}, MySQL={}", rocksCount, mysqlCount);
            return report;  // 快速失败
        }
        
        // Level 2: Checksum校验
        String rocksChecksum = calculateRocksDBChecksum(symbol);
        String mysqlChecksum = calculateMySQLChecksum(symbol);
        report.setChecksumCheck(rocksChecksum.equals(mysqlChecksum));
        report.setRocksChecksum(rocksChecksum);
        report.setMysqlChecksum(mysqlChecksum);
        
        if (!report.isChecksumCheck()) {
            report.addError("Checksum mismatch");
        }
        
        // Level 3: 抽样校验（1%）
        int sampleSize = (int) Math.max(100, rocksCount * 0.01);  // 最少100条
        List<Long> sampleIds = randomSample(rocksCount, sampleSize);
        int mismatchCount = 0;
        
        for (Long userId : sampleIds) {
            AccountSnapshot rocksData = rocksDBManager.get(userId);
            AccountSnapshot mysqlData = mysqlMapper.selectById(userId);
            
            if (!isEqual(rocksData, mysqlData)) {
                mismatchCount++;
                report.addMismatch(userId, rocksData, mysqlData);
            }
        }
        
        report.setSampleCheck(mismatchCount == 0);
        report.setSampleTotal(sampleSize);
        report.setSampleMismatch(mismatchCount);
        
        return report;
    }
    
    /**
     * 【关键】最终一致性校验（RocksDB = MySQL = Redis）
     */
    public FinalConsistencyReport verifyFinalConsistency(String symbol) {
        FinalConsistencyReport report = new FinalConsistencyReport();
        
        long rocksCount = rocksDBManager.count(symbol);
        long mysqlCount = mysqlMapper.countBySymbol(symbol);
        long redisCount = redisTemplate.opsForHash().size("snapshot:" + symbol);
        
        report.setRocksCount(rocksCount);
        report.setMysqlCount(mysqlCount);
        report.setRedisCount(redisCount);
        
        // 三者必须相等
        boolean consistent = rocksCount == mysqlCount && mysqlCount == redisCount;
        report.setConsistent(consistent);
        
        if (!consistent) {
            log.error("[Consistency] Final check failed: RocksDB={}, MySQL={}, Redis={}",
                rocksCount, mysqlCount, redisCount);
        }
        
        return report;
    }
}

7.3 恢复耗时预估

数据量        Checkpoint加载   WAL重放    Kafka重放   MySQL同步   一致性校验   总耗时
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
15分钟数据    10秒            1秒        0           30秒        10秒        ~1分钟
1小时数据     10秒            5秒        30秒        1分钟       15秒        ~2分钟
1天数据       10秒            10秒       5分钟       3分钟       20秒        ~9分钟
1周数据       10秒            30秒       30分钟      5分钟       30秒        ~40分钟

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 8: 服务整合（Week 5-6）

8.1 Schema版本管理

// Protobuf with schema version
message AccountSnapshot {
    int32 schema_version = 1;      // 版本号
    int64 user_id = 2;
    int64 available_balance = 3;
    int64 frozen_balance = 4;
    // ...
}

@Service
public class VersionedDeserializer {
    
    public AccountSnapshot deserialize(byte[] data) {
        AccountSnapshot snapshot = AccountSnapshot.parseFrom(data);
        
        if (snapshot.getSchemaVersion() < CURRENT_VERSION) {
            return migrate(snapshot);
        }
        
        return snapshot;
    }
}

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 9: 性能测试（Week 6-7）

测试场景：
1. Checkpoint创建性能 - 100万用户 < 5秒
2. WAL写入性能 - 10万TPS，延迟 < 1ms
3. 恢复性能 - 1周数据 < 40分钟
4. 一致性校验 - 100万数据 < 30秒
5. 并发恢复 - 10个symbol并行

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
🗂️ Phase 10: 部署与运维（Week 7-8）

10.1 应急Runbook

场景1: 快速恢复（15分钟数据丢失）
```bash
# 自动恢复（推荐）
curl -X POST "http://localhost:8085/api/v1/recovery/fast" \
  -d '{"symbol":"BTCUSDT","serviceType":"ACCOUNT"}'

# 预期耗时：1-2分钟
```

场景2: 一致性校验失败处理
```bash
# 查看失败详情
curl http://localhost:8085/api/v1/recovery/{recoveryId}/consistency

# 系统会自动回滚，如需手动触发全量重建
curl -X POST "http://localhost:8085/api/v1/recovery/full-rebuild" \
  -d '{"symbol":"BTCUSDT"}'
```

10.2 监控告警

关键指标：
- checkpoint_success_rate < 99% → 告警
- recovery_duration > 1小时 → 告警  
- consistency_check_failed > 0 → 严重告警
- rocksdb_write_latency_p99 > 100ms → 告警

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
✅ 数据一致性保证总结

┌─────────────────────────────────────────────────────────────────────────┐
│                         一致性保证机制                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  【写入阶段】                                                            │
│  1. WAL先行 - 数据先写WAL再写RocksDB，崩溃可恢复                        │
│  2. 单线程消费 - 避免并发导致的数据混乱                                  │
│  3. 异步MySQL - RocksDB是真相源，MySQL是副本                            │
│                                                                         │
│  【Checkpoint阶段】                                                      │
│  4. 原子性创建 - 暂停→创建→元数据写入→恢复，finally保证                │
│  5. 元数据绑定 - offset和bizSeq写入元数据文件，与SST一起打包            │
│  6. Checksum校验 - Checkpoint创建时计算，恢复时验证                     │
│                                                                         │
│  【恢复阶段】                                                            │
│  7. 幂等重放 - WAL和Kafka按bizSeq去重，重复执行结果一致                 │
│  8. 三层校验 - Count + Checksum + Sample，全部通过才算成功              │
│  9. 原子写入 - MySQL事务批量写入，Redis Pipeline批量写入                │
│  10. 最终校验 - RocksDB = MySQL = Redis，不一致立即回滚                 │
│                                                                         │
│  【故障处理】                                                            │
│  11. 自动回滚 - 任一步骤失败自动回滚到上一个有效Checkpoint              │
│  12. 完整追溯 - 恢复历史记录保存所有步骤和校验结果                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
✅ 快速恢复能力总结

┌─────────────────────────────────────────────────────────────────────────┐
│                          恢复性能优化                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  【本地Checkpoint】                                                      │
│  • 硬链接创建，秒级完成                                                 │
│  • 本地磁盘读取，100MB/s+                                               │
│  • 适合快速恢复（15分钟内数据）                                         │
│                                                                         │
│  【WAL加速】                                                             │
│  • Checkpoint后截断WAL，只保留增量                                      │
│  • WAL顺序读，速度极快                                                  │
│  • 将恢复时间从4分钟→1分钟（15分钟数据场景）                           │
│                                                                         │
│  【并行处理】                                                            │
│  • 多symbol并行恢复（受限于IO）                                         │
│  • MySQL批量写入（1000条/批次）                                         │
│  • Redis Pipeline批量写入                                               │
│                                                                         │
│  【压缩优化】                                                            │
│  • ZSTD高压缩比，减少网络传输                                           │
│  • 分级压缩，热数据不压缩保证速度                                       │
│                                                                         │
│  【预期性能】                                                            │
│  • 15分钟数据：1-2分钟恢复                                              │
│  • 1天数据：~10分钟恢复                                                 │
│  • 1周数据：~40分钟恢复                                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
