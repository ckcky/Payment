# Spec: 033-test-infrastructure（测试基础设施与业务验证体系）

**Feature**：033　**标题**：Test Infrastructure & Business Verification
**版本**：v1.0（Draft，设计轮产物）　**日期**：2026-09-21
**状态**：🟡 **设计完成，待 Architecture Review 与负责人裁决**（本轮**只出设计，不改代码、不建 migration、不加测试、不引入依赖**）
**前置**：030 已合入 master；031/032 的设计已定稿（本文的验证矩阵以它们的目标契约为准绳）
**输入权威**：[stage-design §8](../stage-design.md)、[design-review §12.2 H8/H9/M10/M12、§13 H16](../design-review.md)、
[Constitution §Engineering.3](../../../../.specify/memory/constitution.md)、[engineering-standards §4~§5](../../../../docs/guides/engineering-standards.md)

**阅读约定**：`【现状】`= 已存在的事实（附 `path:line`）；`【目标】`= 设计意图，**尚未实现**；
`【待确认】`= 属人类决策边界，裁决前不得被下游文档当作现行要求引用。

---

## 0. 定位与一句话目标

**把「测试很多」变成「测试可信」：给正确性装上真库载体、给 schema 装上可重放门禁、给业务不变式装上跨层归属，并明确哪些用例由 033 提供基础设施、哪些必须由业务 Feature 自己交付。**

一句话判据：**033 交付的是「能证伪的能力」，不是「绿色的报告」。**

三条主线（对应 design-review 的 H8 / H9 / M10 + stage-design §8.2 T1~T4）：

1. **T1 真库载体**：把已在 030 落地的 Testcontainers-MySQL 模式**泛化为共享基础设施**，覆盖并发 / 唯一键 / 真 DDL 三类今天**结构性证不了**的场景；
2. **T2 迁移可重放**：让「空库全量」与「存量库 + 增量脚本」两条路径**都在 CI 上跑**，并把已写进 018/019/030 注释里的守卫模式**升格为规范 + 门禁**；
3. **T3/T4 业务验证收口**：架构门禁对「运行时 RPC 环」给出裁决（补规则或显式接受并立 ADR）；跨层不变式（借贷平衡、退款不超付、编号链、库存守恒）在**每一层**都有明确归属。

**明确不做**：不做覆盖率指标竞赛、不引入新的测试框架族（Pact / WireMock / Gatling / JMeter 一律不引）、
不把 demo 场景脚本改写成 Java、不为「看起来像大厂」建独立测试平台。

---

## 1. Background：证据化的现状

### 1.1 测试载体：五层已存在，但 L2 全部跑在 H2 上

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | 五层模型已在规范中定义并被实际使用：L1 单元 / L2 单服务集成（`@SpringBootTest` + H2）/ L3 API 快照 / L4 黑盒 E2E / L5 架构门禁 | [engineering-standards §5](../../../../docs/guides/engineering-standards.md)、`deployment/e2e-tests/`、`deployment/architecture-tests/` |
| 2 | L2 的数据源是 **H2 内存库 + MySQL 兼容模式**，每服务自带一份 `src/test/resources/schema.sql`（与 `deployment/schema/*.sql` **并行维护、无同步校验**） | `ledger-service/src/test/resources/application.yml:3`（`jdbc:h2:mem:ledger;MODE=MySQL;...`）、`*/src/test/resources/schema.sql` |
| 3 | 领域 / 应用层大量使用 **`InMemory*Repository` 桩**（生产代码内的实现，测试与「真实 MyBatis 路径」不同源） | `ledger-service/.../infra/InMemoryLedgerRepository.java` 等 **19 个** `InMemory*` 生产类 |
| 4 | 全仓**只有 1 个** Testcontainers 测试类：`LedgerPostingConcurrencyTest`（030/T11 引入），且**无 Docker 时 `assumeTrue` 整体 skip** | `ledger-service/src/test/java/com/payment/ledger/infra/LedgerPostingConcurrencyTest.java:31-49` |
| 5 | 该唯一真库测试是**手搓**的：自建 `MySQLContainer`、手工读 `09-ledger-schema.sql` 并剥掉 `CREATE DATABASE`/`USE`——模式**没有被抽成可复用资产** | 同上 `:52-56` 与 DDL 读取段 |
| 6 | `common-mybatis`（所有 MyBatis 仓储的公共基座）**零测试** | `common/common-mybatis/src/` 下无 `test` 目录 |
| 7 | L3 快照测试是**自研** JSON schema 快照（`api-snapshots` 目录 + `InternalApiSnapshotTest`），非第三方契约框架 | `deployment/e2e-tests/src/test/java/com/payment/e2e/contract/InternalApiSnapshotTest.java`、`src/test/resources/api-snapshots/` |
| 8 | L4 黑盒 E2E **10 个测试类**，含跨库不变式断言（`ledgerBalanced` / `refundNotExceedPaid` / `businessNoChain` / `stockConserved` / `noOrphanRows` / `idempotentReplay`） | `deployment/e2e-tests/src/test/java/com/payment/e2e/support/Invariants.java:17,43,99,126,164,177,184` |

### 1.2 CI：真库路径只覆盖「空库全量」，增量迁移从未被执行

| # | 【现状】 | 证据 |
|---|---|---|
| 9 | `verify` job 在 PR/push 跑 `./mvnw -B verify`（即 L1+L2(H2)+L5），**无 MySQL、无 Docker service** | `.github/workflows/verify.yml:8-24` |
| 10 | `contract-snapshot` job 在 PR 起最小 live 栈（MySQL 8.0 + Redis service container），但**只跑 `InternalApiSnapshotTest` 一个类** | `.github/workflows/verify.yml:26-168` |
| 11 | 完整 L4 E2E **只在 nightly / 手动 / 打 tag 时跑**（45min 超时），PR 不跑 | `.github/workflows/e2e.yml:4-14` |
| 12 | live 栈建表方式：`for f in deployment/schema/[0-9][0-9]-*.sql deployment/schema/027-*.sql` ——**显式跳过 `015/016/018/019/030` 增量脚本**（注释理由：已吸收进全量文件，且它们前置 `USE`/依赖 `DATABASE()`） | `.github/workflows/e2e.yml:76-89` |
| 13 | **结论**：「存量库上执行增量迁移」这条真实升级路径**在 CI 中零覆盖**；`016` 的 MariaDB 方言在 CI 不会被发现 | 同上 + `deployment/schema/016-refund-channel-attempt.sql:7` |
| 14 | demo 场景脚本（7 个 `scenario-*.sh` + `run-all.sh`）**不在任何 CI 里**，靠人跑 | `deployment/demo/`、CI workflow 无引用 |

### 1.3 Schema 可重放：守卫模式已有正确先例，但未成规范、未成门禁

| # | 【现状】 | 证据 |
|---|---|---|
| 15 | 全量 schema 文件普遍用 `CREATE TABLE IF NOT EXISTS`（**27 处**，分布于 01/02/03/04/05/07/08/09/10）⇒ 对**已存在但缺列**的存量库**不做任何事**，静默「表结构看起来在、列不在」 | `deployment/schema/[0-9][0-9]-*.sql` grep 计数 |
| 16 | `016-refund-channel-attempt.sql:7` 用 **`ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 语法错）**，是全仓唯一残留 | `grep` 结果，其余仅在注释中作为**反例**出现 |
| 17 | 018/019/030 已确立**正确先例**：`information_schema` 守卫 + `PREPARE/EXECUTE` 动态 SQL，并在文件头显式写明「禁用 `ADD COLUMN IF NOT EXISTS`（016 教训）」 | `030-payment-attempt-extra-json.sql:13-15,26-33` |
| 18 | **但该先例没有被写进任何规范文档，也没有 lint**——完全靠人翻历史脚本模仿 | `docs/guides/engineering-standards.md` 无对应小节 |
| 19 | `initdb/01-create-databases.sql` 建 **8 个库（含已退役的 `refund`），不含 `ledger`**（code-debt #1，🔴 High）；ledger 库靠 `09-ledger-schema.sql:4` 自带的 `CREATE DATABASE IF NOT EXISTS` 才侥幸可用 | `deployment/initdb/01-create-databases.sql:5-12` |
| 20 | 每个全量 schema 各自 `CREATE DATABASE`，与 initdb **两处权威并存**，是 #19 这类漂移的结构性成因 | `grep CREATE DATABASE deployment/schema/*.sql`（9 处） |

### 1.4 架构门禁：静态边界强，运行时环弱

| # | 【现状】 | 证据 |
|---|---|---|
| 21 | `ServiceBoundaryTest` 有 **10 条** ArchUnit 规则：服务间无编译期依赖 / domain 不依赖 infra 与框架 / 入站不直连持久层 / 禁分布式事务件 / 渠道路由抽象不依赖渠道 infra / `attempt` 表写入口归属 / alipay SDK 收口 / Router 不读 DyeContext + 全服务必须真被 import | `deployment/architecture-tests/src/test/java/com/payment/arch/ServiceBoundaryTest.java:64-309` |
| 22 | 这些规则全部是**字节码静态依赖**断言 ⇒ **拦不住 Feign/Nacos 构成的运行时调用环**（`order ↔ payment` 已知环，backlog #4） | 同上；design-review M10 |
| 23 | L3 快照测试事实上已经在断言**跨服务契约形状**，是最接近「运行时环可见」的资产，但**没有把它当成环治理工具** | `InternalApiSnapshotTest.java` |

---

## 2. Goals / Non-Goals

### Goals

| # | 目标 | 完成判据 |
|---|---|---|
| **G1** | 提供**可复用**的真库测试载体（容器 + DDL 复用 + 数据隔离 + 无 Docker 降级契约），使 031/032/034 的并发与幂等验收能真跑 | 一个共享基座（见 §5.2）+ ≥3 类真库场景在 CI 绿 |
| **G2** | 「空库全量」与「存量库 + 增量」两条路径**都在 CI 上可重放且结果一致** | §6.3 门禁 job；`016` 方言修复；initdb 收口 |
| **G3** | 迁移守卫模式**升格为规范条文 + 机器门禁**，不再靠考古 | engineering-standards 新增小节 + 一条 lint 断言 |
| **G4** | 架构门禁对运行时 RPC 环给出**明确处置**（补规则 / 或显式接受并立 ADR），不再悬空 | §7 二选一落地并回写 backlog #4 |
| **G5** | 业务验证矩阵成型：每类关键场景**指定唯一归属层与归属 Feature**，消除「都以为对方测了」 | §8 矩阵被 spec 031/032/034/035 的 acceptance 引用 |
| **G6** | 测试**归属边界**写清：033 只交付载体与门禁，用例归业务 Feature | §4，且本文件不含任何「033 顺手补齐业务用例」的承诺 |

### Non-Goals（明确不做）

- ❌ 不追求覆盖率数字目标（不设 80% 之类阈值门禁）——覆盖率门禁会直接诱导「为数字写测试」。
- ❌ 不引入 Pact / WireMock / MockServer / Gatling / JMeter / Jest 式新框架族。**契约继续用自研快照**（§1.1#7），
  HTTP 桩继续用 JDK 自带 `HttpServer`（`OutboundResilienceTest` 先例）。
- ❌ 不把 7 个 `deployment/demo/scenario-*.sh` 重写为 Java E2E（重复建设；改做「nightly 里跑一遍脚本」的轻量挂载）。
- ❌ 不做全服务、全表、全字段的「大爆炸式」真库切换（渐进，见 §5.4）。
- ❌ 不建独立测试平台 / 测试数据服务 / 造数流水线。
- ❌ 不新增微服务、中间件或 CI runner 形态（P1 沿用）。
- ❌ 不做混沌工程平台（故障注入 = 代码级 + demo 脚本级，见 §10）。

---

## 3. 测试分层模型（目标态）

> 层数不变（L1~L5），**变化在两处**：① L2 内部拆出「真库子层」；② 每层补一条**「什么情况必须升级到上一层」的判据**。
> 判据缺位是当前主要病灶——很多本应真库验证的东西被写在 H2 层，看起来绿，实际证不了。

| 层 | 载体 | 断言对象 | **必须升级到本层的信号** | 数量级 |
|---|---|---|---|---|
| **L1 单元** | 纯 JVM，无 Spring | 领域不变式、状态机、纯函数（匹配/金额/解析） | 需要跨聚合或需要事务 ⇒ 升 L2 | 多、快 |
| **L2a 单服务集成（H2）** | `@SpringBootTest` + H2 + **真实 MyBatis 仓储** | 应用服务编排、RPC 入站/出站装配、事务边界、SQL 正确性（单语句语义） | **涉及唯一键竞争 / 并发时序 / 方言特性 / 间隙锁** ⇒ 升 L2b | 中 |
| **L2b 单服务集成（真库 MySQL）** | Testcontainers + **生产同一份 `deployment/schema`** | 真 DDL 约束生效、并发下单赢家、`ON DUPLICATE`/`INSERT IGNORE` 行为、`SELECT ... FOR UPDATE` 加锁、JSON 列 | 需要多服务协作 ⇒ 升 L4 | 少而精（**成本最高，只留结构上必需**） |
| **L3 契约快照** | 自研 JSON schema 快照（live 栈） | 跨服务 API 形状、字段增删、RPC 环的**边集合** | 需要业务后果 ⇒ 升 L4 | 每端点 1 份 |
| **L4 黑盒 E2E** | 全量 live 栈 + HTTP + 直连 DB | **跨域资金不变式**（借贷平衡 / 不超退 / 编号链 / 库存守恒 / 无孤儿行） | — | 场景级，nightly |
| **L5 架构门禁** | ArchUnit（静态）+ 显式规则 | 分层依赖、边界归属、SDK 收口、禁 2PC/XA | 运行时行为 ⇒ L3/L2b | 恒定 |

**升级判据的核心一条（写进规范）**：

> 【目标】凡断言「**只会有一个赢家**」「**并发下不双记**」「**唯一约束挡住了**」的测试，
> MUST NOT 停留在 H2 或 `InMemory*Repository` 桩上。桩能证明「撞键后的处理逻辑对」，
> 证不了「真的会撞键且只有一个赢」。此类断言 MUST 落 L2b。

这条正是 030 已踩通的路（`LedgerPostingConcurrencyTest` 的类注释即为该判据的原始表述，
`ledger-service/src/test/java/com/payment/ledger/infra/LedgerPostingConcurrencyTest.java:33-42`），
033 的作用是把它**从一次特例变成一条规则**。

---

## 4. 归属边界：033 交付什么，业务 Feature 交付什么

> **这是本 Spec 最重要的一节。** stage-design 的 T1~T4 若没有这张表，033 会退化成「替所有人补测试」的无底洞，
> 而业务 Feature 会因为「反正 033 会补」而不写验收测试。

### 4.1 033 OWN（本 Feature 独占交付）

| # | 资产 | 形态 | 判据 |
|---|---|---|---|
| O-1 | **真库测试基座** | 一个可被各服务 test scope 依赖的共享模块（`deployment/test-infra`，见 §5.2） | 只有基础设施代码，**零业务断言** |
| O-2 | **DDL 单一来源装配** | 基座从 `deployment/schema/NN-*.sql` 读 DDL，禁止各服务再复制一份 | 断言「测试所用 DDL == 生产 DDL」 |
| O-3 | **无 Docker 降级契约** | `assumeTrue(DockerClientFactory...)` 的统一封装 + **可配置的 fail 模式**（本地 skip / CI fail） | CI 上真库测试**静默跳过 = 红**（§13.3） |
| O-4 | **迁移重放门禁** | CI job：路径 A（空库全量）+ 路径 B（空库全量→再放全量→再放增量）比对 | §6.3 |
| O-5 | **schema lint** | 构建期静态检查：禁 `ADD COLUMN IF NOT EXISTS` / 禁 `01-create-databases.sql` 与 `deployment/schema` 库清单不一致 | §6.4 |
| O-6 | **架构门禁规则演进** | L5 规则补齐/裁决（§7） | — |
| O-7 | **业务验证矩阵与门禁挂载** | §8 矩阵文档化 + demo 脚本挂载 nightly | — |

### 4.2 业务 Feature OWN（033 不代写）

| # | 用例类别 | 归属 | 033 提供 |
|---|---|---|---|
| B-1 | 记账幂等 / 借贷平衡 / 投影一致性 **用例本体** | **031** | 基座 + 「必须落 L2b」判据 |
| B-2 | 账单导入幂等 / 匹配算法 / 差异收口 **用例本体** | **032** | 同上 + fixture 落盘目录约定 |
| B-3 | UNKNOWN 收敛 / 补偿重试 / DLQ 重放 **用例本体** | **034** | 故障注入钩子 |
| B-4 | 指标 / SLO / 告警 **断言本体** | **035** | 指标测试工具方法（读 `MeterRegistry`） |
| B-5 | 渠道契约参数映射 | 030（已完成） | — |

**红线**（写进 033 的 tasks 与 review 清单）：

- 033 的 PR MUST NOT 包含业务断言（形如「退款不超付」「借贷平衡」的具体用例）；
- 业务 Feature 的 acceptance MUST NOT 写「由 033 覆盖」来替代自己的必测项；
- 若某用例需要新类型的测试数据，由业务 Feature 造，033 只保证造数机制可用。

---

## 5. T1：真库测试载体（Testcontainers 渐进）

### 5.1 决策前提与取舍

| 方案 | 内容 | 判定 |
|---|---|---|
| **A 全面切换** | 所有 L2 从 H2 换成 Testcontainers | ❌ 否决：CI 时长与 runner 成本翻倍，且绝大多数用例断言与方言无关——为覆盖率牺牲反馈速度 |
| **B 定向切三类 + 共享基座**（**推荐**） | 仅「唯一键竞争 / 批次与业务号并发 / 真 DDL 断言」三类落 L2b，其余留 H2 | ✅ 命中真实缺口（§1.1#2~#5），成本可控，可渐进 |
| **C 维持现状** | 并发场景继续无自动化覆盖，靠 demo 手跑 | ❌ 否决：与 Constitution「真实 > 全面」冲突；031/032 会新增真库约束与并发路径，缺口只会扩大 |

> 【待确认 H-033-1】引入/放宽 Testcontainers 属**测试载体变更**（Constitution §Engineering.3 现禁止），
> 需负责人裁决（design-review §13 **H16**）。031/032 的验收已把并发真库验证显式登记为「已知缺口」，
> **该缺口的关闭依赖本项裁决**——若不放宽，031 §10 的投影并发累加与 032 §8 的导入幂等
> 只能停留在「无 Docker skip」状态，其 acceptance 须相应降级为手工验证并在 acceptance 里显式记为未验证。

**推荐裁决方向**：放宽为「**仅测试作用域允许，且必须走 §5.2 基座与 §13.3 降级契约**」，
生产依赖树零变化（ArchUnit 的 L5 规则可加一条「`src/main` 不得依赖 `org.testcontainers`」把这条封死）。

### 5.2 基座设计（`deployment/test-infra`）

【目标】新增 **Maven 模块 `deployment/test-infra`**（`test` scope，非运行时组件；放 `deployment/` 下
以符合 AGENTS.md「非领域组件收口 `deployment/`」纪律）：

```text
deployment/test-infra/
  ├── RealMysqlTestSupport     // 容器生命周期（单例复用，见 §5.3）+ Docker 可用性契约
  ├── SchemaBootstrap          // 从 deployment/schema/NN-*.sql 装配指定库；剥 CREATE DATABASE/USE
  ├── RealDb@SpringBootTest   // 组合注解：真库数据源 + 生产 profile 的最小子集
  ├── FaultHooks              // 供 034 的故障注入用例复用（§10）
  └── MetricsAssert           // 供 035 的指标断言复用（读 MeterRegistry）
```

**契约（逐条可验收）**：

| # | 契约 | 理由 |
|---|---|---|
| C-1 | DDL **只能**来自 `deployment/schema/`，MUST NOT 维护测试专用副本 | 消除 §1.1#2 的双份漂移；030 测试类已确立同一取向（`:44-49`） |
| C-2 | 每个测试类**独占一个 database**（同一容器内建 `xxx_t_<类名>`），容器跨类**复用**（Testcontainers singleton / `@Testcontainers(disabledWithoutDocker)` 语义等价） | 隔离 + 控制时长；避免每类起一个 MySQL（约 25~40s/次） |
| C-3 | 时区、字符集、`sql_mode` **与生产 compose 对齐**（`utf8mb4_unicode_ci`、`serverTimezone=UTC`） | 方言差异是假绿主因（design-review 附A 033 风险②） |
| C-4 | 无 Docker：本地 `skip` + 明确 WARN；CI 上 **skip 即红** | §13.3，防「测试全绿是因为没跑」 |
| C-5 | 基座内 MUST NOT 出现任何业务词汇（`payment`/`ledger`/`settlement` 语义） | §4.1 O-1 判据，防止变成垃圾场 |
| C-6 | 与 `deployment/demo` 与 L4 E2E 的**外部 MySQL 不冲突**（不假设端口 3306 空闲） | 双模式互斥守卫先例（ADR-0070） |

### 5.3 首批落地范围（三类，不贪多）

| # | 目标场景 | 为什么 H2/桩证不了 | 关联 |
|---|---|---|---|
| **T1-a** | **幂等唯一键竞争**：并发提交同一 `{eventType}:{sourceId}` 记账事件 ⇒ 恰 1 行、1 套分录 | 唯一约束只在真 DDL 生效；桩是「人为抛 DuplicateKey」模拟 | 031 §9/§10 |
| **T1-b** | **余额投影并发累加**：同账户并发记账 ⇒ `debit_total` 累加值 == Σentries（无丢更新） | `UPDATE ... SET x = x + ?` 的行锁行为在 H2 兼容模式下不可信 | 031 §10 |
| **T1-c** | **批次/业务号并发唯一**：结算 `batchNo`、`attemptSeq` 并发分配不重号 | 依赖真库唯一约束 + 间隙行为 | 032、design-review M12 |

> 【目标】T1-c 的 `attemptSeq` 并发即 design-review 的 **M12**（原为 033 待办，依赖 H8/T1）。
> 这一项 033 OWN **载体**，**用例本体归 payment 域**（§4.2 精神），033 只提供落点与验收挂钩。

### 5.4 逐模块扩展的顺序与退出条件

【目标】扩展顺序按「新增真库约束的概率 × 资金影响」排：
`ledger` → `settlement` → `payment` → `order` → `reconciliation` → 其余（catalog/fulfillment/entitlement 只在出现真实方言问题时才切）。

**不追求全量覆盖**：某模块若无 §3 升级判据命中的场景，MUST 保持 H2（无理由切换即过度设计）。
每模块切换的**退出条件** = ①基座接入 ②至少 1 条 §3 判据命中的用例在真库跑绿 ③H2 原用例零删除（并存，不搬走）。

> ③ 是关键取舍：**不删既有测试**（AGENTS.md 红线「删测试」）。H2 与真库并存看似冗余，
> 实际分工不同（H2 快、证逻辑；真库慢、证约束），且保证切换过程无覆盖倒退。

---

## 6. T2：schema 可重放与迁移门禁

### 6.1 两类缺陷，分开治理

| 缺陷 | 症状 | 根因 | 治理 |
|---|---|---|---|
| **D-A 全量文件不补列** | `CREATE TABLE IF NOT EXISTS` 遇存量表 ⇒ 整段跳过，新列永远不出现 | 用「建表」表达「演进」 | 新列**必须**由独立增量脚本承载，全量文件仅描述「全新库」的目标形状 |
| **D-B 方言不可执行** | `ADD COLUMN IF NOT EXISTS` 在 MySQL 8 语法错 ⇒ 存量库升级直接失败 | 无 lint、无 CI 覆盖 | 守卫模式规范化 + 构建期静态检查 + 路径 B 门禁 |

### 6.2 守卫模式（升格为规范条文）

【目标】把 030 文件头已写下的先例提炼为 `engineering-standards` 新增小节（§"迁移脚本可重放"），条文：

1. **MUST**：增量脚本对「列/索引/表已存在」幂等，统一用 `information_schema` 守卫 + `SET @sql = IF(...)` + `PREPARE/EXECUTE/DEALLOCATE`；
2. **MUST NOT**：`ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS`（MariaDB 方言）；
3. **MUST**：脚本自带 `USE <schema>`；**MUST NOT** 依赖调用方的当前库；
4. **MUST**：文件头写清三件事——作用 / 幂等策略 / **存量行为**（回填 or 显式不回填及理由，先例：`030-payment-attempt-extra-json.sql:17-21` 的「刻意不回填」）；
5. **MUST NOT**：在增量脚本里 `DROP COLUMN`（历史事实列只停写不删，031 §14④ 同一口径）；
6. **SHOULD**：`NN-<feature-slug>.sql` 编号 = Feature 号，与 `docs/specs/<stage>/<feature>` 一一对应。

### 6.3 双路径重放门禁

【目标】CI 新增 job `schema-replay`（廉价：只起 MySQL，不起服务）：

```text
路径 A（空库全量）  mysql < deployment/initdb/01-create-databases.sql
                   for f in deployment/schema/[0-9][0-9]-*.sql deployment/schema/0[1-9][0-9]-*.sql ; do mysql < $f ; done
                   记录 schema 快照 S_A（information_schema 的 tables/columns/indexes）

路径 B（存量演进）  从「上一里程碑基线」恢复（见下），再重放本轮全部增量脚本
                   记录 schema 快照 S_B

门禁              diff(S_A, S_B) 必须为空；非空即失败并打印差异表
可重放性          路径 A 连续执行两遍结果一致（幂等自检）
```

**「上一里程碑基线」的来源（不新造工具）**：`deployment/schema/baseline/<NNN>.sql` ——每个 Feature
交付时，若其增量脚本已被全量文件吸收，则把「吸收前的全量 dump」落为该基线。首个基线即当前 master 全量重放产物，
由门禁 job 首次运行时生成并入库（一次性动作，后续按 Feature 递增）。

> 【待确认 H-033-2】基线文件入库会新增 `deployment/schema/baseline/*.sql`（不是 migration，是**测试夹具**）。
> 需确认不与「本阶段禁止新增数据库 migration」冲突——我的判定：基线是**门禁输入数据**，非可执行迁移，允许；
> 若负责人判为不允许，退化方案 = 路径 B 改为「路径 A 的产物再叠加增量」（无需基线文件，代价是路径 B 无法暴露
> 「全量文件与增量脚本描述不一致」这一类缺陷，检测力下降一档）。

### 6.4 schema lint（构建期，秒级）

【目标】在 `deployment/test-infra`（或独立 `schema-lint.sh`，实现择一，倾向 shell 以对齐 `deployment/` 现有脚本族）加三条静态断言：

| # | 断言 | 现有违规 |
|---|---|---|
| L-1 | `deployment/schema/*.sql` 中不得出现 `ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS` | `016-refund-channel-attempt.sql:7`（**唯一违规，须修**） |
| L-2 | `initdb/01-create-databases.sql` 的库集合 == `deployment/schema/*.sql` 中 `CREATE DATABASE` 的库集合 | #19：initdb 缺 `ledger`、多已退役的 `refund` |
| L-3 | 带 `ALTER TABLE` 的增量脚本必须含 `information_schema` 守卫（出现 `ALTER TABLE` 但不出现 `information_schema` 即红） | 当前 `015-payment-multi-attempt.sql` 需复核（未含守卫字样） |

> 【目标】修复动作（属 033 实现期，非本轮）：L-1 改写 `016` 为守卫模式；L-2 补 `ledger`、
> 并把 `refund` 标为**遗留**（是否删除属人类决策，见 H-033-3）；L-3 补 `015-payment-multi-attempt.sql` 守卫。

---

## 7. T3：架构门禁对「运行时 RPC 环」的裁决

**问题**：§1.4#22——L5 只能看见编译期依赖，`order ↔ payment` 的 Feign 环对 L5 完全不可见（backlog #4）。

【目标】两条路，**二选一，不并列**：

| 选项 | 内容 | 代价 | 判定 |
|---|---|---|---|
| **R-A 补静态规则** | ArchUnit 扫 `@FeignClient` 注解与其 `value/name` 目标服务，构造**调用边图**，断言「允许图之外的边不存在」，并对已知环显式登记为白名单 | 实现中等（注解可静态读出）；**但白名单等于承认环合法**，规则只防「新增边」 | ✅ **推荐**：能拦住增量，成本一次 |
| **R-B 显式接受** | 立 ADR 承认「order↔payment 运行时环是编排者模式的固有代价」，不再试图用门禁拦 | 零实现成本，但 backlog #4 长期挂着 | 备选 |

**推荐 R-A**，且规则形态必须是「**允许清单式**」而非「禁止环检测」：

- 从各服务 `infra/client/*FeignClient` 静态抽取 `(调用方服务, 目标服务, 端点)` 三元组 ⇒ 生成 `rpc-edges.txt`（**进 git**，作为契约资产）；
- 门禁断言：三元组集合**只能等于**基线；新增边需同步更新基线（⇒ 强制 review 时讨论「这条边该不该有」）；
- 副产品：`rpc-edges.txt` 天然是 [technical-solution.md](../../../../docs/architecture/technical-solution.md) 调用图的事实来源，可顺带修 D-4 类文档漂移。

> 这一条不新增依赖（ArchUnit 已在 reactor 内）、不新增服务，符合 P1。

---

## 8. 业务验证矩阵（核心交付物）

> 读法：**行**=要验证的业务事实；**列**=落在哪一层；**Owner**=谁写用例（033 只写载体）。
> `L2b` 命中项 = 今天的结构性盲区（现在完全没有或写在错误的层）。

### 8.1 Payment 生命周期与渠道

| 场景 | L1 | L2a | L2b | L4 E2E | Owner |
|---|---|---|---|---|---|
| `CREATE→PAYING→SUCCESS/FAILED` 状态机合法转移 | ✅ | | | | payment 域（030 已覆盖） |
| 渠道超时 → `UNKNOWN`（**不猜成败**） | ✅ | | | ✅ `scenario-payment-unknown.sh` | payment |
| `UNKNOWN` → 主动查询 → `SUCCESS` / `FAILED` / **仍 UNKNOWN** | | ✅ | | ✅ | 034 |
| 迟到成功不覆盖已终态（终态吸收） | ✅ | ✅ | | | payment（已有 `TerminalConflictTest`） |
| 染色 `SANDBOX` 反向路径按记录还原模态 | | ✅ | | | 030 已覆盖（`ReversePathDyeModeTest`） |
| 重复支付（双成功）由 Transaction 层判 surplus 并自动退款 | | ✅ | | ✅ | order（**只验证，不重设计**，见 §11） |

### 8.2 Duplicate（四同）

| 场景 | L1 | L2a | **L2b** | L4 | Owner |
|---|---|---|---|---|---|
| 重复**请求**（同幂等键下单/支付） | | ✅ | ✅ | ✅ `IdempotencyAndCallbackE2ETest` | payment/order |
| 重复**回调**（同渠道流水号多次通知） | | ✅ | | ✅ | payment |
| 重复**事件**（MQ 同 `msgId` 重投） | ✅ | ✅ | | | 034 |
| 重复**记账**（同 `{eventType}:{sourceId}`） | | ✅ | **✅ 必须** | | **031**（T1-a 载体由 033 提供） |

### 8.3 Concurrency

| 场景 | 载体 | Owner | 今天状态 |
|---|---|---|---|
| 两个 Payment 同时 SUCCESS（同 Transaction） | L2b + L4 | order/payment | 【现状】仅 L2a 桩（`TransactionCallbackConflictTest`），真竞争未覆盖 |
| duplicate auto-refund 并发触发只退一次 | L2b | order | ❌ 未覆盖 |
| 并发退款（同 TXRF） | L2b | payment-refund | ❌ 未覆盖（034 补） |
| 并发记账同账户投影累加 | **L2b** | ledger | ❌ 未覆盖（031 §10 显式登记缺口） |
| `attemptSeq` 并发分配不重号 | **L2b** | payment | ❌ 未覆盖（M12） |
| 结算批次并发创建唯一 | **L2b** | settlement | ❌ 未覆盖 |
| 订单入口幂等并发 | L2a | order | 【现状】**已有** `OrderEntryIdempotencyConcurrencyTest` |
| 限流器并发 | L2a | order | 【现状】已有 `RateLimiterConcurrencyTest` |

> 观察：已有的两个「并发」测试都是 H2 + 桩 ⇒ 它们证明的是**应用层逻辑**，
> 与 §3 判据要求的「真库只有一个赢家」不是同一件事。033 不推翻它们（红线：不删测试），
> 只在其上补 L2b 的同类断言。

### 8.4 UNKNOWN / Refund / Ledger / Reconciliation / Settlement / MQ

| 域 | 必须覆盖 | 层 | Owner |
|---|---|---|---|
| UNKNOWN | 老化分桶、人工收敛端点、**永不自动终态化**（显式否决） | L2a + L4 | 034 |
| Refund | SUCCESS / FAILED / UNKNOWN 三路、重复请求、重复回调、重试、补偿、超退守卫 | L2a/L2b/L4（已有 `OverRefundGuardE2ETest`） | 034 + payment-refund |
| Ledger | `ΣDEBIT == ΣCREDIT`（构造期 + 真库落盘双证）、幂等、分录不可变、投影 rebuild 一致、关账拒收 | L1/L2a/**L2b**/L4（已有 `Invariants.ledgerBalanced`） | 031 |
| Reconciliation | MATCHED / `AMOUNT_MISMATCH` / `STATUS_MISMATCH` / `CHANNEL_ONLY` / `PLATFORM_ONLY` / 重复导入不重复产差 | L1/L2a/**L2b**/L4（已有 `ChannelStatementDiffE2ETest`、`ReconciliationAccuracyE2ETest`） | 032 |
| Settlement | 只用已确认财务事实、重复结算、失败重试、门禁 fail-closed | L2a/L2b/L4（已有 `SettlementGateE2ETest`） | 032 + 034 |
| MQ | half-message 回查、消费重试 1s/2s/4s、**超次进 DLQ**、DLQ 重放、幂等消费 | L2a（已有 4 个 `common-redis-mq` 测试）+ L4 | 034（**029 已实现，不重设计**，见 §11） |

### 8.5 契约测试（L3）范围

【目标】契约快照补齐至「**每个跨服务写入口 1 份 + 每个财务事实读出口 1 份**」，不追求全端点：

| 契约 | 边 | 关联 |
|---|---|---|
| Payment → Ledger `accounting-events` | 事件入站（031 新契约） | **031 必登记** |
| Ledger → 各调用方 `trial-balance` / `balances` | 投影读 | 031 |
| Payment/Refund → `confirmed-facts` | 事实读（032 补 `merchantId`/`period`） | **032 必登记** |
| Reconciliation → Settlement summary | 结算事实来源 | 032 |
| Payment ↔ Channel SPI（渠道端口） | 端口形状 | 030 已覆盖（`PaymentChannelContractTest`） |
| Reconciliation ↔ Statement Loader | 账单标准化行 | 032 |

**高基数与敏感字段禁令在契约层的落点**：快照文件 MUST NOT 含真实密钥/完整渠道报文（§035 同步约束，033 提供快照脱敏检查）。

---

## 9. 测试数据策略与隔离

| # | 规则 | 理由 / 现状冲突 |
|---|---|---|
| 1 | **每个测试自己造数，禁止共享可变 fixture**（只读 seed 除外） | `deployment/demo/seed.sh` 是演示权威，测试 MUST NOT 依赖它（否则 demo 改数据全线红） |
| 2 | 命名带运行标识：业务号前缀含 `runId`/类名哈希 | 已有先例：每实例 `runId`（030 基类行为） |
| 3 | L2b 用 §5.2 C-2 的「类内独占 database」；跨类不共享数据 | 唯一约束断言的前提 |
| 4 | 金额一律 `amountMinor` 整数，**测试中同样禁 `double`** | AGENTS.md 红线；`MoneyInvariantTest` 已有基座 |
| 5 | 时间：需要「现在」的用例走可注入 Clock；不得依赖 wall-clock 秒级巧合 | `Await.java`（L4 轮询）是黑盒补偿，白盒测试不应模仿 |
| 6 | 外部依赖（渠道/SDK/HTTP）用 JDK `HttpServer` 替身，不引入 MockWebServer/WireMock | 先例 `OutboundResilienceTest`（settlement）；§2 Non-Goals |
| 7 | 固定签名向量（RSA2/HMAC）以**测试常量**形式入 `common-core` 测试资源，禁真实密钥 | 030 已确立（`AlipayNotifySignatureVerificationTest`） |

---

## 10. 故障注入策略

【目标】三级，**不建平台**：

| 级别 | 手段 | 覆盖 | 使用者 |
|---|---|---|---|
| **F-1 代码级** | 仓储/网关桩按开关抛异常或返回 UNKNOWN（现有 `InMemory*Repository` + `ReliabilityConfig` 风格） | 分支收敛、后置失败、指标 | L1/L2a，034 用例 |
| **F-2 真库级** | `test-infra` 的 `FaultHooks`：事务中途失败、约束冲突、慢查询（`SELECT SLEEP`） | 事务边界、回滚、锁竞争 | L2b，031/032/034 |
| **F-3 演示级** | `deployment/demo/fixtures/audit/audit-faults.sql` 与故障注入开关（金额尾数注入） | 端到端后果与可观测表现 | L4 + 手工 |

**判据**：任何「后置动作失败不得回滚前序事实」类断言 MUST 至少有一条 F-2 或 F-3 用例（H2 单语句环境证不了事务边界）。
现有直接先例：`ReconciliationNoFactMutationTest`、`PaymentPersistenceIdempotentRaceTest`。

---

## 11. 与既有 Feature 的禁令耦合

| 对象 | 禁止 | 允许 |
|---|---|---|
| **030 渠道** | 重设计渠道契约 / 模态 / 染色 / 验签 | 把 030 的**既有测试模式**（固定签名向量、真库并发类）泛化为基座 |
| **029 Redis Streams MQ** | 重新设计 MQ、换中间件、改半消息协议 | 为「消费重试耗尽 / DLQ 重放」补**测试载体与用例落点**（用例本体归 034） |
| **C-11 duplicate payment auto-refund** | 新增/改动业务流程、加「禁止多 SUCCESS」约束 | **只验证**：幂等、并发、自动退款、UNKNOWN 恢复、账务一致性（§8.2/§8.3 对应行） |
| **031 Ledger** | 在 033 里写账务断言用例 | 提供 T1-a/T1-b 载体与 L2b 判据 |
| **032 Reconciliation** | 同上 | fixture 目录与可重放基线约定 |
| **034 Reliability** | 同上 | `FaultHooks` |
| **035 Observability** | 同上 | `MetricsAssert` |

---

## 12. 环境策略

| # | 项 | 【目标】规则 |
|---|---|---|
| 1 | JDK | 21 权威（根 `pom.xml` `<java.version>`）。本地以 JDK 21/26 运行 MUST 结果一致；CI 固定 temurin 21 |
| 2 | 无 Docker 开发机 | L2b `skip` + 汇总行显式打印「N 个真库测试被跳过」；**不得静默** |
| 3 | CI 真库测试 | MUST 有 Docker（GitHub `ubuntu-latest` 自带）；无 Docker ⇒ job 直接 fail 而非 skip（§13.3） |
| 4 | 顺序无关 | 任何测试类可单跑、可乱序（`-Dsurefire.runOrder=alphabetical` 与随机各跑一次） |
| 5 | 时区 | 容器与测试统一 UTC；涉及 `period=YYYY-MM` 派生（031 §11）的用例 MUST 断言时区敏感行为 |
| 6 | 双模式栈 | L2b MUST NOT 依赖宿主 3306（§5.2 C-6），不与 `start-all.sh` / `start-container.sh` 抢端口 |
| 7 | 构建产物 | 测试报告落 `deployment/output/`（AGENTS.md 目录纪律），禁污染仓库根 |

---

## 13. CI 策略

### 13.1 分层执行（目标态）

| 触发 | 内容 | 预算 |
|---|---|---|
| **PR（快层）** | `verify`（L1+L2a+L5）+ `contract-snapshot`（L3 一类）+ **新增 `schema-lint`（§6.4，秒级）** | ≤10min |
| **PR（真库层，新增）** | `real-db`：只跑 §5.3 三类 L2b | ≤8min（容器复用，见 C-2） |
| **PR（重放门禁，新增）** | `schema-replay`（§6.3 双路径 + diff） | ≤6min |
| **nightly** | 完整 L4 E2E（现状保留）+ **demo `run-all.sh` 挂载** + `mvnw verify -Drerun随机序` | ≤45min |
| **release（打 tag）** | 全量 + 手动执行清单（沙箱真机） | — |

【目标】demo 脚本挂载 nightly 的方式 = 一条 step：起栈 → `./run-all.sh` → 断言退出码 + 关键 `ACCESS_LOG` 存在；
不重写为 Java（§2 Non-Goals）。

### 13.2 门禁挂载点清单

- `mvnw -B verify`：L1/L2a/L2b/L3/L5（reactor 内，L2b 依赖 §5.3 三类）；
- `.github/workflows/schema.yml`（新增，或并入 `verify.yml`）：L-1~L-3 + 双路径重放；
- 文档漂移门禁：沿用 engineering-standards §11 清单（033 不新增机制，只加一条「`rpc-edges.txt` 与文档调用图一致」）。

### 13.3 假红 / 假绿双向禁令（本 Feature 的纪律核心）

| 禁令 | 内容 | 违反后果 |
|---|---|---|
| **禁假绿①** | CI 上真库测试被跳过 ⇒ job 红（可配置 `realdb.required=true`） | 「全绿但其实没跑」是 §0 判据的直接对立面 |
| **禁假绿②** | `deployment/schema` 与测试内嵌 DDL 不一致 ⇒ 红（§5.2 C-1 保证不可能，仍需断言兜底） | 双份漂移 |
| **禁假绿③** | acceptance 里「未跑通」的项 MUST 显式记为**未验证范围**，MUST NOT 因未跑而默认通过 | 沿用 stage-design §3.4 C-5 口径 |
| **禁假红** | 无 Docker 本地开发 MUST NOT 被真库测试卡住（skip + WARN） | 否则规范会被绕过 |
| **禁迎合** | 不得为了让门禁绿而修改既有断言/删除测试（AGENTS.md 红线） | — |

---

## 14. 交付切片（实现期顺序，供 tasks.md 用）

| 切片 | 内容 | 依赖 |
|---|---|---|
| **033-A**（地基） | `deployment/test-infra` 基座（§5.2 C-1~C-6）+ `schema-lint`（§6.4 L-1~L-3）+ `016` 方言修复 + initdb 补 `ledger` | **H-033-1 裁决** |
| **033-B**（门禁） | `schema-replay` 双路径 job + 基线文件（H-033-2）+ CI 三处挂载（§13.1） | A |
| **033-C**（首批真库） | §5.3 T1-a/T1-b/T1-c 三类落点（**用例本体由 031/032/payment 域交付，033 交付落点与绿灯环境**） | A；与 031/032 实现并行 |
| **033-D**（架构门禁） | R-A 规则 + `rpc-edges.txt` 基线（§7） | 无（可独立先做，**唯一不依赖 H-033-1 的切片**） |
| **033-E**（nightly 增强） | demo 脚本挂载 + 随机序 + 跳过可见性（§13.3 禁假绿①） | A/B |

> 【目标】**033-D 可提前于其他切片做**，且它是四项里唯一「不依赖任何人类裁决、零新依赖」的——
> 若负责人希望先见效，从 D 起。

---

## 15. Human Decisions Required

| # | 决策项 | 边界类型 | 影响 | 推荐 |
|---|---|---|---|---|
| **H-033-1** | 是否放宽「不引入 Testcontainers」，允许**仅测试作用域**使用（= design-review §13 **H16**） | 测试载体变更 | 决定 033-A/B/C 是否成立；决定 031 §10 投影并发与 032 导入幂等的 acceptance 形态 | **放宽**（并加 L5 规则封死 `src/main`） |
| **H-033-2** | `deployment/schema/baseline/*.sql` 门禁基线是否入库 | 数据资产新增（非 migration） | 决定路径 B 检测力 | 入库 |
| **H-033-3** | 已退役的 `refund` 库与 `06-refund-schema.sql`：删除 / 保留标注遗留 | 数据库结构变更 | L-2 门禁的具体口径 | **保留 + 标注遗留**，删除留待独立 chore（避免误伤存量库） |
| **H-033-4** | 运行时 RPC 环取 R-A（补允许清单规则）还是 R-B（显式接受立 ADR） | 门禁口径 / 架构取舍 | §7、backlog #4 关闭方式 | **R-A** |
| **H-033-5** | CI 真库测试「skip 即红」是否作为强制门禁（vs 允许红但仅提示） | 交付节奏 | §13.3 禁假绿① | 强制 |

---

## 16. Risks & Mitigations

| # | 风险 | 后果 | 缓解 |
|---|---|---|---|
| 1 | Docker 在部分开发机/CI 不可用 | 真库测试静默消失，覆盖倒退而无人知 | §12#2 跳过必须可见 + §13.3 禁假绿① |
| 2 | 真库测试拖慢 PR 反馈 | 开发者绕过门禁 | 独立 `real-db` job（并行不串行）+ 容器跨类复用（C-2）+ 只三类 |
| 3 | H2 与真库双份用例长期并存成维护负担 | 复制粘贴腐化 | 明确并存语义（§5.4 ③），且真库用例只断言约束、H2 用例只断言逻辑，重叠面收敛 |
| 4 | 基线文件（H-033-2）过期未更新 ⇒ 路径 B 永远绿 | 门禁假绿 | 基线文件名带 Feature 号，与 `docs/specs/<feature>` 对应；新增 Feature 未更新即 L-2/重放红 |
| 5 | `rpc-edges.txt` 允许清单被随手扩大 | §7 形同虚设 | 变更该文件 MUST 在 PR 描述中说明「为何需要新边」，并进 review 清单 |
| 6 | demo 脚本挂 nightly 引入不稳定（环境依赖、沙箱外网） | nightly 假红、信任流失 | 只挂**离线可跑**的场景（happy path / audit / mq / reconciliation / refund）；涉沙箱的排除 |

---

## 17. Acceptance Criteria（设计轮的可验收物）

| # | 判据 | 验证方式 |
|---|---|---|
| AC-1 | §8 矩阵每一行有明确 Owner，且无一行落在「无人负责」 | 逐行 review（实现期 tasks.md 引用） |
| AC-2 | §4 归属边界写入 engineering-standards，且 031/032/034/035 的 acceptance 引用本矩阵 | 文档链接检查 |
| AC-3 | §6.2 六条迁移规范条文可判定（能被 lint 或 review 直接判真假），无「应当尽量」式表述 | review |
| AC-4 | §13.1 分层执行表与现有 3 个 workflow 逐 job 对照，未删除任何既有 job、未改变既有触发语义 | 与 `verify.yml`/`e2e.yml`/`release.yml` 对照 |
| AC-5 | 五项人类决策（H-033-1~5）各自附推荐与不裁决的后果 | review |
| AC-6 | 本 Feature 设计**未**重设计 030/029/C-11（§11 逐条对照） | review |
| AC-7 | 设计未引入 §2 Non-Goals 列出的任何新框架/平台 | 检查依赖清单变化 = 0 |

---

## 18. Out of Scope（明确不做，防范围蔓延）

- ❌ 覆盖率数字门禁、变异测试（PIT）、性能基准测试平台建设（`deployment/performance` 的 k6 资产维持现状，不扩不删）。
- ❌ 引入 Pact / WireMock / MockServer / TestBench / 混沌平台 / 自建测试编排服务。
- ❌ 全服务全表真库化（§5.4 只按判据定向切）。
- ❌ 删除或合并既有 H2 用例与 `InMemory*Repository`（并存；`InMemory*Repository` 的存废属业务 Feature 重构议题）。
- ❌ 重设计 MQ、渠道、账务、对账的任何业务行为。
- ❌ 真实渠道沙箱进 CI（ADR-0076 R10；真机验证仍为手工 + 未验证范围显式登记）。
- ❌ 分库分表 / 多数据源 / 读写分离相关测试基建（无此架构）。
