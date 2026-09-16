<a id="adr-0072"></a>

# ADR-0072: payment-service 两层结构——payment 支付层与 channelAttempt 渠道层的职责切分

- 状态：✅ **Accepted**（2026-09-16 提出，2026-09-16 负责人裁决接受并随 spec 028 落地）
- 关联：ADR-0054（支付编排职责归位——本 ADR 细化其「payment 层编排支付指令」的内部结构）、ADR-0064（一交易多支付单 / 三渠道 mock）、ADR-0012（双响应码错误分类）、ADR-0063（跨系统一律业务单号）、ADR-0049（配错不许静默走默认）、ADR-0073（渠道路由——本 ADR 的直接下游）、[technical-solution §3.1](../architecture/technical-solution.md)、[payment-service.md](../architecture/systems/payment-service.md)
- 需求源头：负责人 2026-09-16 对 payment-service 结构的裁决——
  > 「之前设计的时候明明说了在 payment-service 里是有两层：一个 payment 支付层，一个 channelAttempt 渠道层。在 payment 层编排支付指令（比如说账务这些），然后调用 channelAttempt 渠道层进行外部渠道的调用。那么 payment 表就应该是在 payment 层的时候记录，在 channelAttempt 返回的时候更新。channelAttempt 渠道层负责具体渠道的实现和抽象，payment 层压根不关心外部渠道是如何实现的，只管调用就行了。但是在代码里我看 channelAttempt 和 payment 表是一起记录更新的，更离谱的是在渠道层完全一点没有看到外部渠道的影子，连桩实现都没有。」

## 背景

### 一、文档里已有的约定：只有原则，没有结构

| 出处 | 原文 | 性质 |
|---|---|---|
| ADR-0054 决策 2 | 「payment **层编排支付指令**：渠道支付落 `payment_attempts` + 记账（`ledgerGateway.postPaymentCapture`，属支付指令编排的一部分）」 | 职责动作 ✔ |
| `payment-service.md` §2 边界表 | 「**Payment ≠ Channel**：核心 Payment 领域只依赖 `application/channel/PaymentChannel` 接口，不依赖 `infra/channel` 具体实现」 | 边界原则 ✔ |
| `technical-solution.md` §3.1.1 | 聚合关系图 `Payment --- PaymentAttempt` | 领域模型 ✔ |

**缺口**：上述三处都**没有**把 channelAttempt 确立为独立的一层——没有它的职责边界、没有它的表归属、没有它的实现族、没有两层之间的交互契约。

而 `technical-solution.md` 的支付链路时序图反而写着：

```text
P->>P: 创建 Payment + PaymentAttempt（本地事务）
```

即：**现状是符合已写文档的**。本 ADR 要处理的不是「实现跑偏」，而是「文档从未定义过结构」——先把目标结构定清楚，实现才有据可依。

### 二、代码现状（2026-09-16 核对真实代码）

| # | 事实 | 证据 |
|---|---|---|
| S1 | `PaymentPersistence`（**application 层**）在**同一事务**内写 `payments` + `payment_attempts` 两张表 | `PaymentPersistence.insertPending()` / `applyAndPersist()` |
| S2 | `PaymentResultApplier.apply(Payment, PaymentAttempt, ChannelResult)` —— **一个方法同时推进两个聚合**的状态机 | `PaymentResultApplier.java:20` |
| S3 | 渠道实现层**只有 1 个文件** `MockChannelAdapter`（12.9 KB），没有任何 per-channel 形状 | `payment-service/src/main/java/com/payment/payment/infra/channel/` 仅此一文件 |
| S4 | 渠道端口层只有 1 个接口 + 4 个 DTO + 1 个回调接口；`PaymentChannel` **无渠道身份方法** | `application/channel/` 共 6 个文件 |
| S5 | **退款尝试的 `channel_code` 被硬编码为字符串 `"mock"`** | `PaymentRefundService.java:89` `PaymentAttempt.refundAttempt(request.paymentNo(), "mock", ...)` |
| S6 | `RefundRequest.channelCode` 是**死字段**——传了，但 `MockChannelAdapter.refund()` 从不读它 | `MockChannelAdapter.java:201-226`；退款引用亦硬编码 `"mock-refund-ref-"` |
| S7 | 支付侧渠道身份靠调用方传入的 `ChargeRequest.channelCode()`，渠道实现自己不知道「我是谁」 | `MockChannelAdapter.channelPrefix(request.channelCode())` |
| S8 | 拆分基数为 `payment_no : payment_attempts = 1:1`（渠道重试在同一行 `retry_count` 递增） | ADR-0054 决策 2；`PaymentPersistence.java:56` |

**S5 是本 ADR 最硬的证据**：一个 WECHAT 支付单退款后，退款尝试行记录的渠道是 `"mock"`——**渠道事实在库层面就是错的**。这不是风格问题，是数据正确性问题，且它恰好暴露了根因：**payment 层不掌握渠道身份，就无法正确写 attempt 的渠道列**。

> ⚠️ 该缺陷在当前「只有一个 Mock 渠道」时不会触发任何测试失败，属**潜伏缺陷**——一旦拆出三渠道就会立刻暴露（且会污染退款对账事实）。

## 决策

### 1. 确立两层，各有自己的聚合、表与写入口

| 层 | 聚合 | 表 | 职责 |
|---|---|---|---|
| **payment 支付层** | `Payment` | `payments` | 支付单生命周期；**支付指令编排**——选路 → 调渠道 → 应用结果 → 记账 → 扇出 order；幂等键；金额口径 `amount_minor` / `currency_code` |
| **channelAttempt 渠道层** | `PaymentAttempt` | `payment_attempts` | **渠道交互的完整生命周期**——创建尝试 → 调用外部渠道 → 收敛结果（含 UNKNOWN）→ 落渠道引用与错误分类；**渠道实现族**（各渠道的协议适配与身份） |

### 2. 表归属与写入口（本 ADR 的关键约束）

- `payments` 表**只由 payment 层写**；
- `payment_attempts` 表**只由 channelAttempt 层写**——payment 层 MUST NOT 直接依赖 `PaymentAttemptRepository`；
- payment 层需要渠道交互时**经端口调用**，拿回 `ChannelResult`，据此更新自己的 `Payment`（`Payment` 只持 `currentAttemptId` 作为指向，不持渠道细节）。

### 3. 渠道身份属于端口

`PaymentChannel` 补 `String channelCode()`——**渠道身份是渠道层的基本属性**，不是由调用方塞进请求 DTO 的外部标记。

> **代价明示**：全仓 **4 个**测试桩（`PaymentDeferredChannelTest` / `ChannelQueryTest.StubChannel` / `PaymentRetryTest` / `ReliabilityMetricsTest`）只实现 `PaymentChannel`，需各补 `channelCode()`。这是结构重构的必要成本——不再采用「另起 `ChannelProvider` 接口绕开」的妥协做法，那会让「渠道身份」与「渠道实现」分属两个接口，与「渠道层是一个层」的目标相悖。

### 4. 渠道实现族：抽象基类承载横切行为，子类只声明身份与差异

```text
AbstractMockChannelAdapter（抽象，非 Bean，承载 4 件横切行为）
├── AlipayChannelAdapter   channelCode() = ALIPAY
├── WechatChannelAdapter   channelCode() = WECHAT
├── DouyinChannelAdapter   channelCode() = DOUYIN
└── MockChannelAdapter     channelCode() = MOCK（保留原名与全部既有构造签名，兼容既有测试与脚本）
```

**基类必须逐字承载的 4 件横切行为**（三渠道行为一致，不得分叉）：

| # | 行为 | 出处 | 分叉后果 |
|---|---|---|---|
| 1 | 金额尾数确定性故障注入（11→超时 / 12→无结论 / 15→业务拒绝） | spec 022 / T429 | E2E 全红，且失败现象看似与渠道无关 |
| 2 | 退款「受理 + 异步推送」（`RefundResultListener`） | spec 019 / D7 | 漏一个渠道 → 退款链路静默退化 |
| 3 | **每 Adapter 独立 `runId`**（UUID 派生） | `MockChannelAdapter.java:66-71` | 若共享 `runId`，各 Adapter 的 `refGen` 都从 1 起 → 撞 `uk_attempts_channel_reference` |
| 4 | `mock-scenario` 严格枚举解析（坏值 FAIL FAST） | ADR-0049 第 2 条 | 配错静默走默认成功 → 最难排查的假绿 |

> **为什么用继承而非委托组合**：委托方案下「渠道层」退化为三个转发壳，渠道层仍不拥有行为；且基类若被多实例共享 `runId`，唯一约束会撞。抽象基类让「一个渠道 = 一个 Adapter 实现」，与「渠道层负责具体渠道的实现与抽象」的诉求一致。

### 5. 退款尝试的渠道归属必须取自原始支付记录

`attempt_type=REFUND` 行的 `channel_code` **必须取自被退支付单的生效支付渠道**（该 `payment_no` 下 `attempt_type=PAYMENT` 那行的 `channel_code`），**禁止硬编码、禁止重新路由**。

> 这条同时是 ADR-0073 的「反向按记录解析」不变量——退款换渠道 = 钱退错地方。S5 的硬编码 `"mock"` 必须随本 ADR 一并消除。

### 6. 事务边界：分层 ≠ 拆事务

两层**可以且应当共享同一个本地事务**——`payments` 与 `payment_attempts` 的状态必须同时迁移，否则出现 `payment=SUCCEEDED / attempt=PENDING` 之类的永久不一致。

本 ADR 只切**职责与写入口**，不动事务边界。**「两层」是职责分层，不是分布式拆分、不是拆数据源、不是拆事务。**

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| 保持现状（payment 层代管 attempt 落库） | 负责人明确不接受；且已被 S5 证明会产出**错误的渠道数据**——payment 层不掌握渠道身份，就没有能力正确写 attempt 的渠道列 |
| 把 channelAttempt 拆成独立微服务 | 严重过度：attempt 与 payment 是同一事务内的强一致对象，拆出去必须引入 Saga 与补偿，收益为零、成本极高 |
| `payments` 与 `payment_attempts` 拆成两个事务/数据源 | 引入一致性窗口；且两表状态机必须同步推进，属「分层」与「拆事务」的概念混淆 |
| 渠道身份用独立 `ChannelProvider` 接口（不改 `PaymentChannel`） | 曾作为「零测试改动」的妥协提出，但会让身份与实现分属两个接口，渠道层仍不成其为「层」。改为补 `PaymentChannel.channelCode()`，测试桩改动作为成本接受 |
| 三个渠道共用一个 Bean + 配置切换 | 即现状（S3/S7），负责人已明确否决：「目前的三个渠道都要分开，哪怕你代码是一样的都行」 |
| 三个渠道各自复制一份 250 行 mock 逻辑 | 横切行为必然分叉 → spec 022 的 E2E 会以「看似无关」的方式失败；基类承载是唯一防线 |

## 影响

**正影响**：

- 渠道归属在**库层面正确**（消除 S5 的潜伏错误数据）；
- `Payment ≠ Channel` 从文档原则变成**结构事实**，并可加构建期门禁（`application/channel/**` 不得依赖 `infra/channel/**`）；
- 为渠道路由（ADR-0073）提供前提——**渠道必须先有身份，注册表才有 key 可挂**；
- `payment-service.md` §2.1 的「必须已注册到渠道 Registry/Router」从悬空描述变为可落地事实。

**代价**：

- `PaymentPersistence` 按两层拆开（payment 层不再持有 `PaymentAttemptRepository`）；
- `PaymentResultApplier` 拆为两侧各自的推进（`attempt` 侧由渠道层收敛、`payment` 侧由支付层推进）；
- `PaymentChannel` 补 `channelCode()` → 4 个测试桩需补实现；
- `infra/channel` 新增 4 个类型（1 抽象基类 + 3 渠道实现）；
- 退款尝试渠道归属修正会**改变存量数据语义**（历史行仍是 `"mock"`，需明确是否回填，见 spec 028）。

**明确不做**：

- ❌ 不拆微服务、不拆数据源、**不拆事务边界**；
- ❌ 不改状态机语义（`Payment` / `PaymentAttempt` 的状态迁移规则逐字不变）；
- ❌ 不改金额口径、幂等键结构、记账链路、对账抽取口径；
- ❌ 不引入新中间件、不引入新依赖、不引入 MQ（Constitution）；
- ❌ 不接入真实渠道 SDK（三个 Adapter 仍是 mock 语义，只是结构与身份变真）。

## 落地

由 **spec 028** 承载（含渠道路由，见 ADR-0073）。本 ADR 与其落地 spec 均属**结构变更**，实现前须经 `/speckit-plan` 评审。
