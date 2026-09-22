# 技术流程规范（Engineering Standards）

> 把 Constitution 的原则落到 Java / Spring Cloud 的具体约束。本文是**规范**，用于编码、测试、Review、CI 的统一判据。
>
> **权威层级**：本规范不得与 [Constitution](../../.specify/memory/constitution.md) 冲突；冲突时以宪法为准。流程规范见 [ai-standards.md](ai-standards.md)，业务规范见 [business-standards.md](business-standards.md)。
>
> **口径说明**：标注 `[目标]` 的条目为**尚未落地**的目标态，不得当作现行强制要求引用（宪法已同步标注，避免"虚假合规"）。

## 1. 代码质量（Code Quality）

- **风格**：命名遵循 Java 惯例，包名 `com.payment.<service>.<layer>`。`[目标]` Checkstyle + Spotless 统一格式化 —— **当前未接入**（根 pom 与 CI 均无该插件）。
- **分层**：`api → application → domain ← infra`，依赖单向；`domain` 不依赖任何框架层。
- **DTO / Entity 分离**：对外 API 用 DTO，持久层用实体，二者不混用；跨服务只传 DTO / 事件。
- **错误处理**：统一错误码 + 异常分层（`BizException` 业务 vs `SystemException` 系统）；全局异常处理器兜底；错误信息对调用方明确。
- **对象**：值对象（幂等键、状态）不可变；实体有清晰的生命周期。

## 2. 资金正确性（Money Invariants，最高优先级）

- 金额用 **`long`（最小单位分）+ 独立 `currencyCode` 字段，或 `BigDecimal`（明确 scale）** 表示；**禁止 `float` / `double`**（Constitution §II）。
- `Money` 值对象**不启用**（ADR-0010，已由宪法追认）：金额以 `long` 分 + `currencyCode` 表达。
- 任何资金变动必须经 `ledger-service` 复式记账，借贷平衡；**禁止**直接改余额字段。

## 3. 一致性（Consistency，Constitution §V）

- **幂等**：支付/退款/结算入口必须有幂等键，数据库唯一约束兜底；重复请求不产生重复资金动作。
- **状态机**：Order/Payment/Refund/Fulfillment/Entitlement/Settlement 用显式单向状态机，集中在 `domain` 的状态转换函数，禁止散落直接 set 状态。
- **分布式一致性**：跨服务用 **Saga + 同步 RPC + 幂等重试**；**不引入 MQ 中间件**——跨服务**异步解耦**改用**既有 Redis（`redis:7`）的 Streams 模拟 MQ** 的事务消息能力（半消息 → 本地事务 → commit/rollback → 回查真相表），**目的是减少需要新增与长期运维的组件**（复用已存在的 Redis 依赖，禁 Kafka / RocketMQ 等新运维实体）；**禁止** 2PC/XA 分布式事务。硬约束见宪法 §IV + §V.3 增补与 [ADR-0074](../adr/0074-redis-transactional-message.md#adr-0074)。
- **未知支付状态**：结果不确定时进 UNKNOWN 状态，靠查询接口 / 对账 / 人工收敛，**禁止**猜成败直接落账。
- **事务边界**：`@Transactional` 只放在 `application` 应用服务层，且只覆盖**单服务本地事务**。

## 4. 测试（Testing）

- **框架**：JUnit 5 + Mockito + AssertJ；集成测试以 H2（MySQL 兼容模式）为默认载体；**真库子层（L2b）已落地**——`deployment/test-infra`（ADR-0081，仅测试作用域）提供 Testcontainers 真实 MySQL 共享基座：单例容器 + 类内独占库 + DDL 单一来源 `deployment/schema`；无 Docker 时 skip 且汇总行显式打印「N 个真库测试被跳过」（不得静默），CI `real-db` job 无 Docker 即红（skip 即红，禁假绿）。
- **分层升级判据（spec 033 §3，写进规范的核心一条）**：凡断言「**只会有一个赢家**」「**并发下不双记**」「**唯一约束挡住了**」的测试，MUST NOT 停留在 H2 或 `InMemory*Repository` 桩上——桩能证明「撞键后的处理逻辑对」，证不了「真的会撞键且只有一个赢」，此类断言 MUST 落 L2b 真库层（先例：`ledger-service` `LedgerPostingConcurrencyTest`）。逐级判据：需要跨聚合或事务 ⇒ 升 L2；涉及唯一键竞争 / 并发时序 / 方言特性 / 间隙锁 ⇒ 升 L2b；需要多服务协作 ⇒ 升 L4。
- **归属边界（spec 033 §4）**：测试基座（`deployment/test-infra`、`@Tag("real-db")` 标签机制、CI job）与业务用例分属不同 Feature——基座 Feature 不代写业务断言；业务 Feature 交付用例本体时引用基座，MUST NOT 绕过基座私起容器或私连宿主 3306（L2b 不与 `start-all.sh` / `start-container.sh` 抢端口，容器与测试统一 UTC）。
- **覆盖**：资金逻辑 MUST 有测试；表驱动测试优先；关键路径（支付成功/失败/超时/重复回调）有集成测试。支付重点覆盖：重复请求、重复回调、支付超时、支付状态未知、渠道失败、重试、重复消息、服务重启、最终一致性。
- **红线**：不得删测试来通过；不得改测试迎合错误实现（Constitution §AI Development）。
- **跨服务调用**：通过公开 HTTP/RPC 用例；调用方和被调用方都必须处理超时、重试和幂等。服务内部事件不作为跨服务通信。
- **契约**：跨服务 RPC 接口变更后，用契约测试或明确联调验证，避免静默破坏（L3 API 快照见 `.github/workflows/verify.yml`）。

## 5. 文档与 ADR

- 重要/不可逆决策 MUST 立 ADR（`docs/adr/NNNN-*.md`），否则视为未决策。
- 每个特性有 Spec（`docs/specs/<stage>/<feature>/spec.md`，按阶段归组，索引见 `docs/specs/README.md`）；代码与 Spec 不一致时，先判断是需求变更还是实现缺陷，再同步修订。
- **阶段（Stage）分层**：特性按目标归入 `docs/specs/<stage>/`；阶段总目标书（可选）写 `docs/specs/<stage>/stage-design.md`，阶段内全部 Feature 交付并合入 master 后归档到 `docs/archive/design/<YYYY-MM-DD>-<stage-slug>/`，并在 `docs/specs/README.md` 索引与 `roadmap.md` 同步。
- 关键业务逻辑（状态机、幂等、账本）在代码内写清「为什么」的注释。

## 6. CI / CD 与 Git

- **Maven Wrapper（mvnw）** 锁定 Maven 版本，保证构建可复现。
- **CI 流水线**：`mvnw verify`（compile + test）。`[目标]` lint（checkstyle/spotless）—— **未接入**。
- **发布**：产物确定性；配置与代码分离（配置走 Nacos / 环境变量）。
- **Git**：Conventional Commits；功能分支 + PR；合并前 Review。
- **分支与合并细则**（与 Constitution §提交与合并节奏 一致）：
  - **分支命名**：`feature/<NNN>-<slug>`（NNN = Spec 编号，如 `feature/016-order-orchestration`）；非 Spec 的修复与杂务用 `fix/<slug>` / `chore/<slug>`。
  - **一律走 PR**：代码改动（任何服务的源码、SQL、配置、构建脚本）MUST 在 feature 分支开发，推送后开 PR 并自合并；PR 描述引用对应 Spec / ADR。
  - **合并方式**：merge commit（`--no-ff`），禁止 fast-forward 与 squash 直推，保证每个 Spec 在提交历史上是一个可回滚的边界。
  - **直推白名单**：仅限纯文档（`docs/**`、`*.md`）与 CI/杂务微调（`chore:` / `docs:`）可直推 `master`；其余直推视为违规。
  - **AI 会话约束**：AI Agent 收到非 docs-only 任务时，第一步 MUST 是创建/切换 feature 分支，禁止在 `master` 上直接提交代码改动。
  - **Spec 编写的 worktree 隔离（推荐）**：主工作区存在 feature WIP 时，编写新 Spec / 纯文档 MUST 使用 `./spec-worktree.sh new <NNN>-<slug>` 在独立 worktree + `docs/spec-<NNN>-<slug>` 分支上进行，完成后 `push` 子命令做 docs-only 白名单校验并直推 `origin/master`，`rm` 清理。注意：该流程仅适用于 docs-only 改动；代码改动仍一律走 feature 分支 + PR。

## 7. 可观测（Observability，Constitution §Observability，spec 035 / ADR-0083）

- **Metrics**：Micrometer 经 `BusinessMetrics` 端口暴露请求量、延迟、错误率与关键业务计数；指标目录（名称/类型/值域/告警意义/Owner）唯一登记处为 [runbook §5](../operations/runbook.md)——**新指标未进目录不得上线**（M-2）。
- **Logs**：结构化日志（logback 单行 kv，MDC 键 = `traceId` / `bizNo` / `dyeMode`）；资金动作 MUST 有审计日志（`FINANCIAL_AUDIT`，只记必要字段）。**敏感字段脱敏本期不做**（ADR-0027，透传桩），改以「显式约束 + 排除 + 测试」守密钥红线（见 §7.3）。
- **Traces**：**自研 `X-Trace-Id` 为唯一权威**（`TraceIdFilter` + `TraceContext` + MDC，跨服务单头透传）；明确**不引入** APM / Micrometer Tracing / OTel / `traceparent`（ADR-0083 决策 4，资金链路不可采样）。`traceId` MUST 在进入异步/MQ 时随 envelope 传递并在消费侧恢复 MDC。

### 7.1 高基数政策（HC-1~HC-4，机器可检查）

| # | 条文 | 检查方式 |
|---|---|---|
| HC-1 | Metrics label **值集合 MUST 在发布前可穷举**（枚举类型、有界字符串、状态机态） | 代码 review + 指标目录登记值域 |
| HC-2 | **MUST NOT** 以业务单号（`*No`）、用户/商户 ID（`*Id`）、时间戳、UUID 作为 label 的**键或值**；`merchantId` 明确禁入（按商户分析走 SQL/Loki） | `MetricsCardinalityTest`（architecture-tests）静态扫描全仓 `metrics.counter/timer/gauge` 调用：标签键 ∈ 有界白名单，值表达式不得命中 `*No`/`*Id`/`getPeriod()`（YYYY-MM）模式——**红即拦** |
| HC-3 | 新 label 引入 MUST 在指标目录登记其值域（同 PR） | 目录 PR 门禁 + HC-2 扫描的键白名单同步扩展 |
| HC-4 | 单指标序列数预算 **200**；预计超出改用日志 + Loki 查询或 Gauge 覆盖写 | `MetricsAssert.assertTagValuesWithinAllowedSet(registry, …)`（test-infra，L2a 遍历 `MeterRegistry` 断言）；CI 可选抓取 `/actuator/prometheus` 序列数 |

`period`（`YYYY-MM`）**例外条款**：只允许出现在 `Gauge`（瞬时值、单序列覆盖写）；Counter/Timer 上禁用（单调累积 ⇒ 序列只增不减）。既有 `period` 标签仅存在于限额子域且值为**窗口枚举**（`LimitPeriod`，非 `YYYY-MM`），属有界值，不在此禁令的实际射程内——扫描对「值表达式 = `getPeriod()`/YYYY-MM 字面量」做棘轮基线，新增即红。既有 93 指标**零改名零删除**，政策守增量不追历史（spec 035 §12 M-1）。

### 7.2 Trace / 日志关联标准（spec 035 §6）

- 九个业务 ID（`transactionNo`/`paymentNo`/`paymentAttemptId`/`channelRequestId`/`refundNo`/`channelRefundNo`/`ledgerTransactionNo`/`reconciliationId`/`settlementNo`）**只进日志不进 Metrics label**：单笔链路主键进 MDC `bizNo`，其余进日志 kv；`channelCode`/`currency` 等**有界枚举**两者皆可。
- **所有资金写操作日志 MUST 带 `bizNo`**（缺失视为缺陷）。
- `ACCESS_LOG` 的 `uri` MUST 归一化路径变量（记 `/payments/{ref}` 级别模式，不落具体单号），否则访问日志自身成为高基数源。
- 关联链判据：给一个 `paymentNo`，四跳内拼出完整链路（bizNo grep → traceId 横向拉通 → FINANCIAL_AUDIT → `deployment/demo/trace-grep.sh`），不依赖 Metrics。

### 7.3 密钥与完整报文不入日志（spec 035 §10，H7 纠偏）

- **显式禁令**：`sign` / `privateKey` / `token` / `app_cert` / 完整渠道报文 MUST NOT 出现在任何日志、指标、审计字段。
- **路径排除**：`common.access-log.exclude-paths` 默认含 `/actuator/**` 与 `/internal/channels/**`——渠道回调入口（如支付宝 notify form）不落正文，保留 `method/uri/status/costMs/traceId/bizNo` 与控制器处理日志。
- **测试**：notify 端点与渠道出站用例 MUST 断言捕获日志不含 `sign=` 与私钥片段（`AccessLogFilterTest`）。
- **边界**：不借机启用通用脱敏（ADR-0027 裁决不变）；字段级 mask 若将来需要，由**单个服务**注册自己的 `SensitiveBodyMasker` Bean（`@ConditionalOnMissingBean` 覆盖点），MUST NOT 改 common-core 默认透传实现。

## 8. 安全（Security）

> 口径与 2026-08-30 负责人裁决、ADR-0024/0025/0027/0028 一致：**本期多数为预留空实现或明确不做**，不得写成"已实现"。

- **本期强制（MUST）**：
  - 密钥、签名、金额等**禁止硬编码**，走环境变量 / 配置中心 / 密钥管理（ADR-0026）。
  - 对外入参 MUST 做输入校验（Bean Validation）。
  - 资金动作 MUST 有审计日志。
- **本期不做 / 预留空函数（有意接受的风险）**：
  - 渠道回调验签 —— **预留空函数恒放行**（`ChannelCallbackSignatureFilter#verifySignature` 恒 `true`，ADR-0025/0052）。接入真实渠道前 MUST 实现。
  - 对外 API 鉴权 / 身份体系 —— **不做**，预留空实现（ADR-0024）。
  - 内部服务间鉴权令牌 —— **不做**，出站不发、入站不校验（ADR-0024/0034/0035）。
  - 敏感数据脱敏 —— **本期不做**（ADR-0027；密钥/完整报文不入日志改以「排除 + 禁令 + 测试」守红线，见 §7.3）。
  - 风控 —— **不做**，类已删除（ADR-0028）。
- **运维前提**：上述"本期不做"成立的前提是**部署环境不对公网暴露**；一旦暴露到不可信网络，鉴权 MUST 先于功能上线补齐。

## 9. 依赖管理（Dependency Management）

- 父 POM + `dependencyManagement` 统一版本（Spring Boot BOM + Spring Cloud BOM 对齐）。
- **最小化依赖**：每个新依赖 MUST 有理由（ADR 或 commit 说明）；禁止「顺手引入」。
- 版本冲突以父 POM 的 BOM 为准，不散落各自声明（散落版本属漂移，见 §11）。
- **禁止擅自升级组件 / SDK / 构建工具版本**：版本变更 MUST 经人类确认并在 commit message 说明理由；AI Agent 不得自行 bump。

## 10. 向后兼容（Backward Compatibility）

- 已发布的对外 API / 跨服务接口变更 MUST 向后兼容或提供迁移路径（Constitution §V）。
- 破坏性变更 MUST 经人类确认（宪法 Governance 人类决策边界「API Breaking Change」）。

## 11. 文档漂移检查清单（合并前置 ④ 的判定依据）

> 宪法 Governance §提交与合并节奏 第 ④ 条「文档无漂移」的判定依据即为本节。以下检查 MUST 在合并回 master 前执行；任一命中即为漂移，须先修正再合并。

1. **ADR 引用一致性**：代码/文档中引用的 ADR 编号必须存在于 `docs/adr/README.md` 索引表，且状态与实际相符（不得把 Rejected / Not Implemented 当作现行决策引用）。
   ```bash
   grep -rhoE 'ADR-[0-9]{4}' --include='*.java' --include='*.md' . | sort -u   # 与索引表比对
   ```
2. **ADR 编号唯一性**：同一 `<a id="adr-XXXX">` 锚点不得出现在两个文件（已知例外见索引表「编号冲突备案」）。
   ```bash
   grep -rhoE '<a id="adr-[0-9]{4}"' docs/adr | sort | uniq -d
   ```
3. **Maven 版本集中**：依赖 / 插件版本应统一在父 POM `dependencyManagement`，不得散落子模块（BOM 未覆盖的少数须在 §9 登记说明）。
   ```bash
   grep -rn '<version>' --include='pom.xml' . | grep -v '/pom.xml'   # 人工确认是否为 BOM 未覆盖项
   ```
4. **产物污染**：仓库根目录不得出现构建 / 运行产物；产物 MUST 落 `deployment/output/`（gitignored）。
   ```bash
   git status --short | grep -vE '^\?\? (\.workbuddy/|deployment/output/)'   # 根目录出现未跟踪产物即违规
   ```
5. **链接可达**：文档中的相对链接目标（含 ADR 锚点 `#adr-XXXX`）必须存在。
   ```bash
   grep -rhoE '\]\([^)]*\.md[^)]*\)' docs | sed 's/](//;s/)//' | sort -u   # 抽查目标是否存在
   ```
6. **RPC 边允许清单一致**：`deployment/architecture-tests/src/test/resources/rpc-edges.txt`（`@FeignClient` 运行时边基线，`RpcEdgeAllowListTest` 逐行全等断言）在新增 / 删除 Feign 边后 MUST 同步更新；各服务 spec 的调用关系描述与该清单一致。
   ```bash
   grep -rn '@FeignClient' --include='*.java' */*-api/src */*-service/src 2>/dev/null | wc -l   # 与 rpc-edges.txt 行数（去注释）比对
   ```

## 12. 迁移脚本可重放（Schema Migration Replay，ADR-0081）

> 来源：030 文件头先例的规范化（spec 033 §6.2）。机器门禁：`deployment/schema-lint.sh`（CI `schema-lint` job，秒级静态断言）与 `deployment/schema-replay.sh`（CI `schema-replay` job，空库全量 + 存量基线演进双路径重放，快照 diff 必须为空）。适用于 `deployment/schema/NN-*.sql` 增量脚本：

1. **MUST**：增量脚本对「列/索引/表已存在」幂等，统一用 `information_schema` 守卫 + `SET @sql = IF(...)` + `PREPARE/EXECUTE/DEALLOCATE`（先例：`015` / `018` / `019` / `030` / `031`）。
2. **MUST NOT**：`ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS`（MariaDB 方言，MySQL 8 直接语法错）。
3. **MUST**：脚本自带 `USE <schema>`；**MUST NOT** 依赖调用方的当前库。
4. **MUST**：文件头写清三件事——作用 / 幂等策略 / **存量行为**（回填 or 显式不回填及理由，先例：`030-payment-attempt-extra-json.sql` 的「刻意不回填」）。
5. **MUST NOT**：在增量脚本里 `DROP COLUMN`（历史事实列只停写不删，031 §14④ 同一口径）。
6. **SHOULD**：`NN-<feature-slug>.sql` 编号 = Feature 号，与 `docs/specs/<stage>/<feature>` 一一对应。

- **全量文件与增量脚本分工**：`CREATE TABLE IF NOT EXISTS` 遇存量表会整段跳过（D-A 类缺陷）——新列 MUST 由独立增量脚本承载，全量文件仅描述「全新库」的目标形状。
- **基线演进**：每个 Feature 交付时，若其增量脚本已被全量文件吸收，MUST 把「吸收前的全量 dump」落为 `deployment/schema/baseline/<NNN>.sql`（门禁输入数据，非可执行迁移；首个基线 = 033），供重放路径 B 暴露「全量文件与增量脚本描述不一致」类缺陷。
