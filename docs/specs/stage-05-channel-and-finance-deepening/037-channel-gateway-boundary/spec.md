# Spec: 037-channel-gateway-boundary（渠道网关域边界收口）

**Feature**：037　**标题**：Channel Gateway Boundary（渠道网关作为进程内微服务边界）
**版本**：v1.0（Draft）　**日期**：2026-09-25
> **Status**: Implemented <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->
**前置**：036（Channel 微内核 + 插件化 + Stripe 沙箱，代码已合入 master，本 Spec 为其边界收口）
**输入权威**：[Constitution §Governance / §Architecture](../../../../.specify/memory/constitution.md)、
[ADR-0063 跨系统标识](../../../adr/)、[ADR-0072 两层职责](../../../adr/)

**阅读约定**：`【现状】`= 已存在的事实（附 `path:line`）；`【目标】`= 设计意图，**尚未实现**；`【待确认】`= 人类决策。

---

## 0. 定位与一句话目标

**把渠道网关做成「进程内微服务」：Payment 只通过一个门面调用它，渠道差异 100% 关在网关域内，新增渠道不改 Payment、不改网关内核。**

036 已经把「插件化」这件事做对了一半——新增 Stripe 时对 `application`/`api`/`domain` 零改动。但 **Payment 与 Channel 的边界没有真正分开**（§1.1），同时 **跨域契约在说 Payment 的语言、且用了数据库自增主键**（§1.2）。本 Feature 补的就是这两件事。

**明确不做**：不拆 Maven 模块（编译期强制留待后续，本轮靠 ArchUnit 门禁）；不引入 MQ；不改渠道路由算法；不动账本科目体系（渠道账户 seed 纪律沿用 036）。

---

## 1. Background：证据化的现状

### 1.1 边界没有门面——Payment 侧直接摸渠道内部件

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | Payment 侧 **9 个类**直接依赖 `ChannelRegistry` / `ChannelRouter` / `PaymentChannel` | grep 全量：`PaymentApplicationService`、`PaymentRefundService`、`RefundAttemptSettlementService`、`ChannelQueryService`、`PaymentRetryService`、`RefundUnknownQueryScheduler`、`ChannelAdminController`、`ChannelPluginCallbackController`、`RoutingProperties` |
| 2 | 其中 **6 处**自己拼装「先路由、再取实现」：`channelRegistry.resolve(channelCode)` | `PaymentApplicationService.java:301`、`PaymentRefundService.java:104`、`ChannelQueryService.java:236`、`PaymentRetryService.java:79,116`、`RefundUnknownQueryScheduler.java:167`、`ChannelPluginCallbackController.java:166` |
| 3 | 结论：渠道网关的内部结构（Router + Registry + Plugin 三件套）对 Payment **完全透明** | — 这是「同层代码分了个包」，不是微服务边界 |

### 1.2 跨域契约在说 Payment 的语言，且用了数值主键

| # | 【现状】 | 证据 |
|---|---|---|
| 4 | `ChargeRequest(paymentNo, attemptId, ...)` —— `attemptId` 是 `payment_attempts.id`，**Long 自增主键**，非业务单号 | `ChargeRequest.java:19`、`PaymentAttempt.java:33`、`03-payment-schema.sql:36` |
| 5 | 全仓**无** `channelNo` / `attemptNo` / `gatewayNo` ⇒ 渠道网关**没有自己的业务单号** | grep 全量（payment-service/src/main）零命中 |
| 6 | ADR-0063 明文「跨系统标识一律业务单号，**禁止数值 ID**」，且历史已踩坑 C-12/S21（把平台 `transactionId` 当渠道交易号传） | `QueryStatusRequest.java:6-13` javadoc 自承 |
| 7 | ⇒ 数值主键 `attemptId` 出现在跨域契约里**本身违反 ADR-0063**，与 `paymentNo` 的口径自相矛盾 | — |
| 8 | Payment 应用服务反向依赖了 Channel 的 DTO：`handleCallback(String paymentNo, ChannelResult result)` | `PaymentCallbackService.java:40` |

### 1.3 回调处理混在 Controller 里，且反���端口的定义权是反的

| # | 【现状】 | 证据 |
|---|---|---|
| 9 | 回调 Controller 里直接做 **Payment 业务校验**：查 payment、验 `channelReference` 归属、验金额/币种 | `ChannelPluginCallbackController.validate()` → `paymentRepository.findByPaymentNo(...)` |
| 10 | `RefundResultListener` 接口定义在 channel 包、实现在 refund 应用服务 ⇒ **渠道 → 退款** 反向调用，且**接口定义权反了** | `RefundResultListener.java`、`AbstractMockChannelAdapter.java:147-148,251`、`MockRefundResultBridge` |
| 11 | 模态判定外泄：`DyeContext.isSandbox()` 散落 10 个文件，Payment 侧至少 5 处 | `PaymentRefundService`、`ChannelQueryService`、`InMemoryPaymentAttemptRepository`、`ChannelAttemptRecorderImpl`、`RefundUnknownQueryScheduler` + api 层 3 个 Controller |
| 12 | 回调双轨：支付宝走专属 `AlipayNotifyController`，Stripe 走通用端点（036 登记的技术债） | `AlipayNotifyController.java` |
| 13 | `extends AbstractChannelPlugin` **仅 Stripe 一家**；MOCK/WECHAT/ALIPAY/DOUYIN 仍是老基类，各自手写模态分发（支付宝一处 4 次） | grep `extends AbstractChannelPlugin` 单命中 |

### 1.4 可用的既有基建（本 Feature 直接复用，不新造）

| # | 【现状】 | 证据 |
|---|---|---|
| 14 | `BusinessNoType` 已有 16 种前缀（TX/OR/OI/PM/TXRF/PMRF/RF/SB/RB/LP/AB/AD/LO/SI/RD），`BusinessNos.of(type)` 一行生成 | `common/common-core/.../id/BusinessNoType.java:13-41`、`BusinessNos.java:50` |
| 15 | `common-dto` 已是**跨服务共享契约包**，被 10 个模块依赖（payment/order/ledger/fulfillment/entitlement/…） | grep `common-dto` in pom.xml |
| 16 | `payment_attempts` 表结构（无 `channel_no` 列） | `03-payment-schema.sql:35-60` |

---

## 2. User Stories

| ID | 角色 | 诉求 | 验收锚点 |
|---|---|---|---|
| US1 | 支付平台开发 | 新增一家渠道时只想写插件，不想碰 Payment 和网关内核 | SC-001 |
| US2 | 支付平台开发 | 调用渠道时只面对一个入口，不需要知道「先路由再取插件」 | SC-002 |
| US3 | 资金正确性负责人 | 跨域传递的标识必须是业务单号，不能是数据库自增主键（ADR-0063） | SC-003 |
| US4 | 支付平台开发 | 渠道回调的验签/报文转换归属渠道域，Payment 只收平台统一结构 | SC-004 |
| US5 | 架构负责人 | 边界靠 CI 门禁强制，不靠口头约定 | SC-005 |

---

## 3. Functional Requirements

### 3.1 单号与契约

- **FR-001**：渠道网关 MUST 拥有自己的业务单号 `channelNo`（`BusinessNoType.CHANNEL`，前缀 `CH` + 雪花），取代跨域契约中的 `attemptId`。
- **FR-002**：`payment_attempts` MUST 增加 `channel_no VARCHAR(32)` 列并建 **UNIQUE** 索引。**存量数据不回填**（系统未上线）。
- **FR-003**：渠道网关跨域契约 MUST 放在 **`common/common-dto`**（既有的跨服务共享契约包），**不得**在 payment-service 内另建契约包。
- **FR-004**：契约 MUST 包含：`ChannelPayCommand` / `ChannelRefundCommand` / `ChannelQueryCommand`（出向），`ChannelPayReceipt` / `ChannelRefundReceipt` / `ChannelQuerySnapshot`（回执），`ChannelPayNotified` / `ChannelRefundNotified`（回调通知）。
- **FR-005**：**查询类契约 MUST 以 `channelNo` 为主键**，`channelTransactionId`（渠道流水号）仅作渠道侧辅助定位，禁止以 `outTradeNo` 作为查单主键。
- **FR-006**：回调通知 MUST 携带 **`channelCode`（渠道编号）**。

### 3.2 门面

- **FR-007**：Payment 侧 MUST 只依赖 `ChannelGateway` 单一门面，暴露 `pay` / `refund` / `query` 三个方法；`ChannelRegistry` / `ChannelRouter` / `ChannelPlugin` 变为渠道网关域私有实现。
- **FR-008**：既有 6 处 `channelRegistry.resolve()` 调用点 MUST 全部改为经门面调用，**行为零变化**。

### 3.3 回调两层

- **FR-009**：回调入口（HTTP 端点）MUST 归属渠道网关域。第一层（渠道网关域内）以**模板方法** `final` 入口固定四步：① 工厂+策略选插件 ② 验签（插件钩子）③ 报文转换（插件钩子）④ 网关单更新（内核统一）。
- **FR-010**：第二层经 **`PaymentNotifyPort`** 跨域通知——该接口由 **Payment 定义并实现**，渠道网关只依赖接口；Payment 侧执行业务校验 / 终态收敛 / 记账 / 通知下游。
- **FR-011**：`ChannelPluginCallbackController.validate()` 中的 Payment 业务逻辑（查 payment、验归属、验金额）MUST 迁入 `PaymentNotifyPort` 的实现。
- **FR-012**：`RefundResultListener` MUST 被 `PaymentNotifyPort.onChannelRefundResult(...)` 取代——修正**接口定义权方向**。
- **FR-013**：模态（MOCK/SANDBOX）判定 MUST 内聚在渠道网关域；`DyeContext` 不得在 Payment 应用/api 层被读取。

### 3.4 存量迁移

- **FR-014**：MOCK / WECHAT / ALIPAY / DOUYIN 四家 MUST 迁移到 `AbstractChannelPlugin`。
- **FR-015**：迁移完成后删除 `AlipayNotifyController`（036 登记的技术债），回调统一走通用端点。

### 3.5 门禁

- **FR-016**：ArchUnit MUST 增加三条门禁：① Payment 应用/api 层禁依赖 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`；② 渠道网关域禁依赖 `com.payment.payment.application.*` / `com.payment.refund.*` 实现（接口 `PaymentNotifyPort` 除外）；③ `DyeContext` 仅限渠道网关域引用。三条均须含阳性对照防空转。

---

## 4. Invariants（不变量）

| ID | 不变量 | 违反后果 |
|---|---|---|
| INV-1 | Payment → Channel 的调用 MUST 只经 `ChannelGateway` | 渠道内部结构外泄，改内核要动 Payment |
| INV-2 | Channel → Payment 的调用 MUST 只经 `PaymentNotifyPort`（Payment 定义+实现） | 定义权倒置，渠道反向驱动业务编排 |
| INV-3 | 跨域传递的类型 MUST 来自 `common-dto` | 拆微服务时契约搬不走 |
| INV-4 | 跨域标识 MUST 为业务单号，禁止数值数据库主键（ADR-0063） | 重演 C-12 事故（错把平台单号当渠道单号） |
| INV-5 | 回调模板方法入口 MUST 为 `final`，步骤顺序不可变 | 渠道可绕过验签/模态门控 |
| INV-6 | 新增渠道 MUST 只新增插件包，不改 Payment、不改网关内核 | 插拔不成立 |

---

## 5. Non-Functional Requirements

| ID | 要求 |
|---|---|
| NFR-1 | 全量改动由**单测**覆盖（本 Feature 改的是边界而非链路行为，不起全链路）；`./mvnw -B -pl payment-service,common/common-core,common/common-dto,deployment/architecture-tests -am test` 必须全绿 |
| NFR-2 | 门面收口（FR-008）为**行为零变化**的机械替换，不得改变任何既有单测断言 |
| NFR-3 | 改动 MUST 在 `feature/037-*` 分支，禁止直接在 master 提交（宪法 §提交与合并节奏） |

---

## 6. Success Criteria

| ID | 场景 | 判据 |
|---|---|---|
| SC-001 | 新增渠道改动面 | 只新增插件包 + `application.yml` 一段 + 账本 seed 2 行 + pom 1 个依赖；Payment 域与网关内核零改动 |
| SC-002 | 门面收口 | Payment 侧对 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin` 的引用数 = 0（ArchUnit 强制） |
| SC-003 | channelNo 唯一性 | `BusinessNos.of(CHANNEL)` 生成值与 `paymentNo` 同构；`channel_no` 有 UNIQUE 索引；并发 10k 生成不重复（复用既有 `BusinessNosTest` 体例） |
| SC-004 | 回调两层 | 渠道报文翻译在渠道域完成；`PaymentNotifyPort` 收到的事件带 `channelCode` 且不含任何渠道私有类型 |
| SC-005 | 门禁生效 | FR-016 三条 ArchUnit 规则全绿，且阳性对照能触发违规 |

---

## 7. 决策记录

| # | 决策 | 理由 | 【待确认】 |
|---|---|---|---|
| D1 | 契约放 `common-dto`，不新建契约包 | 复用既有跨服务共享包；将来渠道网关真拆成服务，两域依赖同一 jar 即可，无需改造 | — |
| D2 | 用 `channelNo`（CH+雪花）取代 `attemptId` | ADR-0063 禁止数值 ID；`attemptId` 是自增主键，且历史已踩 C-12 坑 | 已确认（用户 2026-09-25） |
| D3 | `channel_no` 加 UNIQUE，**不回填存量** | 系统未上线，无存量数据 | 已确认（用户 2026-09-25） |
| D4 | 本轮不拆 Maven 模块 | ArchUnit 门禁已能钉住绝大多数越界；拆模块收益主要是编译期强制，留待边界稳定后再做 | — |
| D5 | 测试以单测为主，不起全链路 | 改的是边界与结构，非链路行为；全链路需 Docker + MySQL + 建表，成本高且无新增覆盖 | 已确认（用户 2026-09-25） |
| D6 | TDD 推进 | 用户指定 | — |
