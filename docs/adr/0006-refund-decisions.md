# ADR-0016 ~ ADR-0018：退款（Feature 005）架构决策集合

> 本文件合并 Feature `005-refund` 的架构决策为单一决策记录，便于集中审阅（同 `0003` / `0004` 的合并风格）。
> 编号为内部决策标签（ADR-0016 ~ ADR-0018），状态独立标注。
> 涉及 Constitution §8「人类决策边界」的决策，均**待负责人确认**（2026-08-29）。

> ✅ **编号冲突已解决（2026-08-29）**：本文件最初位于 `docs/adr/0005-refund-decisions.md` 并使用 ADR-0012~0014，与既有的 `docs/adr/0005-payment-reliability-impl-decisions.md`（Feature 003 实现期决策，占用 **ADR-0012~0015**）**冲突**。
> 已采用推荐方案 ① 解决：文件重命名为 `0006-refund-decisions.md`，标签重编号为 **ADR-0016~0018**，既有 `0005-payment-reliability-impl-decisions.md` 与其 ADR-0012~0015 **保持不变**，全局引用已同步更新。编号现已唯一，无歧义。

---

## ADR-0016: 部分退款支持模型（如何让 PARTIALLY_SUCCEEDED 可达、部分金额如何跟踪）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`005-refund`（spec US1 / FR-001~FR-003 / 缺口 G1）
- **关联 Constitution 条款**：§8.3（新增关键资金字段/表）、§8.8（状态机变更）

### Context（背景）

`RefundStatus.PARTIALLY_SUCCEEDED` 与 `Refund.partiallySucceed()` **已实现但不可达**：出站契约 `RefundAttemptResponse` 只回 `status`（`SUCCEEDED/FAILED/UNKNOWN`）与 `channelReference`，不含实际退款金额；`RefundApplicationService.java:99-103` 的 `switch` 只处理三态，因此任何渠道结果都不可能驱动到 `PARTIALLY_SUCCEEDED`。同时，`Refund` 聚合只有「申请金额 `amountMinor`」，没有「已确认退款金额」这一事实。

后果是双重的：

1. Roadmap Phase 5 验收标准「**部分/全部退款可追踪**」不成立（部分退款无法表达）。
2. 若直接引入部分退款而不定义额度口径，会破坏已有的防超退不变量（H1）：按「申请额」累计会**永久占死**未退部分的额度；按「已确认额」累计则在 `PROCESSING/UNKNOWN` 在途期无法计算，存在并发超退窗口。

需要决定：① 部分金额用什么字段/模型表达；② 状态如何判定；③ 累计额度口径如何定义；④ 剩余额度如何再退。

### Decision（决策）

采用「**单笔 Refund 增加已确认金额 + 状态由金额关系导出 + 累计口径分态**」：

1. **新增字段**：`Refund` 增加 `refundedAmountMinor`（已确认退款金额，`long` 最小货币单位），DB 列 `refunds.refunded_amount_minor BIGINT NOT NULL DEFAULT 0`。不变量：`0 <= refundedAmountMinor <= amountMinor`。
2. **契约扩展**：`RefundAttemptResponse` 增加 `refundedAmountMinor`（向后兼容的新增字段）；payment-service 透传渠道实际退回金额，Mock Channel 支持配置「部分退回」。
3. **状态判定**（全部经 `transitionTo` 唯一入口）：
   - `r == amountMinor` → `succeed()`（`SUCCEEDED`，并置 `refundedAmountMinor = amountMinor`）
   - `0 < r < amountMinor` → `partiallySucceed(r)`（`PARTIALLY_SUCCEEDED`，写入 `refundedAmountMinor = r`）
   - `r <= 0` 或 `r > amountMinor` → **不落成功类状态**，落 `UNKNOWN` + 告警（视为渠道数据错误，禁止资金放大）
4. **累计额度口径**（`RefundPolicy`）：终态（`SUCCEEDED`/`PARTIALLY_SUCCEEDED`）计 **已确认额**；在途（`PROCESSING`/`UNKNOWN`）计 **申请额**（保守占位，防并发超退）；`FAILED`/`REJECTED`/`CLOSED` 不计。约束 `cumulative + requested <= paidAmountMinor`。
5. **剩余额度再退**：`PARTIALLY_SUCCEEDED` 为**终态**，不复活、不追加尝试；剩余金额 MUST 以**新幂等键**另开一笔 `Refund`。
6. **契约缺失兼容**：`refundedAmountMinor` 缺失（payment-service 未升级）时，视为全额成功，保持既有行为。

### 备选方案

- **A. 为 Refund 增加 RefundAttempt 子表，累加多次尝试金额**：能自然表达「同单多次退」，但状态机与幂等语义成倍复杂（终态需可复活、累计需跨行聚合），且当前 Mock Channel 与真实渠道均无「同单多次尝试」语义 —— **否决**（过度设计，Constitution §IV 禁止为复杂度而复杂）。
- **B. 按 RefundItem 明细跟踪已确认金额**：精度更高，但需扩展 `refund_items` 与全部后处理契约，收益与当前需求不匹配 —— **否决（留作 [待定] 演进）**。
- **C. 不引入部分退款，把文档/状态中的 PARTIALLY_SUCCEEDED 删除**：最省事，但直接违反 Roadmap Phase 5 验收标准「部分/全部退款可追踪」，且丢失已实现的领域语义 —— **否决**。
- **D. 单笔 Refund + 已确认金额 + 分态累计（采纳）**：字段最小、状态机语义不变（终态仍不复活）、累计口径可证明安全。

### Consequences（后果）

**正面**：部分退款端到端可达且可追踪；累计额度在「部分成功」下仍不超限；`PARTIALLY_SUCCEEDED` 语义与既有终态吸收完全一致；契约向后兼容。

**代价 / 风险**：

- `refunds` 新增关键资金列，存量数据需回填策略（`SUCCEEDED` 回填为 `amount_minor`，其余为 0）—— 属 Constitution §8.3 范围，须负责人确认。
- 在途按申请额占位会**保守占用**额度：一笔申请 1000 的在途退款会锁死 1000，即使最终只退 300；剩余 700 须等其收敛为 `PARTIALLY_SUCCEEDED` 后才能申请。这是有意取舍（资金正确性 > 吞吐）。
- 状态机流转规则变更（`PARTIALLY_SUCCEEDED` 从不可达变可达）属 Constitution §8.8 范围。

### 关联

- Constitution §II.1（金额铁律）、§V.2（状态机集中）、§8.3、§8.8
- `005-refund` spec：US1、FR-001~FR-003；data-model.md §2/§3/§5；contracts/refund-orchestration.md §1.2
- `refund-service/.../domain/Refund.java:81/86/119`、`application/RefundApplicationService.java:99-103`、`domain/RefundPolicy.java:28`

---

## ADR-0017: refund → fulfillment 编排（补齐缺失 RPC vs 修改文档声明）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`005-refund`（spec US2 / FR-004~FR-006 / 缺口 G2）
- **关联 Constitution 条款**：§8.4（跨服务接口变更）、§III 边界 #6（Fulfillment 不强耦合）

### Context（背景）

`technical-solution.md §4.3.3` 的退款链路标注 `C --> D["履约/权益处理 (refund→fulfillment/entitlement RPC)"]`，§4.3.6 亦列出 `refund-service → fulfillment/entitlement 退款后处理`，Roadmap Phase 5「包含：退款后的 Fulfillment/Entitlement RPC」。

但**实测代码中该 RPC 完全不存在**：refund-service 仅有 `EntitlementGateway` / `EntitlementFeignClient`（`application/EntitlementGateway.java`、`infra/client/EntitlementFeignClient.java`），没有任何 fulfillment 网关；fulfillment-service 也只有 `GET /{id}` 与 `POST /internal/fulfillments/on-payment-succeeded`，**无退款端点**。文档与代码矛盾（已记录于 `systems/refund-service.md` §3.6 矛盾项 A）。

另一重问题是语义边界：`Fulfillment` 的状态机为 `PENDING → PROCESSING → DELIVERED / PARTIALLY_DELIVERED / FAILED`，且 `cancel()` **仅允许 `PENDING → CANCELLED`**（`Fulfillment.java:68`）。即「已交付的履约」在当前领域模型下**不可逆**。

需要决定：① 是否补这条 RPC；② 补齐后它能做到什么程度；③ 若不做，如何处置文档。

### Decision（决策）

**补 RPC，但严格限定为「请求撤销」而非「保证撤销」**，并把当前静默失败改为可追踪：

1. **新增下游端点**：fulfillment-service 暴露 `POST /internal/fulfillments/on-refund`（请求 `RefundFulfillmentRequest`，响应 `RefundFulfillmentResponse{status: CANCELLED | SKIPPED | REJECTED}`）。新增应用服务用例，**不改** `Fulfillment` 状态机规则。
2. **语义边界**：fulfillment 按**自身状态机**决定动作——`PENDING` → `cancel()` 返回 `CANCELLED`；`PROCESSING`/`DELIVERED`/`PARTIALLY_DELIVERED`/`FAILED`/`CANCELLED` → 返回 `SKIPPED`（可解释，非错误）；语义非法 → `REJECTED`。**已交付履约的回收不在本 Feature**（属「已消费权益/已交付履约的统一回收政策」，Roadmap Phase 5 明确「不包含」）。
3. **refund 侧新增出站端口**：`application/FulfillmentGateway.java` + `infra/client/FulfillmentFeignClient.java`（`services.fulfillment.url`，默认 `http://localhost:8086`）。
4. **后处理统一编排**：新增 `RefundPostProcessOrchestrator`，在确认退款后依次调用 **fulfillment → entitlement → ledger**（ledger 见 ADR-0018），每次调用落一条 `RefundPostProcessAttempt`（新增表 `refund_post_process_attempts`），记录目标、结果、失败原因、尝试次数。
5. **失败语义不变且可追踪**：任一侧失败**不回滚**退款成功（Saga，禁 2PC）；移除 `RefundApplicationService.java:113` 的 `catch (RuntimeException ignored)` 静默吞异常，改为递增 `refund.post_process_failed` + 写审计，满足验收标准「后处理失败可独立追踪」。
6. **重试**：同步有限退避重试（[目标] 3 次 / 1s-2s-4s），耗尽后保留失败记录，由对账/人工按 `refundId` 查询并重放；**不引入**重试调度器或 outbox（Constitution §IV，基础设施决策门槛）。
7. **触发条件唯一**：仅 `SUCCEEDED` / `PARTIALLY_SUCCEEDED` 触发；`UNKNOWN` 不触发（未确认结果不得产生不可逆的权利变更）。

### 备选方案

- **A. 维持现状并修改文档**（把 §4.3.3 改为「refund→entitlement（MVP）；fulfillment 撤销 [待定]」）：改动最小，但直接放弃 Roadmap Phase 5 明确「包含」的能力，且放弃前「后处理失败可独立追踪」—— **否决**。
- **B. 补齐 RPC 且要求 fulfillment 无条件撤销（含已交付）**：语义上「退款即收回商品」，但需为 `Fulfillment` 增加逆向状态迁移（如 `DELIVERED → RETURNED`），属**领域模型变更**，超出本 Feature 且 Roadmap 明确不含「已消费权益的统一回收政策」—— **否决（留待后续 Feature 立 ADR）**。
- **C. 由 order-service 中转编排退款后处理**：增加一跳与一个编排方，收益不明—— **否决**（依赖方向与职责更模糊）。
- **D. 补齐 RPC + 请求撤销语义 + 统一可追踪编排（采纳）**：与 §4.3.3 文档一致，尊重 fulfillment 自身状态机，且把静默失败变为可观测。

### Consequences（后果）

**正面**：消除文档与代码的矛盾（G2）；Roadmap「退款后的 Fulfillment/Entitlement RPC」成立；后处理失败首次可独立追踪、可告警（Constitution Observability §4）；不改 fulfillment 状态机，风险可控。

**代价 / 风险**：

- 新增跨服务端点属 Constitution §8.4 范围，须负责人确认。
- `SKIPPED`（已交付）仍会留下「钱已退、货已发」的业务缺口——这是**有意的范围边界**，需在对账/运营侧以其他方式兜底，不在本 Feature 解决。
- 新增一张表 `refund_post_process_attempts`（§8.3 范围）。
- 后处理从 1 次 RPC 变为 2~3 次，受理 P99 需复核（原 `[目标]` ≤ 500ms）。

### 关联

- Constitution §III 边界 #5/#6、§IV、§V.7、§8.3、§8.4
- `005-refund` spec：US2、FR-004~FR-007；data-model.md §4；contracts/refund-orchestration.md §2
- `docs/architecture/technical-solution.md` §4.3.3（矛盾源）、§4.3.6
- `docs/architecture/systems/refund-service.md` §3.6 矛盾项 A
- `refund-service/.../application/RefundApplicationService.java:108-116`、`fulfillment-service/.../domain/Fulfillment.java:68`

---

## ADR-0018: refund → ledger 记账接入（与 spec 004-ledger 的归属与时机）

- **状态**：**Proposed**（待负责人确认）
- **日期**：2026-08-29
- **决策者**：待人类（项目 Owner）
- **关联 Feature**：`005-refund`（spec US4 / FR-009~FR-010）；与 `004-ledger` US2 / T013~T014 重叠
- **关联 Constitution 条款**：§II.3（一切资金变动 MUST 经 ledger-service）、§8.4

### Context（背景）

**现状（已核实，与文档状态不符）**：

- `ledger-service`（端口 8090，Schema `ledger`）**已实现**：`api/LedgerController.java` 暴露 `POST /internal/ledger/postings`、`GET /internal/ledger/postings`、`GET /internal/ledger/balance`、`GET /internal/ledger/entries`；领域层 `Posting`/`LedgerEntry`/`Account`/`BalanceChecker` 与 MyBatis 持久化、单测均已落地。
- `payment-service` **已接入**：`application/LedgerPostingGateway.java` + `infra/client/FeignLedgerPostingGateway.java` + `infra/client/LedgerFeignClient.java`，支付成功即记账。
- **`refund-service` 无任何 ledger 集成**（refund-service 文件清单中不存在任何 ledger 相关类）。

而 spec `004-ledger` 的 US2「退款在账本中冲正」与任务 T013/T014 明确要求 refund 侧记账，其 `contracts/post-refund.md` 也已定义契约；但 `roadmap.md:13/16` 与 `004-ledger/acceptance.md` 仍把 004 描述为「待 ADR 确认 / 未完成」。**即：004 的退款记账一半已具备条件、一半未落地，而文档未反映这一分裂状态。**

同时发现契约漂移：`004-ledger/contracts/post-refund.md` 使用 `accountCode`，而实际 `common-dto` 的 `PostingRequest.EntryRequest` 字段为 **`accountId`**。

需要决定：① 退款记账由 005 承接还是回归 004；② 记账金额口径；③ 失败兜底；④ 契约漂移如何处置。

### Decision（决策）

**由 `005-refund` 承接退款侧记账的实现**（004 保留契约与账本侧所有权），具体：

1. **归属划分**：`ledger-service` 的领域模型、科目表、平衡校验、端点契约归 `004-ledger`（已实现）；**调用方接入（退款侧网关与触发）归 `005-refund`**，理由是本 Feature 同时引入 `refundedAmountMinor`（实际退款金额），记账金额口径必须与该变更同源实现，拆两批反而引入中间态不一致。004 的 T013/T014 相应标记为「由 005 承接」。
2. **新增出站端口**：`refund-service` 新增 `application/LedgerPostingGateway.java` + `infra/client/LedgerFeignClient.java`（`services.ledger.url`，默认 `http://localhost:8090`），**复用 payment-service 既有 `FeignLedgerPostingGateway` 的模式**（超时/幂等/兜底一致）。
3. **记账金额 = `refundedAmountMinor`（实际退款金额）**，非申请金额；部分退款按已退部分冲正（与 ADR-0016 同源）。
4. **触发条件**：仅 `SUCCEEDED` / `PARTIALLY_SUCCEEDED`；`UNKNOWN` / `PROCESSING` / `FAILED` / `REJECTED` **不记账**（Constitution §V.7：未确认结果不直接记账）。
5. **幂等键**：`REFUND:<refundIdempotencyKey>`；`sourceType=REFUND`、`sourceId=<refundId>`；重复收敛返回首次 `Posting`。
6. **失败兜底**：RPC 失败/超时 → **不回滚**退款成功事实；记 `ledger.posting_failed` 指标 + `RefundPostProcessAttempt(target=LEDGER, status=FAILED)`，由有限重试/对账补齐（Saga，禁 2PC）。
7. **编排位置**：记账作为后处理编排的一个「目标（LEDGER）」纳入 `RefundPostProcessOrchestrator`（ADR-0017），与 fulfillment/entitlement 共用「一次调用一条记录、失败不回滚」的语义，保证重复收敛只记一次账。
8. **契约漂移**：以**代码**为准——`PostingRequest.EntryRequest` 用 `accountId`，本 Feature 的 contracts 按 `accountId` 撰写；建议后续由 004 负责人回修 `contracts/post-refund.md`（**本 Feature 不修改 004 的文件**）。

### 备选方案

- **A. 记账回归 004-ledger 单独推进**：职责上更「纯」，但 004 文档仍标记未完成、ADR-0008~0011 未确认，会阻塞本 Feature 的 Constitution §II.3 合规；且记账金额依赖本 Feature 才存在的 `refundedAmountMinor` —— **否决**。
- **B. 退款记账延后（与 Roadmap「不包含 Ledger 冲正」一致）**：Roadmap Phase 5 原文确实写着「不包含真实出款和 Ledger 冲正」，但 Constitution 已升至 v2.1.0 且 ledger-service 已落地，继续延后会留下「已确认退款无账务分录」的硬缺口，违反 §II.3 —— **否决**（Roadmap 该句需按本 ADR 结论修订）。
- **C. 由 refund-service 直接写 ledger 表**：违反 Database-per-service 与「Ledger 只被依赖」—— **否决**。
- **D. 005 承接退款侧接入、004 保留账本侧所有权（采纳）**：与 ADR-0016 的金额变更同源，避免中间态，且不动 004 已实现的账本资产。

### Consequences（后果）

**正面**：退款侧满足 Constitution §II.3；与支付侧记账对称，全局借贷可平衡、可追溯；记账与后处理共用编排与幂等语义，重复收敛只记一次。

**代价 / 风险**：

- 跨 spec 归属调整需负责人确认（004 的 T013/T014 标记承接，roadmap/004 acceptance 需同步修订）。
- 记账失败会留下「退款已成功但账本无冲正分录」的中间态，依赖对账补齐 —— 与支付侧既有取舍一致（ADR-0009），可接受但 MUST 有指标与告警。
- 记账依赖 `ledger-service` 可用性；当前无熔断/降级（`[Phase 按需延后]`），账本不可用会增加退款受理延迟（同步链路内）。
- 科目 `accountId` 需以账本预置科目实际 ID 为准，硬编码风险要求配置化（**建议**：科目 ID 走配置或账本侧按 `entryType` 映射，待 004 负责人确认）。

### 关联

- Constitution §II.3、§IV、§V.7、§8.4
- `docs/specs/004-ledger/`（US2 / FR-006 / T013~T014 / `contracts/post-refund.md`）
- `docs/adr/0004-ledger-design-decisions.md`（ADR-0008~0011，其中 ADR-0009 记账触发与一致性、ADR-0011 MVP 记账范围）
- `005-refund` spec：US4、FR-009~FR-010；contracts/refund-orchestration.md §4
- `ledger-service/.../api/LedgerController.java`、`payment-service/.../infra/client/FeignLedgerPostingGateway.java`、`common/common-dto/.../rpc/PostingRequest.java`
