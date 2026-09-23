# Spec: 029-redis-transactional-mq（Redis 事务消息通道 + 跨服务异步解耦）

**版本**：1.0
**日期**：2026-09-19
> **Status**: Implemented — 批次 A~G 于 2026-09-20 落地，`--no-ff` 合入 master `5e2c00d` <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->
**分支**：`feature/029-redis-transactional-mq`（已合并并清理）
**决策**：[ADR-0074](../../../adr/0074-redis-transactional-message.md)（🟢 Accepted → Implemented）—— Redis 事务消息通道，Supersedes ADR-0031

> 一句话：**把 12 个「吞异常 + 靠对账兜底」的同步通知，改成 Redis Streams 上的事务消息**；事实类事件由订单域广播，动作类事件点对点；顺带用审计消费组落订单轨迹，实现「按订单号还原全链路」。

## 1. 背景与目标

### 1.1 背景：通知链路正在静默丢事件

跨服务调用共 30 处，其中 12 处是「通知/事件」性质。它们当前的失败处理**完全一致**：捕获异常、打一个指标、然后什么都不做。

更糟的是**补偿覆盖不全**：`AuditScheduler` 每日 00:30 的四核对只核资金分录，履约 / 权益 / 库存**完全不在对账范围内**——这些链路丢事件后没有任何自动补偿，属永久不一致。

### 1.2 现状核实（2026-09-18 核对真实代码）

| # | 事实 | 证据 |
|---|---|---|
| S1 | 12 个通知点全部**下游已幂等** | paymentNo 终态吸收 / `uk_fulfillments_source_payment_item` / reservationId / `refund:{TXRF}:sku:{skuId}` / sourceFulfillmentId / `firstTerminal` 迁移 |
| S2 | `onPaymentSucceeded` 串行调 catalog（confirm 库存）与 fulfillment（驱动履约） | `OrderApplicationService.java:250`、`:258` |
| S3 | 履约驱动失败是 **`catch (RuntimeException ignored)` 纯吞**，无指标无告警 | `OrderApplicationService.java:259-261` |
| S4 | 库存 confirm 失败有指标 `order.stock_confirm_failed`，但**无重投** | `OrderApplicationService.java:323-327` |
| S5 | `onRefundResult` 串行调 catalog（回补秒杀）与 fulfillment（终止履约），两处均吞异常 | `TransactionApplicationService.java:283`、`:288`、`:293-296` |
| S6 | 权益回收经 fulfillment → entitlement 链，失败仅 `fulfillment.entitlement_revoke_failed` 指标 | `FulfillmentApplicationService.java:127-131` |
| S7 | 超时关单**不通知任何外部系统** | `OrderTimeoutScheduler.java:100-108` |
| S8 | 记账类三条（payment→ledger、refund→ledger、settlement→ledger）有 T+1 账证核对兜底 | `CertificateAuditor.java:48`、`LedgerAuditor.java:77` |
| S9 | 只有 catalog / order 有 Redis 客户端；payment 刻意不用（ADR-0044/G7） | 各服务 pom |
| S10 | `redis:7` 单实例，**无 volume、无 appendonly、无 maxmemory** | `docker-compose.yml:54` |
| S11 | 现有 ZSet 时间轮含 `TraceContext.runWithNewTrace` + `BusinessMetrics` + 配置开关，是可复用骨架；但 `ZRANGEBYSCORE` 非原子摘取，多实例不安全 | `OrderTimeoutScheduler.java:50-88` |
| S12 | MDC 只有 traceId，无 orderNo 维度；**无法按订单号还原状态变迁** | `TraceIdFilter.java:29-30` |

### 1.3 目标

1. **消除静默丢事件**：12 条中的 8 条改为事务消息，本地事务成功则消息最终必达；
2. **把收敛时间从 ≥24h 人工压缩到秒级自动重投**；
3. **解除主链路尾延迟**：串行 Feign 通知从主流程摘除；
4. **让订单轨迹可还原**：审计消费组订阅全事件落表，提供 timeline API；
5. **守住既有正确性**：不动记账链路、不动事实源原则、不动幂等口径。

### 1.4 为什么不改那 3 条记账链路

| 理由 | 说明 |
|---|---|
| 已有兜底 | T+1 账证核对（`MISSING_POSTING` 差异 + 挂账）能发现 |
| **顺序敏感** | 借贷平衡审计（`LedgerAuditor.java:44`）对并发敏感，乱序可能打出单边账 |
| 红线 | ADR-0031 / ADR-0074 D2：通道不得承载资金事实真相 |

## 2. 硬性不变量（不可破坏）

- **INV-1（事实不回滚，ADR-0009/0018/0054）**：已发生的资金/状态事实 MUST NOT 因通知失败而回滚。异步化后这条**更强**——通知失败只影响消息是否投递，不影响本地事实。
- **INV-2（消费端幂等，ADR-0031 继承）**：所有消费端 MUST 幂等，重复投递 MUST 被吸收。本 spec 的 8 条下游已具备（S1），改造时不得破坏。
- **INV-3（先事务后可见）**：禁止「先 `XADD` 后做本地事务」。半消息 MUST 在本地事务**之前** prepare，Commit MUST 在事务提交之后。
- **INV-4（事实源，ADR-0066）**：`order_items` 是明细唯一事实源；事件负载 MUST 由生产方从**自己的库**富化，禁止透传上游快照。
- **INV-5（Redis 非数据源，ADR-0045）**：Redis 全丢时系统仍正确，只是变慢 / 需人工重放。任何业务正确性 MUST NOT 依赖 Redis 中的消息存在。
- **INV-6（业务单号，ADR-0063）**：事件信封的关联键一律用 `orderNo` / `paymentNo` / `refundNo`，禁止数值 ID。
- **INV-7（不引新组件）**：不引入 MQ 中间件、不引入 Redisson、不引入分布式锁；并发控制维持 DB 行锁 + 乐观锁。
- **INV-8（幂等键不漂移）**：事件投递不得参与幂等键构造——幂等键仍由各域自己的业务事实决定（沿用 ADR-0073 D5 的纪律）。
- **INV-9（traceId 跨异步边界连续，ADR-0074 D14）**：现状靠 `TraceIdRequestInterceptor` 在 Feign 出站时把 traceId 写进 HTTP 头传播；改消息通道后这条路径消失，**必须显式补偿**——生产写入信封、消费恢复进 MDC、回查沿用原始 traceId、消费后再生产继承。
  > **这是异步化最容易退化的一点**：不做的话，一笔订单的日志会被切成 payment / order / fulfillment / entitlement 四截互不相干的碎片，比现状更差。

## 3. 关键用户故事

### US1 - 支付成功后 downstream 事件不再静默丢失（Priority: P1）

**As** 订单域 Owner，**I want** 支付成功后库存确认与履约驱动以事务消息投递，**so that** 下游临时不可用时消息能被重投，而不是被 `catch ignored` 永久吞掉。

**Independent Test**：停掉 fulfillment-service → 完成一笔支付 → 订单仍为 PAID，Redis 中出现待投递消息与 PEL 记录 → 重启 fulfillment → 30s 内履约单自动创建，无需人工干预。

**Acceptance Scenarios**：
1. **Given** fulfillment 不可用，**When** 支付成功回调到达，**Then** 订单状态正常推进为 PAID，且 `mq:stream:order.paid` 存在对应消息（未 ACK）。
2. **Given** 上述消息，`When` fulfillment 恢复，**Then** 30s 内消费完成并 `XACK`，履约单创建成功，指标 `mq.consumed` +1。
3. **Given** 消费者在处理中崩溃，**When** 超过 `minIdleMs`，**Then** `XAUTOCLAIM` 由同组其他实例接管，消息不丢。

---

### US2 - 事务消息：本地事务未提交则消息不可见（Priority: P1）

**As** 架构维护者，**I want** 半消息机制保证「本地事务失败 → 消息绝不投递」，**so that** 不会出现「订单未支付但库存已扣」这类幽灵事件。

**Independent Test**：注入使 `orderRepository.save()` 抛异常的故障 → 断言 `mq:stream:order.paid` 无新消息，半消息被 rollback 清理。

**Acceptance Scenarios**：
1. **Given** 业务代码调用 `producer.prepare()`，**When** 本地事务尚未提交，**Then** 消息**不出现**在 `mq:stream:*`（只存在于 `mq:half:*`）。
2. **Given** 本地事务回滚，**When** 调用 `rollback()`，**Then** 半消息与回查索引均被清除，无任何投递。
3. **Given** 进程在 commit 前崩溃（模拟 kill -9），**When** 回查扫描器运行，**Then** 依据真相表判定：事务已成功则补投，未成功则丢弃。

---

### US3 - 广播：一个事实多个订阅方（Priority: P1）

**As** 库存与履约服务的维护者，**I want** 各自独立订阅 `order.paid`，**so that** 新增一个关心方不需要改生产方代码。

**Independent Test**：`mq:stream:order.paid` 上存在 3 个消费组（catalog / fulfillment / trace），一笔支付后三组的消费位点各自 +1，互不影响。

**Acceptance Scenarios**：
1. **Given** 一笔支付成功，**When** 查询各消费组位点，**Then** catalog、fulfillment、trace 三组**各自**收到该消息。
2. **Given** trace 组消费失败重试中，**When** 查询 catalog 组，**Then** catalog 组不受影响（组间隔离）。
3. **Given** 新增一个订阅方（仅加消费组），**When** 不改动任何生产方代码，**Then** 该方可收到全量历史消息（从 `0` 开始读）。

---

### US4 - 订单取消要通知出去（Priority: P2）

**As** 支付域维护者，**I want** 订单超时关单时收到 `order.cancelled`，**so that** 后续迟到的支付回调能被明确拒绝，而不是靠 surplus 事后退款。

**Independent Test**：下单不支付 → 等待超时 → 断言 `mq:stream:order.cancelled` 有消息，且 payment 侧消费后该订单的支付单被标记不可受理。

---

### US5 - 按订单号还原全链路（Priority: P2）

**As** 客服 / 排障人员，**I want** `GET /api/orders/{orderNo}/timeline` 返回该订单的全部事件，**so that** 无需 grep 日志即可还原状态变迁。

**Independent Test**：完成一笔「下单 → 支付 → 履约 → 部分退款」的完整链路后，调用 timeline API 返回按时间排序的 4+ 条事件，含 traceId 与生产者。

## 4. 功能需求（FR）

### 4.1 基础设施：`common/common-redis-mq`（FR-100 系列）

| ID | 需求 |
|---|---|
| FR-101 | `EventEnvelope`：msgId、topic、eventType、bizNo（orderNo/paymentNo/refundNo）、traceId、producer、occurredAt、payload(JSON)。序列化用 Jackson，落 Stream 为 field-value 对 |
| FR-102 | `TransactionalProducer.prepare(topic, envelope)` → 写 `mq:half:{topic}:{msgId}` + `ZADD mq:half:idx` |
| FR-103 | `TransactionalProducer.commit(msgId)` → `XADD mq:stream:{topic}` + 清半消息与索引 |
| FR-104 | `TransactionalProducer.rollback(msgId)` → 只清半消息与索引，不投递 |
| FR-105 | `TransactionChecker` SPI：`LocalTxState check(EventEnvelope)` → `COMMIT` / `ROLLBACK` / `UNKNOWN` |
| FR-106 | `HalfMessageScanner`：`@Scheduled(fixedDelay=5000)` 扫 `score < now - prepareTimeoutMs(默认 30s)`，调 checker，UNKNOWN 累计超 `maxCheckTimes(默认 10)` 进 DLQ + 告警 |
| FR-107 | `StreamConsumer`：启动建组（`XGROUP CREATE ... MKSTREAM`）、`XREADGROUP` 循环（`blockMs=2000`）、成功后 `XACK` |
| FR-108 | 消费失败：退避 1s/2s/4s 重试，超 `maxRetry(默认 3)` → `XADD mq:dlq:{topic}` + `XACK` + 指标 `mq.dead_letter` |
| FR-109 | `XAUTOCLAIM` 接管：每轮先认领 `minIdleMs(默认 60s)` 以上的 PEL 消息 |
| FR-110 | `MqProperties`：topic / group / blockMs / batchSize / maxRetry / prepareTimeoutMs / maxCheckTimes / enabled |
| FR-111 | 全部扫描与消费逻辑 MUST 用 `TraceContext.runWithNewTrace` 包裹，保证 traceId 可串 |
| FR-112 | 依赖仅 `spring-boot-starter-data-redis` + `StringRedisTemplate`，禁止 Redisson |

### 4.2 事件与拓扑（FR-200 系列）

| ID | 事件 | 生产者 | 订阅方 | 模式 | 替代的原调用 |
|---|---|---|---|---|---|
| FR-201 | `payment.succeeded` | payment | order | 点对点 | `PaymentApplicationService:197`、`PaymentResultProcessor:105` |
| FR-202 | `order.paid` | order | catalog、fulfillment、trace | 广播 | `OrderApplicationService:320`、`:258` |
| FR-203 | `refund.result` | payment | order | 点对点 | `RefundResultProcessor:142` |
| FR-204 | `refund.succeeded` | order | catalog、fulfillment、trace | 广播 | `TransactionApplicationService:310`、`:288` |
| FR-205 | `order.cancelled` | order | catalog、payment、fulfillment、trace | 广播 | `OrderTimeoutScheduler:100-108`（新增） |
| FR-206 | `fulfillment.completed` | fulfillment | entitlement | 点对点 | `FulfillmentApplicationService:86` |
| FR-207 | `fulfillment.revoked` | fulfillment | entitlement | 点对点 | `FulfillmentApplicationService:124` |

**负载口径（FR-210）**：事件 MUST 携带订阅方所需的全部业务字段，由生产方从自己的库富化（INV-4）。例如 `order.paid` 携带 `orderNo`、`paymentNo`、明细行（`orderItemNo` / `skuId` / `quantity`），使 catalog 与 fulfillment 无需反查 order。

### 4.3 各服务改造（FR-300 系列）

| ID | 服务 | 需求 |
|---|---|---|
| FR-301 | payment | 新增 data-redis 依赖（ADR-0044/G7 的显式例外，见 ADR-0074 D11）；生产 `payment.succeeded`、`refund.result`；消费 `order.cancelled` |
| FR-302 | order | 生产 `order.paid`、`refund.succeeded`、`order.cancelled`；消费 `payment.succeeded`、`refund.result`；实现三类回查 checker |
| FR-303 | catalog | 消费 `order.paid`（confirm 库存）、`refund.succeeded`（秒杀回补）、`order.cancelled`（释放预占 + 回补） |
| FR-304 | fulfillment | 新增 data-redis；消费 `order.paid`（建履约单）、`refund.succeeded`（终止履约）、`order.cancelled`（撤单）；生产 `fulfillment.completed` / `fulfillment.revoked` |
| FR-305 | entitlement | 新增 data-redis；消费 `fulfillment.completed`（授予权益）、`fulfillment.revoked`（回收权益） |
| FR-306 | 全部生产方 | 原同步 Feign 调用点改为 `prepare` + 事务 + `commit/rollback`；**保留 `mq.enabled=false` 时回落同步调用**的开关，便于灰度与回滚 |

### 4.4 订单轨迹（FR-400 系列）

| ID | 需求 |
|---|---|
| FR-401 | 新增表 `order_event_log`（order schema）：`id` / `order_no` / `event_type` / `topic` / `msg_id` / `trace_id` / `producer` / `payload_json` / `occurred_at` / `created_at`；索引 `(order_no, occurred_at)` |
| FR-402 | `trace` 消费组订阅**全部** topic，落表；消费失败进 DLQ 不影响业务组 |
| FR-403 | API `GET /api/orders/{orderNo}/timeline` → 按时间升序返回事件列表 |
| FR-404 | 轨迹为**只读投影**：业务正确性 MUST NOT 依赖它（INV-5） |

### 4.5 容灾与可观测（FR-500 系列）

| ID | 需求 |
|---|---|
| FR-501 | `docker-compose.yml` 给 redis 补 `command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy noeviction` + 数据卷挂载 |
| FR-502 | 指标：`mq.prepared` / `mq.committed` / `mq.rolled_back` / `mq.checked` / `mq.consumed` / `mq.retried` / `mq.dead_letter` / `mq.half_backlog`（按 topic 打标签） |
| FR-503 | `StructuredAuditLogger` 记录每次投递与消费结果，含 msgId 与 bizNo |
| FR-504 | Grafana 新增「消息通道」面板：半消息积压、消费延迟、DLQ 堆积、各组位点 |

### 4.6 链路追踪跨异步边界（FR-600 系列，ADR-0074 D14）

| ID | 需求 |
|---|---|
| FR-601 | 生产端 `prepare()` MUST 从 `TraceContext` 取当前 traceId 写入 `EventEnvelope.traceId`（无则新建） |
| FR-602 | 消费端处理前 MUST `MDC.put("traceId", envelope.traceId())`，`finally` 清理；消费逻辑内部的全部日志自动带上该 traceId |
| FR-603 | `HalfMessageScanner` 回查补投 MUST **沿用信封中的原始 traceId**，禁止 `runWithNewTrace` 新建（否则补投这条线索断裂） |
| FR-604 | 消费端若再生产新事件（如 fulfillment 消费 `order.paid` 后发 `fulfillment.completed`），MUST **继承**当前 traceId，视为同一业务链路延续 |
| FR-605 | MDC 除 traceId 外 MUST 同时注入 `bizNo`（orderNo / paymentNo / refundNo），补齐现状「MDC 无 orderNo 维度」的缺口（`TraceIdFilter.java:29-30`） |
| FR-606 | 日志格式（logback pattern）补 `%X{bizNo}`，与 traceId 并列输出 |
| FR-607 | `trace-grep.sh` 等既有排障脚本支持按 bizNo 检索（与按 traceId 检索并列） |

## 5. 演示规划（本轮只写进文档，不实现）

| # | 场景 | 操作 | 预期 |
|---|---|---|---|
| D1 | **事务消息：回滚不投递** | 注入使订单落库失败的故障 | 半消息被 rollback，`mq:stream:order.paid` 无新消息 |
| D2 | **事务消息：崩溃后回查补投** | 在 prepare 与 commit 之间 kill -9 生产者进程 | 5s 内回查扫描发现半消息 → 依据 `orders` 表判定 → 补投 |
| D3 | **下游宕机自愈** | 停 fulfillment → 完成支付 → 30s 后重启 | 订单 PAID 不受影响；fulfillment 恢复后自动消费，履约单创建 |
| D4 | **广播隔离** | 一笔支付后查三组位点 | catalog / fulfillment / trace 各自 +1；trace 组故障不影响另两组 |
| D5 | **订单轨迹** | 跑完「下单→支付→履约→退款」后调 timeline API | 返回完整时序事件，含 traceId |
| D6 | **死信与告警** | 注入消费端永久失败 | 重试 3 次后进 DLQ，`mq.dead_letter` 指标 +1，Grafana 可见 |

## 6. 验收标准（SC）

| ID | 标准 | 判定方式 |
|---|---|---|
| SC-1 | 8 条链路全部改为事务消息投递，同步 Feign 通知点清零 | 代码检查 + `mq.enabled=true` 全链路跑通 |
| SC-2 | 本地事务失败时消息**零投递** | D1 场景断言 Stream 无新消息 |
| SC-3 | 生产者崩溃后，已提交事务的消息在 60s 内被回查补投 | D2 场景 |
| SC-4 | 下游宕机 5 分钟后恢复，消息自动消费完成，无需人工 | D3 场景 |
| SC-5 | 每个广播事件的每个订阅方**独立位点、互不影响** | D4 场景 |
| SC-6 | 重复投递（手工 `XADD` 同一 msgId 两次）被消费端幂等吸收，不产生第二笔业务副作用 | 幂等回归 |
| SC-7 | `GET /api/orders/{orderNo}/timeline` 返回完整事件时序 | D5 场景 |
| SC-8 | 记账三条链路**保持同步**，未接入通道 | 代码检查 |
| SC-9 | 全部既有测试通过（≥595 用例）+ 新增通道测试 ≥30 用例 | `mvn verify` |
| SC-10 | Redis 清空（`FLUSHALL`）后，业务数据正确、仅消息丢失且告警可见 | 容灾演练 |

## 7. 已知限制（诚实标注）

| # | 限制 | 影响 | 兜底 |
|---|---|---|---|
| L1 | **Redis 单实例无副本**，SPOF | Redis 故障时通道整体不可用 | 回落 `mq.enabled=false` 同步模式；业务事实不受影响（INV-5） |
| L2 | AOF 未落盘窗口内崩溃仍可能丢已投递消息 | 极端情况下消息丢失 | T+1 对账 + 人工重放 |
| L3 | **仅 at-least-once**，非 exactly-once | 重复投递 | 消费端幂等（S1 已具备） |
| L4 | 广播组之间**无序** | 订阅方处理顺序不确定 | 各订阅方按自身不变量处理，不依赖跨组顺序 |
| L5 | 不做延迟消息 | 超时关单仍走 ZSet 时间轮 | ADR-0043 既定方案 |
| L6 | 半消息回查依赖真相表可查 | 若事件对应的业务事实无表可查则无法回查 | 仅对有真相表的 6 类事件启用事务消息 |
| L7 | 消息无回溯管理界面 | 排障靠 redis-cli | 一期不做控制台 |

## 8. 不做（Out of Scope）

- **不引入** RocketMQ / Kafka / RabbitMQ / Pulsar 等任何消息中间件（ADR-0074 D1）；
- **不改造**记账三条链路（payment→ledger、refund→ledger、settlement→ledger），维持同步；
- **不做**延迟消息 / 定时消息（超时关单沿用 ZSet 时间轮）；
- **不引入** Redisson、分布式锁、Seata / TCC / XA；
- **不做**消息管理控制台、消息回溯 UI、可视化追踪界面；
- **不做**跨服务的顺序保证与 Saga 协调器（仍由各域自身幂等 + 对账收敛）；
- **不改**现有幂等键构造规则、不动 `order_items` 事实源地位。

## 9. 已拍板口径

| # | 口径 | 来源 |
|---|---|---|
| P1 | 用 Redis 模拟 MQ，不引入 RocketMQ 等组件 | 负责人 2026-09-18 |
| P2 | 按「RocketMQ 事务消息 + 容灾冗余」的水准设计 | 负责人 2026-09-19 |
| P3 | 改造范围 = 8 条通知链路；记账 3 条维持同步 | 负责人 2026-09-18 |
| P4 | 拓扑 = 混合：`payment.succeeded` 单消费者；`order.paid` / `refund.succeeded` / `order.cancelled` 广播；`fulfillment.completed` 点对点 | 负责人 2026-09-19 |
| P5 | 广播点画在 `order.paid`（ADR-0066 事实源），不由 payment 直接广播 | 负责人 2026-09-19 |
| P6 | 订单轨迹纳入本轮 | 负责人 2026-09-19 |
| P7 | 本轮只写文档，代码下一轮 | 负责人 2026-09-18 |

## 10. 文档与决策影响

**新增**
- `docs/adr/0074-redis-transactional-message.md`（ADR-0074，Supersedes ADR-0031）
- 本 spec 四件套

**必须同步（防漂移）——本轮文档期完成**

| 文件 | 改动 |
|---|---|
| `docs/adr/README.md` | 索引表 + 编号速查表登记 0074；补 B1 缺失表头行；登记「文件名前缀 = 首个 ADR 编号」新规则 |
| `docs/adr/0074-redis-transactional-message.md` | 新建（ADR-0074） |
| `docs/adr/traceability.md` | 登记落点 |
| `docs/adr/0029-distributed-evolution-decisions.md` | ADR-0031 标记 **Superseded by ADR-0074** |
| **`docs/architecture/technical-solution.md`** | **总体技术方案必须同步**：`:53` 不引入 MQ 的例外清单追加 Redis 通道、`:193` 异步事件现状、架构总览补消息通道、§4 各链路时序改为事件驱动 |
| **`docs/architecture/systems/order-service.md`** | 生产 `order.paid` / `refund.succeeded` / `order.cancelled`；消费 `payment.succeeded` / `refund.result` |
| **`docs/architecture/systems/payment-service.md`** | 生产 `payment.succeeded` / `refund.result`；消费 `order.cancelled` |
| **`docs/architecture/systems/fulfillment-service.md`** | 消费 `order.paid` / `refund.succeeded` / `order.cancelled`；生产 `fulfillment.completed` / `fulfillment.revoked` |
| **`docs/architecture/systems/catalog-service.md`** | 消费 `order.paid` / `refund.succeeded` / `order.cancelled` |
| **`docs/architecture/systems/entitlement-service.md`** | 消费 `fulfillment.completed` / `fulfillment.revoked` |
| `.specify/memory/constitution.md:156` | 同步 RPC + 幂等重试 → 增补 Redis 事务消息通道 |
| `CHANGELOG.md` / `roadmap.md` | 登记 spec 029 与 ADR-0074 |

> **架构文档必须与代码同批更新**：总体技术方案与各系统设计文档描述的是"系统长什么样"，异步化改变了服务间的耦合形态，若只改代码不改文档，这些文档即刻变成错误描述——这是本项目反复强调的防漂移底线。

**实现期随代码完成**（非 docs-only，不能走直推白名单）

- `deployment/architecture-tests/.../ServiceBoundaryTest.java:121-131`：注释说明 Redis 通道定位与豁免依据（禁用清单不变）
- 各服务 systems 文档的最终核对（实现后按真实代码校一遍）
