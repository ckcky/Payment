# Tasks: 012-entry-idempotency

**Feature**：`012-entry-idempotency`
**状态**：全部完成（**收口型 tasks**，2026-09-02 补写；代码先行，任务为事后反向登记）
**关联**：`spec.md` / `plan.md`

| # | 任务 | 验收方式 | 状态 |
| --- | --- | --- | --- |
| T001 | `IdempotencyDecision` 四态决策对象（PROCEED / CONFLICT / REPLAY / UNAVAILABLE） | 编译 + `OrderControllerIdempotencyTest` | ✅ |
| T002 | `OrderEntryIdempotencyService.check`（SETNX + IN_PROGRESS 30s + DONE 24h + fail-open + 指标） | `OrderEntryIdempotencyServiceTest` 五用例 | ✅ |
| T003 | `OrderEntryIdempotencyService.complete`（覆盖写 DONE + 序列化 + 静默失败） | `OrderEntryIdempotencyServiceTest#firstAttemptProceedsAndStoresDone` | ✅ |
| T004 | 日志脱敏 `mask(key)` | 代码评审 | ✅ |
| T005 | `OrderController` 接线：`Idempotency-Key` 头 → 409 + `Retry-After: 1` / 200 重放 / 201 新建 | `OrderControllerIdempotencyTest` 四用例 | ✅ |
| T006 | **补写 ADR-0039 / ADR-0040**（消除代码中的悬空引用） | `docs/adr/0014-next-stage-decisions.md` | ✅ |
| T007 | **补写 012 的 spec / plan / tasks / acceptance** | 本目录四文件 | ✅ |
| T008 | 清理 `OrderApplicationService` 中由幂等键派生的死变量 `stableKey` | 编译 + 全量测试 | ✅ |

## 说明与遗留

- T001~T005 为代码先行落地后反向登记；T006~T008 为 2026-09-02 文档收口动作。
- **未做**：L4 业务弱提示（同用户 + 同 SKU + 短窗内有待支付单 → 提示），属体验优化，非正确性需求。
- **未做**：客户端 key 的长度 / 字符集校验（spec L4）。
- **未验证**：并发真实重复下单在运行时只产生一张单（需 MySQL + Redis 起服务，本环境不可用）。
