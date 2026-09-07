# Feature Specification: 全链路自动化测试体系——可重复、可报告、可进 CI 的端到端验证

**Feature Branch**: `022-full-chain-automated-testing`

**Created**: 2026-09-07

**Status**: ✅ Accepted → **待实施**（2026-09-07 负责人逐项拍板，见 [ADR-0069](../../adr/0030-end-to-end-automated-testing.md)；本次仅落地文档，代码任务见 [tasks.md](tasks.md) 批次 B~E）

**Input**: 负责人 2026-09-07 测试体系讨论（原文归纳）：

> 「我想给这个项目加上自动化测试，最好是完整链路的，比如说在 demo 控制台发起的支付退款，观察各个系统的状态，还有 db 数据检查啥的。发起退款，看看功能是不是正常，超退能不能拦截，对账准不准确，单号有没有记错啥的。看看业内先进水平是如何设计的。」
> 「你先去 demo 演示台和对账的演示台看看有什么功能，怎么样串起来全链路的测试，需要关注的点是什么，如何验证。」

> 追加拍板（2026-09-07 第二轮，逐项选择）：①E2E 形态用**新建 Java Maven 模块**（主流 + 简单）；②环境**默认复用本地栈**，Testcontainers 仅作 `ci` profile；③**不引入 Spring Cloud Contract / Pact**，改做成本极低的内部 API schema 快照；④CI 采用 **PR 快跑 + nightly 全量**。

---

## 1. 当前代码现实（已核实，禁止按绿地项目理解）

### 1.1 现有测试资产

| 层 | 现状 | 位置 |
|---|---|---|
| 单服务集成测试 | ~100 个测试类 / ~450 个用例；`@SpringBootTest` + H2(`MODE=MySQL`) + `src/test/resources/schema.sql`；出站端口用手写 fake | 各服务 `src/test`；`payment-service/.../support/PaymentTestStack.java`（`RecordingOrderGateway`）、`order-service/.../scenario/SuccessfulPurchaseScenarioTest.java`（`FakeCatalogClient`/`FakePaymentGateway`） |
| 不变量 / 状态机测试 | 退款金额不变量、订单不变量、账本幂等、余额校验、来源可追溯 | `RefundAmountInvariantTest`、`OrderInvariantTest`、`LedgerIdempotencyTest`、`BalanceCheckerTest`、`SourceTraceabilityTest` |
| 架构测试 | ArchUnit 服务边界校验（独立 Maven 模块） | `deployment/architecture-tests/.../ServiceBoundaryTest.java` |
| bash 端到端演示 | 5 个场景脚本 + `lib.sh`（`http`/`assert_eq`/`assert_status`/`wait_until`/`jget`）+ `run-all.sh` + `reset.sh`/`seed.sh` | `deployment/demo/scenario-{happy-path,refund,payment-unknown,reconciliation,audit}.sh` |
| Node 校验 / 压测 | 并发幂等校验、支付-退款链路校验、审计压测 | `deployment/performance/order-idempotency-verify.js`、`order-payment-refund-*.js`、`audit-loadtest.js`、`run-stress.sh` |
| CI | 只跑 `./mvnw -B verify`（单服务测试） | `.github/workflows/verify.yml` |

### 1.2 缺口（G1~G6，本 spec 要补的）

| # | 缺口 | 代码/事实证据 | 影响 |
|---|---|---|---|
| **G1** | **E2E 是「演示脚本」不是「测试」** | `deployment/demo/*.sh` 依赖 `curl` + `assert_eq`；失败即 `exit 1` | 无标准报告（JUnit XML）、无失败诊断产物、不在 CI、不能单条重跑、无历史趋势 |
| **G2** | **无直接 DB 断言** | 断言只查服务 API 与 `/demo/trace` | 做不了跨库一致性（order 库 vs payment 库 vs ledger 分录）与全局聚合不变量 |
| **G3** | **无对账准确性测试** | `scenario-reconciliation.sh` 只跑通流程 | 没有「注入差异 → 断言 100% 检出且分类正确」的能力，对账回归只能靠人眼 |
| **G4** | **无系统性单号链路校验** | 仅各服务内零散断言 | OR/TR/PM/PA/TXRF/PMRF 六类单号的前缀、雪花唯一性、跨库互记与可追溯性无一处集中验证 |
| **G5** | **异常路径覆盖薄** | 只有 `scenario-payment-unknown.sh` 一个场景 | 缺渠道超时、回调重复/乱序/丢失、并发退款、部分失败补偿 |
| **G6** | **环境不可在 CI 复现** | 需 docker compose + 9 个 JVM 进程 + Nacos | E2E 只能人工本地跑，回归保护随时间腐化 |

### 1.3 两个控制台的功能面（驱动面 / 观察面，已实地核实）

#### ① 演示控制台 `http://localhost:8091/demo.html`

| 区块 | 可驱动的动作 | 走的接口 |
|---|---|---|
| ① 商品与下单 | 拉 SKU → 下单（前端 `crypto.randomUUID()` 生成 `Idempotency-Key`）→ 自动「选渠道建支付单」→ 拿 `payUrl` 开收银台 | `/proxy/catalog/skus`、`/proxy/order/orders`、`/proxy/order/orders/{no}/payments` |
| ② 状态面板 | 2s 轮询订单/支付/履约/权益四个状态（spec 018 后履约按明细多条，`join(',')` 展示） | `/proxy/{order,payment,fulfillment,entitlement}/...` |
| ③ 退款（spec 019） | 输金额 + 原因 → 发起退款拿 **TXRF/PMRF** → 「同参重放」演示幂等 → 2s 轮询 PMRF 到终态 → 展示 `payment_attempts` 中 `attempt_type=REFUND` 的渠道流水号 | `POST /proxy/order/internal/orders/refund`、`GET /proxy/payment/internal/refunds/{pmrf}`、`/demo/refund-attempts` |
| ④ 全链路 DB 数据 | 输入 orderNo → **跨 9 个库 14 张表的只读行级快照**（orders、order_items、transactions、**transaction_refunds**、payments、payment_attempts、refunds、fulfillments、entitlements、settlement_items/batches、reconciliation_batches、postings、ledger_entries），每个 section 含 `system/table/label/sql/rows/error` | `GET /demo/trace?orderId=ORxxx`（`DemoDbTraceController`） |

后端三项对测试极关键的能力：`/proxy/{service}/**` 同源透传且 **4xx/5xx 原样回传**（409/429 可直接按状态码断言）、`/mock-channel/callback`（支付回调）、`/mock-channel/refund-callback`（退款回调，可指定 `status` 与 `channelReference`）。

#### ② 对账控制台 `http://localhost:8091/audit.html`（会计四核对，spec 017）

① 触发（`scope: ALL/CERTIFICATE/LEDGER/REAL/REPORT`）→ ② 执行日志 → ③ 差异台账（8 类 kind：漏记账/孤儿分录/金额不符/重复记账/科目勾稽/跨账/账实长款/报表不符 × BLOCKER/MAJOR/MINOR）→ ④ 挂账到 SUSPENSE → ⑤ 调账（补记/红冲/更正/转出，>¥100 强制复核人、操作人≠复核人）→ ⑥ recheck + close（**有未收口差异必须 400**）→ ⑦ 台账 + 试算平衡。

#### ③ 三个会骗人的点（断言设计的硬约束）

| # | 陷阱 | 事实 | 对测试的约束 |
|---|---|---|---|
| **T1** | 回调验签是占位恒放行 | ADR-0025 占位实现，`signMode=FORGED` 也返回 200 | **禁止**断言「伪造回调被拒」——会得到假绿 |
| **T2** | audit 控制台 **MOCK 模式纯前端执行** | MOCK 不调后端，F1~F9 fixture 全在 JS 里 | 自动化测试必须走 **LIVE**（`/proxy/recon/internal/audit/**`）或直连 8088，否则测的是 JS 不是系统 |
| **T3** | 渠道对账差异来自固定 CSV fixture | `reconciliation-service/src/main/resources/fixtures/channel-statements/*.csv`（`sample.csv` 故意塞 `channel-extra-1,999` 制造单边差异） | 「对账准不准」必须**拆两套断言**：真实链路数据一致性 vs 差异检出能力（后者靠注入，与真实链路无关） |

---

## 2. 业内调研结论（2026-09-07 核实）

| 业内方 / 实践 | 做法 | 对本设计的启示 |
|---|---|---|
| 支付/金融系统测试分层 | **测试钻石**（非金字塔）：集成测试为主体，E2E 只留 3~5 条核心路径；接口漂移交给契约/快照层 | **采用**：E2E 少而精，不做全量回归主力 |
| 契约测试（CDC / Pact / Spring Cloud Contract） | 消费方驱动契约，替代大量 E2E | **部分采用**：本项目服务间走 `common-dto` 共享 DTO + Feign，编译期已挡住大半漂移，故**不引入 SCC/Pact**，改用成本极低的 schema 快照 |
| Stripe 测试模式 | 固定测试卡号 + **固定金额触发特定结果**（确定性，非概率） | **采用**：扩展 mock-channel 为「按请求特征确定性触发异常」 |
| 账务不变量测试（业界黄金断言） | `SUM(debits)==SUM(credits)`（按单 + 全局两级）、分录 append-only（禁 UPDATE/DELETE）、幂等重放结果一致、余额可从分录重算、金额一律整数最小单位 | **采用**：沉淀为 `Invariants` 断言原语，可选 jqwik 属性测试 |
| 对账测试的黄金形态 | 构造/注入 N 类差异 → 断言检出率 100% + 分类正确 + 调账后收敛 | **采用**：audit F2~F9 fixture 即现成的确定性差异注入器 |
| 异步与幂等测试 | Awaitility 轮询（**禁止 sleep 等**）、故障注入（toxiproxy / ChaosMesh）、同请求重放结果一致 | **采用**：`Await` 统一轮询；故障注入自研轻量触发约定 |
| 测试数据管理 | factory/fixture 构造 + 每轮唯一前缀或独立 schema；时间用注入 `Clock`（结算/超时/定时任务） | **采用**：每用例唯一业务号前缀，断言按单号过滤（免 truncate） |
| 环境与 CI | Testcontainers 保证可复现；PR 跑快层、nightly 跑 E2E/压测基线；flaky 隔离 | **采用**：本地默认复用栈，`ci` profile 用 Testcontainers；门禁分层 |

---

## 3. 决策记录（负责人 2026-09-07 逐项拍板）

| # | 决策点 | 拍板结果 |
|---|---|---|
| **D1** | E2E 承载形态 | **新建独立 Maven 模块 `deployment/e2e-tests`**（JUnit5 + Java `HttpClient` + Awaitility + JDBC 直连断言），黑盒不依赖业务模块；bash 演示脚本与 Node 压测脚本**保留**（前者服务现场演示、后者服务压测） |
| **D2** | 测试环境 | **默认 `e2e.env=local` 复用本地已起栈**（快）；`e2e.env=ci` 才用 Testcontainers 起 MySQL/Redis 全新容器 |
| **D3** | 契约测试 | **不引入 SCC/Pact**；改做「内部 API 请求/响应 schema 快照」断言（字段集合 + 类型），成本极低 |
| **D4** | CI 门禁 | **PR 快跑**（单元 + 单服务集成 + 快照 + ArchUnit，分钟级）；**nightly + release 前**跑 E2E 全链路/对账/并发幂等 |
| **D5** | DB 断言来源 | **行级快照复用 `/demo/trace`**（已跨 9 库 14 表按单号关联，零成本）；**聚合不变量才直连 MySQL** 写 SQL（借贷平衡、退款累计、孤儿单、单号唯一性） |
| **D6** | 对账准确性验证 | **复用 audit F2~F9 fixture（LIVE 模式）**做会计四核对差异检出；渠道对账用**新增 period 的 CSV** 注入长款/短款/金额不符/单边账/重复 |
| **D7** | 故障注入 | 扩展 `PAYMENT_MOCK_SCENARIO` 为「按请求特征确定性触发」（金额尾数 / remark），不靠概率与 sleep |
| **D8** | 分层占比与目标 | 测试钻石：L1 单元（多）/ L2 单服务集成（最多）/ L3 API 快照（少）/ L4 E2E（少而精）/ L5 对账与故障注入（中） |

---

## 4. 目标体系：五层测试钻石

```
L1 单元          纯领域逻辑：金额、状态机、单号规则        JUnit5 + AssertJ        PR
L2 单服务集成     单服务 + 真实 DB + fake 出站（现有资产）  @SpringBootTest + H2    PR
L3 API 快照       内部 API schema 不漂移（轻量契约）        自写快照断言            PR
L4 全链路 E2E     3~5 条核心链路 + 跨库不变量 + 异常路径    deployment/e2e-tests   nightly
L5 对账/故障注入  差异检出、并发幂等、回调异常、结算门禁     同一模块 + 确定性 mock  nightly
```

> L1/L2 为**现有资产**（不推倒重来）；本 spec 新建 L3~L5，并把 L1/L2 已有之不变量测试沉淀为 L4/L5 可复用的断言原语。

---

## 5. 用户故事与验收标准

### US1：退款全链路功能正确（P0）
**As** 负责人，**I want** 一条命令跑通「下单 → 支付成功 → 发起部分退款 → 异步回调收敛」，并断言 6 个库的状态与金额全部一致，**so that** spec 019 退款链路的每次改动都有回归保护。

- **AC1.1** 下单返回 201 且 `orderNo` 前缀 `OR`；建支付单返回 `paymentNo` 前缀 `PM`；渠道回调后 `payment.status` 收敛为 `SUCCEEDED`。
- **AC1.2** 发起退款同步返回 **TXRF**（交易层）+ **PMRF**（支付层）双号；轮询后 PMRF 到终态 `SUCCEEDED`。
- **AC1.3** 跨库一致：`order.transactions.refunded_minor` == `payment.refunds` 累计成功额；`order.orders.status ∈ {PARTIALLY_REFUNDED, REFUNDED}`。
- **AC1.4** 后效齐全：履约终止、权益撤销、账本冲正分录按 `REFUND/{pmrf}` 可追溯且该单号下 `SUM(借)==SUM(贷)`。
- **AC1.5** 用例失败时自动产出 `target/e2e-dump/<case>/` 诊断包（响应体 + 相关表快照）。

### US2：超退拦截（P0）
**As** 负责人，**I want** 验证单次超退、多次累计超退、并发退款三种场景都被拦住，**so that** 资金安全有自动化守门。

- **AC2.1** 单次退款额 > 实付额 → HTTP 409 + 明确错误码，DB 无新增退款行。
- **AC2.2** 多笔部分退款累计额 > 实付额 → 第 N 笔被拒（409），已退总额 ≤ 实付额（DB 侧不变量 `refundNotExceedPaid` 恒真）。
- **AC2.3** 并发两笔退款（总额超付）→ 成功笔总额恰等于可退额，失败笔被拒；**DB 侧 `refunded_minor ≤ paid_minor` 恒成立**（验证 `refund_intake_locks` 串行化有效）。

### US3：对账准确（P0，拆两套断言）
**As** 负责人，**I want** 验证对账能 100% 检出注入的差异并正确分类，**so that** 「对账准不准」不再是人眼判断。

- **AC3.1（会计四核对）** LIVE 模式下：CLEAN 组（F1）期望 0 差异；FAULT 组（F2~F9）8 类差异全部检出，kind 与 severity 分类与预期一致。
- **AC3.2** 差异 → 挂账 SUSPENSE → 调账 → recheck → close 闭环后差异清零；有未收口差异时 close 返回 400。
- **AC3.3** 结算门禁：对账差异未处理时不允许结算（断言拒绝）。
- **AC3.4（渠道对账）** 新增 period 的 CSV 注入长款/短款/金额不符/单边账/重复 5 类差异，跑批后检出率 100% 且分类正确。
- **AC3.5** 试算平衡：结束时 `Σ借方 == Σ贷方` 且 SUSPENSE 归零。

### US4：单号链路正确（P0）
**As** 负责人，**I want** 全链路跑完后校验所有业务单号的前缀、唯一性与跨库互记关系，**so that** 「单号有没有记错」有机器校验。

- **AC4.1** 前缀规则：OR（订单）/ TR（交易）/ PM（支付）/ PA（attempt）/ TXRF（交易层退款单）/ PMRF（支付层退款单）+ 雪花长度。
- **AC4.2** 双号互记：`transaction_refunds.payment_refund_no == refunds.refund_no（PMRF）` 且 `refunds.transaction_refund_no == transaction_refunds.refund_no（TXRF）`。
- **AC4.3** 归属正确：`transactions.payment_no` 与生效 `payments.payment_no` 一致；`payment_attempts` 归属正确 `payment_no`；退款 attempt 的 `attempt_type=REFUND`。
- **AC4.4** 全局唯一性：本轮产生的所有单号在各自域内无重复（雪花唯一性校验）。
- **AC4.5** 可追溯：本轮单号在各库均能上下游追溯（无孤儿单，`noOrphanRows`）。

### US5：异常路径与幂等（P1）
**As** 负责人，**I want** 覆盖回调重复/乱序/丢失、UNKNOWN 收敛、并发幂等等异常路径，**so that** 可靠性设计不只在文档里成立。

- **AC5.1** 同 `Idempotency-Key` 重复下单 → 恰好 1 笔订单（不新增行、金额不变）。
- **AC5.2** 同 TXRF 退款重放 → 返回同一 PMRF，不新增退款行。
- **AC5.3** 渠道回调重复投递 3 次 → 状态与金额不变（幂等吸收）。
- **AC5.4** 回调丢失 → UNKNOWN → `resolve` 收敛后**后处理不丢**：记账、权益撤销、订单状态全部补齐（与同步成功路径行为一致）。
- **AC5.5** 回调乱序（SUCCESS 先于 PROCESSING 到达）→ 终态不被回退。
- **AC5.6** 秒杀 SKU 退款后库存回补；普通 SKU 库存不变。

---

## 6. 功能需求

| ID | 需求 |
|---|---|
| **FR-001** | 新建 `deployment/e2e-tests` Maven 模块（不依赖业务模块，黑盒：仅 HTTP + JDBC），含 `pom.xml` 与 `run.sh` 一键入口 |
| **FR-002** | `Env`：环境配置（各服务端口、MySQL 连接、Redis）；`-De2e.env=local`（默认）/ `ci` |
| **FR-003** | `Api`：统一 HTTP 封装（下单/建支付单/渠道回调/退款/查状态/对账/结算），透传 4xx/5xx 供状态码断言 |
| **FR-004** | `Db`：按 schema 注册多数据源 JDBC 探针（order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/catalog/merchant） |
| **FR-005** | `Await`：Awaitility 统一轮询等待异步收敛（**禁止 Thread.sleep**） |
| **FR-006** | `Invariants`：可复用断言原语库（见 [plan.md §3](plan.md)） |
| **FR-007** | `Dump`：失败自动落盘诊断包到 `target/e2e-dump/<case>/`（响应体 + 相关表快照 + `/demo/trace` 快照） |
| **FR-008** | `Trace`：`/demo/trace?orderId=` 行级快照客户端，按 section 取 `rows` 断言 |
| **FR-009** | P0 用例 4 组：退款主链 / 超退拦截 / 对账准确 / 单号链路（US1~US4） |
| **FR-010** | P1 用例：幂等重放、回调异常（重复/乱序/丢失）、UNKNOWN 收敛后处理、秒杀库存回补、结算门禁（US5） |
| **FR-011** | 内部 API schema 快照断言（字段集合 + 类型），防接口漂移 |
| **FR-012** | 确定性故障注入触发约定（扩展 mock-channel，见 [plan.md §5](plan.md)） |
| **FR-013** | 数据隔离：每用例唯一业务号前缀 `e2e-{runId}-{case}`，断言按单号过滤 |
| **FR-014** | CI：扩展 `verify.yml`（PR 快层）；新增 `e2e.yml`（nightly + workflow_dispatch + release 前强制），产出 surefire 报告与 dump 产物 |
| **FR-015** | 撰写 `deployment/e2e-tests/README.md`：如何起栈、跑单条/全量、如何看报告与 dump |

---

## 7. 非功能需求

| ID | 需求 |
|---|---|
| **NFR-001** | 全量 E2E（P0+P1）本地冷跑 ≤ 10 分钟；单条用例 ≤ 60 秒 |
| **NFR-002** | 用例之间零耦合：可任意单条重跑、可乱序、可并发组（同库不冲突） |
| **NFR-003** | 断言失败信息必须含「期望值 / 实际值 / 关联业务单号 / 涉及库表」，可直接定位 |
| **NFR-004** | 异步等待一律轮询（默认超时 15s，可配），禁止固定 sleep |
| **NFR-005** | 新增用例成本趋近于零：业务语义写在用例，SQL 与断言逻辑集中在 `Invariants` |
| **NFR-006** | E2E 模块不参与业务模块构建依赖，业务代码零改动（除 mock-channel 故障注入扩展） |

---

## 8. 成功标准（Success Criteria）

| ID | 标准 |
|---|---|
| **SC-001** | `bash deployment/e2e-tests/run.sh` 一条命令跑通 P0 全部用例（退款主链 / 超退 / 对账 / 单号）并输出 surefire 报告 |
| **SC-002** | 故意在退款金额校验处注入缺陷（如去掉累计校验），E2E 超退用例**必须变红**且 dump 可定位（验证测试有效性） |
| **SC-003** | 故意破坏双号互记（不回填 `payment_refund_no`），单号链路用例**必须变红** |
| **SC-004** | 对账用例：F1 平账 0 差异、F2~F9 八类差异全检出且分类正确；渠道对账 5 类注入差异检出率 100% |
| **SC-005** | 单条用例可独立重跑通过（`mvn -pl deployment/e2e-tests test -Dtest=Xxx`） |
| **SC-006** | nightly workflow 在干净环境（compose 起栈）跑通并上传报告与 dump 产物 |
| **SC-007** | 既有 450 个单服务测试不受影响（`./mvnw -B verify` 仍全绿） |

---

## 9. 明确不做（负责人 2026-09-07 拍板）

- **不引入 Spring Cloud Contract / Pact**（D3），改用 API schema 快照。
- **不改造/退役 `deployment/demo/scenario-*.sh` 演示脚本**（服务现场演示；阶段 1 后评估 `run-all.sh` 是否改为调 E2E 模块）。
- **不做真契约测试的 stub 生成与消费方回放**。
- **不为 E2E 引入 k6/Gatling 等新压测框架**（压测沿用 Node 脚本 + `run-stress.sh`）。
- **不做回调验签相关断言**（T1：占位恒放行，断言会得到假绿）。
- **不在 PR 阶段跑 E2E**（D4：10 个 JVM 服务在 GH Actions 上过重）。
- **不做集中式日志/链路采集验证**（Loki 列后续期，见 spec 021）。
