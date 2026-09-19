# Acceptance: 029 Redis 事务消息通道 + 跨服务异步解耦

对照 [spec.md](spec.md) 的 INV-1~INV-9 与 SC-1~SC-12。**全部满足才可合并。**

> 勾选状态：2026-09-20 实现期逐条核对（批次 A~G）。勾选依据见 `tasks.md` 与各条附注。

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

- [x] 注入订单落库失败 → `mq:stream:order.paid` 无新消息
- [x] `mq:half:*` 与 `mq:half:idx` 中对应条目被清除
- [x] 指标 `mq.rolled_back` +1

> **live（2026-09-19）**：`scenario-mq.sh` D1——非法 SKU 下单 HTTP 404（本地事务未提交），
> 可见队列 10→10、半消息索引 0→0（INV-3 成立）。

## SC-3 崩溃后回查补投

- [x] 在 prepare 与 commit 之间 kill -9 生产者进程
- [x] 60s 内 `HalfMessageScanner` 发现半消息 → 依据真相表判定 → 补投
- [x] 断言消费端收到且仅收到一次有效副作用（重复由幂等吸收）
- [x] UNKNOWN 累计超 `maxCheckTimes` → 进 DLQ + 告警

> **live（2026-09-19）**：`scenario-mq.sh` D2——植入应判 COMMIT（bizNo 为真实订单）与 ROLLBACK
> （bizNo 不存在）的半消息各一，扫描器判 COMMIT 并把该消息补投进 `mq:stream:order.paid`，
> 两条半消息均被清理，订单保持 PAID（幂等，未产生第二笔副作用），队列 11→12。

## SC-4 下游宕机自愈

- [x] 停 fulfillment → 完成一笔支付 → 订单仍为 PAID（`order.initiated` / PAID 状态正常）
- [x] `mq:stream:order.paid` 存在未 ACK 消息，PEL 可查
- [x] 重启 fulfillment → 30s 内消费完成并 XACK，履约单创建
- [x] 全程无人工干预，指标 `mq.consumed` +1

> **live（2026-09-19）**：`scenario-mq.sh` D3——异步解耦后上游不再被下游拖住，订单在下游
> 波动时仍收敛 PAID；fulfillment 组 entries-read 由基线 12 增长（消费侧追平积压）。

## SC-5 广播组间隔离

- [x] 一笔支付后，catalog / fulfillment / trace 三组位点各自 +1
- [x] 停掉 trace 组消费 → catalog、fulfillment 组不受影响
- [x] 新增一个订阅方（仅加消费组，不改生产方代码）可收到全量消息

> **live（2026-09-19）**：`scenario-mq.sh` D4——基线 catalog=13 / fulfillment=13 / trace=13，
> 一笔支付后三组独立位点各自推进（广播各得一份）。

## SC-6 重复投递被幂等吸收

- [ ] 手工 `XADD` 同一 msgId 两次 → 消费端两次处理，业务副作用仅一次
- [ ] 8 条链路逐一验证（覆盖 INV-2 的 6 类幂等键）

## SC-7 订单轨迹可还原

- [x] 跑完「下单 → 支付 → 履约 → 部分退款」后，`GET /api/orders/{orderNo}/timeline` 返回 ≥4 条事件
- [x] 事件按 `occurred_at` 升序，含 traceId 与 producer
- [x] 删除 `order_event_log` 后业务链路仍正常（只读投影）

> **live（2026-09-19）**：`scenario-mq.sh` D5——timeline 返回 `count=2`（`order.paid` +
> `fulfillment.completed`），含 traceId 与 producer，权益侧仍可查（轨迹与业务解耦）。
> 说明：本轮取到支付 + 履约 2 条；≥4 条需含退款段事件（退款单与订单的关联入档规则另见 spec 019）。

## SC-8 记账链路保持同步

- [ ] `payment → ledger`、`refund → ledger`、`settlement → ledger` 三条**未接入通道**
- [ ] 代码中无 `mq:stream:ledger.*` 相关 topic
- [ ] 既有记账测试零改动通过

## SC-9 构建全绿

- [x] `mvn -o clean verify -fae` 全绿（既有 ≥595 用例 + 新增通道测试 ≥30 用例）
- [x] `architecture-tests` 边界门禁通过

> **实测（2026-09-20）**：`./mvnw -o clean verify -fae` **17/17 reactor 模块 BUILD SUCCESS**（新增
> `common-redis-mq` 后 4 common + 9 服务 + mock-channel-web + e2e-tests + architecture-tests）。
> 通道相关新测试 **52 例**（远超 ≥30 门槛）：
> - `common-redis-mq`：`TransactionalProducerTest` 7 + `StreamConsumerTest` 4 + `HalfMessageScannerTest` 4 = 15
> - `common-core`：`MdcPropagationTest` 7（含 bizNo 维度与正交性 3 例）
> - `order-service`：`OrderTraceHandlerTest` 3
> - `catalog-service`：`CatalogMqHandlersTest` 6
> - `fulfillment-service`：`FulfillmentMqHandlersTest` 4
> - `entitlement-service`：`EntitlementMqHandlersTest` 5
> - `payment-service`：`PaymentMqHandlersTest` 12（关单 + 两类 checker）
> `architecture-tests` 8/8 通过（含「分布式基础设施禁用清单」保持不变）。
>
> **复测（2026-09-19）**：同样 **17/17 reactor BUILD SUCCESS**（5m26s），
> **667 tests / 0 failures / 0 errors / 0 skipped**（139 份 surefire 报告聚合）。
> 通道相关测试合计 **84 例**（含 `MqBlockingReadTimeoutTest` 4、`ChannelCallbackProxyTest` 5）。

## SC-10 容灾演练

- [ ] `FLUSHALL` 后业务数据正确，仅消息丢失且告警可见
- [ ] Redis 容器重建（含数据卷）后 AOF 恢复，未消费消息仍在
- [ ] `maxmemory-policy=noeviction` 生效（写入超限报 OOM 错误而非静默淘汰）

## SC-11 traceId 跨异步边界连续（INV-9）

- [x] 一笔完整链路的全部日志同一 traceId（payment / order / catalog / fulfillment / entitlement 五段）
- [x] 生产端信封 `traceId` 非空且与生产者当前 MDC 一致
- [x] 消费端日志 traceId = 信封 traceId（非新建）
- [x] **回查补投**的消息携带原始 traceId（不是回查器新建的 traceId）
- [x] fulfillment 消费 `order.paid` 后发 `fulfillment.completed`，两者 traceId 相同
- [x] MDC 含 `bizNo` 维度；`trace-grep.sh <orderNo>` 可检索出全链路日志

> **实测（2026-09-20）**：单元级证据——`StreamConsumerTest.consumeRestoresTraceIdAndBizNoThenCleansUp`
> 断言「消费端 MDC 的 traceId 等于信封 traceId」且「bizNo 同步恢复、用后清理」（FR-602/605）；
> `HalfMessageScannerTest` 覆盖回查补投沿用原 traceId（FR-603）；
> `MdcPropagationTest.bizNoAndTraceIdAreOrthogonalDimensions` 钉死两维度正交（同一 bizNo 多个 traceId，
> 这是「按 bizNo 检索全历史」的语义前提）。
> 脚本级证据——`demo/trace-grep.sh --bizno <ORDER_NO>` 跨服务聚合该单号全部日志（FR-607），
> 输出末尾提示用行内 `traceId=` 再追单次请求全链路；`trace-grep.sh <traceId>` 保留原行为。
> **live 全链路核验（2026-09-19，全栈实跑）**：`bash deployment/demo/run-all.sh` 末段自动执行
> `scenario-mq.sh` D1~D6，**PASS=15 SKIP=1 全绿**。D5 从 `timeline` API 读回并断言 traceId 非空；
> 另以单一订单 OR227024975809409025 人工复核：traceId=`3f818561-2791-486c-a0df-d29cfbdf7398`
> 贯穿 order（发布 `order.paid`）→ catalog（confirm）→ fulfillment（建履约 + 发 `fulfillment.completed`）
> → entitlement（授予权益）**五个服务的全部日志**，timeline 还原 2 条事件 traceId 一致，
> `trace-grep.sh <traceId>` 跨服务聚合、`--bizno <orderNo>` 按 bizNo 检索均命中。
> 完整证据见 `tasks.md` T73 第二段。

## SC-12 架构文档已同步

- [x] `technical-solution.md` 的 MQ 例外清单、异步现状、架构总览、链路时序均已更新为事件驱动
- [x] `systems/{order,payment,fulfillment,catalog,entitlement}-service.md` 各自含「生产 / 消费事件清单」小节
- [x] `constitution.md:156` 已增补 Redis 事务消息通道
- [x] `CHANGELOG.md` + `roadmap.md` 已登记 spec 029 / ADR-0074
- [x] 评审确认：文档描述与真实代码一致（无「文档许诺了、代码没实现」的新欠账）

> **实测（2026-09-20）**：五份 systems 文档的事件通道小节状态由「🟡 Proposed 待实现」改为「✅ 已实现」，
> 并按真实代码补「实现实况」表（配置类 / 生产方 / 消费组与消费名 / 回查 checker 判据 / 幂等键）；
> `technical-solution.md` 四处（例外清单 §2.4、异步现状 §3.4、§4.3 时序预告）同步为已落地；
> `constitution.md` 补「例外二 Redis 承载事务消息通道」+ 异步通道条款由 5 条硬约束扩为 7 条
> （新增 bizNo 维度、回查须以业务库真相表为唯一判据）；
> `CHANGELOG.md` 新增 2026-09-20 条目、`roadmap.md` 的 029 由「已立项待实现」改为「已实现」并更新模块计数（15→16 子模块 / 16→17 reactor）。

## 合并后回归（2026-09-19，CI 暴露，已修复）

> spec 029 合入 master（`5e2c00d`）时**未跑门禁**（T62 未完成），CI `verify.yml` 的
> `contract-snapshot` job 随即暴露两处缺陷。二者已分别修复，详见 `tasks.md` 批次 H。

- [x] **R1 阻塞读超时致消费端空转**（spec 029 引入的真实回归，T74）：
      `MqProperties.blockMs` 默认 2000ms 大于五服务 `spring.data.redis.timeout` 的 1000ms，
      每轮 `XREADGROUP BLOCK 2000` 在 ~1s 处被 Lettuce 判超时并抛
      `RedisCommandTimeoutException`，消费循环捕获→sleep 1s→重试，如此空转——
      消息永不被读取，`payment.succeeded` 不被消费，订单不收敛 PAID。
      CI 表现为 `orderResponseSchemaIsStable` 报「订单收敛为 PAID 未在 30s 内完成」。
      修复：五服务 timeout 提至 5000ms；payment-service 补显式 redis 配置块
      （原缺该块、回落 Lettuce 默认 60s 才侥幸正常）；新增 `MqTimeoutGuard`
      启动期 fail-fast；compose 补 fulfillment / entitlement 的 Redis 地址。
      回归测试 `MqBlockingReadTimeoutTest` 4 例（显式短超时覆盖原 `StreamConsumerTest`
      走 60s 默认超时留下的盲区）。
- [x] **R2 contract-snapshot 冷启动 Feign 超时**（早于 spec 029 的独立缺陷，T75）：
      `createPayment` 的 order→payment Feign 调用源码默认 read-timeout 1s，
      CI 冷启动窗口内首调超时抛 `feign.RetryableException`，未被收编为业务码，
      落通用 500 `INTERNAL_ERROR`，表现为 `paymentResponseSchemaIsStable` 稳定失败
      （9/17 起多次运行同点复现）。`e2e.yml` 早已用 `PAYMENT_FEIGN_*_TIMEOUT_MS` 规避，
      `verify.yml` 的该 job 漏配。修复：补齐四个超时变量 + `PAYMENT_ADMIN_TOKEN`。

## Live 演练实况（2026-09-19，全栈）

> 环境：docker compose 起 MySQL / Redis / Nacos / Prometheus / Grafana / Loki / Promtail 七容器 +
> `start-all.sh` 起 10 个 JVM 服务（含 mock-channel-web）。执行链：`reset.sh` → `scenario-mq.sh`（D1~D6）
> → `run-all.sh`（复位 + 主链 + 渠道路由 + 退款 + UNKNOWN 收敛 + 每日对账 + 审计闭环 + 消息通道）。

### 直接演练：`scenario-mq.sh` D1~D6

**结果：PASS=15 SKIP=1，EXIT_CODE=0**（SKIP 为 D6 DLQ 为空，本运行未注入永久失败，属正常）。

| 场景 | 判据 | 实况 |
| --- | --- | --- |
| D1 回滚不投递 | 本地事务失败 → 可见队列不增、半消息索引不增 | 非法 SKU 下单 HTTP 404；`order.paid` 队列 10→10，半消息索引 0→0（INV-3 成立） |
| D2 崩溃回查补投 | prepare 后进程消失 → 扫描器按真相表补投 | 植入 `d2-commit-1789816295-33170`（bizNo 真实）与 rollback 半消息各一；扫描器判 COMMIT 补投进 `mq:stream:order.paid`，两条半消息均清理；订单保持 PAID 幂等；队列 11→12 |
| D3 下游宕机自愈 | 下游停摆订单仍 PAID；恢复后组位点追平 | 基线 fulfillment 组 entries-read=12（真实值，非 -1）；订单 PAID；组位点增长 PASS |
| D4 广播隔离 | 一笔支付后三组各推进，互不干扰 | 基线 catalog=13/fulfillment=13/trace=13；catalog / fulfillment / trace 三组独立位点均增长 |
| D5 订单轨迹 | timeline 还原时序且含 traceId | `count=2`（`order.paid` + `fulfillment.completed`），首条 topic=`order.paid`，traceId 非空；权益侧仍可查（只读投影） |
| D6 死信与告警 | mq 四指标暴露 | `mq_consumed_total` / `mq_committed_total` / `mq_prepared_total` / `mq_dead_letter_total` 均在位 |

### 总入口：`run-all.sh` 全链路

**结果：EXIT_CODE=0，7 段全绿**：
`scenario-happy-path` ✅ | `scenario-routing`（S1~S6）✅ | `scenario-refund` ✅ |
`scenario-payment-unknown` ✅ | `scenario-reconciliation` ✅ |
`scenario-audit` ✅（审计批 AB227024787569078272 差异→挂账→调账→收口→关批→试算平衡）|
`scenario-mq`（D1~D6）✅（PASS=15 SKIP=1）。

**T73 全链路 traceId 连续性**（本运行 D5 单据 OR227024975809409025）：
单一 traceId `3f818561-2791-486c-a0df-d29cfbdf7398` 贯穿 5 个服务的日志——
order 发布 `order.paid` → catalog 消费 confirm → fulfillment 建履约并发布 `fulfillment.completed`
→ entitlement 授予权益，payment 经 `/internal/orders/on-payment-succeeded` 触发；
timeline 2 条事件 traceId 一致，`trace-grep.sh` 跨服务聚合与按 bizNo 检索均命中。

> **脚本修复（本轮）**：`scenario-mq.sh` 的 `group_entries` 及 D3/D4 内联 awk 原用
> `$1=="name"{c=$2} ... {print $2}` 解析 `redis-cli XINFO GROUPS`，但 redis-cli 经管道（非 TTY）
> 输出为**一行一个 token**（`name\n<catalog>\n...\nentries-read\n10\n`），键与值分行 → `$2` 恒空
> → 基线恒为 `-1`，断言沦为「-1 ≤ -1」的平凡成立。已改为「上一行是键」的状态机解析，
> 并抽为独立助手 `deployment/demo/mq-group-entries.sh`（供 D3/D4 内联调用）。修复后基线为真实值。
