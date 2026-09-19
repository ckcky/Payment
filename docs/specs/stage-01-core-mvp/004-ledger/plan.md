# Implementation Plan: Ledger 资金账本（复式记账）

**Branch**: `004-ledger` | **Date**: 2026-08-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-ledger/spec.md`

## Summary

本 Feature 实现 `ledger-service`（端口 8090，Schema `ledger`），把 Constitution §II.3「一切资金变动 MUST 经 ledger-service 复式记账」从原则落地为服务。核心交付：① 复式记账数据模型（Account / Posting / LedgerEntry，append-only、借贷平衡、幂等）；② 同步 RPC 记账入口（被 payment/refund/settlement 调用）；③ 记账触发与一致性模型（同步幂等 RPC + 失败重试/对账兜底，禁 2PC）；④ 全局借贷平衡性校验与资金审计。所有设计分歧点已落到 ADR-0008~0011（Proposed，待负责人确认）。实现复用既有 `common-core` 与 OpenFeign 工程底座，不引入 MQ/新中间件。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.5 + Spring Cloud

**Primary Dependencies**: Spring Web / Validation、MyBatis-Plus、OpenFeign、Micrometer（metrics）、JUnit 5 + Mockito + AssertJ、Testcontainers（集成测试）。复用 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`/`Money`）、`common-dto`（RPC 契约 DTO）。

**Storage**: 独立 Schema `ledger` 的 MySQL 8.0；`ledger_entries` / `postings` / `accounts` 三表（详见 data-model.md 与 `deployment/schema/09-ledger-schema.sql`）。`LedgerEntry` 不可变，仅 INSERT；`Posting` 状态机极简（PENDING→POSTED；MVP 仅 POSTED）。

**Testing**: JUnit 5 + Mockito + AssertJ；关键路径（支付/退款/结算记账、借贷平衡校验、幂等吸收、拒绝不平衡、记账失败兜底）MUST 有单测与集成测试（Testcontainers MySQL）；`MUST NOT` 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。

**Target Platform**: JVM / Linux 服务（单机多服务）

**Project Type**: 多模块 Web 服务（Spring Cloud 微服务）；新增 `ledger-service` 模块（与既有 9 服务并列）

**Performance Goals**: 单笔记账为本地事务内短写入，P99 ≤ 50ms（本地 MySQL）；平衡性校验为聚合查询，低频调用（对账/运维）。

**Constraints**: 金额禁 float/double；借贷必须平衡；幂等键唯一约束兜底；跨服务同步 RPC + 幂等；不引入 MQ/2PC/XA；不修改业务服务原始事实。

**Scale/Scope**: 当前单节点；科目表为系统预置固定集合（MVP 不动态建科目）；多币种清分属后续。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条款 | 本 Feature 合规情况 | 结论 |
|---|---|---|
| §II 资金正确性（金额/复式记账/Money） | 借贷平衡强校验 + 金额禁 float/double；Money VO 采用见 ADR-0010 | ✅ 通过（ADR-0010 待确认） |
| §III 领域边界（Ledger 只被依赖、不反向依赖） | ledger-service 不依赖任何业务领域；仅暴露记账 RPC | ✅ 通过 |
| §IV 架构（无 MQ、无 2PC、同步 RPC、Saga+幂等） | 同步 RPC 记账 + 失败重试/对账兜底，不引入中间件 | ✅ 通过（与 ADR-0001 一致） |
| §V 一致性（幂等/状态机/重试/UNKNOWN） | 幂等键唯一约束；记账仅对已确认事实；失败不回滚业务事实 | ✅ 通过 |
| §VII 可观测（metrics/审计） | `ledger.posted` / `ledger.posting_failed` 指标 + `FINANCIAL_AUDIT` | ✅ 通过 |
| §VIII AI 开发（先理解代码、先 ADR/Plan、不擅自改领域模型） | 已立 Spec/Plan/ADR；分歧点已落到 ADR-0008~0011 | ✅ 通过 |
| Governance §8.2 重大架构变化（新增服务） | 新增 `ledger-service` 属新增服务，须经负责人确认 ADR-0008~0011 | ⚠️ 须负责人确认 ADR 后再实现 |
| Governance §8.3 数据库 Schema Migration（新增关键资金表） | `ledger` Schema + 三张表属新增关键资金表 | ⚠️ 须负责人确认 ADR 后再实施 |
| Governance §8.5 安全策略 | 记账 RPC 鉴权沿用既有安全基线（见 ADR 待 Phase 9 统一）；MVP 内部可信网络 | ⚠️ 须负责人确认安全范围 |

**Gate 结论**：除 ADR-0008~0011 待人类确认外，无 Constitution 违反。实现前 MUST 先由负责人确认这 4 条 ADR（尤其 §8.2/§8.3 相关）。

## Project Structure

### Documentation (this feature)

```text
specs/004-ledger/
├── spec.md              # 已完成
├── checklists/
│   └── requirements.md  # 需求质量校验
├── plan.md              # 本文件
├── research.md          # Phase 0：决策汇总（ADR-0008~0011）
├── data-model.md        # Phase 1：实体字段设计
├── contracts/
│   ├── post-payment-capture.md   # 支付记账 RPC 契约
│   ├── post-refund.md            # 退款记账 RPC 契约
│   └── post-settlement.md        # 结算记账 RPC 契约
├── quickstart.md        # Phase 1：验证指南
└── tasks.md             # Phase 2：任务清单
```

### Source Code (repository root)

```text
ledger-service/src/main/java/com/payment/ledger/
├── api/
│   ├── LedgerController.java            # 记账 RPC 入口（内部）
│   └── dto/PostingRequest.java          # 入站记账请求
├── application/
│   ├── LedgerPostingService.java        # 记账编排（校验→平衡→落库→审计）
│   └── BalanceChecker.java              # 全局借贷平衡性校验
├── domain/
│   ├── Account.java                     # 科目
│   ├── Posting.java                     # 记账批次（聚合根）
│   ├── LedgerEntry.java                 # 分录（值对象/实体，不可变）
│   └── LedgerRepository.java
├── infra/
│   └── persistence/...                 # MyBatis-Plus 映射
└── LedgerApplication.java

# 调用方（既有服务内新增出站网关）
payment-service/.../application/
└── LedgerPostingGateway.java            # 包裹对 ledger-service 的 Feign 调用（超时/重试/兜底）
refund-service/.../application/LedgerPostingGateway.java
settlement-service/.../application/LedgerPostingGateway.java
```

**Structure Decision**: 新增独立 `ledger-service` 模块（端口 8090，Schema `ledger`），遵循 `api → application → domain ← infra` 分层；调用方仅在「已确认」状态后通过 `LedgerPostingGateway`（Feign）记账，不引入 MQ。

## Complexity Tracking

> 无 Constitution 违反需要论证（新增服务属既有 Constitution §III 已规划的 `Ledger` 领域，非新增边界；但属「新增服务」，须 ADR 确认）。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| （无，待 ADR-0008~0011 确认后填充） | — | — |
