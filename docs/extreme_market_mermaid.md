# 极端行情完整交易链路 - Mermaid 流程图

> 本文档包含所有可在 https://mermaid.live/ 直接使用的流程图代码

---

## 1. 整体服务架构图

```mermaid
graph TB
    subgraph 接入层
        AG[API Gateway<br/>:8080]
    end

    subgraph 交易核心层
        OMS[OMS-Core<br/>:8081]
        HR[Hard-Risk-Core<br/>:8082]
        ME[Match-Engine-Core<br/>:8083]
        LED[Ledger-Core<br/>:8084]
    end

    subgraph 快照层
        AS[Account-Snapshot<br/>:8085]
        PS[Position-Snapshot<br/>:8086]
    end

    subgraph 价格计算层
        IP[Index-Price-Core<br/>:8097]
        MP[Mark-Price-Core<br/>:8098]
        MM[Margin-Mode-Core<br/>:8088]
    end

    subgraph 风险管理层
        LQ[Liquidation-Core<br/>:8089]
        ADL[ADL-Core<br/>:8090]
        INS[Insurance Fund]
    end

    subgraph 行情层
        MKT[Market-Price-Core<br/>:8095]
        PP[Public-Push-Core<br/>:8096]
        PR[Private-Push-Core<br/>:8091]
    end

    subgraph 客户端
        C[Client/WebSocket]
    end

    AG -->|下单| OMS
    OMS -->|风控检查| HR
    OMS -->|Kafka: order-event| ME
    ME -->|撮合| ME
    ME -->|成交结果| LED
    ME -->|Kafka: match-event| MKT
    
    LED -->|Kafka: trade-entry| AS
    LED -->|Kafka: trade-entry| PS
    LED -->|记账| INS
    
    IP -->|指数价格| MP
    MP -->|标记价格| MM
    MP -->|Kafka: mark-price| PP
    
    MM -->|保证金率扫描| LQ
    LQ -->|创建强平单| OMS
    LQ -->|穿仓赔付| INS
    INS -->|余额不足| ADL
    
    ADL -->|ADL减仓| OMS
    ADL -->|清结算| LED
    
    MKT -->|Kafka: market-events| PP
    OMS -->|私有推送| PR
    LED -->|私有推送| PR
    ADL -->|私有推送| PR
    AS -->|私有推送| PR
    PS -->|私有推送| PR
    
    PP -->|WebSocket| C
    PR -->|WebSocket| C
    
    style ME fill:#ff9999
    style LED fill:#99ccff
    style ADL fill:#ffcc99
    style PP fill:#99ff99
    style PR fill:#99ff99
```

---

## 2. 正常交易流程时序图

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant AG as API Gateway:8080
    participant OMS as OMS-Core:8081
    participant HR as Hard-Risk:8082
    participant ME as Match-Engine:8083
    participant MKT as Market-Price:8095
    participant PP as Public-Push:8096
    participant LED as Ledger:8084
    participant AS as Account-Snap:8085
    participant PS as Position-Snap:8086
    participant PR as Private-Push:8091

    C->>+AG: 提交卖单 100BTC @$50K
    AG->>+OMS: 转发订单
    OMS->>OMS: 创建订单并持久化
    OMS->>+HR: 风控检查/余额验证
    HR-->>-OMS: 通过
    OMS->>OMS: Kafka发布 order-event-BTCUSDT
    OMS-->>-AG: 返回订单ID
    AG-->>-C: 响应成功
    
    Note over OMS,ME: Kafka 消息传递
    ME->>+ME: 消费订单并撮合
    ME->>ME: 成交 100BTC @$49,933
    
    par 成交处理
        ME->>LED: Kafka: trade-event
        LED->>LED: 双录分录记账
        LED->>AS: Kafka: trade-entry
        LED->>PS: Kafka: trade-entry
        AS->>AS: 更新账户余额快照
        PS->>PS: 更新持仓快照
    and 行情生成
        ME->>MKT: Kafka: match-event
        MKT->>MKT: OrderBook深度计算
        MKT->>MKT: K线更新
        MKT->>MKT: Ticker统计
        MKT->>PP: Kafka: market-events
        PP->>C: WebSocket 公有推送<br/>(深度/K线/成交/Ticker)
    and 私有推送
        OMS->>PR: Kafka: order-state
        LED->>PR: 资金变动
        AS->>PR: 账户更新
        PS->>PR: 持仓更新
        PR->>C: WebSocket 私有推送<br/>(订单状态/资金/持仓)
    end
```

---

## 3. 价格计算与强平触发流程

```mermaid
sequenceDiagram
    autonumber
    participant ME as Match-Engine:8083
    participant IP as Index-Price:8097
    participant MP as Mark-Price:8098
    participant MM as Margin-Mode:8088
    participant LQ as Liquidation:8089
    participant OMS as OMS-Core:8081
    participant ME2 as Match-Engine:8083
    participant LED as Ledger:8084
    participant INS as Insurance Fund

    Note over ME: 价格持续下跌<br/>$50K → $45K (-10%)
    
    IP->>IP: 聚合外部交易所数据<br/>(Binance/OKX/Bybit)
    IP->>MP: 指数价格更新
    MP->>MP: 计算标记价格<br/>$49,933 (指数±基差)
    MP->>MM: Kafka: mark-price-update
    
    MM->>MM: 保证金率扫描
    Note right of MM: 用户B: 7.5% < 10%<br/>用户C: 9.8% < 10%
    
    MM->>LQ: Kafka: liquidation-trigger
    LQ->>LQ: 幂等检查(Redis)
    LQ->>LQ: 创建强平订单
    
    LQ->>OMS: 调用创建强平单API
    OMS->>ME2: Kafka: order-event<br/>(跳过风控)
    ME2->>ME2: 撮合强平单<br/>20BTC @$49,387
    
    alt 部分成交导致穿仓
        ME2->>LED: 成交事件
        LED->>LED: 计算穿仓 $18,000
        LED->>INS: 申请赔付
        INS->>INS: 赔付 $10,800 (60%)
        INS->>LED: 保险基金赔付记账
    else 保险基金耗尽
        INS->>LQ: 余额不足通知
        LQ->>LQ: 发布 liquidation-completed<br/>adlRequired=true
    end
```

---

## 4. ADL自动减仓完整流程

```mermaid
sequenceDiagram
    autonumber
    participant LQ as Liquidation:8089
    participant INS as Insurance Fund
    participant ADL as ADL-Core:8090
    participant OMS as OMS-Core:8081
    participant ME as Match-Engine:8083
    participant LED as Ledger:8084
    participant PS as Position-Snap:8086
    participant PR as Private-Push:8091
    participant PP as Public-Push:8096
    participant C as Client

    LQ->>INS: 申请赔付 $500K
    INS->>INS: 余额不足<br/>剩余 $182K
    INS-->>LQ: 拒绝赔付
    LQ->>ADL: Kafka: liquidation-completed<br/>adlRequired=true
    
    ADL->>ADL: 消费ADL事件
    ADL->>ADL: 查询ADL排名队列
    Note right of ADL: WHALE: ADL得分 15000 (Rank 1)<br/>用户D: ADL得分 8000 (Rank 2)<br/>用户E: ADL得分 5000 (Rank 3)
    
    ADL->>ADL: 计算分摊比例<br/>WHALE: 53.57% ($170K)
    
    loop ADL执行多个用户
        ADL->>OMS: 创建ADL减仓订单<br/>(WHALE 减仓4.06BTC @$42K)
        OMS->>ME: Kafka: order-event
        ME->>ME: 撮合ADL订单
        ME->>LED: 成交事件
        LED->>LED: 清结算记账
        LED->>PS: Kafka: trade-entry
        PS->>PS: 更新持仓快照
        PS->>PR: Kafka: position-change
        PR->>C: WebSocket: 私有推送<br/>ADL通知/持仓变动
    end
    
    ADL->>LED: Kafka: adl-completed
    LED->>PP: Kafka: adl-completed
    PP->>C: WebSocket: 公有推送<br/>ADL完成通知
    
    Note over ADL: 总计减仓: 7.57 BTC<br/>总计分摊: $318K
```

---

## 5. Kafka Topic 流向图

```mermaid
flowchart LR
    subgraph Producers[生产者]
        OMS[OMS-Core]
        ME[Match-Engine]
        LED[Ledger-Core]
        MKT[Market-Price]
        MM[Margin-Mode]
        LQ[Liquidation]
        ADL[ADL-Core]
        MP[Mark-Price]
        IP[Index-Price]
        PS[Position-Snap]
    end

    subgraph KafkaTopics[Kafka Topics]
        OE[order-event-{symbol}]
        TE[trade-event]
        ME2[match-event]
        MEV[market-events]
        OS[order-state-{symbol}]
        TRE[trade-entry-{symbol}]
        LT[liquidation-trigger]
        LC[liquidation-completed]
        AC[adl-completed]
        MPU[mark-price-update]
        IPU[index-price-update]
        PC[position-change]
    end

    subgraph Consumers[消费者]
        ME3[Match-Engine]
        LED2[Ledger-Core]
        MKT2[Market-Price]
        PP[Public-Push]
        OMS2[OMS-Core]
        AS[Account-Snap]
        PS2[Position-Snap]
        LQ2[Liquidation]
        ADL2[ADL-Core]
        MM2[Margin-Mode]
        MP2[Mark-Price]
        PR[Private-Push]
    end

    OMS --> OE --> ME3
    ME --> TE --> LED2
    ME --> ME2 --> MKT2
    ME --> OS --> OMS2
    MKT --> MEV --> PP
    LED --> TRE --> AS
    LED --> TRE --> PS2
    MM --> LT --> LQ2
    LQ --> LC --> ADL2
    LED --> LC --> INS[Insurance]
    ADL --> AC --> PP
    ADL --> AC --> PR
    MP --> MPU --> MM2
    MP --> MPU --> PP
    IP --> IPU --> MP2
    PS --> PC --> PR

    style OE fill:#ffcccc
    style TE fill:#ccffcc
    style MEV fill:#ccccff
    style LT fill:#ffffcc
    style LC fill:#ffcccc
    style AC fill:#ccffff
```

---

## 6. WebSocket 推送架构图

```mermaid
graph TB
    subgraph 数据源
        ME[Match-Engine:8083]
        MKT[Market-Price:8095]
        MP[Mark-Price:8098]
        IP[Index-Price:8097]
        OMS[OMS-Core:8081]
        LED[Ledger-Core:8084]
        ADL[ADL-Core:8090]
        PS[Position-Snap:8086]
        AS[Account-Snap:8085]
    end

    subgraph Kafka层
        KM[Kafka: market-events]
        KP[Kafka: private-push-{userId}]
        KO[Kafka: order-state]
        KL[Kafka: liquidation-events]
        KA[Kafka: adl-events]
    end

    subgraph 推送服务
        PP[Public-Push-Core:8096]
        PR[Private-Push-Core:8091]
    end

    subgraph 推送内容
        subgraph 公有推送
            P1[深度 depth.{symbol}]
            P2[K线 kline.{symbol}]
            P3[成交 trade.{symbol}]
            P4[Ticker ticker.{symbol}]
            P5[标记价格 markPrice.{symbol}]
            P6[指数价格 indexPrice.{symbol}]
            P7[系统通知 system]
        end

        subgraph 私有推送
            R1[订单更新 order.update]
            R2[持仓更新 position.update]
            R3[资金变动 balance.update]
            R4[强平通知 liquidation.notice]
            R5[ADL通知 adl.notice]
            R6[成交回报 execution.report]
        end
    end

    subgraph 客户端
        C1[交易者A]
        C2[交易者B]
        C3[交易者C]
        C4[...]
    end

    ME --> KM
    MKT --> KM
    MP --> KM
    IP --> KM
    KM --> PP
    
    OMS --> KO
    LED --> KP
    ADL --> KA
    LED --> KL
    PS --> KP
    AS --> KP
    
    KO --> PR
    KP --> PR
    KA --> PR
    KL --> PR
    
    PP --> P1
    PP --> P2
    PP --> P3
    PP --> P4
    PP --> P5
    PP --> P6
    PP --> P7
    
    PR --> R1
    PR --> R2
    PR --> R3
    PR --> R4
    PR --> R5
    PR --> R6
    
    P1 --> C1
    P1 --> C2
    P1 --> C3
    P1 --> C4
    R1 --> C1
    R2 --> C1
    R3 --> C1
    R4 --> C2
    R5 --> C3

    style PP fill:#99ff99
    style PR fill:#99ccff
```

---

## 7. 极端行情完整流程（综合版）

```mermaid
sequenceDiagram
    autonumber
    participant C as Client/WHALE
    participant AG as API Gateway
    participant OMS as OMS-Core
    participant HR as Hard-Risk
    participant ME as Match-Engine
    participant MKT as Market-Price
    participant PP as Public-Push
    participant IP as Index-Price
    participant MP as Mark-Price
    participant MM as Margin-Mode
    participant LQ as Liquidation
    participant LED as Ledger
    participant INS as Insurance Fund
    participant ADL as ADL-Core
    participant PS as Position-Snap
    participant PR as Private-Push

    rect rgb(230, 245, 255)
        Note over C,PR: 阶段1: 正常交易
        C->>AG: 卖单1 100BTC @$50K
        AG->>OMS: 创建订单
        OMS->>HR: 风控检查
        HR-->>OMS: 通过
        OMS->>ME: Kafka: order-event
        ME->>ME: 撮合 @$49,933
        ME->>LED: trade-event
        ME->>MKT: match-event
        MKT->>PP: market-events
        PP->>C: 公有推送
        LED->>PS: trade-entry
        LED->>LED: 记账
        OMS->>PR: order-state
        PR->>C: 私有推送
    end

    rect rgb(255, 245, 230)
        Note over C,PR: 阶段2: 价格下跌触发强平
        IP->>IP: 聚合外部数据
        IP->>MP: 指数价格
        MP->>MM: 标记价格
        MM->>MM: 保证金率扫描
        Note right of MM: 发现用户B/C<br/>保证金率<10%
        MM->>LQ: liquidation-trigger
    end

    rect rgb(255, 230, 230)
        Note over C,PR: 阶段3: 强平处理
        LQ->>LQ: 创建强平单
        LQ->>OMS: 调用创建强平单
        OMS->>ME: order-event(跳过风控)
        ME->>ME: 撮合强平单
        ME->>LED: 成交事件
        LED->>INS: 申请赔付$18K
        INS->>LED: 赔付$10.8K
    end

    rect rgb(255, 230, 245)
        Note over C,PR: 阶段4: 保险基金耗尽触发ADL
        LQ->>INS: 申请赔付$500K
        INS-->>LQ: 余额不足(剩$182K)
        LQ->>ADL: liquidation-completed<br/>adlRequired=true
    end

    rect rgb(245, 230, 255)
        Note over C,PR: 阶段5: ADL执行
        ADL->>ADL: 查询ADL排名<br/>WHALE Rank1
        ADL->>ADL: 计算分摊比例
        ADL->>OMS: 创建ADL减仓单
        OMS->>ME: order-event
        ME->>ME: 撮合ADL单
        ME->>LED: 成交事件
        LED->>PS: trade-entry
        PS->>PR: position-change
        PR->>C: ADL通知
        ADL->>PP: adl-completed
        PP->>C: 系统通知
    end

    Note over C,PR: 总耗时: 2900ms<br/>总计减仓: 7.57 BTC<br/>总计分摊: $318K
```

---

## 8. 服务依赖关系图（层级版）

```mermaid
graph TB
    subgraph 第一层_接入层
        AG[API Gateway:8080]
    end

    subgraph 第二层_业务层
        OMS[OMS-Core:8081]
        HR[Hard-Risk:8082]
    end

    subgraph 第三层_核心引擎
        ME[Match-Engine:8083]
    end

    subgraph 第四层_账本与价格
        LED[Ledger-Core:8084]
        IP[Index-Price:8097]
        MKT[Market-Price:8095]
    end

    subgraph 第五层_派生服务
        MP[Mark-Price:8098]
        AS[Account-Snap:8085]
        PS[Position-Snap:8086]
    end

    subgraph 第六层_风险管理
        MM[Margin-Mode:8088]
        LQ[Liquidation:8089]
        INS[Insurance Fund]
        ADL[ADL-Core:8090]
    end

    subgraph 第七层_推送层
        PP[Public-Push:8096]
        PR[Private-Push:8091]
    end

    AG --> OMS
    OMS --> HR
    OMS --> ME
    HR -.->|风控结果| OMS
    
    ME --> LED
    ME --> MKT
    ME -.->|撮合结果| OMS
    
    LED --> AS
    LED --> PS
    LED --> INS
    
    IP --> MP
    MKT --> PP
    MP --> MM
    MP --> PP
    
    AS --> PR
    PS --> PR
    OMS --> PR
    
    MM --> LQ
    LQ --> OMS
    LQ --> INS
    INS -.->|耗尽| ADL
    ADL --> OMS
    ADL --> LED
    ADL --> PP
    ADL --> PR

    style ME fill:#ff9999,stroke:#ff0000,stroke-width:3px
    style LED fill:#99ccff,stroke:#0066cc,stroke-width:3px
    style ADL fill:#ffcc99,stroke:#ff6600,stroke-width:3px
    style PP fill:#99ff99,stroke:#00cc00,stroke-width:2px
    style PR fill:#99ff99,stroke:#00cc00,stroke-width:2px
```

---

## 9. 数据流状态机图

```mermaid
stateDiagram-v2
    [*] --> 下单: 用户提交订单
    
    下单 --> 风控检查: OMS接收
    风控检查 --> 拒绝: 余额不足/风控失败
    风控检查 --> 待撮合: 风控通过
    
    待撮合 --> 撮合中: Kafka投递
    撮合中 --> 部分成交: 订单匹配
    撮合中 --> 完全成交: 订单完全匹配
    撮合中 --> 撤单: 用户取消
    
    部分成交 --> 撮合中: 继续等待
    部分成交 --> 完全成交: 剩余成交
    
    完全成交 --> 记账: Ledger处理
    记账 --> 快照更新: 异步更新
    记账 --> 推送通知: WebSocket推送
    
    快照更新 --> [*]: 完成
    推送通知 --> [*]: 完成
    拒绝 --> [*]: 结束
    撤单 --> [*]: 结束
    
    完全成交 --> 价格更新: 触发MarkPrice
    价格更新 --> 保证金检查: MarginMode扫描
    保证金检查 --> 正常: 保证金充足
    保证金检查 --> 强平触发: 保证金率<维持率
    
    强平触发 --> 强平中: Liquidation处理
    强平中 --> 强平完成: 强平单撮合
    强平中 --> 穿仓: 流动性不足
    
    穿仓 --> 保险基金赔付: 申请赔付
    保险基金赔付 --> 强平完成: 赔付成功
    保险基金赔付 --> ADL触发: 基金耗尽
    
    ADL触发 --> ADL执行: ADL-Core处理
    ADL执行 --> 减仓完成: 对手方减仓
    减仓完成 --> 记账: 清结算
    减仓完成 --> [*]: ADL完成
```

---

## 10. 性能指标监控图

```mermaid
graph LR
    subgraph 延迟监控
        L1[API Gateway: 2ms<br/>目标<50ms ✅]
        L2[OMS处理: 10ms<br/>目标<30ms ✅]
        L3[硬风控: 2ms<br/>目标<3ms ✅]
        L4[撮合: <1ms<br/>目标<1ms ✅]
        L5[账本记账: 5ms<br/>目标<10ms ✅]
        L6[行情生成: 4ms<br/>目标<10ms ✅]
        L7[强平处理: 30ms<br/>目标<50ms ✅]
        L8[ADL执行: 400ms<br/>目标<3s ✅]
    end

    subgraph 系统指标
        S1[可用性: 99.99% ✅]
        S2[数据一致性: 100% ✅]
        S3[资金损失率: 0% ✅]
        S4[WebSocket并发: 100K+ ✅]
    end

    subgraph 极端行情结果
        R1[大户砸盘: 200 BTC]
        R2[强平触发: 175用户]
        R3[保险基金消耗: $3.3M]
        R4[ADL分摊: $318K]
        R5[最终损失: $0]
        R6[总耗时: 2900ms]
    end

    L1 --> L2 --> L3 --> L4 --> L5
    L6 --> L7 --> L8
    
    style L1 fill:#ccffcc
    style L2 fill:#ccffcc
    style L3 fill:#ccffcc
    style L4 fill:#ccffcc
    style L5 fill:#ccffcc
    style L6 fill:#ccffcc
    style L7 fill:#ccffcc
    style L8 fill:#ccffcc
    style S1 fill:#ccffcc
    style S2 fill:#ccffcc
    style S3 fill:#ccffcc
    style S4 fill:#ccffcc
    style R5 fill:#ccffcc
```

---

## 使用说明

1. 打开 https://mermaid.live/
2. 复制上述任意一个代码块（包括 ```mermaid 标记）
3. 粘贴到左侧编辑器
4. 右侧会自动渲染流程图
5. 可以导出为 PNG/SVG/PDF

## 图例说明

| 颜色 | 含义 |
|------|------|
| 🟥 红色系 | 撮合引擎 / 强平 / ADL 核心服务 |
| 🟦 蓝色系 | 账本 / 私有推送 |
| 🟩 绿色系 | 公有推送 / 正常状态 |
| 🟨 黄色系 | 风险管理 / 警告状态 |
| 🟪 紫色系 | 快照服务 |
