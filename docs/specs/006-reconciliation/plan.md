# Implementation Plan: Reconciliation 对账（缺口补齐）

**Branch**: `006-reconciliation` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-reconciliation/spec.md`

## Summary

本 Feature 把 `reconciliation-service`（端口 8088，Schema `reconciliation`）从「**能比对、不能收口**」推进到「**按周期可比对、差异可处理、批次可关闭、失败可诊断**」。服务核心链路**已实现**（纯函数匹配、四类差异、周期幂等、乐观锁、结算汇总、只读事实 RPC），因此本计划**不新建服务、不重写匹配语义**，只做三件缺口补齐 + 一项文档收口：

1. **G1 生命周期接线**：应用层调用已定义的 `beginProcessing()` / `close()`，新增关闭端点与「无未处理差异方可关闭」门禁，使 `HAS_DIFFERENCE → PROCESSING → CLOSED` 在代码中可达（ADR-0019）。
2. **G2 账单按周期**：`ChannelStatementLoader.load(period)` 改为按周期定位 fixture + **显式留痕回退**，批次记录实际账单来源，杜绝「任何周期都比对同一份固定账单」（ADR-0020）。
3. **G3 事实读取弹性**：为两个只读 Feign 客户端配置超时（connect 1s / read 3s）、有限重试（3 次 / 1s-2s-4s，仅幂等 GET）与错误归一化 + 失败指标，**不引入** Resilience4j/MQ（ADR-0021）。
4. **可观测与文档收口**：补齐 `FINANCIAL_AUDIT`（差异处理/批次关闭）与差异金额指标、批次响应暴露 `unresolvedDifferenceCount`（N2/N4），并同步修正 `technical-solution.md:105`「骨架」等状态漂移（G4）。

所有设计分歧点已落到 ADR-0019~0021（**Proposed**，待负责人确认）。实现复用既有 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`）与 OpenFeign/MyBatis-Plus 工程底座，不引入新中间件。

## Technical Context

**Language/Version**: Java 21 + Spring Boot 3.5 + Spring Cloud

**Primary Dependencies**: Spring Web / Validation、MyBatis-Plus、Spring Cloud OpenFeign（`feign.Retryer`，随 starter 自带，无新依赖）、Micrometer（metrics）、JUnit 5 + Mockito + AssertJ、Testcontainers（集成测试）；复用 `common-core`（`BizException`/`ErrorCodes`/`BusinessMetrics`/`StructuredAuditLogger`）、`common-dto`。

**Storage**: 独立 Schema `reconciliation` 的 MySQL 8.0；单表 `reconciliation_batches`（匹配/差异以 JSON 内嵌，不拆表）。本 Feature 新增列：`statement_source`、`closed_at`、`closed_by`（见 data-model.md §2/§6）。`Difference` 的 `resolvedAt`/`resolvedBy` 随 JSON 内嵌，**不新增子表**。

**Testing**: JUnit 5 + Mockito + AssertJ；关键路径（周期幂等、差异金额、生命周期闭合、关闭门禁、乐观锁冲突、账单回退留痕、重试耗尽不入批）MUST 有单测与集成测试（Testcontainers MySQL 或既有 H2 配置）；`MUST NOT` 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。

**Target Platform**: JVM / Linux 服务（单机多服务）

**Project Type**: 多模块 Web 服务（Spring Cloud 微服务）；**改造既有** `reconciliation-service` 模块（不新增服务）

**Performance Goals**: 单周期对账 P99 ≤ 1s（本地 CSV + 两次同步 RPC + 一次本地事务，同 `reconciliation-service.md` §1.3 目标）；重试最坏情况附加 ≤ 7s（1s+2s+4s），仅发生在故障路径。

**Constraints**: 金额禁 `float/double`（全程 `long` 分）；状态迁移集中且唯一入口；周期幂等靠 DB 唯一约束；同步 RPC + 幂等重试，禁 MQ/2PC/XA；Reconciliation **永不**修改原始 Payment/Refund 事实；Reconciliation ≠ Settlement 解耦；Database-per-service（不跨服务 SQL）。

**Scale/Scope**: 单节点；账单为本地 fixture（非真实渠道）；不新增服务、不新增中间件、不改跨服务契约（不为 `confirmed-facts` 增加 `period` 参数）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪法条款 | 本 Feature 合规情况 | 结论 |
|---|---|---|
| §II 资金正确性（金额铁律） | 差异金额、`amountMinor` 全程 `long` 分；禁 `float/double`；差异金额用 `Math.abs`（FR-005） | ✅ 通过 |
| §III 领域边界（Reconciliation ≠ Settlement；不修改原始事实） | 只产出匹配/差异与处理标记；零回写路径；`settlement-summary` 只输出匹配事实 | ✅ 通过 |
| §IV 架构（无 MQ、无 2PC、同步 RPC、禁无理由引中间件） | 仅同步 RPC；重试用 OpenFeign 自带 `Retryer`，**不引** Resilience4j/MQ；无跨服务 SQL | ✅ 通过（ADR-0021） |
| §V 一致性（幂等 / 状态机 / 重试 / 超时） | 周期唯一约束幂等；`beginProcessing`/`close` 集中状态机；重试仅对幂等 GET 且有退避上限；超时显式配置（G3 修复） | ✅ 通过（修复 §V.6 现有违反） |
| §VII 可观测（metrics / 审计 / 告警） | 新增 `fact_read_failed` / `statement_fallback` / `difference_resolved` / `batch_closed` / 差异金额；差异处理与关闭写 `FINANCIAL_AUDIT` | ✅ 通过（补齐 N2） |
| §VIII AI 开发（先理解代码、先 ADR/Plan、不擅改领域模型） | 已立 Spec/Plan/ADR；不重写匹配算法；重复 reference（N5）与周期窗口契约（N1）记为 `[待定]`，不静默改 | ✅ 通过 |
| Governance §8.2 重大架构变化 | **不新增服务**（仅改造既有服务），不引中间件 | ✅ 通过（无需 §8.2 确认） |
| Governance §8.3 数据库 Schema Migration | `reconciliation_batches` 新增 3 列（非破坏性 `ALTER ... ADD COLUMN`，含默认值/NULL 允许） | ⚠️ 须负责人确认（ADR-0019/0020 连带） |
| Governance §8.4 API Breaking Change | 仅**新增**端点（`POST .../close`）与**新增**响应字段（`unresolvedDifferenceCount`），向后兼容；不改 `confirmed-facts` 契约 | ⚠️ 新增接口须负责人知悉（ADR-0019） |
| Governance §8.8 状态机变更 | 批次状态机：`beginProcessing`/`close` 语义扩展为幂等、新增关闭门禁（未处理差异 = 0） | ⚠️ 须负责人确认（ADR-0019） |

**Gate 结论**：无 Constitution 违反；三项 ⚠️ 均落在 ADR-0019~0021 范围内。实现前 MUST 先由负责人确认这 3 条 ADR（尤其 §8.3/§8.8 相关的批次生命周期与 schema 扩展）。

> 附带修复：G3 当前**违反** Constitution §V.6（外部调用 MUST 有超时）；本 Feature 修复后合规。

## Project Structure

### Documentation (this feature)

```text
specs/006-reconciliation/
├── spec.md                    # 已完成（缺口 G1~G4 + 新发现 N1~N5）
├── plan.md                    # 本文件
├── data-model.md              # 批次/匹配/差异实体、状态机与不变量（含 G1/G2 相关）
├── checklists/
│   └── requirements.md        # 需求质量校验
├── acceptance.md              # 验收清单（实现后勾选）
├── quickstart.md              # 本地验证指南
└── tasks.md                   # 任务清单（阶段化，T-id + [USn]）
```

### Source Code (repository root)

```text
reconciliation-service/src/main/java/com/payment/reconciliation/
├── api/
│   ├── ReconciliationController.java        # 修改：新增 POST /batches/{id}/close
│   ├── ReconciliationBatchResponse.java     # 修改：新增 unresolvedDifferenceCount / statementSource / closedAt
│   └── CloseBatchRequest.java               # 新增（可选：关闭备注/操作人）
├── application/
│   ├── ReconciliationApplicationService.java # 修改：resolve 触发 beginProcessing；新增 closeBatch
│   └── ChannelStatementLoader.java           # 接口不变（load(period) 语义按 ADR-0020 生效）
├── domain/
│   ├── ReconciliationBatch.java             # 修改：beginProcessing/close 幂等 + 关闭门禁 + statementSource/closedAt/closedBy
│   ├── Difference.java                      # 修改：resolve(note, actor, at) + resolvedAt/resolvedBy + note 非空校验
│   ├── ChannelStatementSource.java          # 新增：账单来源值对象（类型/定位符/条目数/是否回退）
│   └── ReconciliationMatching.java          # 不改（纯函数保持不变；N5 记 [待定]）
├── infra/
│   ├── CsvChannelStatementLoader.java       # 修改：按 period 定位 fixture + 显式回退 + 非法行显式处理
│   ├── client/
│   │   ├── PaymentFactsFeignClient.java     # 不改契约；由配置类接管超时/重试
│   │   └── RefundFactsFeignClient.java      # 同上
│   └── config/FeignFactsResilienceConfig.java # 新增：Retryer / ErrorDecoder / 超时属性绑定
└── resources/
    ├── application.yml                      # 修改：feign 超时 + 账单 fixture 目录配置
    └── fixtures/channel-statements/
        ├── sample.csv                       # 保留（默认回退 fixture）
        └── <period>.csv                     # 新增：按周期 fixture（如 2026-08.csv、2026-09.csv）

deployment/schema/
└── 07-reconciliation-schema.sql             # 修改：新增 statement_source / closed_at / closed_by 列
```

**Structure Decision**: 沿用既有 `api → application → domain ← infra` 分层，不新增模块/服务；新增文件仅 3 个（`ChannelStatementSource`、`FeignFactsResilienceConfig`、周期 fixture），其余为**就地修改**，把改动面压到最小（Constitution §VIII.2「一次改动只做一件事」）。

## Complexity Tracking

> 无 Constitution 违反需要论证。以下记录「为什么不做更复杂的方案」，防止实现期复杂度蔓延。

| 简化取舍 | 为什么 | 被否决的更复杂方案 |
|---|---|---|
| 差异内嵌 JSON，**不拆** `differences` 子表 | 现有持久化已验证；拆表引入跨表一致性成本，收益不匹配当前负载 | 拆 `matches` / `differences` 表并引入外键与事务边界 |
| 重试用 OpenFeign 自带 `feign.Retryer`，**不引** Resilience4j | starter 已包含 `feign-core`，零新依赖；熔断无真实负载证据，Constitution §IV 基础设施门槛未过 | 引入 Resilience4j（熔断/隔离/限流全套） |
| `resolvedAt`/`resolvedBy` 随 JSON 内嵌，**不加**数据库列 | 差异已随批次 JSON 持久化，加列需同步迁移与回填，收益低 | 为 resolved_at/resolved_by 增加独立列 |
| 不为 `confirmed-facts` 增加 `period` 参数 | 跨服务契约变更（Constitution §8.4），且需按支付时间建索引；Roadmap Phase 6「不包含」，列 `[待定]` | 改造 payment/refund 事实端点为按周期过滤 |
| 不处理重复 reference（N5） | 需新增差异类型并改变匹配语义，属领域模型变更（Constitution §VIII.5），须先立方案 | 在 `ReconciliationMatching` 引入 `DUPLICATE` 差异类型 |
| 不引入对账调度器 | 当前由运维/测试显式触发；调度器与多节点选主属独立议题 | 进程内 `@Scheduled` + 分布式锁 |
