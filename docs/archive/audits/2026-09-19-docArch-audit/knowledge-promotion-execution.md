# Knowledge Promotion Execution Report

> Phase 3B execution report and Phase 4 follow-up record. Documentation-only governance from verified code facts. No Java, SQL, API behavior, or ADR historical正文 was modified.

## 1. Modified Files

- `docs/architecture/technical-solution.md`
- `docs/architecture/systems/payment-service.md`
- `docs/architecture/systems/order-service.md`
- `docs/architecture/systems/fulfillment-service.md`
- `docs/architecture/systems/reconciliation-service.md`
- `docs/architecture/systems/settlement-service.md`
- `docs/architecture/systems/catalog-service.md`
- `docs/architecture/systems/entitlement-service.md`
- `deployment/README.md`
- `docs/audit/knowledge-promotion-map.md`
- `docs/audit/knowledge-promotion-execution.md`

## 2. Knowledge Promotion Summary

### Promoted

- ADR-0071 / Spec 027 → `payment-service.md` existing limit section was retained and aligned with the global current-state summary in `technical-solution.md`: three tables, reserve/confirm/release/expired operations, DB authority, `LIMIT_EXCEEDED`, and Redis TTL-index-only behavior.
- ADR-0067 / Spec 019 → `order-service.md`, `payment-service.md`, and `technical-solution.md`: TXRF/PMRF ownership, order initiation, payment channel/ledger facts, callback convergence, and order closeout.
- ADR-0066 / Spec 018 → `fulfillment-service.md` and `technical-solution.md`: item-level fulfillment, `PaymentSucceededRequest.items`, `order_item_id` as OI business number, and `(source_payment_no, order_item_id)` idempotency.
- ADR-0065 / Spec 017 → `reconciliation-service.md`, `settlement-service.md`, and `technical-solution.md`: four audit dimensions, adjustment lifecycle, SUSPENSE, append-only ledger adjustment boundary, and settlement gate.
- ADR-0068 / Spec 021 → `technical-solution.md`: current request-end `ACCESS_LOG`, bounded payloads, MDC/traceId, service name, and masking-hook status.
- ADR-0070 / Spec 026 → `technical-solution.md` existing deployment facts and `deployment/README.md`: host/container modes, profiles, mode guard, jar-copy model, health checks, and observability.
- Spec 022 / ADR-0069 evidence → `technical-solution.md` and existing `deployment/e2e-tests/README.md`: E2E module, HTTP/DB assertions, explicit environment activation, reports, dumps, and CI layering.
- ADR-0072 / ADR-0073 → `payment-service.md`: verified reverse-channel behavior now states that refund/retry/query resolves the persisted successful PAYMENT attempt channel through the registry, bypassing normal routing and default fallback.

### Cleaned

- Removed ADR-0074/spec 029 event-topology sections from current payment, order, fulfillment, catalog, and entitlement System Designs.
- Removed the old “payment directly calls fulfillment / payment catches 409 for auto-refund” current-state claims from Technical Solution and fulfillment/order descriptions.
- Removed the old fulfillment model claiming one fulfillment per payment and nullable `order_item_id`.
- Removed the payment System Design claim that refund channel persistence was still hardcoded to `mock`.
- Removed the settlement System Design label saying its accepted implementation was still Proposed.
- Preserved ADR-0074/spec 029 as an explicitly labeled Active Proposal in Technical Solution; they were not promoted.

### Not Promoted

- ADR-0074 / Spec 029: Redis Streams transactional messaging and event topology remain active future design.
- ADR-0075 / ADR-0076 / Spec 030: outside the approved Phase 3B candidate set; not promoted.
- Historical rationale, rejected alternatives, migration chronology, and implementation task narratives: retained in ADR/Spec documents.

## 3. Evidence Boundaries

The following facts were promoted only where code evidence was direct:

- Refund reverse routing is confirmed by `PaymentRefundService.resolveEffectiveChannelCode`, `PaymentAttempt` persistence, `ChannelRegistry`, and the refund-attempt recorder. It uses the successful PAYMENT attempt's `channel_code`; missing channel data raises an error rather than falling back.
- Fulfillment idempotency is confirmed by `findBySourcePaymentNoAndOrderItemId`, the composite schema key, `PaymentSucceededRequest.ItemLine`, and `FulfillmentApplicationService`.
- Audit names and states are taken from `AuditDifferenceKind`, `AuditDifferenceStatus`, `AuditController`, `AuditApplicationService`, `CertificateAuditor`, `LedgerAuditor`, `RealAuditor`, and the audit schema.
- Payment Limit facts are taken from the payment limit subdomain, schema `027-user-payment-limit.sql`, and limit tests. No new limit behavior was invented.

## 4. Documentation Drift Remaining

| ID | Code | Document | Conflict | Action |
|---|---|---|---|---|
| DR-001 | Current order/payment/fulfillment code uses synchronous RPC and current service ownership | `docs/architecture/next-stage-design.md` | Still describes payment catch-409 surplus refund as current and ADR-0054 as Proposed | Human review / separate future-document cleanup |
| DR-002 | Current implementation supports item-level fulfillment | `docs/architecture/systems/fulfillment-service.md` | Remaining `[待定]` entries describe implementation gaps such as transaction/compensation behavior | Keep only verified current behavior; separate backlog in a later reviewed cleanup |
| DR-003 | ADR-0074/spec 029 have no implementation evidence | `docs/architecture/technical-solution.md` | Active Proposal is referenced from the current baseline | Intentionally retained as clearly labeled Proposal; do not promote |
| DR-004 | Roadmap/ADR/Spec status records contain lifecycle history | `docs/architecture/roadmap.md`, ADRs, Specs | Some completed items still say Draft/Proposed while code is implemented | Phase 5 lifecycle classification, not Phase 3B |

## 5. Human Review Required

- Confirm whether the remaining historical/planning document `docs/architecture/next-stage-design.md` should be separately normalized; it was not changed because it is outside the mapped L0 targets.
- Confirm whether fulfillment's current lack of automatic entitlement compensation should remain documented as an operational gap or move to a dedicated backlog document.
- Confirm later lifecycle updates for Spec 019, Spec 027, Spec 028, and the stale settlement ADR labels. No lifecycle status was changed in Phase 3B.

## 6. Context Loading Impact

Ordinary coding tasks can now obtain the following without reading their historical ADR/Spec first:

- payment limits and Redis's narrow expiry-index role from payment/global architecture;
- TXRF/PMRF ownership and current refund convergence;
- item-level fulfillment and its actual composite idempotency key;
- Audit/SUSPENSE/Settlement Gate boundaries;
- unified access-log behavior and masking limitations;
- host/container local runtime and E2E validation entry points;
- current synchronous cross-service behavior without future event topology being presented as an existing dependency.

ADR/Spec documents remain on-demand for rationale, rejected alternatives, implementation chronology, acceptance evidence, active proposals, and lifecycle investigation.

## 7. Scope Verification

The following were not changed:

- Java/Kotlin source;
- SQL schema or migrations;
- tests;
- API behavior;
- ADR historical content;
- Feature Spec historical content;
- Git history;
- lifecycle status was not changed during Phase 3B; explicit Phase 4 lifecycle changes are recorded in `final-documentation-governance-report.md`.
