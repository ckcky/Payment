# Spec: 034-reliability-hardening（可靠性与故障恢复）

**Feature**：034　**标题**：Reliability & Failure Recovery
**版本**：v1.0（Draft，设计轮产物）　**日期**：2026-09-21
**状态**：🟡 **设计完成，待 Architecture Review 与负责人裁决**（本轮**只出设计，不改代码、不建 migration、不加测试、不引入依赖**）
**前置**：030（含 B7/C-18 在途守卫修复）已合入 master；031 的账务事件契约与待记账台账设计已定稿；032 的差异处置闭环已定稿
**输入权威**：[stage-design §6](../stage-design.md)、[design-review §11 §12.2 H5/H10/H12、§13 H13/H17/H18/H20](../design-review.md)

**阅读约定**：`【现状】`= 已存在的事实（附 `path:line`）；`【目标】`= 设计意图，**尚未实现**；
`【待确认】`= 属人类决策边界。

---

## 0. 定位与一句话目标

**给每一种失败一个有名字、有归属、有出口、有告警的收口路径。**

本 Spec 不新增失败类型，只做三件事：

1. **补出口**——今天有 5 类失败「只记指标、无人接管」（§1.1），本 Feature 给它们**载体 + 扫描器 + 人工队列**；
2. **收边界**——把「后置动作失败不得回滚前序事实」这条原则（INV-1/P4）落到**具体的事务边界**上（§1.2 的 C-19 是当前唯一的资金账可被静默少计的路径）；
3. **定政策**——重试/超时/UNKNOWN/补偿四张政策表（§5~§8），逐条回答
   **什么失败 / 谁重试 / 重试几次 / 多久一次 / 何时停止 / 停止后怎么办 / 最终谁负责**。

**明确不做**：不做「失败就重试」的通用重试器；不做自动终态化（违反 Constitution §V.7）；
不重设计 030 的渠道层、不重设计 029 的 Redis Streams MQ、不重设计 C-11 的 surplus 自动退款业务流程。

---

## 1. Background：证据化的现状

### 1.1 已有的恢复机制（**比一般印象多**，本 Feature 在其上扩展而非另起）

| # | 【现状】机制 | 参数 | 证据 |
|---|---|---|---|
| 1 | 支付超时扫描 → 推进 `UNKNOWN`（**不猜成败**） | `timeout=30s`，扫描 `10s` | `payment-service/.../reliability/ReliabilityConfig.java:18,21`、`TimeoutScanner.java:14,18,42` |
| 2 | UNKNOWN 主动查询收敛，**有界** | `queryMaxAttempts=5`，间隔 `15s` | `ReliabilityConfig.java:31,34`、`ChannelQueryService.java:27,92-115` |
| 3 | 请求内联重试（同 attempt 重放） | `retryMaxAttempts=3`，退避 `1s/2s/4s` | `ReliabilityConfig.java:24,27` |
| 4 | 查询耗尽**可发现**：`payment.query_exhausted` 指标 + **critical 告警** | — | `ChannelQueryService.java:115`、`deployment/prometheus/rules/payment-alerts.yml:37-41` |
| 5 | 人工收敛端点（payment + refund 双侧都有） | admin token 守卫 | `PaymentController.java:91-93`、`RefundController.java:40-42` |
| 6 | 终态吸收：迟到冲突结果不改资金事实 | — | `Payment.java:18,151`、`Refund.java:17,185`、`PaymentAttempt.java:249,270` |
| 7 | **补偿扫描器已有成型先例**：限额 `LimitCompensationScheduler` + `Scanner`（30s，扫终态支付补结算额度） | `30s` | `payment/limit/application/LimitCompensationScheduler.java:27`、`LimitCompensationScanner.java:114-117` |
| 8 | MQ：消费失败退避重试 → 超 `maxRetry=3` 进 DLQ；半消息回查超 `maxCheckTimes=10` 进 DLQ | `1s/2s/4s` | `common/common-redis-mq/.../MqProperties.java:33,39,45`、`StreamConsumer.java:162-204`、`HalfMessageScanner.java:22,120-122` |
| 9 | B7/C-18「在途退款区分重放/重试」**已修**，且**显式把后续项挂给本 Feature** | — | `order-service/.../TransactionApplicationService.java:187-224`（`order.refund_stranded_requested` 指标注释：「告警规则与自动补偿扫描器属后续 Feature，见 tasks Q1 / H17」） |

> **结论修正**：design-review 与 stage-design 若干处把恢复能力描述得过弱（「UNKNOWN 无出口」「失败只记指标」）。
> 真实情况是：**支付侧的 UNKNOWN 有界收敛 + 耗尽告警 + 人工端点已经闭环**；
> 缺口集中在**另外五处**（§1.2）与**跨进程一致性边界**（§1.3）。本 Spec 按真实缺口设计。

### 1.2 五个「有指标、无出口」的失败面

| # | 失败面 | 【现状】 | 证据 |
|---|---|---|---|
| **F-1** | **记账失败**：四个调用方的 ledger 出站失败只 `counter` + 日志，**无载体、无重放、无人工队列**；对账 `MISSING_POSTING` 是唯一兜底（事后发现） | `ledger.posting_failed` / `refund.ledger_posting_failed` 计数器 | `payment/infra/client/FeignLedgerPostingGateway.java:53`、`refund/infra/client/RefundFeignLedgerPostingGateway.java:58`、`refund/application/RefundResultProcessor.java:134` |
| **F-2** | **031 的 `pending_postings` 台账只设计未实现**（属 031 的 docs-only 产物），本 Feature 是它的**实现归属**（§9） | 全仓 `pending_postings` 命中仅在文档 | spec 031 §12、grep 结果 |
| **F-3** | **MQ DLQ 是只写不读**：`mq:dlq:{topic}` 有写入、**零消费者、零重放工具、零积压指标** | `MqKeys.java:20-21,45-46`、`TransactionalProducer.java:235-242`；全仓无 DLQ 读取方 | 见 §1.4#8 |
| **F-4** | **退款侧无超时/查询调度**：全仓 `@Scheduled` 5 处，payment 侧 3 处（timeout/query/limit）、order 1、audit 1、mq 1，**refund 域 0 处** ⇒ PMRF 停 `UNKNOWN` 无自动出口（只有人工 `resolve`） | `grep @Scheduled` 结果 | `RefundController.java:40-42` 为唯一出口 |
| **F-5** | **退款单卡 `REQUESTED`**：B7 修复后仍可被指标发现，但**无扫描器接管**（030 显式留给本 Feature） | `order.refund_stranded_requested` 无告警规则 | `TransactionApplicationService.java:222` + `payment-alerts.yml`（7 条规则无此项） |

### 1.3 跨进程一致性边界（本 Feature 唯一的「静默资金损失」级缺陷）

| # | 事实 | 证据 |
|---|---|---|
| 1 | **`onRefundResult` 无事务边界**：TXRF 终态落库（`:292`）与 `refunded_minor` 累加（`:314-315`）、订单状态推进（`:318-319`）、审计（`:353`）是**顺序执行的四次独立写**，方法上无 `@Transactional` | `order-service/.../TransactionApplicationService.java:280,292,314,318,353` |
| 2 | **崩溃窗口不可自愈**：`firstTerminal` 一旦为 `false`（重复通知）方法直接 `return`（`:293-296`）⇒ 若首调在 `:292` 之后、`:315` 之前崩溃，**重放通知会被自己的幂等守卫吞掉**，`refunded_minor` 永久少计 ⇒ `refundableMinor()` 虚高 ⇒ **潜在超退** | 同上 `:290-296,309-315` |
| 3 | 该缺陷即 design-review **C-19 / §12.2 H10**；加固方案（是否引入 `applied` 标记列）待 **§13 H18** 裁决 | design-review |
| 4 | **对照：MQ 分支的边界是对的**——`mq.publishRefundSucceeded` 在本地事务提交后调用，且回落路径把网关异常**吞成指标**（`:336-345`），符合 INV-1/P4 | `:323-346` |
| 5 | 订单取消后渠道**迟到成功**被 `CLOSED` 终态吸收，**不产生任何追回动作**（C-23 / H12）：与 F-3 同构——「事实被吸收了，但没人接管后果」 | `Payment.java:123`（CLOSED 吸收迟到回调）+ design-review C-23 |

### 1.4 Resilience4j 的实际使用面

| # | 事实 | 证据 |
|---|---|---|
| 1 | 依赖只在 payment-service 声明一次 | `payment-service/pom.xml:53` |
| 2 | 唯一配置是 `spring.cloud.openfeign.circuitbreaker.enabled: true`，**无任何熔断器实例配置**（无滑动窗口、无失败率阈值、无半开策略） | `payment-service/src/main/resources/application.yml:29-31` |
| 3 | 全仓**零** `@CircuitBreaker` / `@Retry` / `@TimeLimiter` / `@Bulkhead` 注解、**零** `CircuitBreakerRegistry` 使用 | grep 结果（design-review C-14 同一结论） |
| 4 | 真实弹性由**三套各自实现**承担，且都有测试：Feign `Retryer` 只对幂等只读 GET 开 3 次退避（`FactsClientConfig.java:43-44`）；写路径显式 `NEVER_RETRY`（`OutboundResilienceTest` 第 3 项断言）；应用层自有重试/查询政策（§1.1#1~#3）；MQ 自有退避（§1.1#8） | `reconciliation/infra/client/FactsClientConfig.java:43-71`、`settlement-service/src/test/.../OutboundResilienceTest.java:28-34` |

---

## 2. Goals / Non-Goals

### Goals

| # | 目标 | 完成判据 |
|---|---|---|
| **G1** | 失败分类学成型：每个失败面有 `归属组件 / 重试政策 / 停止条件 / 收口载体 / 最终负责人 / 指标 / 告警` 七列 | §4 表逐格无「—」 |
| **G2** | **C-19 关闭**：`onRefundResult` 的后置动作要么与终态同事务、要么可自愈重放，`refunded_minor` 不再可能永久少计 | §6.2 方案落地 + 崩溃窗口用例 |
| **G3** | **待记账台账落地**（031 §12 的设计由本 Feature 实现）：记账失败有载体、有退避重试、有耗尽人工态 | §9 |
| **G4** | **refund 侧对称补齐**超时/查询收敛（F-4），并让卡 `REQUESTED` 的退款单可被扫描发现（F-5） | §7.3 |
| **G5** | **DLQ 可读可重放可观测**（F-3），不改 MQ 协议 | §10 |
| **G6** | **Resilience4j 去留裁决并落地**（§1.4），消除 ADR-0021 与实现的冲突 | §11 |
| **G7** | UNKNOWN 老化可分桶、可告警、可进人工队列，**显式否决自动终态化** | §7 |
| **G8** | 取消后迟到成功有追回路径（C-23），**复用** order 既有 surplus 分支 | §6.3 |

### Non-Goals

- ❌ **不新增业务状态**（除 §9 台账的 `status` 与 §6.2 可能的一列标记，无其他新状态机；不引入 `ACCOUNTING_PENDING` 之类的 Payment 状态——见 §6.4 否决理由）。
- ❌ 不重设计 029 MQ（半消息协议、`XADD`/`XACK`/claim 语义、topic 划分全部不动）。
- ❌ 不重设计 030 渠道层（超时口径、模态还原、验签、回调链路不动）。
- ❌ 不重设计 C-11 surplus 自动退款（只在 §6.3 复用它作为追回出口）。
- ❌ 不做「超时即失败」的自动终态化（Constitution §V.7 红线，design-review H5 已显式否决）。
- ❌ 不引入分布式事务、事务消息新机制、Saga 框架、工作流引擎（Seata/Temporal/Camunda 一律不引）。
- ❌ 不做自动改单：恢复机制**永不**代替人判断资金成败（同 032 §17 对 Reconciliation 的禁令）。
- ❌ 不做通用重试平台（政策表 §5 是文档 + 配置，不是一个引擎）。

---

## 3. 设计原则：恢复的三条不变式

| # | 不变式 | 违反后果 |
|---|---|---|
| **R-1** | **前序事实永不因后置失败而回滚**（INV-1/P4） | 出现「支付成功被记账失败翻成失败」⇒ 用户钱没了订单还在 |
| **R-2** | **每个自动化出口必须收敛到「有界」+「可发现」**：任何重试/扫描都必须有 `(最大次数 or 最长窗口)` 与「耗尽后的显性化」（指标 + 告警 + 台账态） | 无限重试压垮下游；或静默停摆无人知（F-1/F-5 的病灶） |
| **R-3** | **自动化只能推进「已知事实」，不能推进「推测」**：UNKNOWN 的出口是查询/回调/人工裁定三选一，**永远不含超时猜测** | 违反 §V.7，产生错账且不可追溯 |

**这三条是本 Spec 所有具体设计的判据**，任何后续新增的恢复机制若违反任一条即被拒。

---

## 4. Failure Taxonomy（核心交付物）

> 七列填满。**Owner** = 承担该恢复职责的组件；**载体** = 失败可被查询到的地方（表/队列/端点）。

| # | 失败面 | 触发点 | Owner / 政策 | 停止条件 | 收口载体 | 最终负责人 | 指标 / 告警 |
|---|---|---|---|---|---|---|---|
| **X-1** | 渠道调用瞬时失败 | `PaymentApplicationService` 出站 | payment 内联重试（3 次，`1s/2s/4s`）【现状已有】 | 3 次耗尽 ⇒ 落 `FAILED` 或 `UNKNOWN`（保守） | `payment_attempts` 行 + `payment.retry_exhausted` | oncall（告警） | 【已有】`PaymentRetryExhausted` warning |
| **X-2** | 渠道无响应（超时） | `TimeoutScanner` 30s | payment 推进 `UNKNOWN`【已有】 | 不「停」，转 X-3 | `UNKNOWN` 状态本身 | 系统 | 【已有】`PaymentUnknownBacklog` |
| **X-3** | UNKNOWN 待收敛 | `ChannelQueryScheduler` 15s | payment 主动查询，**5 次**【已有】 | `queryMaxAttempts` 耗尽 | 人工 resolve 端点【已有】+ **老化桶**【目标】 | **人工**（oncall → 财务/渠道方） | 【已有】critical + 【目标】`payment.unknown_age` 分桶 |
| **X-4** | 回调丢失 | — | **不依赖回调**：X-2/X-3 主动查询是主路（架构性保证）【已有】 | 同 X-3 | 同 X-3 | 人工 | 同 X-3 |
| **X-5** | 重复回调 / 迟到冲突结果 | 状态机 | 终态吸收（幂等）【已有】 | N/A | 日志 + `refund.duplicate_result`【已有】 | 无需人 | — |
| **X-6** | **记账 RPC 失败** | 四调用方 ledger 出站 | 【目标】调用方落 `pending_postings` 台账 + 退避重试（§9） | 重试 `N=5` 次 ⇒ `ABANDONED` | 【目标】`pending_postings`（调用方 schema）+ ledger `GET /postings` 回查 | **账务负责人/人工** | 【目标】`ledger.posting_pending` / `ledger.posting_abandoned`（critical） |
| **X-7** | 记账半套（分录落了一部分） | Ledger 内部 | **不可能**：031 §10 同事务写入 + 构造期平衡校验 | N/A | 031 试算平衡 `BALANCE_BREAK` | 账务 | 031 §10 |
| **X-8** | 退款后置累加崩溃（C-19） | `onRefundResult` | 【目标】§6.2 事务边界改造 | N/A（同事务 ⇒ 要么全成要么全未发生） | `transaction_refunds`（+ 可能 `applied` 列，待 H-034-2） | 无需人（重放自愈） | 【目标】`order.refund_post_action_retry` |
| **X-9** | 退款 PMRF 停 `UNKNOWN` | 【目标】refund 侧查询调度（§7.3） | 政策同 X-3（复用 `queryMaxAttempts`） | 5 次耗尽 | 人工 `resolveRefund`【已有】 | 人工 | 【目标】`refund.unknown_age` |
| **X-10** | 退款单卡 `REQUESTED`（未被渠道受理） | 【目标】order 侧在途扫描（§7.3） | 扫描 ⇒ 重放 `doCreateRefund` 重试分支【已有能力】 | 重放 ≤2 次后转人工 | 【目标】审计记录 + 队列视图 | 人工 | 【目标】`order.refund_stranded_requested` **+ 告警（030 已留口）** |
| **X-11** | 订单已取消但渠道迟到成功（C-23） | `PaymentResultApplier` 吸收分支 | 【目标】补发「渠道已收款」信号驱动 order surplus【已有出口】 | N/A（一次性） | 【目标】`order.surplus_payment{cause}` 新枚举值 | 人工（若 surplus 退款失败则并入 X-9） | 【目标】`payment.late_success_on_closed` |
| **X-12** | MQ 消费失败 | `StreamConsumer` | 退避 3 次 ⇒ DLQ【已有】 | `maxRetry=3` | 【目标】DLQ 可读 + 重放工具（§10） | 人工 | 【目标】`mq_dlq_size` gauge + 告警 |
| **X-13** | MQ 半消息超次未决 | `HalfMessageScanner` | 回查 ≤10 次 ⇒ DLQ【已有】 | `maxCheckTimes=10` | 同 X-12 | 人工 | 同 X-12 |
| **X-14** | 结算批次执行失败 / 停 `UNKNOWN` | `SettlementApplicationService` | **不自动重跑**（模拟出款强制 UNKNOWN，ADR 口径），需人确认后用同 `batchNo` 重放【已有幂等键】 | 无自动重试 | 批次行 + 031 §11 期间门禁 | 财务人工 | 【目标】`settlement.pending_age`（035 落告警） |
| **X-15** | 结算记账失败 | settlement ledger 出站 | 同 X-6（台账落 settlement schema） | 同 X-6 | `pending_postings` | 账务 | 同 X-6 |
| **X-16** | 对账差异未收口 | 032 差异生命周期 | 032 的 5 态处置流程（**不在本 Feature 重复设计**） | 关账门禁（031 §11）阻断 | `reconciliation_differences` / `audit_*` | 财务人工 | 032 §12 |
| **X-17** | 下游服务不可用（Feign 连接失败） | 各出站 | 只对**幂等只读**重试（已有）；写操作**绝不盲重试**（`NEVER_RETRY`，`OutboundResilienceTest` 已钉死） | 3 次 | 调用方台账（若涉资金 ⇒ X-6） | 视调用而定 | 【已有】4xx/5xx 指标 |

**这张表如何回答 brief 的七问**（以 X-6 为例）：
什么失败 = 记账 RPC 非 2xx/超时；谁重试 = **调用方**的 `LedgerPostingGateway`（不是 Ledger 自己）；
重试几次 = 5；多久 = 退避 `1s/5s/30s/2m/10m`；何时停止 = 第 5 次后置 `ABANDONED`；
停止后怎么办 = 保留台账 + critical 告警 + 人工触发重放；最终谁负责 = **账务负责人**（对账 `MISSING_POSTING` 是最后防线）。

---

## 5. Retry Policy（谁能重试、按什么退避）

> 【目标】政策表**不引入新配置族**：新增项一律落在既有前缀（`payment.reliability.*` / `payment.mq.*`）或台账表自身的列上。

| 调用类别 | 幂等性 | 重试 | 退避 | 归属实现 |
|---|---|---|---|---|
| 渠道**下单/扣款** | 靠 `paymentNo` + attempt 复用 | **请求内联 ≤3**（同 attempt 重放），超出即 `UNKNOWN` 保守 | `1s/2s/4s`【已有】 | `PaymentRetryService` |
| 渠道**查询** | 天然幂等 | **周期 ≤5** | `15s`【已有】 | `ChannelQueryScheduler` |
| 渠道**退款执行** | 靠 PMRF/TXRF 同号重放（B7 已修） | 只在「未受理」分支重放 | 人工/扫描触发 | §7.3 |
| ledger 记账 POST | 靠 `{eventType}:{sourceId}`（031 §9） | **5 次退避**【目标】 | `1s/5s/30s/2m/10m`【目标】 | §9 台账 |
| 只读事实 GET | 幂等 | 3 次【已有】 | `1s/2s/4s`【已有】 | `FactsClientConfig` |
| **非幂等写 POST（结算/出款/通知）** | — | **绝不自动重试**（`NEVER_RETRY`）【已有纪律】 | — | 人工确认 |
| MQ 消费 | 靠消费端幂等 | 3 次【已有】 | `1s/2s/4s`【已有】 | `StreamConsumer` |
| HTTP 入口（用户点击） | — | **不由服务端重试** | — | 前端/调用方 |

**红线**：
- MUST NOT 对「语义未知的写操作」做自动重试（超时后不知是否生效 ⇒ 走 X-2/X-3 查询收敛，不是重发）；
- MUST NOT 给熔断器配「自动降级返回成功」——资金路径无「降级」概念。

---

## 6. 状态推进与事务边界（C-19 / C-23 专项）

### 6.1 现状问题复述（一句话）

`onRefundResult` 用**自己的终态幂等守卫**保护了「不重复累加」，却也关掉了「累加失败后可重来」的门——
`TransactionApplicationService.java:292` 与 `:315` 之间崩溃 ⇒ 永久少计。

### 6.2 目标方案（两案择一，取 A）

| 方案 | 内容 | 判定 |
|---|---|---|
| **A 同事务收口（推荐）** | 把「TXRF 终态迁移 + `refunded_minor` 累加 + `order.applyRefund`」纳入**同一个本地事务**（同库同服务，`order` schema 内三张表，天然可事务）；**MQ publish / 跨服务网关调用全部移出事务**（提交后执行，异常吞成指标——即 `:323-346` 已有的正确形态） | ✅ 构造性消除窗口；改动局限一个方法；无 schema 变更 |
| **B `applied` 标记列** | 加列区分「终态已落 / 后置已应用」，重放时对 `terminal && !applied` 继续应用 | ⚠️ 需要 schema 变更 ⇒ **属人类决策 H-034-2**（= design-review §13 H18） |

**裁决要点（A 的边界纪律，写死）**：

- 事务内 **MUST NOT** 出现任何 Feign 调用 / Redis 写入 / MQ publish（Constitution §III；防「事务内发消息」重演）；
- `catalogRestock` / `fulfillmentGateway.onRefund` 等跨服务动作保持「提交后 + 失败吞成指标 + 可重放」语义（现状即对，`:336-345`）；
- 审计日志（`:353`）随事务提交后写，失败不得回滚资金累加（同 `LedgerPostingGateway` 的取舍）；
- 重放语义不变：`firstTerminal=false` 时**仍然直接返回**——因为 A 方案下「终态与累加同生同灭」，`false` 就必然意味着「已完整应用」。

> 【目标】回归用例（归 034 自己交付，见 §13）：注入「累加后抛异常」⇒ 断言 TXRF 仍非终态
> 且 `refunded_minor` 未变；再次通知 ⇒ 完整应用且只应用一次。此用例 MUST 落 **L2b 真库**
> （H2 的自动提交语义证不了回滚，见 [spec 033 §3 升级判据](../033-test-infrastructure/spec.md)）。

### 6.3 订单取消后迟到成功的追回（C-23 / H12）

【目标】最小改动，**只加一个信号，不加新链路**：

```text
PaymentResultApplier / PaymentResultProcessor：
  收到渠道 SUCCESS，但 Payment 处于 CLOSED（终态吸收，返回 false）
    → 【新增】metrics.counter("payment.late_success_on_closed", cause="CANCELLED")
    → 【新增】向 order 发「渠道已收款」通知（复用既有 PaymentSucceeded 语义 + 显式标记 late=true）
      → order 既有 surplus 分支（TransactionApplicationService:110 ORDER_NOT_PAYABLE）
        → 既有 surplusRefund 自动原路退回
```

**约束**（防止误用 surplus 机制）：

- MUST NOT 复活已 `CLOSED` 的 Payment（吸收规则不变，R-3）；追回是**新的退款事实**，不是状态回退；
- MUST NOT 新增「禁止多 Payment SUCCESS」或「换渠道前关闭旧 Payment」的任何约束（design-review §14.3 明令禁止）；
- 通知失败 ⇒ 落 §9 台账（同 X-6 模式），并保留 `MISSING_*` 对账兜底；
- 【待确认 **H-034-3**】跨服务通知语义是否复用 `payment.succeeded`（= design-review §13 H20）：
  复用则消息契约需加 `late` 标志（非破坏性加字段）；新增独立 topic 则改 MQ 拓扑（**不推荐**，违反「不重设计 029」）。

### 6.4 显式否决：不给 Payment 加 `ACCOUNTING_PENDING` 状态

brief §8.5 提到「Payment SUCCESS → Accounting Pending → Posted」的形态。**本 Spec 否决在 Payment 上表达它**，理由：

1. Payment 是**支付事实**聚合，记账是**下游派生事实**——让支付状态承载账务进度是跨域状态双主（违反 §11 State Ownership）；
2. 账务进度已有正确归属：`pending_postings`（调用方台账）+ Ledger 侧 `GET /postings` 回查（031 §12）；
3. 加状态 ⇒ `SUCCEEDED→ACCOUNTING_PENDING→POSTED` 全部状态机、指标、快照、E2E 断言跟着改，**成本大且无新增保证**（资金事实早已是 SUCCESS，不因账务而变）；
4. Constitution §V.7 与 brief §8.5「不要为了理论增加大量状态」同一取向。

> 若后续审计/合规要求「未入账的成功支付不得计入可用余额」，正确表达位置是 **Ledger 侧的 `SUSPENSE` 科目 + 期间门禁**（031 §7.6/§11），不是 Payment 状态。

---

## 7. UNKNOWN Policy 与 Query Policy

### 7.1 出口枚举（穷尽，且**只有这三个**）

| 出口 | 触发 | 谁执行 | 是否可能猜 |
|---|---|---|---|
| **主动查询** | 周期调度 | `ChannelQueryService`【已有】/ refund 侧【目标 §7.3】 | ❌ 渠道权威答复 |
| **渠道回调/通知** | 异步到达 | `PaymentCallbackService` / `AlipayNotifyController`【已有】 | ❌ |
| **人工裁定** | `POST /{ref}/resolve`【已有】 | 人工（admin token） | 人可，系统不可 |
| ~~超时自动置 FAILED~~ | — | — | **❌ 永久否决**（§V.7；design-review H5 显式否决项） |

### 7.2 老化与升级（G7）

【目标】UNKNOWN 不再是一个平面状态，而是**带年龄的待办**：

| 桶 | 阈值 | 动作 | 告警 |
|---|---|---|---|
| `0–5m` | 查询窗口内（5×15s） | 正常收敛 | — |
| `5–30m` | 查询已耗尽 | `payment.unknown_age{bucket="query_exhausted"}` | warning（已有 `PaymentQueryExhausted`） |
| `30m–24h` | 跨日 | 进人工队列视图 `GET /internal/payments/unknown?age=` | critical |
| `>24h` | 跨期风险 | 强制登记 + **032 对账兜底** + 关账门禁受影响 | critical + 升级账务 |

**实现约束**：分桶 MUST 由**扫描器周期计算**（`findByStatus(UNKNOWN)` + `posted/updated_at` 差值），
MUST NOT 引入新列（`updated_at` 足够）；`payment.unknown_age` 的 label 只有 `bucket` 与 `module`，
**MUST NOT** 带 `paymentNo`（035 高基数政策）。

### 7.3 refund 侧对称补齐（F-4 / F-5）

【目标】新增两个扫描器，**完全复用 payment 侧政策参数**，不新造配置：

| 扫描器 | 归属 | 扫什么 | 动作 | 政策来源 |
|---|---|---|---|---|
| `RefundUnknownQueryScheduler` | payment-refund 域 | `refunds.status=UNKNOWN` 且 attempt 未超次 | 按 `attempt` 记录的**渠道 + 模态 + 渠道退款流水号**查权威状态 | 复用 `ReliabilityConfig.queryMaxAttempts/queryIntervalMs` |
| `StrandedRefundOrderScanner` | order 域 | TXRF `REQUESTED` 且 `paymentRefundNo=null` 且 `updated_at` 超阈值 | 重放 `doCreateRefund` 的**②重试分支**（B7 已备好） | 新增阈值 `order.refund.stranded-threshold`（默认对齐 30s 超时口径） |

**红线**：
- 扫描器 MUST NOT 自行判定退款成败（R-3），只能触发查询或重放既有受理逻辑；
- `StrandedRefundOrderScanner` 的每次重放 MUST 被 `RefundPolicy` 累计上限校验挡住（030 已确立「最后防线」口径，见 `TransactionApplicationService.java:202-205` 注释）；
- 重放次数 MUST 有界（≤2），耗尽后 `ABANDONED`-等价的人工登记（审计 `FINANCIAL_AUDIT`，030 已要求）。

---

## 8. Timeout Policy（三档并存，需显式文档化）

| 档 | 值 | 适用 | 证据 | 关系约束 |
|---|---|---|---|---|
| 服务间 RPC | connect/read `1s` | 所有 Feign 出站 | `payment-service/src/main/resources/application.yml:26-27` | — |
| 出站 HTTP（渠道/外部） | `1.5s` | 内部 mock 渠道 | 同上 `:43-44`（ADR-0012 口径） | `<` RPC 预算的 2 跳 |
| 支付超时判定 | `30s` | `TimeoutScanner` 判 UNKNOWN | `ReliabilityConfig.java:18` | **MUST >** 任何单次渠道调用 |
| 沙箱渠道 HTTP | `10s`（独立） | 支付宝沙箱 | spec 030 FR-045 | **MUST <** `30s`（否则「还在等渠道已被判 UNKNOWN」） |
| MQ 阻塞读 | `2s` | `StreamConsumer` | `MqProperties.java:27` | — |
| 半消息回查 | `30s` / ≤10 次 | `HalfMessageScanner` | `MqProperties.java:36,39` | — |
| 优雅停机 | `30s` | 排水 | `application.yml:34-35` | — |

【目标】本表进入 `technical-solution.md` 的非功能章节（L0），并在 `application.yml` 相应项加一行 WHY 注释——
**这是 034 唯一允许的「配置说明」性改动，不改值**（值调整属 H-034-4）。

---

## 9. Ledger Recovery：待记账台账落地（031 §12 的实现归属）

> 031 已完成 `pending_postings` 的**设计**（[spec 031 §12](../031-ledger-accounting-foundation/spec.md)），
> 本轮**未实现**（§1.2 F-2 已核实）。本 Feature 是它的**实现归属**，且**不改设计**——沿用 031 定的表结构、
> 键语义与「调用方侧轻量台账」裁决。

### 9.1 落点与写入方

| 项 | 内容 |
|---|---|
| 表 | `pending_postings`，落在**各调用方自己的 schema**（payment / settlement / reconciliation 三处），不放 ledger 库 | 理由：Ledger 不可用时写 Ledger 自己没意义（031 §12 原文裁决） |
| 写入方 | 四个 `LedgerPostingGateway` 出站组件的失败分支（**替换**今天「只 counter」） | `FeignLedgerPostingGateway.java:53` 等 4 处 |
| 键 | `UNIQUE(event_type, source_id)` ⇒ 同一事实反复失败只留一行，`retry_count` 递增 | 031 §12 |
| 重放 | `PostingRetryScheduler`（各调用方一个，参数对齐 `payment.reliability.*` 风格）⇒ 成功置 `REPOSTED`，耗尽置 `ABANDONED` | §5 X-6 退避 |
| 幂等安全 | Ledger 的 `{eventType}:{sourceId}` 派生键保证重放不双记（031 §9） | 台账可重试的**前提** |
| 人工入口 | `GET /internal/{service}/pending-postings?status=` + `POST .../replay`（admin token，与 `resolve` 同守卫风格） | 复用 §1.1#5 先例 |
| 最后防线 | 032 的 `MISSING_POSTING`（BLOCKER）审计不变——台账是**辅助补偿**，不是资金事实源 | 031 §12 红线 |

### 9.2 推广：统一「后置 RPC 失败台账」模式（M7）

【目标】同一张表的形状（`event_type / source_type / source_id / idempotency_key / payload_json / fail_reason / retry_count / status`）
泛化为**出站失败台账**，覆盖记账以外的后置调用：

| 用法 | 今天 | 【目标】 |
|---|---|---|
| 记账 | counter only（X-6） | 台账 + 重试 |
| 支付成功通知 order | counter | 台账 + 重试（`payment.succeeded` 侧） |
| 退款结果通知 order | `refund.order_notify_failed` counter（`RefundResultProcessor.java:159-162`） | 台账 + 重试 |
| 结算记账 | counter | 台账 + 重试 |

**纪律（防「台账万能」）**：
- 台账 MUST NOT 被当业务事实源（红线沿用 031 §12）；
- 台账行 MUST 有终止态（不允许无限 `PENDING`）；
- 表结构复用 ⇒ **不为每种失败新建一张表**（brief §12 反过度设计）。

---

## 10. MQ Recovery（基于既有实现，不改协议）

| 能力 | 【现状】 | 【目标】 |
|---|---|---|
| 生产半消息回查 | ✅ `HalfMessageScanner`（5s，≤10 次） | 不动 |
| 消费退避重试 | ✅ 3 次 `1s/2s/4s` | 不动 |
| 卡死消息接管 | ✅ `claimStale`（`minIdleMs=60s`） | 不动 |
| 死信投递 | ✅ `mq:dlq:{topic}` | 不动 |
| **死信可见性** | ❌ 无任何读取/统计 | 【目标】`GET /internal/mq/dlq?topic=`（运维端点，读 `XRANGE`）+ `mq_dlq_size` gauge |
| **死信重放** | ❌ 无 | 【目标】`POST /internal/mq/dlq/{topic}/{entryId}/replay`：把 DLQ 条目按原 envelope **重新投递到主 stream**（不跳过消费端幂等） |
| **积压可观测** | ❌ 无 pending 统计 | 【目标】`mq_pending_count{topic,group}`（`XPENDING` summary）→ 归 035 指标目录 |

**红线**：重放 MUST 依赖消费端幂等（既有 `msgId` + 业务键）；MUST NOT 引入「重放标记」绕过幂等；
MUST NOT 改 `MqKeys` 前缀或 envelope 形状（029 协议稳定）。

---

## 11. State Ownership（恢复视角的审计）

| 状态 | Source of Truth | 谁可推进 | 谁只能观察 | 谁只能发 Command |
|---|---|---|---|---|
| Payment 支付事实 | `payment-service` / `payments` | 仅 payment（渠道权威答复 + 人工 resolve） | order / ledger / reconciliation | 人工 resolve、扫描器（仅触发查询） |
| PaymentAttempt 渠道层事实 | payment channelAttempt 层 | 仅 `ChannelAttemptRecorder` | 同上 | — |
| TXRF 交易层退款单 | `order-service` / `transaction_refunds` | 仅 order | payment 只回执 | `StrandedRefundOrderScanner`（触发重放，不写结论） |
| PMRF 支付层退款 | `payment-service` / `refunds` | 仅 payment | order | 扫描器（触发查询） |
| `refunded_minor`（交易已退额） | `order` / `transactions` | 仅 order，且与 TXRF 终态**同事务**（§6.2A） | payment | — |
| LedgerTransaction / LedgerEntry | `ledger-service` | 仅 Ledger（PostingEngine + 规则） | 四调用方 | 调用方**只发 Accounting Event** |
| 余额投影 | Ledger（分录派生） | Ledger（同事务累加 / rebuild） | 所有人 | 管理端 rebuild |
| 记账待办 | **调用方** `pending_postings` | 调用方 | Ledger（只回查） | 人工 replay |
| 对账差异 | `reconciliation-service` | 仅 reconciliation（032 生命周期） | settlement（只读门禁） | **MUST NOT 触碰 LedgerEntry**（031/032 双禁令） |
| 结算批次 | `settlement-service` | 仅 settlement | — | 人工重放（同 `batchNo`） |
| MQ 死信 | Redis（运维视图） | 消费框架 | 运维端点 | 人工 replay |

**双主检查结论**：§6.2 的 A 方案消除的是**唯一**一处「同一资金事实由两次独立写决定」的边界；
其余状态归属在 030/031/032 已理清，本 Feature 不新增任何第二权威。

---

## 12. Recovery Sequence（两个代表性时序）

### 12.1 记账失败到收口（X-6）

```text
T0  Payment 落库 SUCCEEDED（事实已定，INV-1）
T0  Gateway.post(event) → Feign 5xx/timeout
      ├─ metrics: ledger.posting_failed            【已有】
      └─ upsert pending_postings{PENDING, retry=0, fail_reason}   【新增】
T+1s..T+10m  PostingRetryScheduler：取 PENDING 且到点 → 重投
      ├─ Ledger 派生键 {eventType}:{sourceId} ⇒ 重放不双记（031 §9）
      ├─ 成功 → status=REPOSTED（终态）
      └─ 第 5 次失败 → status=ABANDONED（终态）+ critical 告警
T+?  人工：GET pending-postings?status=ABANDONED → 排查 → POST replay（重置 retry_count，人工可追溯）
兜底 032 对账：MISSING_POSTING（BLOCKER）仍在——台账不是最后防线，审计才是
```

### 12.2 退款后置累加崩溃（X-8，A 方案后）

```text
通知到达 onRefundResult
  └─ @Transactional(本地) { TXRF 终态迁移 ; refunded_minor 累加 ; order.applyRefund }
        任一失败 ⇒ 全部回滚：TXRF 仍 PROCESSING/REQUESTED
  └─ 事务提交后：MQ publish / 跨服务通知（失败吞成指标 + §9.2 台账）
重复通知：firstTerminal=false → 直接返回（此时必然已完整应用，窗口被构造性消除）
崩溃后自愈：下游重投通知 → 走完整流程（不再被自己的守卫吞掉）
```

---

## 13. Testing Requirements（034 自己交付的用例，载体归 033）

| # | 用例 | 层（[spec 033](../033-test-infrastructure/spec.md)） |
|---|---|---|
| TT-1 | §6.2 崩溃窗口：累加前注入异常 ⇒ 全回滚；重投 ⇒ 完整且仅一次应用 | **L2b 真库** |
| TT-2 | 台账唯一键：同一事件反复失败 ⇒ 1 行、`retry_count` 递增 | **L2b** |
| TT-3 | 台账重放幂等：5 次重投 ⇒ Ledger 只 1 套分录 | L2a + L2b |
| TT-4 | `ABANDONED` 后人工 replay 可再成功，且不产生第二事实 | L2a |
| TT-5 | UNKNOWN 老化分桶正确（含跨日边界、时区口径） | L2a |
| TT-6 | **永不自动终态化**：无任何路径使 UNKNOWN 在无权威答复下变 SUCCESS/FAILED（静态 + 行为双断言） | L1 + **L5** |
| TT-7 | refund 查询扫描：PMRF UNKNOWN → 查询 → SUCCESS/FAILED/仍 UNKNOWN 三路 | L2a |
| TT-8 | 卡 `REQUESTED` 扫描：重放后渠道请求次数 == 2（首次 + 一次重试），且 `RefundPolicy` 挡住超发 | L2a |
| TT-9 | 取消后迟到成功 ⇒ 恰好一次 surplus 自动退款；CLOSED Payment 状态不变 | L2a + L4 |
| TT-10 | DLQ：超次进 DLQ；重放后被正常消费且幂等（不双处理） | L2a + L4 |
| TT-11 | 结算同 `batchNo` 重放不重复结算、不重复记账 | L2a + **L2b** |
| TT-12 | 熔断去留后行为回归：§11 的出站行为与移除前一致（或明确差异被记录） | L3 快照 |

---

## 14. 交付切片

| 切片 | 内容 | 依赖 | 备注 |
|---|---|---|---|
| **034-A** | §6.2 事务边界改造（C-19）+ TT-1 | H-034-2（若选 B 才需裁决；A 方案零 schema 变更可先做） | **最高优先**（唯一静默资金损失项） |
| **034-B** | §9 台账落地（含推广 M7）+ TT-2~4 | 031 实现 | 关闭 F-1/F-2 |
| **034-C** | §7.3 refund 侧扫描器 + §7.2 老化 + TT-5~8 | 无（B 可选前置，非必需） | 关闭 F-4/F-5 |
| **034-D** | §6.3 迟到成功追回 + TT-9 | **H-034-3** | 关闭 C-23 |
| **034-E** | §10 MQ DLQ 读/重放/指标 + TT-10 | 无 | 关闭 F-3 |
| **034-F** | §11 Resilience4j 裁决落地 + TT-12 | **H-034-1** | 独立、纯配置/依赖 |

---

## 15. Human Decisions Required

| # | 决策项 | 边界类型 | 出处 | 推荐 |
|---|---|---|---|---|
| **H-034-1** | Resilience4j **去**（移除依赖与 `circuitbreaker.enabled`）/ **留**（补齐具名熔断器配置 + 阈值 + ADR 支撑） | 架构取舍 / 依赖变更 | §1.4、design-review C-14、§13 H13、backlog #5 | **去**——零注解零配置 ⇒ 它今天唯一实际作用是「让 Feign 异常包装路径变一层」；熔断参数无数据支撑即拍脑袋；将来若真需熔断，针对具体调用点独立立项 |
| **H-034-2** | C-19 加固取 A（同事务，无 schema 变更）还是 B（加 `applied` 列） | 数据库结构变更（仅 B） | design-review §13 H18 | **A**（B 只有在跨库后置动作出现时才有必要） |
| **H-034-3** | 取消后迟到成功的跨服务通知：**复用** `payment.succeeded` 语义（加 `late` 标志）还是新增独立事件 | 跨服务通知语义变更 | design-review §13 H20、§6.3 | **复用**（新 topic 违反「不重设计 029」） |
| **H-034-4** | `payment.unknown_age` 各桶阈值与 `pending_postings` 重试次数/退避序列 | 告警与运营口径 | §7.2、§5 | 按本表默认值起，随 035 的 SLO 目标一并确认（H-035-2） |
| **H-034-5** | 卡 `REQUESTED` 的自动重放是否上线（vs 仅告警 + 人工） | 资金路径行为 | design-review §13 H17 | **自动重放但严格有界（≤2）**，且以 `RefundPolicy` 为最后防线；030 已备好可重放分支，留人工只是把已修的缺陷又拖一遍 |

**最小裁决集（进入实现前必须有结论）**：**H-034-1、H-034-2、H-034-5**（三项分别决定依赖、schema、资金路径行为）。

---

## 16. Risks & Mitigations

| # | 风险 | 后果 | 缓解 |
|---|---|---|---|
| 1 | §6.2 事务边界改动把远程调用误留事务内 | 「事务内发消息」重演，长事务 + 假失败 | 事务内白名单静态检查（L5 规则：`@Transactional` 方法内不得调用 `*Gateway`/MQ），列进 033 §7 的演进项 |
| 2 | 扫描器叠加导致重试风暴（5 类扫描器同扫一张表） | 下游被自己打爆 | 每类扫描器**独立周期 + 独立上限**，且都走 §5 政策表；台账 `retry_count` 是唯一计数器，不允许两处各自递增 |
| 3 | 台账被误用为「资金事实源」 | 双主、审计失真 | §9.2 红线 + 031 §12 原文禁令 + 文档同步到 L0 |
| 4 | DLQ 重放产生二次副作用 | 资金重复 | 消费端幂等是唯一闸门，重放 MUST 走原 `msgId` 且测试 TT-10 钉死 |
| 5 | 移除 Resilience4j 改变异常包装行为 | 隐性 500 语义变化 | TT-12 契约快照回归；先只关配置（`enabled=false`）再删依赖，分两步可回滚 |
| 6 | 老化分桶按 `updated_at` 计算，被无关更新刷新 | 年龄失真、告警漏报 | 明确口径：以 `UNKNOWN` **首次进入**时间（`postings`/`payments` 的状态时间戳），文档写死并在 TT-5 断言 |

---

## 17. Acceptance Criteria

| # | 判据 |
|---|---|
| AC-1 | §4 分类表每一行七列填满，无「待定」 |
| AC-2 | C-19 崩溃窗口在真库用例下不可复现（TT-1），且无新增业务状态 |
| AC-3 | 五个「只记指标」的失败面（F-1~F-5）各自有载体 + 出口 + 告警 |
| AC-4 | UNKNOWN 出口穷尽为三个，且「自动终态化」被静态 + 行为双证否决（TT-6） |
| AC-5 | §11 归属表无第二权威；台账/投影/差异的 Source of Truth 与各域一致，无相互改写 |
| AC-6 | MQ 协议（`MqKeys` / envelope / 半消息）与 029 完全一致；渠道层与 030 完全一致；surplus 业务流程未被改动 |
| AC-7 | 未新增中间件、未新增服务、未新增运行时依赖（H-034-1 若取「留」，则新增的只是**配置**，仍无新依赖） |
| AC-8 | 每项政策都能回答七问（什么失败/谁重试/几次/多久/何时停/停后怎样/谁负责），抽查 3 条由 reviewer 现场提问 |

---

## 18. Out of Scope

- ❌ 熔断/降级的**具体阈值调优**与自适应并发控制（Bulkhead/Rate Limiter 平台建设；027 限额与入口限流不动）。
- ❌ 分布式事务 / 事务消息新机制 / Saga 框架 / 工作流引擎。
- ❌ 自动改单、自动判定资金成败（同 032 禁令）。
- ❌ Payment / Refund / Transaction 状态机变更（除 §9 台账与 §6.2 可能的一列标记外，零状态与 schema 变更）。
- ❌ 重设计渠道超时语义（030 三档口径不动，§8 只做文档化）。
- ❌ 重设计 MQ 拓扑、topic 划分、消费组语义。
- ❌ 真实出款的重试策略（结算仍强制 UNKNOWN，不真实出款）。
- ❌ 容量/故障演练平台、混沌工程工具链。
- ❌ 对账差异处置流程（属 032）、账务规则与科目（属 031）、指标与告警落地形态（属 035）。
