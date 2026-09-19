# Documentation Migration Plan

> Phase 1 only. This is a planning document for the documented governance refactor; no implementation changes are made here.

## 1. Objective

Create a long-lived documentation architecture that enforces the following rule:

- Current Architecture = current system facts
- System Design = service current facts
- ADR = why a decision was made
- Spec = feature lifecycle
- Historical = archival / on-demand context

The goal is to reduce unnecessary context loading, prevent recursive reading, and keep AI default reads focused on the current system.

## 2. Scope

This plan is intentionally limited to documentation governance. It does not modify business logic, database schema, API contracts, or runtime behavior.

## 3. Phase-by-phase plan

### Phase 1 — Audit (this step)

Goal: inventory the current documentation topology and identify boundary violations.

Deliverables:

- `docs/audit/document-inventory.md`
- `docs/audit/document-migration-plan.md`

Status: completed for audit only

### Phase 2 — Policy

Goal: formalize AI context loading rules and the “reference != dependency” rule into `AGENTS.md` and supporting guidance.

Planned actions:

- add a scoped “AI Context Loading Policy” section in `AGENTS.md`
- define a default read path for ordinary tasks
- define read path for active feature work
- define read path for architecture work
- define read path for historical investigations
- explicitly prohibit automatic recursive expansion through references

Need human review? No, this is a documentation policy change, but it should be reviewed before broad adoption.

### Phase 3 — Knowledge promotion

Goal: move current-state facts from implemented specs / ADRs into authoritative architecture files without copying history wholesale.

Planned actions:

- identify implemented-but-not-promoted ADRs
- identify completed features whose facts are already living in code and system design
- move only current valid facts into `technical-solution.md` and system docs
- keep ADR/spec as historical evidence, not active source-of-truth

Need human review? Yes, if a fact appears to be ambiguous or if code and docs contradict each other.

### Phase 4 — Lifecycle metadata

Goal: add minimal lifecycle metadata where useful, without rewriting the entire documentation set.

Planned actions:

- add lightweight metadata only where a doc already has equivalent status fields
- prefer existing status conventions over adding a new custom format
- keep metadata minimal and non-redundant

Need human review? Usually no, unless there is a mismatch between code, docs, and status.

### Phase 5 — Historical classification

Goal: classify ADR/spec documents as active, historical, superseded, rejected, or on-demand.

Planned actions:

- mark completed feature specs as historical once current facts are promoted
- keep superseded ADRs as historical records with explicit supersession links
- retain rejected ADRs as negative examples / archive
- do not delete historical docs unless explicit migration planning indicates a justified removal

Need human review? Yes, for ambiguous or high-risk classification.

### Phase 6 — Validation

Goal: validate that the final documentation architecture aligns with the code and with AI context loading rules.

Planned checks:

- no recursive document expansion triggered by direct references
- current architecture is the default recommendation
- active feature docs are limited to active work
- historical docs are on-demand only
- no evidence of duplicate source-of-truth
- no obvious drift between docs and code

Need human review? Yes for any drift that cannot be explained by intentional divergence.

## 4. Proposed migration decisions

### 4.1 Keep as current architecture

These should remain the default fact sources:

- `docs/architecture/technical-solution.md`
- `docs/architecture/systems/*.md`
- `docs/architecture/roadmap.md` only as planning context, not default fact-base

### 4.2 Keep as policy and constraint sources

- `.specify/memory/constitution.md`
- `docs/guides/ai-standards.md`
- `docs/guides/engineering-standards.md`
- `docs/guides/business-standards.md`

These are required for some tasks, but not every task.

### 4.3 Keep as historical / on-demand

- `docs/adr/*.md`
- `docs/archive/audits/*.md`
- completed feature specs that have already been captured in current architecture

### 4.4 Retain active feature work in the active path

- current active spec / plan / tasks for the feature being implemented
- any directly relevant ADRs for that feature

## 5. Known “implemented but not promoted” candidates

These should be reviewed in Phase 3:

- ADR-0072
- ADR-0073
- ADR-0071
- ADR-0065
- ADR-0066
- ADR-0067
- ADR-0068
- ADR-0070

These appear to be implemented and partially reflected in the architecture docs, but they still need a clear “current fact” canonicalization step.

## 6. Known historicalization candidates

These should be reviewed for historical default classification after promotion:

- completed feature specs already entrenched in code and current architecture
- older ADRs that remain useful as historical records only
- superseded ADRs and rejected ADRs

## 7. Human review required list

The following should be explicitly flagged:

- `ADR-0074` status and active scope
- any completed spec that still reads as active despite implementation
- any doc where current architecture and code differ materially
- any final classification where the repository has mixed “current state” and “historical narrative” without a clear boundary

## 8. Risk controls

The following are explicitly not part of this migration attempt:

- no business logic changes
- no database schema changes
- no API contract changes
- no runtime behavior changes
- no broad deletion of ADR/spec history
- no “architectural cleanup” that removes historical value while leaving the repository in an ambiguous state

## 9. Exit criteria for Phase 1

Phase 1 is complete when:

- inventory is captured
- migration plan is recorded
- major lifecycle boundaries have been identified
- active vs historical classification is reasonably bounded
- remaining ambiguities are explicitly noted for human review

No Phase 2 changes should be made until the audit is confirmed.
