# Tasks: 029 Redis 事务消息通道 + 跨服务异步解耦

> **状态**：**已实现**（批次 A~G，2026-09-20 落地于 `feature/029-redis-transactional-mq`）
> 批次顺序 **A → B → C → D → E → F → G** 不可乱序（后批次依赖前批次类型与配置）
> 对应 [plan.md](plan.md) / [spec.md](spec.md) / [acceptance.md](acceptance.md)

## 批次 A：决策落地 + Redis 容灾 + 依赖铺路

- [x] T1 ADR-0074 落 `docs/adr/0074-redis-transactional-message.md`，状态 Proposed（ADR-0074）
- [x] T2 `0010-distributed-evolution-decisions.md`：ADR-0031 标记 **Superseded by ADR-0074**（保留原文判据不改）
- [x] T3 `docs/adr/README.md`：索引表 + 编号速查表登记 0035 / ADR-0074（顺手补 B1 缺失的表头行）
- [x] T4 `docs/adr/traceability.md` 登记落点
- [x] T5 `docker-compose.yml`：redis 服务补 `--appendonly yes --maxmemory 512mb --maxmemory-policy noeviction` + 数据卷（FR-501）
- [x] T6 `payment-service` pom 新增 `spring-boot-starter-data-redis` + yml 配置，**写明是 ADR-0044/G7 的显式例外**（FR-301）
- [x] T7 `fulfillment-service` pom + yml 新增 Redis（FR-304）
- [x] T8 `entitlement-service` pom + yml 新增 Redis（FR-305）
- [x] T9 断言：批次 A 完成后业务链路零改动，`mvn -o clean verify -fae` 全绿

## 批次 B：`common-redis-mq` starter

- [x] T10 新建 Maven 模块 `common/common-redis-mq`（父 pom 注册、依赖仅 redis + common-core）
- [x] T11 `EventEnvelope` record：msgId / topic / eventType / bizNo / traceId / producer / occurredAt / payload（FR-101）
- [x] T12 `MqProperties` 配置绑定：enabled / block-ms / batch-size / max-retry / prepare-timeout-ms / max-check-times / min-idle-ms（FR-110）
- [x] T13 `TransactionalProducer.prepare()`：写半消息 + `ZADD` 回查索引（FR-102）
- [x] T14 `TransactionalProducer.commit()`：`XADD` 可见队列 + 清半消息与索引（FR-103）
- [x] T15 `TransactionalProducer.rollback()`：只清半消息与索引，不投递（FR-104）
- [x] T16 `TransactionalProducer.sendInTransaction(topic, envelope, callback)` 模板方法：收敛 prepare→事务→commit/rollback 的 try/catch（防调用点漏写）
- [x] T17 `TransactionChecker` SPI：`LocalTxState check(EventEnvelope)` → COMMIT / ROLLBACK / UNKNOWN（FR-105）
- [x] T18 `HalfMessageScanner`：`@Scheduled(fixedDelay=5000)` 扫到期半消息 + 分派 checker + UNKNOWN 计数（FR-106）
- [x] T19 扫描器用 `TraceContext.runWithNewTrace` 包裹，`BusinessMetrics` 打 `mq.checked`（FR-111、FR-502）
- [x] T20 `StreamConsumer`：启动建组（`MKSTREAM`，忽略 `BUSYGROUP`）（FR-107）
- [x] T21 `StreamConsumer` 主循环：`XREADGROUP` + `blockMs=2000` + 成功 `XACK`（FR-107、ADR-0074 D8）
- [x] T22 `XAUTOCLAIM` 接管：`minIdleMs=60s` 以上的 PEL 消息由同组实例认领（FR-109）
- [x] T23 消费失败退避 1s/2s/4s + `maxRetry` 后进 DLQ + `XACK` + 指标 `mq.dead_letter`（FR-108）
- [x] T24 `RedisMqAutoConfiguration` 自动装配（FR-110）
- [x] T25 单测：prepare→commit 后消息可见；prepare→rollback 后不可见（INV-3）
- [x] T26 单测：回查三种状态分派正确；UNKNOWN 超次进 DLQ
- [x] T27 单测：消费端抛异常 → 重试 3 次 → 进 DLQ

## 批次 C：生产侧改造

- [x] T28 `PaymentApplicationService:197` 改 `sendInTransaction("payment.succeeded", ...)`（FR-201）
- [x] T29 `PaymentResultProcessor:105` 同上（FR-201）
- [x] T30 `RefundResultProcessor:142` 改 `sendInTransaction("refund.result", ...)`（FR-203）
- [x] T31 payment 侧回查 checker：`payments` 是否 SUCCEEDED / `refunds` 是否终态（FR-302）
- [x] T32 `OrderApplicationService.onPaymentSucceeded`：事务提交后发 `order.paid`，**负载由本库 `order_items` 富化**（FR-202、INV-4）
- [x] T33 删除 `onPaymentSucceeded` 中 `:250` confirmStock 与 `:258` 履约驱动两处同步调用（改由订阅方执行）
- [x] T34 `TransactionApplicationService.onRefundResult`：首次终态后发 `refund.succeeded`（FR-204）
- [x] T35 删除 `onRefundResult` 中 `:283` 秒杀回补与 `:288` 履约终止两处同步调用
- [x] T36 `OrderTimeoutScheduler.handleExpired`：`cancel()` 落库后发 `order.cancelled`（FR-205，新增能力）
- [x] T37 order 侧三个回查 checker：`orders` 是否 PAID / `transaction_refunds` 是否 SUCCEEDED / `orders` 是否 CANCELLED·CLOSED（FR-302）
- [x] T38 保留 `mq.enabled=false` 回落同步 Feign 的分支（FR-306）
- [x] T39 断言：既有支付 / 退款集成测试在 `mq.enabled=true/false` 两种模式下均通过

## 批次 D：消费侧改造

- [x] T40 catalog 消费 `order.paid` → `confirmStock`，幂等键 `reservationId`（FR-303）
- [x] T41 catalog 消费 `refund.succeeded` → `rollbackSeckill`，幂等键 `refund:{TXRF}:sku:{skuId}`（FR-303）
- [x] T42 catalog 消费 `order.cancelled` → `releaseStock` + `rollbackSeckill`（FR-303）
- [x] T43 fulfillment 消费 `order.paid` → 按明细建履约单（FR-304）
- [x] T44 fulfillment 消费 `refund.succeeded` → 终止履约，并生产 `fulfillment.revoked`（FR-304、FR-207）
- [x] T45 fulfillment 消费 `order.cancelled` → 撤单（FR-304）
- [x] T46 fulfillment 生产 `fulfillment.completed`（FR-206）
- [x] T47 entitlement 消费 `fulfillment.completed` → 授予权益（FR-305）
- [x] T48 entitlement 消费 `fulfillment.revoked` → 回收权益（FR-305）
- [x] T49 payment 消费 `order.cancelled` → 标记订单不可受理，拒收后续回调（FR-301）
- [x] T50 断言：全部消费端重复投递（同 msgId `XADD` 两次）不产生第二笔业务副作用（INV-2）

## 批次 E：订单轨迹

- [x] T51 `01-order-schema.sql` 新增 `order_event_log` 表 + 索引 `(order_no, occurred_at)`（FR-401）
- [x] T52 `trace` 消费组订阅全部 7 个 topic，落表（FR-402）
- [x] T53 `GET /api/orders/{orderNo}/timeline` 接口（FR-403）
- [x] T54 断言：轨迹为只读投影，删除轨迹表后业务链路仍正常（INV-5、FR-404）

## 批次 F：可观测、门禁、文档、演示

- [x] T55 指标：`mq.prepared` / `mq.committed` / `mq.rolled_back` / `mq.checked` / `mq.consumed` / `mq.retried` / `mq.dead_letter` / `mq.half_backlog`（FR-502）
- [x] T56 `StructuredAuditLogger` 记录投递与消费结果，含 msgId + bizNo（FR-503）
- [x] T57 Grafana 新增「消息通道」面板：半消息积压 / 消费延迟 / DLQ 堆积 / 各组位点（FR-504）
- [x] T58 `ServiceBoundaryTest` 注释补充 Redis 通道定位与豁免依据（禁用清单不变）
- [x] T59 `docs/architecture/systems/{order,payment,fulfillment,catalog,entitlement}-service.md` 补生产/消费事件清单（**文档期已写入，实现期按真实代码复核一遍**）
- [x] T60 `CHANGELOG.md` + `roadmap.md` 登记
- [x] T61 演示脚本 D1~D6（spec §5）：回滚不投递 / 崩溃回查 / 下游宕机自愈 / 广播隔离 / 轨迹 / 死信
- [ ] T62 全量门禁：`mvn -o clean verify -fae` 全绿 + 新增通道测试 ≥30 用例（SC-9）

## 批次 G：链路追踪连续性 + 架构文档同步（2026-09-19 追加）

> 批次 G 的两件事都由负责人 2026-09-19 追加：① 用 MQ 后 traceId 必须还能跟踪到；② 架构变动必须同步总体技术方案与各系统设计文档。

- [x] T63 `TransactionalProducer.prepare()` 从 `TraceContext` 取当前 traceId 写入 `EventEnvelope.traceId`（无则新建）（FR-601）
- [x] T64 `StreamConsumer` 消费前 `MDC.put("traceId", envelope.traceId())`，`finally` 清理（FR-602）
- [x] T65 `HalfMessageScanner` 回查补投**沿用信封原始 traceId**，禁止 `runWithNewTrace` 新建（FR-603）
- [x] T66 消费端再生产新事件时**继承**当前 traceId（fulfillment → entitlement 链路延续）（FR-604）
- [x] T67 MDC 增 `bizNo` 维度（orderNo / paymentNo / refundNo），logback pattern 补 `%X{bizNo}`（FR-605、FR-606）
- [x] T68 `trace-grep.sh` 等排障脚本支持按 bizNo 检索（FR-607）
- [x] T69 **`technical-solution.md` 同步**：`:53` 不引入 MQ 的例外清单追加 Redis 通道、`:193` 异步事件现状、架构总览补消息通道、§4 链路时序改事件驱动（spec §10）
- [x] T70 **`systems/*.md` 同步**：order / payment / fulfillment / catalog / entitlement 五份补各自生产/消费事件清单
- [x] T71 `constitution.md:156` 增补 Redis 事务消息通道
- [x] T72 `CHANGELOG.md` + `roadmap.md` 登记 spec 029 / ADR-0074
- [ ] T73 断言：一笔完整链路（下单→支付→履约→权益）的所有日志**同一 traceId**，且可按 bizNo 检索出全链路（SC-11）
