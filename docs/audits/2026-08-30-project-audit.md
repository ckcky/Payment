# PaymentArch 项目审计报告

**审计日期**：2026-08-30
**审计范围**：当前进度 / 功能实现 / 代码质量 / 架构合理性 / 文档一致性
**审计方法**：全量构建验证 + 静态代码扫描 + 文档交叉核对 + 上次审计（2026-08-28）整改项闭环追踪
**审计基线**：`constitution.md` v2.1.0、`roadmap.md`、`technical-solution.md`、spec 001~010

> 📌 **历史快照说明**：本报告的结论对应 **2026-08-30 的工作区状态**。其后的 2026-08-31 文档同步（负责人 2026-08-30 裁决落地）已完成——ADR-0016 回退、ADR-0024/0025 降级为空实现、ADR-0027/0028/0034~0037 代码清理、新增 ADR-0047，全部 spec / 架构 / 运维文档已同步。**当前权威口径请以 `docs/adr/README.md`、`docs/architecture/technical-solution.md` §2.4 与各 spec 文档为准**，本报告保留为审计留痕。

---

## 0. 结论速览

| 维度 | 评级 | 一句话结论 |
|---|---|---|
| **可构建性** | 🟢 优 | 15 个 reactor 模块全绿，290 测试 0 失败 |
| **代码质量** | 🟢 优 | 分层干净、无假绿测试、金额处理规范 |
| **架构合理性** | 🟢 良 | 边界清晰、ArchUnit 有防空转守护；无服务发现是已知取舍 |
| **功能进度** | 🟡 中 | 主链 001~007 均已落地并接入记账；安全能力按决议裁剪 |
| **文档一致性** | 🟡 中 | 4 处漂移均已定位；另有 1 份未入库文档存在技术描述错误但风险判断成立（§4.3） |
| **可运行性** | 🔴 差 | Schema 未挂载 + 无 Docker，服务实际起不来 |

**最需要立刻处理的两件事**：
1. **提交或回退工作区 37 个未提交文件**（见 P1-1）——本轮审计中已修复其中 2 处悬空引用使构建恢复，但改动仍未入库，随时可能再次劣化。
2. **引入 Flyway 挂载 schema**（见 P1-2）——这是"服务跑不起来"的直接根因，也是宪法"可运行性"条款未达标之处。

---

## 1. 构建与测试基线（实测数据）

**命令**：`mvn -o clean verify -fae`（离线、fail-at-end）
**结果**：`BUILD SUCCESS`，15/15 reactor 条目 SUCCESS，无编译错误、无测试失败

| 模块 | 测试数 | 模块 | 测试数 |
|---|---:|---|---:|
| common-core | 32 | refund-service | 29 |
| common-dto | 3 | fulfillment-service | 13 |
| common-mybatis | **0** | entitlement-service | 19 |
| merchant-service | 10 | reconciliation-service | 27 |
| catalog-service | 9 | settlement-service | 31 |
| order-service | 18 | ledger-service | 15 |
| payment-service | 78 | architecture-tests | 6 |

**合计 290 个测试，0 失败 / 0 错误 / 0 跳过。**

### 测试质量抽查（防假绿）

对全部测试文件做断言密度扫描，结论：**无假绿测试**。

- 初检标记 11 个"零断言"文件，经复核全部为 `*ApplicationTests.contextLoads()` —— Spring Boot 标准实践，其断言是隐式的（应用上下文装配成功即通过），**属于误报**。
- 另 2 个初检告警（`InternalServiceAuthTest`、`ModuleBoundaryTest`）分别使用 MockMvc `.andExpect()` 与 ArchUnit `.check()` 流式断言，同为检测脚本口径缺陷导致的误报。

> 附：本次审计使用的初检正则未覆盖 MockMvc/ArchUnit 断言风格，已在报告中修正判定，避免后续复查重复踩坑。

---

## 2. 问题清单

### P0 · 阻断级（本轮审计中已修复）

| # | 问题 | 证据 | 状态 |
|---|---|---|---|
| **P0-1** | **悬空引用导致 9 个服务 Spring Context 加载失败**<br>裁剪删除 `FeignInternalTokenAutoConfiguration` 后，`AutoConfiguration.imports` 仍声明该类，Spring 启动期抛 `ClassNotFoundException` | `common/common-core/.../AutoConfiguration.imports` | ✅ 已修复 |
| **P0-2** | **编译失败：`RiskCheckService` 已删但仍有注入**<br>`PaymentApplicationService` 保留 import / 字段 / 构造参数 / `noRiskCheck()` 工厂方法 | `PaymentApplicationService.java` | ✅ 已修复 |
| **P0-3** | **测试与实现契约脱节（9 条失败）**<br>鉴权与验签已降级为占位放行，但 `ChannelCallbackSecurityTest`、`InternalServiceAuthTest` 仍断言 403/503 | 首次 `verify` 结果：9 Failures | ✅ 已同步 |

**修复说明**：以上三处均为"删除不彻底"的半成品裁剪所致，方向符合 2026-08-30 负责人决议（鉴权/风控/验签改预留空实现），**非设计缺陷**。修复方式：
- 移除 `AutoConfiguration.imports` 中失效声明；
- 摘除 `PaymentApplicationService` 中全部 `RiskCheckService` 引用（含 import、字段、构造参数、`noRiskCheck()`）；
- 将两个测试**契约同步为占位放行**，同时保留不可退化的结构性断言（鉴权挂点仍注册于 `/internal/**`、回调路径不被内部鉴权误拦、业务处理确实被调用），并在 Javadoc 中写明"实现真实鉴权后须整体反转"及反转清单出处。

---

### P1 · 应尽快处理

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| **P1-1** | **工作区 37 个文件未提交，净减 625 行**（383 增 / 1008 删），含 `docs/architecture/next-stage-design.md` 未跟踪 | 裁剪改动暴露在悬空引用风险下（P0-1/P0-2 即由此产生）；无法回滚对比 | 校验后**立即提交**，或明确回退。改动方向（鉴权/风控空实现化 + 移除部分退款）与决议一致，建议入库并写清 commit message |
| **P1-2** | **Schema 未挂载，运行期无表**<br>9 个 `deployment/schema/*.sql`（含 `09-ledger-schema.sql`）齐备，但项目无 Flyway/Liquibase；`deployment/README.md:57` 明确"不挂载、不自动执行" | **服务起不来的直接根因**；违反宪法"可运行性"；新环境需手工建表，易漏 `09-ledger` | 引入 Flyway，各服务放 `src/main/resources/db/migration/V{n}__{desc}.sql`（README 已推荐此路径但未执行） |
| **P1-3** | **CI 无静态检查**<br>`pom.xml` 无 checkstyle / spotless；`.github/workflows/verify.yml` 仅执行 `./mvnw -B verify` | 编码规范无机器门禁，靠人工约定 | 至少接入 checkstyle 并纳入 CI；与宪法"可演进"条款相关 |
| **P1-4** | **`systems/` 缺 `ledger-service.md`**<br>9 份系统文档覆盖 merchant/catalog/order/payment/refund/fulfillment/entitlement/reconciliation/settlement，唯独缺资金核心 | ledger 已实现 961 行且有独立 spec，文档覆盖不完整 | 补写，对齐其余 9 份的结构口径 |

---

### P2 · 可排期处理

| # | 问题 | 说明 |
|---|---|---|
| **P2-1** | **Nacos 声明漂移** | 技术方案 §1 架构基线与 §5 技术栈表仍列 Nacos，代码侧引用数为 **0**（无注册中心、Feign 走直连）。§5 已标 `[目标]`，但 §1 表述易被误读为既有能力 |
| **P2-2** | **Javadoc 引用已删类** | `InternalServiceAuthInterceptor.java` 注释引用已移除的 `InternalTokenRequestInterceptor`、`FeignInternalTokenAutoConfiguration`；仅注释，不影响运行。**2026-08-31 复核：该项含义已变——新 Javadoc 是"已随本次决议移除的实现"清单，属**有意保留的回溯说明**（告诉后人这里原本有什么、为什么没了），不再是残留漂移，视为已闭环。** |
| **P2-3** | **common-mybatis 零测试** | 15 个模块中唯一无测试者（上次审计已记为 P2-12，未闭环） |
| **P2-4** | **`next-stage-design.md` 对账指控技术描述有误，但风险属实** | 指控"batch 生命周期是死代码"**不成立**（四个方法均有调用）；指控"CSV loader 忽略 period"**描述不准**（是回退非忽略），但因 fixture 只有 `sample.csv`，**每个周期实际加载同一份账单，"假对账"风险真实存在**。详见 §4.3 |
| **P2-5** | **对账 fixture 未按周期切分** | `fixtures/channel-statements/` 下**只有 `sample.csv`**，缺按周期命名的账单文件。后果：任意 `period` 都走回退分支，**每个周期对出同一份结果**，对账功能在演示/验证时实质失效。与 P2-4 同源，但这是**可独立修复的具体缺口** |
| **P2-6** | **无分页 / 大表遍历** | 沿用上次审计结论，数据量小暂无影响 |

---

## 3. 上次审计（2026-08-28）整改闭环追踪

| 编号 | 整改项 | 状态 | 核实依据 |
|---|---|---|---|
| P0-1 | 金额裸元组 → `Money` VO + 溢出防护 | ✅ **已闭环** | `Money.java` 存在；`addExact/multiplyExact` 覆盖 3 个文件；主源码无 `float`/`double`/`BigDecimal` 金额运算 |
| P0-2 | 无重试 / 退避 / 超时熔断 | ✅ **已闭环** | `ResilientFulfillmentGateway`（指数退避 max 3 / 200ms 起）+ Feign `connect-timeout: 2000` `read-timeout: 5000` + Resilience4j 熔断 |
| P0-3 | 事务内同步 RPC 占连接 | ✅ **已闭环** | `PaymentPersistence` 拆为 `insertPending` / `applyAndPersist` 两个短事务；`channel.charge` 与 `fulfillment` RPC 已移出 DB 事务 |
| P1-6 | 全站无输入校验 | ✅ **已闭环** | `CreatePaymentRequest` / `ResolveRequest` 加 `@NotNull`/`@Positive`/`@Pattern`，Controller 加 `@Valid` |
| P0-4 / P1-5 | 鉴权 / 验签 | ⚠️ **反向变更** | 按 2026-08-30 决议**主动降级**为占位空实现，非遗漏。已在技术方案 §2.4 与 ADR-0009/0011 文档化 |
| P1-7 | Nacos 声明未用 | ⚠️ **未闭环** | 代码 0 引用，文档仍声明 → 转为 P2-1 文档漂移 |
| P1-8 | Schema 未挂载 | ❌ **未闭环** | 转为 P1-2 |
| P1-9 | CI 无 lint | ❌ **未闭环** | 转为 P1-3 |
| P2-11 | infra→application 反向依赖 | ⚪ **上次审计误判** | `infra/client/*FeignClient` 实现 `application` 层定义的 Gateway 接口，是标准**依赖倒置**（domain/application 定义接口、infra 提供实现），方向正确，**不构成问题**。建议从整改清单移除 |
| P2-12 | 无分页 / common-mybatis 零测试 | ⚠️ **部分闭环** | 分页未做（P2-5）；common-mybatis 仍零测试（P2-3） |

**闭环率**：4/10 完全闭环，1 项误判，1 项部分闭环，2 项未闭环（已转入本次 P1），1 项按决议反向变更。

---

## 4. 代码与架构合理性

### 4.1 正面评价（值得保持）

| 项 | 实测结论 |
|---|---|
| **分层依赖方向** | domain 层**无** `infra` 依赖、**无** Spring 依赖，纯领域模型；`api → application → domain ← infra` 单向成立 |
| **ArchUnit 边界守护** | `architecture-tests` 6 条规则全绿，且**含防空转断言**（防止规则因类扫描为空而假通过）——这一点优于多数同类项目 |
| **金额表示** | 全库 `long` 分 + `Money` 值对象，主源码零 `float`/`double`/`BigDecimal`；`Math.addExact/multiplyExact` 防溢出 |
| **状态机集中化** | 状态转换为领域对象方法（如 `Payment.succeed()` / `transition()`），application 层无散落 `.setStatus()` 直写 |
| **幂等与并发** | 幂等键贯穿 10+ 文件；乐观锁 version 字段 + 唯一约束双保险 |
| **可观测性** | 各服务暴露 `/actuator/health`、`/actuator/prometheus` 与 Swagger UI；结构化审计日志带 traceId / paymentId |
| **测试策略** | 持久化与集成测试走 H2 内存库，**源码零 Testcontainers 依赖**——无需 Docker 即可全量验证，这一点对开发效率是实质性加分 |

### 4.2 需要关注

- **无服务发现**：Feign 走直连地址，多实例 / 弹性伸缩不可行。属已知取舍（`010-distributed-evolution` 以"门禁 + 判据"方式记录触发条件），但技术方案 §1 的表述会让人误以为已有 Nacos（见 P2-1）。
- **同步 RPC 为主**：跨服务全为同步调用，长链路下尾延迟与故障传播风险随服务数增长。当前无 MQ 是有意决策（宪法"基础设施决策门槛 5 问"），但 `007-settlement` 这类批处理链路未来大概率需要异步化。
- **ledger 接入面有限**：payment / refund / settlement 已接入复式记账（分别 5 / 3 / 4 个接入文件），但 reconciliation / fulfillment / entitlement / order 未接入。对账差异是否过账、权益发放是否记账，需明确口径，否则账本完整性有缺口。

### 4.3 关于 `next-stage-design.md` 两条对账指控的核实（技术描述有误，但风险判断成立）

| 文档指控 | 核实结果 |
|---|---|
| ① 对账 batch 生命周期（`beginProcessing` / `close`）未被调用，是死代码 | ❌ **不成立**。`ReconciliationApplicationService` 中四个生命周期方法**全部有调用**：`start()` L86、`finish()` L87、`beginProcessing()` L151、`close(operator, at)` L167。batch 生命周期完整 |
| ② `CsvChannelStatementLoader.load(period)` 忽略 `period` 入参，"每日对账"实际是假的 | ⚠️ **技术描述不准确，但风险判断成立** |

**第 ② 条的准确表述**：`load(period)` **并未忽略**入参——它用 `period` 拼出 `{dir}/{period}.csv` 并优先加载，未命中才回退默认 `sample.csv`，且回退会打 `reconciliation.statement_fallback` 指标 + WARN 留痕（代码 `CsvChannelStatementLoader.java:56-71`）。

**但风险确实存在**：`reconciliation-service/src/main/resources/fixtures/channel-statements/` 下**只有 `sample.csv` 一个文件**，没有任何按周期命名的 fixture。因此任意 `period` 都会走回退分支，**效果上等同于"每个周期加载同一份账单"**——文档担心的"假对账"在结果层面是真的，只是成因是 **fixture 缺失**而非代码忽略入参。

> 修正建议：把文档表述从"忽略 period 入参"改为"缺少按周期切分的 fixture"。处置方向应是补 fixture（或接真实渠道账单源），而非改 `load()` 实现。

**审计方法反思**：本条初查时因 grep 用了 `\.close()`（精确匹配空参数列表）而漏掉 `close(operator, at)`，一度得出相反结论。静态扫描结论**必须二次复核匹配口径**后才能入报告。

---

## 5. 文档一致性（漂移检测）

| # | 漂移点 | 权威源 | 冲突方 | 处置 |
|---|---|---|---|---|
| D-1 | Nacos 是否存在 | 代码：0 引用 | 技术方案 §1 / §5 | 改为"目标态"表述或加显式 `[未落地]` 标记 |
| D-2 | 部分退款 | 代码：`partiallySucceed()` 无调用方 | `005-refund/spec.md` US1（P1） | 已在 spec 顶部加注记标记延后（本轮完成） |
| D-3 | 鉴权 / 风控 / 脱敏 | 代码：占位空实现 | 技术方案 §5.2 原文写作既有能力 | 本轮已重写 §5.2 为"本期强制 / 本期不做"两组；同步 ADR-0009/0011 状态 |
| D-4 | 对账半实现指控 | 代码：实现完整 | `next-stage-design.md` | 见 §4.3，建议核实后修订 |

**上次审计识别的"technical-solution 滞后于 roadmap"问题**：已修复——roadmap 的 Current Status 现正确反映 004-ledger 已落地、payment/refund/settlement 已接入记账，与代码一致。

**文档规模**：`docs/` 共 30+ 篇 Markdown，覆盖 constitution / ADR（11 份）/ roadmap / technical-solution / systems（9 份）/ specs（9 个 feature，均含 `acceptance.md`）/ operations。整体体系完整度高于同规模项目。

---

## 6. 功能进度矩阵

| Feature | 状态 | 代码证据 | 测试 |
|---|---|---|---|
| 001 核心业务模型 | ✅ 验收通过 | 9 服务主链打通 | 覆盖 |
| 003 支付可靠性 | ✅ 验收通过 | UNKNOWN 收敛、重试/退避、幂等吸收 | payment 78 项 |
| 004 Ledger | ✅ 已落地 | `ledger-service` 961 行；domain（Account/LedgerEntry/Posting/LedgerSourceType）+ MyBatis 持久化 + `BalanceChecker` | 15 项（平衡校验 / 幂等 / 来源可追溯） |
| 005 退款 | ✅ 已落地（全额） | `RefundPostProcessOrchestrator` 编排 ledger + 履约 + 对账 | 29 项 |
| 006 对账 | ✅ 已落地（有演示缺口） | 差异检测、batch 生命周期（`start`/`finish`/`beginProcessing`/`close` 全部有调用）、CSV 加载与回退留痕 | 27 项；**但 fixture 只有 `sample.csv`，各周期对出同一结果**（P2-5） |
| 007 结算 | ✅ 已落地 | 结算聚合 + ledger 记账 | 31 项 |
| 009 风控安全 | ⚠️ **按决议裁剪** | 挂点保留（拦截器注册于 `/internal/**`、验签 Filter 保留）、逻辑空实现 | 测试已同步占位契约 |
| 010 分布式演进 | 📋 规划态 | 以"门禁 + 判据"文档化，无代码 | — |

**主链 MVP（merchant → catalog → order → payment → fulfillment → entitlement）已可跑通**，且 payment / refund / settlement 三处资金变动均已接入复式记账 —— 这满足宪法"资金变动必须过 ledger"的核心要求（该条款在 08-28 审计后已反向修正了路线图顺序）。

---

## 7. 整改优先级建议

**第一步（本轮，已完成）**：修复 P0 三处悬空/契约脱节，恢复全量构建绿。

**第二步（本周内）**
1. 提交工作区 37 个文件改动（P1-1），并补 `git log` 可追溯的决策说明
2. 引入 Flyway 挂载 9 个 schema（P1-2）——这是解锁"真实服务跑起来"的前提
3. 修正 `next-stage-design.md` 的对账表述（"忽略入参"→"缺按周期 fixture"）后入库（P2-4）
   —— 同时补按周期切分的对账 fixture（P2-5），修复成本极低但直接决定对账能否被真实验证

**第三步（下个迭代）**
4. 补 `systems/ledger-service.md`（P1-4）
5. 统一 ledger 接入口径：明确 reconciliation / fulfillment / entitlement 是否记账（§4.2）
6. 修正 Nacos 表述（P2-1）、清理 Javadoc 残留（P2-2）
7. 补 common-mybatis 测试（P2-3）

**可选（视目标）**
8. 接入 checkstyle 并纳入 CI（P1-3）
9. 若项目用于面试展示，建议补齐"服务真实启动"链路（Docker + Flyway + 一键启动脚本），把可运行性这一环补上——目前它是唯一明显短板

---

## 8. 审计方法论说明

- 所有构建结论均来自实测 `mvn -o clean verify -fae` 输出，非推断。
- 问题判定均附文件路径/行号证据；对检测脚本的疑似结果（如"零断言"）一律二次复核后给出结论，避免误报。
- 对上次审计结论**不盲信**：本次复核纠正了 P2-11（infra 实现 application 接口属正常依赖倒置）一项误判。
- 文档类问题以"权威源"判定归属：constitution > ADR > roadmap > technical-solution > spec。

---

**审计人**：WorkBuddy
**报告版本**：v1.0（2026-08-30）
