# Tasks: 037-channel-gateway-boundary

**推进方式**：TDD（红 → 绿 → 重构）。每个 T 先落失败测试，再实现，最后跑全量单测。
**测试策略**：单测为主，不起全链路（NFR-1 / D5）。

**验证命令**（每个 T 结束后跑）：

```bash
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
```

---

## ⚠️ 基线变更（2026-09-25）：038 已先于本 Spec 合入 master

**事实**：master 已推进到 `0a81b29`（`Merge branch 'feature/038-payment-service-package-boundary'`）。
038 的 spec 自己写明「前置条件：037 必须已合入 master」，但实际是**038 先合入**。
于是本 Spec 的路径与部分范围需要就地适配：

| 影响 | 事实 | 本 Spec 的处置 |
|---|---|---|
| 包搬迁 | 渠道网关件整体搬入顶层包 `com.payment.channelgateway..`（40 文件）；`com.payment.refund` 被消灭（并入 `com.payment.payment`） | T4 起全部按新包结构落点；`spec.md` §1.1 的 `path:line` 证据表按当时快照保留不改（历史记录型） |
| T2 的「重构」项 | 038 对 `PaymentScene`/`Goods`/`Payer`/`CallbackUrls`/`PayCredential` 做了**完全相同**的包下沉（`→ com.payment.common.dto.channel`） | 与 T2 成果一致，无冲突；合并时按 038 侧取值 |
| T7（FR-016） | 038 已落 **14 条包级 ArchUnit 规则**，其中 `channelGatewayMustNotDependOnOtherServicesAtCompileTime` / `paymentAndChannelGatewayMustKeepOneWayDependency` / `channelGatewayMustNotReferenceLegacyRefundPackage` / `channelPluginsMustResideInTheirInfraChannelPackage` 覆盖了 FR-016 的**大部分**意图，且是包级（非硬编码渠道名） | T7 收窄为「补 FR-016 中 038 **未**覆盖的两条」：① Payment 应用/api 层禁依赖渠道内部件（038 只约束了反方向）；③ `DyeContext` 仅限渠道网关域 |
| 技术债归属 | 038 在 `ServiceBoundaryTest.LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` 白名单登记 4 个类（3 回调 Controller + 1 验签过滤器），注释写明**「收口归 037」** | 归 T5 处理 |

**合并记录**：`feature/037-*` 以 `--no-ff` 合入 `origin/master`，10 处冲突全部是「T2 补的 import」vs「038 的包搬迁」，
统一取 038 侧（新包路径）。合并后 `./mvnw -B clean test` = **1016 tests / 0F / 0E / 18 模块 SUCCESS**（基线）。
> ⚠️ 计数必须用 `clean test`：`target/` 里残留 038 搬迁前的旧包路径 surefire XML
> （`com.payment.payment.infra.channel.*` / `com.payment.refund.*`），不 clean 会把
> payment-service 从 388 虚报成 492、全量从 1016 虚报成 1120。

---

## T1　channelNo 业务单号　[FR-001]

- [x] **红**：`BusinessNosTest` 增加用例——`BusinessNos.of(BusinessNoType.CHANNEL)` 前缀为 `CH`、`isValid` 通过、10k 并发生成不重复
- [x] **绿**：`BusinessNoType` 增加 `CHANNEL("CH")`
- [x] **重构**：无
- [x] 验证：`common-core` 单测全绿

## T2　common-dto 渠道网关契约　[FR-003][FR-004][FR-005][FR-006]

- [x] **红**：新增 `ChannelContractTest`——断言契约位于 `com.payment.common.dto.channel`；`ChannelQueryCommand` 以 `channelNo` 为主键；`ChannelPayNotified` 含 `channelCode`
- [x] **绿**：`common/common-dto` 新建 `dto/channel/`：`ChannelPayCommand` / `ChannelRefundCommand` / `ChannelQueryCommand` / `ChannelPayReceipt` / `ChannelRefundReceipt` / `ChannelQuerySnapshot` / `ChannelPayNotified` / `ChannelRefundNotified`
- [x] **重构**：`PaymentScene` / `Goods` / `Payer` / `CallbackUrls` 是否下沉 common-dto（拆微服务时可复用性评估）
- [x] 验证：`common-dto` 单测全绿

## T3　payment_attempts.channel_no 列　[FR-002]

- [x] **红**：`PaymentAttempt` 单测断言 `channelNo` 必填、不可变
- [x] **绿**：`03-payment-schema.sql` 加 `channel_no VARCHAR(32)` + UNIQUE；`PaymentAttempt` 实体加字段（含 `rehydrate` 重载）；`ChannelAttemptRecorder` 生成并写入
- [x] **重构**：移除 `ChargeRequest.attemptId`
- [x] 验证：`payment-service` 单测全绿；`deployment/demo/reset.sh` 重放建表通过

> ✅ **`重构` 项已执行**（2026-09-25，人类裁决「放行：删字段 + 同步改那 1 处断言」）。
> `ChargeRequest` 由 12 分量收为 **11 分量**（`Long attemptId` 移除），兼容构造器由 5 参收为 4 参。
> 同步改动 11 个构造点（1 生产 + 10 测试）与 `ChannelContractCompatTest` 的
> 1 处断言（`assertThat(req.attemptId()).isEqualTo(7L)` 随字段删除，已就地注明原因）。
> 这正是 NFR-2 的**唯一一次经批准的例外**：该断言的全部内容就是「兼容构造器把第 2 个参数
> 存进了 attemptId」，字段消失后断言无处可依。
> 顺带清掉 `ChargeRequest.java` 里**重复了 4 行的 import 块**（既有笔误）。
>
> ⚠️ spec 完成判据写的是 `grep attemptId ChargeRequest.java 为空`——实际有 **2 处 javadoc** 命中
> （记录「为什么移除」），非代码引用。刻意保留：删掉理由比留一条 grep 假阳性更糟。
> 另有 25 处 `attemptId` 分布在 `Payment.start(Long)` / `PaymentPersistence.applyAndPersist` /
> `ChannelAttemptRecorder.require` 等**内部数值 ID** 语义处，不属跨域契约，不动。

## T4　ChannelGateway 门面收口　[FR-007][FR-008][INV-1]

- [x] **红**：`ChannelGatewayTest`——门面行为等价（pay/refund/query/route/能力查询/未注册拒绝）+ SC-002 反射断言（Payment 侧字段与构造器参数不含 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`）
- [x] **绿**：新增 `ChannelGateway` 接口 + `DefaultChannelGateway` 实现；Payment 侧 5 个消费点全部改走门面（`PaymentApplicationService`、`PaymentRefundService`、`ChannelQueryService`、`PaymentRetryService`、`RefundUnknownQueryScheduler`）
- [x] **重构**：`ChannelAdminController` / `RoutingProperties` 的引用一并收口
- [x] 验证：既有单测断言**零变化**（NFR-2）

### T4 执行记录（基线 = 038 已合入后的包结构）

**落点**：门面与默认实现均在 `com.payment.channelgateway.application`（**不**放 `infra`）。
理由沿用 `SingleChannelRegistry` javadoc 里登记的 2026-09-20 FIX-1 纪律：该类零 infra 依赖，
是「门面」这个**应用层抽象**的退化实现，放 `application` 才能让依赖方向恒为 `infra → application`，
避免 Payment 侧兼容构造反向依赖 `channelgateway.infra..` 而穿透包边界。

**方法族**（7 个）：`route` / `pay` / `refund` / `query` / `supportedScenes` /
`registeredChannelCodes` / `requireRegistered`，另有两个兼容静态工厂
`ChannelGateway.none()` 与 `ChannelGateway.ofSingleChannel(PaymentChannel)`。

**三个设计裁决（均为「行为零变化」而做，不是自由发挥）**：

1. **`pay` / `refund` 取显式渠道码**（`pay(String channelCode, ChargeRequest)`），
   与既有 `query(String, QueryStatusRequest)` 对齐。原因：`PaymentRetryService` 的兼容路径下
   请求可能**不带**渠道码（回落唯一注册渠道），若门面从 `request.channelCode()` 取值会
   `resolve(null)` 抛错——显式传码既保住该路径，又把 INV-6（禁止重新选路）编码进签名。
2. **新增 `requireRegistered(String)`**。`ChannelQueryService#resolveRecordedTarget` 的解析
   发生在 `recordQueryAttempt()` **之前**（脏数据不得消耗查询次数，spec 034 诊断②）。
   若把校验推迟到 `query()` 内部，那次计数就已落库。故保留一个只回答「可不可以」的先验校验，
   `RefundUnknownQueryScheduler` 同型复用。
3. **`RecordedTarget` 承载渠道码字符串而非 `PaymentChannel`**（两个反向路径类各自私有），
   实现由门面在调用时解析——Payment 侧因此不持有任何渠道实现类型。

**第 6 处（`ChannelPluginCallbackController`）刻意不走门面**：
038 已把它搬进 `com.payment.channelgateway.api`，它不再是「Payment 侧越界调用方」，
而是网关域自己的 HTTP 入口；其 `registry.resolve()` 是**域内合法**用法。
更关键的是 FR-007 明确要求 `ChannelPlugin` 成为网关域私有实现——
若为它开一个「取回调插件」的门面方法，等于把 `ChannelPlugin` 重新泄出。
该处的收口由 **T5**（`ChannelCallbackHandler` + `PaymentNotifyPort`）在域内完成。

**`ChannelAdminController` / `RoutingProperties` 的收口状态**：
038 已把两者整体搬入 `channelgateway.api` / `channelgateway.infra.config`，
「Payment 可触达」这一越界事实**已由 038 消除**，故本项不再需要额外改动，标记为完成。
（对照：038 的 `ServiceBoundaryTest.LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` 白名单
登记的 4 个类，其收口归 T5。）

**实测**：`ChannelGatewayTest` 11/11 绿；payment 侧 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`
的**代码引用**为零（仅 `PaymentApplicationService` 一处 javadoc 提及）；
`./mvnw -B clean test` = 1016 tests / 0F / 0E / 18 模块 SUCCESS（含 038 的 16 条 `ServiceBoundaryTest` 门禁）。

## T5　回调两层：模板方法 + PaymentNotifyPort　[FR-009][FR-010][FR-011][FR-012][FR-013][INV-2][INV-5]

- [x] **红**：`ChannelCallbackHandlerTest`——断言四步顺序（选插件 → 验签 → 转换 → 网关单更新）；`PaymentNotifyPortTest`——断言收到事件含 `channelCode` 且不含渠道私有类型
- [x] **绿**：
  - 新增 `ChannelCallbackHandler`（`final` 入口，四步模板方法）
  - 新增 `PaymentNotifyPort`（Payment 定义 + 实现），`onChannelPayResult` / `onChannelRefundResult`
  - `ChannelPluginCallbackController.validate()` 中的 Payment 逻辑迁入 Port 实现
  - 以 `PaymentNotifyPort.onChannelRefundResult` 取代 `RefundResultListener`
  - `DyeContext` 读取点收敛至渠道网关域
- [x] **重构**：`ChannelPluginCallbackController` 只剩「收报文、交网关」
- [x] 验证：`payment-service` 单测全绿

### T5 执行记录（2026-09-25）

**落点**：

| 件 | 落点 | 说明 |
|---|---|---|
| `PaymentNotifyPort` / `PayNotifyOutcome` / `DefaultPaymentNotifyPort` | `com.payment.payment.application` | Payment 定义 + 实现（FR-010 / FR-012 / INV-2） |
| `ChannelCallbackHandler` / `ChannelCallbackAck` | `com.payment.channelgateway.application` | 网关域四步模板（FR-009） |
| `ChannelResult.fromNotified(...)` ×2 | `com.payment.channelgateway.application` | 跨域事件 → 网关域结果的还原工厂 |

**与 spec 字面的三处差异（均为落地时的判据，不是遗漏）**：

1. **类名 `ChannelCallbackHandler` 而非 `AbstractChannelCallbackHandler`**。四步全部是内核自己的
   职责，**没有 per-channel 子类**——「模板方法」体现在 `final handle(...)` + 固定的私有步骤，
   而不是「留一个抽象钩子给子类」。写成 `abstract` 会凭空造出一个永远不会有的继承点。
   （渠道差异确实存在，但全部收在 `ChannelPlugin` 的钩子里，那才是正确的扩展点。）
2. **第 ④ 步「网关单更新」落为「内核统一封装跨域事件 + 跨域通知」**。代码里**不存在**「网关单」
   这个聚合：渠道交互的事实载体是 `payment_attempts`，其写入口唯一归属 `ChannelAttemptRecorder`
   端口，且由 Payment 侧 `PaymentResultProcessor` 统一编排。内核若在此再推一次 attempt 状态，
   就出现**第二个写入口**——两套不变量必然漂移，正是 INV-5 要防的。故 ④ 的「内核统一」落在
   「跨域事件的封装口径只有这一处」。
3. **入向契约的 `channelNo` 改为可空**（`ChannelPayNotified` / `ChannelRefundNotified`）。
   入向通知的寻址键是 `paymentNo` / `refundNo`（渠道只会给商户单号），网关单号是**平台侧**标识；
   在回调这一刻反查它只是为填一个下游不消费的字段，却要多一次未必成功的读库
   （`AbstractMockChannelAdapter` 的异步退款推送在推送时刻只知道 `refundNo`）。
   出向契约（`ChannelPayCommand` 等）仍**强制**必填；`ChannelContractTest` 未断言该字段必填，
   故零回归。

**`RefundResultListener` 的取代**：接口删除；`MockRefundResultBridge` 一并删除（其职责由
`DefaultPaymentNotifyPort` 承接）；`AbstractMockChannelAdapter` 的注入点由
`setRefundResultListener` 改为 `setPaymentNotifyPort`，推送时构造 `ChannelRefundNotified`。
`PaymentAutoRefundServiceTest` 的注入点同步改为内联 `PaymentNotifyPort` 替身（**断言零变化**）。

**038 白名单收口**：`ChannelPluginCallbackController` 改造后依赖全部落在网关域内，
已从 `LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` **移除**（4 → 3）。规则同时新增
`PaymentNotifyPort` / `PayNotifyOutcome` 子树例外（FR-016 ② 的括号例外）。

**T5b（FR-013）**：模态的**施加**收进 `ChannelGateway`——新增
`refund(String, DyeMode, RefundRequest)` / `query(String, DyeMode, QueryStatusRequest)` 重载与
`isSandboxRequest()` 探针。四个读取点同步改造：`ChannelQueryService` / `PaymentRefundService` /
`RefundUnknownQueryScheduler` / `PaymentController`。改造后
`com.payment.payment.application..` 与 `com.payment.payment.api..` 对 `DyeContext` 的
**代码引用为零**（仅 `DefaultPaymentNotifyPort` 一处 `{@code}` javadoc 说明，不产生编译期依赖）。
`payment.infra` 的两处（`ChannelAttemptRecorderImpl` / `InMemoryPaymentAttemptRepository`）
**本轮不动**——它们既非「应用层」也非「api 层」，超出 FR-013 字面口径；见 T7 的覆盖面说明。

**🔴 T5b 缺陷与修复（2026-09-25，值得记档）**：`PaymentController` 由「单构造器」变为
「4 参主构造 + 3 参兼容构造」后，**Spring 无法再唯一确定用哪个构造器**，直接抛
`BeanInstantiationException: No default constructor found`（`NoSuchMethodException: <init>()`），
让**所有**加载该 Controller 的 Spring 测试连锁失败——`PaymentPersistenceTest` /
`ChannelCallbackSecurityTest$UnconfiguredSecret` / `InternalServiceAuthTest$Disabled` /
`$PlatformTokenFallback` / `$UnconfiguredToken` / `PaymentApplicationTests` /
`RefundApplicationTests` 共 **20 个 errors**。

修复：给 4 参主构造补 `@Autowired`（**代码库既有惯例**——`PaymentApplicationService` /
`PaymentPersistence` / `PaymentResultProcessor` / `RefundAttemptSettlementService` /
`DefaultChannelGateway` 的多构造器类全部如此标注，注释统一为「生产主构造：Spring 必须唯一
确定地选它（另有测试用兼容构造，故显式标注）」）。

> ⚠️ **教训**：这 20 个 errors 的日志里同时有 `RedisBusyException: BUSYGROUP` 与
> `CommunicationsException: MySQL link failure`（因另一 worktree 并发争用同一 Redis/MySQL），
> 极易被误判为「纯环境噪声」。**判据**：`Caused by` 里出现 `No default constructor found`
> 这类**确定性代码缺陷**时，环境噪声假设必须先被排除——本次靠 `Caused by` 计数
> （17 次 `No default constructor` vs 12 次 `BUSYGROUP`）才定位到真因。
> 若只看到「ApplicationContext 加载失败」就归因环境，这个缺陷会被带进 master。

**实测**：`ChannelCallbackHandlerTest` 11/11 + `PaymentNotifyPortTest` 12/12；
`ServiceBoundaryTest` 18/18（新增 2 条）；全量 `clean test` 见 T8。

## T6　存量渠道迁移　[FR-014][FR-015]

- [ ] **红**：断言 5 家渠道全部 `extends AbstractChannelPlugin`
- [ ] **绿**：MOCK / WECHAT / ALIPAY / DOUYIN 迁至 `AbstractChannelPlugin`
- [ ] **重构**：删除 `AlipayNotifyController` 及其 2 个测试，回调统一走通用端点
- [ ] 验证：`payment-service` 单测全绿

> 🛑 **停工上报（2026-09-25）：T6 未执行，触发「既有断言必须改」停工条件。**
>
> **事实**：`AlipayNotifyController` 被 **4 个**既有测试类作为被测对象或载体
> （`com.payment.payment.api.AlipayNotifyControllerTest`、`...api.AlipayNotifyValidationTest`、
> `...application.PaymentCallbackPathParityTest`、`...application.PaymentCallbackValidationTest`）。
> 其中 `PaymentCallbackValidationTest#appIdMismatchRejects` 断言
> **`payment.notify_rejected` 的 `reason=app_id` 维度必须 +1**。
> 而通用插件端点**按设计**把渠道私有身份校验（支付宝 `app_id`）交给插件（属「①签名/身份」段），
> 签名失败统一走 `reason=signature`——**该断言在迁移后无处可依**。
>
> **为什么不能「顺手改掉」**：这正是用户纪律里写明的停工条件
> （「既有单测断言必须零变化（NFR-2）。若某个断言必须改，停下报告，不要自己改」），
> 且 FR-015 与 NFR-2 在此**直接冲突**——两条都是本 Spec 的硬约束。
>
> **另有两项同类阻塞**（同一批断言载体）：
> - `PaymentCallbackPathParityTest` 的「两条路径」前提在删除专属端点后不再成立
>   （需要重写成「通用插件端点 vs 平台内部 JSON 端点」并重建 ALIPAY 插件桩）；
> - 删除 `AlipayChannelAdapter`（ALIPAY 迁插件必然结果）会波及
>   `channelgateway/infra/alipay` 下的 `AlipayDualModeTest` / `AlipaySandboxChargeTest`
>   / `AlipayAmountConversionTest` 的装配方式。
>
> **另外**：WECHAT 已被 **spec 039** 一步到位迁为插件（`WechatChannelPlugin`），
> 且 039 已改动 `MockChannelAdapter.java`（T6 的目标文件之一）与
> `docs/architecture/systems/payment-service.md`（T8 的目标文件）——两条线在此**必然撞车**。
>
> ---
>
> ### 🔴 复核后的补充证据（2026-09-25 第二轮，两条更硬的事实）
>
> **① FR-014 与 038 的既有设计意图直接冲突。**
> `ChannelPlugin` / `AbstractChannelPlugin` 都是 **038**（commit `793019c`）引入的。
> `ChannelPlugin` 的类注释原文写着：
> > 「二者分离让既有渠道（`AbstractMockChannelAdapter` 一族）**零改动**继续工作，
> > 新渠道则按插件范式接入。**刻意不做强制迁移**——一次性重写 4 个 Adapter
> > 会把『架构演进』变成『全渠道回归测试』，收益不成比例。」
>
> 而 FR-014 要求「MOCK / WECHAT / ALIPAY / DOUYIN 四家 **MUST** 迁移」。
> 两者对「要不要强制迁移」给出了**相反**的结论，且都是白纸黑字的硬约束。
> 这正落在用户纪律的停工条件「发现 Code ≠ System Design 漂移 / Spec 与代码事实冲突」。
>
> **② 「迁移」不是换基类，而是合并两个功能不等价的基类。**
> 逐项比对 `AbstractMockChannelAdapter`（`implements PaymentChannel`）与
> `AbstractChannelPlugin`（`implements ChannelPlugin`）：
>
> | 能力 | `AbstractMockChannelAdapter` | `AbstractChannelPlugin` |
> |---|---|---|
> | 金额尾数确定性故障注入（spec 022） | 有 | 有（口径重复一份） |
> | 每实例独立 `runId` | 有 | 有（口径重复一份） |
> | **退款「受理 + 异步推送」**（`scheduleRefundPush` → `PaymentNotifyPort`） | **有** | **无** |
> | `mock-scenario` 严格枚举解析（ADR-0049） | 有 | 无（改用 `isRealModeEnabled`） |
> | 模板方法四步（能力校验 / 模态门控 / 模态分派 / 异常兜底） | **无** | **有** |
> | `parseCallback` / `callbackAckBody` | 无 | 有 |
>
> 也就是说：若把 MOCK 直接改 `extends AbstractChannelPlugin`，**mock 退款异步推送链路
> 会立刻断掉**——那是 E2E 演示（`demo/scenario-refund.sh`）与 spec 019/D7 的核心链路。
> 要保住它，就得把 `scheduleRefundPush` 一并搬进 `AbstractChannelPlugin`（或让新基类同时
> 具备两套能力）。**这是一次有行为影响的架构合并，不是 FR-014 假设的机械迁移**，
> 且 `AbstractMockChannelAdapter` 刚在 T5 被改过（注入点换 `PaymentNotifyPort`），
> 叠加改动会显著放大回归面。
>
> **③ 成本复核（修正首轮上报的估计）**：真正「断言内容必须改」的**只有 1 条**
> ——`PaymentCallbackValidationTest#appIdMismatchRejects`（`reason=app_id`）。
> 其余 `reason=amount` / `currency` / `channel_reference` 三类断言**可原样保留**，
> 因为 T5 已把这套校验逐字迁入 `DefaultPaymentNotifyPort.validate()`，指标维度不变。
> 四个测试类的主要成本是**装配方式改写**（`new AlipayNotifyController(...)` +
> `onNotify(form)` → `new ChannelCallbackHandler(...)` + `handle("ALIPAY", envelope)`），
> 而非断言改写。
>
> **需要裁决**：
> - **(a) 收窄 T6**：只做 ALIPAY / DOUYIN 的插件化；**保留** `AlipayNotifyController`
>   （FR-015 不执行）与 `AbstractMockChannelAdapter`（MOCK 不迁移）→ **零断言变更、零 039 撞车**；
> - **(b) 完整执行 FR-014 + FR-015**：放行改 `appIdMismatchRejects` 这 1 条断言
>   （接受 `app_id` 监控维度消失），并接受「两个基类能力合并」的重构
>   + 与 039 在 `MockChannelAdapter.java` 上的合并冲突；
> - **(c) 本轮跳过 T6**：先合入已完成的 T5 / T5b / T7，T6 待 039 合入 master 后单独排期
>   （届时 WECHAT 已就位、撞车面消失）。
>
> **未获裁决前不执行 T6。**

## T7　ArchUnit 门禁　[FR-016]

- [x] **红**：三条规则先落阳性对照（故意违规能触发）
- [x] **绿**：① Payment 应用/api 层禁依赖渠道内部件；② 渠道域禁依赖 Payment/refund 应用实现（`PaymentNotifyPort` 除外）；③ `DyeContext` 仅限渠道域
- [x] 验证：`ServiceBoundaryTest` 全绿，阳性对照存在

### T7 执行记录（038 已覆盖大部分，本 T 只补未覆盖的两条）

| FR-016 条目 | 状态 | 落点 |
|---|---|---|
| ① Payment 应用/api 层禁依赖 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin` | **本 T 新增** | `ServiceBoundaryTest#paymentApplicationAndApiMustNotReachChannelGatewayInternals` |
| ② 渠道网关域禁依赖 payment 应用实现（`PaymentNotifyPort` 除外） | **038 已有 + 本 T 补例外** | `ServiceBoundaryTest#paymentAndChannelGatewayMustKeepOneWayDependency`（新增 `INBOUND_PORT_EXCEPTION`，白名单 4 → 3） |
| ③ `DyeContext` 不得在 Payment 应用/api 层被读取 | **本 T 新增** | `ServiceBoundaryTest#dyeContextMustStayInsideChannelGatewayDomain` |

**为什么 ① 是必须补的**：038 的 `channelRoutingAbstractionMustNotDependOnChannelInfrastructure`
只约束**反方向**（`payment.application` → `channelgateway.infra`）。而「Payment 侧直接持有
`ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`」这条**正方向越界**恰好落在
`channelgateway.application`（不是 `.infra`），**长期不在任何规则覆盖内**——这正是 spec §1.1
记录的 9 处现状。本 T 把 T4 的收口变成不可回退的构建期事实。

**③ 的覆盖面（与 FR-016 ③ 字面的差异，已上报）**：规则 `that()` 取
`payment.application..` + `payment.api..`——这是 **FR-013 的原文口径**。FR-016 ③ 措辞更宽
（「仅限渠道网关域」），但那会要求把 `payment.infra` 的 `ChannelAttemptRecorderImpl` /
`InMemoryPaymentAttemptRepository` 也搬出 payment 域，属**独立的服务边界裁决**，不在本 Spec
的机械收口范围。本轮按 FR-013 口径落地并登记为后续项。

**三条规则的阳性对照**（防空转，均为「先证明可命中再断言否定式」）：
- ①：payment 应用/api 有主体（>20）+ 三件套在导入的字节码里真实存在（==3）+ **网关域自己真的在用它们**（>0，证明「够得着」）；
- ②：两侧都真有类（>20 / >20）；
- ③：payment 应用/api 有主体（>20）+ **网关域真的有类在读 `DyeContext`**（>0）。
  ⚠️ 刻意**不**断言「`DyeContext` 存在于导入类集」——本模块只导入各服务 `target/classes`，
  `common-core` 不在其中，`DyeContext` 在 ArchUnit 模型里是**桩类**；断言其「存在」必然为假
  （本轮实测踩到过，已就地注明原因）。

**实测**：`ServiceBoundaryTest` 18/18 绿（16 条既有 + 2 条新增）；`architecture-tests` 全模块 23/23。


## T8　收口

- [ ] 回填 `acceptance.md` 实测结论
- [ ] 更新 `docs/adr/README.md` 与 `traceability.md`（若新增 ADR）
- [ ] feature 分支 → `--no-ff` 合入 master
