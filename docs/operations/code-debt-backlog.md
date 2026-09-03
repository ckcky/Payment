# 代码缺陷待办清单（Code Debt Backlog）

> **性质**：本文档是**缺陷登记簿，不是方案文档**。所有条目均来自 2026-09-03 的 Phase 5 文档治理审计。
>
> **边界声明**：Phase 5 的整改范围是**文档与目录治理，不修改任何业务代码**。因此本清单只登记、不修复；
> 每条给出定位、证据、影响与建议动作，待负责人裁决后单独安排修复。
>
> **与 ADR 的关系**：本清单不产生决策。涉及架构取舍的条目，须先立 ADR（见 `docs/adr/README.md`），
> 再按 ADR 结论修复，修复后在本表标记「已闭环」并回填 ADR 编号。

| 状态图例 | 含义 |
|---|---|
| 🔴 High | 影响资金正确性 / 数据安全，或阻塞他人理解系统，应优先安排 |
| 🟠 Med-High | 与已 Accepted 的 ADR 冲突，或存在误导性实现 |
| 🟡 Med / Low | 死代码、文案过时、观测盲区等，可批量清理 |

---

## 登记表

| # | 严重度 | 位置 | 现象 | 证据 | 建议动作 | 状态 |
|---|---|---|---|---|---|---|
| 1 | 🔴 High | `deployment/initdb/01-create-databases.sql` | 只建 8 个库，**缺 `ledger`**；注释「对应 9 个服务」口径过时 | ledger-service 已在 `pom.xml` modules 中且已实现（端口 8090） | 补 `CREATE DATABASE ledger`；注释改为「10 个服务 / 9 个数据源（merchant 用内存存储）」 | 待裁决 |
| 2 | 🟡 Med | `common/common-*` 的 `InMemoryIdempotencyRegistry` | 死代码，仅被自身测试引用，无 Spring 装配 | grep 生产引用为 0 | 删除，或补齐文档说明其为测试替身 | 待裁决 |
| 3 | 🟡 Med | `reconciliation-service` 的 `InMemoryReconciliationRepository` | 死代码，未装配到任何 `@Configuration` | grep 装配点为 0 | 同上 | 待裁决 |
| 4 | 🟠 Med-High | `deployment/architecture-tests/ServiceBoundaryTest.java` | 现有 6 条 ArchUnit 规则覆盖编译期分层与「禁 MQ/JTA」，**拦不住运行时 RPC 环** | order-service ↔ payment-service 存在运行时双向 RPC，构建仍全绿 | 新增规则：扫描 `@FeignClient` 调用图，检测服务级循环依赖；或明确接受并写入 ADR | 待裁决 |
| 5 | 🟠 Med-High | `payment-service`（Resilience4j） | 已引入 Resilience4j 且 `circuitbreaker.enabled=true`，**与 ADR-0021「当前不引入熔断」冲突** | `pom.xml` 有依赖；配置显式开启 | 见下方「R2 待裁决项」 | 待裁决 |
| 6 | 🟡 Med | `common/common-mybatis` | **0 测试覆盖** | 该模块 `src/test/` 为空 | 补最小测试（类型处理器 / 分页插件）；或记录为已知盲区 | 待裁决 |
| 7 | 🟡 Low | `ReconciliationBatch.java:82` vs ADR-0019 | `beginProcessing()` 的前置状态表述与 ADR-0019 的状态机描述不一致 | 代码允许的前置状态与 ADR 措辞存在偏差 | 核对后统一——**改文档而非改代码**（ADR-0019 为 Accepted） | 待裁决 |
| 8 | 🟡 Low | `pom.xml:15` 注释 | 「gateway 与 ledger-service 本 MVP 延后，故不在 modules 中」——ledger 实际已在 modules | 与同文件 `:45` 自相矛盾 | 改为「gateway 延后未建；ledger-service 已实现」 | 待裁决 |
| 9 | 🟡 Med | `StructuredAuditLogger.mask()` | 脱敏能力**保留但生产路径零调用**，易造成「已脱敏」的假象 | 见下方专项说明 | 二选一：接入审计日志路径，或删除并在文档中明确「本期不做脱敏」 | 待裁决 |
| 10 | 🟠 Med | 4 个 `infra/redis/` 目录 | `WindowsSafeRedisHealthIndicator` 等文件**未纳入版本库**（untracked） | `git status` 显示 catalog/order 各 2 个目录未跟踪 | 见下方「R4 待裁决项」 | 待裁决 |
| 11 | 🟡 Med | `RefundStatus.PARTIALLY_SUCCEEDED` + `Refund.partiallySucceed()` | **ADR-0016 回退不彻底**：枚举值、状态转换方法与三处 Javadoc 仍在，但 `partiallySucceed()` **零调用**（死方法） | `refund-service/.../domain/Refund.java:91-96`、`domain/RefundStatus.java:13`；`RefundPostProcessOrchestrator:20`、`LedgerPostingGateway:6`、`Refund:15/86/120/145` 引用该状态 | 删除枚举值 + 死方法 + 相关 Javadoc（需先确认无外部序列化依赖，如已落库的状态字符串）；或保留枚举值但加 `@Deprecated` 并写明「不可达」 | 待裁决 |
| 12 | 🟡 Med | `docs/specs/014-seckill-and-cache/acceptance.md` | §1 仍写「k6 压测 —— 本机不可用」，§3 仍把压测列为未验证项，但**实测数据已存在** | 2026-09-02 已实跑（Node 负载生成器），证据在 `deployment/performance/results/`；ADR-0044 已补 | Phase 5 阶段⑤ 按新证据更新该文件：验收方式补「Node 等价实跑」，未验证项保留「不超卖并发断言」 | 待处理（已排期） |

---

## 专项说明

### #9 · 脱敏能力的真实状态（2026-09-03 核实）

审计中一度怀疑「ADR-0027 称脱敏不做，但代码里仍有脱敏实现」。核实后结论如下，**三处需分开表述，不可混为一谈**：

| 组件 | 状态 | 说明 |
|---|---|---|
| `SensitiveDataMasker` | ✅ 已删除 | 与 ADR-0027「⛔ Not Implemented」完全一致 |
| `StructuredAuditLogger.mask(String)` | ⚠️ **存在，生产零调用** | 完整实现（前 2 + `***` + 后 2）；唯一调用者是自身的 `StructuredAuditLoggerTest`。`StructuredAuditLogger` 本身**在产**（ledger / payment 服务用于审计日志），但**不调用 `mask()`** |
| `OrderEntryIdempotencyService.mask(String)` | ℹ️ 局部私有方法 | order-service 内部的**幂等键截断**方法，用于避免长 key 刷屏日志，**与 ADR-0027 的脱敏范畴无关** |

**结论**：当前系统**不具备有效的敏感信息脱敏**。对外表述应为「脱敏⛔ 本期不做」，而非「已部分实现」。
宪法 v2.3.0 的 §Security.4 / §Observability.2 已按此口径加入例外条款。

**风险敞口**：当前项目无真实卡号与渠道凭证，日志中不出现 PAN / 密钥，敞口有限。
**前置条件**：接入真实支付渠道或处理真实卡号/凭证前，MUST 重新引入脱敏并补齐调用点。

### R2 · payment-service Resilience4j 去留（待负责人裁决）

| 选项 | 论证 |
|---|---|
| **移除（建议）** | ADR-0021 已判定「当前无熔断的真实需求证据」；保留一个已开启却无 ADR 支撑的熔断，会让运维误以为存在该保护层，反而更危险 |
| 保留并补 ADR | 若确有需求（如渠道出站调用保护），应新立 ADR 说明场景、阈值与降级行为，并回写到 `systems/payment-service.md` |

> ⚠️ 在裁决前，`docs/architecture/systems/payment-service.md` **不描述任何熔断行为**（本期弹性口径 =
> 显式超时 + 仅幂等调用有限重试）。见阶段 ③ P0-11。

### R4 · 4 个 `infra/redis/` 未跟踪目录（待负责人裁决）

涉及 `catalog-service`、`order-service` 的 main 与 test 各一个目录（共 4 个）。

- 建议：Phase 5 完成后**单独提交** `feat(014): Windows 安全 Redis 健康指示器`，
  不要混进文档治理 commit（保持 commit 语义单一）。
- 若决定不保留：需同步删除，避免本地编译通过而 CI / 他人克隆后编译失败。

---

## 闭环规则

1. 修复某条后，在本表「状态」列改为 `已闭环（ADR-00XX / commit xxxxxxx）`，**不删除行**（保留历史）。
2. 若某条经裁决认定为「不是问题」，状态改为 `已裁决·不修复` 并在建议动作列写明理由。
3. 新增条目须附**可复现的证据**（文件路径 + 行号 / grep 命令），不接受模糊描述。
4. 本清单在每次 Feature 交付时随 Review 一起过一遍，参见
   `docs/guides/engineering-standards.md` §文档漂移检查清单。
