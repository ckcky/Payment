<a id="adr-0075"></a>

# ADR-0075: 聚合支付统一渠道契约——能同时容纳支付宝 / 微信 / 抖音 / Stripe

- 状态：🟢 **Accepted**（2026-09-19 提出并拍板 D1~D11；**2026-09-19 负责人确认：由 Proposed 升级为 Accepted**。实现由 [spec 030](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md) 承接——本 ADR 的契约条款**原样保留、未被修改**；原 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 四件套已于 2026-09-19 **整目录删除**，其设计被该 spec 全量吸收）
- 关联：
  - [ADR-0072](0072-two-layer-channel-architecture.md)（payment-service 两层结构——本 ADR **扩展渠道层的契约**，其结构条款（表归属、写入口、分层≠拆事务）**全部不变**）
  - [ADR-0073](0073-channel-routing.md)（渠道路由——`ChannelRouter` / `ChannelRegistry` 语义不变；本 ADR 的 `scene` / `channelExtra` **不参与选路**）
  - [ADR-0076](0076-traffic-dyeing-and-alipay-sandbox.md)（同期决策：凭证的**消费方**与染色分流）
  - [ADR-0010](0008-ledger-design-decisions.md)（金额只用 `long` 分、**不启用 Money VO**——本 ADR 的金额扁平化直接沿用）
  - [ADR-0012](0012-payment-reliability-impl-decisions.md)（双响应码错误分类——`ChannelResult` 的 `status`/`errorType`/`retryable` 派生逻辑不变）
  - [ADR-0016](0016-refund-decisions.md)（部分退款 ❌ Rejected——本 ADR 的 `outRequestNo` 只是**为将来**保留渠道语义，**不改变「退款恒按全退处理」**）
  - [ADR-0063](0063-cross-service-reference-by-business-no.md)（跨系统一律业务单号——契约内标识沿用 `paymentNo` / `refundNo`）
  - spec 015（多渠道支付）、spec 028（两层结构 + 路由）、spec 030（本 ADR 的**实现轮**，落在 stage-05）
- 需求源头：负责人 2026-09-19「**我们系统内部的接口需要重新设计下，之前是极简版本，很多字段都是没有的**。你主要看看支付宝、微信、抖音支付、stripe 的接口需要什么参数，都什么意思。然后我们聚合支付内部接口怎么设计才能兼容他们这些。**要通用要合理，还要结构清晰**」；同日追加「**ChannelResult 没有承载"渠道凭证"的地方，这个需要加**，基本上每个三方渠道下单之后肯定会返回一个 payUrl，这个要加到请求了」。

## 背景

### 一、现状契约装不下任何一家真实渠道

| # | 事实 | 证据 |
|---|---|---|
| C1 | `ChargeRequest(String paymentNo, Long attemptId, long amountMinor, String currencyCode, String channelCode)` —— **5 个位置参数、零校验、无商品 / 回调地址 / 超时 / 场景 / 付款人字段** | `application/channel/ChargeRequest.java` |
| C2 | `RefundRequest(String paymentNo, String refundNo, long amountMinor, String currencyCode, String channelCode)` —— **无渠道交易号、无原支付金额、无退款请求号、无退款原因** | `RefundRequest.java` |
| C3 | `QueryStatusRequest(String paymentNo, String transactionId, String idempotencyKey)` —— **无渠道交易号** | `QueryStatusRequest.java` |
| C4 | `ChannelResult(Status, channelReference, reason, transportCode, businessCode)` + 7 个静态工厂 + 私有 `of(...)` + `withReason(...)` —— **没有任何字段能承载二维码 / 表单 / `client_secret` / payUrl** | `ChannelResult.java:39-99` |
| C5 | 「支付场景」在内部**完全没有概念**；三渠道的差异只体现在 `channelCode` 字符串与金额尾数故障注入上 | `AbstractMockChannelAdapter`（spec 028） |
| C6 | `PaymentChannel` 只有 4 个方法、**无任何 `default` 方法**；直接实现它的测试桩共 **6 处** | `PaymentChannel.java`；`ChannelQueryTest:32`、`PaymentRetryTest:42`、`ReliabilityMetricsTest:37`、`PaymentDeferredChannelTest:25/75`、`ConfiguredChannelRouterTest:27` |
| C7 | 直接 `new ChargeRequest(...)` 共 **4 处**；`ChannelResult` 被 **21 个测试文件**引用但**全部走静态工厂**（无外部 `new ChannelResult(...)`） | 全量检索 |
| C8 | 契约是**服务内契约**（非跨服务 RPC）；跨服务的 `CreatePaymentRequest` / `CreatePaymentResponse` 在 `common-dto`，本次**不动** | `common/common-dto/` |

### 二、四家渠道的关键差异（这是契约设计的输入）

| 语义 | 支付宝 `page.pay` | 微信 v3 | 抖音支付 | Stripe |
|---|---|---|---|---|
| **金额单位** | **元字符串**（`total_amount="0.01"`） | **分**（`amount.total=1`） | 分 | 最小货币单位 |
| 币种 | 无字段（隐含 CNY） | `amount.currency` | `amount.currency` | `currency`（**小写**） |
| 商品标题 | `subject`（**必填**，≤256） | `description`（**必填**，≤127） | `description`（必填） | `description` |
| 商品描述 | `body`（≤128） | `detail.goods_detail[]` | — | 同 `description` |
| **场景表达** | `product_code` + `qr_pay_mode` | **URL 路径**（`/jsapi` `/native` `/h5` `/app`） | 路径 | `payment_method_types` + `confirm` |
| 付款人 | `buyer_id` | `payer.openid`（**JSAPI 必填**） | `openid` | `customer` |
| 终端 IP | — | `scene_info.payer_client_ip`（**H5 必填**） | — | — |
| 异步通知 | `notify_url`（公共参数） | `notify_url`（**须 HTTPS、禁查询串**） | `notify_url` | **账号级 webhook**（非按单） |
| 同步跳转 | `return_url` | H5 的 `redirect_url` | — | `return_url`（仅 `confirm=true`） |
| 过期 | `timeout_express`（**相对量** `"90m"`） | `time_expire`（RFC3339；**仅支付截止，不关单**） | 同微信 | 无（需另调 cancel） |
| 退款定位 | `out_trade_no` **或** `trade_no` | `out_trade_no` **或** `transaction_id` | `transaction_id` | PaymentIntent `pi_xxx` |
| 退款请求号 | `out_request_no` | `out_refund_no` | `out_refund_no` | `metadata` + `Idempotency-Key` |

| # | 事实 | 证据 |
|---|---|---|
| C9 | **四家返回的付款凭证形态完全不同**：支付宝 = 表单 HTML 或签名跳转 URL；微信 JSAPI = `prepay_id` + 6 参数集，Native = `code_url`（**二维码串，非图片**），H5 = `h5_url`（**须手机浏览器**）；抖音 = `prepay_id`；Stripe = `client_secret` | 四家官方文档；spec 030 §2.1 |
| C10 | 微信 H5 的 `h5_url` 与支付宝的签名 URL **都是 URL，但打开端不同**（微信校验 UA，PC 打开被拒） | 同上 |

### 三、既有约束（不可违背）

| # | 事实 | 证据 |
|---|---|---|
| C11 | 金额只用 `long` 分 + `currencyCode`，**不启用 Money / Amount 值对象** | ADR-0010；全仓 DTO 口径 |
| C12 | 契约内标识一律业务单号（`paymentNo` = PM+雪花、`refundNo` = PMRF） | ADR-0063 |
| C13 | `CreatePaymentResponse` 被 order-service **透明透传**（原样回前端）；改它需两侧同时改 | `OrderApplicationService.createPaymentForOrder` |
| C14 | 核心领域 MUST NOT 依赖具体渠道实现（`Payment ≠ Channel`） | ADR-0072 / Constitution |

## 决策

**D1. 契约粒度 = 四组结构化字段 + 一个渠道扩展袋；金额保持扁平。**

| 分组 | 字段 | 为什么独立成组 |
|---|---|---|
| 扁平 | `paymentNo` / `attemptId` / `amountMinor` / `currencyCode` / `channelCode` | 全仓一致口径已是 `amountMinor`+`currencyCode`（C11），封装只增拆包噪音 |
| `Goods` | `title` / `description` | 四家对「标题/描述」切分不一致（支付宝 `subject`+`body`，微信只有一个 `description`），内部取**并集语义** |
| `CallbackUrls` | `notifyUrl` / `returnUrl` | 两者**语义完全不同**：异步通知是**资金事实来源**（须公网可达），同步跳转**只是体验**。混在一起会诱导实现者用 `returnUrl` 推进状态 |
| `Payer` | `payerId` / `clientIp` | 「站内已登录用户」（openid / customer）与「匿名 H5」（只有终端 IP）是两种调用形态 |
| `PaymentScene` | 枚举六值 | 见 D2 |
| `channelExtra` | `Map<String,String>` | 见下 |

**「什么进通用字段、什么进扩展袋」的判据（唯一标准，实现期 MUST 遵守）**：

> 四家**语义一致且支付必需** → 结构化字段；
> 仅 1~2 家有、**语义不统一**、或非必需 → `channelExtra`。
> **严禁**把渠道私有参数塞进通用字段（如新增 `qrPayMode`）；也**严禁**把通用语义降级成扩展袋（等于放弃抽象）。

`channelExtra` 的键名约定用**渠道原生参数名**（如 `qr_pay_mode` / `goods_tag` / `statement_descriptor`），便于排障时与渠道文档逐字比对。

**D2. `PaymentScene` 是渠道能力声明的载体，不是选路依据。**

- 枚举六值：`WEB / H5 / NATIVE / JSAPI / MINI_PROGRAM / APP`——只保留「**用户在哪、以什么方式付款**」这一层语义。
- **不搬任何一家的词表进内部契约**（支付宝 `product_code` / 微信「路径即场景」/ Stripe `payment_method_types` 三家模型完全不同，搬谁的都会绑架其余）。
- **Stripe 的 `payment_method_types` 不进本枚举**：它是「用什么工具付」（`card` / `alipay` / `link`），与「在哪个端付」正交，走 `channelExtra`。
- `PaymentChannel.supportedScenes()` 由适配器声明能力；请求了未声明的场景 → **明确失败**（见 D2 约束），**不静默降级**（依 [ADR-0049](0048-demo-showcase-decisions.md) 第 2 条纪律）。
- **校验落在 payment 层编排（调 `charge` 之前）**，不放在适配器内部：这样失败是 `400 INVALID_ARGUMENT` 的**明确拒绝**，且不产生半状态的 `ChannelResult`。`scene == null`（旧调用）不校验 → 零回归。

**D3. `ChannelResult` 加可空 `credential` 字段；不另立 `ChargeResult`。**

- 新增工厂 `accepted(String channelReference, String reason, PayCredential credential)`；
- 既有 7 个工厂**语义与签名不变**（`credential = null`）；
- 保留 5 参构造重载（委托 `credential = null`），保证无外部构造点断裂（C7 已确认无外部构造，双保险）；
- `withReason(...)` MUST **保留** `credential`（重试耗尽标注 `RETRY_EXHAUSTED` 时不得丢凭证）；
- `refund` / `queryStatus` 的返回值 `credential` **恒为 `null`**（只有下单会产出付款凭证）。

**D4. 凭证必须类型化：`PayCredential(kind, payload, expiresAt)`，`Kind ∈ {REDIRECT_URL, FORM_HTML, QR_CODE, H5_URL, JSAPI_PARAMS, CLIENT_SECRET}`。**

用单一 `String payUrl` 承载四家的返回会产生语义错配（把 HTML 塞进叫 URL 的字段、把二维码串当 URL 打开）。

`REDIRECT_URL` 与 `H5_URL` **刻意不合并**（C10）：两者都是 URL，但前者任意端可开、后者**必须手机浏览器**。保留两个 Kind 是为了让调用方能做正确的端侧判断，而不是把差异藏在字符串里。

**D5. 凭证不落库。**

`payload` 含签名、有时效、长度远超 `payment_attempts.channel_reference VARCHAR(128)`，且不具备「渠道交易流水号」的唯一性语义（会撞 `uk_attempts_channel_reference`）。持久化的渠道标识**恒为** `channel_reference`。

**D6. `RefundRequest` 必须能带三样现在没有的东西：渠道交易号、退款请求号、退款原因。**

| 字段 | 为什么必须 |
|---|---|
| `channelTransactionId` | 真实渠道退款要按**渠道侧交易号**定位（支付宝 `trade_no` / 微信 `transaction_id` / Stripe `pi_xxx`）；只靠 `out_trade_no` 无法在渠道侧精确收敛 |
| `outRequestNo` | 渠道的**退款请求号**（支付宝 `out_request_no` / 微信 `out_refund_no`）——渠道用它做退款幂等与「同笔多退」区分 |
| `refundNotifyUrl` | 部分渠道支持退款异步通知（与支付通知是**不同**的回调类型） |

> ⚠️ **不改变退款语义**：`outRequestNo` / `refundNotifyUrl` 只是把渠道已有的能力**透出来**，
> **不推翻 ADR-0016「部分退款不做、退款恒按全退处理」**——本项目的退款仍由 order 驱动、按申请额全退。

**D7. `QueryStatusRequest` 必须能带渠道交易号。**

否则 `alipay.trade.query` 只能按 `out_trade_no` 查，`trade_no` 精确收敛路径不可用；微信查单同样有 `out_trade_no` / `transaction_id` 两条路径。

**D8. 兼容优先：三个 record 保留旧参数个数的构造重载；跨服务 DTO 一律不动。**

- `ChargeRequest` 保留 5 参构造器；`RefundRequest` 保留 5 参；`QueryStatusRequest` 保留 3 参；
- `PaymentChannel` 新增的两个方法**必须是 `default`**（否则 C6 的 6 处测试桩编译失败）；
- `CreatePaymentResponse` 与全部 `common-dto` **不动**（C13：order-service 透明透传，改它要两侧同步）。
- 三个 record 是**服务内契约**，不属于 Constitution 的「已发布 API 契约 / 跨服务接口」，**不触发 API Breaking Change 人类决策边界**。

**D9. 金额换算的责任在适配器，纪律在契约。**

契约内**只出现** `amountMinor`(long) + `currencyCode`(String)；「元字符串」只在支付宝适配器内部生成，
MUST 用 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`，**禁止 `double` / `float`** 参与任何金额换算。
`expireAt` 用 `Instant`（绝对时刻），**相对量换算（支付宝 `timeout_express` 的 `"90m"`）由适配器负责**。

## 约束（实现期 MUST 遵守）

- **K1**：`channelExtra` 只放渠道私有参数；通用语义 MUST 使用结构化字段（D1 判据）。
- **K2**：任何金额换算 MUST 经 `BigDecimal.valueOf(minor, 2)`；代码中 MUST NOT 出现 `double` / `float` 金额运算。
- **K3**：`credential` MUST NOT 落库、MUST NOT 进明文日志、MUST NOT 进 git。
- **K4**：`withReason(...)` / 任何 `ChannelResult` 派生操作 MUST 保留 `credential`。
- **K5**：`scene` 校验 MUST 在调 `charge` 之前完成；未支持的场景 MUST 明确失败（`400 INVALID_ARGUMENT`），MUST NOT 静默降级为默认场景。
- **K6**：场景/扩展袋信息 MUST NOT 影响 `ChannelRouter` 的选路结果（ADR-0073 的确定性不变量不受本 ADR 影响）。
- **K7**：`application/**` 对渠道协议的依赖 MUST 只经端口（`PaymentChannel` / `AlipayGateway`），MUST NOT 依赖 SDK 类型。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| **照抄某一家的参数模型**（如把 `product_code` 设为内部字段） | 其余三家会被这套词表绑架（C9/C10 已证明三家模型互斥），且违背「核心领域不依赖具体渠道」（C14） |
| **按渠道分 DTO**（`AlipayChargeRequest` / `WechatChargeRequest` …） | `PaymentChannel` 端口退化成弱抽象（每个适配器要一个方法），核心领域开始依赖具体渠道类型，**直接触碰 Constitution 红线** |
| **全扁平 15+ 字段** | 渠道私有参数（`qr_pay_mode` / `scene_info` / `statement_descriptor`）无处安放，只能继续加通用字段 → 契约必然膨胀成四家的并集垃圾场 |
| **封装 `Amount` / `Money` 值对象** | 与 ADR-0010（金额只用 `long` 分、不启用 Money VO）冲突；且全仓 DTO 口径已是 `amountMinor` + 货币码，封装只增加拆包噪音 |
| **另立 `ChargeResult` 组合类型**（而非给 `ChannelResult` 加字段） | 改 `PaymentChannel.charge` 返回类型 → 波及 6 处测试桩 + 重试链 + `converge` + 持久化；而 `ChannelResult` 已有 21 个引用文件**全部走静态工厂**，加字段几乎零成本 |
| **给 `CreatePaymentResponse` 加 `credentialKind`** | 改 `common-dto` 会破坏 order-service 的透明透传（C13），属跨服务 API 变更；本轮以「凭证类型」只在内部流转、跨服务只传 `payUrl` 收口（已知缺口见影响） |
| **把 `scene` 直接做成 `String` 透传**（渠道私有值） | 等于把某一家的词表写进内部契约（同第 1 条），且失去「渠道能力声明」这一校验点 |

## 影响

- **正影响**：
  - 新增一家渠道**不必再动端口**——差异要么落在 `scene`，要么落在 `channelExtra`；
  - 渠道私有参数不再污染通用字段，契约可在代码评审时用 D1 判据客观校验；
  - 付款凭证有类型化载体后，模拟渠道与真实渠道在**同一个抽象**下产出结果（mock 也可以返回 `QR_CODE` 做演示）；
  - 退款/查单补上渠道交易号后，真实渠道的**精确收敛**路径才成立。
- **代价 / 已知缺口**：
  - ⚠️ **非跳转型凭证本期无处承载**：`CreatePaymentResponse` 只有 `payUrl` 一个字段，
    二维码 / 表单 HTML / JSAPI 参数集 / `client_secret` **无法端到端**，且前端无法区分凭证类型。
    本期只打通 `REDIRECT_URL` / `H5_URL`（支付宝沙箱场景）；补齐需改 `common-dto`（跨服务变更），
    记入 spec 030 §8 L1，下期单独立项；
  - `ChargeRequest` / `RefundRequest` 字段数上升，构造点易错 → 靠兼容构造器 + 后续引入 Builder 缓解；
  - 两套构造重载并存期间，代码里会同时出现「5 参旧式」与「12 参新式」，**实现期 MUST 只在新增路径用新式**，
    存量调用点按需迁移（不在本 ADR 范围）。
- **明确不做**：不改 `PaymentChannel` 的方法集合与返回类型（除 D3 的字段扩展）；不改路由语义；
  不改退款状态机与金额校验口径（ADR-0016 / ADR-0047）；不改跨服务 DTO。

## 落地

- 实现计划：[spec 030 的 plan.md](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/plan.md) **批次 3**（契约 + 类型化凭证）；
- 字段级权威定义：`docs/architecture/systems/payment-service.md` §3.11；
- 凭证的消费方（染色分流与沙箱适配器）：见 [ADR-0076](0076-traffic-dyeing-and-alipay-sandbox.md)。
