# Startup Acceptance Summary (Head 50 Logs)

- Time: 2026-03-01 12:30:35
- Mode: low-memory java -jar direct start (servicectl restart)
- Scope: key closure services from 1.1

| Service | Port | Head50 start marker | Head50 ready marker | Warn/Error in head50 | Note |
|---|---:|---|---|---|---|
| match-engine-core | 8083 | 2026-03-01 12:28:01.620 [main] INFO com.exchange.match.MatchEngineApplication - Starting MatchEngineApplication using Ja | 2026-03-01 12:28:02.609 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8083 (http) wi | W=2, E=1 | head50-ready |
| ledger-core | 8084 | 2026-03-01 12:28:04.028 [main] INFO com.exchange.ledger.LedgerApplication - Starting LedgerApplication using Java 17.0.1 | 2026-03-01 12:28:05.623 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8084 (http) wi | W=9, E=1 | head50-ready |
| oms-core | 8081 | 2026-03-01 12:28:07 [main] INFO com.exchange.oms.OmsApplication - Starting OmsApplication using Java 17.0.17 with PID 17 |  | W=21, E=0 | ready-outside-head50:52:2026-03-01 12:28:09 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8081 (http) wit |
| snapshot-account-core | 8085 | 2026-03-01 12:28:10.745 [main] INFO com.exchange.snapshot.SnapshotApplication - Starting SnapshotApplication using Java  | 2026-03-01 12:28:12.524 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8085 (http) wi | W=2, E=1 | head50-ready |
| position-snapshot-core | 8086 | 2026-03-01 12:28:15.253 [main] INFO com.exchange.position.PositionApplication - Starting PositionApplication using Java  | 2026-03-01 12:28:17.026 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8086 (http) wi | W=2, E=1 | head50-ready |
| market-price-core | 8095 | 2026-03-01 12:28:19.280 [main] INFO com.exchange.market.MarketPriceApplication - Starting MarketPriceApplication using J | 2026-03-01 12:28:20.537 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8095 (http) wi | W=5, E=1 | head50-ready |
| index-price-core | 8093 | 2026-03-01 12:28:40.517 [main] INFO com.exchange.index.IndexPriceApplication - Starting IndexPriceApplication using Java | 2026-03-01 12:28:42.255 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8093 (http) wi | W=2, E=2 | head50-ready |
| mark-price-core | 8094 | 2026-03-01 12:28:45.399 [main] INFO com.exchange.markprice.MarkPriceApplication - Starting MarkPriceApplication using Ja | 2026-03-01 12:28:47.296 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8094 (http) wi | W=2, E=1 | head50-ready |
| margin-mode-core | 8090 | 2026-03-01 12:29:01.910 [main] INFO com.exchange.margin.MarginModeApplication - Starting MarginModeApplication using Jav | 2026-03-01 12:29:04.318 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8090 (http) wi | W=2, E=1 | head50-ready |
| liquidation-core | 8102 | 2026-03-01 12:28:50.003 [main] INFO com.exchange.liquidation.LiquidationApplication - Starting LiquidationApplication us |  | W=22, E=0 | ready-outside-head50:51:2026-03-01 12:28:52.024 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8102 (http) |
| adl-core | 8103 | 2026-03-01 12:28:54.175 [main] INFO com.exchange.adl.AdlApplication - Starting AdlApplication using Java 17.0.17 with PI | 2026-03-01 12:28:57.055 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8103 (http) wi | W=2, E=1 | head50-ready |
| public-push-core | 8096 | 2026-03-01 12:28:22.580 [main] INFO com.exchange.push.PublicPushApplication - Starting PublicPushApplication using Java  | 2026-03-01 12:28:24.737 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8096 (http) wi | W=2, E=0 | head50-ready |
| private-push-core | 8097 | 2026-03-01 12:28:27.719 [main] INFO com.exchange.privatepush.PrivatePushApplication - Starting PrivatePushApplication us | 2026-03-01 12:28:30.259 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8097 (http) wi | W=2, E=1 | head50-ready |
| api-gateway | 8082 | 2026-03-01 12:28:33.085 [main] INFO com.exchange.gateway.ApiGatewayApplication - Starting ApiGatewayApplication using Ja |  | W=24, E=1 | ready-outside-head50:63:2026-03-01 12:28:35.417 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8082 (http) |
| user-core | 8100 | 2026-03-01 12:28:37.024 [main] INFO com.exchange.user.UserCoreApplication - Starting UserCoreApplication using Java 17.0 |  | W=20, E=0 | ready-outside-head50:58:2026-03-01 12:28:38.900 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8100 (http) |
| binance-data-source | 8105 | 2026-03-01 12:29:05.498 [main] INFO com.exchange.binance.BinanceDataSourceApplication - Starting BinanceDataSourceApplic |  | W=0, E=0 | ready-outside-head50:102:2026-03-01 12:29:07.457 [main] INFO o.s.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port 8105 (http |

## Failure Root Cause & Fix

### private-push-core
- Historical failure root cause: non-executable fat jar (startup mode mismatch / manifest issues in previous run context).
- Fix applied: ensure spring-boot repackage and run by java -jar under servicectl; latest run head50 confirms startup + Tomcat 8097 ready.

### snapshot-account-core
- Historical failure root cause: environment permission issue to Nacos gRPC/auth in non-elevated runs (Operation not permitted), causing startup probe failure.
- Fix applied: run closure restart in elevated context and keep low-memory java -jar unified startup; latest run head50 confirms startup + Tomcat 8085 ready.

## Section 3 Acceptance

- Strict run: PASS 13/13
- Report MD: test-reports/acceptance-suite-20260301-123035.md
- Report JSON: test-reports/acceptance-suite-20260301-123035.json
