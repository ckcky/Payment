# ADR-0019 ~ ADR-0021：对账（Feature 006）架构决策集合

> 本文件合并 Feature `006-reconciliation` 的架构决策为单一决策记录，便于集中审阅（同 `0003` / `0004` / `0006` 的合并风格）。
> 编号为内部决策标签（ADR-0019 ~ ADR-0021），状态独立标注。
> 涉及 Constitution §8「人类决策边界」的决策，均**待负责人确认**（2026-08-29）。

---

## ADR-0019: 批次差异处理生命周期（接线 `beginProcessing`/`close` 与「处理中/关闭」语义）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`006-reconciliation`（spec US2 / FR-007~FR-011 / 缺口 G1、N3）
- **关联 Constitution 条款**：§8.3（Schema 变更）、§8.4（新增 API）、§8.8（状态机变更）、§V.2（状态机集中）

### Context（背景）

`reconciliation-service` 的批次状态机**六态齐全**，`beginProcessing()` 与 `close()` 已在领域层实现（`ReconciliationBatch.java:66/72`），但**应用层零调用**：`resolveDifference` 只写 `Difference.resolutionStatus` 便返回（`ReconciliationApplicationService.java:104-115`），controller 也没有任何关闭端点（`ReconciliationController.java:28-56`）。

后果是三重的：

1. **文档记载的生命周期在代码中不可达**：`technical-solution.md:174`（Reconciliation「一致/有差异 → 处理中/关闭」）与 `reconciliation-service.md:64-66` 描述的状态机后半段，运行时永远不会发生——这是**文档与代码的事实性矛盾**，不是「暂未用到」。
2. **资金运营无法表达处理进度**：所有批次都停在 `HAS_DIFFERENCE`，无法区分「刚发现差异」「正在处理」「本周期已收口」，批次状态退化为「有无差异」的布尔量。
3. **差异处理依据不完整**：`resolutionNote` 无必填校验，且没有处理人与处理时间（`Difference.java:56-59`、`ResolveDifferenceRequest.java:6`），Roadmap Phase 6 要求的「人工处理依据」只有一半成立。

需要决定：① 由谁、在何时触发 `beginProcessing`/`close`；② 「处理中」「关闭」的语义与门禁；③ 终态与重复动作的幂等策略；④ 处理依据的最小字段集。

### Decision（决策）

采用「**差异处理即推进 + 显式关闭端点 + 关闭门禁 + 终态只读**」：

1. **触发者**：
   - `beginProcessing()`：由**应用层 `resolveDifference`** 在成功处理一条差异后调用。首个差异 → `HAS_DIFFERENCE → PROCESSING`；后续差异 → 幂等空操作。
   - `close()`：**不自动触发**，新增显式端点 `POST /internal/reconciliation/batches/{id}/close`，由资金运营/运维在对账收口时调用（对账是审计动作，「收口」MUST 由人确认，不由系统代劳）。
2. **状态机改动（仍集中在 `ReconciliationBatch`，不新增 `setStatus`）**：
   - `beginProcessing()`：前置从「仅 `HAS_DIFFERENCE`」扩展为「`HAS_DIFFERENCE` 或 `PROCESSING`」，后者为空操作（幂等）。**保持**对 `CONSISTENT`/`CLOSED` 抛 `STATE_TRANSITION_VIOLATION`（现有测试 `ReconciliationBatchStateMachineTest:76-81` 的断言不变）。
   - `close()`：允许来源 `CONSISTENT` / `PROCESSING`（既有）；新增 `CLOSED → CLOSED` 空操作（幂等吸收）；新增**关闭门禁**——`unresolvedDifferenceCount > 0` 时抛**新增错误码 `UNRESOLVED_DIFFERENCES`**（语义比 `STATE_TRANSITION_VIOLATION` 更精确，便于调用方区分「状态不对」与「差异没处理完」）。
   - `HAS_DIFFERENCE` 直接 `close` 仍被拒（`STATE_TRANSITION_VIOLATION`）：一条差异都没处理就不算收口。
3. **语义定义**：
   - **PROCESSING** =「已开始人工介入处理差异」（不要求差异已处理完）。
   - **CLOSED** =「本周期对账已收口：所有差异均已处理并有依据」，是**只读终态**；关闭后处理差异被拒，`settlement-summary` 仍可读（对账结果不因关闭而失效）。
4. **处理依据字段集**：`Difference.resolve(note, actor, at)` —— `resolutionNote` **MUST 非空白**（否则 `INVALID_ARGUMENT`），同时写入 `resolvedBy`（操作人）与 `resolvedAt`（ISO-8601）。两者随 `differences_json` 内嵌，**不加 DB 列**（差异本就随批次 JSON 持久化）。
5. **重复处理语义**：已 `RESOLVED` 的差异再次处理 → **幂等刷新**（更新 `resolutionNote`/`resolvedBy`/`resolvedAt`）。理由：运维补充/更正依据是合理场景；且刷新只影响依据字段，不改变「已处理」这一事实。**待负责人确认**（备选：拒绝第二次处理）。
6. **可观测与审计**：差异处理与批次关闭各写一条 `FINANCIAL_AUDIT`（`traceId`/period/batchId/reference/前后状态/operator/note），并递增 `reconciliation.difference_resolved`、`reconciliation.batch_closed`（对账**执行**为只读比对，不写资金审计，避免噪音）。
7. **持久化**：`reconciliation_batches` 新增 `closed_at DATETIME NULL`、`closed_by VARCHAR(64) NULL`（非破坏性 `ADD COLUMN`，存量不回填）。

### 备选方案

- **A. 维持现状（不接线），把文档改成「四态」**：改动最小，但承认「对账无法收口」，与 Roadmap Phase 6 验收「差异可处理」及 `technical-solution.md:174` 直接冲突；批次状态退化 —— **否决**。
- **B. 差异全部处理完自动 `close`**：省一个端点，但「收口」是审计判断（可能有人为判定「差异可解释但不处理」的场景），自动关闭会掩盖未决问题；且自动动作难以界定操作人 —— **否决**。
- **C. 允许带未处理差异强制关闭（带理由）**：灵活，但会让 `unresolvedDifferenceCount > 0` 与 `CLOSED` 并存，破坏 `SettlementEligibility` 的判定直觉（`SettlementEligibility.java:33` 仍会拒绝结算，等于关闭无意义）—— **否决（可作为后续「部分收口」演进，需另立 ADR）**。
- **D. 差异处理即推进 + 显式关闭端点 + 门禁 + 终态只读（采纳）**：生命周期可达、语义明确、收口由人确认、与结算资格口径一致。

### Consequences（后果）

**正面**：文档记载的生命周期在代码中真正可达，消除文档/代码矛盾；批次状态可表达处理进度与收口；关闭门禁保证「CLOSED ⇒ 无未处理差异」，与 `SettlementEligibility` 语义闭环；处理依据完整可追溯。

**代价 / 风险**：

- `reconciliation_batches` 新增两列，属 Constitution §8.3（Schema 变更）范围，须负责人确认。
- 新增内部端点 `POST .../batches/{id}/close`，属 §8.4（API 变更）——仅新增、向后兼容，既有端点契约不变。
- 状态机迁移规则扩展（`beginProcessing`/`close` 幂等 + 关闭门禁）属 §8.8（状态机变更）范围。
- 关闭为**单向不可逆**（`CLOSED` 终态，不重开）：若关闭后该周期又产生新事实，须另开周期批次；跨周期补差列为 `[待定]`。

### 关联

- Constitution §V.2（状态机集中）、§VII.2（资金审计）、§8.3 / §8.4 / §8.8
- `006-reconciliation` spec：US2、FR-007~FR-011；data-model.md §2.1（状态机表）、§3（Difference）、INV-2/3/4/11
- 代码：`reconciliation-service/.../domain/ReconciliationBatch.java:66/72`、`domain/Difference.java:56`、`application/ReconciliationApplicationService.java:104-115`、`api/ReconciliationController.java:46`、`deployment/schema/07-reconciliation-schema.sql`
- 下游：`settlement-service/.../domain/SettlementEligibility.java:29-37`

---

## ADR-0020: 渠道账单来源（按周期 fixture + 显式回退 vs 参数化加载器 vs 维持全局 fixture）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`006-reconciliation`（spec US1 / FR-003 / FR-018 / 缺口 G2、新发现 N1）
- **关联 Constitution 条款**：§8.3（Schema 变更）、§8.4（跨服务契约变更——本决策**规避**）、Security（输入校验）

### Context（背景）

`CsvChannelStatementLoader.load(period)` **接收 `period` 却完全忽略它**，恒读 `fixtures/channel-statements/sample.csv`（`CsvChannelStatementLoader.java:24/27`）。因此：

1. 「按周期对账」在渠道侧不成立——任何周期都比对同一份固定账单，差异无法在不同周期间复现与对比。
2. **更关键的新发现（N1）**：平台侧同样没有周期概念——`PaymentFactsService.confirmedFacts()` 与 `RefundFactsService.confirmedFacts()` 返回**全量**已确认事实（`PaymentFactsService.java:28-32`、`RefundFactsService.java:26-30`），端点根本没有 `period` 参数。也就是说，**双侧都不带周期**，只修 loader 并不能让「按周期对账」成立。
3. Roadmap Phase 6 明确「不包含真实渠道账单接入」，因此账单来源改动必须落在「Mock/预置」范围内，不能借机做真实渠道适配。

需要决定：账单来源如何按周期组织；`period` 的语义到底是什么；平台侧周期口径如何处理。

### Decision（决策）

采用「**按周期定位 fixture + 显式留痕回退 + 明确 `period` 语义为批次/快照标识**」：

1. **按周期定位**：`load(period)` 优先加载 `{fixture-dir}/{period}.csv`（`fixture-dir` 默认 `fixtures/channel-statements/`，可配置）。命中即用，`fallbackUsed=false`。
2. **显式回退**：未命中该周期 fixture 时，加载默认文件 `{fixture-dir}/sample.csv`（可配置），并 **MUST 留痕** —— 批次记录 `statementSource`（`fallbackUsed=true`、实际 `locator`、条目数），递增 `reconciliation.statement_fallback`，打印 WARN（含 `period` 与 `locator`）。**MUST NOT 静默回退**（静默 = 制造「按周期对账」的假象，违背 Constitution §I.1 真实性与 §VII 可观测）。
3. **两者皆无**：抛 `INTERNAL_ERROR`（保留 `CsvChannelStatementLoader.java:31` 既有行为）。
4. **`period` 的语义（关键口径）**：**MVP 明确 `period` = 对账批次的标识/快照标签，不是时间窗口过滤条件**。理由：平台侧事实端点无 `period` 参数，为双侧引入时间窗口属跨服务契约变更（Constitution §8.4），且需按支付/退款时间建索引，超出 Roadmap Phase 6「不包含真实渠道账单接入、自动调账」的范围。因此本 Feature **不为 `confirmed-facts` 增加 `period` 参数**，也不声称实现了「按时间窗口对账」。
   - 未来若需真正的时间窗口，须**另立 ADR**（契约变更 + 索引 + 存量事实时间字段口径）。
5. **输入安全**：`period` MUST 校验仅含 `[A-Za-z0-9._-]`（防路径穿越，Constitution Security），非法 → `INVALID_ARGUMENT`。
6. **解析严格化**：账单行非法（列数 < 4 或金额非数字）MUST 显式 WARN（含行号），**MUST NOT** 静默跳过——静默丢弃会人为制造「漏单」假象，与对账目标相悖。
7. **持久化**：`reconciliation_batches` 新增 `statement_source VARCHAR(255) NULL`，存 `ChannelStatementSource`（`sourceType=FIXTURE` / `locator` / `entryCount` / `fallbackUsed`）序列化结果，供事后追溯「这一批到底对了哪份账单」。

### 备选方案

- **A. 维持全局固定 fixture（现状），只把文档改诚实**：零改动，但 Roadmap「按周期对账」与「差异可重复识别」的可验证性落空；学习闭环无法演示不同周期的差异（如 T+1 才出现的差异）—— **否决**。
- **B. 参数化加载器 + 全量时间窗口过滤（双侧加 `period`）**：最「正确」的对账语义，但需要改 `GET /internal/payments/confirmed-facts` 与 `/internal/refunds/confirmed-facts` 两个已发布契约（§8.4）、为 payment/refund 增加按时间查询与索引，且真实渠道账单尚未接入，收益无法验证 —— **否决（列 [待定]，须另立 ADR）**。
- **C. 按周期 fixture + 显式回退 + 明确语义（采纳）**：在 Roadmap Phase 6 范围内让「按周期」可验证（不同周期得到不同结果），同时用回退保证既有 e2e 不被打破，并把「不是时间窗口」如实写进文档与 ADR。

### Consequences（后果）

**正面**：不同周期可产出不同、可复现的差异集合，学习闭环成立；回退有痕迹，不会自欺；`statement_source` 让每批对账的账单来源可追溯；无需改动任何跨服务契约。

**代价 / 风险**：

- **必须诚实**：`period` 仍是「批次标识」而非时间窗口，平台侧事实是全量快照——文档（spec US1/Assumptions、`reconciliation-service.md` §4.3）MUST 明确该口径，避免读者误以为已实现周期窗口对账。
- 回退路径会让「未准备 fixture 的周期」仍能对账成功，存在被误用的风险；由指标 + WARN + `statement_source` 三处留痕缓解。
- `reconciliation_batches` 新增一列，属 §8.3 范围。
- 真实渠道账单接入时，`ChannelStatementSource.sourceType` 从 `FIXTURE` 扩展为 `SFTP`/`API`，加载器接口不变（可演进）。

### 关联

- Constitution §I.1（真实）、§IV.4（不跨服务改数据）、§VII（可观测）、§8.3 / §8.4
- `006-reconciliation` spec：US1、FR-003 / FR-018；data-model.md §5（`ChannelStatementSource`）、INV-7
- 代码：`reconciliation-service/.../infra/CsvChannelStatementLoader.java:24-57`、`application/ChannelStatementLoader.java`、`application/ReconciliationApplicationService.java:68`
- 相关发现：`payment-service/.../application/PaymentFactsService.java:28`、`refund-service/.../application/RefundFactsService.java:26`（N1，平台侧无周期过滤）

---

## ADR-0021: 事实读取 RPC 的弹性（超时 / 有限重试 / 错误归一化 vs 引入熔断中间件）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`006-reconciliation`（spec US3 / FR-012~FR-015 / 缺口 G3）
- **关联 Constitution 条款**：§V.4（重试须有退避与上限）、§V.6（所有外部调用 MUST 有超时）、§IV（基础设施决策门槛）

### Context（背景）

reconciliation → payment / refund 的两条事实读取 RPC **没有任何弹性配置**：`@FeignClient` 只有 `name` 与 `url`（`PaymentFactsFeignClient.java:11`、`RefundFactsFeignClient.java:11`），`application.yml` 中也没有 connect/read timeout、Retryer、ErrorDecoder 或熔断配置（`application.yml:22-26`）。

这直接违反 Constitution §V.6「所有外部调用 MUST 有超时」（当前是框架默认，非显式决策），并且在 payment/refund 抖动时：① 请求可能长时间挂起；② 失败原因被框架异常包装，无结构化信号；③ 无重试，一次瞬时抖动就让整周期对账失败（虽可重跑，但无诊断证据）。

同时要避免另一端的过度设计：Constitution §IV 要求引入任何基础设施前 MUST 回答五个问题；`technical-solution.md §4.1` 也明示熔断「延迟到需要时再引入」。对账是**只读、低频、不阻塞主资金链路**的面，当前没有任何负载或故障证据支持引入熔断/隔离框架。

需要决定：用什么方式满足超时与重试要求，是否引入 Resilience4j。

### Decision（决策）

采用「**显式超时 + 仅对只读幂等 GET 的有限重试 + 错误归一化与失败指标**」，**不引入** Resilience4j：

1. **超时**：为两个 facts 客户端显式配置 `Request.Options` —— connect **1s** / read **3s**（可通过 `services.payment.connect-timeout-ms` / `read-timeout-ms` 覆盖，refund 同）。满足 Constitution §V.6 的显式决策要求。
2. **重试**：配置 `feign.Retryer`（**Spring Cloud OpenFeign starter 已传递依赖 `feign-core`，零新增依赖**），默认 **3 次**、退避 **1s / 2s / 4s**（`Retryer.Default`）：
   - **仅**作用于这两个只读 facts 客户端（通过 `@FeignClient(configuration=...)` 局部绑定，**MUST NOT** 注册为全局 Bean 污染其他客户端）。
   - 资格：仅对**幂等且无副作用**的 `GET confirmed-facts` 重试（Constitution §V.4）；对账服务没有任何写向 payment/refund 的 RPC，因此不存在误重试写操作的风险。
3. **错误归一化**：自定义 `ErrorDecoder` 把框架异常统一为 `BizException(INTERNAL_ERROR)`（保留目标服务与 HTTP 状态于日志，不外泄细节），避免调用方看到 `FeignException` 这类基础设施异常。
4. **失败语义（关键不变量）**：事实读取在**建批之前**（`ReconciliationApplicationService.java:66-68`），失败 MUST 直接上抛，**绝不落半成品批次** —— 该周期因此无批次，可被安全重跑（与周期唯一约束幂等天然兼容：未落库 ⇒ `findByPeriod` 为空 ⇒ 重跑合法）。
5. **可观测**：失败时递增 `reconciliation.fact_read_failed`（维度 `target=payment|refund`）+ 结构化日志（含 `traceId`、`period`、目标服务、失败原因），满足 Constitution §VII.4 的业务告警要求。
6. **不引入 Resilience4j / Sentinel**：当前无熔断的真实需求证据（对账为低频只读面，失败不影响主资金链路），引入需通过 Constitution §IV 的五问门槛，且 `technical-solution.md §4.1` 已判定「延迟引入」。若未来对账进入高频调度或出现雪崩证据，再立 ADR 评估。

### 备选方案

- **A. 维持现状（框架默认，不显式配置）**：零改动，但持续违反 Constitution §V.6，且失败无诊断信号 —— **否决**。
- **B. 只配超时，不重试**：满足 §V.6，但一次瞬时抖动即失败；对账是批处理语义，重试代价极低（只读幂等）而收益明确 —— **部分采纳（超时部分）+ 否决（拒绝重试部分）**。
- **C. 引入 Resilience4j（熔断 + 隔离 + 限流）**：能力最全，但当前无任何负载/故障证据，属 Constitution §IV 明确禁止的「为复杂度引入中间件」；且 `technical-solution.md §4.1` 已判定延迟引入 —— **否决（留待有证据时另立 ADR）**。
- **D. 显式超时 + 有限重试（仅幂等 GET）+ 错误归一化 + 失败指标，不引中间件（采纳）**：满足 §V.6/§V.4，零新增依赖，失败可诊断且不产生半成品批次。

### Consequences（后果）

**正面**：修复 Constitution §V.6 的既存违反；瞬时故障自愈；失败不入批（可安全重跑）；失败有明确指标与日志；零新增依赖。

**代价 / 风险**：

- 重试会**放大**下游压力：最坏情况单周期对账额外产生 2×2 次重试调用。缓解：重试上限 3 次 + 指数退避 + 仅对账这一低频只读面生效。
- 最坏耗时从 ~1s 增至 ~8s（3s 读超时 × 3 次 + 退避），仅发生在故障路径；对账非在线链路，可接受（需在文档标注，避免被误当作在线接口调优）。
- 无熔断：payment/refund 长时间不可用时会持续重试而非快速失败。这是有意取舍（对账低频、失败即整体失败且可重跑）；若未来引入调度器（定时对账），MUST 重新评估熔断 —— 记为 `[待定]`。
- 局部 `@FeignClient(configuration=...)` 绑定需注意 Spring Cloud 的父子上下文隔离（配置类不应被 `@ComponentScan` 扫到，避免退化为全局配置）。

### 关联

- Constitution §V.4（重试退避与上限）、§V.6（超时 MUST）、§IV（基础设施门槛）、§VII.4（业务告警）
- `006-reconciliation` spec：US3、FR-012~FR-015；data-model.md INV-8（失败不入批）
- 代码：`reconciliation-service/.../infra/client/PaymentFactsFeignClient.java:11`、`infra/client/RefundFactsFeignClient.java:11`、`application/ReconciliationApplicationService.java:66-68`、`src/main/resources/application.yml:22-26`
- 参考：`003-payment-reliability` 的 ADR-0005（重试模型：上限 3 次 / 退避 1s-2s-4s，与本决策保持一致）
