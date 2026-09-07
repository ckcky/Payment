# Plan: 022 全链路自动化测试体系

> 承载目标：把「在 demo 控制台发起支付/退款 → 观察各系统状态与 DB 数据」变成可重复、可报告、可进 CI 的自动化测试；
> 重点覆盖四件事：**退款功能正常 / 超退能拦截 / 对账准确 / 单号记对**（[spec.md](spec.md) US1~US5）。
> 形态按负责人拍板：**新建 Java Maven 模块（黑盒 HTTP + JDBC）**、默认复用本地栈、**不引 SCC/Pact**、**PR 快跑 + nightly 全量**。

---

## 0. 事实基线（实施时直接引用，勿重复探查）

| 项 | 事实 |
|---|---|
| 服务与端口 | merchant 8081 / catalog 8082 / order 8083 / payment 8084 / fulfillment 8086 / entitlement 8087 / reconciliation 8088 / settlement 8089 / ledger 8090 / mock-channel-web 8091 |
| 基础设施 | MySQL 3306（`root/root`，同实例多 schema：order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/catalog/merchant）、Redis 6379、Nacos 8848、Prometheus 9090、Grafana 3000 |
| 起栈 | `deployment/start-all.sh`（compose + 9 个 spring-boot 进程）；演示入口 `deployment/demo/start-demo.sh` |
| 行级快照 API | `GET /demo/trace?orderId=ORxxx`（8091）跨 9 库 14 表，返回 `sections[].{system,table,label,sql,rows,error}` |
| 透传代理 | `GET|POST /proxy/{service}/**`（8091）同源透传，**4xx/5xx 原样回传** |
| 渠道回调 | `/mock-channel/callback`（支付）、`/mock-channel/refund-callback`（退款，可指定 `status`/`channelReference`） |
| 对账 | 会计四核对 `audit.html`（**必须 LIVE 模式**）；渠道对账 CSV 在 `reconciliation-service/src/main/resources/fixtures/channel-statements/` |
| 现有场景开关 | `PAYMENT_MOCK_SCENARIO`（`deployment/demo/restart-payment.sh BUSINESS_UNKNOWN`），**整进程粒度**，需扩为请求级 |

---

## 1. 总体设计

### 1.1 五层测试钻石（本次新建 L3~L5）

```
L1 单元        纯领域逻辑（金额/状态机/单号）      JUnit5 + AssertJ       PR        [现有]
L2 单服务集成   单服务 + H2 + fake 出站（450 用例）  @SpringBootTest        PR        [现有]
L3 API 快照     内部 API schema 不漂移              自写快照断言            PR        [新建]
L4 全链路 E2E   3~5 条核心链路 + 跨库不变量          deployment/e2e-tests   nightly   [新建]
L5 对账/故障注入 差异检出、并发幂等、回调异常、门禁    同一模块 + 确定性 mock  nightly   [新建]
```

> 依据：本项目服务间走 `common-dto` 共享 DTO + Feign，编译期已挡住大半漂移；跨服务「最终一致性」契约测不了，必须靠少量 E2E + 不变量断言兜底。

### 1.2 为什么选 Java Maven 模块（三个候选的取舍）

| 候选 | 优点 | 否决理由 |
|---|---|---|
| **① 新建 `deployment/e2e-tests`（推荐）** | JUnit5 报告天然进 CI；IDE 可单条重跑；JDBC 跨库聚合断言能力强；与 `mvnw` 体系一体 | 需新起模块（一次性成本） |
| ② bash 脚本测试化（改 `lib.sh` 加用例注册/JUnit XML） | 改动最小，复用 5 个 scenario | 无类型安全；跨库聚合 SQL 与报告生成写起来痛苦；难维护、难扩展 |
| ③ Node/TS（Vitest + mysql2） | 启动快、写得轻 | 与 Maven/CI 体系割裂；团队主力是 Java；报告集成需额外转换 |

### 1.3 目录结构

```
deployment/e2e-tests/
  pom.xml                       # 不依赖业务模块（黑盒：HTTP + JDBC 即可）
  run.sh                        # 一键：检查栈就绪 → 跑测试 → 出报告
  README.md                     # 起栈/跑单条/看报告/dump 说明
  src/test/java/com/payment/e2e/
    support/
      Env.java                  # 端口与 DB 连接；-De2e.env=local(默认) | ci
      Api.java                  # HTTP 封装（下单/建支付单/回调/退款/状态/对账/结算）
      Db.java                   # 多 schema JDBC 探针（SimpleJdbc 即可，无需 Spring）
      Await.java                # Awaitility 轮询（禁 sleep）
      Trace.java                # /demo/trace 行级快照客户端
      Invariants.java           # 通用断言原语（§3）
      Dump.java                 # 失败落盘 target/e2e-dump/<case>/
      Scenario.java             # 确定性故障注入触发构造（§5）
    refund/RefundChainE2ETest.java
    refund/OverRefundGuardE2ETest.java
    recon/ReconciliationAccuracyE2ETest.java
    numbering/BusinessNoChainE2ETest.java
    reliability/IdempotencyAndCallbackE2ETest.java   # P1
    inventory/SeckillRestockE2ETest.java             # P1
    contract/InternalApiSnapshotTest.java            # P1（L3）
  src/test/resources/
    e2e-local.properties / e2e-ci.properties
    api-snapshots/*.json                             # L3 快照基线
```

**依赖（最轻集）**：`junit-jupiter`、`assertj-core`、`awaitility`、`jackson-databind`、`mysql-connector-j`、`slf4j-simple`。
HTTP 用 JDK 11+ `java.net.http.HttpClient`（**不引 RestAssured**，够用且轻）；`testcontainers` **仅 `ci` profile**。

### 1.4 运行方式

```bash
# 本地（推荐，最轻）：复用已起栈 + 本机 MySQL 3306
bash deployment/start-all.sh
bash deployment/e2e-tests/run.sh          # = mvn -pl deployment/e2e-tests test -De2e.env=local

# CI / 干净跑：Testcontainers 起 MySQL + Redis
mvn -pl deployment/e2e-tests test -De2e.env=ci

# 单条重跑
mvn -pl deployment/e2e-tests test -Dtest=OverRefundGuardE2ETest
```

**数据隔离**：每个用例使用唯一业务号前缀 `e2e-{runId}-{case}`（如 `e2e-20260907T2210-overrefund`），
所有 DB 断言**按单号过滤**（`WHERE order_no LIKE 'e2e-...%'`），互不干扰，**无需每次 truncate**；
如需全清可前置 `deployment/demo/reset.sh`。

---

## 2. 全链路串法（9 步，全部可用控制台现有能力驱动）

```
0. reset.sh 播种 → 等各服务 /actuator/health 就绪
1. 下单（带 Idempotency-Key）            → 断言 201 + OR 前缀
2. 选渠道建支付单                        → 断言 PM 前缀 + payUrl 非空
3. 渠道回调 SUCCESS + channelReference    → 可重复发一次验幂等
4. 断言后效：履约已建、权益恰好 1 份 AVAILABLE、/internal/ledger/balance balanced、
   分录按 PAYMENT/{paymentNo} 可追溯     → 记 trace 快照 S1
5. 发起部分退款（拿 TXRF/PMRF）→ 轮询终态
6. 第二笔部分退款 + 第三笔超退           → 断言第三笔 409（超退拦截）
7. 渠道对账跑批（差异 → resolve → close 门禁 → 结算批）
8. 会计四核对 LIVE（CLEAN 期望 0 差异 / FAULT 期望 8 类齐全）
   → 挂账 → 调账 → recheck → close → 试算平衡 Σ=0 且 SUSPENSE 归零
9. 每步结束记 /demo/trace 快照 → 最后做前后快照 diff（新增行、状态迁移、金额守恒）
```

第 4/5/8 步的**行级**断言走 `/demo/trace`（零成本）；聚合不变量走 `Db`（§3）。

---

## 3. DB 断言原语库（`Invariants.java`）

按 schema 注册 JDBC 源（order / payment / fulfillment / entitlement / ledger / settlement / reconciliation / catalog / merchant），
沉淀下列可复用原语；用例只写业务语义。

| 原语 | 断言口径（关键 SQL 示意） |
|---|---|
| `refundNotExceedPaid(orderNo)` | `payment.refunds` 累计（SUCCEEDED 计实退、PROCESSING/UNKNOWN 按申请额保守计）≤ `payment.payments.amount_minor`；且与 `order.transactions.refunded_minor` 相等 |
| `businessNoChain(orderNo, txrf, pmrf)` | 前缀 + 雪花长度规则；`transaction_refunds.payment_refund_no == refunds.refund_no`；`refunds.transaction_refund_no == transaction_refunds.refund_no`；`transactions.payment_no` == 生效 `payments.payment_no`；`payment_attempts.payment_no` 归属正确且退款 attempt 的 `attempt_type='REFUND'` |
| `ledgerBalanced(refNo)` | 按业务单号分组 `SUM(debit_minor) == SUM(credit_minor)`；再跑一次全局 `SUM(D)==SUM(C)`；分录 append-only（`postings` 无 UPDATE/DELETE —— 用行版本号/审计列检查） |
| `orderStatus(orderNo, expected)` | `order.orders.status` 与 `order.transactions.status` 一致且在预期集合内 |
| `entitlementRevoked(orderNo)` / `fulfillmentTerminated(orderNo)` | `entitlements.status` 全部非 AVAILABLE；`fulfillments.status` 为终止态（spec 018 后按 item 多条，逐条断言） |
| `reconDiffDetected(batchNo, expected)` | `reconciliation_differences` 中注入条目全部出现，且 `kind`/`severity` 分类与预期一致（检出率 100%） |
| `settlementGated(batchNo)` | 存在未收口差异时，结算接口返回拒绝（4xx） |
| `stockRestoredForSeckill(orderNo)` | 秒杀 SKU 退款后配额回补；普通 SKU 库存不变（对照 `catalog` 库快照） |
| `idempotentReplay(keyOrTxrf, rowCountSql)` | 同号重放前后：返回体一致、目标表行数不变、金额不变 |
| `noOrphanRows(runPrefix)` | 本轮所有单号在上下游库均可追溯（无孤儿单 / 无悬空引用） |

**失败时** `Dump.java` 自动写 `target/e2e-dump/<case>/`：`{step}.response.json`、`trace-<orderNo>.json`、相关表 `SELECT *` 快照、`invariants.log`。

---

## 4. 用例矩阵

### P0（阶段 1 必做 —— 负责人点名的四件事）

| 关注点 | 用例 | 关键断言 |
|---|---|---|
| 退款功能正常 | `RefundChainE2ETest#partialRefundChain` / `#fullRefundChain` | AC1.1~AC1.5：6 库状态与金额收敛、订单 PARTIALLY_REFUNDED/REFUNDED、权益撤销、履约终止、账本冲正可追溯 |
| 超退拦截 | `OverRefundGuardE2ETest#singleOverPaid` / `#cumulativeOverRefund` / `#concurrentRefundRace` | AC2.1~AC2.3：409 + DB 侧 `refundNotExceedPaid` 恒真（并发下验证 intake 锁有效） |
| 对账准确 | `ReconciliationAccuracyE2ETest#auditFourCheckClean` / `#auditFourCheckFaultInjection` / `#channelStatementDiffInjection` / `#settlementGate` | AC3.1~AC3.5：CLEAN 0 差异、F2~F9 八类全检出且分类正确、调账后清零、close 门禁、试算平衡 |
| 单号正确 | `BusinessNoChainE2ETest#prefixAndUniqueness` / `#twoLayerRefundNoCrossLink` / `#traceability` | AC4.1~AC4.5：前缀、雪花唯一、双号互记、attempt 归属、无孤儿单 |

### P1（阶段 2）

- 幂等重放：同 `Idempotency-Key` 下单、同 TXRF 退款重放、渠道回调重复 3 次（AC5.1~AC5.3）
- 回调异常：回调丢失 → UNKNOWN → `resolve` 收敛后**后处理不丢**（记账/权益/订单状态补齐）；回调乱序不回退终态（AC5.4~AC5.5）
- 秒杀退款库存回补；普通商品不回补（AC5.6）
- 结算门禁（对账差异未处理 → 不允许结算）
- **L3 内部 API schema 快照**（字段集合 + 类型），防接口漂移

### P2（阶段 3）

- 账本全局不变量（全库 `SUM(D)==SUM(C)`、append-only 全量校验）
- 全链路 traceId 串联校验（复用 spec 021 `trace-grep.sh`）
- 可选：jqwik 金额属性测试（退款累计、四舍五入、币种一致性）
- 冒烟级性能基线（单笔链路 p95；重压测仍用 Node 脚本）

---

## 5. 确定性 mock / 故障注入（扩展现有机制）

现状 `PAYMENT_MOCK_SCENARIO`（`deployment/demo/restart-payment.sh BUSINESS_UNKNOWN`）是**整进程场景注入**，粒度太粗、跑异常路径要重启服务。
建议扩为「按请求特征确定性触发」（Stripe 式：固定卡号/金额触发指定结果）：

| 触发约定（建议，实施时可微调字段） | 效果 |
|---|---|
| `PAYMENT_MOCK_SCENARIO=SUCCESS\|FAILED\|UNKNOWN\|TIMEOUT` | 基线场景（**保留现有**行为） |
| `amount_minor % 100 == 11` | 渠道**超时**（无响应） |
| `amount_minor % 100 == 12` | 渠道受理后**不发回调**（回调丢失 → UNKNOWN） |
| `amount_minor % 100 == 13` | 回调**重复**投递 3 次 |
| `amount_minor % 100 == 14` | 回调**乱序**（SUCCESS 先到、PROCESSING 后到） |
| `remark` 含 `E2E-FAIL` | 渠道明确失败 |

> 阶段 1 若改动成本高，可先用现有 `SCENARIO` + 手工补回调（`/mock-channel/refund-callback`）过渡，阶段 2 再落地请求级触发。
> **禁止**用 sleep/概率制造异常——E2E 必须确定性可复现。

---

## 6. CI 门禁（PR 快跑 + nightly 全量）

**`verify.yml`（PR，每次，分钟级）**：现状 `./mvnw -B verify` + 新增 **L3 API 快照** + ArchUnit 模块。

**新增 `e2e.yml`（nightly + workflow_dispatch + release 前强制）**：

1. `services:` 起 MySQL 8 / Redis 7（GH Actions 原生 service 容器，比 compose 轻）；Nacos 沿用 compose（若过重再退到 `SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false` + Feign 直连 URL —— **实施时验证**）
2. `./mvnw -q install -DskipTests` → 后台起 9 个服务 → 轮询 `/actuator/health` 就绪
3. `mvn -pl deployment/e2e-tests test -De2e.env=ci`
4. 上传 `target/surefire-reports/**` 与 `target/e2e-dump/**` 为构建产物
5. **flaky 策略**：环境就绪用轮询；用例本身**不做自动重试**（重试会掩盖真问题），失败看 dump 定位；连续 flaky 的用例降级为 `@Disabled` 并登记待办

---

## 7. 落地路线图

| 阶段 | 交付物 | 复用 / 依赖 |
|---|---|---|
| **阶段 1（骨架 + P0）** | `deployment/e2e-tests` 模块（Env/Api/Db/Await/Trace/Invariants/Dump）+ 4 组 P0 用例 + `run.sh` + README | 复用本地栈、`/demo/trace`、audit F1~F9 fixture；bash 演示脚本保留 |
| **阶段 2（异常 + 快照）** | P1 用例、故障注入触发约定（§5）、L3 API 快照、秒杀回补、结算门禁 | 需扩展 mock-channel 请求级触发 |
| **阶段 3（CI + 深度）** | `e2e.yml` nightly、Testcontainers `ci` profile、账本全局不变量、可选 jqwik | GH Actions 起栈验证 |

工作量粗估：阶段 1 ≈ 一个 spec 的规模（模块 + 断言库 + 4 组用例）；阶段 2 与故障注入改造相当；阶段 3 偏工程配置。

---

## 8. 风险与取舍

| 风险 | 缓解 |
|---|---|
| E2E 慢 / 脆（10 个服务） | 默认复用本地栈；E2E 不进 PR；Awaitility 轮询代替 sleep；失败自动 dump |
| 数据污染 | 每用例唯一业务号前缀 + 断言按单号过滤 + 可选 `reset.sh` 全清 |
| 与演示脚本重复 | 阶段 1 后评估：E2E 稳定则 `run-all.sh` 改为调 E2E 模块，演示只保留交互页面 |
| CI 起 Nacos/9 服务复杂 | 先「跟本地一致」的 compose 方案；过重再退到关注册中心 + Feign 直连 |
| 断言库沦为一次性代码 | 原语集中在 `Invariants.java`，用例只写业务语义，新增用例成本趋近于 0 |
| 三个假绿陷阱（T1~T3） | 见 [spec.md §1.3](spec.md#-三个会骗人的点断言设计的硬约束)：禁断言验签、对账必走 LIVE、对账拆真实一致性/差异检出两套断言 |

---

## 9. 测试方案（本 spec 自身的验收方式）

1. **测试有效性验证（SC-002/SC-003）**：故意注入缺陷（去掉退款累计校验 / 不回填 `payment_refund_no`），对应用例必须变红 —— 证明断言真的在保护。
2. **分层门禁**：PR 跑 L1~L3；nightly 跑 L4~L5；release 前强制全量。
3. **回归底线**：既有 450 个单服务测试不受影响（SC-007）。
