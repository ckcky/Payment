<a id="adr-0071"></a>

# ADR-0071: 用户支付限额——日月年周期额度与两阶段预占（spec 027 立项）

- 状态：🟢 **Accepted**（2026-09-16 提出，同日随 spec 027 实现完成转为 Accepted；D1~D13 全部确认）
- 关联：ADR-0028（**最小风控 ⛔ Not Implemented，本 ADR 与其切割**）、ADR-0064（一交易多支付单）、ADR-0009（记账失败不回滚事实、靠对账收敛——补偿扫描的哲学依据）、ADR-0044（Redis 引入，**payment-service 明确不使用**）、ADR-0048（演示组件同源代理只读——本 ADR 提出显式例外）、ADR-0062/0063（业务单号、跨系统引用）、ADR-0010（金额只用 `long` 分）、spec 019（退款双层单）
- 需求源头：负责人 2026-09-16「我想在项目里加上限额这个功能，比如针对用户进行日月年限额」。

## 背景

**现状核实（2026-09-16 核对真实代码与文档）**：

| # | 事实 | 证据 |
|---|---|---|
| G1 | `payments` 表已含 `user_id` / `amount_minor` / `currency_code`，是**唯一的资金入口事实源** | `deployment/schema/03-payment-schema.sql`；`payment-service.md` §2.3 |
| G2 | **没有任何限额/额度概念**：全仓无 limit / quota 表或类型；`RiskCheckService` 与 `payment.risk.*` 已于 2026-08-31 删除 | ADR-0028 ⛔ Not Implemented；`docs/specs/stage-01-core-mvp/009-risk-security/spec.md` §2 |
| G3 | **ADR-0028 否决的是「评分式风控」**：原设计是「可配两条阈值规则；命中**只记录指标与审计，不阻断**」 | spec 009 §3 US4、§4 FR-006 |
| G4 | 支付成功的副作用（通知 order、记账）**运行在事务之外**：`createPaymentIntent` 无 `@Transactional`，`PaymentResultProcessor.applyAndNotify` 亦无；只有 `PaymentPersistence.insertPending` / `applyAndPersist` 是独立短事务 | `PaymentApplicationService.java`、`PaymentPersistence.java`、`PaymentResultProcessor.java` |
| G5 | 渠道回调会**重复且乱序**，当前靠状态机终态吸收（`changed=false` 不触发副作用） | `PaymentResultApplier.apply`；`payment-service.md` §5.2 |
| G6 | 一交易对多支付单（`transaction_no : payment_no = 1 : N`），换渠道即新建支付单 | ADR-0064 |
| G7 | payment-service 经评估**不使用 Redis**（支付事实需强一致） | `payment-service.md` §5.1；ADR-0044。**⚠️ 本 ADR D13 已作限用途反转**：Redis 仅作「在途占用过期索引」，仍禁止做额度计数 |
| G8 | 演示组件 `mock-channel-web` 同源代理各服务**只读**接口 | ADR-0048 |

**必须先说清的一件事**：G2 + G3 意味着「风控不做」是**已裁决**的结论。限额若在文档里被写成「风控」，后人翻 `docs/adr/README.md` 看到 ADR-0028 会直接判其为翻案。二者的本质区别是——

| | ADR-0028 最小风控（已否决） | 本 ADR 限额 |
|---|---|---|
| 判定方式 | 阈值评分 | 确定性额度比较 |
| 命中后果 | **只记录，不阻断** | **硬拒绝，支付单不创建** |
| 性质 | 风险观测 | 业务合规约束 |

故本 ADR **不是 ADR-0028 的翻案**，是新增一项独立的业务能力。

## 决策

**D1. 归属 payment-service，不新增微服务。**（负责人已确认）

限额约束的是**支付事实**（钱有没有真的动），不是订单意图。权威的「已发生金额」只能由 payment 提供（G1）；放到 order-service 会维护一份与 payment 重复的消费累计，即**事实源分裂**。且 G6 下交易与支付单是 1:N，order 想知道用户已付多少只能反过来问 payment。order 若需「下单时拦截」，只能**读** payment 的额度服务，不持有写数据。

**D2. 数据模型 = 三张表，双金额。**（负责人已确认）

- `user_payment_limits`：配置。`UK(user_id, currency_code)`，一行存 `daily/monthly/yearly` 三个限额。
- `user_limit_usage`：累计。`UK(user_id, currency_code, period)`，双金额 `used_minor`（已确认）+ `pending_minor`（**在途占用**）。
- `limit_operations`：幂等流水，`UK(biz_no = paymentNo, op_type, period)`，`op_type ∈ {RESERVE, CONFIRM, RELEASE, EXPIRED}`。

> **为什么必须双金额**：只统计 `SUCCEEDED` 的话，并发 N 笔同日请求会同时读到「未超限」再全部放行，日限额被直接击穿。`pending` 是在途保守占用，与退款域 `RefundPolicy`「按申请额累计防超退」同口径。

> **唯一键含 `period` 的实现修正**（2026-09-16，实现期发现）：原 `UK(biz_no, op_type)` 使**同一支付单跨三周期只能留一条流水**——首笔 `RESERVE(DAY)` 落库后，`RESERVE(MONTH)` / `RESERVE(YEAR)` 全部撞键被当作「重复」跳过。后果是 **MONTH / YEAR 两档额度从不累加**，即限额静默失效（正是 D4 第 1 条要防的那类错误）。
> 因「一笔支付同时占用日 / 月 / 年三档」是设计本意，故唯一键必须扩为 `(biz_no, op_type, period)`；幂等语义不变（同一支付单在同一周期的同一操作仍只允许一条）。

**D3. 两阶段：预占 → 确认 / 释放，判定靠一条原子 UPDATE。**（负责人已确认）

```sql
UPDATE user_limit_usage SET pending_minor = pending_minor + ?
WHERE user_id = ? AND currency_code = ? AND period = ?
  AND used_minor + pending_minor + ? <= ?
```

影响 0 行 = 超限。不用分布式锁；**判定路径不用 Redis**（G7）——D13 引入的 Redis **只作过期索引**，不参与判定与计数。

**D4. 幂等三道闸门。**（负责人已确认，由「回调重复会不会炸」的质疑逼出）

仅靠状态机 `changed=true`（G5）**挡不住两类问题**：

1. 事务外的额度结算崩了（G4）→ 支付已 `SUCCEEDED` 但 `used` 未加 → **额度虚高、限额静默失效**。这比报错危险得多：钱收了，限额不知道。
2. `UNKNOWN` 长期不收敛 → `pending` 永久占用 → 用户额度被吃掉。

因此三层：

1. **状态机终态吸收**：重复/乱序回调 `changed=false`，不触发额度操作（挡「回调重发」）；
2. **幂等流水 `UK(biz_no, op_type, period)`**：一张支付单在同一周期的每种操作只允许一条流水，撞键即跳过（挡「事务外重试」与「补偿重跑」）；
3. **补偿扫描**：以 **payment 为事实源**，扫「payment 已终态但只有 `RESERVE`、无 `CONFIRM`/`RELEASE`」的记录补结算。`UNKNOWN` **不处理**（保守占用，守「未知状态不猜成败」）。

第 3 条沿用 ADR-0009 的既有哲学：副作用失败不回滚事实，靠对账收敛。不新造概念。

**D5. 超限 = 硬拒绝 `409 LIMIT_EXCEEDED`，支付单不创建。**（负责人已确认）

新增全局错误码。必须断言「`payments` 表无新增行」——是**未创建**，不是「创建了再拒」。

**D6. 金额口径：预占与确认同一金额，释放带下限保护。**（负责人已确认）

- 一律用 `payment.getAmountMinor()`（请求额），**不用渠道回调回传的实付额**（该字段当前语义为「仅落观测」）。否则预占 100、实付 98 会让 `pending` 永久残留 2 分。
- 释放：`pending_minor = GREATEST(0, pending_minor - ?)`。即使前面所有防线都被绕过，也绝不让 `pending` 变成负数把额度撑大。

**D7. 退款成功不回补额度。**（负责人已确认）

额度按「支付发生额」口径，非「净支出」。退款是异步的（spec 019），回补会遇到「退款先到、支付后到」把 `used` 扣成负数的问题。

**D8. 默认关闭：查不到配置行 = 不限额。**（负责人已确认）

否则 `deployment/demo/traffic-gen.sh` 的持续流量与 nightly E2E 会被 409 打断。这条是**兼容性的硬要求**。

**D9. 演示：`/internal/limits/**` 走读写代理。**（已采纳方案 ②，2026-09-16）

ADR-0048 定「演示组件代理只读接口」（G8），而设置限额是写操作。备选：① 只由 `demo/seed.sh` 播种 SQL、演示页只读（严格守 ADR-0048，但演示是死的）；② 开放读写代理并在本 ADR 写明边界；③ 复用 ADR-0049 场景注入。

**采纳 ②**，理由：**限额配置不产生资金动作**，与「禁止演示页伪造业务事实」是两回事。本 ADR 即作为 ADR-0048 的显式例外登记，**例外范围仅限 `/internal/limits/**`**。

> 实现落点：复用 `DemoProxyController` 的既有透明代理 `/proxy/{service}/**`，路径为
> `/proxy/payment/internal/limits/**`（`payment` 本就在 `MockChannelProperties` 服务映射中，
> 无需新增白名单代码）。代理本身不区分读写，故「只读」约束的例外只需在此登记 + 集中在这一路径下。
> 既有的「演示页禁止写业务事实」纪律不变：限额配置表以外的任何表仍无写路径。

**D10. 周期重置不用定时任务。**（已采纳，2026-09-16）

`period_start` 存当前周期起始日（今天 / 本月 1 号 / 1 月 1 号），请求进来现算（`LimitPeriod.periodStart(Instant)`，UTC），查不到就建行；跨周期旧行自然闲置。零调度器、零跨天临界问题。已实现于 `LimitPeriod` + `MybatisLimitUsageRepository.ensureRow`。

**D11. 在途占用加 TTL 自动释放。**（2026-09-16 负责人拍板）

D4（UNKNOWN 保守占用）单独存在时，在 `deferChannel=true` 演示收银台路径下，用户「点了支付不回调」会让 `pending` **永久挂住**，等价于一个可主动触发的拒绝服务面。故新增 `EXPIRED` 操作类型：TTL 到期即释放 `pending`，但**绝不反向修改 `payments.status`**（守「不猜成败」）。
**回收机制经 D13 改为惰性回收（`LimitPendingRecycler` + Redis TTL），不再使用扫描器。**

TTL 下界 = 支付侧最大自动收敛窗口 = `payment.reliability.timeout`(30s) + `query-max-attempts × query-interval-ms`(5×15s) = **105s**，否则会释放一笔正在被主动查询收敛的支付。默认取 **900s**，与 `order.timeout.ttl-seconds` 对齐（订单超时已被取消并释放库存，再占额度无意义）。

> ⚠️ 本段原文曾写「实现走 DB 扫描 + `@Scheduled`」，**已被 D13 取代**（D13 判定该路径「太重」，改为 Redis TTL + 惰性回收）。此处保留原文仅作决策演进留痕，**最终实现以 D13 为准**：`LimitPendingRecycler`（无调度器）+ `RedisLimitExpiryIndex`。

**D12. 软超限口径：新支出硬约束，已发生事实软记账。**（2026-09-16 负责人拍板）

`RESERVE`（准入）硬拒绝；`CONFIRM`（事实）无条件累加 `used`，哪怕 `used > limit` 也**不拒绝、不 clamp、不回滚**。理由：支付成功是不可逆的外部事实，钱已从用户账户真实扣出；为守住限额而少记 `used`，正是 D4 想避免的「静默少扣 → 限额失效」，比超额危险得多。

典型触发：① TTL 已释放后支付才成功；② UNKNOWN 人工判成功时已超；③ **限额被调低**（1000→500 而当日已付 800，最常见却最易忽略）。

处理不是消除偏差而是让它可见：`payment_limit_overrun` 指标 + `limit.overrun` 审计 + 查询接口返回 `overrun` 标记。收敛靠下一笔 `RESERVE` 被拒，直至周期重置。

**同期否决**：不为 `PENDING` 开显式「放弃支付」关单出口。`Payment.close()` 现仅接受 `SUCCEEDED/FAILED → CLOSED`，放开属改状态机（人类决策边界），且 TTL 已覆盖该诉求。

**D13. 允许 payment-service 使用 Redis；在途占用过期判定改为「Redis TTL + 惰性回收」，不做定时扫描。**（2026-09-16 负责人拍板）

⚠️ 这是对 **C7 / ADR-0044「payment 不使用 Redis」** 的**显式反转**，需留档理由。

**为什么不选 DB 扫描 + `@Scheduled`**（原 FR-038 方案）：它为一个「过期判定」引入一整套机制——新增 `LimitExpiryScanner` + `LimitExpiryScheduler`、60s 全表轮询、`LIMIT 200` 分批游标、以及和补偿扫描（FR-016）抢同一条记录的互斥处理。收益与复杂度不匹配，负责人判定「太重」。

**采用方案**：`RESERVE` 成功后写 `SET limit:pending:{paymentNo} {amount} EX {reserve-ttl}`；回收**惰性**发生在用户下一次 `RESERVE` 判定之前——按 `idx_limitop_user_type` 查该用户未结算在途（通常 0~几条），`MGET` 判存，Redis 中不存在的即已过期 → 插 `EXPIRED` 流水并释放 `pending`。**零调度器、零全表扫描、无跨实例时钟依赖**，过期判定交给 Redis TTL。

**为什么「惰性」就够**：被挂住的只有**该用户自己**的额度。他下次发起支付时必然先回收（自愈）；他不再发起则这笔占用对他毫无影响。跨用户不受影响，因此不需要全局扫描。

**守住的核心边界**（本决策得以成立的前提，已固化为 INV-9）：

1. Redis **不做额度计数**——`used` / `pending` 权威恒在 DB。C7 的**实质**约束（支付事实强一致）并未被突破，被突破的只是「payment 进程不连 Redis」这一表层表现。
2. Redis 不可用 MUST **fail-open 保守占用**（跳过回收、不做释放），**不得**拦截支付或中断建单。
3. Redis 数据丢失导致的提前释放，落入 D12 软超限口径并留痕，**绝不**静默修正 `used`。
4. **方向性原则**：Redis 出错只能让约束变松（且可见），绝不能让已发生的事实被篡改（不可见）。

> 第 4 条是与「Redis 做计数」的**根本区别**：后者崩溃丢计数 = `used` 系统性偏低 = 限额**静默失效**；本方案最坏是提前释放 = 显式软超限 + `limit.overrun` 留痕。同为 Redis 故障，后果一个是不可见的系统性错误，一个是可见的局部超额——这是它能被接受的全部理由。

**依赖与兼容**：`spring-boot-starter-data-redis`（与 order-service 同一 parent），Redis 实例已在 `deployment/docker-compose.yml`（`redis:7`）。H2 测试经 `NoopLimitExpiryIndex` 降级，**零 Redis 依赖**通过，既有测试零改动。

**同期否决**：周期重置仍不用 Redis TTL（D10 备选 ②）——周期语义是「属于哪个周期」而非「创建后 N 秒」，且跨周期旧行需保留审计。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| 独立 `limit-service` 微服务 | 违反 Constitution「无理由新增微服务」；且额度与支付事实同库才能保证原子判定，拆开后要处理跨服务事务，成本远超收益 |
| 归属 order-service | **事实源分裂**（D1）。order 是意图、payment 是事实；G6 下 1:N，order 侧的累计必然与 payment 对不上 |
| 实时 `SELECT SUM(payments)` 聚合，不加累计表 | ① **并发穿透**：先查后判有竞态，`SUCCEEDED` 口径下 N 笔并发可全部放行；② 无法表达「在途占用」，要么放行超限、要么误伤；③ 每笔支付都扫历史，随数据量劣化 |
| `SUM` 聚合 + `SERIALIZABLE` 事务 | 能防穿透但代价过高（全表范围锁），且仍无法表达在途；不如单条原子 UPDATE |
| 只靠状态机 `changed=true` 做幂等 | 挡不住事务外崩溃（G4）→ **`used` 虚低、限额静默失效**；也挡不住 `UNKNOWN` 长期占用（D4） |
| Redis 原子 `INCR` 做计数 | 与 DB 双写不一致，崩溃后丢失计数 = 限额**静默失效**；G7 的实质约束（支付事实强一致）不因 D13 放宽——**D13 只放开「过期索引」用途，计数仍在 DB** |
| DB 扫描 + `@Scheduled` 做在途占用 TTL 释放 | 为一个过期判定引入调度器 + 60s 轮询 + 分批游标 + 与补偿扫描的互斥处理；负责人 2026-09-16 判定「太重」（D13），改用 Redis TTL + 惰性回收 |
| Redis keyspace notification 触发释放 | 依赖 `notify-keyspace-events` 配置，且**过期事件不保证送达**，丢失即永久占用；不如惰性回收（下一次支付必然自愈）可靠 |
| Redis TTL 做周期重置 | 周期语义是「属于哪个周期」而非「创建后 N 秒」，且跨周期旧行需保留审计（D10 备选 ②，仍否决） |
| 定时任务重置周期计数 | `period_start` 现算即可（D10），加调度器会引入跨天临界与失败补偿问题，无收益 |
| 退款回补额度 | 异步时序会把 `used` 扣成负数（D7）；且退款含义是「资金退回」不等于「消费额度返还」，两种口径需业务定义，一期不引入 |
| 按渠道回调的实付额确认额度 | 与预占额不等 → `pending` 残差（D6）；当前该字段语义本就是「仅落观测」 |
| 复用 ADR-0028 的风控挂点 | 该 ADR 已 ⛔ Not Implemented 且**代码已删、不留挂点**（spec 009 §2）；且其语义是「不阻断」，与限额的「硬拒绝」相反 |

## 影响

- **正影响**：平台首次具备「用户维度资金约束」能力，且判定为单条原子 SQL，无新中间件、无新服务；幂等与补偿沿用项目既有哲学（状态机吸收 + 唯一键 + 对账收敛），可解释性强；demo 可现场演示「在途占用」与「超限拒绝」两个过去看不见的中间态。
- **代价**：payment-service 新增 3 张表 + 限额子域（约 20 个类型）+ **1 个补偿扫描**（FR-016 `LimitCompensationScheduler`）+ **惰性回收**（FR-038 `LimitPendingRecycler`，无调度器、无全表轮询——经 D13 取代原扫描方案后实际成本更低）；`payments` 建单路径新增一次 DB 往返（预占）与终态时一次（结算）；新增 `LIMIT_EXCEEDED` 错误码与 `limit.*` 指标；payment-service 首次依赖 Redis（`spring-boot-starter-data-redis`，仅作过期索引且 fail-open）。
- **对既有决策的影响**：**不改变** ADR-0028 的结论（风控仍不做）；**补充 ADR-0048 一条显式例外**（D9 采纳 ②，范围仅 `/internal/limits/**`）；**对 ADR-0044「payment 不使用 Redis」构成限用途反转**（D13，仅作过期索引）；`payment-service.md` 需新增限额章节（实现期同步，防文档漂移）。
- **不做**：单笔限额、商户/渠道维度限额、多币种折算（roadmap 明确不做多币种清分）、风控评分、额度冻结/解冻、白名单、人工提额审批流、额度变更审计台。
