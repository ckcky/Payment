# Implementation Plan: Settlement 结算（缺口补齐）

**Branch**: `007-settlement` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-settlement/spec.md`

## Summary

本 Feature 把 `settlement-service`（端口 8089，Schema `settlement`）从「**能算收入−退款、不能算调整项、闸门靠委托、结果不可关闭、账务不入账**」推进到「**调整项可算、闸门本地可执行、结果可收敛可关闭、账务可追溯**」。服务核心链路**已实现**（资格校验、净额计算、批次+明细持久化、八态状态机、双唯一约束幂等、模拟执行进 UNKNOWN、指标与审计、两个出站 Feign 客户端、5 个测试类），因此本计划**不新建服务、不重写状态机**，只做四项缺口补齐 + 一项文档收口：

1. **G1 调整项真实化**：新增 `settlement_adjustments` 表与 `SettlementAdjustment` 实体（替代零引用的 `Adjustment` 记录），登记端点带幂等键 + 非空理由/操作人 + 审计，`createBatch` 汇总 `ACTIVE` 调整项并生成 `ADJUSTMENT` 明细，净额公式与不变量按 ADR-0022 落地。
2. **G2 闸门本地可执行**：新增 `ConfirmedFactGate`，逐条校验事实的 `type` / 币种 / 金额 / 来源周期，拒绝即不落批次并留指标与日志（N1 的商户维度因契约缺失无法本地校验，记为 `[待定]`）；reconciliation 404 归一化为 `NOT_FOUND`（N2）。
3. **G3/N3/N4/N5/N6 收口**：混币种显式拒绝；接线 `close()` 端点与按商户+周期查询（N3）；出站 Feign 显式超时 + 幂等读有限重试（N4）；幂等键命中校验商户/周期一致性（N5）；收敛携带操作人与理由并落审计（N6）。
4. **G4 账务收口（依赖 ADR-0023）**：新增 settlement 侧 `LedgerPostingGateway`（对齐 payment 侧模式），在批次收敛为 `SUCCEEDED` 时向 `ledger-service` 提交幂等键 `SETTLEMENT:<batchId>` 的平衡 Posting；失败不回滚、进待记账兜底。若负责人选择「遵循 Roadmap Phase 7 不实现 Ledger」，此项降级为 `[待定]`。
5. **G5 文档收口**：修正 `technical-solution.md:106`（骨架）、`:101`（ledger 延后 Phase 8）、`settlement-service.md:91`（adjustment MVP=0）与 `roadmap.md` Current Status。

所有设计分歧点已落到 ADR-0022~0023（**Proposed**，待负责人确认）。实现复用既有 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`）与 OpenFeign / MyBatis-Plus 工程底座，不引入新中间件。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.5 + Spring Cloud

**Primary Dependencies**: Spring Web / Validation、MyBatis-Plus、Spring Cloud OpenFeign（`feign.Retryer`，随 starter 自带，无新依赖）、Micrometer（metrics）、JUnit 5 + Mockito + AssertJ、Testcontainers（集成测试）；复用 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`）、`common-dto`（`PostingRequest`/`PostingResponse`，settlement pom 已依赖）。

**Storage**: 独立 Schema `settlement` 的 MySQL 8.0；既有 `settlement_batches` / `settlement_items` 两表。本 Feature **新增** `settlement_adjustments` 表（含 `uk_settlement_adjustments_idem`），并为 `settlement_batches` 增加少量列（闸门证据：`fact_count`；见 data-model.md §2/§5）。

**Testing**: JUnit 5 + Mockito + AssertJ；关键路径（调整项净额、登记幂等与拒绝、闸门四类拒绝、明细与合计一致、幂等键错配、乐观锁冲突、close 幂等、记账幂等、记账失败不回滚、超时/重试）MUST 有单测与集成测试（Testcontainers MySQL 或既有 H2 配置）；`MUST NOT` 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。

**Target Platform**: JVM / Linux 服务（单机多服务）

**Project Type**: 多模块 Web 服务（Spring Cloud 微服务）；**改造既有** `settlement-service` 模块（不新增服务）

**Performance Goals**: 创建结算批次 P99 ≤ 500ms（2 次同步 RPC + 单次 MySQL 写，同 `settlement-service.md` §1.3 目标）；批次查询 P99 ≤ 300ms；重试最坏情况附加 ≤ 7s（1s+2s+4s），仅发生在故障路径。

**Constraints**: 金额禁 `float/double`（全程 `long` 分）；状态迁移集中且唯一入口；幂等靠 DB 唯一约束；同步 RPC + 幂等重试，禁 MQ/2PC/XA；settlement **永不**修改 reconciliation/payment/refund 原始事实；Settlement ≠ Reconciliation 解耦；**无真实出款**；Database-per-service（不跨服务 SQL）。

**Scale/Scope**: 单节点；单币种（CNY）；不新增服务、不新增中间件、不改跨服务契约（不为 `settlement-summary` 增加 `merchantId`，N1 记为 `[待定]`）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条款 | 本 Feature 合规情况 | 结论 |
|---|---|---|
| §II 资金正确性（金额铁律 / 复式记账） | 调整项、净额、明细全程 `long` 分；禁 `float/double`；记账交由 `ledger-service`（settlement 自身不实现账本），借贷平衡由账本聚合根强校验 | ✅ 通过（记账取舍见 ADR-0023） |
| §III 领域边界（Settlement ≠ Reconciliation；不修改原始事实） | 只消费 `settlement-summary`，零回写路径；记账为出站单向调用，ledger 不反向依赖 | ✅ 通过 |
| §IV 架构（无 MQ、无 2PC、同步 RPC、禁无理由引中间件） | 仅同步 RPC；重试用 OpenFeign 自带 `Retryer`，**不引** Resilience4j/MQ；无跨服务 SQL；不新增服务 | ✅ 通过（ADR-0023） |
| §V 一致性（幂等 / 状态机 / 重试 / 超时） | 三处 DB 唯一约束幂等；状态迁移集中在 `SettlementBatch`；重试仅对幂等 GET 且有退避上限；超时显式配置（N4 修复） | ✅ 通过（修复 §V.6 现有违反） |
| §V.7 未确认结果不落账 | 闸门本地校验 + 模拟执行强制 `UNKNOWN`；记账仅在收敛为 `SUCCEEDED` 后发起 | ✅ 通过（G2/G4 补齐） |
| §VII 可观测（metrics / 审计 / 告警） | 新增 `gate_rejected` / `adjustment_registered` / `adjustment_rejected` / `negative_net` / `closed` / 记账指标；调整与收敛写 `FINANCIAL_AUDIT` | ✅ 通过（补齐） |
| §VIII AI 开发（先理解代码、先 ADR/Plan、不擅改领域模型） | 已立 Spec/Plan/ADR；净额公式与 `compute` 不变量变更、商户维度契约（N1）均记为 `[待定]`，不静默改 | ✅ 通过 |
| Governance §8.2 重大架构变化 | **不新增服务**（仅改造既有服务），不引中间件 | ✅ 通过（无需 §8.2 确认） |
| Governance §8.3 数据库 Schema Migration | **新增关键资金表** `settlement_adjustments` + `settlement_batches` 少量列 | ⚠️ 须负责人确认（ADR-0022） |
| Governance §8.4 API Breaking Change | 仅**新增**端点（`/adjustments`、`/{id}/close`、按商户+周期查询）与**新增**响应字段；不改 reconciliation / ledger 既有契约。**但 FR-012 幂等键错配由「静默返回」改为「报错」属行为变更** | ⚠️ 须负责人确认（ADR-0022 / spec Clarifications 4） |
| Governance §8.8 状态机变更 | `close()` 由「零调用」变为经端点可达；净额不变量（`compute`）按 ADR-0022 调整 | ⚠️ 须负责人确认（ADR-0022） |

**Gate 结论**：无 Constitution 违反；三项 ⚠️ 均落在 ADR-0022~0023 范围内。实现前 MUST 先由负责人确认这 2 条 ADR（尤其 §8.3/§8.8 相关的调整项持久化与净额语义）。

> 附带修复：N4 当前**违反** Constitution §V.6（外部调用 MUST 有超时）；本 Feature 修复后合规。

## Project Structure

### Documentation (this feature)

```text
specs/007-settlement/
├── spec.md                    # 已完成（缺口 G1~G5 + 新发现 N1~N6）
├── plan.md                    # 本文件
├── data-model.md              # 批次/明细/调整项实体、状态机与不变量
├── checklists/
│   └── requirements.md        # 需求质量校验
├── acceptance.md              # 验收清单（实现后勾选）
├── quickstart.md              # 本地验证指南
└── tasks.md                   # 任务清单（阶段化，T-id + [USn]）
```

### Source Code (repository root)

```text
settlement-service/src/main/java/com/payment/settlement/
├── api/
│   ├── SettlementController.java          # 修改：新增 POST /adjustments、POST /batches/{id}/close、GET /batches?merchantId=&period=
│   ├── CreateAdjustmentRequest.java       # 新增（merchantId/period/direction/amountMinor/currencyCode/reason/operator/idempotencyKey）
│   ├── AdjustmentResponse.java            # 新增
│   ├── ResolveSettlementRequest.java      # 修改：新增 operator / reason（N6）
│   ├── CloseSettlementRequest.java        # 新增（可选：operator / reason）
│   └── SettlementBatchResponse.java       # 修改：新增 factCount（闸门证据）
├── application/
│   ├── SettlementApplicationService.java  # 修改：汇总调整项、调用闸门、幂等键一致性校验、closeBatch、记账触发
│   ├── ConfirmedFactGate.java             # 新增：逐条事实校验（纯函数，可单测）
│   ├── LedgerPostingGateway.java          # 新增：出站记账端口（依赖 ADR-0023）
│   └── SettlementFact.java                # 不改（契约无 merchantId，N1 记 [待定]）
├── domain/
│   ├── SettlementBatch.java               # 修改：净额不变量与明细一致性（ADR-0022）；close 语义保持集中
│   ├── SettlementAdjustment.java          # 新增：调整项实体（替代零引用的 Adjustment）
│   ├── AdjustmentDirection.java           # 新增：CREDIT / DEBIT（ADR-0022）
│   ├── SettlementAdjustmentRepository.java# 新增：调整项仓储边界
│   ├── Adjustment.java                    # 处置：删除死代码或重组（ADR-0022，Constitution §VIII.2 须经确认）
│   └── SettlementItem.java                # 不改（ADJUSTMENT 类型语义生效）
├── infra/
│   ├── client/
│   │   ├── FeignReconciliationClient.java # 修改：404 → NOT_FOUND 归一化（N2）
│   │   ├── ReconciliationFeignClient.java # 不改契约；由配置类接管超时/重试
│   │   ├── MerchantFeignClient.java       # 同上
│   │   ├── FeignLedgerPostingGateway.java # 新增：记账 Feign 实现（依赖 ADR-0023）
│   │   └── LedgerFeignClient.java         # 新增：ledger-service 出站契约
│   ├── config/FeignResilienceConfig.java  # 新增：Request.Options / Retryer / ErrorDecoder（N4）
│   └── persistence/
│       ├── SettlementAdjustmentEntity.java  # 新增
│       ├── SettlementAdjustmentMapper.java  # 新增
│       └── MybatisSettlementRepository.java # 修改：调整项仓储实现 + 幂等键一致性校验
└── resources/
    └── application.yml                    # 修改：feign 超时/重试属性、services.ledger.url

deployment/schema/
└── 08-settlement-schema.sql               # 修改：新增 settlement_adjustments 表 + settlement_batches 少量列
```

**Structure Decision**: 沿用既有 `api → application → domain ← infra` 分层，不新增模块/服务；新增文件集中在调整项与闸门两处，其余为**就地修改**，把改动面压到最小（Constitution §VIII.2「一次改动只做一件事」）。`ledger-service` **不被修改**（只被新增调用方），`reconciliation-service` **不被修改**（只做客户端侧错误归一化）。

## Complexity Tracking

> 无 Constitution 违反需要论证。以下记录「为什么不做更复杂的方案」，防止实现期复杂度蔓延。

| 简化取舍 | 为什么 | 被否决的更复杂方案 |
|---|---|---|
| 调整项**独立建表** `settlement_adjustments`，不内嵌 JSON | 调整项需要独立幂等键、独立审计与「建批前登记」门禁；内嵌批次则建批前无处存放 | 在 `settlement_batches` 内嵌 `adjustments_json`（建批前无法登记） |
| 调整项**仅允许建批前**登记，建批后拒绝 | 批次是创建时的事实快照；允许事后修改等于让已生成批次的净额可被追溯篡改 | 支持建批后登记并自动重算批次（需重开状态机、破坏终态语义） |
| 闸门只做**本地可判定**的校验（type/币种/金额/周期），不回查 payment/refund 原始事实 | 回查等于在 settlement 里重复实现对账，违反 Settlement ≠ Reconciliation；且原始事实无商户/周期维度（N1） | 本地全量复核：对每条事实回查 payment/refund 确认状态 |
| 重试用 OpenFeign 自带 `feign.Retryer`，**不引** Resilience4j | starter 已包含 `feign-core`，零新依赖；熔断无真实负载证据，Constitution §IV 基础设施门槛未过 | 引入 Resilience4j（熔断/隔离/限流全套） |
| 记账**仅在收敛为 `SUCCEEDED` 后**发起，净额 ≤ 0 不记账 | 对齐「模拟执行 ≠ 真实出款」；账本要求分录金额 > 0；避免 UNKNOWN/FAILED 批次留下虚假已结事实 | 批次 `READY` 即记账（早于执行，FAILED 后需冲正） |
| 不为 `settlement-summary` 增加 `merchantId`（N1） | 跨服务契约变更（Constitution §8.4），需 payment/refund 事实链路同步改造；Roadmap Phase 7 未授权 | 改造 reconciliation 事实契约，按商户分组返回 |
| 不在 settlement 内实现账本 | Constitution §II.3：`ledger-service` 已实现，记账统一走它；Roadmap Phase 7「不实现 Ledger」 | 在 settlement 自建 `ledger_entries` 镜像表 |
