# Documentation Audit Inventory

> Phase 1 only. This is a read-only audit and does not change current architecture or code behavior.

## 1. Current documentation architecture

The repository already has a workable three-layer model, but the actual behavior is uneven:

- L0: Current system facts
  - `docs/architecture/technical-solution.md`
  - `docs/architecture/systems/*.md`
- L1: Current constraints
  - `.specify/memory/constitution.md`
  - `docs/guides/*.md`
- L2: Active feature work
  - `docs/specs/<feature>/spec.md`
  - `docs/specs/<feature>/plan.md`
  - `docs/specs/<feature>/tasks.md`
- L3: Historical / on-demand
  - `docs/adr/`
  - `docs/archive/audits/`
  - completed feature specs that have already been absorbed into current system design

The repository is broadly aligned with the intended model, but the boundary is not yet consistently enforced in practice. The biggest gap is that many completed feature specs and ADRs still read like active knowledge sources, while the current architecture is sometimes mixed with historical decision detail.

## 2. Inventory summary

| Document | Type | Status | Scope | Current / Historical | AI read policy | Target |
|---|---|---|---|---|---|---|
| `AGENTS.md` | policy | current | global | Current | Default | Keep |
| `docs/README.md` | doc-map | current | global | Current | Default | Keep |
| `docs/architecture/technical-solution.md` | architecture | current | global | Current | Default | Keep |
| `docs/architecture/roadmap.md` | roadmap | current | global | Current (planning) | On demand | Keep as planning doc |
| `docs/architecture/systems/payment-service.md` | system design | current | payment | Current | Default | Keep |
| `docs/architecture/systems/order-service.md` | system design | current | order | Current | Default | Keep |
| `docs/architecture/systems/ledger-service.md` | system design | current | ledger | Current | Default | Keep |
| `docs/adr/README.md` | index | current | global | Historical reference | On demand | Keep |
| `docs/adr/*.md` | ADR | mixed | mixed | Historical by default | On demand / never-by-default | Keep but classify |
| `docs/specs/<feature>/spec.md` | feature spec | mixed | feature | Active or Historical depending on lifecycle | Required only for active features | Keep |
| `docs/specs/<feature>/plan.md` | implementation plan | mixed | feature | Active or Historical | Required only for active features | Keep |
| `docs/specs/<feature>/tasks.md` | execution record | mixed | feature | Active or Historical | Required only for active features | Keep |
| `docs/archive/audits/*.md` | archive | historical | historical | Historical | Never by default | Keep |

## 3. Current state findings

### 3.1 Strong current-state documents already exist

The strongest current architecture sources are already present:

- `docs/architecture/technical-solution.md`
- `docs/architecture/systems/payment-service.md`
- `docs/architecture/systems/order-service.md`
- `docs/architecture/systems/ledger-service.md`
- plus other service design docs under `docs/architecture/systems/`

These are the documents that should be treated as the default fact base for ordinary coding tasks.

### 3.2 Current architecture is mostly in the right place

The repository has already made meaningful progress toward the intended model:

- Global current facts are concentrated in `technical-solution.md`
- Service-level current facts are concentrated in `systems/*.md`
- Historical reasons and decision rationale are concentrated in ADR files
- Completed feature specs remain in `docs/specs/` but should not be treated as the default fact source once promoted

### 3.3 Main problem: historical content is still too easy to read as current truth

The biggest risk is not directory disorder; it is context expansion.

Examples from the current docs:

- `docs/architecture/technical-solution.md` still contains a number of historical/implementation notes and proposed roadmap references alongside current-state facts.
- Some service design docs still mention proposed feature references such as spec 029 / ADR 0074 without clearly separating “current fact” from “planned next step”.
- Some specs still carry `Draft` or `Proposed` markers even when the underlying code is already implemented, which blurs current-state boundaries.
- `docs/adr/README.md` and the individual ADR files are very rich, but they are not being treated as “current system fact” by default; they are a historical archive, not the primary working memory for AI agents.

## 4. High-risk patterns found

### 4.1 Evidence of context explosion risk

Observed pattern:

- `technical-solution.md` references ADRs and specs for current-state context
- service design docs reference ADRs and specs
- ADR index references specs and implementation notes
- feature specs reference earlier ADRs

This is not automatically a bug, but it creates a recursive read chain when agents read “safe-looking” documents without checking whether they need the additional context. The repository needs a hard rule: “reference does not imply dependency”.

### 4.2 Historical and current facts are mixed in the same layer

This is the main documentation drift pattern.

Examples:

- `technical-solution.md` is mostly current-state, but still contains historical-transition markers and “proposed” notes.
- roadmap entries list current features and historical phases in the same section as current status, which is useful for planning but not ideal as a default fact source.
- some “Accepted / Implemented” ADRs and specs are still active in the documentation narrative even after being effectively promoted into the architecture layer.

### 4.3 Notable gaps in explicit lifecycle metadata

The project has strong documentation, but metadata is not uniformly enforced across all ADR/spec files. The repository demonstrates the correct content, yet lacks a single consistent “AI read policy” clue on all documents.

This is why a metadata policy is still needed in the next phase, but not during Phase 1.

## 5. ADR classification (preliminary audit)

This is a provisional classification based on file state and current architecture references. It should be confirmed by human review if the repository wants a final canonical classification.

| Classification | Count | Notes |
|---|---:|---|
| Active | 1 | ADR-0074 is the clearest active decision in flight; it is current and not yet implemented |
| Implemented but not promoted | 3 | Several implemented ADRs still need stronger promotion into current architecture language |
| Implemented and promoted | 12+ | Many of the accepted/implemented ADRs are already reflected in technical-solution/system design |
| Superseded | 4+ | Supersession chains are visible and should remain historical |
| Rejected | 1 | ADR-0016 partial refund rejection remains a historical decision |
| Historical | 10+ | Many older ADRs are historical and should be read only on demand |
| Human review required | 2+ | Ambiguous or transitional ADRs should be checked before a final promotion decision |

Important examples:

- `ADR-0074` — active, proposed, not yet implemented; should remain in active work context
- `ADR-0031` — superseded by ADR-0074; remains historical
- `ADR-0054` / `ADR-0065` / `ADR-0066` / `ADR-0067` — implemented and mostly reflected in current architecture, but should still be treated as historical references by default
- `ADR-0016` — rejected, not to be treated as current truth

## 6. Spec classification (preliminary audit)

| Classification | Count | Notes |
|---|---:|---|
| Active | 1 | `029-redis-transactional-mq` is the clearest current active spec in progress |
| Completed | 10+ | Several feature specs are completed and currently represented in code and architecture |
| Historical | 10+ | Many completed specs should be historical by default after promotion |
| Missing promotion | 3+ | Some completed specs are partially absorbed but not fully reflected in current-state docs |
| Human review required | 2 | Transitional or ambiguous statuses should be checked before final historicalization |

Examples:

- `028-channel-routing` is implemented and appears to be largely absorbed into service design; it should be historical by default once the current architecture is fully normalized.
- `027-user-payment-limit` is implemented but appears to be in a transition state between “feature work” and “current architecture”.
- `029-redis-transactional-mq` is genuinely active and should remain in active feature context.
- several older specs remain “Draft” in file headers even though the code is already implemented; these should be reviewed before final historicalization.

## 7. Current state gaps to address in later phases

The following are the most meaningful “current fact missing” / “promotion needed” items identified in Phase 1:

1. Current architecture should more explicitly state what is truly active vs. historically implemented but no longer the default context.
2. The “active feature” boundary should be expanded to include a single canonical list of current “in-flight” ADR/specs; the repo currently has several partially overlapping active references.
3. The distinction between “implemented and promoted” versus “implemented but not promoted” should be made explicit in the architecture layer.
4. There are still historical/roadmap references cached inside the current system docs; those should be separated into historical-on-demand references.
5. The current architecture should formally own the truth of the system as it runs today, with ADR/specs treated as explanatory history only.

## 8. ADR / Spec candidates for knowledge promotion

### 8.1 ADRs that likely should be promoted into current architecture

These are the strongest candidates for current-state promotion based on the existing architecture docs:

- ADR-0072 / two-layer channel architecture
- ADR-0073 / channel routing
- ADR-0071 / user payment limit (if the current architecture intends to keep it as a real active domain)
- ADR-0065 / accounting audit and suspense adjustment
- ADR-0066 / schema normalization and item-level fulfillment
- ADR-0067 / order-driven refund
- ADR-0068 / unified access logging
- ADR-0070 / local containerized stack

These are already partially present in the current design docs, and should be treated as part of current fact after a formal promotion check.

### 8.2 Spec files that should eventually become historical by default

After effective knowledge promotion, the following categories should move to historical default status:

- completed feature specs that are already reflected in architecture/system docs
- specs whose code and architecture are fully live
- specs that are no longer used as the working memory for ordinary implementation work

Examples include:

- `001-core-business-model`
- `016-order-payment-orchestration`
- `017-accounting-audit`
- `018-schema-normalization-item-fulfillment`
- `019-order-driven-refund`
- `021-unified-access-logging`
- `022-full-chain-automated-testing`
- `026-containerized-local-stack`
- `027-user-payment-limit`
- `028-channel-routing`

### 8.3 Active docs that should remain active

- `029-redis-transactional-mq` (active feature)
- any current in-flight ADR/spec that is truly not yet implemented and not yet absorbed into architecture

## 9. Conflict / drift notes

This Phase 1 audit found the following conflicts or drift risks:

1. Some docs describe a current state that still mentions “proposed” or “not implemented” work as if it were already part of the live system.
2. There is a risk of “current architecture = mixed architecture + proposal backlog” if the docs are not carefully limited.
3. Some previously completed features still read like active design work when they should be historical context.
4. Specification status strings (`Draft`, `Implemented`, `Accepted`, `Proposed`) are not consistently aligned with the code state or the current architecture narrative.
5. A single, explicit “current-state source of truth” is present in concept, but not yet enforced uniformly as a hard rule for all AI reads.

## 10. Human decisions required

These should be escalated before Phase 2 begins:

- Whether `ADR-0074` should be considered a currently active implementation decision or merely a planned next-stage ADR.
- Whether some implemented specs are already sufficiently promoted to be marked historical by default.
- Whether any current architecture sections should be tightened to explicitly remove roadmap-style or proposal-style references.
- Whether some older ADRs should remain as historical records without being surfaced in default AI context.

## 11. Phase 1 conclusion

The repo already contains most of the right structural ideas: current architecture, system design, ADRs, and specs. The main problem is not a missing folder but a missing hard boundary between:

- Current system facts
- Active feature work
- Historical evidence

The phase 1 audit confirms that the repository is close to the target architecture, but it still needs a disciplined policy to prevent recursive context expansion and to keep AI read behavior aligned with the actual lifecycle of each document.
