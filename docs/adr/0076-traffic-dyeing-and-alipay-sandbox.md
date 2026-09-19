<a id="adr-0076"></a>

# ADR-0076: 全链路染色分流（mock / 沙箱）+ 渠道模态落库 + 支付宝沙箱接入

- 状态：🟢 **Accepted**（2026-09-19 提出并拍板 D1~D11；**2026-09-19 负责人确认：由 Proposed 升级为 Accepted**。实现由 [spec 030](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md) 承接。⚠️ **落库形态修订（2026-09-19，H2）**：`payment_attempts.channel_mode` 专用列**不再采用**，改为通用 JSON 列 `extra_json` 的 `channelMode` 键承载——**决策语义不变**（模态仍「落库 + 反向读库」），仅载体形态不同，详见下方「修订记录」；`alipay-sdk-java` 新依赖已由负责人裁决**引入**（H3））
- 关联：
  - **部分取代 [ADR-0072](0072-two-layer-channel-architecture.md)**（payment-service 两层结构）：
    - 其决策 4「渠道实现族：抽象基类承载横切行为，**子类只声明身份与差异**」被本 ADR **修订**为
      「**`ALIPAY` 升级为双模态特例**：子类可覆写『协议实现的分发』，但 **`MOCK` 分支必须 `super` 委托**，
      基类 4 件横切行为口径 100% 不变」；
    - 其「❌ **不接入真实渠道 SDK**」被本 ADR 取代；
    - **其余条款全部保持不变**（两层职责切分、表归属与写入口、分层 ≠ 拆事务、退款渠道取自原始支付记录）。
  - **部分取代 [ADR-0073](0073-channel-routing.md)**（渠道路由）：其影响章「**不做**：……真实渠道 SDK 接入」被本 ADR 取代；
    **路由规则本身、`channelCode` 仍为 String 不改枚举、确定性选路、前向/反向严格分离** 全部保持不变。
  - [ADR-0075](0075-unified-channel-contract.md)（同期决策：统一渠道契约与类型化凭证——本 ADR 是凭证的**消费方**）
  - [ADR-0025](0024-risk-security-decisions.md)（渠道回调 HMAC 验签**预留空实现**——本 ADR **不改其口径**，HMAC 占位保留给本地 mock 路径）
  - [ADR-0052](0052-channel-callback-signature-decisions.md)（⛔ Not Implemented，原文留下「待用户后续裁决：是否接入真实验签」——**本 ADR 给出裁决**）
  - [ADR-0026](0024-risk-security-decisions.md)（密钥明文 env 注入、禁硬编码/禁入库——沙箱密钥沿用）
  - [ADR-0049](0048-demo-showcase-decisions.md)（**配错不许静默走默认**——染色非法值与「开关未开」一律 fail fast）
  - [ADR-0024](0024-risk-security-decisions.md) / [ADR-0034](0034-internal-token-decisions.md)（内部令牌**先例教训**：入站校验上线但出站头未同步补 → 全线 403 → 整体删除）
  - [ADR-0012](0012-payment-reliability-impl-decisions.md)（`ChannelResult` 双响应码——沙箱适配器 MUST 沿用同一错误分类）
  - spec 019（退款异步回调）、spec 028（两层结构 + 路由）、spec 030（本 ADR 的**实现轮**，落在 stage-05）
- 需求源头：负责人 2026-09-19「**你就只在 AlipayChannelAdapter 上实现就行啊，不需要专门搞 SandboxChannelAdapter。通过链路染色的方式区分是走本地的 mock**」；
  同日追加「简单来说就是**到底是走本地 mock 还是走 sandbox 的 mock 是由 demo 演示页面下单的时候选的环境，全链路染色传过来的**。demo 页面要把这个开关选择加上」。

## 修订记录

### 2026-09-19 · 落库形态修订（H2 裁决，负责人拍板）

| 项 | 原表述（本 ADR 定稿时） | 修订后 | 性质 |
|---|---|---|---|
| 模态落库载体 | `payment_attempts` **新增专用列** `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'` | `payment_attempts` **新增通用 JSON 列** `extra_json TEXT NULL`，模态以键 **`channelMode`** 承载（`MOCK` / `SANDBOX`） | **载体形态修订**——决策语义**不变**：模态仍以「**落库 + 反向读库**」实现 |
| 迁移脚本文件名 | `030-payment-attempt-channel-mode.sql` | `030-payment-attempt-extra-json.sql` | 随载体与 Spec 编号同步（Spec 编号已由 `031` 定为 `030`） |
| JSON 存法 | — | **`TEXT` 存 JSON**（沿用项目既有 `payload_json` / `matches_json` / `differences_json` 先例），**不使用 MySQL 原生 `JSON` 类型**（H2 测试库兼容） | 新增约束 |

**修订理由（原文摘要）**：负责人在 spec 030 门 2 裁决中要求「**不新增专用列，用通用 JSON 列承载**」，
使 `payment_attempts` 未来新增渠道交互扩展字段无需再动 schema。
**决策语义未被破坏**：本 ADR 的 R4「为什么必须落库」、K3「反向路径 MUST 读库」、K6「schema 三处齐备」
**全部继续有效**，仅把「专用列」替换为「通用 JSON 列的固定键」。

**新增两条实现期约束（由 spec 030 FR-303 / FR-304 补充，本 ADR 原文未覆盖）**：

- **写入侧强制**：`ChannelAttemptRecorder` 新建 attempt 时 **MUST** 写入 `channelMode` 键（**新写入行 MUST NOT 缺失**）；
- **读取侧 fail-safe**：`extra_json` 为 `NULL` / 非法 JSON / 缺键 / 值非法 ⇒ **一律按 `MOCK`**（安全方向，**MUST NOT** 误连真实渠道）。

> 载体与约束的完整定义见 [spec 030 §14.1](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md)（FR-300~FR-309）。

## 背景

### 一、能力缺口

| # | 事实 | 证据 |
|---|---|---|
| G1 | **全项目不存在任何染色 / 打标 / 灰度 / 泳道机制**（0 处 dye / 染色 / lane / gray 实现）；Nacos 只做服务发现，**无配置中心、0 处 `@RefreshScope`** | 全仓检索 |
| G2 | 演示页下单**硬编码 `channelCode:'MOCK'`**，且**没有任何「环境」概念** | `demo.html` 的 `placeOrder(...)` / `demoOverrun(...)` |
| G3 | `payment_attempts` **无渠道模态列**（`channel_mode` 不存在） | `deployment/schema/03-payment-schema.sql` |
| G4 | `payment.mock-cashier.enabled` 是**全局开关**：`boolean defer = mockCashier.isEnabled();` —— 一开就对**所有渠道**跳过 `charge` | `PaymentController.java` |

### 二、可复用的骨架（染色照抄的对象）

| # | 事实 | 证据 |
|---|---|---|
| G5 | **traceId 三段式骨架完整**：`TraceIdFilter`（`OncePerRequestFilter`，order = -200，读 `X-Trace-Id` → ThreadLocal + MDC → 响应头回写 → finally 清理）+ `TraceContext`（ThreadLocal + `runWithNewTrace`）+ `TraceIdRequestInterceptor`（**全仓唯一** Feign `RequestInterceptor`） | `common-core/.../trace/`、`client/` |
| G6 | 装配在 `CommonCoreAutoConfiguration`（`FilterRegistrationBean`）与 `FeignTraceAutoConfiguration`（`@ConditionalOnBean`/`@ConditionalOnMissingBean`）；`AutoConfiguration.imports` 已注册这两类 | `common-core/.../config/`、`META-INF/spring/` |
| G7 | **common-core 被 9 个业务服务 + common-dto + common-mybatis + mock-channel-web 依赖** → 在其中加入站 Filter + 出站 Interceptor **天然覆盖全栈，无需改任何服务 pom** | 各服务 pom |
| G8 | demo 页经 `mock-channel-web`(8091) 的 `/proxy/{service}/**` **黑名单式逐头透传**（只跳过 host/content-length/connection/transfer-encoding/keep-alive/upgrade）→ **任意自定义头都能原样到 order-service** | `DemoProxyController.copyRequestHeaders` |
| G9 | order-service **无任何 Filter**，只显式消费 `Idempotency-Key` | `order-service` 全量检索 |
| G10 | 调度器（`TimeoutScanScheduler` / `ChannelQueryScheduler` / `LimitCompensationScheduler`）**不经入站 Filter**，靠 `TraceContext.runWithNewTrace` 自行造上下文 | 各 Scheduler |

### 三、风险与既有约束

| # | 事实 | 证据 |
|---|---|---|
| G11 | **先例教训**：`InternalTokenRequestInterceptor` + 入站鉴权曾是一套完整的「出站 header 透传 + 入口校验」实现，因**入站校验上线但调用方未同步补出站头**导致**全线 403**，被 ADR-0024/0034 决议**整体删除**（代码已删，注释残留） | `0034-internal-token-decisions.md` |
| G12 | 支付回调入站只有 **HMAC 占位**：`ChannelCallbackSignatureFilter#verifySignature` **恒 `return true`**，urlPatterns = `/internal/payments/*` + `/internal/refunds/*`，预设 **JSON body** + `X-Channel-Signature`/`X-Channel-Timestamp` | `web/ChannelCallbackSignatureFilter.java`、`web/WebConfig.java` |
| G13 | 支付宝通知是 **form-urlencoded 表单** + **RSA2（支付宝公钥）验签**，与 G12 的 HMAC+JSON **方向与算法都不同** | 支付宝开放平台文档 |
| G14 | 渠道码 `ALIPAY` **已被 mock 三兄弟之一占用**（`AlipayChannelAdapter` 返回 `"ALIPAY"`），而注册表是 `Map<String, PaymentChannel>`，**一个 code 只能一个实例** | ADR-0073 / `SpringChannelRegistry` |
| G15 | `alipay-sdk-java`（`4.40.994.ALL`，2026-09-11）**jar 本体 34.3 MB**，传递依赖含 `fastjson 1.2.83_noneautotype` / `okhttp 3.12.13` / `bcprov-jdk15on 1.62` / `dom4j 1.6.1` / `commons-logging 1.1.1`；公开漏洞库对其标记 **≥9 个 CVE** | Maven Central / 依赖树与漏洞库核对 |
| G16 | `payment_attempts.channel_reference` 是 `VARCHAR(128)` + **唯一约束** `uk_attempts_channel_reference` | `03-payment-schema.sql` |
| G17 | schema **无 Flyway / 无 ddl-auto**；建表用 `CREATE TABLE IF NOT EXISTS` → **存量库不会补列**，靠 `deployment/demo/reset.sh` 重放建表 | `deployment/schema/`、`deployment/demo/reset.sh` |
| G18 | 负责人明确「**不新建 SandboxChannelAdapter**」（需求源头） | 需求源头 |
| G19 | 支付宝 `page.pay` **同步响应不含 `trade_no`**（交易号由异步通知带入） | 支付宝文档 |
| G20 | 支付宝要求商户对异步通知返回**纯文本 `success`**，否则按递增间隔重试（约 25 小时内 8 次） | 支付宝文档 |

## 决策

**R1. 染色用 `common-core` 三段式全链路透传，头名 `X-Dye-Tag`，取值 `MOCK` / `SANDBOX`，缺省 = `MOCK`。**

| 环节 | 新增 | 对齐 traceId |
|---|---|---|
| 枚举 | `dye/DyeMode`（含大小写不敏感的严格 `parse`） | — |
| 上下文 | `dye/DyeContext`（ThreadLocal + `isSandbox` / `set` / `clear` / `runWith(mode, Runnable)`） | `TraceContext` |
| 入站 | `dye/DyeFilter`（`OncePerRequestFilter`，**order = -190**，MDC key `dyeMode`，响应头回写，finally 清理） | `TraceIdFilter`（-200） |
| 出站 | `dye/DyeRequestInterceptor`（`feign.RequestInterceptor`；**非空才写**，不把缺省语义硬编码进协议） | `TraceIdRequestInterceptor` |
| 装配 | `CommonCoreAutoConfiguration` 加 Filter 注册；`FeignTraceAutoConfiguration` 加 Interceptor | 同 |

- **过滤链定序**：`TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)`。
- **`AutoConfiguration.imports` 无需改动**（两个自动配置类已注册，新增的只是其中的 Bean）。
- **缺省语义 = `MOCK`**：不染色绝不误连真实渠道（安全默认）。
- **`order-service` 与代理零改动**：靠 G7（common-core 全栈覆盖）+ G8（代理逐头透传）。

**R2. 染色值非法 → 400 fail fast，绝不静默回落。**

依据 G + [ADR-0049](0048-demo-showcase-decisions.md) 第 2 条纪律。静默回落的后果是「**选了沙箱却走了 mock**」——
这是最难查的一类假绿（演示看起来成功，实际没调过真实渠道）。同理，**染色 `SANDBOX` 而
`sandbox.enabled=false` 也 MUST 明确失败**，不静默改走 mock。

**R3. 染色 MUST NOT 参与选路。**

染色只决定「**同一 `channelCode` 下用哪种协议实现**」。`ChannelRouter` 的输入仍是 `RouteContext`，
选路规则（ADR-0073）逐字不变；`channelCode` 仍为 String、不改枚举、注册表仍一 code 一实例（G14）。
反向三条路径同样 MUST NOT 因染色而改换渠道（继承 spec 028 INV-6：退款换渠道＝钱退错地方）。

**R4. 渠道模态落进 `payment_attempts`，反向路径据它还原。**（⚠️ 载体已按 **H2** 修订：见「修订记录」）

- **列定义（2026-09-19 修订）**：~~`channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'`~~ →
  **`extra_json TEXT NULL`**（通用 JSON 列，**TEXT 存 JSON**），模态以键 **`channelMode`** 承载；取值 `MOCK` / `SANDBOX`。
- **写入**：`ChannelAttemptRecorder` 创建 attempt 时读 `DyeContext`（空 → `MOCK`）写入 `channelMode` 键，**方法签名不变**（调用点零改动）；
  **新写入行 MUST NOT 缺失该键**（spec 030 FR-303）；幂等重复命中已存在支付单时**返回库内值，不被当前请求覆盖**。
- **schema 三处齐备**（G17：建表语句不会给存量库补列）：① `03-payment-schema.sql` 建表语句补列；
  ② 新增增量迁移 `030-payment-attempt-extra-json.sql`（~~`030-payment-attempt-channel-mode.sql`~~）；③ 测试 H2 schema 同步。
- **反向还原**：`PaymentRefundService` / `ChannelQueryService`（覆盖 `TimeoutScanScheduler`）取到 PAYMENT attempt 后
  用 `DyeContext.runWith(attempt.getChannelMode(), () -> channel.xxx(req))` 包裹渠道调用。
  `getChannelMode()` 是领域对象的**只读派生访问器**（由 `extra` 解析），也是读取模态的**唯一入口**。
- **为什么必须落库**：G10——调度器不经入站 Filter，ThreadLocal 为空；不落库则反向路径**只能固定走 mock**，
  沙箱单的退款是假的（比不做更危险）。
- **为什么不用「解析 `channel_reference` 前缀」**：spec 028 已明确「渠道归属读列，**禁止解析引用字符串**」；
  且 G19 下沙箱 `charge` 时渠道引用为 `null`，前缀根本不存在。
- **存量行**：`extra_json` 为 `NULL` ⇒ 读为 `MOCK`，**不回填、不修正**（历史事实）。
  ⚠️ 因载体改为**可空 JSON**，缺失 / 损坏成为可能，故必须配 **读取侧 fail-safe**（`NULL` / 非法 JSON / 缺键 / 值非法 ⇒ 一律 `MOCK`，spec 030 FR-304）。

**R5. 单 Adapter 双模态：不新建 Sandbox Adapter；`ALIPAY` 升级为双模态特例，`MOCK` 分支必须 `super` 委托。**

```
AlipayChannelAdapter.charge(req):
    DyeContext.isSandbox() ? sandboxCharge(req) : super.charge(req)
```

- `supportsRealMode()` 返回 `true`（默认 `false`）；`supportedScenes()` 按真实能力收窄。
- **与 ADR-0072 决策 4 的关系（逐字对齐）**：该决策要求「子类只声明身份与差异」「基类 4 件横切行为不得覆写」。
  本 ADR **只允许覆写「协议实现的分发」**，且 **`MOCK` 分支必须 `super` 委托**——因此
  ① 金额尾数故障注入、② 退款受理 + 异步推送、③ 每实例 `runId`、④ 场景严格枚举解析
  这 4 件横切行为的**口径 100% 不变**；ADR-0072 该条款的**实质约束（横切不得分叉）未被破坏**，被修订的只是措辞。
- **不新建 Sandbox Adapter 的三重理由**：① 负责人明确（G18）；② 渠道码 `ALIPAY` 已被占用（G14），
  新 Adapter 要么改渠道码（波及演示脚本 / 前端 / 测试），要么让注册表一 code 挂两实现（破坏 ADR-0073 的注册表语义）；
  ③ 两份 250 行 mock 逻辑必然分叉（ADR-0072 已论证过这个风险）。

**R6. 引入 `com.alipay.sdk:alipay-sdk-java`，但把 SDK 收口在 `AlipayGateway` 端口之后。**

- **引入理由**：RSA2 签名 / 验签 / 参数排序自研极易出错，出错的表现是「支付成功但平台判失败」这类**资金级事故**；
  官方 SDK 由渠道方维护，签名与验签语义不会写错。
- **风险显式接受**（G15）：34.3 MB jar + 4 个传递依赖（`fastjson` / `okhttp` / `bcprov` / `dom4j`）+ **≥9 个已知 CVE**。
  这是本 ADR **唯一**的「主动引入复杂度」决策，代价与理由一并登记，供将来复核。
- **收口方式**：端口 `application/channel/AlipayGateway`（`pagePay` / `query` / `refund` / `verifyNotify`）；
  实现 `infra/channel/alipay/AlipaySdkGateway`。**`application/**` MUST NOT 依赖 `com.alipay.sdk`**（ArchUnit 断言）。
  将来若决定换纯 JDK 实现，**只替换一个类，核心领域零扩散**。
- **版本管理**：在**根 pom `dependencyManagement`** 锁定；子模块 MUST NOT 写版本（工程规范 §9）。

**R7. 支付宝通知走独立的 RSA2 入站端点，不复用 HMAC 过滤器。**

- 端点：`POST /internal/channels/alipay/notify`（`form-urlencoded`）。
  该路径**天然不在** `ChannelCallbackSignatureFilter` 的 urlPatterns（`/internal/payments/*`、`/internal/refunds/*`）内。
- **不含险复用**：G12 的过滤器预设 **HMAC-SHA256 + JSON body**；支付宝是 **RSA2 + 表单**——
  与 G13 的算法与报文形态都不同，硬塞进去等于把两套验签语义混在一个类里。
- 处理顺序：剔除 `sign` / `sign_type` → 参数名排序拼串 → `AlipayGateway.verifyNotify(...)` →
  校验 `app_id` / `seller_id` / `out_trade_no` / `total_amount` → `trade_status` 映射 →
  复用现有 `PaymentCallbackService.handleCallback`（**不新建收敛链路**，终态吸收/乱序保护/幂等全部沿用）。
- 映射：`TRADE_SUCCESS` / `TRADE_FINISHED` → `success(trade_no)`；`TRADE_CLOSED` → `businessFailure`；
  `WAIT_BUYER_PAY` → `businessUnknown`（**不推进**）。
- **必须返回纯文本 `success`**（G20）：不带引号、不带换行、不 JSON 包装；处理异常则返回非 `success` 触发渠道重试，
  重复通知由终态吸收兜底。本期**同步处理**（现有回调是纯 DB 写，够快）。
- **ADR-0052 的裁决**（原文留白「待用户后续裁决」）：**真实渠道走各自的专用端点与算法**（支付宝 RSA2），
  **HMAC 占位（ADR-0025）保留给本地 mock 路径，本 ADR 不改其口径**。

**R8. 凭证 = 「渠道已受理待付款」的信号，payment 停 `PROCESSING`。**

- `ChannelResult.credential != null` ⇒ 渠道只是**受理**、买家尚未付款 ⇒ MUST **不调** `applyAndPersist`：
  不记账、不通知 order，payment 停 `PROCESSING`（复用既有状态，**不新增 `AWAITING_PAYMENT`**）。
- `payUrl` 优先取 `credential.payload()`；`defer` 公式收窄为
  `deferChannel = mockCashier.isEnabled() && !DyeContext.isSandbox()`——**沙箱不 defer**，
  必须真调 `charge` 才拿得到凭证（这正是修复 G4 全局开关的点）。

**R9. `enabled` 默认 `false`：既是装配门控，也是 kill switch。**

- `payment.channel.adapters.alipay.sandbox.enabled` 默认 `false` → 无密钥时服务照常启动、CI 可跑；
- `enabled=true` 时**启动期强校验**必需项（`app-id` / 私钥 / 支付宝公钥 / `notify-url`）非空，缺失 → 启动失败并列出缺失项；
- 密钥全部 **env 注入**（`PAYMENT_ALIPAY_*`），禁硬编码 / 禁入库 / 禁明文日志（ADR-0026）;
- 沙箱 HTTP 超时独立配置（默认 **10000ms**），与全局 `payment.channel.http-timeout-ms`(1500ms) 解耦，
  且 MUST **小于** `payment.reliability.timeout`(30s)——否则出现「还在等支付宝、支付已被判 UNKNOWN」。

**R10. 沙箱不进 CI。**

沙箱不可复现、需密钥与公网可达的 `notify_url`、且不产生真实资金。CI 只跑**离线单测**
（mock `AlipayGateway` + **固定签名向量**钉死签名/验签/参数排序）；真机验证为**手工 live**，
且若因环境不可用而未做，**必须在 ADR 与 acceptance 中显式记录未验证范围，不得默认通过**。

**R11. 模态错误处理沿用 ADR-0012 的双响应码，不新造错误分类。**

沙箱适配器 MUST 把超时 / 断连 / 不完整响应映射为 `ChannelResult.Status.UNKNOWN`（`timeout` / `transportFailure`），
业务拒绝映射为 `businessFailure`，**绝不臆断成败**。

## 约束（实现期 MUST 遵守）

- **K1**：染色入站读（`DyeFilter`）与出站写（`DyeRequestInterceptor`）MUST **同一批落地**。
  依据 G11：只做一半会重演「全线 403」。**这是本 ADR 最容易被漏做、后果最严重的一条。**
- **K2**：`ChannelRouter` 实现 MUST NOT 引用 `DyeContext`（R3）。
- **K3**：反向路径 MUST 用 `payment_attempts` 落库的模态（**H2 修订后 = `extra_json.channelMode`，经 `attempt.getChannelMode()` 读取**）还原，MUST NOT 依赖 ThreadLocal、MUST NOT 解析渠道引用字符串。
- **K4**：`AlipayChannelAdapter` 的 `MOCK` 分支 MUST `super` 委托，MUST NOT 复制或改写基类 4 件横切行为。
- **K5**：`application/**` MUST NOT 依赖 `com.alipay.sdk`；SDK 相关类型 MUST NOT 出现在任何端口签名中。
- **K6**：`payment_attempts` 的模态列（**H2 修订后 = `extra_json` 的 `channelMode` 键**）的 schema 变更 MUST 三处齐备（建表语句 + 增量迁移 + 测试 schema）。
- **K6b**（**2026-09-19 新增，随 H2 载体修订**）：`extra_json` 写入侧 MUST 强制含 `channelMode` 键；读取侧 MUST fail-safe（`NULL` / 非法 JSON / 缺键 / 值非法 ⇒ 一律 `MOCK`，**MUST NOT** 误判为 `SANDBOX`）。
- **K7**：`notify` 端点 MUST 返回**恰好**纯文本 `success`；MUST NOT 打印完整通知报文；MUST NOT 在验签失败时触达收敛服务。
- **K8**：密钥与凭证 MUST NOT 入库、MUST NOT 进 git、MUST NOT 进明文日志。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| **新建 `AlipaySandboxChannelAdapter`** | 负责人明确不做（G18）；且渠道码 `ALIPAY` 已被占用（G14），要么改码（波及演示脚本/前端/测试）要么破坏注册表一 code 一实例的语义 |
| **染色只在 payment-service 内部** | demo 页的染色头到不了 payment（order-service 不读不传），演示链路走不通（G8/G9 说明头只在 order 侧被消费） |
| **染色只改 order + payment 两个服务** | 重复两套 Filter + Interceptor；且将来 refund 链路 / 其他服务拿不到染色 |
| **用配置开关（而非请求头）区分 mock / 沙箱** | 需要重启或引入配置中心（G1：本项目无配置中心），且**无法按单选择环境**——演示价值归零 |
| **反向路径固定走 mock（不落库）** | 沙箱单退款是假的：钱从沙箱单退到 mock 渠道，**比不做更危险** |
| **反向路径靠解析 `channel_reference` 前缀判断模态** | spec 028 已明确「渠道归属读列，禁止解析引用字符串」；且 G19 下沙箱 `charge` 时引用为 `null`，前缀不存在 |
| **复用 `ChannelCallbackSignatureFilter` 吃支付宝通知** | G12 vs G13：HMAC+JSON 与 RSA2+表单两套语义混在一个类里，且 urlPatterns 也不覆盖新路径 |
| **纯 JDK 自研支付宝协议**（`HttpClient` + `SHA256withRSA`） | 零依赖，但把 150~200 行密码学相关代码放进**取款路径**，出错即资金级事故；风险收益不划算。**折中**：SDK 收口在 `AlipayGateway` 端口后，将来换实现零扩散 |
| **新增 `AWAITING_PAYMENT` 支付状态** | 要改状态机 + 状态枚举 + DB 取值 + 受影响的测试与断言，改动面明显变大；复用 `PROCESSING` 与既有 mock defer 路径**同构**，语义已足够 |
| **引入 Nacos 配置中心做动态开关** | 违背「不增组件」；且染色是**按请求维度**的语义，配置中心只能做全局开关 |
| **给 `CreatePaymentResponse` 加 `credentialKind`** | 改 `common-dto` 破坏 order-service 的透明透传，属跨服务 API 变更；本轮以只传 `payUrl` 收口（缺口见 ADR-0075 影响 / spec 030 L1） |

## 影响

- **正影响**：
  - 「同一渠道码下两种协议实现」有了**统一的开关语义**，且该语义可被**单次请求**指定——demo 无需重启切环境；
  - 模态落库顺带把「这次交互到底走的哪套协议」变成**可查询的事实**，排障与对账可按列过滤；
  - 沙箱接入**没有新增渠道码、没有新增微服务、没有改状态机、order-service 零改动**；
  - `AlipayGateway` 端口让 SDK 成为**可替换件**（将来换自研或换版本不影响核心领域）。
- **代价 / 风险（显式接受）**：
  - **依赖体积与攻击面**：34.3 MB jar + `fastjson` / `okhttp` / `bcprov` / `dom4j`，**≥9 个已知 CVE**（G15）。
    缓解：仅 `infra` 依赖、端口收口、不参与任何对外解析（`fastjson` 只被 SDK 内部用于自身报文）。
    **若将来该项目要走向严肃生产，此条 MUST 复核**（记入 `docs/operations/code-debt-backlog.md`）；
  - **跨 9 个服务的行为面扩大**：染色 Filter 会进入所有服务的过滤链。缓解：缺省语义为 `MOCK`（不改既有行为）、
    非法值 fail fast、`finally` 清理 ThreadLocal 与 MDC；
  - **DB 结构变更**：新增列 + 迁移脚本（已获批，非破坏性）；
  - **反向路径依赖落库值**：若 attempt 行缺模态（极端：手工改库），会退回 `MOCK`，属可接受的保守失效。
    ⚠️ **H2 修订后**该风险面**变大**（`extra_json` 为可空 TEXT，可能缺失 / 非法 JSON / 缺键 / 值非法），
    故由 spec 030 补足**写入侧强制**与**读取侧 fail-safe** 双保险（见「修订记录」）。
- **明确不做**：不改支付状态机；不改渠道路由规则；不改退款语义；不做流量灰度分流（染色只用于协议分流）；
  不做渠道真实健康探测；不新增渠道码；不改 `mock-channel-web` 的代理与收银台页；不回填存量 attempt 行。

## 落地

- 实现计划：[spec 030 的 plan.md](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/plan.md) **批次 4**（染色骨架）/ **批次 5**（模态落库）/ **批次 6**（反向自足）/
  **批次 7**（沙箱适配器）/ **批次 8**（回调三段式）/ **批次 9**（demo 开关）；
- 接口与配置落点：`docs/architecture/systems/payment-service.md` §3.11 / §3.12 / §7；
- 运维落点：`docs/operations/runbook.md`（沙箱密钥、染色开关、`notify_url` 公网可达与内网穿透）。
