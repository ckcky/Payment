<a id="adr-0082"></a>

# ADR-0082：失败恢复归属与补偿边界——出站失败台账 + 后置动作事务化 + Resilience4j 移除（Feature 034）

> 承载 [spec 034-reliability-hardening](../specs/stage-05-channel-and-finance-deepening/034-reliability-hardening/spec.md) 的四条决策。
> 状态：**🟢 Accepted（2026-09-21 负责人裁决，按 spec 推荐方案批准）**——涉及 Constitution §Governance
> 「人类决策边界」（资金路径行为变更、依赖增删、跨服务通知语义），
> 裁决记录见 spec 034 §16 与本日 CHANGELOG。

---

## Context

走查（spec 034 §1 证据）先**修正一处被低估的现状**：支付侧的恢复机制其实已相当完整——
超时转 UNKNOWN（30s/10s 扫描）、UNKNOWN 有界主动查询（5 次 / 15s）、查询耗尽有 `payment.query_exhausted`
critical 告警、payment 与 refund 双侧都有人工 `resolve` 端点、MQ 有半消息回查与 DLQ，
且 **027 已经给出一个成熟的补偿扫描器先例**（`LimitCompensationScheduler` + `Scanner`，30s）。

真正的缺口集中在**五个「有指标、无出口」的失败面**与**一处静默资金损失**：

| # | 缺口 | 证据 |
|---|---|---|
| F-1 | 记账 RPC 失败只 `counter`，**无载体、无重放、无人工队列**（4 个调用方各自如此） | `FeignLedgerPostingGateway.java:53`、`RefundFeignLedgerPostingGateway.java:58`、`RefundResultProcessor.java:134` |
| F-2 | 031 §12 的 `pending_postings` 台账**只设计未实现** | 全仓命中仅在文档 |
| F-3 | MQ **DLQ 只写不读**：无消费者、无重放工具、无积压指标 | `MqKeys.java:20-21`、`TransactionalProducer.java:235-242`；全仓无读取方 |
| F-4 | **refund 域零调度器**：PMRF 停 UNKNOWN 只有人工出口 | `grep @Scheduled` 全仓 6 处，refund 0 处 |
| F-5 | 退款单卡 `REQUESTED` 已可被指标发现，但 030 **显式把扫描器留给后续 Feature**（即本 Feature） | `TransactionApplicationService.java:220-222` 注释 |
| C-19 | `onRefundResult` 无事务边界：TXRF 终态落库（`:292`）与 `refunded_minor` 累加（`:314`）分作两次独立写，中间崩溃后**重复通知会被自己的幂等守卫吞掉**（`:293-296`）⇒ 已退额永久少计 ⇒ `refundableMinor()` 虚高 ⇒ 潜在超退 | 同左 |
| C-14 | Resilience4j 只有依赖 + 一行 `circuitbreaker.enabled: true`，**零注解、零阈值配置**，与 ADR-0021「当前不引入熔断」冲突且无 ADR 支撑 | `payment-service/pom.xml:53`、`application.yml:29-31` |

---

## Decision

### 决策 1：恢复的三条不变式（本 Feature 所有设计的判据）

- **R-1 前序事实永不因后置失败回滚**（INV-1/P4 的重述）；
- **R-2 每个自动化出口必须「有界」+「可发现」**：任何重试/扫描都要有 `最大次数或最长窗口`，且耗尽后必须显性化（指标 + 告警 + 台账终态）；
- **R-3 自动化只能推进已知事实**：UNKNOWN 的出口穷尽为「主动查询 / 渠道回调 / 人工裁定」三个，
  **永久排除「超时自动置 FAILED」**（Constitution §V.7）。

### 决策 2：出站失败台账 = **一张表、调用方侧、复用形状**，不是新的可靠性子系统

- `pending_postings` 落在**各调用方自己的 schema**（payment / settlement / reconciliation），不放 ledger 库——
  Ledger 不可用时写 Ledger 自己没有意义（031 §12 裁决，本 ADR 继承不改设计）；
- `UNIQUE(event_type, source_id)` ⇒ 同一事实反复失败只留一行、`retry_count` 递增；
  重试 `5` 次（`1s/5s/30s/2m/10m`）⇒ 成功 `REPOSTED`，耗尽 `ABANDONED`（终态，不允许无限 `PENDING`）；
- **可安全重放的前提是 ADR-0077 D4 的 Ledger 派生幂等键**——没有它，台账就是双记制造机；
- 同一形状泛化到记账以外的后置 RPC（支付成功通知、退款结果通知、结算记账），
  **MUST NOT 为每种失败新建一张表**；
- **红线**：台账是补偿辅助，**不是资金事实源**；032 的 `MISSING_POSTING`（BLOCKER）审计仍是最后防线。

### 决策 3：后置动作与终态**同事务**（C-19 修复取 A 方案），跨服务动作一律移出事务

`onRefundResult` 的「TXRF 终态迁移 + `refunded_minor` 累加 + 订单状态推进」纳入同一本地事务
（三张表同在 `order` schema，天然可事务，零 schema 变更）；MQ publish 与跨服务网关调用保持在**提交后**执行、
失败吞成指标并入台账——即 `:323-346` 今天已经写对的那半边语义。
事务内 MUST NOT 出现任何 Feign / Redis / MQ 调用。
（方案 B「加 `applied` 标记列」被降级为**备选**：它解决的窗口在 A 方案下不存在，代价是多一列资金语义状态。）
重放语义随之收紧：`firstTerminal=false` ⇒ 必然已完整应用，直接返回是安全的。

### 决策 4：**移除** Resilience4j

删除 `payment-service` 的依赖与 `openfeign.circuitbreaker.enabled`。理由：
① 零使用（无注解、无 registry、无实例配置）⇒ 它今天唯一实际作用是**改变 Feign 异常包装路径一层**；
② 阈值无数据支撑，任何配置都是拍脑袋，而「配了但没人理解其触发条件」的熔断器在资金路径上比没有更危险
（熔断期间的降级/快速失败语义未定义）；
③ 真实弹性已由三套各自实现且有测试的机制承担：Feign `Retryer` 只对幂等只读 GET 开 3 次退避
（`FactsClientConfig.java:43-44`）、写路径显式 `NEVER_RETRY`（`OutboundResilienceTest` 第 3 项钉死）、
应用层有界重试与查询政策（`ReliabilityConfig`）。
将来若确需熔断，MUST 针对**具体调用点**独立立项并先定义降级语义。
落地分两步（先 `enabled=false` 观察一个迭代，再删依赖），配契约快照回归。

### 决策 5：refund 侧对称补齐有界收敛，复用既有政策参数

新增 `RefundUnknownQueryScheduler`（PMRF UNKNOWN → 按 attempt 记录的**渠道 + 模态 + 渠道退款流水号**查权威状态，
复用 `ReliabilityConfig.queryMaxAttempts/queryIntervalMs`）与 `StrandedRefundOrderScanner`
（TXRF `REQUESTED` 且 `paymentRefundNo=null` 超阈值 → 重放 030/B7 已备好的「②未受理重试分支」，
**自动重放 ≤2 次**，耗尽转人工 + `FINANCIAL_AUDIT`；`RefundPolicy` 累计上限是最后防线）。
扫描器 MUST NOT 自行判定成败（R-3）。
UNKNOWN 老化按 `bucket` 分四档（0-5m / 5-30m / 30m-24h / >24h），零新增列（用状态时间戳）。

### 决策 6：取消后迟到成功（C-23）**复用** surplus 出口，不新增状态、不新增链路

`Payment` 的 `CLOSED` 终态吸收规则不变（不回退状态）；在「吸收但渠道成功」分支补一个指标 +
一次**带 `late` 标志的支付成功通知**，驱动 order 既有 `ORDER_NOT_PAYABLE` surplus 分支自动原路退回。
**显式禁止**借此新增「禁止多 Payment SUCCESS」或「换渠道前关闭旧 Payment」的任何约束
（design-review §14.3 对 C-11 的裁决不变）。

---

## 备选方案

- **A. 给 Payment 加 `ACCOUNTING_PENDING` 状态**（brief 提到的理论形态）：让支付事实聚合承载下游账务进度，
  造成同一事实双主，且牵动状态机 / 指标 / 快照 / E2E 全部断言而无新增保证 ⇒ **否决**（spec 034 §6.4 详述）。
- **B. 引入通用重试框架 / Saga 引擎 / 工作流（Temporal、Camunda 等）**：brief §12 与 Constitution §I 双禁 ⇒ 否决。
- **C. 记账失败改「Ledger 侧重试」**：Ledger 不知道调用方为什么失败、也不持有业务事实 ⇒ 归属错误，否决。
- **D. 采纳**：调用方台账 + 同事务收口 + 有界扫描 + 移除熔断依赖。

---

## Consequences

**正面**
- F-1~F-5 五个失败面各自具备「载体 + 出口 + 告警 + Owner」，静默停摆不再可能；
- C-19 的崩溃窗口被**构造性消除**（不是加检查），且未引入新状态；
- 031 §12 的设计第一次有实现归属；030 留给后续的 `REQUESTED` 扫描器在本 Feature 收口；
- ADR-0021 与实现的冲突（backlog #5）以「移除」了结，出站行为回归有契约快照兜底。

**代价 / 风险**
- 台账表出现在 3 个 schema（同形状、不同库），需靠规范而非外键保持一致；
- 多类扫描器并存的重试风暴风险：每类独立周期与上限，`retry_count` 只能由台账一处递增；
- 事务边界改动若把远程调用误留事务内 ⇒ 长事务与假失败：拟由 L5 规则（`@Transactional` 方法内不得调 `*Gateway`/MQ）守住，
  该规则落点登记给 033 §7；
- 移除依赖需两步走 + 契约回归（隐性异常语义变化是最容易被忽略的部分）；
- 「自动重放卡住的退款」是资金路径上的自动化，必须接受 H-034-5 的显式裁决与 ≤2 次硬上限。

---

## 待人类裁决（详见 spec 034 §15）

| # | 决策项 | 边界类型 | 推荐 |
|---|---|---|---|
| H-034-1 | Resilience4j 去 / 留（= design-review §13 **H13**、backlog #5） | 架构取舍 / 依赖变更 | **去** |
| H-034-2 | C-19 取 A（同事务）或 B（加 `applied` 列，= §13 **H18**） | 数据库结构变更（仅 B） | **A** |
| H-034-3 | 迟到成功的通知语义：复用 `payment.succeeded` 加 `late` 标志 vs 新事件（= §13 **H20**） | 跨服务通知语义 | 复用 |
| H-034-4 | 老化分桶阈值与台账重试次数/退避序列 | 运营口径 | 采纳默认，随 035 SLO 一并确认 |
| H-034-5 | 卡 `REQUESTED` 是否自动重放（= design-review §13 **H17** 的收口） | 资金路径行为 | 自动但严格有界（≤2） |
