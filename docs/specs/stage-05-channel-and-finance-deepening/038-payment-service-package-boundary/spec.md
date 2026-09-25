# 038-payment-service-package-boundary — Spec

> **Status**: Draft `<!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->`
> **Date**: 2026-09-25
> **Stage / Path**: `docs/specs/stage-05-channel-and-finance-deepening/038-payment-service-package-boundary/`
> **Related ADR**: 待实现阶段补（本 Feature 为**纯结构重构**，若判定需要 ADR 则新建）
> **Standard**: [spec-standard.md](../../../standards/spec-standard.md)

> **阅读约定**：`【现状】`= 已核实的代码事实（附路径 / 行号，均已 grep 实证）；`【目标】`= 设计意图，**尚未实现**。
> **本 Spec 是执行方唯一事实源**：`tasks.md` 的逐条打勾清单、文件移动映射表、验收命令均以此为准。

---

## 1. 背景（Problem）

### 1.1 `payment` 与 `refund` 两个顶层包**双向循环 import**

`com.payment.refund` 作为与 `com.payment.payment` **平级**的顶层包，在结构上宣称自己是独立限界上下文。但代码实证是**双向纠缠**：

| # | 【现状】 | 证据（grep 实证） |
|---|---|---|
| 1 | `payment` 包有 **4 个文件** import `com.payment.refund` | `payment/application/PaymentAutoRefundService.java`、`payment/application/RefundAttemptSettlementService.java`、`payment/mq/PaymentMqHandlers.java`、`payment/PaymentApplication.java` |
| 2 | `refund` 包有 **10 个文件** import `com.payment.payment` | `refund/api/RefundController.java`、`refund/application/MockRefundResultBridge.java`、`refund/application/RefundApplicationService.java`、`refund/application/RefundAttemptSettlementGateway.java`、`refund/application/RefundFactsService.java`、`refund/application/RefundResultProcessor.java`、`refund/application/RefundRpcCallbackService.java`、`refund/application/RefundUnknownQueryScheduler.java`、`refund/infra/client/LocalPaymentRefundGateway.java`、`refund/infra/client/LocalRefundAttemptSettlementGateway.java` |
| 3 | 结论：**这不是两个限界上下文，是一个域被切成了两半，且切错了位置** | 双向 import = 包边界失效的直接证据 |

### 1.2 分包错位导致「同一职责被写成两份」

| # | 【现状】 | 证据 |
|---|---|---|
| 4 | 两份退款应用服务 | `payment/application/PaymentRefundService.java` 与 `refund/application/RefundApplicationService.java` |
| 5 | 两份**同名**接口 `LedgerPostingGateway` | `payment/application/LedgerPostingGateway.java` 与 `refund/application/LedgerPostingGateway.java` |
| 6 | 两份退款结算职责 | `payment/application/RefundAttemptSettlementService.java` 与 `refund/application/RefundAttemptSettlementGateway.java` |
| 7 | 两套退款 HTTP 入口 | `payment/api/PaymentRefundCommandController.java`（`/internal/payments/refund-command`）、`payment/api/RefundRpcController.java`（`/internal/payments/query-amount`、`/internal/payments/refund-attempt`）**对** `refund/api/RefundController.java`（`/internal/refunds/{refundNo}`、`/{refundNo}/resolve`、`/{refundNo}/channel-callback`）与 `refund/api/RefundFactsController.java`（`/internal/refunds/confirmed-facts`） |

### 1.3 渠道网关件散落在 payment 域的 4 个子包里

| # | 【现状】 | 证据 |
|---|---|---|
| 8 | 渠道件共 **40 个主源码文件**，分散在 `payment/application/channel/**`(17)、`payment/infra/channel/**`(15)、`payment/api/`(5)、`payment/web/`(1)、`payment/infra/config/`(2) | 完整清单见 §3.1 移动映射表 |
| 9 | 后果：`ChannelGateway` 这个「进程内微服务边界」在物理结构上不存在 | 037（渠道网关边界）的 ArchUnit 门禁只能写成「禁依赖某些具体类」，退化成硬编码渠道名——正是 036 诊断出的脆弱点 |

### 1.4 关键实现约束（执行方 MUST 先读）

| # | 【现状】 | 证据 |
|---|---|---|
| 10 | `PaymentApplication` **显式枚举**扫描包，新增顶层包**必须同步登记，否则 Spring 扫不到、启动即崩** | `payment/PaymentApplication.java:14` `@SpringBootApplication(scanBasePackages = {"com.payment.payment", "com.payment.refund", "com.payment.posting"})`；`:16` `@EnableFeignClients(basePackages = {"com.payment.payment", "com.payment.refund"})`；`:18-22` `@MapperScan({...})` 四个包 |
| 11 | `posting` 是**跨切面出站失败台账**（spec 034），不属于「资金动作域」也不属于「渠道网关域」 | `com.payment.posting` 独立子域包，ADR / spec 034 明确 |

---

## 2. 目标（Goal）

| ID | 目标 | 判定方式 |
|---|---|---|
| G1 | 消灭 `com.payment.refund` 顶层包——`refund` 降为 payment 域内的**操作切片**，与 `pay` / `queryOrder` / `queryRefund` 同级 | SC-001 |
| G2 | 建立 `com.payment.channelgateway` 包，渠道件 100% 收拢其内 | SC-002 |
| G3 | 退款 HTTP 入口收口到单一前缀 `/internal/payments/refunds/**`，消除 `/internal/refunds/**` 双前缀 | SC-005 |
| G4 | 结构性约束由 ArchUnit **包级规则**强制，不再硬编码渠道类名 | SC-006 |
| G5 | 全量单测零回归（本 Feature 为纯结构重构，**行为零变化**） | SC-007 |

---

## 3. 范围（Scope）

**服务边界**：仅 `payment-service` 内的包结构；**跨服务影响**：`reconciliation-service` 1 处 Feign 路径 + `deployment/` 下 5 处调用方（§3.3）。

**是否动 schema**：**否**（不改任何表结构、不加列）。
**是否动公共 API**：**是**——退款端点前缀变更（§3.3）。HTTP 请求/响应**字段名与语义不变**。
**是否动状态机**：**否**（`RefundStatus` / `PaymentStatus` 枚举值与其迁移条件一律不动）。
**是否动领域模型**：**否**（字段、约束、校验逻辑一律不动，只改所在包）。

### 3.1 移动映射表 A：渠道件 → `com.payment.channelgateway`（40 个主源码文件）

> 根路径前缀：`payment-service/src/main/java/com/payment/`

| 源路径 | 目标路径 | 文件数 |
|---|---|---|
| `payment/api/` | `channelgateway/api/` | 4 |
| `payment/api/dto/` | `channelgateway/api/dto/` | 1 |

`payment/api/` → `channelgateway/api/` 明细：`AlipayNotifyController.java`、`ChannelAdminController.java`、`ChannelCallbackController.java`、`ChannelPluginCallbackController.java`
`payment/api/dto/` → `channelgateway/api/dto/` 明细：`ChannelCallbackRequest.java`

| 源路径 | 目标路径 | 文件数 |
|---|---|---|
| `payment/application/channel/` | `channelgateway/application/` | 11 |
| `payment/application/channel/spi/` | `channelgateway/application/spi/` | 6 |

`payment/application/channel/` 明细：`ChannelAttemptRecorder`、`ChannelRegistry`、`ChannelResult`、`ChannelRouter`、`ChargeRequest`、`PaymentChannel`、`QueryStatusRequest`、`RefundRequest`、`RefundResultListener`、`RouteContext`、`SingleChannelRegistry`
`payment/application/channel/spi/` 明细：`AbstractChannelPlugin`、`ChannelCallbackEnvelope`、`ChannelPlugin`、`ChannelPluginDescriptor`、`ChannelPluginFactory`、`ParsedCallback`

| 源路径 | 目标路径 | 文件数 |
|---|---|---|
| `payment/infra/channel/` | `channelgateway/infra/` | 8 |
| `payment/infra/channel/alipay/` | `channelgateway/infra/alipay/` | 2 |
| `payment/infra/channel/stripe/` | `channelgateway/infra/stripe/` | 5 |
| `payment/infra/config/` | `channelgateway/infra/config/` | 2 |
| `payment/web/` | `channelgateway/web/` | 1 |

`payment/infra/channel/` 明细：`AbstractMockChannelAdapter`、`AlipayChannelAdapter`、`ChannelPluginFactoryLocator`、`ConfiguredChannelRouter`、`DouyinChannelAdapter`、`MockChannelAdapter`、`SpringChannelRegistry`、`WechatChannelAdapter`
`payment/infra/channel/alipay/` 明细：`AlipayGateway`、`AlipaySdkGateway`
`payment/infra/channel/stripe/` 明细：`StripeChannelPlugin`、`StripeChannelPluginFactory`、`StripeGateway`、`StripeSandboxProperties`、`StripeSdkGateway`
`payment/infra/config/` 明细：`AlipaySandboxProperties`、`RoutingProperties`（**该目录全部搬空**）
`payment/web/` 明细：`ChannelCallbackSignatureFilter`（**仅此一个**，其余 `CachedBodyHttpServletRequest` / `InternalServiceAuthInterceptor` / `MockCashierProperties` / `ResolveAuthorizationInterceptor` / `WebConfig` 留在 `payment/web/`）

**包名重写规则**（必须按此顺序做前缀替换，长前缀优先）：

```
com.payment.payment.application.channel.spi  → com.payment.channelgateway.application.spi
com.payment.payment.application.channel      → com.payment.channelgateway.application
com.payment.payment.infra.channel.alipay     → com.payment.channelgateway.infra.alipay
com.payment.payment.infra.channel.stripe     → com.payment.channelgateway.infra.stripe
com.payment.payment.infra.channel            → com.payment.channelgateway.infra
com.payment.payment.infra.config             → com.payment.channelgateway.infra.config
com.payment.payment.api.dto                  → com.payment.channelgateway.api.dto   （仅 ChannelCallbackRequest）
com.payment.payment.api                      → com.payment.channelgateway.api       （仅上表 4 个 Controller）
com.payment.payment.web                      → com.payment.channelgateway.web       （仅 ChannelCallbackSignatureFilter）
```

### 3.2 移动映射表 B：`com.payment.refund` → payment 域内切片（33 主 + 10 测试）

> **分层原则**：`api` / `domain` / `infra` **平铺吸收**（类名本身带 `Refund` 前缀可自辨，与 `Payment` / `PaymentAttempt` 同域平铺）；**仅 `application` 层建 `refund/` 操作切片**。

| 源路径 | 目标路径 | 文件数 | 明细 |
|---|---|---|---|
| `refund/api/` | `payment/api/` | 4 | `RefundController`、`RefundFactsController`、`RefundResponse`、`ResolveRefundRequest` |
| `refund/api/dto/` | `payment/api/dto/` | 1 | `RefundFactResponse` |
| `refund/application/` | `payment/application/refund/` | 10 | `CreateRefundCommand`、`LedgerPostingGateway`、`MockRefundResultBridge`、`PaymentRefundGateway`、`RefundApplicationService`、`RefundAttemptSettlementGateway`、`RefundFactsService`、`RefundResultProcessor`、`RefundRpcCallbackService`、`RefundUnknownQueryScheduler` |
| `refund/domain/` | `payment/domain/` | 6 | `Refund`、`RefundDecision`、`RefundItem`、`RefundPolicy`、`RefundRepository`、`RefundStatus` |
| `refund/infra/` | `payment/infra/` | 1 | `InMemoryRefundRepository` |
| `refund/infra/client/` | `payment/infra/client/` | 4 | `LedgerFeignClient`、`LocalPaymentRefundGateway`、`LocalRefundAttemptSettlementGateway`、`RefundFeignLedgerPostingGateway` |
| `refund/infra/persistence/refund/` | `payment/infra/persistence/refund/` | 6 | `MybatisRefundRepository`、`RefundEntity`、`RefundIntakeLockMapper`、`RefundItemEntity`、`RefundItemMapper`、`RefundMapper` |
| `refund/` (test) | `payment/` (test) | 10 | 见下表 |

测试目录（`payment-service/src/test/java/com/payment/`）：

| 源 | 目标 |
|---|---|
| `refund/RefundApplicationTests.java` | `payment/RefundApplicationTests.java` |
| `refund/application/RefundApplicationServiceTest.java` | `payment/application/refund/RefundApplicationServiceTest.java` |
| `refund/application/RefundFactsServiceTest.java` | `payment/application/refund/RefundFactsServiceTest.java` |
| `refund/application/RefundLedgerPostingTest.java` | `payment/application/refund/RefundLedgerPostingTest.java` |
| `refund/application/RefundMetricsTest.java` | `payment/application/refund/RefundMetricsTest.java` |
| `refund/domain/RefundAmountInvariantTest.java` | `payment/domain/RefundAmountInvariantTest.java` |
| `refund/domain/RefundPolicyTest.java` | `payment/domain/RefundPolicyTest.java` |
| `refund/domain/RefundStateMachineTest.java` | `payment/domain/RefundStateMachineTest.java` |
| `refund/integration/RefundScenarioTest.java` | `payment/integration/RefundScenarioTest.java` |
| `refund/support/RefundTestStack.java` | `payment/support/RefundTestStack.java` |

**包名重写规则**：

```
com.payment.refund.api.dto               → com.payment.payment.api.dto
com.payment.refund.api                   → com.payment.payment.api
com.payment.refund.application           → com.payment.payment.application.refund
com.payment.refund.domain                → com.payment.payment.domain
com.payment.refund.infra.persistence.refund → com.payment.payment.infra.persistence.refund
com.payment.refund.infra.client          → com.payment.payment.infra.client
com.payment.refund.infra                 → com.payment.payment.infra
```

### 3.3 移动映射表 C：渠道测试（8 个）

> 根路径前缀：`payment-service/src/test/java/com/payment/`

| 源 | 目标 |
|---|---|
| `payment/application/channel/ChannelAttemptRecorderContractTest.java` | `channelgateway/application/` |
| `payment/infra/channel/AlipaySandboxChargeTest.java` | `channelgateway/infra/` |
| `payment/infra/channel/ConfiguredChannelRouterTest.java` | `channelgateway/infra/` |
| `payment/infra/channel/DyeNotAffectingRoutingTest.java` | `channelgateway/infra/` |
| `payment/infra/channel/MockChannelAdapterScenarioTest.java` | `channelgateway/infra/` |
| `payment/infra/channel/alipay/AlipayAmountConversionTest.java` | `channelgateway/infra/alipay/` |
| `payment/infra/channel/alipay/AlipayDualModeTest.java` | `channelgateway/infra/alipay/` |
| `payment/infra/channel/alipay/AlipayNotifySignatureVerificationTest.java` | `channelgateway/infra/alipay/` |

### 3.4 退款 HTTP 入口收口（`FR-005`）

| 现状路径 | 目标路径 |
|---|---|
| `GET /internal/refunds/{refundNo}` | `GET /internal/payments/refunds/{refundNo}` |
| `POST /internal/refunds/{refundNo}/resolve` | `POST /internal/payments/refunds/{refundNo}/resolve` |
| `POST /internal/refunds/{refundNo}/channel-callback` | `POST /internal/payments/refunds/{refundNo}/channel-callback` |
| `GET /internal/refunds/confirmed-facts` | `GET /internal/payments/refunds/confirmed-facts` |

**冲突核查（已做）**：`/internal/payments` 现有子路径为 `refund-command`、`refund-attempt`、`query-amount`、`confirmed-facts`、`unknown`、`{paymentNo}/channel-callback`，与新增的 `refunds/**` **无冲突**。

**调用方 MUST 同步修改（6 个文件 / 8 处）**：

| # | 文件 | 行 |
|---|---|---|
| 1 | `reconciliation-service/src/main/java/com/payment/reconciliation/infra/client/RefundFactsFeignClient.java` | 22 |
| 2 | `deployment/mock-channel-web/src/main/java/com/payment/mockchannel/web/RefundCallbackProxy.java` | 73 |
| 3 | `deployment/e2e-tests/src/test/java/com/payment/e2e/support/Api.java` | 107 |
| 4 | `deployment/demo/scenario-refund.sh` | 69、97 |
| 5 | `deployment/demo/scenario-routing.sh` | 188 |
| 6 | `deployment/mock-channel-web/src/main/resources/static/demo.html` | 774 |

---

## 4. 非目标（Non-goals）

| 不做 | 理由 / 留给谁 |
|---|---|
| 不合并两份 `LedgerPostingGateway` | 属**行为改动**（记账口径），非结构重构；单列后续 Feature |
| 不合并 `PaymentRefundService` 与 `RefundApplicationService` | 同上；本轮只消灭「两个域」的错觉，不重排职责 |
| 不做 037 的门面 / `PaymentNotifyPort` / 回调分层 | 037 独立推进（本 Feature 只为其提供**包级门禁的落点**） |
| 不拆 Maven 模块 | 编译期强制留待边界稳定后再做（同 037 D4） |
| 不改 `RefundStatus` / `PaymentStatus` 状态机、不改 `transaction_refunds` / `refund_items` 表结构、不改任何 Feign 契约字段名 | 纯结构重构，行为零变化 |
| 不迁移 `com.payment.posting` | spec 034 明确的独立跨切面子域 |
| 不为 `pay` / `query` 建对称的操作切片目录 | pay 侧类已在 `payment/application` 根且被大量单测直接引用，移动收益 < 回归风险（决策 D2） |

---

## 5. 场景与用户故事（Scenarios / User Stories）

| ID | 角色 | 场景 → 期望 |
|---|---|---|
| US1 | 支付平台开发 | 打开 `payment-service` 包树，一眼看出只有两个业务域（`payment` 资金动作域 / `channelgateway` 渠道网关域）+ 一个跨切面子域（`posting`）；`refund` 不再是与 `payment` 平级的"第三个域" |
| US2 | 支付平台开发 | 新增一家渠道：只在 `channelgateway/infra/<channel>/` 下新增插件包，不改 `com.payment.payment.**` 任何文件 |
| US3 | 对账服务开发 | 拉退款事实只面对一个前缀：`/internal/payments/refunds/confirmed-facts`，与支付事实 `/internal/payments/confirmed-facts` 同源 |
| US4 | 运维 / 演示 | 改造后 `deployment/demo/scenario-refund.sh` 与演示控制台查库面板行为与改造前**完全一致**（三层退款单逐层可见） |

**失败流**（本 Feature 为结构重构，失败流集中于"漏改"）：

| # | 场景 | 触发 | 期望行为 |
|---|---|---|---|
| F1 | 新增顶层包漏登记扫描 | 搬包后未改 `PaymentApplication` 三个注解 | 应用启动报 Bean 找不到；**执行方 MUST 在 T2 显式登记**（FR-002）并由 SC-003 断言 |
| F2 | 调用方漏改端点前缀 | 只改了 payment-service、未改 `reconciliation-service` Feign | 对账拉退款事实 404；由 SC-005 的 6 处 grep 断言拦截 |
| F3 | 包名替换顺序错误 | 先替换 `...application.channel` 再替换 `...application.channel.spi` | `spi` 包被写成 `com.payment.channelgateway.application.spi` 之外的错误名（如 `...channelgateway.application.spi` 重复嵌套）；由 §3.1 的"长前缀优先"规则 + 编译拦截 |
| F4 | 同包引用未补 import | 原 `payment/api` 包内互相引用无需 import | 编译报错；执行方 MUST 全量编译后逐个补 import（036 已踩过此坑） |

---

## 6. 功能需求（Functional Requirements）

| ID | 需求 |
|---|---|
| FR-001 | 系统 MUST 建立顶层包 `com.payment.channelgateway`，并按 §3.1 映射表把 **40 个渠道主源码文件**迁入；包名按 §3.1 重写规则改写（**长前缀优先**） |
| FR-002 | `PaymentApplication` MUST 同步登记新包：`scanBasePackages` 增加 `com.payment.channelgateway`、**移除** `com.payment.refund`；`EnableFeignClients.basePackages` 同改；`MapperScan` 移除 `com.payment.refund.infra.persistence` 并改为 `com.payment.payment.infra.persistence.refund` |
| FR-003 | `com.payment.refund` 顶层包 MUST 被消灭；按 §3.2 映射表把 **33 个主源码 + 10 个测试文件**迁入 `com.payment.payment.**`；包名按 §3.2 重写规则改写 |
| FR-004 | `application` 层 MUST 建退款操作切片 `com.payment.payment.application.refund`，与支付 / 查询操作同层级；`api` / `domain` / `infra` 层平铺吸收，`Refund` / `RefundItem` / `RefundPolicy` 与 `Payment` / `PaymentAttempt` 同域共存于 `com.payment.payment.domain` |
| FR-005 | 退款 HTTP 入口 MUST 收口到 `/internal/payments/refunds/**`（§3.4 四条路径映射）；请求 / 响应**字段名与语义零变化** |
| FR-006 | §3.4 表中 **6 个调用方文件 / 8 处**引用 MUST 全部同步为新路径 |
| FR-007 | `RefundController` 与 `RefundFactsController` MUST 合并为单个 `com.payment.payment.api.RefundController`，`@RequestMapping("/internal/payments/refunds")`，承载 §3.4 全部四个端点 |
| FR-008 | `com.payment.posting` MUST 保持独立顶层包，不并入 `payment` 或 `channelgateway` |
| FR-009 | ArchUnit MUST 新增三条**包级**门禁：① `com.payment.payment.**` 与 `com.payment.channelgateway.**` 之间**只允许** `payment → channelgateway` 单向依赖（`channelgateway` 仅可依赖 `common-*` 与自身；**例外白名单**：`com.payment.payment.domain.PaymentAttempt` 及 `ChannelAttemptRecorder` 相关既有 DIP，须在规则中显式声明）；② `com.payment.channelgateway.**` 内 MUST NOT 出现 `com.payment.refund` 引用；③ 新增渠道插件 MUST 位于 `com.payment.channelgateway.infra.<channel>` 下。三条均须含**阳性对照**防空转 |
| FR-010 | 全量改动 MUST 保持**行为零变化**：`./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿，且既有断言**一行不改** |
| FR-011 | L0 文档 MUST 同步：`docs/architecture/systems/payment-service.md` 中 `/internal/refunds/**` 端点清单改为新路径；`CHANGELOG.md` 记一笔破坏性变更。**ADR 正文不回改**（历史决策留痕） |

---

## 7. 业务规则与不变量（Business Rules）

| ID | 规则 / 不变量 | 违反时的处理 | 守卫者 |
|---|---|---|---|
| INV-1 | 顶层业务包 MUST 只有 `payment`（资金动作域）、`channelgateway`（渠道网关域）、`posting`（跨切面）三个 | 出现第四个顶层业务包即边界腐化 | ArchUnit FR-009 |
| INV-2 | `refund` MUST NOT 作为**域**存在——它是 `payment` 域内的一个**操作**，与 `pay` / `queryOrder` / `queryRefund` 同级 | 重建 `com.payment.refund` 顶层包即回退 | ArchUnit FR-009 ② |
| INV-3 | 渠道件 MUST 100% 位于 `com.payment.channelgateway.**`，不得残留在 `com.payment.payment.**` | 残留即插拔失效 | ArchUnit FR-009 ③ + grep 断言 |
| INV-4 | `PaymentApplication` 的扫描包清单 MUST 与实际顶层包**逐一对应** | 漏登记 = 启动即崩（无 Bean） | SC-003 显式断言 |
| INV-5 | 本 Feature MUST 保持行为零变化：不改状态机、不改表结构、不改契约字段名、不改记账口径 | 出现行为改动即越界 | SC-007 全量单测零回归 |
| INV-6 | 资金相关不变量（幂等键唯一、借贷平衡、金额精度、终态吸收口径）一律**沿用现状，本 Feature 不触碰** | — | 既有测试钉死 |

---

## 8. 状态与生命周期（State / Lifecycle）

**无变更。** 本 Feature 不动任何状态枚举与迁移条件；`RefundStatus` / `PaymentStatus` / `PaymentAttemptStatus` 原样迁移。

---

## 9. 错误与边界（Error / Edge Cases）

| 场景 | 触发条件 | 期望行为 | 判据 |
|---|---|---|---|
| 包扫描漏登记 | FR-002 未执行 | 应用启动失败（Bean 缺失） | SC-003 |
| 循环依赖复现 | `channelgateway` 反向 import `payment.application` | ArchUnit FR-009 ① 报红 | SC-006 |
| 端点 404 | 调用方漏改 | 集成测试 / E2E 报 404 | SC-005 |
| 同包引用编译错 | 搬包后未补 import | 编译失败 | SC-004（`mvn -B compile` 通过） |
| 空目录残留 | `git mv` 后留空包目录 | Maven 编译告警；应清理 | SC-001（`find` 无空目录） |

---

## 10. 幂等与重复处理（Idempotency）

**无变更。** 本 Feature 不引入任何新的资金入口、不改任何幂等键生成与判定逻辑。既有幂等纪律完整沿用（退款三层：order 在途守卫 → payment TXRF 幂等键 → `RefundPolicy` 累计上限）。

---

## 11. 验收标准（Acceptance Criteria）

| ID | 验收标准 | 验证方式 |
|---|---|---|
| SC-001 | `com.payment.refund` 目录不存在；`payment-service/src/**` 下 grep `com.payment.refund` **零命中** | `grep -rn "com\.payment\.refund" payment-service/src/ \| wc -l` = 0；`find` 无空目录 |
| SC-002 | 40 个渠道件全部位于 `com.payment.channelgateway/**`；`com.payment.payment.**` 下 grep `ChannelPlugin\|ChannelRegistry\|ChannelRouter\|AbstractMockChannelAdapter` **零命中**（`ChannelAttemptRecorder` / `ChannelResult` 的**接口引用**除外，须显式列出） | grep 断言 + 目录清单比对 §3.1 |
| SC-003 | `PaymentApplication` 三个注解的包清单与实际顶层包逐一对应 | 人工核对 + 应用能启动（`/actuator/health` 或集成测试） |
| SC-004 | `./mvnw -B -pl payment-service -am compile` 通过，零错误零告警中断 | 命令执行 |
| SC-005 | §3.4 六处调用方全部改为新路径；全仓 grep `/internal/refunds` 在 `src` 与 `deployment/demo`、`deployment/e2e-tests` 下**零命中** | `grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . \| grep -v "\.workbuddy/"` = 0 |
| SC-006 | FR-009 三条 ArchUnit 规则全绿，且阳性对照能触发违规 | `./mvnw -B -pl deployment/architecture-tests -am test` |
| SC-007 | 全量单测零回归，既有断言一行未改 | `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿（基线见 `acceptance.md`） |
| SC-008 | 演示链路回归（人工）：跑 `deployment/demo/scenario-refund.sh`，退款三层（TXRF / PMRF / `payment_attempts` REFUND）逐层可见，与改造前一致 | 演示步骤见 `acceptance.md` |

---

## 12. 依赖（Dependencies）

**硬依赖（不做完无法开始）**
- `feature/037-channel-gateway-boundary` **当前禁止合入 master**（`channel_no NOT NULL` 已加 DDL 但持久层未映射）。本 Feature **从 master 起分支**，不依赖 037 代码。⚠️ 若 037 先合入，038 需重新对齐其改动。

**软依赖（可并行）**
- 036（Channel 微内核 + 插件化 + Stripe）已合入 master —— 本 Feature 依赖其**插件目录形态**作为迁移目标结构。
- spec 034（出站失败台账）—— 决定 `posting` 保持独立（FR-008）。

---

## 13. 相关文档（Related Documents）

- [ADR-0072 两层职责](../../../adr/) — `Payment` / `PaymentAttempt` 分层
- [ADR-0063 跨系统标识](../../../adr/) — 业务单号纪律（037 相关，本 Feature 不触碰）
- [payment-service System Design](../../../architecture/systems/payment-service.md) — L0，FR-011 需同步端点清单
- [037-channel-gateway-boundary](../037-channel-gateway-boundary/spec.md) — 渠道网关边界收口（本 Feature 为其提供包级门禁落点）。
  ⚠️ 其**代码**仍在 `feature/037-channel-gateway-boundary` 分支且禁止合入（持久层未映射 `channel_no`）；文档已在 master。
- [Constitution §Governance](../../../../.specify/memory/constitution.md) — 公共 API 变更属人类决策边界
