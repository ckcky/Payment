# Spec: 014-seckill-and-cache（秒杀与缓存）

**版本**：0.2（收口型）
**日期**：2026-09-02
**状态**：Accepted（实现已存在，本 Spec 为**事后补写的收口产物**）
**输入**：`docs/architecture/next-stage-design.md` §7（F4）

> ⚠️ **收口说明（ADR-0053）**：同 013，源码在 Spec 之前已落地，超前 roadmap 顺序且缺文档，
> 见 **`docs/adr/0015-wip-ahead-of-roadmap.md`（ADR-0053）**。
> 本文件以 2026-09-02 的真实代码为准补写，目的是**让文档等于代码**，不做"应当如此"的宣称。

> 🔴 **最大偏离：Redis 未经论证闸门**。roadmap §7 要求「**压测基线 → 论证引入**」——先有 k6 基线证据再引入 Redis。
> 本实现的 Redis 是**直接引入、未经论证**的（ADR-0044）。验证脚本 `performance/catalog-seckill-k6.js` 已建但**从未实跑**
> （本环境无 Docker/MySQL/Redis，见 011 acceptance §4）。补论证证据列为 TODO，**本次不做**。

---

## 1. 背景与目标

秒杀场景的三个痛点：

1. **读放大**：瞬时大量商品详情查询直接打到 DB。
2. **写热点**：库存行成为 DB 单行写瓶颈，且高争用下乐观锁重试成本陡增（见 013 §2.3）。
3. **瞬时洪峰**：需要在入口削峰，避免把后端压垮。

本 Feature 引入 Redis 作为**加速层**（非数据源），用四件事解决上述三个问题：

| 痛点 | 手段 | 位置 |
| --- | --- | --- |
| 读放大 | SKU 读缓存（cache-aside，TTL 300s，fail-open） | `catalog` `SkuCache` |
| 写热点 | 秒杀配额 Lua 原子预扣（**fail-closed**） | `catalog` `SeckillStockService` + `seckill-deduct.lua` |
| 瞬时洪峰 | 下单入口固定窗口限流（429 快速失败） | `order` `RateLimiter` + `RateLimitInterceptor` |
| 超时占用 | 订单超时 ZSet 时间轮（与 013 共用） | `order` `OrderTimeoutScheduler` |

**非目标**：

- ❌ 不做分布式限流（当前为**单机内存**固定窗口；分布式应改 Redis 令牌桶/Lua）。
- ❌ 不做 Redis 持久化 / 哨兵 / 集群（演示用单机 Redis）。
- ❌ 不引入 MQ 削峰（宪章 IV 排除）。
- ❌ Redis **不作为数据源**——DB / Ledger 才是最终真相源（ADR-0045，唯一例外见 §4.2 fail-closed）。

---

## 2. 关键用户故事

- **US1 读多不走库**：SKU 详情重复查询命中 Redis，跳过 DB；Redis 挂了仍能读到（回源）。
- **US2 秒杀不击穿**：秒杀品在 Redis 层就被判准入，配额耗尽返回 409，**洪峰不打到 DB**。
- **US3 宁可拒也不超卖**：Redis 不可用时秒杀路径**拒绝**（fail-closed），而不是放行去冲 DB。
- **US4 洪峰被削平**：下单入口超过窗口容量直接 429，且**明确告知不可重试**。
- **US5 失败必回补**：预扣成功后若下单/支付失败或超时，配额必须回补，否则永久少卖。
- **US6 普通品不受影响**：未播种配额的 SKU 走 `bypass`，行为与引入秒杀前完全一致。

---

## 3. 功能需求（FR）

### 3.1 SKU 读缓存

- **FR-001** `SkuCache` MUST 以 cache-aside 提供 `getById(Long)` / `getByCode(String)`：命中 Redis 直接反序列化返回；未命中回源仓储并回写（带 TTL）。
- **FR-002** 缓存键 MUST 为 `{key-prefix}id:{id}` 与 `{key-prefix}code:{code}`，前缀由 `catalog.cache.key-prefix` 配置（默认 `sku:`）。
- **FR-003** 缓存值 MUST 为 `SkuCacheView` 的 JSON 快照（含 `id / skuCode / productId / name / priceMinor / currencyCode / deliveryDefinition / status / version`），并**显式标注 `@JsonProperty`**（项目未开启 `-parameters` 编译参数，不标注则反序列化不可靠）。
- **FR-004** 写路径（创建 / 激活 / 暂停）MUST 调用 `evict(id, code)` 失效对应键。
- **FR-005** 缓存 MUST **fail-open**：Redis 不可用或序列化异常时记录 WARN 并回退仓储，**绝不阻断读**；回写失败同样静默。

### 3.2 秒杀配额预扣

- **FR-006** `SeckillStockService.tryPreDeduct(skuId, quantity)` MUST 通过 Lua 脚本**原子**完成「判余量 + 扣减」，返回三态：

  | 返回 | `allowed` | `bypassed` | `remaining` | 触发条件 |
  | --- | --- | --- | --- | --- |
  | `allow` | `true` | `false` | 扣减后剩余（≥ 0） | 配额充足，已 `DECRBY` |
  | `bypass` | `true` | `true` | `-2` | 该 SKU 未播种配额（普通品）或功能关闭 |
  | `deny` | `false` | `false` | `-1` | 配额不足，**或 Redis 不可用（fail-closed）** |

- **FR-007** Lua 脚本 MUST 以 `seckill:sku:{skuId}` 为键，语义为：`EXISTS == 0 → -2`；`cur < qty → -1`；否则 `DECRBY` 返回新值。
- **FR-008** **fail-closed**：Redis 抛异常时 MUST 返回 `deny`（拒绝），并记指标 `catalog_seckill_degraded_total{reason="redis_unavailable"}`。**不得**退化为 bypass（放行即等于绕过库存保护）。
- **FR-009** `rollback(skuId, quantity)` MUST 回补配额（`INCRBY`），用于下单失败 / 超时；Redis 异常时记 WARN（fail-open，回补失败只影响可售量，不破坏正确性）。
- **FR-010** `seed(skuId, total)` MUST 为秒杀 SKU 播种配额（演示/测试用），Redis 异常时记 WARN。
- **FR-011** 端点 MUST 为 `POST /internal/stock/seckill/{seed,deduct,rollback}`（`SeckillStockController`）；`/deduct` 在 `!allowed` 时抛 `CONFLICT`（→ **409**）。

### 3.3 限流

- **FR-012** `RateLimiter` MUST 实现**固定窗口**：按 `bucket` 计数，窗口内超过 `capacity` 返回 `false`。
- **FR-013** `RateLimitInterceptor` MUST 注册在 `POST /orders`（`RateLimitConfig`），超限返回 **429** 与 `{"error":"rate_limit_exceeded","retryable":false}`。
- **FR-014** 限流拒绝 MUST **不返回 `Retry-After`**——语义是「快速失败、不可重试」，避免重试风暴（ADR-0046）。
- **FR-015** 限流 MUST 可通过 `order.ratelimit.enabled=false` 整体关闭。

### 3.4 编排与回补

- **FR-016** 下单 MUST 对**每个明细行**先 `trySeckillDeduct`，`!allowed` 立即抛 `CONFLICT` 并回滚已成功的行（`releaseStock` + `rollbackSeckill`）。
- **FR-017** 订单超时 / 支付失败释放库存时 MUST **同时**回补秒杀配额；**缺了这一行会导致配额永久泄漏（少卖）**。
  > 2026-09-02 修复：`OrderTimeoutScheduler.handleExpired` 原先只调 `releaseStock` 未调 `rollbackSeckill`，
  > 与 `OrderApplicationService.releaseStockForOrder` 行为不一致；已补齐并加断言（见 `OrderTimeoutSchedulerTest`）。
- **FR-018** 超时调度 MUST 使用 Redis ZSet 时间轮（`order:timeouts`，`score = now + ttl`），`@Scheduled(fixedDelay)` 扫描 `score ≤ now` 的到期项。

---

## 4. 降级矩阵（Redis 不可用时会发生什么）

| 能力 | 降级策略 | 理由 |
| --- | --- | --- |
| SKU 读缓存 | **fail-open**（回源 DB） | 缓存只影响性能，不影响正确性 |
| 秒杀配额预扣 | **fail-closed**（`deny` → 409） | 放行等于绕过库存保护，宁可拒单 |
| 配额回补 `rollback` | **fail-open**（记 WARN） | 已无更安全选项；只影响可售量，不产生超卖 |
| 订单超时扫描 | **跳过本轮**（记 WARN） | 不阻断下单；代价是悬挂预占（013 L1） |
| 下单入口幂等（012） | **fail-open**（当首次请求处理） | 不阻断下单；代价是崩溃窗口内可能重复单 |

> 唯一违背「Redis 可全部丢失」原则的是**秒杀预扣的 fail-closed**：
> Redis 挂了秒杀品会**买不到**（可用性下降），但不会**卖超**（正确性保住）。这是有意识的取舍（ADR-0045）。

---

## 5. 配置契约

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `catalog.cache.enabled` | `true` | SKU 读缓存开关 |
| `catalog.cache.ttl-seconds` | `300` | 缓存 TTL |
| `catalog.cache.key-prefix` | `sku:` | 缓存键前缀 |
| `catalog.seckill.enabled` | `true` | 秒杀预扣开关（关闭 ⇒ 所有 SKU 走 bypass） |
| `order.timeout.enabled` | `true` | 超时时间轮开关 |
| `order.timeout.ttl-seconds` | `900` | 下单后未支付多久算超时 |
| `order.timeout.poll-millis` | `5000` | 扫描间隔 |
| `order.timeout.zset-key` | `order:timeouts` | ZSet 键 |
| `order.ratelimit.enabled` | `true` | 限流开关 |
| `order.ratelimit.capacity` | `50` | 每窗口容量 |
| `order.ratelimit.window-millis` | `1000` | 窗口长度 |
| `order.ratelimit.bucket` | `/orders` | 限流桶标识 |

**Redis 键空间**：`sku:id:{id}`、`sku:code:{code}`、`seckill:sku:{skuId}`、`order:timeouts`、`idemp:order:{key}`（012）。

---

## 6. 关键时序

### 6.1 秒杀下单（准入 + 预占 + 回补）

```text
POST /orders（RateLimitInterceptor：容量外 → 429 且不可重试）
  └─ OrderApplicationService.doCreateOrder
       ├─ trySeckillDeduct(skuId, qty)   —— Lua 原子：allow / bypass / deny
       │     deny ⇒ 抛 CONFLICT（409），进回滚分支
       ├─ reserveStock(reservationId, skuId, qty)   —— DB 三段式预占（013）
       ├─ timeoutScheduler.schedule(orderId)        —— 登记 ZSet
       └─ createPayment(...)
  回滚分支：已成功的行 releaseStock + rollbackSeckill，然后 order.cancel()
```

### 6.2 超时回补（修复点）

```text
OrderTimeoutScheduler.processExpired()
  └─ handleExpired(orderId)
       ├─ releaseStock(reservationId, skuId, qty)   // 013：DB 回补
       ├─ rollbackSeckill(skuId, qty)               // 014：Redis 配额回补（2026-09-02 补齐）
       └─ order.cancel() + save
       finally: ZREM（无论成败）
```

---

## 7. 验收标准（SC）

- **SC-001** 缓存：首次未命中回源并回写，二次命中不查 DB；Redis 异常时回源且**不抛异常**。
- **SC-002** 缓存失效：创建 / 激活 / 暂停 SKU 后 `evict` 被调用。
- **SC-003** 秒杀三态：已播种且充足 → `allow(remaining)`；配额不足 → `deny`；未播种 → `bypass`；`enabled=false` → `bypass`。
- **SC-004** fail-closed：Redis 抛异常 → `deny` 且指标 `catalog_seckill_degraded_total` +1。
- **SC-005** 端点：`/internal/stock/seckill/deduct` 在 deny 时返回 **409**。
- **SC-006** 限流：窗口内前 `capacity` 次放行，第 `capacity+1` 次返回 **429** 且 body 含 `"retryable":false`；窗口滑动后重新计数。
- **SC-007** 回补：下单失败与订单超时**两条路径**都会 `rollbackSeckill`。
- **SC-008** `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁）。

---

## 8. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
| --- | --- | --- | --- |
| L1 | **Redis 引入未经 roadmap §7「压测基线 → 论证引入」闸门** | 属于 ADR-0053 记录的 SOP 偏离；"为什么值得引入"目前只有设计推演、无实测证据 | ADR-0044 |
| L2 | **k6 压测从未实跑**：`performance/catalog-seckill-k6.js` 已建，但本机无 Docker/MySQL/Redis | 「库存 100 / 并发 5000 不超卖不漏卖」的断言**未被验证** | 011 acceptance §4 |
| L3 | **限流为单机内存窗口**：多实例部署时各实例独立计数，实际限流 = 容量 × 实例数 | 分布式下非全局限流 | ADR-0045 / ADR-0046 |
| L4 | **限流维度是全局桶**：`bucket` 固定为 `/orders`，未区分用户 / SKU / 商户 | 单一用户可打满全局配额 | `RateLimitProperties.bucket` |
| L5 | **缓存无主动刷新、无防击穿**：TTL 到期瞬间的高并发会同时回源（无 single-flight / 互斥锁） | 缓存击穿风险（TTL 300s 下影响可控） | `SkuCache.readThrough` |
| L6 | **缓存快照含价格与状态**：`SkuCacheView` 含 `priceMinor` / `status`；若未来新增改价接口而**未调用 `evict`**，订单会用到过期价格快照 | 潜在价格一致性风险（当前无改价接口，风险未激活） | `SkuCacheView` / `CatalogApplicationService` |
| L7 | **Redis 与 DB 的最终一致靠回补链路**：预扣成功但 DB 预占失败时必须 `rollback`；任何新增失败分支若漏掉回补即造成少卖 | 配额泄漏（已修一处，见 FR-017） | 本 Spec FR-016/FR-017 |
| L8 | **无 Redis 高可用**：单机 Redis，无哨兵 / 集群 / 持久化 | Redis 宕机期间秒杀品不可售（fail-closed 的代价） | ADR-0045 |

---

## 9. 依赖与前置条件

- 单机 Redis 7（默认 `localhost:6379`，连接超时 1s）。
- MySQL 8（`catalog` / `order` 库）。
- k6（仅压测需要，非构建依赖）。

---

## 10. 关联 ADR

| 编号 | 标题 | 结论 |
| --- | --- | --- |
| ADR-0044 | Redis 引入论证 | Accepted，但**偏离 §7 闸门**，论证证据待补 |
| ADR-0045 | Redis 用途边界与降级 | Redis 非数据源；秒杀预扣取 fail-closed |
| ADR-0046 | 秒杀限流策略与丢弃语义 | 固定窗口 + 429 快速失败 + 拒绝不可重试 |
| ADR-0043 | 订单超时释放机制（ZSet） | 与 013 共用时间轮 |
| ADR-0053 | 超前落地偏离处置 | 保留代码 + 补写文档 |

均收录于 `docs/adr/0014-next-stage-decisions.md` 与 `docs/adr/0015-wip-ahead-of-roadmap.md`。
