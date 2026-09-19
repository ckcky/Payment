# Acceptance: 029 Redis 事务消息通道 + 跨服务异步解耦

对照 [spec.md](spec.md) 的 INV-1~INV-8 与 SC-1~SC-10。**全部满足才可合并。**

> 断言口径（全篇）：**消息通道不得成为任何业务正确性的唯一依据**（INV-5）。任何一条断言的反面验证方式都是——清空 Redis（`FLUSHALL`）后业务数据仍正确，只是消息丢失且告警可见。

## INV 不变量（硬门禁）

- [ ] **INV-1 事实不回滚**：通知失败不影响本地已提交事实（订单仍 PAID、退款单仍终态），与改造前语义一致
- [ ] **INV-2 消费端幂等**：8 条链路各自重复投递一次（同 msgId 投递两次），断言不产生第二笔业务副作用（第二张履约单 / 第二次库存扣减 / 第二次权益授予）
- [ ] **INV-3 先事务后可见**：构造本地事务抛异常场景，断言 `mq:stream:*` 无新消息、半消息被 rollback 清理
- [ ] **INV-4 事实源**：`order.paid` 负载的明细来自 order 库 `order_items`；fulfillment/catalog 消费时**不反查 order**
- [ ] **INV-5 Redis 非数据源**：`FLUSHALL` 后业务数据正确；仅消息丢失且 `mq.*` 告警可见
- [ ] **INV-6 业务单号**：事件信封 bizNo 一律 `orderNo` / `paymentNo` / `refundNo`，无数值 ID
- [ ] **INV-7 不引新组件**：全仓无 kafka / rocketmq / rabbitmq / amqp / redisson 依赖；`ServiceBoundaryTest` 仍通过
- [ ] **INV-8 幂等键不漂移**：事件投递不参与任何幂等键构造；各域幂等键与改造前逐字一致
- [ ] **INV-9 traceId 跨异步边界连续**：一笔完整链路（下单 → 支付 → 订单 PAID → 库存确认 → 履约 → 权益授予）的**全部日志同一 traceId**；用该 traceId 可检索出跨 5 个服务的完整日志

## SC-1 8 条链路全部改为事务消息

- [ ] `OrderApplicationService` 中 `:250` confirmStock、`:258` 履约驱动的同步调用已移除
- [ ] `TransactionApplicationService` 中 `:283` 回补、`:288` 终止履约的同步调用已移除
- [ ] `PaymentApplicationService:197`、`PaymentResultProcessor:105`、`RefundResultProcessor:142` 改为 `sendInTransaction`
- [ ] `OrderTimeoutScheduler` 关单后发布 `order.cancelled`
- [ ] `mq.enabled=false` 时全部回落同步 Feign，行为与改造前一致

## SC-2 事务失败零投递

- [ ] 注入订单落库失败 → `mq:stream:order.paid` 无新消息
- [ ] `mq:half:*` 与 `mq:half:idx` 中对应条目被清除
- [ ] 指标 `mq.rolled_back` +1

## SC-3 崩溃后回查补投

- [ ] 在 prepare 与 commit 之间 kill -9 生产者进程
- [ ] 60s 内 `HalfMessageScanner` 发现半消息 → 依据真相表判定 → 补投
- [ ] 断言消费端收到且仅收到一次有效副作用（重复由幂等吸收）
- [ ] UNKNOWN 累计超 `maxCheckTimes` → 进 DLQ + 告警

## SC-4 下游宕机自愈

- [ ] 停 fulfillment → 完成一笔支付 → 订单仍为 PAID（`order.initiated` / PAID 状态正常）
- [ ] `mq:stream:order.paid` 存在未 ACK 消息，PEL 可查
- [ ] 重启 fulfillment → 30s 内消费完成并 XACK，履约单创建
- [ ] 全程无人工干预，指标 `mq.consumed` +1

## SC-5 广播组间隔离

- [ ] 一笔支付后，catalog / fulfillment / trace 三组位点各自 +1
- [ ] 停掉 trace 组消费 → catalog、fulfillment 组不受影响
- [ ] 新增一个订阅方（仅加消费组，不改生产方代码）可收到全量消息

## SC-6 重复投递被幂等吸收

- [ ] 手工 `XADD` 同一 msgId 两次 → 消费端两次处理，业务副作用仅一次
- [ ] 8 条链路逐一验证（覆盖 INV-2 的 6 类幂等键）

## SC-7 订单轨迹可还原

- [ ] 跑完「下单 → 支付 → 履约 → 部分退款」后，`GET /api/orders/{orderNo}/timeline` 返回 ≥4 条事件
- [ ] 事件按 `occurred_at` 升序，含 traceId 与 producer
- [ ] 删除 `order_event_log` 后业务链路仍正常（只读投影）

## SC-8 记账链路保持同步

- [ ] `payment → ledger`、`refund → ledger`、`settlement → ledger` 三条**未接入通道**
- [ ] 代码中无 `mq:stream:ledger.*` 相关 topic
- [ ] 既有记账测试零改动通过

## SC-9 构建全绿

- [ ] `mvn -o clean verify -fae` 全绿（既有 ≥595 用例 + 新增通道测试 ≥30 用例）
- [ ] `architecture-tests` 边界门禁通过

## SC-10 容灾演练

- [ ] `FLUSHALL` 后业务数据正确，仅消息丢失且告警可见
- [ ] Redis 容器重建（含数据卷）后 AOF 恢复，未消费消息仍在
- [ ] `maxmemory-policy=noeviction` 生效（写入超限报 OOM 错误而非静默淘汰）

## SC-11 traceId 跨异步边界连续（INV-9）

- [ ] 一笔完整链路的全部日志同一 traceId（payment / order / catalog / fulfillment / entitlement 五段）
- [ ] 生产端信封 `traceId` 非空且与生产者当前 MDC 一致
- [ ] 消费端日志 traceId = 信封 traceId（非新建）
- [ ] **回查补投**的消息携带原始 traceId（不是回查器新建的 traceId）
- [ ] fulfillment 消费 `order.paid` 后发 `fulfillment.completed`，两者 traceId 相同
- [ ] MDC 含 `bizNo` 维度；`trace-grep.sh <orderNo>` 可检索出全链路日志

## SC-12 架构文档已同步

- [ ] `technical-solution.md` 的 MQ 例外清单、异步现状、架构总览、链路时序均已更新为事件驱动
- [ ] `systems/{order,payment,fulfillment,catalog,entitlement}-service.md` 各自含「生产 / 消费事件清单」小节
- [ ] `constitution.md:156` 已增补 Redis 事务消息通道
- [ ] `CHANGELOG.md` + `roadmap.md` 已登记 spec 029 / ADR-0074
- [ ] 评审确认：文档描述与真实代码一致（无「文档许诺了、代码没实现」的新欠账）
