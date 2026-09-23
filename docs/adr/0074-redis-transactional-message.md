<a id="adr-0074"></a>

# ADR-0074: Redis 事务消息通道——用 Streams 承载跨服务异步解耦（spec 029 立项）

- 状态：🟢 **Accepted → Implemented**（2026-09-19 提出并拍板；2026-09-20 随 spec 029 批次 A~G 落地，`--no-ff` 合入 master `5e2c00d`）
- 关联：
  - **Supersedes [ADR-0031](0029-distributed-evolution-decisions.md)**（⛔ Not Implemented「不使用 MQ」——本 ADR 以「Redis 不是 MQ 组件」的方式满足其解耦证据要求，并把其三条约束原样继承）
  - [ADR-0043](0038-next-stage-decisions.md)（订单超时用 **Redis ZSet 时间轮**顶替 MQ 延迟消息——本 ADR 的先例与可复用骨架）
  - [ADR-0044](0038-next-stage-decisions.md) / [ADR-0045](0038-next-stage-decisions.md)（Redis 引入与「**Redis 非数据源**」定位；本 ADR 对 payment 用 Redis 构成显式例外）
  - [ADR-0060](0060-redis-lettuce-pool.md)（Lettuce 池化，本 ADR 的消费者连接方案受其约束）
  - [ADR-0066](0066-schema-normalization-and-item-granular-fulfillment.md)（`order_items` 为明细单一事实源——决定广播点画在哪里）
  - [ADR-0054](0054-order-payment-orchestration.md)（支付成功事实不回滚、surplus 判定在 transaction 层）
  - [ADR-0063](0063-cross-service-reference-by-business-no.md)（跨系统一律业务单号，事件信封沿用）
  - spec 029
- 需求源头：负责人 2026-09-18「异步状态解耦我希望用 redis 来模拟 MQ，不要使用 rocketMQ 这种组件，我这个个人项目不想搞太多的组件」；2026-09-19 追加「就当我们这个 MQ 是 RocketMQ 的那种事务消息，并且做了容灾冗余的那种」。

## 背景

### 一、现状：12 个通知点是「吞异常 + 靠对账兜底」

| # | 事实 | 证据 |
|---|---|---|
| G1 | 全库 17 个 `@FeignClient` / 29 方法 / 30 个调用点；其中 **12 个是「通知/事件」性质**（事实已发生、不强依赖返回值），10 个是同步查询、3 个是必须取返回值的命令 | 全量 Feign 盘点 |
| G2 | 这 12 条的**下游全部已幂等**（paymentNo 终态吸收、`(sourcePaymentNo, orderItemId)` 唯一键、reservationId、`refund:{TXRF}:sku:{skuId}`、sourceFulfillmentId、`firstTerminal` 迁移） | 各下游入口 |
| G3 | 通知失败一律 `catch (RuntimeException ignored)` + 打指标，**不回滚已发生的事实** | `OrderApplicationService.java:259-261`（驱动履约，纯吞）、`PaymentResultProcessor.java:106-124` |
| G4 | **对账只核资金分录**：`AuditScheduler` 每日 00:30 的四核对不覆盖履约 / 权益 / 库存 → B4/B10/B11 丢了**没有任何自动补偿**，属永久不一致，只能人工重放 | `CertificateAuditor.java:48`、`LedgerAuditor.java:77` |
| G5 | 订单侧是**所有事件的唯一入口 + 转发者**：`onPaymentSucceeded` 串行调 catalog（confirm 库存）与 fulfillment（驱动履约）；`onRefundResult` 串行调 catalog（回补）与 fulfillment（终止履约） | `OrderApplicationService.java:250/258`、`TransactionApplicationService.java:283/288` |
| G6 | 超时关单**不通知任何外部系统**：`handleExpired` 内部串行 release + 回补 + cancel，payment 侧只能靠 surplus 兜底 | `OrderTimeoutScheduler.java:100-108` |

### 二、为什么不能用「真 MQ」

| # | 事实 | 证据 |
|---|---|---|
| G7 | ADR-0031 裁决「**不使用 MQ**」（⛔ Not Implemented），并要求引入前必须有**性能 / 解耦 / 削峰**三类证据之一；即使引入也 MUST 遵守：只用于通知与解耦、不得承载资金事实真相、消费端 MUST 幂等、不引 2PC/XA | `0029-distributed-evolution-decisions.md:71` |
| G8 | Constitution `:156`「跨服务通过同步 RPC 编排和幂等重试实现最终一致，**暂不引入 MQ 或跨服务异步事件**」；`:145` 禁止为体现复杂度引入中间件 | `.specify/memory/constitution.md` |
| G9 | `ServiceBoundaryTest:121-131` 禁 `kafka / amqp / rabbitmq / rocketmq / jms / jta`——**Redis 不在禁列** | `deployment/architecture-tests` |
| G10 | 负责人明确：**个人项目不想搞太多组件** | 2026-09-18 需求源头 |

### 三、Redis 侧的家底

| # | 事实 | 证据 |
|---|---|---|
| G11 | **ADR-0043 已用 Redis ZSet 时间轮顶替 MQ 延迟消息**，原文认可「ZSet 时间轮是无 MQ 下的合理替代」——**本项目已有「Redis 顶替 MQ 能力」的 accepted 先例** | `0038-next-stage-decisions.md:100-102` |
| G12 | 只有 catalog、order 两个服务有 `spring-boot-starter-data-redis`；**payment 刻意不用**（ADR-0044/G7） | 各服务 pom |
| G13 | `redis:7` 单实例，**无 volume、无 appendonly、无 maxmemory**——容器重建全丢 | `docker-compose.yml:54` |
| G14 | Lettuce 池 `max-active:16`（ADR-0060）；客户端只有 `StringRedisTemplate`，无 Redisson | 各服务 application.yml |
| G15 | 现有 ZSet 时间轮**多实例不安全**（`ZRANGEBYSCORE` 非原子摘取），是反面教材；但含 `TraceContext.runWithNewTrace` + `BusinessMetrics` + 配置开关，是可复用的消费者骨架 | `OrderTimeoutScheduler.java:50-88` |

## 决策

**D1. 用 Redis Streams 作为传输层，在其上实现「事务消息」语义，不引入任何 MQ 组件。**

半消息 → 本地事务 → Commit/Rollback → 超时回查本地事务状态，完整对齐 RocketMQ 事务消息的四段式。Redis 7 具备 `XGROUP / XREADGROUP / XACK / XPENDING / XAUTOCLAIM`（6.2+），多实例消费与崩溃接管是原生能力，不需要自建。

> **为什么不是 List**：`LMOVE` 是抢占式的，一条消息只能被一个消费者取走，**天生做不了广播**；而本项目有 3 个事件是多订阅的（见 D6）。
> **为什么不是 Pub/Sub**：无持久化、无确认、无重投，消费者离线即丢，连 at-least-once 都做不到。

**D2. 通道只用于「通知与解耦」，永不承载资金事实的唯一真相。**

原样继承 ADR-0031 的三条约束：不得作为资金事实真相源、消费端 MUST 幂等、不引 2PC/XA。据此，**记账类三条链路（payment→ledger、refund→ledger、settlement→ledger）维持同步不动**——它们已有 T+1 账证核对兜底，且借贷平衡审计对顺序敏感，异步化的收益小于引入乱序的风险。

**D3. 半消息协议（事务消息核心）**

| 用途 | 结构 | Key |
|---|---|---|
| 半消息（消费者不可见） | String JSON | `mq:half:{topic}:{msgId}` |
| 回查索引 | ZSet（score = prepare 时间戳） | `mq:half:idx` |
| 可见队列 | Stream | `mq:stream:{topic}` |
| 消费组 | Consumer Group | `g:{subscriber}` |
| 死信 | Stream | `mq:dlq:{topic}` |

流程：① **Prepare**（本地事务**前**）写半消息 + `ZADD` 索引 → ② 执行本地事务 → ③ 成功则 **Commit**（`XADD` 进可见队列，再清半消息）/ 失败则 **Rollback**（只清半消息，不投递）→ ④ `HalfMessageScanner` 每 5s 扫 `score < now-30s` 的半消息，调业务方 `TransactionChecker` 回查本地事务状态后补 Commit 或 Rollback。

**D4. 回查的判定依据 = 各域自己的真相表**（等价 RocketMQ 的 `checkLocalTransaction`）

| 事件 | 判定依据 |
|---|---|
| `payment.succeeded` | `payments` 该 paymentNo 是否 `SUCCEEDED` |
| `order.paid` | `orders` 该 orderNo 是否 `PAID` |
| `refund.result` | `refunds` 该 PMRF 是否终态 |
| `refund.succeeded` | `transaction_refunds` 该 TXRF 是否 `SUCCEEDED` |
| `order.cancelled` | `orders` 是否 `CANCELLED` / `CLOSED` |
| `fulfillment.completed` | `fulfillments` 是否 `DELIVERED` |

**D5. 投递语义 = at-least-once，重复由消费端幂等吸收。**

Commit 的两步（`XADD` + 清半消息）非原子，崩溃后回查可能重复投递一次；这是**刻意接受**的代价——8 条下游全部已幂等（G2），用幂等换掉分布式事务是本项目一贯取向（ADR-0009/0018）。**不承诺 exactly-once。**

**D6. 拓扑 = 混合：事实类事件广播，动作类事件点对点。**

| 事件 | 生产者 | 订阅方 | 模式 |
|---|---|---|---|
| `payment.succeeded` | payment | order | 点对点 |
| `order.paid` | order | catalog（confirm 库存）、fulfillment（建履约单）、trace | **广播** |
| `refund.result` | payment | order | 点对点 |
| `refund.succeeded` | order | catalog（回补秒杀）、fulfillment（终止履约）、trace | **广播** |
| `order.cancelled` | order | catalog（释放+回补）、payment（拒收后续支付）、fulfillment（撤单）、trace | **广播** |
| `fulfillment.completed` / `fulfillment.revoked` | fulfillment | entitlement | 点对点 |

同一 Stream 上**多个消费者组各自独立位点**即广播（每组各收一份），**组内多实例**即分摊——两种模式由同一套 Stream 原语覆盖。

**D7. 广播点画在 `order.paid`，不画在 `payment.succeeded`。**

ADR-0066 规定 `order_items` 是明细单一事实源（代码注释「绝不信任上游 `request.items()` 的快照」），且「正常到账 vs surplus」判定在 order 的 transaction 层（ADR-0054）。两项职责只能在 order 完成，故 order 消费 `payment.succeeded` 后**以订单域的名义**发布 `order.paid`，catalog / fulfillment 自行订阅。

> 若让 fulfillment 直接订阅 `payment.succeeded`，它拿不到权威 `orderItemNo`，只能反查 order——那等于把异步解耦又退化为同步依赖。

**D8. 消费者用「短阻塞循环」（`blockMs=2000`）而非长阻塞。**

Lettuce 共享池仅 16 连接（G14），`XREADGROUP BLOCK 0` 会常驻独占连接。`blockMs=2000` + 循环既避免独占，也不会忙等（每秒最多一次空读）。

**D9. 消费失败 → 有限重试（退避 1s/2s/4s，对齐 `PaymentRetryService` 风格）→ 超 `maxRetry` 进 DLQ + 指标告警。**

未 ACK 消息留在 PEL，`XAUTOCLAIM` 接管崩溃消费者遗留的消息——**消费者崩溃不丢消息**。

**D10. 容灾按「MQ 有冗余」的水准落地，但诚实标注上限。**

- `docker-compose.yml` 给 redis 补 `--appendonly yes --maxmemory 512mb --maxmemory-policy noeviction` + 数据卷（消除 G13 的「容器重建全丢」）；
- **仍会丢的场景**：Redis 整体不可用且 AOF 未落盘 → 已提交事务的消息丢失，兜底是 T+1 对账 + 人工重放；Redis 单实例无副本，存在 SPOF。
- 这两条与 ADR-0045 一致：**Redis 全丢系统仍正确，只是变慢/需人工**——它不是数据源。

**D11. payment / fulfillment / entitlement 新增 `spring-boot-starter-data-redis` 依赖。**

payment 用 Redis 构成 ADR-0044/G7 的**显式例外**，写法参照 ADR-0071 D13（只做消息通道，不做计数/缓存），并在本 ADR 登记。

**D12. 订单轨迹纳入本轮：trace 消费组订阅全部事件落 `order_event_log`，并提供 `GET /api/orders/{orderNo}/timeline`。**

这顺带补上「按订单号还原全链路状态变迁」的能力缺口（现有 MDC 只有 traceId，无 orderNo 维度）。**新增订阅者零成本**——不改任何生产者代码，正是广播拓扑的直接收益。

**D13. 不做延迟消息。**

超时关单继续用 ZSet 时间轮（ADR-0043），不另造延迟队列；`order.cancelled` 作为该流程的**产物事件**广播出去。

**D14. traceId 必须跨异步边界连续——这是异步化的头号可观测性风险。**

现状链路追踪靠 **`TraceIdRequestInterceptor` 在 Feign 出站时把 MDC 里的 traceId 写进 HTTP 头**实现。改成消息通道后，HTTP 头这条传播路径**消失**，若不显式处理，一笔订单的日志会被切成 payment 段、order 段、fulfillment 段、entitlement 段四截互不相干的碎片——这比现状（同步调用天然同 traceId）**更差**。

故强制：

| 环节 | 要求 |
|---|---|
| 生产 | `prepare()` MUST 从 `TraceContext` 取当前 traceId 写入信封（无则新建） |
| 消费 | 处理前 MUST `MDC.put("traceId", envelope.traceId())`，`finally` 清理 |
| 回查补投 | MUST **沿用信封中的原始 traceId**，不得新建（否则补投这条线索就断了） |
| 消费后再生产 | MUST **继承**当前 traceId（同一业务链路延续） |
| MDC 维度 | 除 traceId 外，MUST 同时注入 `bizNo`（orderNo / paymentNo / refundNo） |

> 最后一条顺带补上现状缺口：现有 MDC 只有 traceId、无 orderNo 维度（`TraceIdFilter.java:29-30`），排障只能靠 grep 日志文本。把 bizNo 提升为 MDC 维度后，配合 §D12 的轨迹表，"按订单号还原全链路"才是真的可用。

## 约束（实现期 MUST 遵守）

- **C1**：出站事件 MUST 走半消息协议，禁止「先 `XADD` 后做本地事务」（那会把 G3 的吞异常升级成丢消息）。
- **C2**：消费端 MUST 幂等，且幂等键沿用 ADR-0063 业务单号口径。
- **C3**：`order_items` 仍是明细唯一事实源，事件负载 MUST 由生产方从自己的库富化（ADR-0066）。
- **C4**：Redis 通道不得成为资金事实真相源；账本仍以 ledger DB 为准。
- **C5**：不引入 Redisson / 分布式锁；并发控制维持现有 DB 行锁 + 乐观锁。
- **C6**：架构测试 `ServiceBoundaryTest` 的 MQ 禁用清单**不变**，仅在注释中说明 Redis 通道的定位与豁免依据。
- **C7**：traceId MUST 跨异步边界连续（D14）——生产写入信封、消费恢复进 MDC、回查沿用原始 traceId、消费后再生产继承。任何一处漏做都会让链路追踪碎片化。**这是本 ADR 最容易被实现期忽略、也最难事后补救的一条。**

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| 引入 RocketMQ / Kafka 事务消息 | 违背 G10（不增组件）与 G8（Constitution），且 ADR-0031 要求的证据用不上这么重的设施 |
| Redis List + `LMOVE` 可靠队列 | 抢占式语义，**不支持广播**，与 D6 冲突 |
| Redis Pub/Sub | 无持久化无确认，消费者离线即丢，做不到 at-least-once |
| 纯异步无回查（`XADD` 了事） | 不满足负责人「按 RocketMQ 事务消息水准」的要求：进程在提交后投递前崩溃就永久丢事件 |
| DB Outbox 表 + 投递器 | 可靠性更优，但负责人已明确选择「只用 Redis」；且本项目的真相表天然可回查（D4），Redis 侧半消息 + 回查已能达到同等效果 |
| 全广播（payment.succeeded 直接广播） | fulfillment 拿不到权威 `orderItemNo`，违背 G3/ADR-0066 事实源原则 |
