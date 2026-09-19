# Knowledge Promotion Map

> Phase 3A only. This file records promotion evidence and targets. It does not modify current architecture, code, ADRs, Specs, lifecycle status, or runtime behavior.

## 1. Purpose

Knowledge Promotion means extracting only current, verified system facts from implemented ADRs and Feature Specs into the L0 architecture layer.

It does not mean copying ADRs or Specs into architecture documents. The following remain historical material:

- decision rationale and rejected alternatives;
- historical background and migration steps;
- implementation task history and acceptance evidence;
- superseded or obsolete designs;
- unresolved proposals and future architecture.

The intended L0 sources of truth are:

- `docs/architecture/technical-solution.md` for global and cross-service facts;
- `docs/architecture/systems/*.md` for service-level facts.

## 2. Promotion Rules

1. Implementation status is established from current code and tests first, not from an ADR or Spec status label.
2. A candidate is `Already Promoted` only when the current fact is both implemented and adequately described in the relevant L0 document.
3. A candidate is `Partially Promoted` when implementation is evidenced but L0 has a meaningful omission, stale description, or mixed current/history content.
4. A candidate is `Not Promoted` when implementation is evidenced but L0 does not materially describe the current capability.
5. A candidate is `Unclear` when code, current architecture, and historical documents disagree and the disagreement cannot be resolved without a human decision.
6. `ADR-0074` and `029-redis-transactional-mq` are active work and are excluded from promotion. Their Proposed design must not be treated as current architecture.
7. A `Both` target means different granularity: a concise cross-service fact in `technical-solution.md`, and the complete service fact in the owning System Design. It does not mean copying the same text.
8. Historicalization is not performed in Phase 3A. `Ready` means the evidence is sufficient to consider it after promotion; it is not an instruction to change status now.

## 3. Summary Mapping Table

| Candidate | Type | Implementation | Current Architecture | Promotion Status | Target | Historicalization | Human Review |
|---|---|---|---|---|---|---|---|
| ADR-0072 | ADR | Implemented | Detailed in payment System Design and global model | Already Promoted | System Design + Technical Solution | Ready | No |
| ADR-0073 | ADR | Implemented | Detailed in payment System Design; global relationship is present but routing detail is service-owned | Partially Promoted | Both | Ready after cleanup | No |
| ADR-0071 | ADR | Implemented in payment code | No complete limit model in current L0 baseline | Not Promoted | System Design + Technical Solution | Human Review Required | Yes |
| ADR-0065 | ADR | Implemented | Reconciliation/settlement L0 docs do not describe the audit package and gate completely | Partially Promoted | Both | Not Ready | Yes |
| ADR-0066 | ADR | Implemented | Order L0 is partly current; fulfillment System Design still describes the pre-018 null/item model | Partially Promoted | Both | Human Review Required | Yes |
| ADR-0067 | ADR | Implemented in order/payment code | Payment and order docs contain much of the current flow, but historical/status inconsistencies remain | Partially Promoted | Both | Human Review Required | Yes |
| ADR-0068 | ADR | Implemented in common/deployment code | Global observability is described, but access-log contract is not fully owned by L0 | Partially Promoted | Both | Not Ready | Yes |
| ADR-0070 | ADR | Implemented in deployment | Technical Solution has deployment facts, but the full dual-mode operational contract is not consolidated | Partially Promoted | Both | Ready after cleanup | No |
| 001-core-business-model | Spec | Core flows implemented | Core facts are spread across global and service docs | Partially Promoted | Both | Human Review Required | Yes |
| 016-order-payment-orchestration | Spec | Implemented | Order/payment docs largely describe the current flow; Spec header is stale | Already Promoted with status drift | Both | Human Review Required | Yes |
| 017-accounting-audit | Spec | Implemented | Audit code exists, but L0 does not fully describe four-way audit and settlement gate | Partially Promoted | Both | Not Ready | Yes |
| 018-schema-normalization-item-fulfillment | Spec | Implemented | Order code is current; fulfillment System Design is stale | Partially Promoted | Both | Human Review Required | Yes |
| 019-order-driven-refund | Spec | Implemented in current code | Spec says “code not implemented”; L0 describes much of the flow but conflicts remain | Unclear | Both | Human Review Required | Yes |
| 021-unified-access-logging | Spec | Implemented | Common implementation exists; L0 only describes generic observability | Partially Promoted | Both | Not Ready | Yes |
| 022-full-chain-automated-testing | Spec | Implemented | E2E module exists; current architecture does not own the testing topology sufficiently | Not Promoted | Technical Solution + deployment/e2e documentation | Ready after promotion | No |
| 026-containerized-local-stack | Spec | Implemented | Global deployment section exists, but dual-mode and operational facts are incomplete | Partially Promoted | Both | Ready after cleanup | No |
| 027-user-payment-limit | Spec | Implemented | No complete current limit section found in payment System Design baseline | Not Promoted | System Design + Technical Solution | Human Review Required | Yes |
| 028-channel-routing | Spec | Implemented | Payment System Design contains routing; it still contains stale “pending”/hardcoded-channel notes | Partially Promoted | System Design + Technical Solution | Human Review Required | Yes |

## 4. ADR Promotion Mapping

### ADR-0072 — payment / channelAttempt two-layer structure

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0033-two-layer-channel-architecture.md`, decision sections 1-6.
- Code: `payment-service/src/main/java/com/payment/payment/application/channel/PaymentChannel.java`; `payment-service/src/main/java/com/payment/payment/infra/channel/AbstractMockChannelAdapter.java`; `AlipayChannelAdapter.java`; `WechatChannelAdapter.java`; `DouyinChannelAdapter.java`; `MockChannelAdapter.java`; `SpringChannelRegistry.java`; `PaymentAttempt` persistence under `payment-service/src/main/java/com/payment/payment/infra/persistence/attempt/`.
- Tests/guard: `deployment/architecture-tests/src/test/java/com/payment/arch/ServiceBoundaryTest.java` contains the payment-attempt write-boundary checks; payment channel routing tests exist under `payment-service/src/test/java/com/payment/payment/infra/channel/`.
- System Design: `docs/architecture/systems/payment-service.md` §2.1.1 describes the two aggregates, table ownership, reverse lookup, and shared local transaction.
- Technical Solution: `docs/architecture/technical-solution.md` §3.1.1 describes the payment/channelAttempt split and table ownership.

**Current Facts To Promote:** None required for the core ADR decision. The current L0 already states:

- `payments` belongs to the payment layer;
- `payment_attempts` belongs to the channelAttempt layer;
- payment calls a channel port and does not depend on concrete adapters;
- reverse operations use the recorded channel and do not reroute;
- the two layers share one local transaction.

**Promotion Target:** `Already Promoted`. Keep the complete service detail in `payment-service.md`; keep only the cross-service/domain relationship in `technical-solution.md`.

**Historical Content To Keep In ADR:** The original code gap, the hardcoded `mock` defect that motivated the change, rejected split-service and split-transaction alternatives, and implementation cost discussion.

**Drift / caveat:** `payment-service.md` still contains a later paragraph describing refund channel hardcoding as a pending spec-028 defect. Current code evidence shows channel identity and routing types exist, but the refund-channel implementation must be checked before that paragraph is removed in Phase 3B. This is a cleanup item, not a reason to copy ADR rationale into L0.

### ADR-0073 — deterministic channel routing

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0034-channel-routing.md`, decisions D1-D8.
- Code: `payment-service/src/main/java/com/payment/payment/application/channel/ChannelRouter.java`; `ChannelRegistry.java`; `payment-service/src/main/java/com/payment/payment/infra/channel/ConfiguredChannelRouter.java`; `SpringChannelRegistry.java`; `RoutingProperties.java`; `PaymentController.java`; `PaymentApplicationService.java`.
- Tests: `payment-service/src/test/java/com/payment/payment/infra/channel/ConfiguredChannelRouterTest.java`; architecture tests include the application-to-infra channel dependency boundary.
- System Design: `docs/architecture/systems/payment-service.md` §3.2 create-payment contract, §3.10 routing endpoints, and §4.1 create-payment flow; §2.1.1 records routing and reverse-routing constraints.
- Technical Solution: `docs/architecture/technical-solution.md` §3.1.1 includes the channel layer globally, while routing-specific behavior remains service-owned.

**Current Facts To Promote:**

- explicit `channelCode` wins when supplied;
- absent channel code is resolved by a registered, enabled, priority-ordered router;
- routing is deterministic and does not perform automatic failover;
- reverse operations resolve the channel from the persisted attempt and never reroute;
- routing exposes read-only inspection/preview endpoints and demo-only status override.

**Already present:** The payment System Design contains these facts in §§2.1.1, 3.2, 3.10, and 4.1. The global document only needs a short relationship-level statement if Phase 3B confirms a cross-service summary is useful.

**Promotion Target:** `Partially Promoted` → `Both`: retain complete routing behavior in `payment-service.md`; add only a concise global statement in `technical-solution.md` if needed.

**Historical Content To Keep In ADR:** Why automatic failover/intelligent routing/multi-merchant routing were rejected, the supersession of spec 015, and the migration cost/compatibility discussion.

**Historicalization:** Ready after the stale notes in the payment System Design are reconciled with code.

### ADR-0071 — user payment limits

**Implementation status:** Implemented in current code.

**Evidence chain:**

- ADR: `docs/adr/0032-user-payment-limit.md`, decisions D1-D13.
- Code: payment limit domain/application/infra under `payment-service/src/main/java/com/payment/payment/limit/`; `LimitGate.java`; `LimitReserveService.java`; `LimitSettlementService.java`; `LimitPendingRecycler.java`; `LimitCompensationScanner.java`; `LimitController.java`; `PaymentPersistence.java`.
- Schema: `deployment/schema/027-user-payment-limit.sql`.
- Tests: limit tests under `payment-service/src/test/java/com/payment/payment/limit/`, including concurrency, reserve, settlement, and persistence tests.
- System Design: no complete limit-domain section was identified in the current `payment-service.md` baseline; the document describes payment amount/user fields but not the three-table model and lifecycle.
- Technical Solution: no complete global user-limit capability description was identified.

**Current Facts To Promote:**

- limits belong to payment-service because payment owns the confirmed money fact;
- three tables: configuration, usage, and idempotent operations;
- daily/monthly/yearly periods use reserve then confirm/release;
- the database is authoritative for used/pending amounts;
- `LIMIT_EXCEEDED` rejects creation before a payment row is created;
- terminal-state absorption, unique operation keys, and compensation protect idempotency;
- Redis is only an expiry index for pending reservations, with conservative fail-open behavior; it is not the amount counter;
- successful refunds do not restore payment-limit usage in the current business rule.

**Promotion Target:** `Not Promoted` → `Both`: concise global money-entry constraint in `technical-solution.md`; full data model, API, state/operation rules, and Redis exception in `payment-service.md`.

**Historical Content To Keep In ADR:** The comparison with rejected risk scoring, the D13 reversal of the earlier “payment must not use Redis” decision, rejected alternatives, and implementation-time correction of the period-inclusive unique key.

**Human Review:** Required before promotion because the Phase 1 inventory and Spec 027 status are inconsistent with the current implementation and the ADR header says Accepted while the Spec header remains Draft.

### ADR-0065 — accounting audit and suspense adjustment loop

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0026-accounting-audit-suspense-adjustment.md`.
- Code: `reconciliation-service/src/main/java/com/payment/reconciliation/audit/application/AuditApplicationService.java`; `CertificateAuditor.java`; `LedgerAuditor.java`; `RealAuditor.java`; `AdjustmentPolicy.java`; `AuditScheduler.java`; `AuditController.java`.
- Ledger evidence: `ledger-service/src/main/java/com/payment/ledger/domain/Account.java` contains `SUSPENSE`; schema seed is in `deployment/schema/09-ledger-schema.sql`.
- Settlement gate evidence: `settlement-service/src/main/java/com/payment/settlement/application/AuditGateClient.java`; `SettlementApplicationService.java`; Feign gate client under `settlement-service/src/main/java/com/payment/settlement/infra/client/`.
- Tests/demo: audit tests under `reconciliation-service/src/test/java/com/payment/reconciliation/audit/`; `deployment/demo/scenario-audit.sh`; audit schema `deployment/schema/10-audit-schema.sql`.
- System Design: `reconciliation-service.md` still primarily describes the earlier reconciliation batch and resolve model; `settlement-service.md` describes confirmed-fact gating but not the complete four-audit/suspense lifecycle.
- Technical Solution: contains the global reconciliation/settlement relationship but not the complete audit package contract.

**Current Facts To Promote:**

- reconciliation owns four audit checks and audit batch lifecycle;
- differences can be suspended, adjusted, rechecked, and closed;
- `SUSPENSE` is the transition account for unresolved monetary differences;
- settlement creation is gated by audit status and ledger balance;
- audit writes its own audit tables and append-only ledger adjustments without mutating payment/refund/settlement facts;
- adjustment operations are idempotent and balanced.

**Promotion Target:** `Partially Promoted` → `Both`: global audit/settlement boundary in Technical Solution; complete audit model and APIs in reconciliation System Design; gate behavior in settlement System Design.

**Historical Content To Keep In ADR:** The seven original gaps, rejected “one-shot block” behavior, double-review downgrade, SUSPENSE account decision, and demo/test delivery history.

**Human Review:** Required because the current System Designs still describe an earlier implementation surface and must be reconciled with the newer audit package rather than blindly appended.

### ADR-0066 — schema normalization and item-level fulfillment

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0027-schema-normalization-and-item-granular-fulfillment.md`.
- Code/schema: `order-service/src/main/java/com/payment/order/domain/OrderItem.java`; `OrderApplicationService.java`; `common/common-dto/src/main/java/com/payment/common/dto/rpc/PaymentSucceededRequest.java`; `fulfillment-service/src/main/java/com/payment/fulfillment/application/FulfillmentApplicationService.java`; `deployment/schema/018-schema-normalization.sql`; `deployment/schema/01-order-schema.sql`.
- Tests: fulfillment application/persistence tests and common RPC contract tests under their respective `src/test` trees.
- System Design: `order-service.md` describes order items and payment-success enrichment; `fulfillment-service.md` still says `order_item_id` is null and the relation is pending.
- Technical Solution: §3.1.1 names item-level fulfillment as a model concept, but the global flow does not fully state the implemented `(source_payment_no, order_item_no)` idempotency contract.

**Current Facts To Promote:**

- `order_item_no` is the cross-service business identifier for an order item;
- `PaymentSucceededRequest` can carry item lines and order enriches them from its own `order_items` source of truth;
- fulfillment creates one fulfillment per payment/order item;
- fulfillment idempotency is keyed by `(source_payment_no, order_item_id)`;
- payment attempts retain amount and currency evidence;
- the relevant migration scripts and test schemas must remain aligned.

**Promotion Target:** `Partially Promoted` → `Both`: global business-reference/item-granularity statement in Technical Solution; complete schema and fulfillment behavior in order and fulfillment System Designs.

**Historical Content To Keep In ADR:** Column-order conventions, exceptions, rejected numeric-ID string approach, migration mechanics, and demo portal changes.

**Drift:** `fulfillment-service.md` contradicts current code and schema by describing nullable/null item references and one fulfillment per payment. This is `DOCUMENTATION_DRIFT`, not a basis for choosing the old or new design silently.

**Human Review:** Required before historicalization because the stale System Design must be corrected first.

### ADR-0067 — order-driven two-level refund model

**Implementation status:** Current code evidence indicates implemented.

**Evidence chain:**

- ADR: `docs/adr/0028-order-driven-refund-two-layer-refund-order.md`.
- Code: `order-service/src/main/java/com/payment/order/application/TransactionApplicationService.java`; `RefundOrder.java`; `TransactionRefundRepository.java`; `OrderRefundRpcController.java`; `payment-service/src/main/java/com/payment/refund/application/RefundApplicationService.java`; `RefundResultProcessor.java`; `RefundRpcCallbackService.java`; `PaymentRefundService.java`.
- DTO/schema: `common/common-dto/src/main/java/com/payment/common/dto/rpc/RefundCommandRequest.java`; `RefundResultNotification.java`; `deployment/schema/018-schema-normalization.sql` and refund/order schemas.
- Tests: `order-service/src/test/java/com/payment/order/scenario/TransactionRefundTest.java`; `payment-service/src/test/java/com/payment/refund/integration/RefundScenarioTest.java`; `PaymentAutoRefundServiceTest.java`.
- System Design: `order-service.md` §4.2 describes transaction surplus judgment and order closure; `payment-service.md` §3.6 and §4.3 describe PMRF/TXRF, async callback convergence, ledger reversal, and order notification.
- Technical Solution: §3.1.1 and §4.3.3 contain global refund ownership and current refund-state facts.

**Current Facts To Promote:**

- order/transaction creates and owns the TXRF refund decision;
- payment creates the PMRF execution record and owns channel facts/ledger reversal;
- TXRF and PMRF are mutually recorded and idempotent;
- refund success/failure/resolve converge through the same payment post-processing path;
- payment notifies order with both business numbers; order updates transaction/order/refund state and downstream stock/fulfillment behavior;
- refund does not change the original successful payment fact.

**Promotion Target:** `Both`, but most facts are already present. Phase 3B should normalize the two System Designs and remove stale process commentary rather than copy the ADR.

**Historical Content To Keep In ADR:** Industry comparisons, abandoned single-number designs, the original seven gaps, and the decision sequence.

**Drift:** Spec 019 says “代码未实施”, while current code and System Designs contain TXRF/PMRF and three-path convergence. This is a direct Spec/code/document conflict. Mark `DOCUMENTATION_DRIFT` and require human confirmation before historicalization.

### ADR-0068 — unified access logging

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0029-unified-access-logging.md`.
- Code: `common/common-core/src/main/java/com/payment/common/core/accesslog/AccessLogFilter.java`; `AccessLogProperties.java`; `CommonCoreAutoConfiguration.java`; `MdcTaskDecorator.java`; `common/common-core/src/main/resources/logback-spring.xml`.
- Operations: `deployment/demo/tail-logs.sh`; `deployment/demo/trace-grep.sh`; Promtail configuration under `deployment/promtail/`.
- Tests: `common/common-core/src/test/java/com/payment/common/core/accesslog/AccessLogFilterTest.java`; `MdcPropagationTest.java`.
- System Design: no service-specific access-log contract is currently owned by the service docs; Technical Solution §5.3 only states generic structured logging/trace requirements.

**Current Facts To Promote:**

- common-core installs one request-end `ACCESS_LOG` record with method, URI, status, duration, request and response payload fields;
- payloads are bounded and the filter has a masking hook, while real masking remains disabled by current security policy;
- the log pattern includes service name and trace ID;
- `MdcTaskDecorator` preserves and clears MDC across worker execution;
- demo scripts provide merged log tailing and trace lookup.

**Promotion Target:** `Partially Promoted` → `Both`: common cross-service observability contract in Technical Solution; implementation/configuration contract in the relevant service/engineering documentation rather than duplicating it in every service design.

**Historical Content To Keep In ADR:** Industry comparison, rejected AOP/request-logging alternatives, the deferred Loki decision, and original rollout gaps.

**Human Review:** Required because the masking hook and current “masking not implemented” security exception must remain aligned.

### ADR-0070 — local containerized stack

**Implementation status:** Implemented.

**Evidence chain:**

- ADR: `docs/adr/0031-containerized-local-stack.md`.
- Deployment code: `deployment/docker/Dockerfile`; `deployment/docker-compose.yml`; `deployment/start-container.sh`; `deployment/start-all.sh`; `deployment/stop-all.sh`; `deployment/lib-mode-guard.sh`; `deployment/build-images.sh`.
- Runtime evidence: `deployment/demo/run-all.sh`, release/start scripts, Prometheus/Promtail configuration, and ADR/spec verification matrices.
- System Design: deployment topology is not owned by an individual service; deployment facts are partially represented in Technical Solution §6.
- Technical Solution: §6 describes local deployment, while roadmap/spec 026 contains the full dual-mode contract.

**Current Facts To Promote:**

- local stack supports host and container modes;
- both modes share ports and artifacts and are mutually guarded;
- Docker Compose runs application services and infrastructure;
- images copy host-built jars rather than building Maven reactors in containers;
- environment variables adapt service discovery for container mode;
- health checks, memory limits, logs, and Prometheus/Promtail behavior are part of the supported local runtime.

**Promotion Target:** `Partially Promoted` → `Both`: concise runtime topology and mode contract in Technical Solution; operational details in deployment documentation, with only service-specific deployment constraints in System Designs.

**Historical Content To Keep In ADR:** Host JDK failure incident, rejected Kubernetes/multi-stage-build options, resource tuning history, and validation timeline.

## 5. Feature Spec Promotion Mapping

### 001-core-business-model

**Current Facts:** The core purchase lifecycle, Order/Transaction/Payment separation, unknown-state rule, idempotency, and fulfillment-to-entitlement relationship are represented across `technical-solution.md` §§3-4, `order-service.md`, `payment-service.md`, `fulfillment-service.md`, and `entitlement-service.md`. Code evidence includes the domain state machines and the order/payment/fulfillment RPC paths.

**Missing Facts:** The global document still carries broad historical phase language and Feature references; the exact current one-to-many payment model and current refund scope have evolved beyond the original Spec. The Spec itself remains `Draft` despite implementation.

**Historical Content:** Original requirements, user scenarios, acceptance reasoning, and superseded MVP assumptions.

**Promotion Target:** Both, through normalization of existing L0 rather than new bulk content.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` because the original Spec contains assumptions later superseded by multi-payment/refund decisions and its status is stale.

### 016-order-payment-orchestration

**Current Facts:** order transaction layer owns surplus judgment and refund initiation; payment owns payment execution, channel interaction, ledger posting, and notification; order owns the post-payment business fan-out. Evidence: `order-service/src/main/java/com/payment/order/application/TransactionApplicationService.java`, `OrderApplicationService.java`, `payment-service/.../PaymentResultProcessor.java`, and the corresponding order/payment System Design sections.

**Missing Facts:** The current L0 is mostly complete, but payment/fulfillment documents retain historical “Proposed 未实施” migration annotations. The Spec header also remains Draft and points to a Proposed ADR even though current code is implemented.

**Historical Content:** Original gap table, migration plan, and rejected ownership alternatives.

**Promotion Target:** Both, with cleanup of stale annotations only in Phase 3B.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` due to status and historical-note drift.

### 017-accounting-audit

**Current Facts:** reconciliation owns audit batches, four audit dimensions, differences, SUSPENSE adjustments, recheck/close, and audit endpoints; settlement consumes an audit gate; ledger owns the SUSPENSE account. Evidence is listed under ADR-0065.

**Missing Facts:** Reconciliation System Design needs the `audit` subdomain, three audit tables, status transitions, adjustment/transfer rules, and endpoint contract. Settlement System Design needs the blocking/allowing audit gate and SUSPENSE interpretation. Technical Solution needs a concise cross-service audit/settlement boundary.

**Historical Content:** Original missing-capability audit, demo fixture design, acceptance matrix, and implementation chronology.

**Promotion Target:** Both.

**Historicalization Readiness:** `NOT_READY` until the current audit facts are promoted and the old “resolve only writes a note” narrative is removed or clearly historicalized.

### 018-schema-normalization-item-fulfillment

**Current Facts:** order items have OI business numbers; payment-success DTO carries item lines; fulfillment creates item-granular records and uses `(sourcePaymentNo, orderItemNo)` idempotency. Code and schema evidence is listed under ADR-0066.

**Missing Facts:** `fulfillment-service.md` still describes nullable `order_item_id`, one fulfillment per payment, and payment as the caller. The global flow does not fully state item-level idempotency.

**Historical Content:** Column ordering rules, migration scripts, table exceptions, rejected alternatives, and demo portal work.

**Promotion Target:** Both.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` because Code ≠ Fulfillment System Design.

### 019-order-driven-refund

**Current Facts:** Current code contains TXRF/PMRF, transaction refund persistence, order-driven initiation, payment-side channel/ledger processing, and unified result convergence. Current payment and order System Designs describe most of it.

**Missing Facts:** The Spec itself claims code is not implemented, and some architecture text retains earlier migration markers. The exact current caller and endpoint ownership must be confirmed once by a human before historicalization.

**Historical Content:** Industry research, rejected single-layer designs, original gap analysis, and task-by-task implementation plan.

**Promotion Target:** Both.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` due to direct Spec/code status conflict.

### 021-unified-access-logging

**Current Facts:** common-core access filter, fixed ACCESS_LOG format, payload truncation/masking hook, service name pattern, MDC propagation, and log aggregation scripts are implemented.

**Missing Facts:** Technical Solution only states generic observability; there is no canonical current access-log contract and no clear ownership for the common-core behavior.

**Historical Content:** Research, rejected logging approaches, deferred Loki centralization, and original migration gaps.

**Promotion Target:** Both, with a concise global contract and a focused engineering/common-core reference.

**Historicalization Readiness:** `NOT_READY` until the contract and masking exception are recorded in an authoritative current document.

### 022-full-chain-automated-testing

**Current Facts:** `deployment/e2e-tests` is a Maven module with API/DB assertions, layered scenarios, reports, schema snapshots, and CI integration. The Spec and code provide strong evidence.

**Missing Facts:** Technical Solution does not clearly define the E2E module as the current validation layer, its environment assumptions, or the known local proxy artifact. This knowledge belongs partly in Technical Solution and partly in deployment/e2e README, not in service System Designs.

**Historical Content:** Original demo-script gap, test-tool selection, acceptance matrix, and CI rollout history.

**Promotion Target:** Technical Solution + deployment/e2e documentation.

**Historicalization Readiness:** `READY` after the current test topology is recorded; no architecture conflict was found.

### 026-containerized-local-stack

**Current Facts:** Dockerfile, Compose full stack, host/container dual mode, mode guard, jar-copy image strategy, health checks, and observability integration are implemented.

**Missing Facts:** Technical Solution §6 is not yet a single complete current runtime contract; dual-mode guard behavior, jar prerequisite, environment overrides, and resource limits remain distributed across scripts/spec.

**Historical Content:** Host JDK incident, rejected Kubernetes and in-container Maven build, resource tuning, and validation history.

**Promotion Target:** Both, with operational detail remaining in `deployment/README.md` and scripts.

**Historicalization Readiness:** `READY` after promotion because implementation and runtime evidence are complete.

### 027-user-payment-limit

**Current Facts:** payment limit subdomain, three tables, reserve/settle lifecycle, limit error, compensation, and Redis expiry index are implemented. Code/schema evidence is listed under ADR-0071.

**Missing Facts:** Current payment System Design and global Technical Solution do not fully describe ownership, tables, API, state/operation idempotency, or the Redis exception.

**Historical Content:** Distinction from rejected risk scoring, D1-D13 rationale, rejected alternatives, and implementation corrections.

**Promotion Target:** Both.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` because the Spec is still Draft and the Phase 1 inventory recorded a transitional state despite code evidence.

### 028-channel-routing

**Current Facts:** payment/channelAttempt separation, channel identity, registry, deterministic configured routing, explicit-channel precedence, no automatic failover, reverse channel lookup, and routing inspection endpoints are implemented.

**Missing Facts:** The main payment System Design contains most facts but also retains stale “spec 028 待修” and “current implementation hardcodes mock” language. Technical Solution has the global channel model but not a concise routing summary.

**Historical Content:** Original absent-component analysis, rejected failover/intelligent routing, compatibility costs, and implementation task history.

**Promotion Target:** Both, with detailed behavior in payment System Design and a short global statement in Technical Solution.

**Historicalization Readiness:** `HUMAN_REVIEW_REQUIRED` until the stale hardcoded-channel statement is checked against current `PaymentRefundService` and corrected in Phase 3B.

## 6. Current Architecture Cleanup Candidates

| File | Section | Problem | Recommended Action |
|---|---|---|---|
| `docs/architecture/technical-solution.md` | §1.2, §2.3, §3.4, §4.3, §4.4 | ADR-0074/spec 029 Proposed design is written into current architecture sections as an upcoming implementation | Phase 3B: separate active roadmap references from current facts; do not promote the Proposed design |
| `docs/architecture/technical-solution.md` | §4.3.3 / payment flow | Historical “current vs target” migration commentary remains alongside current flow | Phase 3B: retain rationale in ADR/Spec and leave only verified current flow |
| `docs/architecture/technical-solution.md` | §3.3 service table | Baseline date/status notes and historical implementation caveats are mixed with current service facts | Phase 3B: normalize the current table and move chronology to roadmap/history |
| `docs/architecture/systems/payment-service.md` | §4.3 refund facts | Current text says refund channel hardcoding is still a spec-028 pending defect; code evidence shows the channel-routing implementation exists and requires targeted confirmation | Phase 3B: reconcile code and text, then update only verified current behavior |
| `docs/architecture/systems/payment-service.md` | §3.10 events | Proposed ADR-0074 event topology is embedded in a current service contract | Phase 3B: mark as active proposal or move to active Spec context |
| `docs/architecture/systems/order-service.md` | §3.5 | Proposed ADR-0074 event topology is presented beside current RPC facts | Phase 3B: separate current synchronous behavior from active proposal |
| `docs/architecture/systems/fulfillment-service.md` | §2.1, §2.3, §3.1, §4.1 | Stale pre-018 model: null order item, one fulfillment per payment, payment caller, while code uses item lines and order-driven invocation | Phase 3B: human-reviewed correction from code evidence |
| `docs/architecture/systems/fulfillment-service.md` | §3.5 | Proposed event consumer/producer topology is mixed into current API contract | Phase 3B: separate active proposal |
| `docs/architecture/systems/catalog-service.md` | §3.9 | Proposed ADR-0074 event consumer is inside current service design | Phase 3B: separate active proposal |
| `docs/architecture/systems/entitlement-service.md` | §3.5-3.6 | Proposed Redis/event consumer and future Redis dependency are described as if part of the current service | Phase 3B: separate active proposal |
| `docs/architecture/systems/reconciliation-service.md` | §2-4 | Older reconciliation model does not expose implemented audit package and SUSPENSE loop | Phase 3B: promote ADR-0065 current facts and remove stale “resolve only” narrative |
| `docs/architecture/systems/settlement-service.md` | header/§1 | ADR-0022/0023 are still labeled Proposed although code is implemented; audit gate details are incomplete | Phase 3B: human-reviewed status/fact reconciliation |
| `docs/architecture/systems/fulfillment-service.md` | §5 | `[待定]` retry/transaction notes describe known implementation gaps in an L0 current design | Phase 3B: split current behavior from backlog, without silently changing code |

## 7. Documentation Drift

The following conflicts are recorded without choosing a winner silently:

| Drift ID | ADR / Spec evidence | Code evidence | Current architecture evidence | Required human decision |
|---|---|---|---|---|
| DR-001 | ADR-0072/0073 describe implemented two-layer/routing design | Channel ports, registry, router, and per-channel adapters exist | Payment System Design mostly describes them but retains a stale hardcoded-mock note | Confirm current refund-attempt channel persistence behavior, then clean the note |
| DR-002 | Spec 027 remains Draft; Phase 1 classified it transitional | Payment limit domain, schema, controllers, reserve/settlement/recycler code exist | No complete payment-limit L0 section | Confirm implementation is the intended current product capability before promotion |
| DR-003 | Spec 019 explicitly says code not implemented | TXRF/PMRF, refund orchestration, callback convergence, and tests exist | Payment/order docs describe the implemented flow | Confirm Spec status/documentation is stale, then promote current facts |
| DR-004 | ADR-0066/Spec 018 say item-level fulfillment implemented | `order_item_no`, item DTOs, item-granular fulfillment and repositories exist | Fulfillment System Design still says order_item_id is null and payment is caller | Confirm current code as authoritative and update System Design in Phase 3B |
| DR-005 | ADR-0065/Spec 017 say audit loop implemented | audit package, SUSPENSE, audit schema, settlement gate, tests/demo exist | Reconciliation/settlement System Designs omit or understate the audit loop | Confirm the current audit contract and promote it |
| DR-006 | ADR-0070/Spec 026 say container stack implemented | Dockerfile, full Compose, mode guards, scripts exist | Technical Solution is partial and deployment facts are distributed | Confirm the supported local runtime contract |
| DR-007 | ADR-0074/spec 029 are Proposed and active | No implementation evidence was used in this mapping | Technical Solution and several System Designs describe the future event topology | Remove proposal language from current-state sections in Phase 3B; do not promote ADR-0074 |
| DR-008 | ADR-0068/Spec 021 say access logging implemented | AccessLogFilter, MDC decorator, logback pattern, scripts, tests exist | L0 only has generic observability language | Confirm the canonical location for the common access-log contract |

## 8. Human Review Required

Human confirmation is required for:

1. Whether ADR-0071 / Spec 027 is an intended current business capability and can be promoted despite stale Spec status.
2. Whether Spec 019 should be marked implemented in a later lifecycle phase, given the strong current code evidence.
3. Whether fulfillment item-granular behavior from ADR-0066 is the current accepted contract, given the stale fulfillment System Design.
4. Whether the ADR-0065 audit package and settlement gate are the final current accounting contract.
5. Whether the current refund channel persistence behavior fully satisfies ADR-0072/0073 before removing the stale hardcoded-channel note.
6. Whether the common access-log contract belongs in Technical Solution plus engineering guidance, rather than being duplicated into each System Design.
7. Whether the old status labels in completed Specs and ADRs are documentation drift to be handled in Phase 5.

## 9. Phase 3B Migration Plan

This is a concrete plan only. No Phase 3B changes are executed here.

| Order | Source | Target | Change | Risk | Human Review |
|---:|---|---|---|---|---|
| 1 | ADR-0072 / ADR-0073 | `docs/architecture/systems/payment-service.md` | Verify and normalize the current two-layer, registry, deterministic routing, explicit-channel, and reverse-routing facts; remove stale “pending” notes only after code check | Medium | No, except refund-channel ambiguity |
| 2 | ADR-0066 / Spec 018 | `docs/architecture/systems/fulfillment-service.md` | Replace stale one-payment/null-item description with the implemented item-granular DTO, schema, and `(payment, order item)` idempotency contract | High | Yes |
| 3 | ADR-0067 / Spec 019 | `docs/architecture/systems/order-service.md` and `payment-service.md` | Normalize TXRF/PMRF ownership, callback convergence, order closeout, and payment ledger reversal as current facts | High | Yes |
| 4 | ADR-0065 / Spec 017 | `docs/architecture/systems/reconciliation-service.md` | Add current audit package, four audit dimensions, adjustment lifecycle, SUSPENSE, endpoints, and no-source-mutation boundary | High | Yes |
| 5 | ADR-0065 / Spec 017 | `docs/architecture/systems/settlement-service.md` | Add current audit-gate decision, blocker rules, confirmed-fact gate, and SUSPENSE/settlement relationship | High | Yes |
| 6 | ADR-0071 / Spec 027 | `docs/architecture/systems/payment-service.md` | Add payment-limit ownership, three tables, reserve/confirm/release, idempotency, `LIMIT_EXCEEDED`, and Redis expiry-index exception | High | Yes |
| 7 | ADR-0068 / Spec 021 | `docs/architecture/technical-solution.md` and relevant engineering/common-core guidance | Add concise current access-log/MDC contract, payload limits, service field, and masking-hook status | Medium | Yes |
| 8 | ADR-0070 / Spec 026 | `docs/architecture/technical-solution.md` and `deployment/README.md` | Consolidate host/container dual-mode contract, mode guard, jar prerequisite, Compose topology, health checks, and observability behavior | Medium | No |
| 9 | Spec 022 / ADR-0069 | `docs/architecture/technical-solution.md` and `deployment/e2e-tests/README.md` | Record the current E2E module, DB assertions, CI layer, environment assumptions, and known local proxy artifact | Low | No |
| 10 | ADR-0071, ADR-0066, ADR-0067, ADR-0068, ADR-0070 | Current L0 documents only | After facts are promoted, remove historical implementation narration from current sections while retaining ADR/Spec links as navigation | Medium | Yes where status is disputed |
| 11 | ADR-0074 / Spec 029 | `technical-solution.md` and affected System Designs | Separate Proposed event topology from current runtime facts; do not promote it until implemented | High | Yes |

## 10. Additional Candidates

These were observed during the audit but are outside the Phase 1 candidate set and are not promoted in Phase 3A:

- ADR-0074 / Spec 029: explicitly excluded active work.
- ADR-0075 and ADR-0076 / Spec 030: present in the current working tree and referenced by payment documentation, but not part of the Phase 1 candidate list; handle in a separately approved mapping pass.
- ADR-0069 / Spec 022: included only as a Feature-level dependency in the Phase 3B plan, not as a first-batch ADR promotion candidate.

## 11. Phase 3A Boundary

Completed in this phase:

- implementation and L0 evidence chains for all eight specified ADR candidates;
- current/missing/historical facts and targets for all ten specified Feature Specs;
- cleanup candidates and explicit documentation drift records;
- human review points and a concrete Phase 3B change plan.

Not performed:

- no modification to `technical-solution.md`;
- no modification to `docs/architecture/systems/*.md`;
- no modification to ADRs or Specs;
- no code, schema, API, lifecycle status, archive, or runtime changes;
- no Phase 3B execution.
