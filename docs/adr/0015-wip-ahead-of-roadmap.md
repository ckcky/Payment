<a id="adr-0053"></a>
# ADR-0053：库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置

- **状态**：Accepted（2026-08-31，提交负责人复盘；若否决则回退 013/014 代码）
  → **🟡 收口：主决策已执行，1 条待办未闭环**（2026-09-03 复核）
- **日期**：2026-08-31（提出）｜2026-09-03（待办复核）
- **关联**：`docs/architecture/next-stage-design.md` §1-7（011~014 规划）；ADR-0041~0046（**已写入**，见 `0005`…`0014`）；ADR-0025（验签空实现）；ADR-0031（不使用 MQ）；roadmap「Next Feature」

## 背景（Context）

2026-08-31 在准备提交 Feature 011（demo-showcase）时，发现 working tree 除 011 工作外，还包含**实质性、可编译、测试通过**的库存与秒杀实现，对应 `next-stage-design.md` 中的 Feature **013-inventory-reservation** 与 **014-seckill-and-cache**：

- `catalog-service`：`Stock` 聚合、`StockReservation`、三段式预占/确认/释放（`StockApplicationService`）、`StockController`、Redis 缓存、`seckill` 包、`seckill-deduct.lua`。
- `order-service`：`OrderTimeoutScheduler`（Redis ZSet 时间轮）、`SeckillResult`、`ReserveStockCommand`/`ReleaseStockCommand`/`ConfirmStockCommand`、`ratelimit`、`idempotency`、`web`。
- `order-service` 的 `OrderApplicationService` 同时包含 011 收银台跳转调用与 013 库存预占调用（**两期代码纠缠在同一文件，无法干净拆分提交**）。

该代码与 roadmap / Spec Kit SOP 存在三处偏离：

1. **顺序超前**：roadmap 规划顺序为 011 → 012 → 013 → 014，013/014 出现在 011 验收之前。
2. **缺 spec 驱动产物**：无 `docs/specs/013-*` / `014-*`，无 plan/tasks/acceptance，ADR-0041~0046（库存域归属 / 扣减时机 / 超时释放机制 / Redis 引入论证 / Redis 用途边界 / 秒杀限流）**从未写入**。
3. **Redis 引入越过闸门**：014 直接引入 `StringRedisTemplate` + Lua 脚本，但 roadmap §7 明确要求「压测基线 → 论证引入」的论证闸门（ADR 待写）；宪章禁 MQ（ADR-0031）背景下 Redis 作为新基础设施更应先论证。

## 决策（Decision）

**保留代码，不静默删除；以本 ADR 显式标记偏离，spec/ADR 补写列为 TODO，待负责人复盘后走 Spec Kit 流程收口。**

理由（遵循负责人既定工作准则「遇争议点先按最简单实现、写入 ADR 让我决策、中间不用停」）：

- 代码已编译通过（`mvn -o clean verify -fae` 16 reactor 条目全绿，含 `architecture-tests` 边界门禁），删除是破坏性动作且与「向前推进」相悖；
- 代码与 011 在 `order-service` 纠缠，强行拆分提交会导致 master 中间态不可编译，违反「每次提交可构建」；
- 不假装 specs 已存在——本 ADR 即偏离的书面留痕，符合「安全策略可审计 / 不把临时决定留代码」的 SOP 要求。

## 后果（Consequences）

- **正向**：工作成果保留，全量构建仍绿；偏离被显式记录，可审计、可复盘。
- **负向 / 风险**：
  - 013/014 当前是「无 spec 的 WIP」，正确性仅靠已实现单测覆盖，尚未经过完整 acceptance 与压测断言（roadmap §7 的「库存 100 / 并发 5000 不超卖」断言未自动化验证）。
  - 014 的 Redis 引入未论证，违反 roadmap 闸门；若 Redis 不可用，相关能力降级（代码已有 try/catch 降级日志，但语义正确性需评估）。
  - 文档漂移风险：roadmap / runbook 的「已实现 Feature」「JVM 进程数」需同步，否则出现文档与代码不一致。
## 待办闭环情况（2026-09-03 复核）

> 本 ADR 的 4 条待办逐条核对结果如下。**3 条已闭环，1 条仍开放**，
> 故本 ADR 状态为「主决策已执行 / 待办未全闭环」，**不标为完全完成**。

| # | 原待办 | 状态 | 证据 |
|---|---|---|---|
| 1 | 负责人复盘本 ADR，确认「保留并补 spec」或「回退 013/014」 | ✅ 已闭环 | 后续开发按「保留并补 spec」推进，013/014 至今在 master 且全量构建绿 |
| 2 | 补 `docs/specs/013-*` / `014-*`（spec→plan→tasks→acceptance），落 ADR-0041~0046 | ✅ 已闭环 | `docs/specs/013-inventory-reservation/`、`docs/specs/014-seckill-and-cache/` 均含 spec / plan / tasks / acceptance 四件；ADR-0041~0046 已写入 `docs/adr/0014-next-stage-decisions.md` |
| 3 | 为 014 的 Redis 引入补「压测基线 → 论证」证据（roadmap §7 闸门） | ✅ 已闭环 | 2026-09-02 实跑压测，证据已归档至 ADR-0044「压测基线证据」节；产物 `deployment/performance/results/2026-09-02-catalog-*`。注意：**k6 未能使用**（二进制下载被代理拦截），实际以 Node 标准库负载生成器等价复刻 |
| 4 | 补 013/014 的端到端 / 压测自动化断言（不超卖、不漏卖、无重复单、限流生效） | 🟡 **部分闭环** | 见下 |

**待办 #4 的明细**：

- ✅ **已覆盖**：秒杀三态（bypass/deny/allow）、fail-closed 降级、缓存 miss/hit/异常回源、
  限流窗口（RateLimiter 层）、超时回补配额、幂等键并发 —— 均有单元测试
  （`SeckillStockServiceTest`、`SkuCacheTest`、`RateLimiterTest`、`OrderEntryIdempotencyServiceTest`、
  `OrderTimeoutSchedulerTest`）。
- ❌ **仍缺失**：
  1. **「不超卖」并发断言 —— 单元测试级已验证，大规模未自动化** —— R3 新增 `SeckillStockConcurrencyTest`（配额 10 / 50 并发各扣 1 ⇒ 恰好 10 `allowed` + 40 `deny`、剩余恒 0，Lua 原子不超卖），**已获证据**；
     但 roadmap §7 的
     「库存 100 / 并发 5000 不超卖」**大规模断言仍未自动化**（需分布式压测，见 ADR-0058 / `R3-distributed-verification.md`）。
  2. **多线程并发测试 —— 已有（R3）**，但 DB 乐观锁并发断言仍缺 —— R3 新增 `SeckillStockConcurrencyTest`、
     `OrderEntryIdempotencyConcurrencyTest`、`RateLimiterConcurrencyTest` 均为真实多线程并发（embedded Redis）；
     `013/acceptance.md` SC-008 标注「并发防覆盖 ⚠️ 未直接验证」现仅指 DB 层：MyBatis-Plus 乐观锁依赖仍**未经本项目测试证明**。
  3. **端到端未验证** —— 013/014 的 acceptance 均注明「端到端运行时未验证（环境受限）」。

> 📌 **由本条派生的文档待办**：`docs/specs/014-seckill-and-cache/acceptance.md` §1 仍写
> 「k6 压测 —— 本机不可用」，§3 仍列压测为未验证项；现已存在实测数据，需按新证据更新
> 该文件的验收方式与未验证项清单。已登记为 `docs/operations/code-debt-backlog.md` #12，
> 在 Phase 5 阶段 ⑤ 一并处理（避免在 ADR 收口这一 commit 里跨太多文件）。

## 与既有 ADR 的关系

- 不 supersede 任何 ADR；是**一次 SOP 偏离的处置记录**。
- ADR-0041~0046 号段已按预留用途写入 `docs/adr/0014-next-stage-decisions.md`，本 ADR 未占用其编号。
- 与 ADR-0025（验签空实现）、ADR-0031（不使用 MQ）无冲突——013/014 未引入 MQ。
- **本 ADR 记录的三处 SOP 偏离**（顺序超前 / 缺 spec 驱动产物 / Redis 越过闸门）**均已消解**；
  剩余的是**测试覆盖缺口**（待办 #4），不再属 SOP 偏离范畴。

> 本文件作为「偏离 / 处置日志」文档，后续若再有 SOP 偏离类决策可在此追加（ADR-0054+）。
