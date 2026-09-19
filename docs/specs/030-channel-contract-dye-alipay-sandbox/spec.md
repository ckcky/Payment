# Spec: 030-channel-contract-dye-alipay-sandbox（聚合支付统一渠道契约重构 + 全链路染色分流 + 支付宝沙箱接入）

**版本**：1.0
**日期**：2026-09-19
**状态**：Draft（**四件套已齐备**：[spec.md](spec.md) / [plan.md](plan.md) / [tasks.md](tasks.md) / [acceptance.md](acceptance.md)；**本轮只写文档，不改代码**。实现待 ADR-0075 / ADR-0076 转 Accepted 后开工）
**分支**：本轮纯文档直推 master；实现期另开 `feature/030-channel-contract-dye-alipay-sandbox`
**决策**：
- [ADR-0075](../../adr/0075-unified-channel-contract.md)（🟡 Proposed）—— 聚合支付统一渠道契约（能同时容纳支付宝 / 微信 / 抖音 / Stripe）
- [ADR-0076](../../adr/0076-traffic-dyeing-and-alipay-sandbox.md)（🟡 Proposed）—— 全链路染色分流 + 渠道模态落库 + 支付宝沙箱接入（含新依赖）

> **三件事必须同一份 spec**：契约是凭证的载体，凭证是染色的产出，染色是沙箱分流的开关。
> 分三次做等于把同一批文件动三遍，且中间态（有凭证字段却无处传递、有染色却无人消费）比现状更别扭。
>
> **接口细节的落点**：本 Spec 只承载**设计依据与需求**；**字段级权威定义在
> [payment-service.md §3.11](../../architecture/systems/payment-service.md#311-渠道内部契约spec-030--adr-0075)**
> （系统设计文档），`technical-solution.md` 只描述结构变化不展开字段。

## 1. 背景与目标

### 1.1 背景：三个「文档许诺过、代码装不下」的缺口

**缺口 A（契约）**：内部渠道契约是**极简版**，装不下任何一家真实渠道。

| 出处 | 原文 | 性质 |
|---|---|---|
| `ChargeRequest.java` | `(paymentNo, attemptId, amountMinor, currencyCode, channelCode)` —— 5 个位置参数、零校验 | 事实 |
| `ChannelResult.java` | 只有 `status / channelReference / reason / transportCode / businessCode` | 事实 |
| `QueryStatusRequest.java` | `(paymentNo, transactionId, idempotencyKey)` —— **没有渠道交易号** | 事实 |
| `RefundRequest.java` | `(paymentNo, refundNo, amountMinor, currencyCode, channelCode)` —— **没有渠道交易号、没有原支付金额** | 事实 |

后果：支付宝要 `subject`（商品标题，**必填**）、微信要 `description`（**必填**）+ `payer.openid`（JSAPI 必填）、
Stripe 要 `payment_method_types` —— 这些**一个都没有字段可放**；而渠道下单后返回的二维码 / 表单 / `client_secret`
**没有任何字段可承载**。

**缺口 B（分流）**：全项目**不存在任何「环境 / 染色 / 灰度」机制**（全仓 0 处 dye / 染色 / 泳道 / 灰度实现；
Nacos 只做服务发现、无配置中心、0 处 `@RefreshScope`）。演示页下单**硬编码 `channelCode:'MOCK'`**，
没有任何「走本地 mock 还是走真实沙箱」的选择入口。

**缺口 C（模态不可追溯）**：`payment_attempts` **没有渠道模态列**。一旦同一 `channel_code` 下存在两种协议实现
（mock / 真实），退款、主动查询、超时扫描这三条**没有入口请求**的反向路径就无从判断该用哪一种——
它们的调用方是调度器，不经入站 Filter，ThreadLocal 为空。

### 1.2 代码现状（2026-09-19 核对真实代码）

| # | 事实 | 证据 |
|---|---|---|
| S1 | 端口 `PaymentChannel` 只有 4 个方法（`channelCode/charge/refund/queryStatus`），**无任何 `default` 方法** | `application/channel/PaymentChannel.java` |
| S2 | `ChannelResult` 的 7 个静态工厂 + 私有 `of(...)` + `withReason`，全部**不携带付款凭证** | `ChannelResult.java:39-99` |
| S3 | 直接实现 `PaymentChannel` 的测试桩共 **6 处** | `ChannelQueryTest:32`、`PaymentRetryTest:42`、`ReliabilityMetricsTest:37`、`PaymentDeferredChannelTest:25/75`、`ConfiguredChannelRouterTest:27` |
| S4 | 直接 `new ChargeRequest(...)` 共 **4 处**（生产 1 + 测试 3） | `PaymentApplicationService:181`、`PaymentChannelContractTest:16`、`MockChannelAdapterScenarioTest:23-24`、`PaymentRetryTest:39` |
| S5 | `ChannelResult` 被 **21 个测试文件**引用，但**全部走静态工厂**，无外部 `new ChannelResult(...)` | 全量检索 |
| S6 | 订单编排的延迟分支**不调 charge、不落渠道结果**，直接返回 `RoutedPayment` | `PaymentApplicationService.java:171-176` |
| S7 | 延迟开关是**全局**的：`boolean defer = mockCashier.isEnabled();`，一开就**对所有渠道**跳过 charge | `PaymentController.java`、`payment.mock-cashier.enabled` 默认 `true` |
| S8 | `payUrl` 由 `PaymentController.buildPayUrl` **硬编码**拼 `mock-cashier.base-url/cashier?...`，空渠道码回落字面量 `"MOCK"` | `PaymentController.java` |
| S9 | `CreatePaymentResponse(paymentNo, status, payUrl, attemptSeq, channelCode)` 定义在 **common-dto**，order-service 侧**透明透传**（原样返回前端） | `common/common-dto/.../rpc/CreatePaymentResponse.java` |
| S10 | **traceId 三段式骨架完整可照抄**：`TraceIdFilter`(-200) + `TraceContext`(ThreadLocal) + `TraceIdRequestInterceptor`（全仓唯一 Feign `RequestInterceptor`），装配在 `CommonCoreAutoConfiguration` / `FeignTraceAutoConfiguration` | `common-core/.../trace/`、`config/` |
| S11 | common-core 被 **9 个业务服务 + common-dto + common-mybatis + mock-channel-web** 依赖 → 在 common-core 加 Filter/Interceptor **天然覆盖全栈，无需改任何服务 pom** | 各服务 pom |
| S12 | 演示页经 `mock-channel-web`(8091) 的 `/proxy/{service}/**` **黑名单式逐头透传**（只跳过 host/content-length/connection/transfer-encoding/keep-alive/upgrade）→ 任意自定义头都能原样到 order-service，**代理零改动** | `DemoProxyController.copyRequestHeaders` |
| S13 | `payment_attempts.channel_reference` 是 **`VARCHAR(128)` + 唯一约束** `uk_attempts_channel_reference` | `deployment/schema/03-payment-schema.sql` |
| S14 | schema **无 Flyway / 无 ddl-auto**，靠 `deployment/demo/reset.sh` 重放建表；建表语句用 `CREATE TABLE IF NOT EXISTS`（**存量库不会补列**） | `deployment/schema/`、`deployment/demo/reset.sh` |
| S15 | payment-service pom **无 alipay-sdk / okhttp / httpclient**；根 pom `dependencyManagement` 也无 | `payment-service/pom.xml` |
| S16 | 支付回调入站只有 **HMAC 占位**：`ChannelCallbackSignatureFilter#verifySignature` **恒 `return true`**，预设 JSON body + `X-Channel-Signature`/`X-Channel-Timestamp` | `web/ChannelCallbackSignatureFilter.java`（ADR-0025 / ADR-0052） |
| S17 | **先例教训**：`InternalTokenRequestInterceptor` + 入站校验曾因「入站校验上线但调用方未同步补出站头」导致**全线 403**，被整体删除（ADR-0024/0034） | `0011-internal-token-decisions.md` |

> **S17 是本 Spec 最重要的一条约束来源**：染色是「入站读 + 出站写」的成对能力，**只做一半必然造成全线故障**。

### 1.3 目标

1. **契约能容纳四家**：把极简契约重设计成同时兼容支付宝 / 微信 v3 / 抖音支付 / Stripe 的内部统一契约，
   渠道私有参数有处可放、且不污染通用字段；
2. **凭证能回到浏览器**：渠道下单返回的「怎么把用户带去付款」（跳转 URL / 二维码 / 表单 / `client_secret`）
   有类型化的载体，并能经现有 `payUrl` 链路回到前端；
3. **沙箱下单跳第三方**：染色为 SANDBOX 时，下单真的调渠道、拿到的凭证就是**支付宝沙箱收银台地址**，
   **不再跳我们的 mock 收银台**；
4. **模态可追溯**：`payment_attempts` 记录本次交互的渠道模态，反向三条路径据此还原；
5. **demo 可选环境**：演示页下单时选「本地 mock / 支付宝沙箱」，经全链路染色传到适配器；
6. **零回归**：`order-service` 与所有跨服务 DTO **一字不改**；既有 6 处测试桩、4 处构造点靠兼容构造器与
   `default` 方法零改动编译。

### 1.4 为什么一起做

- 凭证必须挂在契约上（缺口 A 与 B 是同一个改动的两面）：先加凭证字段再做染色，等于把 `ChannelResult` 动两遍；
- 染色的**消费者**就是适配器的双模态分发，而没有契约变更，双模态无处产出凭证；
- 模态落库（缺口 C）是染色在**异步/无请求上下文**场景下的唯一闭环手段——不做落库，染色只覆盖「下单付款」
  一条链路，退款与超时扫描会静默走错协议。

## 2. 四家渠道参数语义与统一契约设计

> 本节是本 Spec 的**设计依据**：先看四家各自要什么、字段什么意思，再说明内部契约为什么这么切。
> **字段级权威定义见 [payment-service.md §3.11](../../architecture/systems/payment-service.md#311-渠道内部契约spec-030--adr-0075)**。

### 2.1 四家下单接口的必填与关键参数（语义对照）

| 语义 | 支付宝 `alipay.trade.page.pay` | 微信支付 v3 统一下单 | 抖音支付（DouyinPay） | Stripe `PaymentIntent` |
|---|---|---|---|---|
| 商户订单号 | `out_trade_no`（**必**，≤64，字母数字下划线） | `out_trade_no`（**必**，6–32，**同商户号下唯一**） | `out_trade_no`（**必**） | 无此字段 → `metadata` + `Idempotency-Key` 头 |
| 金额 | `total_amount`（**必**，**单位「元」两位小数字符串**） | `amount.total`（**必**，**单位「分」整数**） | `amount.total`（**必，分**） | `amount`（**必**，最小货币单位整数） |
| 币种 | 无字段（隐含 CNY） | `amount.currency`（选，默认 CNY） | `amount.currency` | `currency`（**小写**三字母） |
| 商品标题 | `subject`（**必**，≤256，禁 `/ = &`） | `description`（**必**，≤127，**用户账单可见**） | `description`（**必**） | `description`（选） |
| 商品描述 | `body`（选，≤128） | `detail.goods_detail[]` | — | 与 `description` 同字段 |
| 异步通知 | `notify_url`（公共参数） | `notify_url`（**必**，**须 HTTPS 且禁查询串**） | `notify_url`（必） | 账号级 **webhook**（非按单配置） |
| 同步跳转 | `return_url`（公共参数） | H5 的 `scene_info.redirect_url` | — | `return_url`（**仅 `confirm=true` 可用**） |
| 过期 | `timeout_express`（**相对量**如 `"90m"`，超时关单） | `time_expire`（**RFC3339** 绝对时刻；**仅支付截止，不关单**） | 同微信 | 无（需另调 cancel） |
| 支付场景 | `product_code`（如 `FAST_INSTANT_TRADE_PAY`）+ `qr_pay_mode` | ⚠️ **由 URL 路径区分**：`/jsapi` `/native` `/h5` `/app` | 同微信（路径区分） | `payment_method_types[]` / `automatic_payment_methods` + `confirm` |
| 付款人 | `buyer_id`（选） | `payer.openid`（**JSAPI 必**） | `openid` | `customer`（`cus_xxx`） |
| 终端 IP | — | `scene_info.payer_client_ip`（**H5 必**，风控） | — | —（Radar 风控） |
| 透传数据 | `passback_params`（原样回传） | `attach`（原样回传） | `attach` | `metadata`（webhook 回带） |
| 渠道私有 | `qr_pay_mode` / `quit_url` / `goods_detail` | `scene_info` / `goods_tag` / `support_fapiao` / `limit_pay` | `goods_tag` | `receipt_email` / `statement_descriptor` / `capture_method` |

**下单接口返回的凭证形态（四家完全不同，这是「凭证必须带类型」的直接证据）**：

| 渠道 | 返回 | 形态 |
|---|---|---|
| 支付宝 `page.pay` | `form` 表单 HTML，或 `pageExecute(req,"GET")` 的**签名跳转 URL** | URL / HTML |
| 微信 `jsapi` | `prepay_id` + `appId/timeStamp/nonceStr/package/signType/paySign` | **参数集** |
| 微信 `native` | `code_url`（二维码内容串，**非图片**） | 二维码串 |
| 微信 `h5` | `h5_url`（**须手机浏览器**打开） | URL |
| 抖音 | prepay_id（同微信） | 参数集 |
| Stripe | `client_secret`（`pi_xxx_secret_yyy`，仅供浏览器完成**该一笔**） | 密钥串 |

**退款 / 查单接口的关键差异**：

| 语义 | 支付宝 | 微信 v3 | Stripe |
|---|---|---|---|
| 退款定位 | `out_trade_no` **或** `trade_no`（渠道交易号） | `out_trade_no` **或** `transaction_id` | PaymentIntent `pi_xxx` |
| 退款单号 | `out_request_no`（**同一笔可多次部分退款，靠它区分**） | `out_refund_no` | `metadata` + `Idempotency-Key` |
| 退款金额 | `refund_amount`（**元**字符串；缺省 = 全额） | `amount.total`（分） | `amount`（最小单位） |
| 退款结果 | **同步返回**（`code=10000` 即成功） | **同步返回**（状态 `SUCCESS`/`PROCESSING`） | **同步返回**（`status`） |
| 查单定位 | `out_trade_no` 或 `trade_no` | 路径 `/v3/pay/transactions/out-trade-no/{no}?mchid=` | `GET /v1/payment_intents/{id}` |

> ⚠️ **两处实现期须复核**（本文档不臆断）：① 支付宝退款是否支持独立 `refund_notify_url`；
> ② 抖音存在**两套并存**的下单体系（`DouyinPay` 直连 vs 抖音开放平台交易模板，后者用
> `out_order_no`/`item_order_id`/MD5+salt 签名）。本 Spec 以 DouyinPay 为准，实现期复核后如不一致，
> 只影响适配器内部映射，不影响内部契约。

### 2.2 内部契约怎么切：四组结构化字段 + 一个渠道扩展袋

判据（**唯一标准**，实现期 MUST 遵守）：

| 情形 | 归属 |
|---|---|
| 四家**语义一致**且支付必需的字段 | **结构化字段**（顶层或分组 record） |
| 仅 1~2 家有、语义不统一、或非必需 | **`channelExtra` 扩展袋**（键值对） |
| **仅与「在哪个端、以什么方式付款」有关** | **`PaymentScene` 枚举**（渠道能力声明的载体） |

**严禁**把渠道私有参数塞进通用字段，也**严禁**把通用语义降级成扩展袋（否则等于放弃抽象）。

四组结构化字段的分组理由：

| 分组 | 为什么独立成组 |
|---|---|
| `Goods`（`title` / `description`） | 四家对「标题/描述」的切分不一致（支付宝是 `subject`+`body`，微信只有一个 `description`），内部取**并集语义** |
| `CallbackUrls`（`notifyUrl` / `returnUrl`） | 两者**语义完全不同**：异步通知是**资金事实来源**（须公网可达），同步跳转**只是体验**（浏览器可能不跳）。混在一起会诱导实现者用 `returnUrl` 推进状态 |
| `Payer`（`payerId` / `clientIp`） | 「站内已登录用户」（有 openid/customer）与「匿名 H5」（只有终端 IP）是两种调用形态 |
| `PaymentScene`（枚举） | 四家对「怎么把用户带到付款页」的建模方式完全不同（支付宝 `product_code` / 微信**路径** / Stripe `payment_method_types`）。若搬任何一家的词表进内部契约，其余三家就被绑架 |

**金额保持扁平**（不封装 `Amount`）：全仓一致口径已是 `amountMinor`(long) + `currencyCode`(String)，
封装只增加拆包噪音，不带来约束收益。

### 2.3 渠道凭证：必须带类型

单一 `String payUrl` 无法承载四家的返回——把 HTML 塞进叫 URL 的字段、把二维码串当 URL 打开，都是语义错配。
故引入 `PayCredential(kind, payload, expiresAt)`，`Kind ∈ {REDIRECT_URL, FORM_HTML, QR_CODE, H5_URL, JSAPI_PARAMS, CLIENT_SECRET}`。

`REDIRECT_URL` 与 `H5_URL` **刻意不合并**：两者都是 URL，但前者任意端可开、后者**必须手机浏览器**（微信校验 UA，PC 打开被拒）。
保留两个 Kind 是为了让调用方能做正确的端侧判断，而不是把差异藏在字符串里。

**挂载方式**：给 `ChannelResult` 加**可空** `credential` 字段 + 新工厂 `accepted(ref, reason, credential)`；
既有 7 个工厂不变（`credential = null`）；**不另立 `ChargeResult`**——改 `charge` 返回类型会波及端口、重试链、
收敛逻辑与持久化，代价远大于收益。`refund` / `queryStatus` 恒为 `null`。

**不落库**：`payload` 含签名、有时效、长度远超 `channelReference VARCHAR(128)`，且不具备
「渠道交易流水号」的唯一性语义（会撞 `uk_attempts_channel_reference`）。持久化标识恒为 `channel_reference`。

### 2.4 链路染色：语义、优先级与落点

| 项 | 取值 |
|---|---|
| 头名 | `X-Dye-Tag` |
| 取值 | `MOCK` / `SANDBOX`（大小写不敏感） |
| **缺省语义** | **不染色 = `MOCK`** —— 安全默认，绝不误连真实渠道 |
| 非法值 | **400 fail fast** + 指标（依 ADR-0049 第 2 条：「配错不许静默走默认」）。静默回落会让「选了沙箱却走了 mock」变成最难查的假绿 |
| 与配置的关系 | `payment.channel.adapters.alipay.sandbox.enabled=false` 时，染色为 `SANDBOX` → **明确失败**，不静默回落 mock（同 ADR-0049 纪律） |
| 是否参与选路 | **不参与**。染色只决定「同一 `channelCode` 下的协议实现」；选路仍按 ADR-0073（一期只做渠道路由骨架） |
| 透传 | `common-core` 三段式（仿 traceId）：入站 `DyeFilter`（order `-190`）+ `DyeContext`（ThreadLocal）+ 出站 `DyeRequestInterceptor`（Feign 全局） |
| MDC / 响应头 | MDC key `dyeMode`；响应头回写；`finally` 清理 |

**为什么放 common-core 而不是 payment-service**：demo 页的染色头要在 **order-service** 被读出、再由 order 的
Feign 出站写给 payment-service。放在 common-core 一处，即可让 9 个服务自动获得「入站读 + 出站写」，
**order-service 与代理零代码改动**（S11 / S12）。

### 2.5 模态落库与反向路径

- `payment_attempts` 新增 `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'`；
- 写入时机：`ChannelAttemptRecorder` 创建 attempt 时读 `DyeContext`（空 → `MOCK`）落库，**方法签名不变**；
- 幂等重复（同 `paymentNo` 回放）取**库内值**，不覆盖；
- 反向三条路径（退款 / 主动查询 / 超时扫描）从 attempt 读出 `channel_mode` 后
  `DyeContext.runWith(mode, ...)` 包裹渠道调用——这三个调度器不经入站 Filter，**落库是唯一闭环手段**。

## 3. 硬性不变量（不可破坏）

- **INV-1（金额纪律）**：内部一律 `amountMinor`(long) + `currencyCode`(String)；**「元字符串」只在支付宝适配器内部换算**，
  MUST 用 `BigDecimal.valueOf(minor, 2).toPlainString()`，**禁止 `double` / `float`**（0.1 + 0.2 类误差直接变错账）。
  内部契约 MUST NOT 出现「元」或小数金额字段。
- **INV-2（凭证不落库）**：`PayCredential.payload` 与任何密钥 MUST NOT 入库、MUST NOT 进 git、MUST NOT 进明文日志。
  持久化的渠道标识恒为 `payment_attempts.channel_reference`。
- **INV-3（染色不参与选路）**：`ChannelRouter` MUST NOT 读取染色上下文。染色只决定「同 code 下的协议实现」，
  选路规则仍由 ADR-0073 定义。
- **INV-4（反向按记录解析）**：退款 / 重试 / 主动查询 MUST 用 `payment_attempts.channel_code` 解析渠道，
  **禁止重新路由**（继承 spec 028 INV-6）。染色同样 MUST NOT 让反向路径改换渠道。
- **INV-5（入站读与出站写同批）**：染色的 `DyeFilter`（入站）与 `DyeRequestInterceptor`（出站）
  MUST 在同一批落地。依据 S17：`InternalToken` 曾因只做入站校验导致**全线 403** 被整体删除。
- **INV-6（凭证即「待付款」信号）**：`ChannelResult.credential != null` ⇒ 渠道仅**受理**、买家尚未付款
  ⇒ payment MUST **停在 `PROCESSING`**、MUST NOT 走「成功收敛」路径（不记账、不通知 order）。
- **INV-7（SDK 收口）**：`application/**` MUST NOT 依赖 `com.alipay.sdk`（构建期 ArchUnit 断言）。
  支付宝协议实现必须落在 `AlipayGateway` 端口之后，由 `infra` 实现。
- **INV-8（不静默降级）**：以下情形 MUST 明确失败（`400 INVALID_ARGUMENT`），**禁止静默回落**：
  染色值非法、染色 `SANDBOX` 但渠道未启用真实模式、请求了渠道未声明的 `PaymentScene`。

## 4. 关键用户故事

### US1 - 内部契约能同时容纳四家渠道（Priority: P1）

**As** 平台维护者，**I want** 一个能同时表达支付宝 / 微信 / 抖音 / Stripe 下单与退款需求的内部契约，
**so that** 新增一家渠道不必再动端口，渠道私有差异也不会污染核心领域。

**Why this priority**：契约是其余一切的地基——凭证、染色、双模态都挂在它上面。

**Independent Test**：`ChargeRequest` 能表达「支付宝 page.pay + 商品标题 + notify_url + 过期 + WEB 场景」，
也能表达「微信 JSAPI + openid + client_ip + JSAPI 场景」，且两者用的是**同一组字段**；
既有 4 处 `new ChargeRequest(...)` 与 6 处 `PaymentChannel` 测试桩**编译零改动**。

**Acceptance Scenarios**：

1. **Given** 新契约，**When** 构造一个仅带 5 个旧参数的请求，**Then** 编译通过、mock 渠道行为与今天逐字节一致。
2. **Given** 新契约，**When** 为支付宝沙箱填充 `goods.title` / `callbackUrls.notifyUrl` / `expireAt` / `scene=WEB`，
   **Then** 适配器能把它们分别映射到 `subject` / `notify_url` / `timeout_express` / `product_code`。
3. **Given** 一个渠道私有参数（如微信 `goods_tag`），**When** 需要传递，**Then** 走 `channelExtra`，
   **不新增通用字段**。
4. **Given** 请求 `scene=JSAPI` 但渠道 `supportedScenes()` 不含 JSAPI，**When** 发起支付，
   **Then** `400 INVALID_ARGUMENT`（INV-8），**不静默按 WEB 处理**。

---

### US2 - 渠道凭证经 payUrl 回到浏览器（Priority: P1）

**As** 用户，**I want** 下单后拿到的 `payUrl` 就是**渠道真实收银台地址**，**so that** 我能直接在支付宝付款，
而不是被带到平台的 mock 收银台。

**Why this priority**：这是「接真实渠道」在用户侧的唯一可见产出；没有它，染色分流毫无意义。

**Independent Test**：`DyeContext=SANDBOX` 下单 → `CreatePaymentResponse.payUrl` 以
`https://openapi-sandbox.dl.alipaydev.com/gateway.do?` 开头；`payment.status == PROCESSING`；
`payment_attempts` 有且仅有一行 PAYMENT attempt 且 `status != SUCCEEDED`。

**Acceptance Scenarios**：

1. **Given** 沙箱模式且渠道返回凭证，**When** 创建支付意图，**Then** `payUrl` = 凭证 `payload`，
   且**不触发** `applyAndPersist`（INV-6），payment 停 `PROCESSING`。
2. **Given** mock 模式（不染色），**When** 创建支付意图，**Then** 行为与今天完全一致
   （延迟分支返回 mock 收银台 `payUrl`），零回归。
3. **Given** 渠道返回「无结论」但**不带凭证**（如超时），**When** 创建支付意图，**Then** 走既有 UNKNOWN 路径，
   `payUrl` 为 `null`。
4. **Given** 一笔已带凭证的待付款支付，**When** 买家完成付款且异步通知到达，**Then** 由现有回调链路收敛为
   `SUCCEEDED`（凭证不参与状态推进）。

---

### US3 - 全链路染色决定走本地 mock 还是真实沙箱（Priority: P1）

**As** 演示者，**I want** 在演示页选一次环境，**so that** 整条链路（order → payment → 适配器）按这个选择走，
不用改任何配置、不用重启。

**Why this priority**：这是「同 code 双模态」能够被验证的前提，也是演示价值的来源。

**Independent Test**：带 `X-Dye-Tag: SANDBOX` 经 `/proxy` → order-service → Feign → payment-service，
断言适配器走了真实分支（`payment_attempts.channel_mode = 'SANDBOX'`，适配器指标/日志可证）。

**Acceptance Scenarios**：

1. **Given** demo 页选「支付宝沙箱」，**When** 下单并建支付单，**Then** order-service 读出头并透传，
   payment-service 落 `channel_mode='SANDBOX'`。
2. **Given** 不带头，**When** 下单，**Then** `channel_mode='MOCK'`，全链路行为与今天一致。
3. **Given** `X-Dye-Tag: BLUE`（非法值），**When** 请求到达，**Then** `400 INVALID_ARGUMENT` + 指标
   （**不静默回落**，INV-8）。
4. **Given** 染色 `SANDBOX` 但 `sandbox.enabled=false`，**When** 建支付单，**Then** `400 INVALID_ARGUMENT`，
   **不静默走 mock**（INV-8）。
5. **Given** 一个不经 HTTP 的调度器线程，**When** 读取 `DyeContext`，**Then** 为空（不误读上一个请求的残留）——
   反向路径必须靠落库值还原（US5）。

---

### US4 - 支付宝沙箱下单与异步通知闭环（Priority: P1）

**As** 平台，**I want** 在 `AlipayChannelAdapter` 内实现真实支付宝协议（**不新建 Sandbox Adapter**），
**so that** 沙箱单能真正下单、真正收到回调并收敛为成功。

**Why this priority**：这是本轮的业务闭环终点；缺了它，US2 的 `payUrl` 指向一个打不开的地址。

**Independent Test**：`mvn verify` 全绿且**不连沙箱**；离线单测用固定签名向量验证 RSA2 签名与验签；
手工 live 验证（需密钥 + 公网 `notify_url`）按 `plan.md` 的手册步骤执行。

**Acceptance Scenarios**：

1. **Given** 沙箱模式，**When** `charge`，**Then** 返回 `accepted(null, "awaiting buyer", credential)`，
   `credential.kind = REDIRECT_URL`，且渠道引用为 `null`（`page.pay` 同步响应**不含 `trade_no`**）。
2. **Given** 沙箱模式 + 尾号为 `11` 的金额，**When** `charge`，**Then** 仍返回 `timeout`——
   **MOCK 分支的尾数故障注入口径 100% 不变**（`DyeContext=MOCK` 时适配器 MUST `super` 委托）。
3. **Given** 支付宝 `POST` 表单通知（`trade_status=TRADE_SUCCESS`），**When** 到达 notify 端点，
   **Then** RSA2 验签通过 → 校验 `app_id`/`seller_id`/`out_trade_no`/`total_amount` → 收敛为 `SUCCEEDED`，
   且 HTTP 响应体**恰好是纯文本 `success`**（无引号、无换行）。
4. **Given** 一个验签失败的通知，**When** 到达，**Then** `403` 且**不触达** `PaymentCallbackService`。
5. **Given** `trade_status=WAIT_BUYER_PAY`，**When** 到达，**Then** 映射为 `businessUnknown`，
   **不推进** payment 状态。

---

### US5 - 渠道模态落库，反向路径还原（Priority: P2）

**As** 运维/后端，**I want** 每行 attempt 记下它当时走的模态，**so that** 退款与超时扫描不会把沙箱单当成 mock 单处理。

**Why this priority**：不做这条，染色只覆盖「下单付款」一条链，反向路径静默错协议——比不做更危险。

**Independent Test**：对一笔 `channel_mode='SANDBOX'` 的成功沙箱支付发起退款，断言退款 attempt 行
`channel_mode='SANDBOX'`，且渠道调用发生在 `DyeContext=SANDBOX` 的包裹内。

**Acceptance Scenarios**：

1. **Given** 一笔沙箱支付，**When** 发起退款，**Then** 退款 attempt 的 `channel_mode='SANDBOX'`，
   且退款走真实支付宝协议（而非 mock 受理）。
2. **Given** 一笔 mock 支付，**When** 发起退款，**Then** 行为与今天完全一致（`channel_mode='MOCK'`）。
3. **Given** 存量库中 `channel_mode` 缺省的历史行，**When** 读取，**Then** 值为 `'MOCK'`（向后兼容），
   行为与今天一致。
4. **Given** 幂等重复请求，**When** 命中已存在支付单，**Then** 返回库内 `channel_mode`，**不覆盖**。

---

### US6 - demo 页可选择环境（Priority: P2）

**As** 演示者，**I want** 演示页有一个「本地 mock / 支付宝沙箱」开关，**so that** 一次点击就能切换整条链路的行为。

**Independent Test**：`demo.html` 选「支付宝沙箱」→ 下单 → `window.open(payUrl)` 打开的是**支付宝沙箱页面**；
切回「本地 mock」→ 打开的是我们的 mock 收银台。

**Acceptance Scenarios**：

1. **Given** 选「支付宝沙箱」，**When** 下单，**Then** 请求体 `channelCode=ALIPAY`、请求头带 `X-Dye-Tag: SANDBOX`。
2. **Given** 选「本地 mock」，**When** 下单，**Then** `channelCode=MOCK`、**不带**染色头（缺省即 MOCK）。
3. **Given** 沙箱模式下单，**When** 收到响应，**Then** `pr.data.payUrl` 指向沙箱网关，
   `window.open(payUrl)` 直接进入支付宝沙箱收银台（**不经 mock 收银台**）。
4. **Given** 代理，**When** 请求经 `/proxy/**` 转发，**Then** 自定义头原样到达 order-service（代理零改动）。

## 5. 功能需求（FR）

### 5.1 统一渠道契约（ADR-0075）

- **FR-001** 新增 `PaymentScene` 枚举（`application/channel/`）：`WEB / H5 / NATIVE / JSAPI / MINI_PROGRAM / APP`，
  作为渠道能力声明的载体；与四家的映射关系写入 Javadoc 对照表。
- **FR-002** 新增 `Goods(String title, String description)` record：`title` → 支付宝 `subject` / 微信+抖音+Stripe `description`；
  `description` → 支付宝 `body`。**不设校验**（保持纯数据载体，长度上限由适配器处理）。
- **FR-003** 新增 `CallbackUrls(String notifyUrl, String returnUrl)` record，并在 Javadoc 中**显式声明**
  「`returnUrl` 不承载资金事实，MUST NOT 据其推进支付状态」。
- **FR-004** 新增 `Payer(String payerId, String clientIp)` record。
- **FR-005** `ChargeRequest` 扩展为
  `(paymentNo, attemptId, amountMinor, currencyCode, channelCode, scene, goods, callbackUrls, expireAt, payer, attach, channelExtra)`；
  **保留 5 参兼容构造器**（其余走默认），保证 S4 的 4 处构造点**零改动**。
- **FR-006** `RefundRequest` 扩展为
  `(paymentNo, refundNo, amountMinor, currencyCode, channelCode, channelTransactionId, outRequestNo, reason, refundNotifyUrl)`；
  **保留 5 参兼容构造器**。
  > 真实渠道退款 MUST 带渠道交易号（支付宝 `trade_no` / 微信 `transaction_id` / Stripe `pi_xxx`）；
  `outRequestNo` 对应支付宝 `out_request_no`（**多次部分退款的区分键**）。
- **FR-007** `QueryStatusRequest` 扩展为 `(paymentNo, transactionId, idempotencyKey, channelTransactionId)`；
  **保留 3 参兼容构造器**。
  > 否则 `alipay.trade.query` 只能按 `out_trade_no` 查，无法用 `trade_no` 精确收敛。
- **FR-008** `channelExtra` 为 `Map<String,String>`，**可空**；键名约定用渠道原生参数名（便于排障比对）。
- **FR-009** `PaymentChannel` 新增两个 `default` 方法（**不破坏 S3 的 6 处测试桩**）：
  - `default Set<PaymentScene> supportedScenes() { return EnumSet.allOf(PaymentScene.class); }`
    （默认全支持；真实渠道适配器 MUST 覆写并收窄）；
  - `default boolean supportsRealMode() { return false; }`
    （默认不支持真实模态；仅支付宝沙箱适配器返回 `true`）。
- **FR-010** 场景校验落在 payment 层编排（调 `charge` **之前**）：`scene != null` 且不在 `supportedScenes()` 内
  → 抛 `BizException(ErrorCodes.INVALID_ARGUMENT)` → `400`（INV-8）。`scene == null` 不校验（零回归）。
- **FR-011** `ChargeRequest` 等三个 record 均为**服务内契约**（非跨服务 RPC），不触发 Constitution
  「API Breaking Change」人类决策边界；靠兼容构造器保证零中断编译。

### 5.2 渠道凭证（ADR-0075）

- **FR-012** 新增 `PayCredential(Kind kind, String payload, Instant expiresAt)` 与
  `Kind ∈ {REDIRECT_URL, FORM_HTML, QR_CODE, H5_URL, JSAPI_PARAMS, CLIENT_SECRET}`。
- **FR-013** `ChannelResult` 新增**可空** `credential` 字段：
  - 新增工厂 `accepted(String channelReference, String reason, PayCredential credential)`；
  - 既有 7 个工厂语义不变（`credential = null`）；
  - 保留 5 参构造重载（委托为 `credential = null`），保证无外部构造点断裂；
  - `withReason(...)` MUST **保留** `credential`。
- **FR-014** 凭证**不落库**（INV-2）；`payment_attempts` 不新增凭证列。
- **FR-015** 凭证透传链路：`ChannelResult.credential` → 重试结果 → `RoutedPayment` → `PaymentController`
  把 `credential.payload()` 填进**现有** `CreatePaymentResponse.payUrl`。
  **`common-dto` 与 order-service 零改动**（S9）。
- **FR-016** `credential != null` ⇒ **不调 `applyAndPersist`**，payment 停 `PROCESSING`（INV-6）。
- **FR-017** `payUrl` 组装规则改为**优先取凭证**：凭证在 → 用 `credential.payload()`；
  否则沿用既有 mock 收银台链接（灰度期行为不变）。
- **FR-018** ⚠️ **已知承载缺口**：`CreatePaymentResponse` 只有 `payUrl` 一个字段，
  **非跳转型凭证**（`QR_CODE` / `FORM_HTML` / `JSAPI_PARAMS` / `CLIENT_SECRET`）无处承载，
  且前端无法区分凭证类型。本轮只打通 `REDIRECT_URL` / `H5_URL`（支付宝沙箱场景）；
  其余形态记入 §8 已知限制，**下期引入独立的 `credentialKind` + 承载字段**（需协调 order-service，属 API 变更）。

### 5.3 全链路染色（ADR-0076）

- **FR-019** 新增 `common-core` `dye/DyeMode` 枚举：`MOCK` / `SANDBOX`，含**大小写不敏感的严格解析**
  `parse(String)`：`null` / 空白 → `MOCK`；非法值 → 抛异常（fail fast，ADR-0049 纪律）。
- **FR-020** 新增 `dye/DyeContext`：ThreadLocal，提供 `isSandbox()` / `current()` / `set(DyeMode)` / `clear()` /
  `runWith(DyeMode, Runnable)`（供后台线程与反向路径还原）。
- **FR-021** 新增 `dye/DyeFilter`（`OncePerRequestFilter`）：读 `X-Dye-Tag` → 解析 → `DyeContext.set` +
  `MDC.put("dyeMode", ...)` → 响应头回写 → `finally` 清理（`DyeContext.clear()` + `MDC.remove`）。
  非法值 → `400 INVALID_ARGUMENT` + 指标 `dye_tag_rejected_total{reason=invalid}`。
- **FR-022** 新增 `dye/DyeRequestInterceptor`（`feign.RequestInterceptor`）：`DyeContext` 非空时写入 `X-Dye-Tag`
  出站头；空则**不写**（避免把缺省语义硬编码进协议）。
- **FR-023** 装配：`CommonCoreAutoConfiguration` 增加 `dyeFilter` + `dyeFilterRegistration(order = -190)`；
  `FeignTraceAutoConfiguration` 增加 `dyeRequestInterceptor`（`@ConditionalOnMissingBean`）。**两者必须同批落地（INV-5）**。
- **FR-024** 过滤链定序：`TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)`。
  payment 的 `ChannelCallbackSignatureFilter`（`Integer.MIN_VALUE`）`shouldNotFilter` 只命中两条回调路径，
  与下单链路不冲突。
- **FR-025** `AutoConfiguration.imports` **无需改动**（两个自动配置类已注册，新增的是其中的 Bean）。
- **FR-026** 染色 MUST NOT 参与选路（INV-3）：`ChannelRouter` 不读 `DyeContext`（ArchUnit 或代码评审固化）。
- **FR-027** 染色 MUST NOT 让反向路径改换渠道（INV-4）；反向路径的模态来源是**落库列**而非请求头。

### 5.4 模态落库与反向还原（ADR-0076）

- **FR-028** `payment_attempts` 新增列
  `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK' COMMENT '渠道模态 MOCK/SANDBOX（spec 030 / ADR-0076）'`。
- **FR-029** schema **三处齐备**（依据 S14，建表语句不会给存量库补列）：
  ① `deployment/schema/03-payment-schema.sql` 建表语句补列；
  ② 新增增量迁移 `deployment/schema/030-payment-attempt-channel-mode.sql`（`ALTER TABLE ... ADD COLUMN`）；
  ③ `payment-service/src/test/resources/schema.sql`（H2）同步。
- **FR-030** `PaymentAttempt` 领域对象新增 `channelMode` 字段，全部工厂方法 / `rehydrate` / `InMemoryPaymentAttemptRepository`（测试）同步。
- **FR-031** `ChannelAttemptRecorder` 实现内读 `DyeContext`（空 → `MOCK`）落库；
  **接口方法签名不变**（调用点零改动）。
- **FR-032** 幂等重复命中已存在支付单时，返回**库内** `channel_mode`，MUST NOT 用当前请求的染色值覆盖。
- **FR-033** 反向路径还原：`PaymentRefundService.refund` 与 `ChannelQueryService.queryRound` 取到 PAYMENT attempt 后
  以 `DyeContext.runWith(attempt.getChannelMode(), () -> channel.xxx(req))` 包裹渠道调用；
  `TimeoutScanScheduler` 同理（经 `ChannelQueryService` 路径）。
- **FR-034** 存量行向后兼容：`DEFAULT 'MOCK'`，**不回填、不修正**（历史事实）。

### 5.5 支付宝沙箱适配器（ADR-0076）

- **FR-035** **不新建** `AlipaySandboxChannelAdapter`：在既有 `AlipayChannelAdapter` 内实现双模态分发，
  `MOCK` 分支 MUST `super` 委托（保证基类 4 件横切行为口径 100% 不变）。
- **FR-036** `AlipayChannelAdapter.supportsRealMode()` 返回 `true`；其余三个渠道维持 `default false`。
- **FR-037** 新增端口 `application/channel/AlipayGateway`：
  `pagePay(...)` / `query(...)` / `refund(...)` / `verifyNotify(...)`；
  实现 `infra/channel/alipay/AlipaySdkGateway` 收口 SDK（INV-7）。
- **FR-038** 新增 `AlipaySandboxProperties`（`@ConfigurationProperties("payment.channel.adapters.alipay.sandbox")`）：
  `enabled` / `gateway-url` / `app-id` / `merchant-private-key` / `alipay-public-key` /
  `notify-url` / `return-url` / `http-timeout-ms` / `sign-type`。
- **FR-039** `enabled` 默认 **`false`**：既是装配门控，也是**一键 kill switch**
  （保证无密钥时服务照常启动、CI 可跑）。染色 `SANDBOX` 而 `enabled=false` → `400 INVALID_ARGUMENT`（INV-8）。
- **FR-040** 新增依赖 `com.alipay.sdk:alipay-sdk-java`（版本在根 pom `dependencyManagement` 锁定）；
  引入理由与风险（34MB jar / 传递依赖 / 已知 CVE）**显式接受并记入 ADR-0076**。
- **FR-041** `charge`（沙箱分支）调 `alipay.trade.page.pay`，用 **GET 签名 URL** 作为凭证：
  `accepted(null, "awaiting buyer", PayCredential.redirectUrl(url, expireAt))`。
  > `page.pay` 同步响应**不含 `trade_no`**，故 `channelReference` 为 `null`；`trade_no` 由异步通知带入。
  > MySQL 唯一约束**允许多个 NULL**，不冲突（FR-028 / S13）。
- **FR-042** `queryStatus`（沙箱分支）调 `alipay.trade.query`：
  `TRADE_SUCCESS` / `TRADE_FINISHED` → `success(trade_no)`；`TRADE_CLOSED` → `businessFailure`；
  `WAIT_BUYER_PAY` → `businessUnknown`（不臆断）。
- **FR-043** `refund`（沙箱分支）调 `alipay.trade.refund`（**同步返回**，`code=10000` 即 `success(refund_no)`）。
- **FR-044** 金额换算 MUST 用 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`（INV-1）。
- **FR-045** 沙箱 HTTP 超时独立配置（默认 `10000ms`），与全局 `payment.channel.http-timeout-ms`(1500ms) 解耦；
  且 MUST 小于 `payment.reliability.timeout`(30s)，避免「还在等支付宝、支付已被判 UNKNOWN」。
- **FR-046** 密钥全部 **env 注入**（`PAYMENT_ALIPAY_*`），禁硬编码 / 禁入库 / 禁明文日志（ADR-0026、INV-2）。

### 5.6 支付宝通知入站（ADR-0076）

- **FR-047** 新增端点 `POST /internal/channels/alipay/notify`（`form-urlencoded`）。
  > 路径**天然不在** `ChannelCallbackSignatureFilter` 的 urlPatterns（`/internal/payments/*`、`/internal/refunds/*`）内，
  > 与 HMAC 占位过滤器**不冲突、不复用**（算法与报文形态都不同：支付宝是 RSA2 + 表单）。
- **FR-048** 验签：剔除 `sign` / `sign_type` → 按参数名排序拼串 → `AlipayGateway.verifyNotify(...)`（SDK RSA2 + 支付宝公钥）；
  失败 → `403`，**不触达** `PaymentCallbackService`。
- **FR-049** 业务校验（验签通过后）：`app_id` / `seller_id` / `out_trade_no` / `total_amount` 四者一致；
  任一不符 → 记录并拒绝（不推进状态）。
- **FR-050** `trade_status` 映射：`TRADE_SUCCESS` / `TRADE_FINISHED` → `success(trade_no)`；
  `TRADE_CLOSED` → `businessFailure(trade_no, status)`；`WAIT_BUYER_PAY` → `businessUnknown`（不推进）。
- **FR-051** 收敛复用现有 `PaymentCallbackService.handleCallback`（终态吸收 + 乱序保护 + 幂等），
  **不新建收敛链路**。
- **FR-052** HTTP 响应体 MUST 恰好为**纯文本 `success`**（无引号、无换行、无 JSON 包装）；
  处理异常 → 返回非 `success` 触发支付宝重试；重复通知由终态吸收兜底。
- **FR-053** 本期**同步处理**（现有回调处理是纯 DB 写，够快）；若实测耗时逼近支付宝超时窗口，
  改为「先应答后异步处理」（记入已知限制）。

### 5.7 配置

- **FR-054** 新增配置块：

  ```yaml
  payment:
    channel:
      adapters:
        alipay:
          sandbox:
            enabled: false                 # kill switch；无密钥时保持 false
            gateway-url: https://openapi-sandbox.dl.alipaydev.com/gateway.do
            app-id: ${PAYMENT_ALIPAY_APP_ID:}
            merchant-private-key: ${PAYMENT_ALIPAY_MERCHANT_PRIVATE_KEY:}
            alipay-public-key: ${PAYMENT_ALIPAY_PUBLIC_KEY:}
            notify-url: ${PAYMENT_ALIPAY_NOTIFY_URL:}
            return-url: ${PAYMENT_ALIPAY_RETURN_URL:}
            http-timeout-ms: 10000
  ```

- **FR-055** `enabled=true` 时**启动期强校验**必需项非空（`app-id` / 私钥 / 公钥 / `notify-url`），
  缺失 → 启动失败并列出缺失项（对齐 ADR-0049「配错不许静默走默认」）。
- **FR-056** 既有 `payment.channel.*`（`http-timeout-ms` / `mock-scenario` / `refund-async*` /
  `adapters.{ALIPAY,WECHAT,DOUYIN}.scenario`）语义与默认值**全部不变**。
- **FR-057** `payment.mock-cashier.enabled` 语义收窄为「**仅对 mock 模态生效**」：
  延迟公式改为 `deferChannel = mockCashier.isEnabled() && !DyeContext.isSandbox()`
  （沙箱**不延迟**，必须真调 charge 才拿得到凭证）。

### 5.8 演示

- **FR-058** `demo.html` 新增环境选择器：`本地 mock` / `支付宝沙箱`（默认本地 mock，保持既有演示脚本可复现）。
- **FR-059** 解除 `placeOrder(...)` 与 `demoOverrun(...)` 中硬编码的 `{channelCode:'MOCK'}`：
  mock → `MOCK`（不带染色头）；沙箱 → `ALIPAY` + 请求头 `X-Dye-Tag: SANDBOX`。
- **FR-060** `payUrl` 沿用现有 `window.open(payUrl)`：沙箱时直接打开**支付宝沙箱收银台**。
- **FR-061** `deployment/demo/README.md` 补环境开关说明与预期；`start-all.sh` 补沙箱环境变量；
  可选新增 `deployment/demo/scenario-alipay-sandbox.sh`（**手工 live 验证，不进 CI**）。
- **FR-062** 代理与 `cashier.html` **零改动**（S12 已证明头透传天然成立）。

### 5.9 可观测

- **FR-063** 新增计数器 `payment_channel_mode_total{mode, result}`（按模态观测渠道调用结果）。
- **FR-064** 新增计数器 `dye_tag_rejected_total{reason}`，并落一条 WARN（配置/调用事故需要被看见）。
- **FR-065** 路由与染色日志 MUST 携带 `traceId` 与 `dyeMode`（MDC 两维），复用 spec 021 的 ACCESS 日志体系，
  **不新建日志通道**。
- **FR-066** 支付宝 notify 端点 MUST 记录：`out_trade_no` / `trade_status` / 验签结果 / 是否推进（INFO + 审计日志），
  **禁止打印完整通知报文**（可能含敏感信息）。

### 5.10 兼容与零回归

- **FR-067** 既有 `MockChannelAdapter` 场景语义（ADR-0049）与 `payment.channel.mock-scenario` 配置语义不变；
- **FR-068** 既有 6 处 `PaymentChannel` 测试桩、4 处 `new ChargeRequest(...)`、21 个引用 `ChannelResult` 的测试文件
  **编译零改动**（靠兼容构造器 + `default` 方法 + 工厂方法不变）。
- **FR-069** 不染色时（缺省 `MOCK`）整条链路行为与今天**逐字节一致**，既有 E2E / 集成 / 单元测试零改动通过。
- **FR-070** `ChannelCallbackSecurityTest` 的 HMAC 占位断言**保持不变**——HMAC 占位保留给本地 mock 路径，
  支付宝 notify 走独立 RSA2 端点（FR-047）。

## 6. 演示规划

**复用既有能力**：`mock-channel-web`(8091) 的 `/proxy/{service}/**` 泛代理、`/demo/trace?orderId=` 全链路查询、
`/demo` 演示控制台、`portal.html` 门户、`cashier.html` mock 收银台。**无需新建后端代理。**

### 6.1 新增件

| 件 | 位置 | 说明 |
|---|---|---|
| 环境选择器 | `mock-channel-web/.../static/demo.html` | 「本地 mock / 支付宝沙箱」二选一 |
| 沙箱环境变量 | `deployment/start-all.sh` + `deployment/demo/README.md` | `PAYMENT_ALIPAY_*` 注入说明 |
| 手工验证脚本（可选） | `deployment/demo/scenario-alipay-sandbox.sh` | **不进 CI**（需公网 `notify_url`） |

### 6.2 演示动线

`portal.html` → `demo.html` 选「支付宝沙箱」→ 下单（带头 `X-Dye-Tag: SANDBOX`、`channelCode=ALIPAY`）
→ 响应 `payUrl` → `window.open` 打开**支付宝沙箱收银台** → 用沙箱买家账号付款
→ 支付宝推 notify（需内网穿透）→ payment 收敛 `SUCCEEDED`
→ `/demo/trace?orderId=` 查 `payment_attempts.channel_mode` / `channel_code` / `channel_reference`。

### 6.3 可演示场景

| # | 动作 | 输入 | 可观测结果 | 验证 |
|---|---|---|---|---|
| S1 | 缺省走本地 mock | 不选环境 | `channel_mode=MOCK`，`payUrl` 指向 mock 收银台 | FR-019 / FR-059 |
| S2 | 选沙箱走真实协议 | 选「支付宝沙箱」 | `channel_mode=SANDBOX`，`payUrl` 以沙箱网关开头 | US2 / US3 |
| S3 | 沙箱付款后收敛 | 沙箱买家付款 + notify | payment `SUCCEEDED`，`channel_reference=trade_no` | US4 |
| S4 | 非法染色被拒 | `X-Dye-Tag: BLUE` | `400 INVALID_ARGUMENT`，**不建单** | FR-021 / INV-8 |
| S5 | 沙箱关闸不静默回落 | `sandbox.enabled=false` + 染色 SANDBOX | `400 INVALID_ARGUMENT` | FR-039 |
| S6 | mock 尾数故障注入不变 | 不染色 + 金额尾号 11 | 仍 `timeout`（基类行为未被覆写） | US4-2 / INV（ADR-0072 不变量） |
| S7 | 退款回沙箱 | 对 S3 的支付发起退款 | 退款 attempt `channel_mode=SANDBOX`，走真实退款接口 | US5 |

## 7. 验收标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿（含 `architecture-tests` 边界门禁与 FR-038 的新断言）。
- **SC-002（契约，INV-1/US1）** `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential` 就位；
  `ChargeRequest` / `RefundRequest` / `QueryStatusRequest` 扩展后**保留兼容构造器**，
  既有 4 处构造点与 6 处测试桩**编译零改动**。
- **SC-003（金额纪律，INV-1）** 单测断言 `amountMinor=1` → 支付宝参数 `"0.01"`；
  `amountMinor=100000000` → `"1000000.00"`；代码中**无 `double` / `float`** 参与金额换算。
- **SC-004（凭证，INV-2/US2）** 沙箱下单响应 `payUrl` 为签名 URL；`payment_attempts` **无凭证列**；
  日志中**不出现**完整签名 URL / 私钥 / `client_secret`。
- **SC-005（凭证即待付款，INV-6）** `credential != null` 时不走成功收敛：不记账、不通知 order，
  payment 停 `PROCESSING`；随后 notify 到达才收敛 `SUCCEEDED`。
- **SC-006（染色透传，US3）** 带 `X-Dye-Tag: SANDBOX` 从 demo 页经 `/proxy` → order → payment，
  最终 `payment_attempts.channel_mode = 'SANDBOX'`；不带头时为 `'MOCK'`。
- **SC-007（染色 fail fast，INV-8）** 非法染色值 → `400 INVALID_ARGUMENT` + 指标；
  染色 SANDBOX 而 `enabled=false` → `400`；二者**均不建单**（`payments` 无新增行）。
- **SC-008（染色不入路由，INV-3）** 相同 `RouteContext` 在两种染色下选出**同一** `channelCode`（单测固化）。
- **SC-009（SDK 收口，INV-7）** ArchUnit：`application/channel/**` MUST NOT 依赖 `com.alipay.sdk`。
- **SC-010（入站出站同批，INV-5）** `DyeFilter` 与 `DyeRequestInterceptor` 同批存在；
  单测断言 order-service 侧读出头、Feign 出站写下头（防 S17 重演）。
- **SC-011（模态落库，US5）** 新列三处 schema 齐备；全新库与**存量库 + 迁移脚本**两条路径均可用；
  存量行读为 `'MOCK'`；幂等重复不覆盖库内值。
- **SC-012（反向还原，INV-4/US5）** 对沙箱支付退款：退款调用发生在 `DyeContext=SANDBOX` 包裹内，
  退款 attempt `channel_mode='SANDBOX'`，且**渠道未被重新路由**。
- **SC-013（沙箱协议离线可测，US4）** 新依赖可下载；签名 / 验签 / 参数排序用**固定向量**单测钉死；
  单测全程不连沙箱、不访问公网。
- **SC-014（notify 端点，FR-048~FR-052）** 合法通知 → 收敛并**恰好返回纯文本 `success`**；
  验签失败 → `403` 且不触达收敛服务；`WAIT_BUYER_PAY` → 不推进。
- **SC-015（零回归）** 不染色路径下，既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过。
- **SC-016（文档一致）** `payment-service.md` §3.11 契约定义与代码一致；`technical-solution.md` 仅描述结构不重复字段；
  ADR README 两张表与 traceability 已登记；**链接与锚点自检 0 断链**。

## 8. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
|---|---|---|---|
| L1 | **非跳转型凭证无处承载**：`CreatePaymentResponse` 只有 `payUrl` | 二维码 / 表单 / JSAPI 参数集 / `client_secret` 场景**本期无法端到端**；支付宝仅支持 GET URL 形态 | FR-018 |
| L2 | 染色**不参与选路** | 染色 `SANDBOX` + `WECHAT`（无真实实现）→ `400 INVALID_ARGUMENT`；demo 页选沙箱时**自动选 ALIPAY** | FR-026 / INV-8 |
| L3 | 沙箱单待付款时 payment 停 `PROCESSING`，`TimeoutScanner` 30s 后可能把它转 `UNKNOWN` | 与既有 mock 延迟路径**同构**（非新增风险），靠 `alipay.trade.query` / notify 收敛 | FR-057 |
| L4 | `notify_url` **必须公网可达** | 本地演示需内网穿透；沙箱不产生真实资金 | FR-054 |
| L5 | 支付宝 SDK **34MB jar + 传递依赖（fastjson / okhttp / bcprov / dom4j）+ 已知 CVE** | 依赖体积与攻击面上升；已收口在 `AlipayGateway` 后，将来可换纯 JDK 实现零扩散 | ADR-0076 |
| L6 | 沙箱**不能进 CI**（不可复现、需密钥与公网） | 真机验证为**手工**；CI 只跑离线单测（固定签名向量） | §6 / plan.md |
| L7 | `page.pay` 同步响应不含 `trade_no` | `charge` 成功时 `channel_reference = null`；`channel_reference` 直到 notify 才落值 | FR-041 |
| L8 | 抖音存在**两套并存**的下单体系；支付宝退款是否有独立 `refund_notify_url` 未定 | 仅影响适配器内部映射，**不影响内部契约**；实现期复核 | §2.1 尾注 |
| L9 | 支付宝沙箱 HTTP 超时 10s 与全局 1.5s 不一致 | 同一 `payment.channel` 下存在两档超时，需在文档与配置注释中显式说明 | FR-045 |
| L10 | notify 端点**同步处理** | 若实测耗时逼近支付宝超时窗口，需改「先应答后异步」 | FR-053 |
| L11 | 染色值**不在响应业务体中回显**（只在响应头与 MDC） | 演示页要展示当前模态需读响应头 | FR-021 |

## 9. 不做（Out of Scope）

- ❌ **不新建 `AlipaySandboxChannelAdapter`**——在 `AlipayChannelAdapter` 内双模态（负责人 2026-09-19 明确）。
- ❌ **不改渠道码**（不把 mock 三兄弟改为 `ALIPAY_MOCK` 之类）——避免波及演示脚本 / 前端 / 测试。
- ❌ 不接微信 / 抖音 / Stripe 的真实协议（本期只做**支付宝沙箱**；契约先兼容四家，实现后续逐家接）。
- ❌ **不做二维码 / 表单 / JSAPI 参数的端到端承载**（L1），不为此改 `common-dto`。
- ❌ **不改支付状态机**——待付款复用 `PROCESSING`，不新增 `AWAITING_PAYMENT`。
- ❌ 不做部分退款语义变更、不做自动降级 failover、不做智能路由（spec 028 §8 继续有效）。
- ❌ **不引入 Nacos 配置中心 / `@RefreshScope`**（染色靠请求头，不靠动态配置）。
- ❌ 不把染色用于**流量灰度分流**（本轮只用于 mock/sandbox 协议分流）。
- ❌ 不做渠道真实健康探测（spec 028 L1 继续有效）。
- ❌ 不改 `mock-channel-web` 的代理与 `cashier.html`。
- ❌ 不回填存量 attempt 行的渠道模态（`DEFAULT 'MOCK'` 即定论）。

## 10. 已拍板口径

| # | 口径 | 裁决 | 来源 |
|---|---|---|---|
| D1 | 反向路径模态判定 | `payment_attempts` **加列**记录模态，反向读列 | 负责人 2026-09-19 |
| D2 | 支付宝协议实现 | 引 `com.alipay.sdk:alipay-sdk-java`，收口在 `AlipayGateway` 后 | 负责人 2026-09-19 |
| D3 | 染色落点 | `common-core` 全链路透传（仿 traceId 三段式） | 负责人 2026-09-19 |
| D4 | 契约粒度 | 分组嵌套（`Goods` / `CallbackUrls` / `Payer`）+ `channelExtra` 扩展袋；金额保持扁平 | 负责人 2026-09-19 |
| D5 | 支付宝凭证形态 | **签名 GET URL** → 直接当 `payUrl`，演示页 `window.open` 零改造 | 负责人 2026-09-19 |
| D6 | 凭证出参 | **只复用 `CreatePaymentResponse.payUrl`**，不动 `common-dto`；order-service 零改动是硬约束 | 负责人 2026-09-19 |
| D7 | 待买家付款时的支付单状态 | 复用 `PROCESSING`（不碰状态机） | 负责人 2026-09-19 |
| D8 | 单 Adapter 双模态 | **不新建 Sandbox Adapter**；`MOCK` 分支必须 `super` 委托 | 负责人 2026-09-19 |
| D9 | demo 环境开关 | 演示页下单时选「本地 mock / 支付宝沙箱」，经全链路染色传到适配器 | 负责人 2026-09-19 |
| D10 | 交付范围 | **本轮只写文档不改代码**（本轮为文档轮）；实现待两份 ADR 转 Accepted | 负责人 2026-09-19 |
| D11 | 验证边界 | 代码 + **离线单测**；真机沙箱由负责人自测（自备 APPID / 密钥 + 公网 `notify_url`），**沙箱不进 CI** | 负责人 2026-09-19 |

**待负责人确认**：ADR-0075 / ADR-0076 的 Proposed 状态是否升为 Accepted（升为 Accepted 即可进入实现）。

## 11. 文档与决策影响

| 类型 | 对象 | 动作 |
|---|---|---|
| 新增 ADR | `docs/adr/0075-unified-channel-contract.md`（ADR-0075，🟡 Proposed） | 本轮创建 |
| 新增 ADR | `docs/adr/0076-traffic-dyeing-and-alipay-sandbox.md`（ADR-0076，🟡 Proposed） | 本轮创建 |
| 新增 Spec | `docs/specs/030-channel-contract-dye-alipay-sandbox/`（本文档 + plan / tasks / acceptance） | 本轮定稿 |
| **部分取代** | ADR-0072（两层结构）决策 4「子类只声明身份与差异」与「不接入真实渠道 SDK」 | ADR-0076 修订为「ALIPAY 双模态特例，MOCK 分支必须 `super` 委托」；其余条款不变 |
| **部分取代** | ADR-0073（渠道路由）「不做：真实渠道 SDK 接入」 | ADR-0076 取代该项；路由规则本身不变 |
| **部分取代** | spec 028 §7 L5 与 §8「不接入真实渠道 SDK / 不引入新第三方依赖」 | 加注「已于 2026-09-19 由 ADR-0075/0076 开启」 |
| **裁决登记** | ADR-0052「待用户后续裁决：是否接入真实验签」 | 本次裁决：支付宝走 **RSA2 独立端点**；HMAC 占位**保留给本地 mock 路径** |
| **登记指向** | ADR-0025（HMAC 占位） | 加注指向 ADR-0076（真实渠道走独立端点，不改 HMAC 口径） |
| **消除漂移** | `payment-service.md` 新增 §3.11 渠道内部契约；§3.2 / §3.5 / §7 同步；**顺带修两个重复的 `§3.10`** | 本轮同步 |
| **消除漂移** | `technical-solution.md`：§2.4（预留契约兑现）、§3.5（技术栈新增依赖）、§5.2（验签不再恒放行）、§4.3（凭证出参） | 本轮同步，**不展开字段细节** |
| **消除漂移** | `docs/operations/runbook.md` 新增沙箱密钥 / 染色 / 穿透运维项 | 本轮同步 |
| **消除漂移** | `deployment/demo/README.md` 环境开关说明 | 本轮同步 |
| **顺带修** | `docs/specs/028-channel-routing/tasks.md` 首行标题误写「026」 | 本轮修正 |
| 保持不变 | spec 028 的 INV-1~INV-6、幂等键结构、路由确定性、前向/反向分离；ADR-0012 双响应码、ADR-0049 场景配置化、ADR-0063 业务单号、ADR-0026 密钥 env | 不改动 |
