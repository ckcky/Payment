# Plan: 029 Redis 事务消息通道 + 跨服务异步解耦

对应 [spec.md](spec.md)。**本 Plan 描述实现期（`feature/029-redis-transactional-mq`）的落地方案**；已于 2026-09-20 按批次 A~G 完成落地并合入 master `5e2c00d`。

## 0. 范围与执行顺序

七个批次，**A → B → C → D → E → F → G 不可乱序**（后批次依赖前批次的类型与配置）：

| 批次 | 内容 | 对应 FR | 可独立编译？ |
|---|---|---|---|
| A | 决策落地 + Redis 容灾 + 依赖铺路 | FR-301/304/305、FR-501 | ✅（此时业务链路完全不变） |
| B | `common-redis-mq` starter（信封 / 生产者 / 回查 / 消费者） | FR-101~FR-112 | ✅（纯新增模块） |
| C | 生产侧改造（payment、order） | FR-201/202/203/204/205、FR-210、FR-302 | ❌ 依赖 B |
| D | 消费侧改造（catalog、fulfillment、entitlement） | FR-303/304/305、FR-206/207 | ❌ 依赖 B/C |
| E | 订单轨迹（表 + trace 组 + timeline API） | FR-401~FR-404 | ❌ 依赖 B |
| F | 可观测、架构门禁、文档同步、演示件 | FR-502~FR-504、§5、§10 | ❌ 依赖 C/D/E |
| **G** | **链路追踪连续性 + 架构文档同步**（2026-09-19 追加） | FR-601~FR-607、spec §10 | G 的文档部分可独立做；代码部分依赖 B/C/D |

**回滚开关贯穿始终**：`mq.enabled=false` 时所有改造点回落同步 Feign，保证任何批次中断都可退回现状（FR-306）。

---

## 批次 A：决策落地 + Redis 容灾 + 依赖铺路

### A1 决策与文档同步

ADR-0074 落 `docs/adr/0074-redis-transactional-message.md` 并标 Proposed；同步修订：
- `0029-distributed-evolution-decisions.md`：ADR-0031 标 **Superseded by ADR-0074**（保留原文，不改判据）；
- `docs/adr/README.md` 两张表（B1 缺表头行，顺手补）；
- `traceability.md`；
- `technical-solution.md:53`（不引入 MQ 的例外清单追加 Redis 通道）、`:193`（异步事件现状）；
- `.specify/memory/constitution.md:156`。

### A2 Redis 容灾配置

`deployment/docker-compose.yml:54` 的 redis 服务补：

```yaml
command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy noeviction
volumes:
  - redis-data:/data
```

> **为什么必须 noeviction**：Stream 与半消息都是「不能悄悄丢」的数据。`allkeys-lru` 会在内存压力下淘汰消息键——那等于静默丢事件，比宕机更危险。宁可写入报 OOM 错误（有告警），不可静默淘汰。

### A3 依赖铺路

`payment-service` / `fulfillment-service` / `entitlement-service` 三个 pom 新增 `spring-boot-starter-data-redis`，配置沿用现有口径（host、timeout 1000ms、Lettuce 池 `max-active:16`，容器模式由 compose 注入 `SPRING_DATA_REDIS_HOST=redis`）。

> payment 用 Redis 是 **ADR-0044/G7 的显式例外**，须在 ADR-0074 D11 与本批次提交信息中写明：仅作消息通道，不做缓存 / 计数。

---

## 批次 B：`common-redis-mq` starter

新增 Maven 模块 `common/common-redis-mq`，包名 `com.payment.common.redismq`，依赖仅 `spring-boot-starter-data-redis` + `common-core`。

### B1 事件信封

```java
public record EventEnvelope(
    String msgId,      // 雪花
    String topic,      // order.paid / refund.succeeded / ...
    String eventType,  // 同 topic，冗余便于检索
    String bizNo,      // orderNo / paymentNo / refundNo（ADR-0063）
    String traceId,
    String producer,   // order / payment / fulfillment
    long occurredAt,
    String payload     // JSON
) {}
```

落 Stream 时按 field-value 写入（`XADD mq:stream:{topic} * msgId ... payload ...`），避免二次转义。

### B2 半消息协议（事务消息核心）

| 步骤 | 命令 |
|---|---|
| Prepare | `SET mq:half:{topic}:{msgId} <envelope JSON>` + `ZADD mq:half:idx <now> {topic}:{msgId}` |
| Commit | `XADD mq:stream:{topic} * <fields>` → `ZREM mq:half:idx {topic}:{msgId}` → `DEL mq:half:...` |
| Rollback | `ZREM` + `DEL`（不投递） |

**Prepare 必须在本地事务之前**（INV-3）。业务侧典型形态：

```java
String msgId = producer.prepare("order.paid", envelope);
try {
    tx.execute(status -> { /* 本地事务 */ });
    producer.commit(msgId);
} catch (RuntimeException e) {
    producer.rollback(msgId);
    throw e;
}
```

> 建议提供 `producer.sendInTransaction(topic, envelope, txCallback)` 模板方法，把上面这段收敛到 starter 内，避免每个调用点手写 try/catch（8 个生产点一处漏写就是丢消息）。

### B3 回查扫描器

`HalfMessageScanner` `@Scheduled(fixedDelay=5000)`：
1. `ZRANGEBYSCORE mq:half:idx 0 (now - prepareTimeoutMs)` 取到期半消息；
2. 按 topic 分派到业务方注册的 `TransactionChecker`；
3. `COMMIT` → 走 B2 的 Commit；`ROLLBACK` → 清理；`UNKNOWN` → 累计次数，超 `maxCheckTimes` 进 DLQ + 告警。

复用 `TraceContext.runWithNewTrace` 包裹（对齐 `OrderTimeoutScheduler:56` 的既有写法）。

### B4 消费者骨架

`StreamConsumer` 生命周期：
1. 启动：`XGROUP CREATE mq:stream:{topic} g:{subscriber} 0 MKSTREAM`（已存在则忽略 `BUSYGROUP`）；
2. 每轮先 `XAUTOCLAIM`（`minIdleMs=60s`）接管同组崩溃实例遗留的 PEL 消息；
3. `XREADGROUP GROUP g:{subscriber} {consumerName} COUNT {batchSize} BLOCK 2000 STREAMS mq:stream:{topic} >`；
4. 成功 → `XACK`；失败 → 退避 1s/2s/4s 重试，超 `maxRetry` → `XADD mq:dlq:{topic}` + `XACK` + 指标。

> **`blockMs=2000` 而非 0 的理由**（ADR-0074 D8）：Lettuce 共享池仅 16（ADR-0060），长阻塞会独占连接；2s 短阻塞 + 循环既及时释放也几乎无空转开销。

### B5 配置

```yaml
payment:
  mq:
    enabled: true
    block-ms: 2000
    batch-size: 10
    max-retry: 3
    prepare-timeout-ms: 30000
    max-check-times: 10
    min-idle-ms: 60000
```

---

## 批次 C：生产侧改造

### C1 payment 侧（`payment.succeeded` / `refund.result`）

| 原调用 | 改造 |
|---|---|
| `PaymentApplicationService:197` | `sendInTransaction("payment.succeeded", envelope, ...)` |
| `PaymentResultProcessor:105` | 同上 |
| `RefundResultProcessor:142` | `sendInTransaction("refund.result", ...)` |

回查 checker：查 `payments` / `refunds` 对应单号是否终态。

### C2 order 侧（`order.paid` / `refund.succeeded` / `order.cancelled`）

- `onPaymentSucceeded`（`OrderApplicationService:243`）：本地事务（`markPaidAndTransaction`）提交后，prepare/commit `order.paid`；**负载由本库 `order_items` 富化**（INV-4，保留 `enrichWithItems` 语义）；原 `:250` confirmStock 与 `:258` 履约驱动**删除**（改由 catalog / fulfillment 各自订阅）。
- `onRefundResult`（`TransactionApplicationService:235`）：首次终态迁移成功后发 `refund.succeeded`；原 `:283` 回补与 `:288` 终止履约**删除**。
- `OrderTimeoutScheduler.handleExpired`：`order.cancel()` 后发 `order.cancelled`（新增能力，当前无人通知）。

回查 checker：`order.paid` 查 `orders` 是否 PAID；`refund.succeeded` 查 `transaction_refunds` 是否 SUCCEEDED；`order.cancelled` 查 `orders` 是否 CANCELLED/CLOSED。

> **顺序陷阱**：`order.cancelled` 的发布必须在 `cancel()` 落库之后，且关单与支付成功回调并发时仍靠现有乐观锁 + surplus 兜底（ADR-0054），本批次不改这套语义。

---

## 批次 D：消费侧改造

| 服务 | 订阅 | 处理 | 幂等键（沿用） |
|---|---|---|---|
| catalog | `order.paid` | `confirmStock` | `reservationId = order:{orderNo}:sku:{skuId}` |
| catalog | `refund.succeeded` | `rollbackSeckill` | `refund:{TXRF}:sku:{skuId}` |
| catalog | `order.cancelled` | `releaseStock` + `rollbackSeckill` | 同上 |
| fulfillment | `order.paid` | 按明细建履约单 | `(sourcePaymentNo, orderItemId)` 唯一键 |
| fulfillment | `refund.succeeded` | 终止/撤销履约 | 同上 |
| fulfillment | `order.cancelled` | 撤单 | 同上 |
| entitlement | `fulfillment.completed` | 授予权益 | `sourceFulfillmentId` 唯一 |
| entitlement | `fulfillment.revoked` | 回收权益 | 终态吸收 |
| payment | `order.cancelled` | 标记订单不可受理，拒收后续回调 | paymentNo 终态吸收 |

fulfillment 处理完 `order.paid` / `refund.succeeded` 后，继续生产 `fulfillment.completed` / `fulfillment.revoked` 给 entitlement（保持既定 fulfillment → entitlement 链，order 不直调 entitlement）。

---

## 批次 E：订单轨迹

1. `deployment/schema/01-order-schema.sql` 新增 `order_event_log`，索引 `(order_no, occurred_at)`；
2. `trace` 消费组订阅全部 7 个 topic，落表（消费失败进 DLQ，不影响业务组）；
3. `GET /api/orders/{orderNo}/timeline` 返回时序事件列表。

> 轨迹是**只读投影**（INV-5）：业务正确性不得依赖它；它只服务排障与客服。

---

## 批次 F：可观测、门禁、文档、演示

- 指标 `mq.*`（FR-502）+ `StructuredAuditLogger` 留痕；
- Grafana「消息通道」面板：半消息积压、消费延迟、DLQ 堆积、各组位点；
- `ServiceBoundaryTest` 注释补充 Redis 通道定位（禁用清单不变）；
- `docs/architecture/systems/{order,payment,fulfillment}-service.md` 补各自的生产/消费事件清单；
- `CHANGELOG.md` / `roadmap.md` 登记；
- 演示脚本 D1~D6（spec §5）。

---

## 批次 G：链路追踪连续性 + 架构文档同步

### G1 traceId 跨异步边界（头号可观测性风险）

现状依赖 `TraceIdRequestInterceptor` 把 MDC 的 traceId 写进 Feign 出站 HTTP 头。改消息通道后这条路径消失，必须显式补偿：

| 环节 | 实现要点 |
|---|---|
| 生产 | `prepare()` 内 `envelope.traceId = TraceContext.getOrCreate()` |
| 消费 | 处理前 `MDC.put("traceId", envelope.traceId())`，`finally` `MDC.remove` |
| 回查 | **沿用信封原始 traceId**（回查器自身仍用 `runWithNewTrace` 包一层扫描逻辑，但补投出去的消息带的是原始 traceId） |
| 再生产 | 消费端发新事件时继承当前 traceId |
| MDC 维度 | 增 `bizNo`，logback pattern 补 `%X{bizNo}` |

> **为什么回查必须用原始 traceId**：回查补投的消息是"替生产者补发一条早就该发的消息"，它属于原业务链路。若用新 traceId，这条补投线索与原始链路永久断开——恰恰是最需要追踪的那条。

### G2 架构文档同步（与代码同批，不得滞后）

异步化改变了服务间的耦合形态：原「order 串行调用 catalog/fulfillment」变为「order 发布事实、各方订阅」。**总体技术方案与各系统设计文档若不同步，即刻变成错误描述。**

必改（本轮文档期先按方案写入，实现期复核）：

- `technical-solution.md`：`:53` 不引入 MQ 的例外清单追加 Redis 通道、`:193` 异步事件现状、架构总览补消息通道、§4 链路时序改为事件驱动；
- `systems/order-service.md` / `payment-service.md` / `fulfillment-service.md` / `catalog-service.md` / `entitlement-service.md`：各自补「生产 / 消费事件清单」小节；
- `constitution.md:156`、`CHANGELOG.md`、`roadmap.md`。
