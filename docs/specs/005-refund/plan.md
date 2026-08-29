# Implementation Plan: Refund 退款（缺口补齐）

**Branch**: `005-refund` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-refund/spec.md`

## Summary

本 Feature **不是从零构建 refund-service**，而是对**已实现**的 `refund-service`（8085 / Schema `refund`）做缺口补齐与收口。核心交付：① 让已存在但不可达的 `PARTIALLY_SUCCEEDED` 端到端可达（引入「已确认退款金额」并扩展累计额度口径）；② 补齐完全缺失的 refund → fulfillment 后处理 RPC，并把静默吞掉的 entitlement 失败改为可独立追踪的尝试记录；③ 为 `resolve` 收敛入口补充防御性前置断言；④ 将已确认退款接入已实现的 `ledger-service` 记账（承接 spec `004-ledger` US2 在退款侧的缺口）。所有设计分歧点已落到 ADR-0016~0018（Proposed，待负责人确认）。不引入 MQ / 新中间件。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.5 + Spring Cloud

**Primary Dependencies**: Spring Web / Validation、MyBatis-Plus、OpenFeign、Micrometer（metrics）、JUnit 5 + Mockito + AssertJ、Testcontainers（集成测试）。复用 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`/`Money`）、`common-dto`（跨服务 RPC 契约 DTO）。

**Storage**: 独立 Schema `refund` 的 MySQL 8.0。既有 `refunds` / `refund_items` / `refund_intake_locks`；本 Feature 为 `refunds` 增列 `refunded_amount_minor`，并新增 `refund_post_process_attempts` 表（详见 data-model.md）。DDL 权威文件 `deployment/schema/06-refund-schema.sql`。

**Testing**: JUnit 5 + Mockito + AssertJ；资金路径（部分金额、累计额度、幂等吸收、后处理失败追踪、记账幂等）MUST 有单测与集成测试（Testcontainers MySQL）；既有 refund 测试（6 个领域/应用测试 + `RefundScenarioTest` 集成测试）MUST 保持通过；`MUST NOT` 删测试或改测试迎合错误实现。

**Target Platform**: JVM / Linux 服务（单机多服务）

**Project Type**: 多模块 Web 服务（Spring Cloud 微服务）；**在既有 `refund-service` 模块内扩展**，另需在 `fulfillment-service` 新增一个入站端点。

**Performance Goals**: 沿用 `refund-service.md` §1.3 `[目标]`：创建退款受理 P99 ≤ 500ms、收敛处理 P99 ≤ 300ms（新增记账/第二路后处理 RPC 后需复核）。

**Constraints**: 金额禁 float/double（long 分）；状态迁移唯一入口 + 乐观锁；幂等键 DB 唯一约束；跨服务同步 RPC + 幂等，禁 MQ / 2PC / XA；Database-per-service；后处理与记账失败不得回滚退款成功事实。

**Scale/Scope**: 当前单节点；单币种（CNY）；多币种清分、真实出款、复杂审批不在本 Feature。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条款 | 本 Feature 合规情况 | 结论 |
|---|---|---|
| §II.1 金额铁律（long 分 / BigDecimal，禁 float/double） | `refundedAmountMinor` 与全部新增金额字段均为 `long` 最小货币单位；FR-011 显式声明 | ✅ 通过 |
| §II.2 Money 值对象 | 退款侧沿用既有 `long amountMinor` 表示（与全项目现状一致）；是否启用 `Money` VO 属跨服务全量改造（审计 P0-1），**不在本 Feature 扩大范围** | ✅ 通过（不改现状，不扩大） |
| §II.3 一切资金变动经 ledger-service 复式记账 | US4/FR-009 补上退款侧记账（当前唯一未接入的已确认资金变动）；借贷平衡由账本侧强校验 | ✅ 通过（依赖 ADR-0018） |
| §III 边界 #5 Refund ≠ Payment Refund（跨域编排） | 本 Feature 正是把编排面补全（payment + fulfillment + entitlement + ledger），而非只做渠道退款 | ✅ 通过 |
| §III 边界 #6 Fulfillment 不强耦合 Payment/Refund | fulfillment 撤销请求由 fulfillment 自身状态机决定（当前仅 `PENDING → CANCELLED`），refund 不改写其状态 | ✅ 通过（能力边界见 ADR-0017） |
| §III 依赖方向（Refund 可依赖底层领域，Ledger 只被依赖） | refund → payment/fulfillment/entitlement/ledger 均为单向出站；ledger 不反向依赖 | ✅ 通过 |
| §IV 架构（无 MQ、Database-per-service、禁 2PC） | 全部新增交互为同步 Feign RPC + 幂等；只读写 `refund` Schema | ✅ 通过（与 ADR-0001 一致） |
| §V.1/§V.4/§V.5 幂等 / 重试 / 重复回调 | 幂等键 + 唯一约束维持；后处理有限退避重试；终态吸收重复收敛；`resolve` 补防御断言 | ✅ 通过 |
| §V.2 状态机集中、禁散落 setStatus | `PARTIALLY_SUCCEEDED` 仍经 `transitionTo` 唯一入口；新增字段不改迁移规则 | ✅ 通过 |
| §V.7 UNKNOWN 不猜成败、不重复执行资金动作 | FR-007 显式约束：UNKNOWN 不重试渠道退款、不触发后处理与记账 | ✅ 通过 |
| §VII 可观测（metrics + 资金审计 + 业务告警） | 新增 `refund.partially_succeeded` / `refund.post_process_failed`；`FINANCIAL_AUDIT` 覆盖新分支（FR-015） | ✅ 通过 |
| §VIII AI 开发（先理解代码、先 ADR/Plan、不擅改领域模型） | 已先核实代码并标注 `file:line`；领域模型扩展已落入 ADR-0016 待确认 | ✅ 通过 |
| Governance §8.3 新增关键资金字段/表 | `refunds.refunded_amount_minor` 为关键资金字段；`refund_post_process_attempts` 为新增表 | ⚠️ 须负责人确认（ADR-0016） |
| Governance §8.8 状态机变更 | `PARTIALLY_SUCCEEDED` 由不可达变为可达，属退款状态机流转规则变更 | ⚠️ 须负责人确认（ADR-0016） |
| Governance §8.4 跨服务接口变更 | `RefundAttemptResponse` 增字段（refund↔payment）、新增 refund→fulfillment 端点 | ⚠️ 须负责人确认（ADR-0016/0013） |

**Gate 结论**：除 ADR-0016~0018 待人类确认外，无 Constitution 违反。实现前 MUST 先由负责人确认这 3 条 ADR（尤其 §8.3 / §8.4 / §8.8 相关项）。

## Project Structure

### Documentation (this feature)

```text
specs/005-refund/
├── spec.md                          # 已完成
├── plan.md                          # 本文件
├── data-model.md                    # Refund/RefundItem + 部分退款 schema 不变量
├── contracts/
│   └── refund-orchestration.md      # refund↔payment / fulfillment / entitlement / ledger 契约
├── checklists/
│   └── requirements.md              # 需求质量校验
├── acceptance.md                    # 验收清单
├── quickstart.md                    # 本地验证指南
└── tasks.md                         # 任务清单（未开始）
```

### Source Code (repository root)

```text
refund-service/src/main/java/com/payment/refund/
├── domain/
│   ├── Refund.java                          # [改] 增 refundedAmountMinor + partiallySucceed 可达
│   ├── RefundPolicy.java                    # [改] 累计口径：终态计已确认额 / 在途计申请额
│   ├── RefundPostProcessAttempt.java        # [新] 后处理尝试实体（可独立追踪）
│   └── RefundPostProcessTarget.java         # [新] FULFILLMENT / ENTITLEMENT / LEDGER
├── application/
│   ├── RefundApplicationService.java        # [改] 结果分支 + 编排后处理（两侧）+ 记账触发
│   ├── RefundRpcCallbackService.java        # [改] resolve 防御断言 + 收敛后后处理/记账
│   ├── RefundPostProcessOrchestrator.java   # [新] 后处理编排（记录尝试、失败不回滚）
│   ├── FulfillmentGateway.java              # [新] 出站端口（缺失的 RPC）
│   └── LedgerPostingGateway.java            # [新] 出站端口（对齐 payment-service 既有实现）
├── infra/client/
│   ├── FulfillmentFeignClient.java          # [新]
│   └── LedgerFeignClient.java               # [新]
├── infra/persistence/refund/
│   ├── RefundEntity.java / RefundMapper.java            # [改] 新列映射
│   └── RefundPostProcessAttemptMapper.java / Entity.java # [新]
└── api/
    └── RefundResponse.java                  # [改] 暴露 refundedAmountMinor

fulfillment-service/src/main/java/com/payment/fulfillment/
├── api/RefundRpcController.java             # [新] POST /internal/fulfillments/on-refund
└── application/FulfillmentApplicationService.java  # [改] 退款撤销用例

common/common-dto/src/main/java/com/payment/common/dto/rpc/
└── RefundAttemptResponse.java               # [改] 增 refundedAmountMinor（向后兼容：新增字段）
```

**Structure Decision**: 全部改动遵循既有 `api → application → domain ← infra` 分层与出站端口模式（端口在 `application`、Feign 实现在 `infra/client`、契约 DTO 在 `common-dto`），与 `PaymentRefundGateway` / `EntitlementGateway` 现有写法保持一致；`fulfillment-service` 只新增入站端点与用例，不改变其状态机规则。

## Complexity Tracking

> 本 Feature 无 Constitution 违反需论证。以下记录两处「刻意不做」的简化，避免后续误读为遗漏。

| 取舍 | 为什么这样做 | 被否决的更复杂方案 |
|---|---|---|
| 部分退款以「单笔 Refund + 已确认金额」表达，不为同一笔退款追加多次渠道尝试 | 与终态吸收语义一致（终态不复活）；剩余额度以新幂等键另开一笔，幂等与累计口径最简单可证 | 「RefundAttempt 子表 + 累加」模型：状态机与幂等语义成倍复杂，且当前无真实渠道支持同单多次退 |
| 后处理失败只做「同步有限重试 + 独立记录」，不引入重试调度器 | 与 Constitution §IV（无 MQ）及既有 entitlement 后处理语义一致；失败可追踪已满足验收标准 | 独立重试调度器 / outbox：引入新基础设施，违反基础设施决策门槛 |
