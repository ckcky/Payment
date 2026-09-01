# Acceptance: 012-entry-idempotency

**Feature**：`012-entry-idempotency`
**验收日期**：2026-09-02
**验收结论**：**通过（单元层）｜ 并发运行时未验证（环境受限）**
**关联**：`spec.md` / `plan.md` / `tasks.md`

---

## 1. 验收方式

| 层 | 方式 | 环境要求 |
| --- | --- | --- |
| 单元 / MockMvc | `mvn -o clean verify -fae`（Mockito + MockMvc） | 无外部依赖 |
| 并发运行时（roadmap §10 口径："并发发两个同 key 请求 ⇒ 只产生一张单，两次响应完全相同"） | 需 MySQL + Redis + order/catalog/payment 起服务 | **本机不可用** |

## 2. 已验证项（✅ 有证据）

| SC | 内容 | 结果 | 证据 |
| --- | --- | --- | --- |
| SC-001 | 首次 `check` → `PROCEED`，写 `IN_PROGRESS`（30s）；`complete` 后为 `DONE:`（24h） | ✅ | `OrderEntryIdempotencyServiceTest#firstAttemptProceedsAndStoresDone` |
| SC-002 | 无 key 不触碰 Redis，直接 `PROCEED` | ✅ | `#noKeyProceedsWithoutRedis` |
| SC-003 | 并发重复（仍 `IN_PROGRESS`）→ `CONFLICT` → 控制器 **409 + `Retry-After: 1`** | ✅ | `#concurrentDuplicateConflicts` + `OrderControllerIdempotencyTest#conflict_returns409WithRetryAfter` |
| SC-004 | 已完成重复 → `REPLAY` → 控制器 **200 且 body 与首次一致** | ✅ | `#completedDuplicateReplays` + `OrderControllerIdempotencyTest#replay_returns200WithStoredJson` |
| SC-005 | Redis 不可用 → `UNAVAILABLE`（不抛异常）+ `order_idempotency_degraded_total` +1 | ✅ | `#redisUnavailableFailOpen` |
| SC-006 | `mvn -o clean verify -fae` 全量 BUILD SUCCESS | ✅ | 见 §5 |

## 3. 未验证项（❌ 无证据）

| 项 | 阻塞原因 |
| --- | --- |
| 真实并发：两个同 key 请求在运行时只产生一张订单 | 需 MySQL + Redis + 多服务启动，**本机不可用**（见 011 acceptance §4） |
| 30s TTL 过期后重试的最终一致性行为 | 同上 |

## 4. 本轮补文档消除的漂移

| # | 漂移 | 处置 |
| --- | --- | --- |
| D1 | 代码注释引用 **ADR-0039/0040，但两编号从未成文**（`OrderController`、`OrderEntryIdempotencyService`、`IdempotencyDecision`、`docker-compose.yml`） | **已补写**于 `docs/adr/0014-next-stage-decisions.md` |
| D2 | 本 Feature 有实现、无 spec/plan/tasks/acceptance | **已补写**本目录四文件 |
| D3 | `OrderApplicationService` 中 `stableKey` 死变量（暗示库存键随客户端 key 变化，与 013 三处构造公式矛盾） | **已删除**并补注释 |

## 5. 构建门禁

```bash
'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o clean verify -fae
```

**结论**：BUILD SUCCESS（16 个 reactor 条目，`architecture-tests` 边界门禁通过）。

## 6. 遗留 TODO

1. 在有 MySQL + Redis 的机器上验证并发同 key 只产生一张单（§3）。
2. 考虑为客户端 key 增加长度 / 字符集校验（spec L4）。
3. 可选：实现 L4 业务弱提示（同用户 + 同 SKU + 短窗内有待支付单 → 提示）。
