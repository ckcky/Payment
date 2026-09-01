# Plan: 012-entry-idempotency

**Feature**：`012-entry-idempotency`
**状态**：已实现（**收口型 plan**，2026-09-02 补写；代码先行）
**关联**：`spec.md` ｜ ADR-0039 / ADR-0040（`docs/adr/0014-next-stage-decisions.md`）

> 本 plan 记录**已落地实现的既有设计**。补写动因：代码中已大量引用 ADR-0039/0040，
> 但这两个 ADR 从未成文，且本 Feature 无 spec 目录——属文档漂移，本次一并收口。

## 1. 技术方案

- `OrderEntryIdempotencyService`（order-service application 层）：
  - `check(key)`：空 → `PROCEED`；`SETNX` 成功 → 写 `IN_PROGRESS`（30s）并 `PROCEED`；
    已存在且以 `DONE:` 开头 → `REPLAY(json)`；否则 `CONFLICT`；Redis 异常 → `UNAVAILABLE` + 指标。
  - `complete(key, response)`：覆盖写 `DONE:{json}`（24h）；Redis 异常静默 + 指标。
  - `mask(key)`：日志脱敏（前 4 字符 + `***`）。
- `IdempotencyDecision`：不可变决策对象，枚举 `PROCEED / CONFLICT / REPLAY / UNAVAILABLE` + `storedJson`。
- `OrderController.createOrder`：`@RequestHeader("Idempotency-Key", required = false)`；
  `CONFLICT` → 409 + `Retry-After: 1`；`REPLAY` → 200 + 原始 JSON；否则建单 → 201 + `complete`。
- 依赖 `StringRedisTemplate`（Redis 非数据源，fail-open）。

## 2. 依赖

- 单机 Redis（与 013 超时 ZSet、014 缓存/秒杀共用同一实例）。
- 前端/调用方需在下单请求中携带客户端生成的 `Idempotency-Key`。

## 3. 测试策略

| 测试 | 覆盖 |
| --- | --- |
| `OrderEntryIdempotencyServiceTest` | `firstAttemptProceedsAndStoresDone` / `noKeyProceedsWithoutRedis` / `concurrentDuplicateConflicts` / `completedDuplicateReplays` / `redisUnavailableFailOpen` |
| `OrderControllerIdempotencyTest` | 无 key → 201 / CONFLICT → 409 + Retry-After / REPLAY → 200 + 存储 JSON / PROCEED → 201 |

## 4. 风险

- Redis 不可用 ⇒ 完全不去重（fail-open），显式接受（ADR-0039）。
- 30s TTL 内若首个请求崩溃且未 `complete`，客户端需等待（ADR-0040）。
- 客户端生成 key 无长度/字符集校验（spec L4）。
