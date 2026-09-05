# Feature Specification: Refund 退款（部分/全部退款、幂等、后处理编排与记账）

**Feature Branch**: `005-refund`

**Created**: 2026-08-29

**Status**: Draft（设计决策见 `docs/adr/0006-refund-decisions.md`，ADR-0016~0018 待负责人决策）

**Input**: 用户描述：为 Roadmap Phase 5 · Refund 建立 Spec Kit 产物。本 Feature **不是从零构建**——`refund-service`（端口 8085，Schema `refund`）核心链路已实现，本 Spec 是**缺口补齐 / 收口**型 Spec。

> 本 Feature 对应 `docs/architecture/roadmap.md` 的 **Phase 5 · Refund（Roadmap 标签「003 Refund」）**，但本仓库 `init-options.json` 规定 spec 目录采用**顺序编号**，故物理目录为 `005-refund`，与 Roadmap 阶段标签**解耦**（同 `003-payment-reliability`、`004-ledger` 既定约定，见 Clarifications）。
> 所有开放性设计分歧点已落到 ADR-0016~0018，**已由负责人裁决（2026-08-30）**：ADR-0016 **Rejected（不做）**、ADR-0017 / ADR-0018 **Accepted**。实现已于 2026-08-31 完成并验收。

> ## 负责人裁决（2026-08-30）· 落地（2026-08-31）
>
> | ADR | 裁决 | 影响 |
> |---|---|---|
> | **ADR-0016 部分退款** | ❌ **Rejected（不做）** | **US1 整体移除**；`PARTIALLY_SUCCEEDED` 与 `partiallySucceed()` 保留为**无调用方的不可达实现**；累计口径**一律按申请额** |
> | **ADR-0017 refund→fulfillment** | ✅ **Accepted** | US2 全量落地 |
> | **ADR-0018 refund→ledger** | ✅ **Accepted** | US4 全量落地，记账金额 = `amountMinor` |
>
> - 裁决口径：**单笔退款没有「部分成功」**。渠道只回三态（`SUCCEEDED`/`FAILED`/`UNKNOWN`），成功即视为该笔申请额全额退回；若真实发生部分退回，走 `UNKNOWN` + 对账收敛，**不落 `PARTIALLY_SUCCEEDED`、不记 `refundedAmountMinor`**。
> - ⭐ **金额校验口径（新增 ADR-0047，Proposed）**：**同一支付仍支持多笔退款**（每笔独立幂等键，按申请额累计占用额度，受 `refund_intake_locks` 行锁串行化）。`RefundPolicy.decide` **只做**「币种一致 / 金额为正 / 累计申请额 + 本次申请额 ≤ 已支付金额」，**不做**「申请额 = 可退全额」的等值校验 —— 后者会与 `001-core-business-model/spec.md`「退款默认支持部分退款和多次退款」的已 Accepted 基线冲突。详见 [ADR-0047](../adr/0006-refund-decisions.md#adr-0047-退款金额校验口径adr-0016-回退后是否强制申请额--可退全额)。
> - ⚠️ **落地补充说明（2026-08-31）**：ADR-0016 曾按最简实现落地过（`refundedAmountMinor` 全链路），裁决后**已整体回退**。
>   回退清单见 [ADR-0016 回退落地记录](../adr/0006-refund-decisions.md)。
> - US2 / US3 中涉及 `PARTIALLY_SUCCEEDED` 的验收条款**一并移除**；**全额路径的条款全部保持有效**。
> - 落地口径见 [technical-solution §2.4](../architecture/technical-solution.md#24-本阶段范围裁剪与预留契约)。

## 当前代码现实（已核实，禁止按绿地项目理解）

**`refund-service` 已远超「骨架」**：`technical-solution.md:101` 与 `roadmap.md:11` 仍标注为「骨架」，但实测代码已落地——领域聚合与状态机（`Refund`/`RefundStatus`）、资格与可退款金额策略（`RefundPolicy`）、MyBatis 持久化（悲观锁 + 乐观锁）、幂等（DB 唯一约束 `uk_refunds_idempotency_key` + `refund_intake_locks`）、出站 RPC（payment / entitlement）、对账事实接口、以及单元测试与 Testcontainers 集成测试。详见 `docs/architecture/systems/refund-service.md`。

**因此本 Spec 的范围是「补缺口」，不是「建服务」。三项已核实的真实缺口：**

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| G1 | **部分退款端到端不可达**：`PARTIALLY_SUCCEEDED` 状态与 `partiallySucceed()` 已实现但无调用方；`attemptRefund` 只回 `SUCCEEDED/FAILED/UNKNOWN`，`RefundApplicationService.java:99-103` 只处理三者 | `Refund.java:86`、`RefundApplicationService.java:99-103`、`RefundAttemptResponse.java` | Roadmap 验收标准「部分/全部退款可追踪」**不满足** |
| G2 | **refund → fulfillment RPC 完全缺失**：仅有 `EntitlementGateway`/`EntitlementFeignClient`，无任何 fulfillment 网关；fulfillment-service 也无退款端点（只有 `/internal/fulfillments/on-payment-succeeded`） | 无 `FulfillmentGateway`；`FulfillmentController` 仅 `GET /{id}` | 与 `technical-solution.md §4.3.3`「refund→fulfillment/entitlement RPC」矛盾 |
| G3 | **`resolve()` 缺乏防御性前置断言**：`RefundRpcCallbackService.resolveRefund` 直接驱动状态机，若退款仍处 `REQUESTED`（尚未 `process()`）则依赖状态机抛通用 `STATE_TRANSITION_VIOLATION` | `RefundRpcCallbackService.java:24-36` | 错误语义不清晰，终态吸收与非法前置混淆 |

**另发现（超出既定 3 项，已在 Clarifications/ADR 记录）**：`ledger-service` 与 payment 侧 `LedgerPostingGateway` **已实现**，但 `refund-service` 无任何 ledger 集成（spec `004-ledger` US2 / T013~T014 的退款记账未落地）。

### 能力现状矩阵（诚实标注，禁止按绿地项目理解）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| 退款领域聚合与状态机（含 `PARTIALLY_SUCCEEDED` 定义） | 已实现 | `domain/Refund.java`、`domain/RefundStatus.java` |
| 资格与可退款金额策略（币种/正数/累计不超限） | 已实现（口径待扩展） | `domain/RefundPolicy.java:28` |
| MyBatis 持久化（悲观锁 + 乐观锁） | 已实现 | `MybatisRefundRepository`、`RefundIntakeLockMapper` |
| 受理幂等（唯一约束 + 回查 + 重复键捕获） | 已实现 | `uk_refunds_idempotency_key`、`RefundApplicationService.java:57/164` |
| refund → payment（金额查询 + 渠道退款尝试） | 已实现 | `PaymentRefundFeignClient` |
| UNKNOWN 收敛（`resolve`） | 已实现（缺防御断言 G3） | `RefundRpcCallbackService.java:24` |
| 对账事实暴露（仅 `SUCCEEDED`） | 已实现 | `RefundFactsService.java:26` |
| 退款后权益吊销 RPC | 已实现（失败被静默吞掉） | `RefundApplicationService.java:113` |
| 指标 + 资金审计（5 个计数 + `FINANCIAL_AUDIT`） | 已实现 | `refund.initiated/duplicate/rejected/succeeded/failed/unknown` |
| 单元 + Testcontainers 集成测试 | 已实现 | `refund-service/src/test/...`（6 个测试类） |
| ~~部分退款（部分金额追踪、`PARTIALLY_SUCCEEDED` 可达）~~ | ⛔ **裁决不做（ADR-0016 Rejected）**，曾实现后回退 | `PARTIALLY_SUCCEEDED` 枚举保留但无调用方 |
| **refund → fulfillment 撤销 RPC** | ✅ **已补齐（ADR-0017）** | `FulfillmentGateway` + `FulfillmentRefundController` |
| **后处理失败可独立追踪** | **[目标]（缺口 G2 衍生）** | 当前 `catch (RuntimeException ignored)` |
| **`resolve` 防御性前置断言** | **[目标]（缺口 G3）** | 依赖状态机通用异常 |
| **refund → ledger 记账** | **[目标]（承接 004 US2）** | 账本侧已实现，退款侧未接入 |
| 已交付履约 / 已消费权益的回收 | **[待定]** | `Fulfillment.cancel()` 仅 `PENDING → CANCELLED`；Roadmap Phase 5 明确「不包含」 |
| 复杂退款审批流程 | **[Phase 延后]** | Roadmap Phase 5「不包含」 |
| 出站 Feign 超时 / 熔断降级 | **[目标] / [Phase 按需延后]** | 当前沿用 OpenFeign 默认值 |

## User Scenarios & Testing

> 标注约定：无标记 = 已实现；`[目标]` = 建议值待确认；`[待定]` = 留待后续；`[P1/P2/P3]` = 优先级。

### ~~User Story 1 - 部分退款可追踪且累计金额不超限 (Priority: P1)~~ ⛔ 不做（ADR-0016 Rejected）

> **本节整体不适用**，保留为历史决策记录。重新开放部分退款时，本节与 [ADR-0016](../adr/0006-refund-decisions.md) 的「回退落地记录」即为准绳。
>
> ⭐ **替代口径（当前生效）**：累计额度一律按**申请额 `amountMinor`** 计（含在途 `PROCESSING`/`UNKNOWN` 保守占位），
> 超额申请 `REJECTED` 且不发起渠道尝试——防超退不变量（H1）不受影响。用例见
> `RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit`。

作为平台资金运营，我希望当渠道只退回了申请金额的一部分时，这笔退款被明确记录为「部分成功」并记下**实际退款金额**，剩余可退额度据此正确释放，从而让「部分/全部退款可追踪、累计金额不超限」成立。

**Why this priority**: 这是 Roadmap Phase 5 验收标准的第一条，也是当前唯一**结构性缺失**的能力（G1）。全额退款按「申请金额 = 已退金额」处理即可，部分退款必须有独立的「已确认退款金额」事实，否则要么额度被永久占死（按申请额计），要么可能超退（按 0 计）。

**Independent Test**: 对一笔已支付 1000 的支付发起退款 1000，令 Mock Channel 只退回 300；断言退款状态为 `PARTIALLY_SUCCEEDED`、`refundedAmountMinor=300`；再对同一支付发起退款 700（新幂等键），断言被批准且成功；再发起退款 1，断言因累计超限被 `REJECTED`。

**Acceptance Scenarios**:

1. **Given** 一笔已支付 1000 的支付，**When** 申请退款 1000 而渠道仅退回 300，**Then** 退款落 `PARTIALLY_SUCCEEDED`，`refundedAmountMinor=300`，且 `failureReason`/结果可解释。
2. **Given** 该支付已有一笔 `PARTIALLY_SUCCEEDED`（已退 300）的退款，**When** 以新幂等键申请退款 700，**Then** `RefundPolicy` 按「累计已确认 300 + 申请 700 ≤ 1000」批准，退款成功。
3. **Given** 该支付累计已确认退款已达 1000，**When** 再申请任意正额退款，**Then** 落 `REJECTED` 且原因明确，不发起渠道退款尝试。
4. **Given** 渠道退回金额等于申请金额，**When** 结果回传，**Then** 落 `SUCCEEDED`（非部分成功），`refundedAmountMinor == amountMinor`。
5. **Given** 处理中的退款（`PROCESSING`/`UNKNOWN`，尚无已确认金额），**When** 计算累计占用额度，**Then** 以**申请金额**占位计入（保守防超退，见 data-model §3）。

---

### User Story 2 - 退款后处理编排完整且失败可独立追踪 (Priority: P1)

作为平台，我希望一笔确认退款（成功或部分成功）后，编排层**同时**请求 fulfillment 撤销与 entitlement 吊销；任一侧失败都不回滚退款成功事实，但 MUST 被独立记录下来，可查询、可重试、可对账。

**Why this priority**: Roadmap「包含：退款后的 Fulfillment/Entitlement RPC」与「验收标准：后处理失败可独立追踪」。当前只有 entitlement 一条 RPC 且失败被 `catch (RuntimeException ignored)` 静默吞掉（`RefundApplicationService.java:113`），fulfillment 完全缺失（G2）——「后处理失败可独立追踪」实际上不成立。

**Independent Test**: 令 entitlement 与 fulfillment 的退款后处理 RPC 均抛异常；断言退款仍为 `SUCCEEDED`，且可查到两条失败的后处理尝试记录（目标服务、失败原因、尝试次数），并产出 `refund.post_process_failed` 指标与审计。

**Acceptance Scenarios**:

1. **Given** 一笔退款被确认为 `SUCCEEDED`，**When** 后处理编排执行，**Then** 依次（幂等地）调用 fulfillment 撤销与 entitlement 吊销，各自携带 `refundNo` 作为幂等依据。
2. **Given** 后处理 RPC 抛异常或超时，**When** 编排捕获，**Then** 退款成功事实**不被回滚**，失败被记录为一次独立的后处理尝试（含目标、原因、时间），并递增 `refund.post_process_failed`。
3. **Given** 同一笔退款被重复收敛（重复 resolve / 重复回调），**When** 后处理编排再次执行，**Then** 下游按幂等键吸收，不重复撤销、不重复吊销。
4. **Given** 一笔 `PARTIALLY_SUCCEEDED` 退款，**When** 后处理编排执行，**Then** 同样触发（部分退款也影响履约/权益），且请求体携带**实际退款金额**。
5. **Given** 退款处于 `UNKNOWN`，**When** 结果未收敛，**Then** **不**触发任何后处理（未确认结果不得产生不可逆的权利变更）。
6. **Given** fulfillment 侧履约已 `DELIVERED`/`PROCESSING`，**When** 收到退款撤销请求，**Then** fulfillment 按自身状态机决定（当前仅 `PENDING → CANCELLED` 可行），返回可解释结果而非被强制改写（Constitution 边界 #6；能力边界见 ADR-0017）。

---

### User Story 3 - 重复退款幂等、未知退款不重复执行、收敛有防御边界 (Priority: P2)

作为平台，我希望重复提交的退款申请被幂等吸收（不重复退款），处于 `UNKNOWN` 的退款不被重复发起渠道退款尝试，且收敛入口对非法前置状态给出明确拒绝而非含糊异常。

**Why this priority**: 全额退款的幂等与 UNKNOWN 不猜成败**已实现**（`uk_refunds_idempotency_key` + 状态机终态吸收）。本 US 是**收口**：把部分退款引入的新状态纳入同一幂等语义，并补齐 G3 的防御断言，避免「部分成功 + 重复收敛」组合产生超额退款。

**Independent Test**: 以相同幂等键连续提交两次退款，断言只发起一次渠道尝试；将一笔退款置为 `UNKNOWN` 后重复调用 `resolve`，断言只收敛一次、只触发一次后处理；对仍处于 `REQUESTED` 的退款调用 `resolve`，断言返回明确的 `STATE_TRANSITION_VIOLATION`。

**Acceptance Scenarios**:

1. **Given** 同一幂等键的退款请求重复到达，**When** 受理，**Then** 返回首次结果，`refund.duplicate` 递增，不发起第二次渠道退款尝试（已实现）。
2. **Given** 一笔退款处于 `UNKNOWN`，**When** 未获得权威结果前被再次处理，**Then** **不**重复发起渠道退款尝试（未收敛前不重复执行不可确认的资金动作）。
3. **Given** 一笔 `UNKNOWN` 退款被权威结果收敛为成功且重复收敛多次，**When** 状态机吸收，**Then** 只触发一次后处理与一次记账。
4. **Given** 一笔退款仍处于 `REQUESTED`（尚未 `process()`），**When** 调用 `resolve`，**Then** 被**显式**拒绝（`STATE_TRANSITION_VIOLATION`，含当前状态），而非依赖状态机的通用异常（G3）。
5. **Given** 一笔已终态退款收到冲突的收敛结果，**When** 状态机处理，**Then** 终态吸收，不覆盖、不重复推进（已实现）。

---

### User Story 4 - 确认退款在账本留下平衡冲正分录 (Priority: P2)

作为平台，我希望每一笔**已确认**的退款都经 `LedgerPostingGateway` 向 `ledger-service` 记一笔借贷平衡的冲正分录，金额以**实际退款金额**为准，从而满足 Constitution §II.3「一切资金变动 MUST 经 ledger-service 复式记账」并消除 spec `004-ledger` US2（T013/T014）在退款侧未落地的缺口。

**Why this priority**: Constitution §II.3 是硬性铁律，且 `ledger-service`（8090）与 payment 侧 `LedgerPostingGateway` **已实现并接入**——退款侧是当前唯一未接入的已确认资金变动。但记账依赖 ledger 服务可用性与科目表确认，故列 P2（依赖 ADR-0018）。

**Independent Test**: 使一笔退款进入 `SUCCEEDED`，断言 `ledger-service` 中存在 `sourceType=REFUND`、`sourceId=<refundNo>` 且借贷平衡的 `Posting`；重复触发同一退款记账，断言幂等返回首次 `Posting`；对 `PARTIALLY_SUCCEEDED`（已退 300）断言记账金额为 300 而非申请额。

**Acceptance Scenarios**:

1. **Given** 一笔退款被确认为 `SUCCEEDED`（已退 R），**When** 记账触发，**Then** 以幂等键 `REFUND:<refundIdempotencyKey>` 提交冲正 `Posting`（DEBIT `MERCHANT_PAYABLE` R / CREDIT `CUSTOMER_CASH` R），借贷平衡。
2. **Given** 一笔 `PARTIALLY_SUCCEEDED` 退款（申请 1000、已退 300），**When** 记账触发，**Then** 记账金额为 **300**（实际退款金额），非申请金额。
3. **Given** 记账 RPC 失败或超时，**When** 调用方处理，**Then** **不回滚**退款成功事实，记录 `ledger.posting_failed` 并进入重试/对账兜底（Saga 语义，禁 2PC）。
4. **Given** 同一退款被重复提交记账，**When** 携带相同幂等键，**Then** 返回首次 `Posting`，不产生重复分录。

---

### User Story 5 - 退款可观测与对账事实覆盖部分成功 (Priority: P3)

作为平台 SRE/资金运营，我希望退款的全部分支（成功/部分成功/失败/未知/拒绝）都有业务指标与资金审计，且对账事实接口覆盖「部分成功」并以实际退款金额暴露，从而让「部分/全部退款可追踪」在对账侧也成立。

**Why this priority**: G1 引入 `PARTIALLY_SUCCEEDED` 后，`RefundFactsService.confirmedFacts()` 当前只返回 `SUCCEEDED`（`RefundFactsService.java:27`），部分退款会成为对账孤儿。指标侧 `refund.initiated/duplicate/rejected/succeeded/failed/unknown` 已实现，缺 `refund.partially_succeeded` 与 `refund.post_process_failed`。

**Independent Test**: 触发一次部分成功退款，断言 `refund.partially_succeeded` 递增、`FINANCIAL_AUDIT` 含前后状态，且 `confirmed-facts` 能以实际退款金额（300）返回该笔事实。

**Acceptance Scenarios**:

1. **Given** 一笔退款进入 `PARTIALLY_SUCCEEDED`，**When** 结果落库，**Then** 指标 `refund.partially_succeeded` 递增，并写入含前后状态与实际退款金额的 `FINANCIAL_AUDIT`。
2. **Given** 存在 `PARTIALLY_SUCCEEDED` 退款，**When** reconciliation 拉取 `confirmed-facts`，**Then** 该笔以**实际退款金额**出现 [目标]（口径见 ADR-0016）。
3. **Given** 后处理失败，**When** 编排捕获，**Then** 指标 `refund.post_process_failed` 递增，可被告警（Constitution Observability §4「退款失败 MUST 告警」）。

---

### Edge Cases

- **渠道退回金额 > 申请金额**：视为数据错误，MUST 拒绝推进为成功（落 `UNKNOWN` + 告警），不得记入 `refundedAmountMinor`（防资金放大）。
- **渠道退回金额为 0**：按 `FAILED`/`UNKNOWN` 处理（取决于渠道语义），MUST NOT 落 `PARTIALLY_SUCCEEDED`（不变量 `0 < refundedAmountMinor < amountMinor`）。
- **部分成功后剩余额度的再次申请**：`PARTIALLY_SUCCEEDED` 为终态，剩余金额 MUST 以**新幂等键**另开一笔退款（终态吸收一致性，见 ADR-0016）。
- **并发受理同一支付**：`refund_intake_locks` 悲观行锁串行化「读累计 + 写受理」（已实现，H1 防超退）。
- **部分退款 + 失败的组合**：先部分成功、后续尝试失败 → 状态保持 `PARTIALLY_SUCCEEDED`（终态吸收），已退金额不回退。
- **额度计算口径混合**：处理中/未知按申请额占位，终态按已确认额计（data-model §3 不变量）。
- **后处理 RPC 超时**：按失败记录，不做阻塞重试（MVP 为同步链路内的有限重试 [目标] 3 次 / 1s-2s-4s），耗尽后进入可查询的失败清单，由对账/人工兜底。
- **fulfillment 已交付**：撤销请求被 fulfillment 自身状态机拒绝（`Fulfillment.cancel()` 仅 `PENDING → CANCELLED`），返回可解释状态，不视为编排失败。
- **账本服务不可用**：不回滚退款成功，记账进入兜底清单（ADR-0018）。
- **契约未升级的下游（payment-service 未回传 `refundedAmountMinor`）**：按 ADR-0016 默认策略视为全额成功，保持既有行为，不静默改判为部分成功。
- **部分成功后权益已部分消费**：entitlement 按自身规则处理（可部分吊销/拒绝），返回的 `NOOP`/`FAILED` 被如实记录；统一回收政策不在本 Feature（Out of Scope）。
- **同一支付的多笔退款并发受理**：`refund_intake_locks` 行锁串行化；先到者落库后，后到者读到的累计额已含前者，超额者落 `REJECTED`。
- **退款受理成功但后处理全失败**：退款保持成功事实，三条 `RefundPostProcessAttempt` 均为失败，进入可查询清单；由对账/人工按 `refundNo` 重放，重放沿用原幂等依据。
- **跨服务 traceId 断裂**：后处理与记账调用 MUST 透传 `traceId`（`TraceIdRequestInterceptor`），保证资金审计可跨服务串联。
- **重复记账与重复后处理的组合**：幂等键分别以 `REFUND:<refundIdempotencyKey>`（账本侧唯一约束）与 `uk_pp_idem`（后处理侧唯一约束）兜底，二者互不影响。

## Out of Scope（明确不做）

按 Roadmap Phase 5「不包含」，并在本 Spec 中重申，避免实现期范围蔓延：

- **复杂退款审批流程**（多级审批、人工工单）——`[Phase 延后]`。
- **已消费权益 / 已交付履约的统一回收政策**：本 Feature 只负责「请求撤销」并接受下游的可解释结果（`SKIPPED`/`REJECTED`），不定义回收策略（见 ADR-0017）。
- **真实出款**：资金动作仍经 Mock Channel 透传 payment-service，不动真实渠道。
- **Ledger 冲正的账本侧实现**：`ledger-service` 已落地，本 Feature 只做退款侧**接入**，不修改账本领域模型与科目表（归属 `004-ledger`，见 ADR-0018）。
- **按 RefundItem 明细跟踪已确认金额**：MVP 只跟踪整单已确认金额（`refundedAmountMinor`），明细级跟踪 `[待定]`。
- **多币种清分、Refund 关闭后的重开、跨支付合并退款**：均不在本 Feature。

## Requirements

### Functional Requirements

- ~~**FR-001**: 系统 MUST 区分「申请金额 `amountMinor`」与「已确认退款金额 `refundedAmountMinor`」~~ ⛔ **不适用（ADR-0016 不做）**。当前**只有 `amountMinor` 一个金额口径**，成功退款恒为全额。
- ~~**FR-002**: 渠道实际退回金额 `r` 的分态处理~~ ⛔ **不适用（ADR-0016 不做）**。渠道仅回三态：`SUCCEEDED` → `succeed()`；`FAILED` → `fail()`；其余 → `markUnknown()`。
- **FR-003**: 同一支付的可退款额度 MUST 按**申请金额 `amountMinor` 累计（终态与在途一视同仁）**，且累计 + 申请额 MUST NOT 超过已支付金额（超额落 `REJECTED`，不发起渠道尝试）。⭐ *（ADR-0016 回退后的最终口径）*
- **FR-004**: 退款确认（`SUCCEEDED`）后，系统 MUST 编排**三侧**后处理：向 fulfillment-service 请求撤销（**ADR-0017 已补齐**）、向 entitlement-service 请求吊销（已实现）、向 ledger-service 记账（**ADR-0018 已补齐**）。
- **FR-005**: 每次后处理调用 MUST 落一条可查询的尝试记录（目标服务、结果状态、失败原因、时间），失败 MUST NOT 回滚退款成功事实（Saga，禁 2PC/XA），且 MUST 产出 `refund.post_process_failed` 指标。
- **FR-006**: 后处理 RPC MUST 携带幂等依据（以退款为粒度，如 `refundNo` + 目标），下游 MUST 幂等吸收重复请求。
- **FR-007**: `UNKNOWN` 状态的退款 MUST NOT 被重复发起渠道退款尝试，且 MUST NOT 触发任何后处理或记账；收敛仅依据权威结果。
- **FR-008**: `resolve` 入口 MUST 显式校验当前状态为 `UNKNOWN`（防御性前置断言，G3）；对 `REQUESTED` MUST 返回明确的 `STATE_TRANSITION_VIOLATION`；对已终态 MUST 幂等吸收（返回当前状态，不重复推进）。
- **FR-009**: 已确认退款（`SUCCEEDED`）MUST 经 `LedgerPostingGateway` 以幂等键 `REFUND:<refundIdempotencyKey>` 向 `ledger-service` 提交**借贷平衡**的冲正分录，金额取 **`amountMinor`**（成功退款恒为全额，Constitution §II.3，ADR-0018）。
- **FR-010**: 记账失败或超时 MUST NOT 回滚退款成功事实，MUST 记录 `ledger.posting_failed` 并进入重试/对账兜底。
- **FR-011**: 金额一律最小货币单位 `long` 分或 `BigDecimal`（明确 scale），**MUST NOT** 使用 `float`/`double`；校验在退款受理、记账两处**分别**执行（渠道契约已不回传金额），不依赖上游（Constitution §II.1、technical-solution §4.5）。
- **FR-012**: 所有退款状态迁移 MUST 经 `Refund` 状态机唯一入口（`transitionTo`，`Refund.java:119`），MUST NOT 散落 `setStatus`；乐观锁 + 悲观锁并发保护保持不变。
- **FR-013**: 跨服务交互（payment / fulfillment / entitlement / ledger）MUST 沿用同步 RPC（OpenFeign）+ 幂等，**MUST NOT** 引入 MQ、跨服务异步事件或 2PC/XA（Constitution §IV、ADR-0001）。
- **FR-014**: Database-per-service：refund-service 只读写自有 `refund` Schema，MUST NOT 直接 SQL 他服务表（Constitution §IV.4）。
- **FR-015**: 系统 MUST 产出退款业务指标 `refund.initiated` / `refund.duplicate` / `refund.rejected` / `refund.succeeded` / `refund.failed` / `refund.unknown` / `refund.post_process_failed`（⛔ ~~`refund.partially_succeeded`~~ 随 ADR-0016 不做），并为每次资金状态迁移写入 `FINANCIAL_AUDIT`（含幂等键、金额、前后状态、traceId）。
- **FR-016**: 对账事实接口 `GET /internal/refunds/confirmed-facts` MUST 覆盖已确认退款（`SUCCEEDED`）并以 **`amountMinor`** 暴露（无「实际退款金额」概念）。
- **FR-017**: 资金路径（累计额度、幂等吸收、记账、后处理失败追踪）MUST 有单元测试与集成测试；**MUST NOT** 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。

### Key Entities

- **Refund（退款聚合根）**：一次退款申请与平台退款状态。⛔ **不新增 `refundedAmountMinor`**（ADR-0016 回退）；`PARTIALLY_SUCCEEDED` 枚举保留但**无调用方**。位置 `refund-service/.../domain/Refund.java`。
- **RefundItem（退款明细，已实现）**：`orderItemId + amountMinor`（最小货币单位）。后处理可据此定位具体明细，但处理结果由下游领域决定。
- **RefundPolicy（领域纯函数，已实现）**：可退款金额计算与资格判定（币种一致、金额为正、累计不超限）。累计口径**未改动**，一律按申请额（实现在 `RefundApplicationService`）。
- **RefundPostProcessAttempt（后处理尝试，新增）**：每次退款后处理 RPC 的独立记录（目标服务、退款 ID、结果状态、失败原因、尝试次数、时间），支撑「后处理失败可独立追踪」（FR-005）。
- **LedgerPostingGateway（出站端口，新增）**：refund → ledger-service 的 Feign 出站网关（与 payment-service 既有 `LedgerPostingGateway` 同模式），提供超时/幂等/失败兜底（FR-009）。
- **FulfillmentGateway（出站端口，新增）**：refund → fulfillment-service 的 Feign 出站网关（G2，FR-004）。

## Success Criteria

### Measurable Outcomes

- ~~**SC-001**: 部分退款 100% 落 `PARTIALLY_SUCCEEDED`~~ ⛔ **不适用（ADR-0016 不做）**。全额退款 100% 落 `SUCCEEDED`；「全额退款可追踪」成立。
- **SC-002**: 同一支付的累计退款 100% 不超过已支付金额（并发受理含悲观锁串行化），超额申请 100% 落 `REJECTED` 且不发起渠道尝试。
- **SC-003**: 重复幂等键退款 100% 被吸收（不产生第二次渠道尝试）；`UNKNOWN` 退款 100% 不被重复执行资金动作；`resolve` 对 `REQUESTED` 100% 显式拒绝、对终态 100% 幂等吸收。
- **SC-004**: 确认退款 100% 触发 fulfillment + entitlement 两侧后处理；后处理失败 100% 被独立记录（可查询、含原因）且 100% 不回滚退款成功。
- **SC-005**: 已确认退款 100% 在 `ledger-service` 留下借贷平衡冲正分录，金额 = **`amountMinor`**；重复记账 100% 幂等吸收；记账失败 100% 进入兜底且不回滚。
- **SC-006**: 全部退款分支（成功/失败/未知/拒绝）100% 产出业务指标与 `FINANCIAL_AUDIT`；`confirmed-facts` 覆盖已确认退款（`SUCCEEDED`）且金额口径正确。

## Assumptions

- `refund-service`（8085 / Schema `refund`）核心链路已实现，本 Feature 只补缺口与扩展，**不重写**既有领域模型、持久化与幂等机制。
- 不引入 MQ / 分布式事务 / 跨服务异步事件；后处理与记账均为同步 RPC + 幂等 + 有限重试/对账兜底（Constitution §IV、ADR-0001）。
- 当前单节点/单机部署；出站 Feign 超时建议 `[目标]` connect 1s / read 3s（当前沿用 OpenFeign 默认值，见 `refund-service.md` §5.4）；熔断/降级 `[Phase 按需延后]`。
- ⛔ ~~渠道「部分退回」由 `RefundAttemptResponse` 回传实际金额~~ —— ADR-0016 裁决后**不成立**：渠道只回三态，成功即全额。真实渠道对接不在本 Feature。
- `ledger-service` 与 payment 侧记账网关已实现并接入（端口 8090），退款侧接入复用该既有能力与科目表；科目编码与账户 ID 以账本侧预置为准（ADR-0018）。
- 复杂退款审批流程、已消费权益的统一回收政策、真实出款均**不在本 Feature**（Roadmap Phase 5「不包含」）。
- 具体取舍见 ADR-0016~0018，**已于 2026-08-30 由负责人裁决**：ADR-0016 Rejected / ADR-0017、ADR-0018 Accepted（Constitution §8.3、§8.4、§8.8）。

## Dependencies（依赖与前置）

| 依赖 | 状态 | 说明 |
|---|---|---|
| Phase 2-4（Payment / Reliability / Fulfillment & Entitlement） | 已完成 | 支付主链、UNKNOWN 收敛、履约与权益授予均已落地，退款的编排对象存在且稳定 |
| `payment-service` 退款尝试端点 | 已实现 | `POST /internal/payments/refund-attempt`；本 Feature 需其回传实际退款金额（T011/T012） |
| `entitlement-service` 退款端点 | 已实现 | `POST /internal/entitlements/on-refund` |
| `ledger-service` 记账端点 | 已实现（退款侧未接入） | `POST /internal/ledger/postings`（端口 8090）；承接 `004-ledger` US2 |
| `fulfillment-service` 退款端点 | **缺失** | 本 Feature 新增（T021） |
| Mock Channel 支持「部分退回」 | **缺失** | 本 Feature 需支持配置化部分退回以验证 US1（T012） |

## Clarifications

### Session 2026-08-29

- **编号约定**：spec 目录采用顺序编号 `005-refund`，与 Roadmap 阶段标签「003 Refund / Phase 5」**解耦**（Roadmap 标签为阶段描述，非 spec ID；同 `003-payment-reliability`「Roadmap 002」、`004-ledger`「Roadmap 006」的既定约定）。
- **Spec 性质**：本 Spec 为**缺口补齐型**（gap-closing / completion），非绿地构建。三项缺口 G1/G2/G3 见文首表格，均已核实到 `file:line`。
- **分歧点 → ADR**（`docs/adr/0006-refund-decisions.md`，状态 **Proposed**，待负责人决策）：
  - ~~部分退款支持模型~~ → **ADR-0016** ❌ **Rejected（不做）**。
  - refund → fulfillment 编排（补齐缺失 RPC vs 修改文档声明）→ **ADR-0017**。
  - refund → ledger 记账接入（与 spec `004-ledger` US2 的归属与时机）→ **ADR-0018**。
- **新发现的矛盾（未写入 ADR，供负责人知悉）**：
  1. **ADR 编号冲突（已解决）**：本包 ADR 原为 `0005-refund-decisions.md` + ADR-0012~0014，与既有 `0005-payment-reliability-impl-decisions.md`（ADR-0012~0015）冲突。**已于 2026-08-29 解决**：文件重命名为 `0006-refund-decisions.md`，标签重编号为 ADR-0016~0018，既有文件与其编号保持不变。
  2. **004-ledger 文档状态过期**：`ledger-service`（8090）与 payment 侧 `LedgerPostingGateway`/`FeignLedgerPostingGateway` **已实现**，但 `roadmap.md:13/16` 与 `004-ledger/acceptance.md` 仍描述为「待 ADR 确认 / 未完成」；且 spec `004-ledger` US2（退款记账 T013/T014）在退款侧未落地——本 Feature 的 US4/FR-009 承接该缺口（待 ADR-0018 裁定归属）。
  3. **契约文档漂移**：`004-ledger/contracts/post-refund.md` 使用 `accountCode`，而实际 `common-dto` 的 `PostingRequest.EntryRequest` 字段为 `accountId`（`common/common-dto/.../PostingRequest.java`）。
  4. **成熟度标注过期**：`technical-solution.md:101` 仍将 refund-service 标为「骨架」（`refund-service.md` §1.1 已指出）。
