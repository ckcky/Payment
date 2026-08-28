# Implementation Plan: 支付可靠性（超时、UNKNOWN 收敛、有限重试与人工收敛）

**Branch**: `003-payment-reliability` | **Date**: 2026-08-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-payment-reliability/spec.md`

## Summary

本 Feature 为 Phase 3 Payment Reliability，把「支付可靠性」从原则落地为机制：超时进入 UNKNOWN、UNKNOWN 经主动查询/回调收敛、幂等调用的有限重试与耗尽、人工收敛端点、以及可靠性指标与告警。所有决策的分歧点已落到 ADR-0003~0007（状态 Proposed，待负责人按 Constitution §8 确认）。实现复用既有 `PaymentUnknownResolutionService`、支付状态机终态吸收与 002 成功回写，不引入 MQ/分布式事务、不新增服务。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.x + Spring Cloud

**Primary Dependencies**: Spring (web / scheduling / validation)、MyBatis-Plus、Micrometer（metrics）、JUnit 5 + Mockito + AssertJ、Testcontainers（集成测试）。复用既有 `payment-service` 内 `ChannelAdapter` 接口与 Mock Channel。

**Storage**: 每个服务独立 Schema 的 MySQL（当前单机同物理库）；本 Feature 仅在 `payment-service` 自有 Schema 内新增/调整字段，**MUST NOT** 触碰他服务表。UNKNOWN 时长等指标使用持久化层 `BaseEntity` 的时间戳（createdAt/updatedAt），不向 Payment 领域聚合新增审计时间戳字段（避免 Constitution §8.8 领域模型变更）。

**Testing**: JUnit 5 + Mockito + AssertJ；关键路径（超时→UNKNOWN、查询收敛、重试+退避、硬拒绝不重试、重试耗尽→UNKNOWN、人工收敛、乱序终态吸收）MUST 有单测与集成测试；`MUST NOT` 删测试或改测试迎合错误实现。

**Target Platform**: JVM / Linux 服务（单机多服务）

**Project Type**: 多模块 Web 服务（Spring Cloud 微服务）；本 Feature 仅改动 `payment-service`

**Performance Goals**: 调度扫描与查询收敛为后台低优先级任务，不阻塞支付主链路（同步 RPC）；重试退避上限须防止雪崩（默认 1s/2s/4s）。

**Constraints**: 所有外部渠道调用 MUST 有超时；超时 ≠ 成败，进 UNKNOWN；仅幂等调用可重试；跨服务沿用同步 RPC + 幂等；不引入 MQ/2PC/XA。

**Scale/Scope**: 当前单节点调度器单实例；多节点分布式调度锁不在本 Feature（后续演进另立 ADR）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条款 | 本 Feature 合规情况 | 结论 |
|---|---|---|
| §II 资金正确性（金额用 long/BigDecimal） | 不新增金额计算；仅在既有 Payment/Transaction 上迁移状态 | ✅ 通过 |
| §III 领域边界（Payment ≠ Channel，数据所有权） | 仅在 `payment-service` 自有 Schema 内改动；不跨服务改表 | ✅ 通过 |
| §IV 架构（无 MQ、无 2PC、同步 RPC、Saga+幂等） | 收敛/重试/超时均用进程内 Scheduler + 同步 RPC + 幂等，不引入中间件 | ✅ 通过（与 ADR-0001 一致） |
| §V 一致性（幂等/状态机/重试/超时/UNKNOWN） | 全面对齐：超时→UNKNOWN、终态吸收、有限退避重试、UNKNOWN 不猜成败 | ✅ 通过 |
| §VII 可观测（metrics/审计/告警） | 新增 payment.timeout / payment.retry / payment.retry_exhausted / manual.resolution 等指标与「UNKNOWN 堆积」「重试耗尽」告警 | ✅ 通过 |
| §VIII AI 开发（先理解代码、先 ADR/Plan、不擅自改领域模型） | 已读既有 `PaymentUnknownResolutionService`/`Payment` 状态机/002 回写；分歧点已立 ADR-0003~0007 | ✅ 通过 |
| Governance §8.8 支付状态机变更 | ADR-0007 推荐**保持既有状态机不变**（维持终态吸收），未新增/修改状态流转规则；仅补充触发（超时/查询/重试/人工） | ⚠️ 须负责人确认 ADR-0007 后再实现 |
| Governance §8.5 安全策略 | 人工收敛端点涉及权限/审计，已由 ADR-0006 约束（受控端点 + 强制 FINANCIAL_AUDIT） | ⚠️ 须负责人确认 ADR-0006 后再实现 |
| Governance §8.2 重大架构变化 | 未新增服务/中间件；收敛/重试机制属在既有 payment-service 内扩展 | ✅ 通过（无需新 ADR 论证基础设施） |

**Gate 结论**：除 ADR-0003~0007 待人类确认外，无 Constitution 违反。实现前 MUST 先由负责人确认这 5 条 ADR（尤其 §8.5/§8.8 相关）。

## Project Structure

### Documentation (this feature)

```text
specs/003-payment-reliability/
├── spec.md              # 已完成（/speckit-specify）
├── checklists/
│   └── requirements.md  # 已完成（质量校验）
├── plan.md              # 本文件
├── research.md          # Phase 0：决策汇总（ADR-0003~0007）
├── data-model.md        # Phase 1：实体字段设计
├── contracts/
│   └── manual-resolution.md  # Phase 1：人工收敛内部 RPC 契约
├── quickstart.md        # Phase 1：验证指南
└── tasks.md             # Phase 2：任务清单（/speckit-tasks）
```

### Source Code (repository root)

```text
payment-service/src/main/java/com/payment/payment/
├── domain/
│   ├── Payment.java            # 既有状态机（保持，仅补充触发入口）
│   ├── PaymentAttempt.java     # 既有；本 Feature 扩展 attemptIndex/errorType/nextRetryAt
│   └── PaymentRepository.java  # 既有
├── application/
│   ├── PaymentResultProcessor.java       # 既有 applyAndNotify（复用）
│   ├── PaymentUnknownResolutionService.java  # 既有 resolve（复用，被查询调度器调用）
│   ├── TimeoutScanScheduler.java    # 新增：PROCESSING→UNKNOWN
│   ├── UnknownQueryScheduler.java   # 新增：UNKNOWN→查询渠道→resolve
│   ├── PaymentRetryScheduler.java   # 新增：瞬时失败重试
│   └── ManualResolutionService.java # 新增：人工收敛（受控）
├── infra/
│   ├── client/ChannelAdapter.java    # 既有；本 Feature 复用 queryStatus(idempotencyKey)
│   └── persistence/...               # 既有；本 Feature 仅加列（entered_unknown_at/resolved_at 等）
└── api/
    └── internal/PaymentResolutionController.java  # 新增：人工收敛内部端点
```

**Structure Decision**: 完全落在既有 `payment-service` 模块内，沿用 `api → application → domain ← infra` 分层；不新增模块/服务，不引入中间件。

## Complexity Tracking

> 无 Constitution 违反需要论证（本 Feature 均为既有服务的内部扩展，未突破领域边界/架构边界）。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| （无） | — | — |
