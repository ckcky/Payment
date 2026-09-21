# Changelog

本文件记录 PaymentArch 的**重大治理与架构演进**。日常小修小补不在此列；以提交哈希 + 日期溯源。

规范、ADR、技术方案、系统设计同步更新的约定见 Constitution §「提交节奏 / 文档与代码同步」。

---

## [2026-09-21] feat(032)：对账实账化——渠道账单导入对象 + typed 匹配 + 差异台账拆表 + 渠道资金事实入账 + 结算口径收口（ADR-0080 Accepted）

**性质**：Feature 032 实现落地。负责人 2026-09-21 裁决 H-032-1~H-032-6 按 spec §16 推荐方案批准（[ADR-0080](docs/adr/0080-reconciliation-statement-and-fund-facts.md) 同日转 🟢 Accepted）。
关闭 design-review **C-13**（静默漏结算）/ **C-20**（跨商户串账 + 跨期重复结算）/ **C-22**（退款渠道引用不精确）；031 §16 挂起的渠道清算链路在此收口。

- **账单真实化（G1）**：三新表 `statement_imports`/`statement_lines`/`reconciliation_differences` + 守卫式增量迁移（`032-reconciliation-statement.sql`，过 schema-lint L-3 与双路径重放）；`StatementImport` 聚合 + `StatementLine` 实体（业务单号类型 `SI`/`RD`）；`CsvStatementParser` v2/legacy 双表头（结构性错误整批 REJECTED、归一缺陷行留档）；导入幂等 = 内容 SHA-256 指纹 `uk_import_identity` 回查重放；**删除 `sample.csv` 静默回退与 `statement_fallback` 指标**——run 无可用 NORMALIZED 导入 ⇒ 400 `STATEMENT_UNAVAILABLE` + 指标。
- **匹配与差异台账（G2）**：`ReconciliationMatching.matchTyped` 三级降级强键 `(merchantId, referenceType, reference)`（弱匹配只出候选不改判）+ 差异八类（新增 `FEE_MISMATCH`/`DUPLICATE_CHANNEL`/`UNKNOWN_MAPPING`）；差异拆独立行表（RD 单号、`uk_diff_identity` 幂等吸收、`differences_json` 停写不停读）；批次唯一键 `uk(period)` → `uk(channel_code, period, import_id)`（更正账单可重对）。
- **事实维度（G4）**：`confirmed-facts` 增可选 `period` 过滤（`DATE(created_at)`，不可解析周期串回退全量 + WARN）+ 出参 `merchantId` 全链透传（退款事实经 payment 反查）；退款渠道引用精确化（仅 SUCCEEDED 尝试、id 降序确定性排序）；结算口径改「全部已确认事实 − `excludedFacts` 未收口差异净影响」，settlement `ConfirmedFactGate` 商户校验（归属未知拒绝、他商户滤除）。
- **渠道资金事实入账（G3）**：032 为 `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` **唯一产生方**（`ChannelFundPostingService`；`CS-`/`CF-{channel}-{period}` 周期级确定性 sourceId，ledger 幂等键吸收重放；净额/费用为 0 跳过；`PERIOD_CLOSED` 原样上抛）；渠道费唯一合法来源 = FEE 账单行（双计三防线）；ArchUnit 词汇门禁扩展（`CHANNEL_FEE`/`CHANNEL_SETTLEMENT` 字面量仅 reconciliation/ledger/契约可引用）。
- **处置策略化（G5）**：差异处置分态 `ADJUSTING`（瞬时）/`ADJUST_FAILED`（可见、不计未收口，独立事务失败台账不静默吞）；audit 侧人工收口端点 `POST /internal/audit/batches/{batchNo}/differences/{id}/resolve`（F7 跨账差异关闭路径）；`AutoDispositionPolicy`（`SMALL_CHANNEL_ONLY_SUSPEND`，enabled/金额上限默认双关）；reconciliation 侧差异查询/按单号收口/渠道资金入账/run 端点补齐 API 暴露层。
- **demo 与测试**：`scenario-audit.sh` 增账单导入（v2 CSV）与 F7 人工收口步骤（⑧ 关批可过）；`scenario-reconciliation.sh` 切导入前置 + run + 差异分页 + 按单号收口；audit-faults 注入行补 `merchant_id` 与期间内 `created_at`；对账/结算测试按实账化契约重写（TC-032-1~14 落点），schema 新表过 test-infra 真库唯一键断言。
- **文档同步**：systems/reconciliation-service.md（§3.1/§3.5 契约更新 + §8 实账化增量）、systems/settlement-service.md（§1.4 结算口径）、systems/payment-service.md（§3.7 事实契约）、technical-solution.md 服务表、specs/README 与 roadmap 032 → 🟢 已实现。
- **遗留**：T24b（自动处置/audit 处置在 034 C-19「ledger 记账同事务优先」合入后的回归重测）待 rebase 后由编排者安排。

---

## [2026-09-21] feat(033)：测试基础设施——真库共享基座（仅测试作用域）+ schema 双路径重放门禁 + RPC 边允许清单（ADR-0081 Accepted）

**性质**：Feature 033 实现落地。负责人 2026-09-21 裁决 H-033-1~H-033-5 按 spec 推荐方案批准（Testcontainers
仅测试作用域 / 基线入库 / refund 保留标注遗留 / R-A 边允许清单 / skip 即红）。**纯测试基础设施与门禁**：
不改任何生产行为，既有 H2 用例与 `InMemory*Repository` 桩零删除，033 自身零业务断言（用例本体归 031/032/payment 域）。

- **真库共享基座**（ADR-0081 决策 1）：新模块 `deployment/test-infra`（仅测试作用域依赖）——Testcontainers
  单例 MySQL（跨类复用）+ 类内独占库 `t_<类名>_<hash>` + `SchemaBootstrap`（DDL 单一来源 `deployment/schema`，
  路径白名单 + 剥 CREATE DATABASE/USE + 必含约束断言，禁假绿②）+ `RealDb` 组合注解（`@Tag("real-db")` +
  spring.datasource System property 注入）+ `DockerContract`（无 Docker 本地 skip 并显式汇总 /
  `realdb.required=true` 时 fail，禁假绿①）+ `FaultHooks` / `MetricsAssert`；容器与测试统一 utf8mb4 / UTC。
- **既有真库测试切换基座**：`LedgerPostingConcurrencyTest` 迁移共享基座，**三条用例与断言逐字保留**（spec §11
  显式授权的泛化）。
- **schema 门禁**（ADR-0081 决策 2）：`schema-lint.sh`（L-1 MariaDB 方言禁令 / L-2 initdb 与 schema 库集合一致 /
  L-3 ALTER 必带 information_schema 守卫）+ `016` 守卫模式改写（语义不变）+ initdb `refund` 遗留标注（H-033-3）+
  `schema-replay.sh` 双路径重放（A 空库全量连跑两遍幂等自检 + B 基线恢复→全量+增量，information_schema 快照
  diff 必须为空）+ 首基线 `deployment/schema/baseline/033.sql`（33 表 / 9 库，H-033-2）；D-A 缺陷注入实测可证伪
  （注入列 → exit 1 且 diff 定位）。
- **架构门禁**（ADR-0081 决策 3）：`RpcEdgeAllowListTest` + `rpc-edges.txt`（15 条 `@FeignClient` 运行时边逐行
  全等，允许清单式）+ `src/main` 禁 `org.testcontainers` L5 规则（封口「仅测试作用域」）。
- **CI 挂载**：`schema.yml` 新增（lint + replay）；`verify.yml` 追加 `real-db` job（`-Drealdb.required=true`，
  skip 即红，既有 job 零改动）；`e2e.yml` 追加 nightly 五个离线 demo 场景（happy-path / audit / mq /
  reconciliation / refund，§16 R6 排除涉沙箱）+ ACCESS_LOG 断言 + `verify-random-order`
  （`-Dsurefire.runOrder=random`）；`demo/reset.sh` 容器名环境变量间接寻址（compose 默认不变）。
- **规范收口**：engineering-standards §4 测试载体与 L1→L2b 升级判据 + 归属边界、§11 漂移清单第 6 条
  （rpc-edges 一致性）、新增 §12「迁移脚本可重放」六条（spec §6.2 升格）；Constitution §Engineering.3 同步；
  ADR-0081 🟢 Accepted + 索引同步；roadmap / specs README / spec 033 三件套（plan/tasks/acceptance）齐。
- **验证**：全量 `./mvnw -o clean verify -fae` **915 tests 全绿（0 失败 / 0 错误 / 0 跳过）**，16 模块 BUILD
  SUCCESS；schema-lint / schema-replay 本机实跑 exit 0；详见 spec 033 [acceptance.md](docs/specs/stage-05-channel-and-finance-deepening/033-test-infrastructure/acceptance.md)。

---

## [2026-09-21] feat(031)：Ledger / Accounting 地基——Accounting Event 入站契约 + 两级科目 + 余额投影（ADR-0077~0079 Accepted）

**性质**：Feature 031 实现落地。负责人 2026-09-21 裁决 D-1~D-7 按 spec 推荐方案批准并开工。

- **契约事件化**（ADR-0077）：payment / refund / settlement / reconciliation-audit 四调用方改投 `AccountingEvent`，
  **调用方组分录与科目硬编码删除**（ArchUnit `AccountingVocabularyBoundaryTest` 门禁）；`PostingRequest/PostingResponse` 退役；
  幂等键由账本派生 `{eventType}:{sourceId}`，重复投递回放首笔，并发唯一赢家（`uk_postings_idempotency_key` 兜底）。
- **两级科目**（ADR-0078）：`account_definitions`（8 科目目录）/ `accounts`（owner 维度实例）就地演进；
  `AccountResolver` 未登记 owner 即拒绝；`payments.merchant_id` 落列。
- **余额投影与账期**（ADR-0079）：`account_balances` 同事务累加 + `/balances/rebuild` 重建自愈；
  `trial-balance` 试算平衡 + `ledger_periods` 关账门禁。
- **Schema**：`09-ledger-schema.sql` 重写 + `031-ledger-accounting-foundation.sql` 增量；`demo/reset.sh` 回灌科目种子。
- **测试**：必测 9 项 + Testcontainers 真 MySQL（管线集成 / 原子性 / 并发）；`mvn verify` **866 tests 全绿**；
  demo happy-path / audit / reconciliation 场景实测；入账日志补 `bizNo=sourceId`（`TraceContext.runWithBizNo` 新增 Supplier 重载）。
- **文档**：`systems/ledger-service.md` 重写、`technical-solution.md` 三处修订、spec 031 补 Plan/Tasks/Acceptance 三件套、
  roadmap / ADR 索引同步。**遗留**：CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT 产生方切换归 032/034；
  L2b 公共测试基座归 033。

---

## [2026-09-21] docs：032~035 设计轮（对账真实化 / 测试基础设施 / 可靠性 / 可观测 SLO，纯文档不动代码）

**性质**：Design-only（docs-only）。**未改任何 Java 业务代码、未改业务逻辑、未建 migration、未加测试、未引入运行时依赖**；
031 仍在负责人处待裁决，本轮**未触碰其 162 个工作区改动**。

- **新 Spec（四份，v1.0 均为「🟡 设计完成，待 Architecture Review」）**：
  [`032-reconciliation-real-statement`](docs/specs/stage-05-channel-and-finance-deepening/032-reconciliation-real-statement/spec.md)（497 行）/
  [`033-test-infrastructure`](docs/specs/stage-05-channel-and-finance-deepening/033-test-infrastructure/spec.md)（536 行）/
  [`034-reliability-hardening`](docs/specs/stage-05-channel-and-finance-deepening/034-reliability-hardening/spec.md)（480 行）/
  [`035-observability-slo`](docs/specs/stage-05-channel-and-finance-deepening/035-observability-slo/spec.md)（467 行）。
- **跨 Feature 收口**：[`design-summary.md`](docs/specs/stage-05-channel-and-finance-deepening/design-summary.md) ——
  统一术语、依赖图（含对 stage-design §9.2 的 4 处修正）、Domain Ownership / Source of Truth / 幂等边界 /
  恢复边界 / 观测边界 / 测试边界六张表、禁止耦合清单 P-1~P-9、实现顺序六道门、阶段级 Out of Scope、
  7 项 Architecture Review 清单（下挂 27 项逐条裁决）、本轮明确未做的事。
- **新 ADR（四条，均 🟡 Proposed，未 Accepted 前不得实现）**：
  [ADR-0080](docs/adr/0080-reconciliation-statement-and-fund-facts.md)（账单导入对象 + `(merchantId, referenceType, reference)` 三级匹配 +
  复用 5 态差异生命周期 + 032 为 `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 唯一产生方 + 结算口径先于差异策略化）、
  [ADR-0081](docs/adr/0081-test-carrier-and-schema-replayability.md)（Testcontainers 放宽至**仅测试作用域** + schema 双路径重放门禁 +
  RPC 边允许清单；**变更 Constitution §Engineering.3**）、
  [ADR-0082](docs/adr/0082-failure-recovery-ownership-and-compensation.md)（恢复三不变式 R-1/R-2/R-3 + 调用方侧 `pending_postings`
  一张表复用形状 + C-19 后置动作同事务 + **移除** Resilience4j + refund 侧对称有界收敛）、
  [ADR-0083](docs/adr/0083-observability-baseline-slo-and-cardinality.md)（4 SLO 以 Recording Rule 表达 + 高基数政策 HC-1~HC-4
  + 报文/密钥不入日志取**路径排除**（不推翻 ADR-0027）+ **否决 APM**、保留自研 traceId）。
- **以代码为事实来源的三处基线校正**（写设计前先核对，纠正了 stage-design 的低估）：
  ① Testcontainers **已在用**（`LedgerPostingConcurrencyTest`，030/T11）⇒ 033 是「把一次性手搓泛化为基座」而非首次引入，
  真实缺口是 **CI 从不重放增量迁移脚本**（`.github/workflows/e2e.yml:76-89`）与 **27 处 `CREATE TABLE IF NOT EXISTS` 对存量表不补列**；
  ② UNKNOWN 侧**已有**有界查询 + critical 告警 + 双侧人工 resolve ⇒ 034 的真实缺口是**五个「有指标无出口」的失败面**
  （记账 RPC、DLQ 只写不读、refund 域零调度器、卡 `REQUESTED`、C-19 崩溃窗口）；
  ③ 93 个业务指标标签键**恰好**只有 16 种低基数维度，但**无任何条文或检查守住** ⇒ 035 的高基数政策是「守增量」不是「追历史」，既有指标零改名。
- **登记治理收口**：ADR README 索引 + 编号速查 + 水位推进至 **ADR-0084**；specs README 注册 032~035 与 design-summary；
  roadmap 新增「stage-05 设计状态」条（**明确标为设计完成待评审，不得标 Implemented**）；
  stage-design §9.2 补 4 处依赖修正与两条硬顺序约束；traceability §3 标注五条 ADR **L0 尚无落点**。
- **本轮显式不做**：未运行 demo/E2E 验证设计可行性、未复核 031 工作区代码与其设计的一致性、
  未验证 SLO 目标值可达性（全部标 `[目标]`）、未做工作量与排期估算（tasks.md 属 Spec Kit 下一阶段）。

## [2026-09-21] docs：031 Ledger / Accounting 地基收敛（审计 → Spec → ADR-0077~0079，纯文档不动代码）

**性质**：架构审计与设计收敛（docs-only）。**未改任何业务代码、未建 migration、未实现功能**。

- **审计**：以代码为事实来源走查 ledger-service 与四个记账调用方，确认核心越界——
  **记账决策权在上游**（调用方组分录、传 accountId/direction、`netMinor = amountMinor - feeMinor` 在上游算，
  `FeignLedgerPostingGateway` 等硬编码科目常量），违反「上游告诉账务发生了什么，账务系统决定应该怎么记」。
- **新 Spec**：[`031-ledger-accounting-foundation/spec.md`](docs/specs/stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/spec.md)
  （编号勘误：stage-design §9.2 原写 `032-ledger-account-view` 有误，账务地基 = **031**）。
  固化十项架构原则与 `Accounting Event → Posting Rule → Posting Line → Account Resolver → Account →
  LedgerTransaction → LedgerEntry` 管线；六类事件的正式记账规则表（PAYMENT_CAPTURE / REFUND /
  CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / CHANNEL_FEE / ADJUSTMENT）；费用口径
  Gross / MerchantFee / ChannelFee / MerchantNet / ChannelNet（费率计算**不属于** Ledger）；
  科目 Definition+Instance 两级模型与**迁移式改造**（`accounts` 表就地演进，id=3 就地更名 FEE_REVENUE，
  不留两套并存的冲突模型）；余额 Projection（方案②同事务累加）、期间/关账、调用方侧 pending 台账。
- **新 ADR**：`docs/adr/0077-ledger-accounting-foundation-decisions.md`（ADR-0077 契约事件化 /
  ADR-0078 两级科目迁移 / ADR-0079 余额投影与期间），均 🟡 Proposed。
- **旧 ADR 标注**：ADR-0008 **Partially Superseded by 0077/0078**（被取代的仅「分录由调用方组装」与
  「固定 5 科目枚举」两条）；ADR-0009/0011 契约与边界描述标注待修订（语义不变）；
  索引与编号水位同步（下一可用编号 ADR-0080）。
- **stage-design 收口**：§4.2 缺口重写为「已收敛为 031 Spec + ADR-0077~0079」（新增 G0），
  §4.4 登记 L-0（记账决策权在上游，🔴），§9.2/§9.3 Feature 矩阵与决策表同步。

**下一步**：负责人裁决 D-1~D-7（契约一刀切换 / 科目表变更 / schema 变更含 `payments.merchant_id` /
负净额反向分录 / 存量数据口径）→ ADR 转 Accepted → `/speckit-plan 031`
（031-A 边界地基 / 031-B 余额·期间·台账，两批交付）。

---

## [2026-09-20] fix：Payment / Channel 边界复审的 4 项实现级修复（FIX-1~FIX-4）

**性质**：架构边界修复（非 spec 变更、非模型重设计）。来源是当天对 030 的
Payment / Channel / Demo 三层边界的**只读复审**；本轮只修其中**确定存在、且不依赖 030 Spec 改动**的
问题，Spec 固化的部分**原样未动**（见文末「未修项」）。

**FIX-1（P0）INV-4 门禁盲区 + 3 处真实穿透**
`ServiceBoundaryTest#channelRoutingAbstractionMustNotDependOnChannelInfrastructure` 的 `that()` 原为
`com.payment.payment.application.channel..`——**不覆盖 `application..` 主体**。一个子包之差让三处真实反向依赖长期逃过门禁
（`PaymentRetryService` / `ChannelQueryService` / `PaymentRefundService` 的兼容构造引用
`infra.channel.SingleChannelRegistry`）：INV-4 名义合规、实际穿透。
修法两半：① `SingleChannelRegistry`（零 infra 依赖，是「注册表」抽象的一个退化实现）由 `infra.channel`
归位 `application.channel`；② 规则 `that()` 放宽到 `com.payment.payment.application..` 并**配阳性对照**
（断言被检主体与禁用目标都真有类，防空转）。
**门禁活性已实证**：临时在 `application..` 植一处 `infra.channel` 依赖，规则**如期变红并指名该类**（旧规则不会命中）；
撤销后复绿。

**FIX-2（P0）建单并发幂等路径把「幂等重试」答成状态机错误**
`PaymentPersistence.insertPending` 撞 `uk_payments_idempotency_key` 时，`insertNew` 只回查了既有支付单，
调用方**仍继续**为该支付单 `openPaymentAttempt` 建第二条 attempt、再 `payment.start(新 attemptId)`——
而既有支付单已是 `PROCESSING` ⇒ 抛 `STATE_TRANSITION_VIOLATION`、建单事务整体回滚。
**这是「只查不判」的典型**：返回值无法区分「刚插入」与「回查到的」。
修法：`insertNew` 回传 `newlyCreated`；命中重复时**立即回放库内支付单 + 其既有 PAYMENT 尝试**
（`created=false`），不建第二条 attempt、不推状态机——兑现 FR-152 / FR-306（幂等重复返回**库内**值，
不用本次请求染色覆盖）。
**未引入 UPSERT**：业务唯一键是 `uk_payments_idempotency_key`，其取值确定性（调用方给或由
`payment:{orderNo}:{channelCode}:{attemptSeq}` 派生），「先查后插 + 撞键回放」已构成完整语义；
`ON DUPLICATE KEY UPDATE` 会把 `status` / `currentAttemptId` / 渠道结果等**不可覆盖字段**一并改写，直接毁掉首次事实。
回归测试 `PaymentPersistenceIdempotentRaceTest`（用「对手方先落库」的仓储桩把只在真并发下出现的分支钉成确定性）。

**FIX-3（P1）Payment 1:1 PaymentAttempt 补写侧断言**
架构定义要求该基数关系，但渠道层写入口此前**无任何断言**（design-review C-02 方案 A 未落地）。
现于端口默认方法 `ChannelAttemptRecorder#requireNoExistingPaymentAttempt` 统一判据，
**三个实现同口径**（生产 `ChannelAttemptRecorderImpl`、内存测试桩、仓储兼容垫片）——
不变量若在三个实现里各写一遍，迟早漂移成「生产拦住了、测试桩没拦」。
**库约束表达不了**：需要的是「`payment_no` 在 `attempt_type='PAYMENT'` 子集上唯一」，而 MySQL 无部分索引；
退而求其次的 `UNIQUE(payment_no, attempt_type)` 会连**合法的多条 REFUND 尝试**一起禁掉（部分退款是正常业务）。

**FIX-4（P1）退款尝试的写归位渠道层端口；重复键不再无条件吸收**
`PaymentRefundService` 此前自己 `new PaymentAttempt.refundAttempt(…)` + `converge` + `save`，
并在自己这边 `catch (DuplicateKeyException)` 把**一切**重复键当幂等吸收——这正是
`uk_attempts_channel_reference` 是**单列**唯一、而退款侧把「原支付交易号」当退款流水号（F5 形态）
撞上同支付单那条 **PAYMENT** 行时，缺陷被伪装成「幂等重放」的成因。
修法：新增端口方法 `ChannelAttemptRecorder#recordRefundAttempt(…)`（创建 + 收敛 + 落库一步到位，仍是**一次**带终态的 INSERT）；
撞键经 `requireTrueRefundReplay` 判定——只有「同一 `payment_no` 已有同 `channel_reference` 的 REFUND 行」
才算真重放并吸收，其余一律抛 `INTERNAL_ERROR` 指名引用冲突。

**门禁**：`./mvnw -o -B clean verify -fae` BUILD SUCCESS（17/17 模块，**845 tests / 0 failures**，较修复前 837 净增 8）；
`payment-service` **362 tests / 0 failures**；`ServiceBoundaryTest` **10/10**（ArchUnit）。

**验证（E2E 与沙箱主链路）**：本地 live 栈 e2e `Tests run: 24, Failures: 2, Errors: 4, Skipped: 1`——
**与本次改动无关，已在 `master` 上原样复现**：`e2e-nightly` 连续 10 次失败（含 2026-09-19 20:00 那次），
失败签名逐条一致（`RefundChainE2ETest` 两条、`OverRefundGuardE2ETest`、`ReconciliationAccuracyE2ETest.faultInjectionMatrix`）；
本地多出的 2 条来自 `deployment/demo/scenario-audit.sh` 在 2026-09-19 11:15 预置的对账故障夹具
（`PM-AUD-0001..0003` / `RF-AUD-0001`），CI 无此夹具。**本次改动零回归。**

**Sandbox 主链路实测未破坏**：① mock 主链 `POST /orders` → `POST /orders/{no}/payments(ALIPAY)` →
`POST /internal/payments/{no}/channel-callback(SUCCESS)` → payment `SUCCEEDED` → `payment.succeeded` →
order `PAID`（1s 内）；② 真实沙箱链路 `X-Dye-Tag: SANDBOX` 建单**如期**返回 RSA2 已签名自动提交表单
（`https://openapi-sandbox.dl.alipaydev.com/gateway.do?...method=alipay.trade.page.pay`，`payUrl` 首字符 `<` ⇒ `FORM_HTML`），
支付单留在 `PROCESSING`/`UNKNOWN`、`payment_attempts.extra_json.channelMode='SANDBOX'` 且 `channel_reference` 为空（待买家确认）——
与 ADR-0076 设计一致。

**⚠️ E2E 红项的根因=已在案的存量 P0（复现确认，非本轮引入、也非本轮新发现）**：refund 系用例的「订单收敛超时」
不是本轮改动所致，而是 `GOTCHAS.md` §C.1 早已记录的那条链——`refund.result` 事务消息**从未成功投递**。
本轮用对照法复现了该结论：`PaymentEventPublisher.payload(RefundResultNotification)` 把**可空的 `failureReason`**
放进 payload，退款**成功**时为 `null`，而 `TransactionalProducer.envelope` 末参是 `Map.copyOf(payload)`
⇒ `NullPointerException(getMessage()=null)` ⇒ 被 `catch` 成 WARN「退款结果通知 order 失败 reason=null」
（`reason=null` 即其指纹）。**对照证据**：`XLEN mq:stream:refund.result` = **0**（redis 已运行 29h），
同期 `XLEN mq:stream:payment.succeeded` = **338** —— 后者证明该流「消费后不删」，故 0 只能是「从未投递」。
属 spec 029 事务消息负载装箱缺陷（F4/F5/F6 链），不落在本次 Payment/Channel 边界范围，**只复现不修改**。

**未修项（明确留置，不属本轮范围）**：Spec 030 以 FR 形式**固化**了若干与本轮架构定义冲突的行为
（FR-167 把 `defer` 判定放在 payment 层、FR-151/303 要求渠道持久化层读 `DyeContext` 落模态、
FR-153/271 要求 payment 侧 `runWith(attempt.getChannelMode())` 包裹渠道调用、FR-114/§7.3 把 `payUrl` 回落组装留给 payment），
以及 `PaymentResultProcessor` / `RefundAttemptSettlementService` 仍在 payment 层驱动 attempt 收敛（= 渠道 Template Method 缺位）。
这些要么改 Spec / ADR、要么动 Payment-Channel 核心分工，**按「不修改已完成的 030 Spec」与「不重设计核心模型」的口径一律未动**。

---

## [2026-09-20] fix：支付宝异步通知验签口径错误（真实回调 100% 被拒）—— spec 030 F3

**性质**：**资金链路阻断级缺陷**。spec 030 沙箱联调实战发现：沙箱买家真实付款后，支付宝 `TRADE_SUCCESS`
异步通知**到达但被拒**（`POST /internal/channels/alipay/notify` → **403**），支付单永远停在 `UNKNOWN`、
资金事实永远收敛不了。**既有 834 tests 全绿的门禁无法发现它**——所有 notify 相关测试都把
`verifyNotify` **stub 掉**，「真报文 + 真验签」这条路径**零自动化覆盖**。

**根因**：`AlipaySdkGateway.verifyNotify` 用的是 `AlipaySignature.rsaCheckV2`。在 `alipay-sdk-java 4.40.996.ALL`
（已 `javap -c` 反编译核对）里两个方法语义**是反的**：`getSignCheckContentV1` 剔除 `sign` **和** `sign_type`；
`getSignCheckContentV2` **只**剔除 `sign`（保留 `sign_type`）。而支付宝给异步通知签名时**不含 `sign_type`**
⇒ V2 拼出的待签串多出一段 `sign_type=RSA2`，验签必然失败。真实报文实测（同一条通知、同一把公钥，
差异仅 `sign_type=RSA2` 一项）：**V1 待签串 597 字符通过 / V2 待签串 612 字符失败**。

**修复**：
- `verifyNotify` 改用 `rsaCheckV1`，并传 `LinkedHashMap` 副本（SDK 的 `getSignCheckContent*` 会就地 `remove`，
  不污染调用方的 `params`）。
- 新增 `AlipayNotifySignatureVerificationTest`（自签密钥对，离线可跑，不依赖沙箱密钥与公网）：
  ① 真实形态通知（`sign_type` 不参与签名）**必须**通过；② **阳性对照**——让 `sign_type` 参与签名
  **必须**判为不通过（防止将来被「修」回 V2 语义）；③ 空报文 / 缺 `sign` 一律不通过（INV-10）。

**live 回归（修复后实测）**：支付单 `PM227388464260329472` ⇒ notify `验签通过` → `200 success`
⇒ 由 `UNKNOWN/TIMEOUT` **收敛为 `SUCCEEDED`**；订单 `PAID`；ledger `PAYMENT:PM227388464260329472` POSTED，
分录 **DEBIT 9900 / CREDIT 9900**（借贷平衡）。

**判据修正（重要）**：此前把「空体探针返回 403」当作「验签正常」的证据 —— **错误推论**：
空报文本来就该 403，它既证明不了密钥正确，更证明不了**算法口径**正确。
**判据必须是一条真实的渠道报文。**

---

## [2026-09-20] feat：spec 030 统一渠道契约 + Mock/沙箱双模态 + 回调基础闭环（ADR-0075 / ADR-0076）

**范围**：渠道层从「单一 mock 实现」演进为「统一契约 + 双模态（本地 mock / 真实沙箱）+ 回调闭环」。
本 Feature 分 12 个 Phase（Phase 0~11），前 6 个 Phase 为**资金缺陷修复与契约奠基**，后 6 个为**沙箱真调与回调闭环**。

**两个资金缺陷修复（Phase 1/2，独立可交付）**：
- **B1 账本幂等键双口径**（🔴）：同一支付单的**同步成功**路径与**回调收敛**路径产生**不同**的 posting 键
  （`PAYMENT:payment:{orderNo}:{code}:{seq}` vs `PAYMENT:PAYMENT:{paymentNo}`），导致同一笔钱记两次账。
  修复：两条路径统一为 `PAYMENT:{paymentNo}`，前缀拼接**只留** `FeignLedgerPostingGateway` 一处（FR-220/221/222）。
- **B7 在途守卫区分「重放 / 重试」**（🟠）：order 侧退款在途守卫把 `REQUESTED`（渠道未受理）与
  `PROCESSING`（渠道已受理）一视同仁——前者重试**不**重放渠道调用（漏退），后者重试会**重复**调渠道（双重退款）。
  修复：判据改为「是否已成功推进过」，并**修正** `TransactionRefundTest` 中固化了错误预期的断言（`hasSize(1)` ⇒ 2）。

**统一渠道契约（Phase 3，零破坏）**：
- `ChargeRequest` 5→12 字段、`RefundRequest` 5→9、`QueryStatusRequest` 3→4，**保留兼容构造器**（既有调用点零改动，SC-A-02）。
- 新增 `PayCredential`（付款凭证，6 种 `Kind`）与 `ChannelResult.credential`——修复前真实渠道的「跳转 URL」无处承载。
- `PaymentChannel` 新增 `supportedScenes()` / `supportsRealMode()` 两个 `default` 方法（不破坏既有实现）。

**流量染色（Phase 4/5/6）**：
- `DyeMode`（MOCK/SANDBOX）+ `DyeContext` ThreadLocal + 入站过滤器 / 出站拦截器；
  染色头 `X-Dye-Tag` **只决定协议实现**，**不参与**路由决策（INV-3，ArchUnit 强制）。
- 模态落库：`payment_attempts.extra_json` 的 `channelMode` 键（**通用扩展列**，非专用列）。
  读取侧 **fail-safe**：`NULL` / 非法 JSON / 缺键 / 非法值四类坏数据一律读作 `MOCK`，绝不中断反向路径（FR-304）。
- 反向路径自足：查询 / 退款 / 超时扫描按**落库模态**还原协议实现（FR-270/271/272），
  并修**确定性排序**（原无 `ORDER BY` 的 `findFirst()` 使「查哪个渠道」不可复现）。

**支付宝沙箱适配器（Phase 7，INV-7）**：
- **单 Adapter 双模态**（`AlipayChannelAdapter`）：`MOCK` 走 `super` 委托（零分叉），`SANDBOX` 走真实协议。
- **端口收口**：`AlipayGateway`（平台自有类型）→ `AlipaySdkGateway`（**唯一** import SDK 的 **Java 包 `com.alipay.api`** 的类，
  ArchUnit 构建期强制 + 阳性对照；⚠️ 非 Maven 坐标 `com.alipay.sdk`）。将来换纯 JDK 实现**零扩散**。SDK 供应链风险由 ADR-0076 显式接受。
- 沙箱未启用（`enabled=false`）而染色 `SANDBOX` ⇒ **400**，**绝不静默回落 mock**（INV-8）。
- 金额换算 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`——**禁 `double`/`float`**（INV-1）。

**支付宝回调闭环（Phase 8，INV-10）**：
- 新端点 `POST /internal/channels/alipay/notify`，**三段式校验**（验签 → 引用归属 → 金额/币种）。
- 验签失败 ⇒ **403** 且**不触达**收敛（状态零改动）；响应体 **MUST 恰好**为纯文本 `success`（FR-206）。
- 校验失败**三件套**：不推进 + 计指标（`payment.notify_rejected`）+ `FINANCIAL_AUDIT` 审计，缺一不可（FR-213）。
- 收敛**复用**既有 `PaymentCallbackService.handleCallback`，**不新建链路**（FR-205）；
  两条回调路径收敛语义一致（SC-B2-06）。
- **INV-6**：`credential != null` ⇒ 渠道仅受理、买家未付款 ⇒ payment **停 `PROCESSING`**，不走成功收敛。

**演示环境开关（Phase 9）**：`demo.html` 新增「本地 mock / 支付宝沙箱」选择器，默认 mock；
`start-all.sh` 透传 `PAYMENT_ALIPAY_SANDBOX_*` 并在缺失时**启动即失败**。

**门禁实况（Phase 10）**：`./mvnw -o -B clean verify -fae` → **17 个 reactor 模块全 BUILD SUCCESS**，
**834 tests / 0 failures / 0 errors / 0 skipped**（含 `architecture-tests` 的结构断言）。
新增 ArchUnit 规则：`application/**` 与 `domain/**` **MUST NOT** 依赖 SDK 的 **Java 包 `com.alipay.api`**（INV-7；⚠️ 非 Maven 坐标 `com.alipay.sdk`，写错会让门禁恒通过）；
`ChannelRouter` **MUST NOT** 读 `DyeContext`（INV-3）。
零回归核验：既有测试仅 `TransactionRefundTest`（授权的修正型断言）与 `PaymentCaptureLedgerPostingTest`（加强断言）被改；
全仓**零**私钥 / 零 `client_secret`；既有渠道配置默认值**全部不变**。

**未落地（已知限制）**：B7 的**并发**用例在 H2 上可能假绿，需真库（落点 033 / H16）；
B1 的并发限制**已解除**——`LedgerPostingConcurrencyTest` 走 Testcontainers-MySQL 真库实跑通过（3 tests / 0 skip / 25.7s）。
沙箱真调需真实买家账号 + 公网 `notify_url`，无法进 CI，由 demo 动线手工覆盖（见验收 §3.2）。

**合并后收口修正（2026-09-20，本次收口）**：
- **F1 凭证形态误标**：支付宝 `pageExecute().getBody()` 返回的是**自动提交表单 HTML**（**不是 URL**），
  却被标成 `PayCredential.Kind.REDIRECT_URL`，使 `isRedirectFamily()` 返回 `true`，消费端按「可直接跳转」处理
  ⇒ **点开是空白页**（联调实测）。修：新增 `PayCredential.formHtml(...)`，`AlipayChannelAdapter` 改用 `FORM_HTML`；
  3 处测试桩载荷改为真实表单 HTML 形态并更正断言（避免「桩与真实契约不一致」导致断言跟着错标）。
- **F2 架构门禁空转假绿**：INV-7 的 ArchUnit 规则原写 `resideInAPackage("com.alipay.sdk..")`，
  而**该 Java 包不存在**（`com.alipay.sdk` 只是 Maven groupId；真实包是 `com.alipay.api`）⇒ 规则**恒通过**、形同虚设。
  修：规则改为 `com.alipay.api..`，并加**阳性对照**（先断言 `AlipaySdkGateway` 确实依赖该包），同类笔误将立即变红。
- **文档同步**：`030/spec.md §2.5` 新增两表、`030/acceptance.md` 重写（🟢/🔵/⏳ 证据记号）、`030/tasks.md` 头部状态；
  `adr/0075`·`adr/0076`·`adr/README`·`technical-solution.md`·`systems/payment-service.md`·`stage-design.md` 包名/形态对齐。
- 收口门禁：`./mvnw -o -B clean verify -fae` → **17/17 BUILD SUCCESS，834 tests / 0 failures / 0 errors / 0 skipped**。

---

## [2026-09-19] fix：spec 029 合并后回归修复（CI contract-snapshot 转绿）

**范围**：spec 029 合入 master（`5e2c00d`）时未跑门禁，CI `verify.yml` 的 `contract-snapshot`
job 随即暴露两处缺陷。本次修复二者，并补上此前遗留的 T62 / T73 验收项。

- **R1 阻塞读超时致消费端空转**（spec 029 引入的真实回归）：
  `MqProperties.blockMs` 默认 2000ms **大于**五服务 `spring.data.redis.timeout` 的 1000ms——
  每轮 `XREADGROUP BLOCK 2000` 在 ~1s 处被 Lettuce 判定命令超时并抛 `RedisCommandTimeoutException`，
  消费循环捕获→sleep 1s→重试，如此空转：消息永不被读取，`payment.succeeded` 不被消费，
  订单不收敛为 PAID（CI 报「订单收敛为 PAID 未在 30s 内完成」）。
  修复：五服务 timeout 提至 `5000ms`；`payment-service` 补显式 `spring.data.redis` 配置块
  （原缺该块、回落 Lettuce 默认 60s 才侥幸正常，属默认值兜底的隐性正确）；
  新增 `MqTimeoutGuard`（`InitializingBean`）在启动期 fail-fast 校验
  `redis.timeout > block-ms + 500ms`；`docker-compose.yml` 补 fulfillment / entitlement
  缺失的 `SPRING_DATA_REDIS_HOST` 与 `redis: service_healthy` 依赖。
  回归测试 `MqBlockingReadTimeoutTest`（4 例）显式注入短超时，覆盖原 `StreamConsumerTest`
  用 `new LettuceConnectionFactory(host, port)`（60s 默认）留下的盲区。
- **R2 contract-snapshot 冷启动 Feign 超时**（早于 spec 029 的独立缺陷）：
  `createPayment` 的 order→payment Feign 调用源码默认 read-timeout 1s，CI 冷启动窗口内
  首次跨服务调用超时抛 `feign.RetryableException: Read timed out`，未被
  `GlobalExceptionHandler` 收编为业务错误码，落 catch-all 分支输出通用 500 `INTERNAL_ERROR`
  ——表现为 `paymentResponseSchemaIsStable` 稳定失败（9/17 起多次运行同点复现，与 MQ 无关）。
  同仓 `e2e.yml` 早已用 `PAYMENT_FEIGN_*_TIMEOUT_MS` 规避并留有注释，`verify.yml` 的该 job 漏配。
  修复：补齐四个超时变量 + `PAYMENT_ADMIN_TOKEN`，与 `e2e.yml` 对齐；源码默认值不动。
- **验收补记**：
  - **T62（全量门禁 / SC-9）**：补跑 `./mvnw -o -B clean verify -fae` → 17 reactor 条目全 **BUILD SUCCESS**
    （5m26s），**667 tests / 0 failures / 0 errors / 0 skipped**（139 份 surefire 报告聚合）；
    通道相关测试 **84 例**（≫ SC-9 要求的 ≥30）。
  - **T73（SC-11 traceId 连续性）**：全栈复测，`run-all.sh` 末段 `scenario-mq.sh` D1~D6 **全绿（PASS=15 SKIP=1）**；
    单据 OR227024975809409025 的 traceId `3f818561-2791-486c-a0df-d29cfbdf7398` 贯穿 order / catalog /
    fulfillment / entitlement / payment 五段日志，timeline 按 bizNo 还原 2 条事件且 traceId 一致。
  - **INV / SC live 演练**：`scenario-mq.sh` D1~D6 实跑覆盖 SC-2/3/4/5（回滚不投递 / 崩溃回查补投 /
    下游宕机自愈 / 广播隔离）；`run-all.sh` 七段（主链 / 渠道路由 / 退款 / UNKNOWN 收敛 / 每日对账 /
    审计闭环 / 消息通道）**EXIT_CODE=0 全通过**。
  - **演示脚本修复**：`scenario-mq.sh` 原用 `$2` 解析 `redis-cli XINFO GROUPS`（管道输出为一行一 token，
    键值分行 → 基线恒 `-1`，断言平凡成立）——改为状态机解析并抽为 `deployment/demo/mq-group-entries.sh`。

---

## [2026-09-20] feat：spec 029 Redis 事务消息通道落地（ADR-0074）

**范围**：新增 `common/common-redis-mq` starter + 五服务（order / payment / catalog / fulfillment / entitlement）
生产消费侧改造 + order 侧只读轨迹投影 + 可观测与演示件。ADR-0074 状态 🟡 Proposed → 🟢 **Accepted**；
**Supersedes ADR-0031**（不使用 MQ 中间件——Redis Streams 复用既有 Redis，未新增运维实体，禁令清单不变）。

- **通道语义（INV-3）**：`prepare`（写半消息 + `ZADD` 回查索引）→ 本地事务提交 → `commit`（`XADD` 可见队列 + 清半消息）。
  半消息超 `prepare-timeout-ms` 未决即进回查：`HalfMessageScanner` 每 5s 扫到期项，分派 `TransactionChecker`
  （COMMIT / ROLLBACK / UNKNOWN），UNKNOWN 超 `maxCheckTimes` → DLQ（FR-105/106）。
- **消费语义（FR-107~109）**：`XGROUP MKSTREAM` 建组（忽略 `BUSYGROUP`）→ `XREADGROUP`（blockMs 2000）→ 成功 `XACK`；
  失败按 1s / 2s / 4s 退避重试，超 `maxRetry` 进 DLQ 并 `XACK`；`XAUTOCLAIM`（`minIdleMs=60s`）接管同组超时 PEL。
- **替代的 8 条同步通知链路**：`payment.succeeded` / `refund.result`（payment → order，点对点）；
  `order.paid` / `refund.succeeded` / `order.cancelled`（order → 广播）；`fulfillment.completed` /
  `fulfillment.revoked`（fulfillment → entitlement，点对点）。**记账三条链路维持同步**（ADR-0074 D2）。
- **回落开关（FR-306）**：`payment.mq.enabled=false` 时全部 MQ 装配不生效，生产方回落既有同步 Feign 语义
  （秒杀回补、履约驱动、库存确认等原调用点保留在 `else` 分支）；两种模式既有集成测试均通过。
- **订单轨迹（FR-401~404）**：新增 `order_event_log` 表（`msg_id` 唯一键幂等吸收）+ `trace` 消费组独立订阅全部
  7 个 topic + `GET /api/orders/{orderNo}/timeline`；轨迹为**只读投影**（INV-5），删表不影响业务链路。
- **traceId 连续性（FR-601~604）**：信封携带 traceId；消费端 `MDC.put("traceId", …)` 恢复、`finally` 清理；
  半消息回查补投**沿用信封原始 traceId**（不新建）；fulfillment → entitlement 链路继承上游 traceId。
- **可观测（FR-502~504）**：8 项 `mq.*` 指标（`prepared` / `committed` / `rolled_back` / `checked` / `consumed` /
  `retried` / `dead_letter` / `half_backlog`）；`StructuredAuditLogger` 记录 `mq.committed` / `mq.rolled_back` /
  `mq.consumed` / `mq.dead_letter`（含 msgId + bizNo）；Grafana 新增「⑥ 消息通道」行（11 图：半消息积压 / DLQ /
  投递回滚速率 / 各组消费与重试 / PEL 积压 / 各组位点 / 回查分派 / 审计速率），`redis-exporter` 提供 stream 服务端指标。
- **容灾（FR-501）**：Redis 补 `--appendonly yes --maxmemory 512mb --maxmemory-policy noeviction` + 数据卷
  —— 容量触顶**拒绝写入而非驱逐**，避免半消息 / 位点被无声淘汰。
- **架构门禁**：`ServiceBoundaryTest` 的分布式基础设施禁用清单**保持不变**（Kafka / RabbitMQ / RocketMQ / JMS / JTA-XA
  仍禁），测试注释补充「Redis 通道不在清单内」的定位与豁免依据。

---

## [2026-09-19] spec 029 立项：Redis 事务消息通道（仅文档）

**范围**：跨服务异步解耦方案定稿（**ADR-0074**，Supersedes ADR-0031「不使用 MQ」）+ spec 029 四件套。
本轮**只写文档，不改代码**；实现期另开 `feature/029-redis-transactional-mq`（已于 2026-09-20 落地，见上条）。

- **通道形态**：用现有 `redis:7` 的 Streams 承载**事务消息**语义（半消息 → 本地事务 → commit/rollback → 5s 回查真相表），
  **不引入 RocketMQ / Kafka 等任何消息中间件**（个人项目不增组件）。
- **改造范围**：8 条通知链路（`payment.succeeded` / `refund.result` 点对点；`order.paid` / `refund.succeeded` /
  `order.cancelled` 广播；`fulfillment.completed` / `fulfillment.revoked` 点对点）。
  **记账三条链路（payment / refund / settlement → ledger）维持同步**——借贷平衡审计对顺序敏感且有 T+1 兜底。
- **拓扑取舍**：广播点画在 `order.paid` 而非 `payment.succeeded`，因 `order_items` 是明细单一事实源（ADR-0066）
  且 surplus 判定在 order 的 transaction 层。
- **补齐缺口**：trace 消费组订阅全部事件落 `order_event_log` + `GET /api/orders/{orderNo}/timeline`，
  实现「按订单号还原全链路状态变迁」；MDC 补 `bizNo` 维度，**traceId 跨异步边界连续**（ADR-0074 D14）。
- **容灾**：Redis 补 `--appendonly yes --maxmemory 512mb --maxmemory-policy noeviction` + 数据卷（当前无持久化）。
- **命名规则变更**：新建 ADR 文件的文件名前缀改为「文件内首个 ADR 的编号」（如 `0074-redis-transactional-message.md`
  承载 ADR-0074），消除双编号心智负担；历史 0001~0034 保持不动以免全库断链。

---

## [2026-09-16] feat：spec 027 用户支付限额（ADR-0071 落地）

**范围**：payment-service 新增**用户支付限额**子域（`com.payment.payment.limit.*`），加上 common-core
错误码、schema 与演示组件，跨 4 个模块。ADR-0071（`docs/adr/0071-user-payment-limit.md`）
状态 🟡 Proposed → 🟢 **Accepted → Implemented**。

- **能力**：按 `userId` + `currencyCode` 的**日 / 月 / 年**三档周期额度；建支付单前**原子预占**
  （一条 `UPDATE ... WHERE used + pending + ? <= limit`），超限 → **`409 LIMIT_EXCEEDED` 且支付单不创建**
  （是「未创建」而非「创建了再拒」）；`SUCCEEDED` 确认、`FAILED` / `CLOSED` 释放。
- **三表**：`user_payment_limits`（配置）/ `user_limit_usage`（`used` + `pending` 双金额）/ `limit_operations`
  （幂等流水）。**`limit_operations` 唯一键为 `(biz_no, op_type, period)`**——一笔支付同时占三档，
  原 `(biz_no, op_type)` 会使 MONTH / YEAR 档静默失效（实现期修正）。
- **在途占用 TTL**：`RESERVE` 成功后写 Redis `SET limit:pending:{paymentNo} {amt} EX 900s`，
  回收**惰性**发生在用户下次 `RESERVE` 之前（`LimitPendingRecycler`，零调度器、零全表扫描）；
  TTL 下界 105s（须大于支付侧最大自动收敛窗口），启动强校验。**payment 首次依赖 Redis**
  （`spring-boot-starter-data-redis`，ADR-0044 的限用途反转：**仅作过期索引，不做计数**）。
- **软超限口径**：`used` 按已发生事实**如实累加**（哪怕 `used > limit`），不拒绝、不 clamp、不回滚；
  以 `payment_limit_overrun{period}` 指标 + `limit.overrun` 审计 + 查询响应 `overrun` 标记**让偏差可见**，
  收敛靠下一笔 `RESERVE` 被拒，直至周期重置。
- **降级（INV-9）**：Redis 未配置 / 不可用 → `NoopLimitExpiryIndex` **fail-open 保守占用**
  （跳过回收、不拦截支付）；H2 全量测试**零 Redis 依赖**通过。
- **默认不限额**（D8）：查不到配置行即不约束；`demo/seed.sh` 不为 `demo-user` 播种限额，
  `traffic-gen.sh` 与 E2E 不被 409 打断。`payment.limit.enabled=false` 时行为与今天逐字节一致。
- **内部端点**：`GET/PUT/DELETE /internal/limits/users/{userId}`、`GET /internal/limits/payments/{paymentNo}/operations`、
  `GET /internal/limits/diagnostics`；对既有对外契约**零改动**（超限只以错误码表达）。
- **演示例外**：`mock-channel-web` 允许经 `/proxy/payment/internal/limits/**` 读写该路径
  —— ADR-0071 D9 对 **ADR-0048「演示代理只读」的显式例外**（例外范围严格限于该路径）。
- **演示**：`demo.html` 新增限额输入组（设置 / 一键演示超限 / 清除）+ 右栏「用户限额」卡
  （日 / 月 / 年进度条 = `used` 实心 + `pending` 半透明、超限红条、最近流水）；
  `/demo/trace` 新增「用户限额」分组；`portal.html` 补「限额」chip；
  新增 `deployment/demo/scenario-limit.sh`（7 场景 L1~L7）。
- **与 ADR-0028 的切割**：限额是**业务合规约束**（确定性比较 + 硬拒绝），**非风控评分**；
  风控 ⛔ Not Implemented、代码已删的结论**不变**。

## [2026-09-16] v2.0.0 发布（tag `v2.0.0`）

**范围**：自 v1.0.0（2026-09-07）以来 124 个提交 —— spec 018 / 019 / 020 / 021 / 022 / 023 / 026 落地，
spec 027 / 028 立项（仅文档）。**完整发布说明见 [docs/releases/v2.0.0.md](docs/releases/v2.0.0.md)**，本节只留索引。

- **运行形态**：新增容器模式 `deployment/start-container.sh`，与宿主模式端口契约一致故互斥，
  由 `lib-mode-guard.sh` 双向守卫；一份通用 Dockerfile 参数化 10 模块（**ADR-0070，Supersedes ADR-0057**）。
- **质量体系**：新增黑盒 `deployment/e2e-tests` 模块 + 9 条资金不变量断言原语 + 确定性故障注入；
  `verify.yml` 增 PR 契约快照门禁；E2E 进 nightly 且 v* tag 强制（**ADR-0069**）。
- **退款链路**：两层退款单 TXRF / PMRF 双号互记 + 渠道退款异步回调闭环（HMAC 验签）+ 编排归属收口到 order
  （**ADR-0067**）；`transactions` 补 `payment_no` / `refunded_minor`。
- **可观测**：统一单行 `ACCESS_LOG`（含服务名）+ 异步 MDC traceId + 9 服务优雅停机 30s drain（**ADR-0068** / spec 023）；
  `onPaymentSucceeded` 出站 RPC 移出事务边界（事实不回滚）。
- **数据模型**：22 表列序归一、新增 `order_item_no`（OI+雪花）、履约细化到明细级、
  `payment_attempts` 补金额与币种留痕（**ADR-0066**）。
- **⚠️ 破坏性变更**：退款创建入口 `POST /internal/refunds` **下线**（改走 order 侧，`resolve` 保留）；
  `refunds.refund_no` 前缀改 `PMRF`（存量 `RF` 可读）；`GET /fulfillments/by-order` 改返回数组；
  **存量库升级须依次执行 `deployment/schema/018-*.sql` 与 `019-*.sql`**（幂等，步骤见发布说明）。
- **发布流程变更**：`release.yml` 改用 `body_path: docs/releases/<tag>.md` 取代 `generate_release_notes`
  ——**今后每个版本发布前必须提供对应的说明文件**，否则 Release 正文为空。
## [2026-09-08] feat：spec 028 支付两层结构 + 渠道路由（ADR-0072/0073 落地）

**范围**：payment-service 由「单渠道硬编码」改为**两层结构 + 确定性路由**；跨 common / order / payment /
mock-channel-web / arch-tests 六个模块，53 个文件。ADR-0072（`0072-two-layer-channel-architecture.md`）
与 ADR-0073（`0073-channel-routing.md`）状态 🟡 Proposed → ✅ Accepted。

**核心不变式（INV-1~INV-6，均有门禁或测试兜底）**

- **INV-3 确定性路由**：`ConfiguredChannelRouter` 决策链为 `enabled=false 回落旧行为` → `显式 code 只校验
  已注册（不校验 enabled）` → `priority 最小、同级字典序` → `抛 NO_AVAILABLE_CHANNEL`。无随机、无计数器；
  同一 `RouteContext` 连跑 20 次结果一致的确定性用例在 `ConfiguredChannelRouterTest` 中固化。
- **INV-4 / INV-5 边界门禁**：`ServiceBoundaryTest` 新增两条**非空断言**规则——`application.channel..`
  MUST NOT 依赖 `infra.channel..`；`application..`（豁免 `reliability` / `channel` 两包与
  `ChannelAttemptRecorders`）MUST NOT 调用 `PaymentAttemptRepository.save`（`payment_attempts` 写入口
  唯一归属 channel 层端口 `ChannelAttemptRecorder`）。架构测试 6/6 → **8/8**。
- **INV-6 反向路径不重路由**：退款 / 渠道查询 / 重试三处一律经 `ChannelRegistry` 按 attempt 记录的
  `channel_code` 解析，禁止触达 Router；显式请求 `DOWN` 渠道 → `409 CHANNEL_UNAVAILABLE`。
- **INV-5 真实违规修复**：`RefundAttemptSettlementService` 原直接 `attemptRepository.save(...)`，
  改注入端口 `ChannelAttemptRecorder`（保留单参兼容构造，经 `ChannelAttemptRecorders.of()` 委托）。

**分层与端口**

- 新增 `application/channel/`：`ChannelAttemptRecorder`（openPaymentAttempt / openRefundAttempt /
  converge / markUnknown）、`ChannelRegistry`（resolve / registeredCodes）、`ChannelRouter` +
  `RouteContext` record（javadoc 明写 `amountMinor` / `currencyCode` 当前未使用）。
- 新增 `infra/channel/`：`AbstractMockChannelAdapter`（逐字承载尾数故障注入 / 退款异步推送 /
  每实例独立 `runId` / scenario 严格枚举 4 件横切行为）、`Alipay` / `Wechat` / `DouyinChannelAdapter`
  （`@Component`，只声明身份 + 差异）、`SpringChannelRegistry`（启动期校验 code 非空且唯一）、
  `ConfiguredChannelRouter`、`SingleChannelRegistry`（FR-036 兼容垫片）。
- `PaymentChannel` 端口补 `String channelCode()`；`MockChannelAdapter` 改继承抽象基类、声明
  `channelCode()="MOCK"`，**保留全部 6 个既有构造签名**，既有 10 处 `new MockChannelAdapter(...)`
  测试零改动通过。

**调用侧契约**

- `CreatePaymentRequest.channelCode` / `CreateOrderPaymentRequest.channelCode` 去 `@NotBlank`，
  可空即交由 Router 决定；`CreatePaymentResponse.channelCode` 语义收口为「路由后最终渠道」。
- 幂等键取值来源改为**路由后** code，避免 `payment:OR1:null:1` 脏键；选路 MUST 在建单之前
  （不先落库再改写 `channel_code`）。显式传未注册 code → `400 INVALID_ARGUMENT` 且错误信息列出已注册清单。
- `PaymentApplicationService` 保留单通道构造重载，内部包装为「单通道注册表 + 恒等路由」。

**可观测性与只读端点**

- 计数器 `payment_routing_total`（Micrometer 点名 `payment.routing`，导出为下划线），标签
  `result ∈ {explicit, routed, no_available_channel, unavailable_explicit}`；每次路由落 INFO（经 MDC
  带 `traceId`，复用 spec 021 ACCESS 体系）；`no_available_channel` 额外落 WARN。
- 新增 `GET /internal/channels`（code/status/priority/enabled）与 `GET /internal/channels/route-preview`
  （候选排序 + 排除理由，**零落库**）；`ErrorCodes` 新增 `NO_AVAILABLE_CHANNEL` / `CHANNEL_UNAVAILABLE`
  （复用 `GlobalExceptionHandler` 映射 409，**不新建异常类**）。

**文档与演示件**

- `payment-service.md` 补 §3.9 两个新错误码、§3.10 路由只读端点表；`runbook.md` 补 `payment_routing_total`
  指标行与 §4.1 非密钥路由配置项 + 启动失败原因；`tasks.md` 批次 A–G 全勾（T56 实跑待有栈环境）。
- 新增 `mock-channel-web/static/routing.html` + portal 入口 + 导航项；新增 `deployment/demo/scenario-routing.sh`
  （S1~S6 确定性断言，读 `payment_attempts.channel_code`，无 sleep / 无概率）并纳入 `run-all.sh`；
  `restart-payment.sh` 支持 `<SCENARIO> [CODE=SCENARIO,...]` 按渠道设定 mock 人格（S5/FR-010）。
- **订正**：spec §5.3 原表声称可断言 `payments.channel_code`，实测该表**无此列**（`PaymentResponse` 亦无该字段），
  改为统一读 `payment_attempts.channel_code`；未新增伪列。

**验证**：`./mvnw -o clean verify -fae` 全部 **16 个 reactor 模块 BUILD SUCCESS**；payment-service
测试 149 + 路由 22 全绿；架构测试 8/8。

**Batch G 实跑暴露并修复的 5 处缺陷（纯构建/单测均未发现）**

首轮 live 演示即失败，逐项定位——**这是 live 验证不可替代的价值**：

1. **`channelCode` 的 `@NotBlank` 实际漏改**（`common-dto` + `order-service`）：T27/T28 标记完成但代码
   仍拒绝空值，不传渠道直接 `400 must not be blank`，US2「只表达支付意图」主路径**完全不可用**。
   改为 `@Pattern`（允许空、非空时校验形态）。
2. **`application.yml` 的 flow mapping 里裸写 `${...}`**：SnakeYAML 把 `{ scenario: ${X:Y} }` 中的
   `${` 判为嵌套 mapping 起始 → 容器启动即 `expected ',' or '}'` 崩溃。加引号修复。
3. **控制器回显请求里的原始 `channelCode`**（违反 FR-027/FR-030）：自动选路时响应与 payUrl
   都带 `null`/`MOCK`，与真实落库渠道不一致。新增 `RoutedPayment`（支付单 + 最终渠道码）由服务层
   回带，控制器一律以它为准。
4. **下游 409 被压成 500**：order-service 无 Feign `ErrorDecoder`，`FeignException` 落进兜底分支，
   调用方看到「内部错误」而非「渠道不可用」。新增 `PaymentFeignConfig`（仅绑定 payment 客户端）
   解析下游 `code`/`message` 还原为 `BizException`，让 `409 CHANNEL_UNAVAILABLE` 语义穿透。
5. **演示件三处**：`docker-compose.yml` 补 `SPRING_PROFILES_ACTIVE: demo`（否则 FR-035 端点不存在）；
   `scenario-routing.sh` 补 `⓪a 复位渠道可用性`（内存覆盖不随 `reset.sh` 清、上轮残留致误报）
   + 修 `attempt_channel_of` 的 stdout 污染（`http()` 日志被 `$(...)` 一并捕获）。

**实跑结果**：全栈容器起栈 → `run-all.sh` 全绿（含路由段）→ `scenario-routing.sh` **22 条断言全过**
（S1 自动落 ALIPAY / S2 显式 WECHAT 零干预 / S3 绕开 DOWN / S4 显式 DOWN → 409 且无部分写入 /
S5 两渠道独立落库 / S6 退款回原渠道 INV-6），`payment_routing_total{result="routed"}` 正常暴露。

---

## [2026-09-16] docs：spec 进度文档刷新 + 陈旧分支清理

**范围**：纯文档 + 仓库清理，无代码改动（028 的 ADR 为 Proposed，未开工）。

- `docs/specs/stage-03-evolution-consolidation/026-containerized-local-stack/spec.md`：状态由 `Draft` 改为
  `✅ Implemented`，补齐三条收口合并点（`61e9e3d`/`6a07a3d`、`08f3bb1`、`01193ff`）与实测口径。
  原 `Draft` 系文件未刷新的滞后标记，非任务未完成（tasks.md 早为 68/68）。
- `docs/architecture/roadmap.md`：Current Status 补齐 `018`~`026` 九个 spec 的落地汇总表
  （此前只登记到 `017`，`018`~`026` 全部缺失）；并登记 `028-channel-routing`
  为「已立项待实现」（ADR-0072/0073 🟡 Proposed，待核准）；订正「当前能力」行的模块清单
  （10 服务 → **9 服务**，反映 spec 019 退款域并入 payment；补 `e2e-tests` 模块）；
  登记 `003`/`006`/`007` 的非阻塞遗留测试项。
- **新增 spec 027（用户支付限额，纯文档）**：`docs/specs/stage-04-new-directions/027-user-payment-limit/`
  （`spec.md` / `plan.md` / `tasks.md` / `acceptance.md`）+
  `docs/adr/0071-user-payment-limit.md`（ADR-0071，🟡 Proposed，D1~D13 已确认）。
  三表模型（`user_payment_limits` / `user_limit_usage` / `limit_operations`）+ 两阶段预占
  （`RESERVE → CONFIRM / RELEASE`）+ 三道幂等闸门（终态吸收 / 流水 UK / 以 payment 为事实源的补偿扫描）；
  在途占用 TTL=900s（D11，Redis 惰性回收，零调度器）+ 软超限口径（D12，新支出硬约束、已发生事实软记账）
  + 允许 payment 使用 Redis（D13，**ADR-0044 的显式例外**，仅限 TTL 标记、不做计数）。
  与 ADR-0028「最小风控」切割——限额属业务合规能力，非风控翻案。**无代码改动**。
- **分支清理**（均已确认并入 master）：远端删 `chore/022-pr-contract-gate`、
  `fix/023-ops-closeout`、`feature/026-container-stack`；本地删 `chore/022-pr-contract-gate`、
  `feature/007-outbound-resilience`、`fix/023-ops-closeout`。

---

## [2026-09-15] fix：容器模式收银台 payUrl 客户端不可达（spec 026 补丁）

**范围**：修 `PAYMENT_MOCK_CASHIER_BASE_URL` 在容器模式下的取值错误 + 补「payUrl 客户端可达性」回归用例。
无 ADR（属 spec 026 容器化的缺陷修复，非新决策）。

- **缺陷**：容器模式下 compose 把 `payment.mock-cashier.base-url` 配成 `http://mock-channel-web:8091`
  （容器内服务名）。该值被 `buildPayUrl()` 拼进 `payUrl`，交给前端 `window.open()` 由**浏览器**打开——
  浏览器所在宿主解析不了容器内网名字（NXDOMAIN）。表现为「下单成功、支付单也建了，但收银台跳转不了」，
  而容器内 curl 该 URL 反而 200，极具迷惑性。
- **扫清的第二个错误候选**：`host.docker.internal` **同样不可用**——实测该名仅容器内可解析（宿主 NXDOMAIN）。
  正解是 `localhost:8091`（8091 已 publish 到宿主）。与 `prometheus.yml` 的取值差异是有意的：
  那里是**容器主动出站抓取**，请求由 prometheus 容器发起；此处地址是给浏览器用的，方向相反。
- **测试缺口（根因）**：既有 e2e / demo 全是 curl 打接口断言状态码，**从不打开接口返回的链接**，
  故此类缺陷对整套自动化隐形。新增 `CashierPayUrlReachabilityTest`：按**绝对 URL** 直连 payUrl 要求 2xx，
  并断言客户端不共享服务网络时 payUrl 不得含容器内服务名。
  已做**有效性验证**：把配置改回错误值 → 用例立刻变红并直指根因（HTTP 503 / NXDOMAIN），非空跑。
- **展示层兜底**：`DemoProxyController` 对上游响应做 `mock-channel-web:8091` / `host.docker.internal:8091`
  → `localhost:8091` 的改写，即使环境变量又被配错，/demo 页拿到的 payUrl 也保证可用。
- **顺带修复**：`Dump.write` 未净化文件名——step 含完整 URL 时 `? : / &` 使写盘失败，
  失败时恰恰丢掉诊断产物。已改为替换非法字符并限长。
- **回归**：容器模式 e2e 24/25（新增用例绿；唯一红仍是已知本地代理伪影 MISSING_POSTING，CI 为准）；
  demo 5 场景退出码 0。

---

## [2026-09-15] spec 026：本地全栈容器化（双模式 · Compose 而非 K8s）

**范围**：spec 026 立项 + P1~P7 全部落地并实测通过（P4 可观测适配、P5 演示脚本模式分支、P7 双模验证矩阵）。
详见 [docs/specs/stage-03-evolution-consolidation/026-containerized-local-stack/](docs/specs/stage-03-evolution-consolidation/026-containerized-local-stack/)，决策见 **ADR-0070**

- **Supersedes ADR-0057「服务未容器化」**：原决策理由「学习项目，容器化非当前目标」被推翻——
  宿主 JDK 版本绑架启动（`RunMojo` 需 Java 17+，本机默认 java 11 → `UnsupportedClassVersionError`）
  与宿主进程生命周期脆弱（任务回收致 payment 8084=000、压测数据作废）两个真实痛点证明其价值。
- **双轨并存（D2）**：新增容器模式 `deployment/start-container.sh`，保留宿主模式 `deployment/start-all.sh`；
  两者端口契约一致（8081–8091）故**互斥**，由 `deployment/lib-mode-guard.sh` 提供双向守卫（已实测拦截生效）。
- **宿主打 jar，镜像只 COPY（D3）**：`deployment/docker/Dockerfile` 一份参数化通用镜像服务 10 个模块，
  复用父 POM 已产出的 `deployment/output/jars/*.jar`，镜像内不跑 Maven（避免 3 个 common 模块被重编译 10 次）。
  基础镜像 `eclipse-temurin:21-jre-jammy`。
- **配置适配不改源码（D4）**：compose `environment` 覆盖硬编码的 `127.0.0.1`（Nacos `nacos:8848`、
  MySQL `mysql:3306`、Redis `redis`、mock-channel-web 的 `/proxy` 目标改容器服务名），业务源码零变更。
- **compose 增加 `pay-arch` 网络与 profiles**：`infra`（7 中间件）/ `full`（+10 应用）；
  应用 `depends_on nacos: condition: service_healthy`，避免 Connection refused 假成功。
- **P4 可观测适配**：`prometheus.yml` **刻意不改**——抓取目标沿用 `host.docker.internal:808x`，
  容器模式端口已 publish 到宿主，一份配置双模通吃（改 compose 服务名会让宿主模式失联）。
  实测容器模式 Prometheus 10 target 全 UP、Loki 10 个服务均有日志。
  应用日志落挂载卷靠 Dockerfile 的 `tee` 双写：项目 `logback-spring.xml` 只有 CONSOLE appender，
  `logging.file.name` 无效，只能由入口把 stdout 分流到文件（同时保留 `docker logs`）。
- **P5 演示脚本**：`restart-payment.sh` 按当前模式自动选宿主重启 / 容器重建
  （`docker compose up -d -e PAYMENT_CHANNEL_MOCK_SCENARIO=...`）；容器模式 demo 5 场景退出码 0。
- **P7 双模验证矩阵（已全部执行）**：demo 两种模式均退出码 0；e2e 两种模式**同形 22/23**
  （唯一红为已知本地代理伪影 `MISSING_POSTING`，CI 为准）；压测两种模式均 `chain_completed=100`、
  0 个 5xx（吞吐 rps 36.4 → 26.2，catalog 609 → 552，源于容器堆 256m < 宿主 384m + NAT 开销，可接受）。
- **容器模式专属修复（无此则 e2e 必挂）**：①reconciliation 容器挂载宿主 `/tmp/e2e-channel-statements`
  ——渠道账单 CSV 由测试 JVM（宿主）写入，容器不挂就读不到；②Dockerfile 加 `TZ=Asia/Shanghai`
  ——基础镜像默认 UTC，与宿主差 8 小时会让按日期窗口的断言分叉。
- **内存教训**：容器堆必须**低于**宿主（`-Xmx256m` + `metaspace 128m`）并给每个服务
  `mem_limit: 512m`（catalog 承载秒杀主流量放宽到 768m）。不限量时 10 个 JVM 顶穿 Docker Desktop
  默认配额 7.75GiB，导致 VM 被杀、全部容器 Exited(255)。稳态实测约 5.3GiB。
- **前提提示**：镜像构建需能访问 Docker Hub 拉取 `eclipse-temurin:21-jre-jammy`；网络不可达时
  `docker compose build` 会在 `FROM` 阶段失败，此时用宿主模式。

---

## [2026-09-08] spec 022 收尾 + 023 live 验证：测试有效性实测、PR 契约门禁、运维验证闭环

**范围**：spec 022 批次 G 收尾（T433/T434/T435/T436）+ spec 023 剩余项（T5/T14/T16/T17/T18），全部 live 栈实测。详见各自 tasks.md 附注。

- **T433 测试有效性实测（SC-002/SC-003）**：临时注入缺陷后还原（未入库）——①旁路 order 可退余额校验 + payment 累计校验 → 超退用例红 2/3（断言含订单号金额，dump 落盘）；附带发现仅去 payment 侧校验仍绿，409 闸在 order 侧（防御纵深）；②`RefundOrder` 不回填 `payment_refund_no` → 单号链用例红（双号互记断裂）。
- **T434 全量回归**：`./mvnw -B verify` BUILD SUCCESS；E2E 全量 22/23 绿 + 1 skip，唯一红（对账矩阵 MISSING 检出）经 general_log / 访问日志 / 无沙箱复跑三重实证为本机透明代理吞写伪影，CI 通道为准（`Db.execute` 已走 `/demo/db-exec` 代执行 + 落库回读，b6af115）。
- **T430 PR 契约门禁**：`verify.yml` 新增 `contract-snapshot` job（最小栈仅跑 L3 API 快照），ArchUnit 随 reactor verify 生效——契约漂移 PR 即红，不等 nightly。
- **023 live 验证**：Prometheus targets 无 `job="refund"`；`scenario-happy-path.sh` 全断言通过；SIGTERM 优雅停机日志完整（Commencing → complete，端口释放）；`start-all.sh` Nacos 超时改 exit（d40602d）。
- **T436 评估结论**：`demo/run-all.sh`（现场演示编排）与 E2E 模块（无头回归门禁）受众与生命周期不同，不合并。

---

## [2026-09-07] spec 022：全链路自动化测试体系（代码落地，ADR-0069）

**范围**：新建黑盒 `deployment/e2e-tests` Maven 模块（不依赖业务模块），承载「退款正常 / 超退拦截 / 对账准确 / 单号记对」四类全链路验证；决策与验收见 [spec 022](docs/specs/stage-03-evolution-consolidation/022-full-chain-automated-testing/spec.md)。**live 实跑验证待办**（T433/T434，`bash deployment/e2e-tests/run.sh`）。

- **支撑层**：Env（local/ci 双环境）/ Api（JDK HttpClient 黑盒，4xx 原样返回）/ Db（9 schema JDBC 探针）/ Await（Awaitility 统一轮询，禁 Thread.sleep）/ Trace / Dump（失败自动落盘）/ Invariants（9 条断言原语：超退守卫、单号链、记账平衡、权益撤销、履约终止、库存守恒、幂等重放、无孤儿）/ E2eBase（用例级数据隔离 + 造单助手）。
- **P0/P1 用例**：退款主链（部分/全额，六库一致 + 权益撤销 + 履约终止）、超退守卫（单次/累计/并发 4×2000 不超付）、对账四核对（LIVE CLEAN + 8 类 FAULT 注入矩阵，DB 直改备份→注入→检出→还原）、CSV 渠道账实差异注入（长/短/金额不符/重复，经 `statement-dir-override` 运行时落盘）、结算门禁、单号链、幂等与回调异常路径（重复 3 次 / 丢失→resolve→后处理不丢 / 乱序不回退）、API schema 快照（L3，`-De2e.update-snapshots=true` 重录基线）。
- **确定性故障注入（T429 / D7）**：`MockChannelAdapter` 请求级触发——金额尾数 11=超时 / 12=无结论 / 15=业务拒绝；基线 `PAYMENT_MOCK_SCENARIO` 保留；E2E 经 catalog API 造指定价格 SKU 控制金额。
- **CI 门禁（D4）**：PR 快跑不变（e2e 模块默认 `skipTests=true`）；新增 `e2e.yml` nightly + workflow_dispatch + v* tag 强制（起 MySQL/Nacos → 起 9 服务 → `-De2e.env=ci` → 上传 surefire + dump）。
- **flaky 策略（T432）**：不自动重试，连续 flaky `@Disabled` 降级登记；秒杀用例未配置 SKU 时 Assumptions 跳过（防假红 NFR-005）。

---

## [2026-09-07] spec 023：审计中性项收尾——可观测与一致性加固

**范围**：2026-09-07 审计报告中性工程遗留项（安全类按负责人裁决保持桩实现不在范围，Testcontainers/E2E 归 spec 022）。决策与验收见 [spec 023](docs/specs/stage-03-evolution-consolidation/023-audit-ops-remediation/spec.md)。

- **可观测**：删除 Prometheus 死目标 `refund`（:8085 已随 ADR-0064 退役，REFUND 指标由 payment-service 同进程暴露）。
- **优雅停机**：9 领域服务统一 `server.shutdown=graceful` + 30s drain（审计 M5），SIGTERM 不再掐断在途回调。
- **事务收窄（审计 M1）**：`onPaymentSucceeded` 的出站 RPC（confirmStock/履约）移出事务边界，DB 段改 TransactionTemplate 编程式事务；confirm 失败仅告警 + `order.stock_confirm_failed` 指标，不回滚 PAID（事实不回滚，ADR-0054）。
- **文档漂移**：order/payment 系统文档对齐两步式下单与 ADR-0054/0067/019 实况，清除「Proposed 未实施」过时标注；docs 导航补 runbook（基线 H1/H3/H4/H5 全闭环）。

---

## [2026-09-07] spec 022（立项·待实施）：全链路自动化测试体系

**状态**：仅落地文档（spec 四件套 + [ADR-0069](docs/adr/0069-end-to-end-automated-testing.md)），**代码未开发**；决策 D1~D8 见 [spec 022](docs/specs/stage-03-evolution-consolidation/022-full-chain-automated-testing/spec.md)，任务清单见 [tasks.md](docs/specs/stage-03-evolution-consolidation/022-full-chain-automated-testing/tasks.md)（批次 A 已完成，B~G 待实施）。

**范围**：把「在 demo 控制台发起支付/退款 → 观察各系统状态与 DB 数据」变成可重复、可报告、可进 CI 的自动化测试，覆盖**退款功能正常 / 超退能拦截 / 对账准确 / 单号记对**四件事。

- **形态**：新建独立 Maven 模块 `deployment/e2e-tests`（JUnit5 + AssertJ + Awaitility + JDK HttpClient + JDBC，黑盒不依赖业务模块），业务代码零改动。
- **环境**：默认 `-De2e.env=local` 复用本地已起栈；`ci` profile 才用 Testcontainers；数据隔离用唯一业务号前缀 + 按单号过滤。
- **断言**：行级复用现成 `/demo/trace`（跨 9 库 14 表快照），聚合不变量（借贷平衡/退款累计/单号互记/孤儿单）直连 MySQL，沉淀为 `Invariants` 断言原语库。
- **门禁**：不引 SCC/Pact（改做内部 API schema 快照）；PR 快跑（单元+集成+快照+ArchUnit），E2E 与对账差异注入放 nightly。
- **硬约束**：禁断言渠道回调验签（ADR-0025 占位恒放行，会假绿）；对账验收必须走 audit LIVE 模式（MOCK 纯前端）；「对账准不准」拆真实数据一致性与差异检出能力两套断言。

---

## [2026-09-07] spec 020：演示界面设计系统统一——DESIGN.md 单一真相源 + 共享 Token 层

**范围**：mock-channel-web 4 个演示页（portal / demo / cashier / audit）视觉层统一。决策见 [spec 020](docs/specs/stage-03-evolution-consolidation/020-demo-ui-design-system/spec.md)（D1–D5 采纳建议项），设计规范见 [docs/design/DESIGN.md](docs/design/DESIGN.md)。

### 变更
- **设计真相源**：新增 `docs/design/DESIGN.md`（Stripe 风格基底裁剪：靛紫 `#533afd` 主色、weight-300 展示字、tnum 表格数字、pill 按钮）+ `static/design.css` 共享 token 层（157 行，CSS variables + 语义类）；灵感来源 VoltAgent/awesome-design-md（MIT）。
- **状态语义映射收口（FR-004）**：业务状态 → 语义色映射表落 DESIGN.md §2，`st-{STATUS}` / `c-{STATUS}` 动态类名拼接模式不变、样式由 design.css 同名类供给；新增状态 MUST 先登记映射表。
- **四页改造**：收银台加渐变横幅 + 细体大字金额（¥ 格式，tnum）+ pill 按钮分级（实心仅主 CTA）；门户 emoji 换内联 SVG 图标；demo/audit 控制台 token 化 + 表格数字对齐 + dark shell 日志面板统一（`--pa-brand-dark`）。
- **零依赖铁律（NFR-001）**：无 CDN / webfont / 图片 / npm；JS 行为与接口契约零改动（唯一展示性调整为收银台金额格式）。

---

## [2026-09-07] spec 021：统一访问日志——结束时单条 ACCESS + 固定格式含服务名 + 异步 MDC 修复

**范围**：全服务可观测性基建。决策见 [ADR-0068](docs/adr/0068-unified-access-logging.md)，实施见 [spec 021](docs/specs/stage-03-evolution-consolidation/021-unified-access-logging/tasks.md)。

### 核心变更
- **AccessLogFilter**（common-core `accesslog` 包）：`OncePerRequestFilter` + ContentCaching 包装，每请求结束落一条 `ACCESS_LOG` INFO——`method/uri/status/costMs/req/resp` 全字段单行（D2）；异常路径 try/finally 必打；`copyBodyToResponse()` 保证响应完整（NFR-002）。
- **报文口径**（D4）：GET `req=-`；multipart/非文本 `<binary>` 占位；4KB 截断带 `...(truncated,total=N)`；`/actuator/**` 排除；`common.access-log.enabled=false` 整体关闭。
- **脱敏桩**（D3）：`SensitiveBodyMasker` 接口 + `PassThroughBodyMasker` 透传（本期不真脱敏）；服务可覆盖，Filter 零改动。
- **格式固定含服务名**（D5）：logback `springProperty` 注入 `spring.application.name`，全服务行格式统一 `service=<名>`。
- **异步 MDC 修复**（D7）：`MdcTaskDecorator` + `TraceContext.runWithNewTrace()`；ChannelQuery / TimeoutScan / OrderTimeout / Audit 4 个 Scheduler 入口补 traceId，后台日志可全链路捞取。
- **日志查看**（D6）：`deployment/demo/tail-logs.sh`（全栈合并实时视图，[服务名] 前缀）+ `trace-grep.sh <traceId>`（跨服务全链路捞取）。
- **装配定序**：`TraceIdFilter` 显式 `FilterRegistrationBean` order=-200 → `AccessLogFilter` order=-100，14 服务零代码改动自动生效。

### 明确不做（负责人拍板）
- 真脱敏实现（只留桩）；Loki+Promtail 集中采集（后续期）；AOP 方法级日志；FileAppender。


## [2026-09-07] spec 019：order 驱动的两层退款单（TXRF/PMRF）+ 渠道退款异步回调闭环

**范围**：退款链路重设计。决策见 [ADR-0067](docs/adr/0067-order-driven-refund-two-layer-refund-order.md)，实施见 [spec 019](docs/specs/stage-03-evolution-consolidation/019-order-driven-refund/tasks.md)。

### 核心变更
- **两层退款单**：order 库新增 `transaction_refunds`（TXRF+雪花，幂等键=TXRF 可重入）；payment 库 `refunds.refund_no` 改自生成 PMRF+雪花（存量 RF 保留），加 `transaction_refund_no` 双向互记。
- **transactions 补资金口径**：加 `payment_no`（生效支付单，首张成功写入不覆盖）+ `refunded_minor`（累加已退）；`OrderStatus` 补 `PARTIALLY_REFUNDED`/`REFUNDED`。
- **退款异步回调闭环**：Mock 渠道退款改「受理 + 延迟推送」异步模式；payment 新增渠道退款回调端点（HMAC 验签防重放）；`RefundResultProcessor` 三路收敛（同步受理 / 渠道回调 / 人工 resolve）到同一编排；收敛后通知 order（`/internal/orders/on-refund-result`，双号 TXRF+PMRF）。
- **编排归属收口（ADR-0054 延伸）**：业务下游扇出（履约终止 / 权益撤销 / 秒杀回补）归 order 侧；payment 侧删除 `RefundPostProcessOrchestrator` 及 fulfillment/entitlement 直调；权益撤销沿 fulfillment → entitlement 既定链（fulfillment `onRefund` 触发）。
- **下线与修复**：删除 `POST /internal/refunds` 创建入口（resolve 保留）；记账幂等键统一 `REFUND:{PMRF}`（修双重前缀）；`PaymentResultProcessor` 订单通知失败不再静默吞（WARN + 指标）。
- **演示**：mock-channel-web 增加退款回调推送代理（`/mock-channel/refund-callback`）与 TXRF 追踪段；`scenario-refund.sh` 改调 order 入口。
- **迁移**：`019-order-driven-refund.sql`（幂等，已在本地 MySQL 重放验证）。

### 明确不做（负责人拍板）
- 退款 UNKNOWN 自动收敛器（回调丢失靠 resolve 兜底）；resolve 端点 Admin Token 鉴权；部分退款次数上限。

### 回归
- payment-service 135/135、order-service 61/61、fulfillment 21/21 全绿；全仓 `mvn -o clean install -fae` BUILD SUCCESS。


## [2026-09-07] spec 018：表结构列序规范化 + payment_attempts 金额留痕 + 按 order_item 粒度履约

**范围**：全项目 22 张表列序规范化 + 履约粒度升级 + 演示可观测性。决策见 [ADR-0066](docs/adr/0066-schema-normalization-and-item-granular-fulfillment.md)，实施记录见 [spec 018](docs/specs/stage-03-evolution-consolidation/018-schema-normalization-item-fulfillment/spec.md)。

### 变更
- **列序规范化（FR-001）**：统一为「自增 id → 业务主键 → 唯一索引列 → 业务列 → 审计列」；违规 5 张表（payment_attempts / payments / refunds / fulfillments / entitlements 等）经幂等迁移脚本 `deployment/schema/018-schema-normalization.sql`（information_schema 守卫 + PREPARE 动态 SQL，存量库重放两次验证幂等）归位，基线 CREATE TABLE 与 H2 测试 schema 同步。
- **order_item_no 业务单号引入（FR-004 / ADR-0062）**：`BusinessNoType.ORDER_ITEM("OI")`，order_items 第 2 列 + 唯一键；`fulfillments.order_item_id` 语义升级为 OI 单号。
- **按订单明细粒度履约（FR-005 / 消除 null 硬编码）**：`PaymentSucceededRequest` 契约加 `List<ItemLine>`（payment 传 null，order 以本库 order_items 富化后转发，单一事实源）；fulfillment 逐明细建履约，幂等键细化 `(source_payment_no, order_item_id)`；`onRefund` 遍历取消全部 PENDING；每条履约各自触发权益授予（授予链零改动）；`/fulfillments/by-order` 改返回数组。
- **payment_attempts 金额留痕（FR-002）**：加 amount_minor / currency_code（PAYMENT=支付金额；REFUND=所属支付单金额，决策 D2）。
- **演示可观测性**：全链路 DB 数据中文注释（16 调用点）；新增 portal.html 门户主界面（演示 / 对账 / Grafana / Prometheus / 压测五类入口）。

### 附带修复
- 迁移脚本对存量库 `attempt_type` 列（016 时代表尾追加）归位到第 4 列。
- 016 迁移脚本 MariaDB 方言问题（`ADD COLUMN IF NOT EXISTS`）记入代码缺陷待办清单 #13，不在本 spec 修复。

---

## [2026-09-07] v1.0.0 发行形态切换：源码包 → 预构建二进制发行包

**范围**：发行工程，不动业务代码。首个正式版本 tag `v1.0.0`，由 CI（`.github/workflows/release.yml`）自动构建并发布。

### 变更
- **`make-release.sh` → `deployment/release/`**：与发行包启停脚本（start/stop/reset-demo）同居，打包从脚本位置上溯仓库根；CI 引用同步更新。产物为 `payment-platform-<V>-bin.tar.gz`（10 个 fat jar + 一键启停 + 建表 SQL + 演示场景 + k6 压测），目标机器只需 JDK 21 + Docker，零 Maven/构建。
- **退役旧「源码发布包」形态**：删除根目录 `run.sh` / `stop.sh` / `run-tests.sh`（源码包解压入口，`git archive` 快照 + 现场 Maven 全量构建，违背「可直接运行」初衷）；`run-stress.sh` → `deployment/performance/` 与负载生成器同目录。
- **发行包默认向 Nacos 注册 127.0.0.1**（单机包最稳，`PAYMENT_NACOS_IP` 可覆盖）；`restart-payment.sh` 双模式兼容包内 jars/ 与源码仓。
- **RELEASE.md** 重写为二进制发布流程（tag push → CI → make-release.sh → Release 资产）。
- 实机冒烟：冷启动 90s 10/10 服务 UP；happy-path / 退款（幂等重放 + 防超额）场景包内连跑全过。

### 附带修复
- `scenario-refund.sh` 幂等键动态化（原 rk-001/rk-002 写死，重跑命中重放污染断言）；漏网全角括号吞变量两处（`$REFUND_STATUS）`、`$SCENARIO）`）。
- 演示控制台补退款流程与渠道尝试展示（attempt_type=REFUND）；复位脚本对齐 Feature 015 退款并库。
- 根目录脚本清零：仓库根仅保留 Maven 标配（`mvnw` / `pom.xml` / `VERSION`）与文档。

---

## [2026-09-03] 文档与目录治理（审计整改）

**范围**：仅文档与目录治理，不动任何业务代码（例外：`.gitignore` 与 `git rm --cached` 属版本库治理）。对应核心指令「已决策 ADR 在技术方案/系统设计中体现；历史文档归档；系统架构与方案审计；目录规划清晰」。

### 新增
- 宪法 `.specify/memory/constitution.md` 升级 **v2.3.0**：固化 2026-08-30 裁决（§II.2 金额=long 分+currencyCode、Money VO 不启用；§Security.4/§Obs.2 脱敏加本期例外；§ES.1/3/§Obs.3 Checkstyle/Testcontainers/Tracing 标 `[目标]`；§Anti-Goals 撤「不引入 Redis」；新增「文档与代码同步」提交纪律）。顶部追加 Sync Impact Report。
- `docs/adr/0054-core-payment-correctness.md` → **ADR-0054**（确认性：002-payment-order-callback 资金约束）。
- `docs/adr/0055-entry-and-infra-decisions.md` → **ADR-0055/0056/0057**（幂等键由 order 生成 / Nacos 暂不启用·偏离 ADR-0002·待 R1 / 服务未容器化）。
- `docs/adr/0058-performance-baseline.md` → **ADR-0058**（性能基线；Phase 4 实测：读 p99 16.83ms、命令 seckill p99 434ms、DB 卸载 99.98%）。
- `docs/architecture/systems/ledger-service.md`：补齐第 10 篇系统设计（复式记账、4 预置科目、借贷平衡门禁、幂等、FR-001~011、三来源记账接入点）。
- `docs/architecture/technical-solution.md` §9 **ADR 追溯索引**（9.1 P0 内联 / 9.2 P1 索引 / 9.3 ADR 文件三组，全部 `ADR-` 前缀，便于 grep 审计）。
- `docs/architecture/systems/payment-service.md` §7 回调与出站安全；`order-service.md` §8 超时与库存释放（ADR-0043）。
- `docs/archive/audits/`：4 篇历史审计报告归档（2026-08-26/28/30），正文标注 `Status: archived / superseded`。
- `CHANGELOG.md`（本文件）。

### 修正（防漂移）
- `technical-solution.md` 6 处 P0 + 17 处漂移（9→10 服务、骨架→已实现、Money 铁律、Redis 已引入、退款 Ledger 冲正已接入、人工收敛已实现、Nacos/Tracing/Testcontainers/Checkstyle 标 `[目标]`、当前阶段→roadmap 走至 014、Feature 依赖图对齐实际含 008 缺口）。
- `systems/` 8 篇缓存文案（`[待定]` → `[已评估·本期不引入]`；reconciliation 状态机已接线、catalog 库存归 catalog + SkuCache、order 入口强幂等键、payment 撤回「50%打开熔断」等）。
- 根 `README.md`、`deployment/README.md` 端口/服务数/资金变动路径修正；`docs/specs` 3 处路径 `docs/deployment` → `deployment`。
- `docs/adr/` 15 个聚合文件补 53 个 `<a id="adr-XXXX">` 锚点；`README.md` 跳转表扩展至 **ADR-0058**。

### ADR 状态收口
- `0008`（0022/0023 → Accepted）、`0006`（0047 → Accepted）、`0014`（0044 补压测证据）、`0015`（0053 收口为部分完成）。

### 版本库治理
- `.workbuddy/`（含 `memory/` 工作日志）整体退出版本库（磁盘保留，git 不跟踪），避免 AI 工作记忆污染工程变更历史（commit `2b53434`）。
- 压测产物归位 `deployment/performance/`。

### 待负责人二次裁决（未自动执行）
- **R1**：ADR-0056 是否 `Supersedes` ADR-0002（Nacos 暂不启用·偏离）。
- **R2**：payment-service Resilience4j 去留（撤回「50%打开熔断」表述，现状待定）。
- **R3**：ADR-0058 已标「未验证目标」，待生产/压测环境复核。
- **R4**：`catalog-service` / `order-service` 下 `src/.../infra/redis/` 未跟踪目录是否纳入版本库。

### 相关提交
- `72ebeef` 补提交根 README / deployment README 漂移修正（阶段④ 续）
- `3130168` 已决策 ADR 在技术方案/系统设计中体现 + 新立 ADR-0054~0058 + 17 处漂移修正（阶段④）
- `b111334` 修正 technical-solution 与 systems 的 11 处 ADR 冲突（P0）+ 8 篇缓存文案（阶段③）
- `671aad0` 宪法 v2.3.0 + ADR 状态收口 + 跳转表（阶段②）
- `2b53434` .workbuddy 退出版本库，压测产物归位 deployment/performance

---

## [2026-08-31] 文档同步（2026-08-30 裁决落地）

- ADR-0016 回退、ADR-0024/0025 降级为空实现、ADR-0027/0028/0034~0037 代码清理、新增 ADR-0047。
- 全部 spec / 架构 / 运维文档同步（详见 `docs/archive/audits/2026-08-30-project-audit.md` 历史快照说明）。

---

## [2026-08-30] 宪法 v2.1.0 → 裁决固化前置

- 审计发现文档漂移，启动「先核对代码事实再改文档」整改流程，形成本项目防漂移基线。
