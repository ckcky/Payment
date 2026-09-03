# ADR-0016 ~ ADR-0018、ADR-0047：退款（Feature 005）架构决策集合

> 本文件合并 Feature `005-refund` 的架构决策为单一决策记录，便于集中审阅（同 `0003` / `0004` 的合并风格）。
> 编号为内部决策标签（**ADR-0016 ~ ADR-0018、ADR-0047**），状态独立标注。
> **ADR-0047 的编号说明**：它产生于 ADR-0016 回退后的文档同步阶段（2026-08-31），主题仍属退款领域，故并入本文件而非新开文档。之所以跳到 0047，是因为 `docs/architecture/next-stage-design.md` §9 已把 **ADR-0038~0046 整段预留**给下一阶段提案清单（Mock 收银台 / 幂等键 / 库存域 / Redis 等）；为避免编号冲突，新提案自 **ADR-0047** 起。
> 涉及 Constitution §8「人类决策边界」的决策，均**待负责人确认**（2026-08-29）。

> ✅ **编号冲突已解决（2026-08-29）**：本文件最初位于 `docs/adr/0005-refund-decisions.md` 并使用 ADR-0012~0014，与既有的 `docs/adr/0005-payment-reliability-impl-decisions.md`（Feature 003 实现期决策，占用 **ADR-0012~0015**）**冲突**。
> 已采用推荐方案 ① 解决：文件重命名为 `0006-refund-decisions.md`，标签重编号为 **ADR-0016~0018**，既有 `0005-payment-reliability-impl-decisions.md` 与其 ADR-0012~0015 **保持不变**，全局引用已同步更新。编号现已唯一，无歧义。

> **负责人裁决（2026-08-30，落地 2026-08-31）**
> - **ADR-0016 部分退款** → ❌ **Rejected（不做）**。本期只支持**全额退款**：`PARTIALLY_SUCCEEDED` 枚举保留但**无调用方、不可达**；渠道侧若返回部分成功按 `UNKNOWN` 处理走对账收敛；累计口径一律按**申请额**占位（在途保守占用，防并发超退 H1）。已实现的 `refundedAmountMinor` 全链路**已回退**，清单见 ADR-0016 内的「回退落地记录」。重新开放时须同步解决：退款单拆分模型、多次退累计口径、权益/履约按比例回收、Ledger 部分冲正分录（Constitution §8 边界，须重新确认）。
> - **ADR-0017 refund → fulfillment 编排** → ✅ **Accepted**。
> - **ADR-0018 refund → ledger 记账接入** → ✅ **Accepted**。
> - **ADR-0047 退款金额校验口径（是否强制「申请额 = 可退全额」）** → 🟡 **Proposed（实现已按「只做累计不超付」落地，待确认）**，见文末。
>
> 落地口径见 [technical-solution §2.4](../architecture/technical-solution.md#24-本阶段范围裁剪与预留契约)。

---

<a id="adr-0016"></a>
## ADR-0016: 部分退款支持模型（如何让 PARTIALLY_SUCCEEDED 可达、部分金额如何跟踪）

- **状态**：❌ **Rejected / Not Implemented（延后）** —— 2026-08-30 负责人裁决「**部分退款不做**」；代码已按裁决**回退**（2026-08-31 完成）
- **日期**：2026-08-29（裁决 2026-08-30，回退落地 2026-08-31）
- **决策者**：项目 Owner
- **关联 Feature**：`005-refund`（spec US1 / FR-001~FR-003 / 缺口 G1）
- **关联 Constitution 条款**：§8.3（新增关键资金字段/表）、§8.8（状态机变更）

> ### 回退落地记录（2026-08-31）
>
> 原始方案曾按最简实现落地，裁决后**整体回退**，受影响清单与处置：
>
> | 位置 | 回退动作 |
> |---|---|
> | `common-dto/rpc/RefundAttemptResponse` | 回到 3 分量 `(refundId, status, channelReference)`，删除 `refundedMinor` |
> | `payment/application/channel/ChannelResult` | 回到 5 分量，删除 `refundedMinor` 与 2 参 `success(...)` 重载 |
> | `payment/infra/channel/MockChannelAdapter` | 删除 `configuredRefundMinor` / `setRefundMinor` 与部分退款分支 |
> | `payment/application/PaymentRefundService` | 组装回复不再传实退金额 |
> | `refund/domain/Refund` | 删除 `refundedAmountMinor` 字段、`getRefundedAmountMinor()`；`rehydrate` 回到 13 参；`succeed()` 收敛为无条件全额成功 |
> | `refund/application/RefundApplicationService` | 累计口径回到「一律按**申请额**」占位（在途保守占用，防并发超退 H1）；`switch` 回到三态 |
> | `refund/api/RefundResponse` | 回到 7 分量，删除 `refundedAmountMinor` |
> | `refund/application/RefundPostProcessOrchestrator` | 记账金额一律取 `refund.getAmountMinor()` |
> | `refund/infra/persistence/refund/{RefundEntity, MybatisRefundRepository}` | 删除该字段的列映射与读写 |
> | `deployment/schema/06-refund-schema.sql`、`refund-service/src/test/resources/schema.sql` | 删除 `refunded_amount_minor` 列（已部署环境需手工 `ALTER TABLE \`refund\`.\`refunds\` DROP COLUMN \`refunded_amount_minor\`;`） |
>
> **刻意保留（不可达但保留）**：`RefundStatus.PARTIALLY_SUCCEEDED` 枚举与 `Refund.partiallySucceed(long)`。
> 理由：若删除枚举，任何历史 `status='PARTIALLY_SUCCEEDED'` 行在 `MybatisRefundRepository#toDomain` 的 `RefundStatus.valueOf(...)` 处抛 `IllegalArgumentException`，
> 会连带打挂 `findByPaymentId` → 整条退款受理路径。保留一个**无调用方**的方法，代价远低于数据迁移风险。
> 该方法 Javadoc 已显式标注「ADR-0016 已否决、当前无调用方」。

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

<a id="adr-0017"></a>
## ADR-0017: refund → fulfillment 编排（补齐缺失 RPC vs 修改文档声明）

- **状态**：✅ **Accepted**（2026-08-30 负责人裁决 accept；实现已落地）
- **日期**：2026-08-29（裁决 2026-08-30）
- **决策者**：项目 Owner
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

<a id="adr-0018"></a>
## ADR-0018: refund → ledger 记账接入（与 spec 004-ledger 的归属与时机）

- **状态**：✅ **Accepted**（2026-08-30 负责人裁决 accept；实现已落地）
- **日期**：2026-08-29（裁决 2026-08-30）
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

---

<a id="adr-0047"></a>
## ADR-0047: 退款金额校验口径（ADR-0016 回退后，是否强制「申请额 = 可退全额」）

**状态**：✅ **Accepted**
**日期**：2026-08-31（提出）｜2026-09-03（收口为 Accepted）
**触发**：ADR-0016（部分退款）被裁决 Rejected 并回退后，`docs/architecture/technical-solution.md` §8.3 遗留一条未闭环要求——「退款资格判断除『累计不超付』外，MUST 增加**全额校验**：申请金额 ≠ 可退全额 → 直接 `REJECTED`」。本次文档同步时必须对此给出确定口径。

> **🧭 收口注记（2026-09-03，Phase 5 文档治理）**
> 本 ADR 提出时即写明「实现已按强度 B 落地并通过全量测试」，但状态长期停留在 `Proposed`。
> 现已逐条验证运行口径与决策一致，收口为 **Accepted**。**本注记只改状态与补充证据，未改动决策正文。**
>
> #### 落地验证（2026-09-03 核对）
>
> | 决策项 | 实现位置 | 结论 |
> |---|---|---|
> | `RefundPolicy.decide` 仅三条校验（币种 / 为正 / 累计不超付），**无**全额等值约束 | `refund-service/.../domain/RefundPolicy.java` | ✅ 完全一致（源码仅 3 个 `if`，无 `requestedMinor == refundableAmount` 判断） |
> | 不记 `refundedAmountMinor` | `common-dto/.../rpc/RefundAttemptResponse.java` 字段已移除 | ✅ 已移除 |
> | 不落 `PARTIALLY_SUCCEEDED` | `Refund.partiallySucceed()` **零调用**（死方法），运行时无退款可进入该状态 | ✅ 运行时符合（⚠️ 但枚举值与死方法仍在，见下） |
> | 多笔退款由 `refund_intake_locks` 行锁串行化 | `deployment/schema/06-refund-schema.sql`、`RefundIntakeLockMapper.java`、`RefundApplicationService.java` | ✅ 已建 |
>
> ⚠️ **残留死代码（已登记待办，不属本 ADR 范围）**：ADR-0016 的回退**不彻底**——
> `RefundStatus.PARTIALLY_SUCCEEDED` 枚举值、`Refund.partiallySucceed()` 死方法，
> 以及 `RefundPostProcessOrchestrator` / `LedgerPostingGateway` / `Refund` 三处引用该状态的 Javadoc 仍在。
> 它们不影响运行时口径（无调用方），但会造成「系统支持部分成功」的误读。
> 见 `docs/operations/code-debt-backlog.md` #11。

### Context（背景）

ADR-0016 裁决「部分退款不做」后，同一个「不做」有两种截然不同的落地强度：

- **强度 A（严格全额）**：任何 `requestedMinor != refundableAmount` 的申请一律 `REJECTED`。即一笔 1000 的支付，第一次只能退 1000，退 300 会被拒。
- **强度 B（单笔全额 + 多笔累计）**：不校验单笔是否等于可退全额，只校验「累计申请额 + 本次申请额 ≤ 已支付金额」（H1 防超退）。即一笔 1000 的支付，可分多次退 300 / 400 / 300，每次要么全额成功要么失败，**不存在「一笔退款部分成功」**。

关键事实与约束：

1. `001-core-business-model/spec.md` 第 66–67、289 行明确规定：*「订单取消、部分退款、全部退款……必须依据可退款金额判断」*、*「部分退款、多次退款的累计金额不得超过已支付且尚未退款金额」*、*「退款默认支持部分退款和多次退款」* —— 强度 A 与这条**已 Accepted 的基线 spec 直接冲突**。
2. 现有 `RefundPolicy.decide` 只做强度 B，且 `RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit` 用 **300 + 400 + 400（paid=1000）** 的多笔场景验证防超退；改强度 A 会使第一笔 300 即被拒，该测试直接失效。
3. `005-refund/spec.md` FR-003 与 data-model §3 已按强度 B 定稿：累计一律按申请额（终态与在途一视同仁）。
4. ADR-0016 回退掉的实体是 `refundedAmountMinor` 与 `PARTIALLY_SUCCEEDED`——即**「单笔退款内部的部分成功追踪」**；它从未触及「同一支付能否分多笔退款」。

### Decision（决策）

**采纳强度 B：只做「累计不超付」（H1），不引入全额等值校验。**

- `RefundPolicy.decide` 保持三条校验（币种一致 / 金额为正 / 累计 + 申请 ≤ 已付），**不新增** `requestedMinor == refundableAmount` 约束。
- 「部分退款不做」的准确含义收敛为：**单笔退款没有「部分成功」这一状态**——渠道只回 `SUCCEEDED / FAILED / UNKNOWN` 三态，成功即视为该笔申请额全额退回；若真实发生部分退回，按 `UNKNOWN` 处理并走对账收敛，**不落 `PARTIALLY_SUCCEEDED`、不记 `refundedAmountMinor`**。
- 同一支付**允许多笔退款**（每笔独立幂等键、每笔按申请额累计占用额度），由 `refund_intake_locks` 行锁串行化受理，累计超额者落 `REJECTED` 且不发起渠道尝试。
- `technical-solution.md` §8.3 的「全额校验 MUST」要求**撤销**，改为上述口径；§10 风险表中「不支持部分退款」的应对条款同步改为「累计口径 + 超额显式 `REJECTED`」。

### 备选方案

- **A. 强制全额等值（严格全额）**：语义最贴合「只支持全额退款」的字面表述。但违反 `001-core-business-model` 已 Accepted 的「支持部分退款和多次退款」基线，且需重写防超退测试与 FR-003。**否决**（属 Constitution §8 边界，且与既有 spec 冲突，不能由实现侧单方面收紧）。
- **C. 全额校验做成可配置开关（默认关）**：保留两种口径。但会引入「同一份数据两种语义」的运维分叉，且当前无真实需求驱动。**否决**（违反最简实现原则）。
- **D. 只做累计不超付（采纳）**：与 001 基线一致、与现有测试一致、改动量 0，且 H1 防超退不变量完整保留。**采纳**。

### Consequences（后果）

**正面**

- 与 `001-core-business-model` 的「支持部分退款和多次退款」基线自洽，无需改 spec 或破坏既有验收。
- H1 防超退不变量（累计 + 申请 ≤ 已付，并发由行锁串行化）完整保留，资金正确性不受影响。
- 实现改动量 0，全量 15 reactor 条目保持 BUILD SUCCESS。

**负面 / 已知取舍**

- 语义上**确实**支持了「对 1000 的支付退 300」这种业务意义上的部分退款，与「部分退款不做」的字面表述存在张力。**本文档即为该张力的显式记录**：被否掉的是**单笔退款的部分成功追踪**，不是**多笔退款**。
- 权益/履约回收仍按「整笔退款」处理（`RefundPostProcessOrchestrator` 对每笔退款各撤销一次），多笔场景下下游会收到多次撤销请求，依赖其幂等。
- 若将来业务方要求「只允许一次性退全额」，须另立 ADR 并同步修订 `001-core-business-model`。

**待负责人确认点**

1. 是否接受「部分退款不做 = 不做单笔部分成功追踪，但保留多笔退款」这一口径？
2. 若不接受，则须同时修订 `001-core-business-model/spec.md` 第 66–67、289 行，并重写 `005-refund` FR-003 与防超退测试 —— 属 Constitution §8 边界，需显式裁决。

### 关联

- ADR-0016（部分退款 Rejected，本 ADR 为其回退后的口径收口）
- `docs/specs/001-core-business-model/spec.md`（第 66–67、289 行：部分/多次退款基线）
- `docs/specs/005-refund/spec.md` FR-003、`data-model.md` §3（累计口径）
- `docs/architecture/technical-solution.md` §2.4 #6、§8.3、§10
- `refund-service/.../domain/RefundPolicy.java`、`RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit`
