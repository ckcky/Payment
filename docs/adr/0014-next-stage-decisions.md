# 下一阶段决策集合（ADR-0038~0046）

> 本文件收录 Roadmap `next-stage-design.md` §9 预留号段 **ADR-0038~0046** 的九项正式决策，覆盖三个 Feature：
>
> | Feature | 编号 |
> | --- | --- |
> | `011-demo-showcase` | ADR-0038（已被 ADR-0048 取代） |
> | `012-entry-idempotency` | ADR-0039、ADR-0040 |
> | `013-inventory-reservation` | ADR-0041、ADR-0042、ADR-0043 |
> | `014-seckill-and-cache` | ADR-0044、ADR-0045、ADR-0046 |
>
> ⚠️ **收口说明**：这些决策对应的**代码在 spec/ADR 之前已落地**（超前 roadmap 顺序 011→012→013→014），
> 属 **ADR-0053** 记录的 SOP 偏离（见 `docs/adr/0015-wip-ahead-of-roadmap.md`）。
> 本文件将已实现的决策固化，使代码与决策重新对齐。
>
> 📌 **2026-09-02 补记**：ADR-0039/0040 此前**只有代码引用、无 ADR 文档**——
> `OrderController`、`OrderEntryIdempotencyService`、`IdempotencyDecision`、`deployment/docker-compose.yml`
> 的注释均写着「ADR-0039/0040」，但这两个编号从未落地成文，属**悬空引用（文档漂移）**。本次补写消除该漂移。

---

<a id="adr-0038"></a>
## ADR-0038 — 演示形态：Mock 收银台 vs 纯脚本（011）

- **状态**：**Superseded by ADR-0048**
- **日期**：2026-08-31
- **决策（原）**：接受新增 `mock-channel-web` 收银台组件，而非纯脚本演示。
- **为何被取代**：该内容在 2026-08-31 负责人推翻 v0.1 后，以**修订版 ADR-0048** 的形式正式落
  （见 `docs/adr/0012-demo-showcase-decisions.md`），编号 0038 空出。本条仅保留编号占用记录，**不得挪作他用**。
- **后果**：011 的演示形态以 ADR-0048 修订版为准（含 `payUrl` 跳转链路与 8091 端口）。

---

<a id="adr-0039"></a>
## ADR-0039 — 下单幂等键的签发与存储位置（012）

- **状态**：Accepted（代码已实现；ADR 文档 2026-09-02 补写）
- **日期**：2026-08-31（决策）/ 2026-09-02（成文）
- **决策**：
  - **签发**：`Idempotency-Key` 由**客户端生成**（如 `crypto.randomUUID`），经 **HTTP 头**传递（`Idempotency-Key`），**不塞 body**（便于网关统一处理，对齐 Stripe 等工业实践）。
  - **存储**：服务端**仅用 Redis 防重**（`idemp:order:{key}`），**不建 DB 幂等表**。
  - **状态编码**：首次 `SETNX` 成功 → 写入 `IN_PROGRESS`（TTL **30s**）；业务完成 → 覆盖写 `DONE:{responseJson}`（TTL **24h**）。
- **备选**：② 服务端签发 key（多一次往返，且无法覆盖网络重传）；③ L3 DB 唯一约束 `uk(idempotency_key)`。
- **理由**：
  - 前端手段（按钮置灰 / loading / debounce）只能**减少**重复发生，不能**保证**不发生（刷新、后退、多标签、网关重试都绕过前端）。
  - 订单**不是资金入口**：资金正确性已由 Payment / Refund / Settlement / Ledger 的 DB 唯一约束 + 状态机兜底（宪章 V.1 强制），订单层的重复属**商业意图**层面，接受 Redis 的弱保证。
  - 宪章 IV 禁止无理由新增复杂度；不建幂等表即不引入新的表与 migrate。
- **后果**：
  - 正向：实现简单、无 DB 依赖、不新增 schema。
  - 负向：Redis 不可用 → **fail-open**（当首次请求处理，记 `order_idempotency_degraded_total{reason="redis_unavailable"}`），崩溃窗口内**可能重复建单**——这是**显式接受**的代价。
  - 响应可重放：重放时**原样回放首次响应的 JSON 字符串**（不反序列化重建对象——项目未开启 `-parameters` 编译参数，Jackson 反序列化 record 不可靠）。

---

<a id="adr-0040"></a>
## ADR-0040 — 并发幂等「超时接管」策略（012）

- **状态**：Accepted（代码已实现；ADR 文档 2026-09-02 补写）
- **日期**：2026-08-31（决策）/ 2026-09-02（成文）
- **决策**：**不接管 + 轮询**。同 key 的请求在首个请求仍 `IN_PROGRESS` 时，返回 **409 + `Retry-After: 1`**，由客户端轮询；服务端**不做 CAS 接管**。
- **放弃策略**：`IN_PROGRESS` 带 **30s TTL**；业务失败且未调用 `complete` 时 key 自动过期，客户端可在 TTL 后重试。
- **备选**：CAS 接管（`UPDATE ... WHERE key=? AND status='IN_PROGRESS' AND expire_at < now()`，靠影响行数抢锁）+ 双重检查。
- **理由**：原请求可能**只是慢、并未死**，接管会导致双写；「不接管」语义最清晰、生产更稳。
- **后果**：
  - 正向：不会出现两个请求同时建单；语义可被客户端明确理解（409 = 稍后再问）。
  - 负向：首个请求若**真的崩溃**且未 `complete`，客户端需等满 30s TTL 才能重试（体验代价，可接受）；过期窗口内极小概率重复建单（已在 ADR-0039 中显式接受）。
  - 已完成同 key 的请求返回 **200 + 首次响应原文**（`REPLAY`），不重复创建。

---

<a id="adr-0041"></a>
## ADR-0041 — 库存域归属（013）

- **状态**：Accepted
- **日期**：2026-08-31（收口）
- **决策**：库存聚合（`Stock` / `StockReservation`）放在 **catalog-service** 内（推荐方案①），**不独立成 `inventory-service`**。
- **备选**：② 独立 `inventory-service`；③ order 内。
- **理由**：库存是 SKU 的销售属性，与商品同属一个 Bounded Context；宪章 IV 禁止「无理由新增微服务」，过早拆分是复杂度炫技。
- **后果**：catalog 承担库存职责；跨服务（order）经 HTTP/RPC 调用 catalog 的库存接口预占/释放。

---

<a id="adr-0042"></a>
## ADR-0042 — 库存扣减时机（013）

- **状态**：Accepted
- **日期**：2026-08-31（收口）
- **决策**：**下单预占（reserve）→ 支付成功才确认扣减（confirm）→ 失败/超时释放（release）** 三段式。
  - 不变量 `total = available + reserved + sold` 由领域层 `Stock.assertInvariant()` 强制（乐观锁 `version` 防并发覆盖）。
  - 支付前不占用 `sold`；超时/失败可回补 `available`。
- **后果**：不超卖、不漏卖；确认/释放均幂等（`deductId` / `reservationId`）。退款导致的库存回补不在本期。

---

<a id="adr-0043"></a>
## ADR-0043 — 订单超时释放库存的机制（013）

- **状态**：Accepted（实现 = **Redis ZSet 时间轮**）
- **日期**：2026-08-31（收口）
- **决策**：用 **Redis ZSet 时间轮**实现订单超时取消——`score = 到期时间戳(ms)`、`member = orderId`；`OrderTimeoutScheduler.processExpired()` 定期扫描到期项 → 取消订单 + 释放预占库存；扫描时 Redis 不可用仅记日志跳过本轮（降级，不阻断下单）。
- **备选**：DB 扫描（payment-service 已有 `TimeoutScanner` 先例）。
- **理由**：解耦超时扫描与 DB；与 014 共享 Redis 依赖；宪章排除 MQ 延迟消息，ZSet 时间轮是无 MQ 下的合理替代。
- **后果**：
  - 正向：超时逻辑与 DB 解耦，扩展性好。
  - 负向：引入 Redis 依赖；**Redis 不可用时悬挂预占需人工/对账兜底**；**多实例重复扫描未处理**（单实例 demo 足够，分布式需去重，见 ADR-0045）。

---

<a id="adr-0044"></a>
## ADR-0044 — Redis 引入论证（014）

- **状态**：Accepted（**✅ 偏离已消除**，2026-09-03 补齐压测证据）
- **日期**：2026-08-31（收口）｜2026-09-03（补齐压测证据，偏离消除）
- **决策**：引入 Redis（`StringRedisTemplate` + Lua）用于缓存 / 秒杀预扣 / 超时窗口 / 限流。
- **~~⚠️ 偏离说明~~（2026-09-03 更新）**：roadmap §7 要求「**压测基线 → Redis 论证引入**」的论证闸门（先有基线证据再引入）。本实现在**未做论证**的情况下直接引入 Redis，属 ADR-0053 记录的 SOP 偏离。该待办已于 2026-09-02 实跑压测、2026-09-03 归档证据后**消除**。
- **后果**：获得缓存/预扣/窗口加速；新增 Redis 运维依赖；秒杀路径 fail-closed 保护库存。

### 压测基线证据（2026-09-02 实跑，补 roadmap §7 闸门）

> **环境**：本机 MySQL 8.0.46（`localhost:3306/catalog`）+ Redis `6379`（Windows 版、单线程）+ 单 SKU（id=1）；
> catalog-service 以项目**默认配置**运行（`catalog.cache.enabled` 开启）。
> **工具说明**：k6 二进制下载被代理拦截，**未能使用 k6**；改用 Node 标准库自研负载生成器
> `deployment/performance/catalog-seckill-loadgen.js`（零外部依赖，复刻 k6 脚本的两套场景与 SLO）。
> 报告生成：`deployment/performance/generate-report.js`。

| 场景 | 总请求 | 吞吐 | p95 | p99 | 错误率 | SLO 判定 |
|---|---|---|---|---|---|---|
| `sku_cache_read`（ramping 0→50→200→0，2min，`GET /skus/{id}`） | 33,627 | 549.62 RPS | **13.20 ms** | **16.83 ms** | 0.00% | ✅ 达标（p95<50ms、p99<120ms） |
| `seckill_flash`（ramping 0→500→0，40s，`POST /internal/stock/seckill/deduct`） | 44,502 | 1,108.20 RPS | **415.70 ms** | **434.27 ms** | 0.00% | ❌ 延迟未达标 |

**DB 卸载佐证（缓存有效性的决定性证据）**：
`HikariCP connections_acquire_seconds_count = 5`，而本场景成功读请求 = 33,627
⇒ **99.98% 的读命中 Redis 缓存**（`sku:id:1`），未回源 DB。cache-aside 按设计工作。

**两点必读的结论纠正**（避免重复历史误判，详见 `deployment/performance/README.md`）：

1. **「Redis 连不上」是误报** —— `/actuator/health` 报 `redis=DOWN` / `Cannot read Redis info`
   是 Spring Boot Actuator 的已知误报（false-negative）。**判据应是业务指标**（缓存命中数、降级计数），
   不是 health 端点。本次实测中 Redis 全程可达且 Lua 预扣可写。
2. **「读缓存未生效」是误判** —— 早期结论来自跑在 `CATALOG_CACHE_ENABLED=false` 的 **H2 实例**上。
   改用默认配置（MySQL + 缓存开启 + Redis 可达）重跑后，DB 卸载 99.98%。

**已知未验证项**：秒杀配额（`seckill:sku:1`）播种为 1,000,000，压测全程 200 准入、
**未触发 409 售罄**，故「配额耗尽快速拒绝、不击穿 DB」的能力**本轮未验证**（建议后续播种小配额重跑）。

**产物**：`deployment/performance/results/2026-09-02-catalog-load-result.json`（原始数据）、
`deployment/performance/results/2026-09-02-catalog-perf-report.html`（可视化报告）。


---

<a id="adr-0045"></a>
## ADR-0045 — Redis 用途边界（014）

- **状态**：Accepted
- **日期**：2026-08-31（收口）
- **决策**：Redis **仅用于 缓存 / 去重窗口（超时 ZSet）/ 库存预扣计数（Lua）/ 限流（规划）**；**不作为数据源**——DB / Ledger 为最终真相源，Redis 可全部丢失，系统仍正确（只是变慢），**唯独秒杀预扣路径取 fail-closed**（Redis 不可用 → 拒绝而非绕过，保护库存优先）。
- **与规划的偏差**：
  - 限流当前为**单机内存固定窗口**（`RateLimiter`），**非 Redis**；roadmap 规划「限流用 Redis」在分布式部署下才改令牌桶/Lua。
  - 多实例下 ZSet 时间轮重复扫描未处理（见 ADR-0043）。
- **后果**：用途边界受控；非数据源原则保留，降低 Redis 强依赖风险。

---

<a id="adr-0046"></a>
## ADR-0046 — 秒杀限流策略与丢弃语义（014）

- **状态**：Accepted
- **日期**：2026-08-31（收口）
- **决策**：**固定窗口限流**，超限**快速失败（429）且拒绝不可重试**——不返回 `Retry-After`，调用方应 fail-fast 而非重试（避免重试风暴放大压力）。
- **可观测 / 可关闭**：限流开关经配置属性可关闭；命中应可观测。
- **后果**：削峰生效；误杀需可观测与可关闭（属性开关）；单机窗口在分布式下非全局限流（见 ADR-0045）。

---

## 编号与状态总览

| 编号 | 标题 | 状态 | 关联 Feature |
| --- | --- | --- | --- |
| ADR-0038 | 演示形态：Mock 收银台 vs 纯脚本 | **Superseded by ADR-0048** | 011 |
| ADR-0039 | 下单幂等键的签发与存储位置 | Accepted（客户端生成 + 仅 Redis，不建幂等表） | 012 |
| ADR-0040 | 并发幂等「超时接管」策略 | Accepted（**不接管 + 轮询**，409 + Retry-After） | 012 |
| ADR-0041 | 库存域归属 | Accepted | 013 |
| ADR-0042 | 库存扣减时机（三段式） | Accepted | 013 |
| ADR-0043 | 订单超时释放机制（Redis ZSet） | Accepted | 013 |
| ADR-0044 | Redis 引入论证 | Accepted（**偏离 §7 闸门**，论证待补） | 014 |
| ADR-0045 | Redis 用途边界 | Accepted | 014 |
| ADR-0046 | 秒杀限流策略与丢弃语义 | Accepted | 014 |

> 0038~0046 号段已全部落文，无空号。
> ADR-0039/0040 于 2026-09-02 补写，用于消除代码中已存在但文档缺失的**悬空引用**
> （`OrderController` / `OrderEntryIdempotencyService` / `IdempotencyDecision` / `docker-compose.yml`）。
