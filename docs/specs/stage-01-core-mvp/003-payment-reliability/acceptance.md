# Acceptance: 支付可靠性（Feature 003）

**Feature**: `003-payment-reliability`（超时 → UNKNOWN、主动查询收敛、有限重试、可靠性指标）
**验收日期**: 2026-08-28
**结论**: **通过（实现完成，测试全绿）** — US1/US2/US3/US5 已实现并验证；US4（人工收敛）按 ADR-0006 **Deferred**。

---

## 1. 验收证据

| 项目 | 结果 |
|---|---|
| 全量构建 | `mvn verify` **BUILD SUCCESS**（13 个模块全部 SUCCESS） |
| payment-service 测试 | **63 tests，0 失败**（本 Feature 新增 18 例） |
| 本 Feature 新增测试 | `TimeoutScanTest`(4) / `ChannelQueryTest`(4) / `PaymentRetryTest`(4) / `ReliabilityMetricsTest`(3) / `TerminalConflictTest`(3) |
| 既有回归 | `PaymentStateMachineTest`(14)、`PaymentCallbackContractTest`(4)、`PaymentPersistenceTest`(3)、`PaymentApplicationTests`(1) 等全部保持通过 |
| 环境说明 | 本沙箱 `./mvnw` 启动器损坏，改用 `mvn.cmd verify`（Maven 3.9.5）执行，结果等价 |

> 手动 e2e（需本地 MySQL）见 `quickstart.md`，由负责人在已起库的环境执行。

---

## 2. FR 逐条核验

| FR | 要求 | 实现位置 | 验证 | 结论 |
|---|---|---|---|---|
| FR-001 | PROCESSING 超阈值 → UNKNOWN，原因记超时 | `reliability/TimeoutScanner` | `TimeoutScanTest` 4 例 | ✅ |
| FR-002 | 超时进 UNKNOWN 不触发成功回写 | 同上（仅 `markUnknown`，不调 `applyAndNotify`） | `TimeoutScanTest` | ✅ |
| FR-003 | UNKNOWN 主动查询渠道并收敛为终态 | `reliability/ChannelQueryService` + `ChannelQueryScheduler` + `channel/QueryStatusRequest` | `ChannelQueryTest` 4 例 | ✅ |
| FR-004 | 收敛只触发一次下游；重复结果幂等吸收 | 复用 `PaymentUnknownResolutionService.resolve`（终态吸收） | `ChannelQueryTest`、`TerminalConflictTest` | ✅ |
| FR-005 | 瞬时错误有限次 + 退避重试，可配置有上限 | `reliability/PaymentRetryService`（`retryMaxAttempts` / `retryBackoff`） | `PaymentRetryTest` | ✅ |
| FR-006 | 硬拒绝不重试，直接进失败 | `ChannelResult.failure` → `errorType=HARD`，`tryHandleRetryable` 返回 null 走既有流程 | `PaymentRetryTest.hardDeclineIsNotRetried` | ✅ |
| FR-007 | 重试耗尽且不确定 → UNKNOWN | `PaymentRetryService`（`markUnknown("RETRY_EXHAUSTED")` + `payment.retry_exhausted`） | `PaymentRetryTest.exhaustedRetriesBecomeUnknown` | ✅ |
| FR-008/009 | 人工收敛（含理由、审计） | — | — | **Deferred（ADR-0006）** |
| FR-010 | 可靠性指标：超时/重试/耗尽/收敛/时长 | `payment.timeout`、`payment.retry`、`payment.retry_exhausted`、`payment.query`、`payment.unknown.duration` | `ReliabilityMetricsTest` 3 例 | ✅ |
| FR-011 | 状态迁移经状态机唯一入口 + 乐观锁 | 未绕过既有状态机；终态吸收 | `TerminalConflictTest` 3 例 | ✅ |
| FR-012 | 沿用同步 RPC + 幂等，不引入 MQ/2PC | 调度器为进程内 `@Scheduled`，无 MQ/2PC 新增依赖 | 代码审查 | ✅ |

---

## 3. Success Criteria 核验

| SC | 判定 |
|---|---|
| SC-001 超时 100% 进 UNKNOWN，订单/交易不推进 | ✅ `TimeoutScanTest` |
| SC-002 收敛后下游只推进一次，重复吸收率 100% | ✅ `ChannelQueryTest` + `TerminalConflictTest` |
| SC-003 瞬时错误按上限退避重试并最终成功；硬拒绝 0 重试；耗尽 100% 进 UNKNOWN | ✅ `PaymentRetryTest` |
| SC-004 人工收敛与审计 | ⛔ **Deferred（ADR-0006，延后 Phase 9）** |
| SC-005 指标与真实收敛时长 | ✅ `ReliabilityMetricsTest`（时长由 `enteredUnknownAt` 计算，ADR-0015） |

---

## 4. 实现期决策（需负责人确认）

实现过程中出现的分歧点已按「最简方式」实现并记录于
`docs/adr/0005-payment-reliability-impl-decisions.md`，状态 **Proposed**：

| ADR | 决策 | 影响面 |
|---|---|---|
| ADR-0012 | UNKNOWN 渠道结果**不重试**，直接进 UNKNOWN 由查询收敛（偏离 data-model 原文） | `ChannelResult.errorType` |
| ADR-0013 | 复用 `retryCount`/`respondedAt`，仅新增 `error_type` + `next_retry_at` 两列 | `payment_attempts` Schema |
| ADR-0014 | 重试在**同一 attempt** 重放，不新建 attempt 行 | 重试历史粒度 |
| ADR-0015 | 新增 `entered_unknown_at` 列度量真实收敛时长（不复用 `updatedAt`） | `payments` Schema |

**Schema 增量（生产库需执行）**：

```sql
ALTER TABLE payments
  ADD COLUMN query_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN entered_unknown_at DATETIME NULL;

ALTER TABLE payment_attempts
  ADD COLUMN error_type VARCHAR(16) NULL,
  ADD COLUMN next_retry_at DATETIME NULL,
  ADD KEY idx_attempts_next_retry_at (next_retry_at);
```

完整 DDL 见 `deployment/schema/03-payment-schema.sql`（已同步更新）。

---

## 5. 已知缺口与移交项

1. **T021 告警面板**：计数器已全部产出，「UNKNOWN 堆积」「重试耗尽」的告警规则与 Grafana 面板移交
   **009 Observability Baseline** 统一建设（Constitution §VII.4）。
2. **US4 人工收敛（FR-008/009）**：按 ADR-0006 延后至 Phase 9（Risk / Security），
   与权限/审计体系一并建设。当前剩余 UNKNOWN 由对账兜底。
3. **手动 e2e 未在本环境执行**：沙箱无 MySQL，quickstart 中的手动场景待负责人在本地验证。
4. **多节点调度锁**：spec Assumptions 已声明单节点部署，多节点分布式调度锁不在本 Feature 范围。

---

## 6. 后续

- Roadmap 已更新：003 标记「已实现」，Next Feature 指 `004-ledger`。
- 未完成内容无「悄悄留在代码中的临时决定」——全部记录于 ADR-0012~0015 与本文件第 5 节（SOP 第 10 步）。
