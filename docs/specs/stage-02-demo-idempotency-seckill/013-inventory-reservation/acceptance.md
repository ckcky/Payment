# Acceptance: 013-inventory-reservation

**Feature**：`013-inventory-reservation`
**验收日期**：2026-09-02
**验收结论**：**通过（单元/聚合层）｜ 端到端运行时未验证（环境受限）**
**关联**：`spec.md` / `plan.md` / `tasks.md`

---

## 1. 验收方式

| 层 | 方式 | 环境要求 |
| --- | --- | --- |
| 领域 / 应用 / 调度 | `mvn -o clean verify -fae`（Mockito + H2/内存 fake） | 无外部依赖 |
| 端到端（下单→支付→履约→库存终态） | 需 MySQL + Redis + 9 服务同时启动 | **本机不可用** |

## 2. 已验证项（✅ 有证据）

| SC | 内容 | 结果 | 证据 |
| --- | --- | --- | --- |
| SC-001 | 领域不变量 `total = available + reserved + sold`，违反即抛 `STATE_TRANSITION_VIOLATION` | ✅ | `StockAggregateTest` |
| SC-002 | `reserve` 余量不足抛 `CONFLICT`（409）且数值不变 | ✅ | `StockAggregateTest` / `StockApplicationServiceTest` |
| SC-003 | 三段式各自幂等（重复 reserve / confirm / release 只生效一次） | ✅ | `StockApplicationServiceTest` |
| SC-004 | 已 `CONFIRMED` 的预占不可被重新预占、不被 `release` 回滚 | ✅ | `StockApplicationServiceTest` |
| SC-005 | 下单→预占、支付成功→确认、超时→释放 的状态迁移 | ✅ 部分（下单+支付成功由 `SuccessfulPurchaseScenarioTest` 覆盖；超时路径由 `OrderTimeoutSchedulerTest` 覆盖，非同进程连贯） | 见上 |
| SC-006 | 超时扫描：到期取消 + 释放 + **回补秒杀配额** + `finally ZREM`；终态订单跳过 | ✅ | `OrderTimeoutSchedulerTest` |
| SC-007 | `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁） | ✅ | 见 §5 |
| SC-008 | 并发防覆盖：基于同一 version 快照的后提交者影响行数 0 → `CONFLICT`，不重复扣减 | ⚠️ **未直接验证**（乐观锁机制为 MyBatis-Plus 内建，本项目无并发测试用例） | —— |

## 3. 未验证项（❌ 无证据，不宣称通过）

| 项 | 阻塞原因 |
| --- | --- |
| 端到端：真实下单后 `stock` 表 `available/reserved/sold` 三列按预期变化 | 本机无 MySQL / Redis，**服务无法启动**（见 011 acceptance §4） |
| 超时链路真实触发（ZSet 到期 → 取消 → 库存回补 → 秒杀配额回补） | 同上 |
| 高并发下不超卖 / 不漏卖 | 需 k6 压测（014 L2），脚本已建未实跑 |

## 4. 本轮补文档过程中发现并修复的缺陷

| # | 缺陷 | 影响 | 处置 |
| --- | --- | --- | --- |
| D1 | `OrderTimeoutScheduler.handleExpired` 只调 `releaseStock`，**未调 `rollbackSeckill`** | 秒杀品订单超时后 Redis 配额永久泄漏（少卖） | **已修复**（T011）+ 加断言 |
| D2 | `OrderApplicationService` 中 `stableKey` 变量**计算后从未使用**（死代码，且暗示库存键会随客户端幂等键变化，与另两处构造公式矛盾） | 误导 + 无效代码 | **已删除**并补注释说明库存键契约（T012） |
| D3 | 文档宣称「DB 条件更新 `WHERE available >= ?` 为第一道防线」，实现实为乐观锁 | 文档与代码不符（漂移） | **已修正** `spec.md` §2.3，并显式标注与草案的差异 |

## 5. 构建门禁

```bash
'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o clean verify -fae
```

**结论**：BUILD SUCCESS（16 个 reactor 条目，`architecture-tests` 边界门禁通过）。

## 6. 遗留 TODO（不阻塞验收，需决策）

1. 是否把并发控制从乐观锁改回草案的 `UPDATE ... WHERE available >= ?` 原子条件更新（spec §2.3 注）。
2. 补 `StockController` 的独立单元测试（tasks.md 说明）。
3. 实现第 3 道防线：`sold + reserved` 与订单 / 支付事实的定期核对（spec L3）。
4. 在有 MySQL + Redis 的机器上跑端到端验收（§3）。
