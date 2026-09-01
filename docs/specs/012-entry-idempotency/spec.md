# Spec: 012-entry-idempotency（下单入口幂等）

**版本**：0.1（收口型）
**日期**：2026-09-02
**状态**：Accepted（实现已存在，本 Spec 为**事后补写的收口产物**）
**输入**：`docs/architecture/next-stage-design.md` §5（F2）

> ⚠️ **收口说明（ADR-0053）**：同 013/014，本 Feature 的代码在 Spec 之前已写入 working tree。
> 更严重的是：代码中（`OrderController` / `OrderEntryIdempotencyService` / `IdempotencyDecision` /
> `deployment/docker-compose.yml`）大量引用 **ADR-0039 / ADR-0040，但这两个 ADR 从未成文**——
> 属**悬空引用**型文档漂移。本 Spec 与 `docs/adr/0014-next-stage-decisions.md` 中补写的 ADR-0039/0040 一同消除该漂移。

---

## 1. 背景与目标

「用户点两次下单」是交易系统最经典的入口问题。难点不在"要不要去重"，而在**分清哪一层该用强保证、哪一层接受弱保证**：

- 客户端手段（按钮置灰 / loading / debounce）只能**减少**重复发生，**保证不了**不发生——刷新、后退、多标签、脚本、网关重试、网络重传全都绕过前端。
- 资金入口（Payment / Refund / Settlement / Ledger）的正确性由 **DB 唯一约束 + 状态机**兜底，这是宪章 V.1 的强制要求。
- **订单**不是资金入口：一次重复下单是"商业意图"层面的问题，用 Redis 去重即可，**崩溃窗口内的重复单是显式接受的代价**。

本 Feature 的目标：**一个 `Idempotency-Key` 只产生一张单，且重复请求返回与首次完全一致的响应**。

**非目标**：

- ❌ 不建 DB 幂等表（ADR-0039 明确否决 L3）。
- ❌ 不修改资金入口的既有幂等机制（不在本 Feature 范围）。
- ❌ 不做"超时接管"（ADR-0040 明确选择不接管 + 轮询）。

---

## 2. 分层防御与裁决

| 层 | 手段 | 防住 | 防不住 |
| --- | --- | --- | --- |
| L0 客户端 | 按钮置灰 / loading / debounce | 手抖双击 | 刷新、后退、多标签、脚本、网关重试、网络重传 |
| L1 **前端幂等键（采用）** | 客户端生成 `Idempotency-Key`，重复提交带同一 key | 同页面重复提交（含刷新 / 多标签） | 换设备、key 过期后重下、用户真想买两件 |
| L2 **服务端 Redis 去重（裁决选定的唯一服务端手段）** | `SETNX` + TTL：`IN_PROGRESS` → 409 轮询；`DONE` → 200 重放 | 真正的并发重复 | Redis 不可用 → **fail-open**（接受重复窗口） |
| ~~L3 服务端 DB 唯一约束~~ | ~~`uk(idempotency_key)`~~ | —— | **裁决不做**（订单非资金入口） |
| L4 业务弱提示 | 同用户 + 同 SKU + 短窗内有待支付单 → 提示 | 体验问题 | 不能当正确性保证 |

---

## 3. 关键用户故事

- **US1 双击只下一单**：同一 `Idempotency-Key` 的两次请求只创建一张订单。
- **US2 重放拿到同一结果**：已完成的 key 重复提交，返回 **200 + 与首次完全一致的响应**（含同一个 `orderId`）。
- **US3 并发请求不双写**：首个请求处理中的并发重复请求返回 **409 + `Retry-After: 1`**，由客户端轮询，**不重复创建**。
- **US4 Redis 挂了不阻断下单**：Redis 不可用时 fail-open 当首次请求处理，仅记指标。
- **US5 无 key 不防重**：未携带 `Idempotency-Key` 的请求行为与引入本 Feature 前完全一致。

---

## 4. 功能需求（FR）

- **FR-001** `Idempotency-Key` MUST 由**客户端生成**，经 **HTTP 请求头** `Idempotency-Key` 传递（**不放在 body**，便于网关统一处理）。
- **FR-002** `OrderEntryIdempotencyService.check(key)` MUST 返回四态决策：

  | 场景 | 判定 | 返回 | 控制器行为 |
  | --- | --- | --- | --- |
  | key 为空 | — | `PROCEED` | 正常建单（不防重） |
  | `SETNX` 成功（首次） | 写入 `IN_PROGRESS`，TTL **30s** | `PROCEED` | 建单 → 201 |
  | 已存在且值为 `DONE:{json}` | 已完成 | `REPLAY(json)` | **200 + 首次响应原文** |
  | 已存在且值为 `IN_PROGRESS` | 并发处理中 | `CONFLICT` | **409 + `Retry-After: 1`** |
  | Redis 抛异常 | — | `UNAVAILABLE` | 当首次处理（**fail-open**）+ 记指标 |

- **FR-003** `complete(key, response)` MUST 在订单创建成功后把结果覆盖写为 `DONE:{responseJson}`，TTL **24h**。
- **FR-004** Redis 键 MUST 为 `idemp:order:{key}`。
- **FR-005** 重放 MUST **原样回放首次响应的 JSON 字符串**，不反序列化重建对象——
  项目未开启 `-parameters` 编译参数，Jackson 反序列化 record 不可靠，直接回放可保证 HTTP body 形状不变。
- **FR-006** Redis 不可用 MUST **fail-open**：`check` 返回 `UNAVAILABLE`（当首次处理）、`complete` 静默失败，
  两者均记指标 `order_idempotency_degraded_total{reason="redis_unavailable"}`，**绝不阻断下单**。
- **FR-007** 日志中的 key MUST **脱敏**：`mask(key)` 仅保留前 4 字符 + `***`，避免订单幂等键泄漏到日志。
- **FR-008** `IN_PROGRESS` MUST 带 **30s TTL**：业务失败且未 `complete` 时自动过期，允许客户端在 TTL 后重试；
  服务端**不做 CAS 接管**（ADR-0040）。

---

## 5. 数据契约

**Redis 键**：`idemp:order:{Idempotency-Key}`

| 值 | TTL | 含义 |
| --- | --- | --- |
| `IN_PROGRESS` | 30s | 首次请求正在处理 |
| `DONE:{CreateOrderResponse JSON}` | 24h | 已完成，可重放 |

**HTTP 契约**（`POST /orders`）：

| 情况 | 状态 | 响应 |
| --- | --- | --- |
| 无 key / 首次 | 201 | `CreateOrderResponse` |
| 已完成重放 | 200 | 首次响应的原始 JSON |
| 并发处理中 | 409 | 空 body + `Retry-After: 1` |

---

## 6. 验收标准（SC）

- **SC-001** 首次：`check` 返回 `PROCEED` 且 Redis 写入 `IN_PROGRESS`（TTL 30s）；`complete` 后变为 `DONE:`（TTL 24h）。
- **SC-002** 无 key：不触碰 Redis，直接 `PROCEED`。
- **SC-003** 并发重复：同 key 且仍 `IN_PROGRESS` → `CONFLICT` → 控制器 409 + `Retry-After: 1`。
- **SC-004** 已完成重复：同 key 且 `DONE:` → `REPLAY` → 控制器 200 且 body 与首次一致。
- **SC-005** Redis 不可用：返回 `UNAVAILABLE`（不抛异常），指标 `order_idempotency_degraded_total` +1。
- **SC-006** `mvn -o clean verify -fae` 全量 BUILD SUCCESS。

---

## 7. 已知限制

| # | 限制 | 影响 |
| --- | --- | --- |
| L1 | **fail-open 的重复窗口**：Redis 不可用时完全不去重，可能重复建单 | 显式接受的代价（ADR-0039）；资金正确性仍由资金入口 DB 约束兜底 |
| L2 | **30s 崩溃窗口**：首个请求崩溃且未 `complete` 时，客户端需等满 30s TTL 才能重试 | 体验代价；TTL 内极小概率重复建单 |
| L3 | **无 L4 弱提示**：「同用户 + 同 SKU + 短窗内有待支付单 → 提示」未实现 | 体验问题，不影响正确性 |
| L4 | **key 由客户端生成，无校验**：未限制长度 / 字符集 / 归属用户 | 恶意超大 key 会占用 Redis 内存；当前无防护 |
| L5 | **`createOrder` 的 `reservationKey` 参数在当前实现中未被使用**：库存预占键恒为 `order:{orderId}:sku:{skuId}`（013 的三处构造必须一致），故客户端幂等键不参与库存键构造 | 该参数为历史预留；2026-09-02 已删除其对应的死变量并补注释说明，签名保留以兼容既有调用与测试 |

---

## 8. 关联 ADR

| 编号 | 标题 | 结论 |
| --- | --- | --- |
| ADR-0039 | 下单幂等键的签发与存储位置 | 客户端生成 + 仅 Redis 防重，**不建 DB 幂等表** |
| ADR-0040 | 并发幂等「超时接管」策略 | **不接管 + 轮询**（409 + Retry-After）；IN_PROGRESS TTL 30s |

均收录于 `docs/adr/0014-next-stage-decisions.md`。
