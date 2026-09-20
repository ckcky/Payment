# Tasks: 030 统一渠道契约 + Mock/沙箱双模态 + 回调基础闭环

> **状态**：**已全部完成（142/142）并已合入 master**（`feature/030-channel-contract-sandbox-callback` 合并点 `3a39a7a`；
> 演示侧 `feature/030-demo-sandbox-ui` 合并点 `e348090`）。验收记录见 [acceptance.md](acceptance.md)（2026-09-20 收口）。
> **前置门**：门 1 / 门 2 **已通过**；门 3 **部分通过**（H4 ✅ / H17 ⏳）；门 0 **已完成**（Phase 0）。
> **实现顺序约束**：Phase 0（文档）→ Phase 1/2（**两个资金缺陷，可独立先做**）→ Phase 3（契约）→ Phase 4（染色骨架，**必须整体落地**）→ Phase 5（落库）→ Phase 6（反向自足）→ Phase 7（沙箱）→ Phase 8（回调）→ Phase 9（demo）→ Phase 10（测试门禁）→ Phase 11（L0 回写）。
> **编号引用**：`[FR-nnn]` 见 [spec.md](spec.md)；`[INV-n]` 见 spec §7.8 / §11.2；`[SC-*]` 见 [acceptance.md](acceptance.md)。
> **路径约定**：`payment-service/` / `order-service/` / `common/common-core/` 为模块根；`.../` 表示沿用上一行已给出的包路径前缀。
> **收口补记（2026-09-20）**：T11 的并发半部**已真库实测交付**（`ledger-service` 的 `LedgerPostingConcurrencyTest`，Testcontainers-MySQL，
> 3 tests / 0 skip / 0 fail）；另修 2 处「声明 ≠ 实况」——凭证形态（`FORM_HTML`）与 INV-7 ArchUnit 包名空转，
> 见 [spec §2.5](spec.md#25-实现期实况修正2026-09-20两处声明--实况)。

---

## Phase 0 —— 门 0 文档收口（**docs-only，可直推 master**）

- [x] **T1** 改 `docs/architecture/systems/payment-service.md`：修 `:52` 悬空锚点 `#311-渠道内部契约spec-030--adr-0075`；消除 `:281`/`:302` **两个重复的 `### 3.10`**（合并为一节）[SC-A-16]
- [x] **T2** 改 `docs/architecture/systems/payment-service.md:146-150`：当前把 `channel_mode` 列与 `deployment/schema/030-payment-attempt-channel-mode.sql` 写成「已实现」——**该列与文件均不存在**。**MUST 按 H2 新载体改写**为 `extra_json` 的 `channelMode` 键 + `030-payment-attempt-extra-json.sql`，**不得只删旧列名** [FR-150][FR-300]
- [x] **T3** 改 `docs/architecture/technical-solution.md`：统一 `Payment:PaymentAttempt` 基数表述（与 `systems/payment-service.md` 一致）[C-02/C-03]
- [x] **T4** 改 `docs/architecture/technical-solution.md`：写入「**渠道事实 / 平台事实可合法不一致**」口径（终态吸收的解释）[§6.1]
- [x] **T5** ~~给旧 030 余件加 `Superseded by 030` 横幅~~ → **改为「删除旧 030 四件套」**，**已于 2026-09-19 完成**（负责人裁决：整目录删除，见 [plan.md §5](plan.md)）。理由：旧 030 的 T31/T32 是**旧 `channel_mode` 专用列**口径，与 H2 裁决冲突，保留即误用风险。**复核口径**：全仓 `030-channel-contract-dye-alipay-sandbox` **0 残留引用**
- [x] **T6** 复核 `docs/adr/README.md`（索引表 + 编号速查表 `0001–0076` + `下一可用编号：ADR-0077`）与 `docs/adr/traceability.md` 的 0075/0076 登记；**全部相对链接可达、0 断链** [SC-A-16]

## Phase 1 —— B1 账本幂等键口径统一（**payment-service，🔴**）

- [x] **T7** 改 `payment-service/src/main/java/com/payment/payment/application/PaymentApplicationService.java:222`：由传 `payment.getIdempotencyKey()` 改为只传 **`paymentNo`** [FR-220]
- [x] **T8** 改 `payment-service/src/main/java/com/payment/payment/application/PaymentResultProcessor.java:188`：由传 `"PAYMENT:" + paymentNo` 改为只传 **`paymentNo`**（去掉手工前缀）[FR-220]
- [x] **T9** 确认 `payment-service/src/main/java/com/payment/payment/infra/client/FeignLedgerPostingGateway.java:42` **独占**前缀拼接且**未被修改**——**前缀只留一处** [FR-220][FR-222]
- [x] **T10** 加强 `payment-service/src/test/java/com/payment/payment/application/PaymentCaptureLedgerPostingTest.java` 与 `PaymentApplicationServiceTest.java`：断言两条路径产生**同一**键 `PAYMENT:{paymentNo}` [FR-221][SC-B1-01][SC-B1-02]
- [x] **T11** 补测试：同一支付单**先同步成功再收到回调** ⇒ 账本分录数 **= 1**（不是 2）✅ **已交付**（`PaymentCaptureLedgerPostingTest#syncSuccessThenCallbackProducesExactlyOnePosting` + `#bothSuccessPathsProduceTheSamePostingKey`）；并发两条路径 ⇒ 唯一约束吸收，分录数 **= 1** ✅ **已交付**（`LedgerPostingConcurrencyTest`，Testcontainers-MySQL **真库实测**：8 线程并发同键 INSERT ⇒ 恰好 1 个赢家、7 个被 `uk_postings_idempotency_key` 吸收、落库 1 条；DDL 直接读 `09-ledger-schema.sql` 并断言约束存在；无 Docker 守护进程时整体 **skip 不 fail**）[FR-223][SC-B1-03][SC-B1-04]

## Phase 2 —— B7 在途守卫区分「重放 / 重试」（**order-service，🟠**）

- [x] **T12** 改 `order-service/src/main/java/com/payment/order/application/TransactionApplicationService.java:189-198`：在途守卫判据改为「**是否已成功推进过**」——`paymentRefundNo != null` 或状态 `PROCESSING` ⇒ **回放**（不调渠道）；状态 `REQUESTED` **且** `paymentRefundNo == null` ⇒ **重放渠道调用** [FR-230]
- [x] **T13** 确认改动 **未破坏**「先落库后调用」的幂等前提（**MUST NOT** 改为「先调渠道后落库」）[FR-231]
- [x] **T14** ⚠️ **修正固化缺陷的断言**：`order-service/src/test/java/com/payment/order/scenario/TransactionRefundTest.java:104-114` 的 `hasSize(1)` ⇒ **修正为 2**（渠道失败后重试 ⇒ 请求次数 = 2）；PR 描述 **MUST** 给出缺陷证据链（`design-review §11 C-18`），证明是**修正错误预期**而非迎合实现 [FR-235][SC-B7-01]
- [x] **T15** 补测试：TXRF 已 `PROCESSING`（渠道已受理）⇒ 重试时渠道请求次数 **= 1**（正确回放，不重复调）[FR-232][SC-B7-02]
- [x] **T16** 补测试：并发两次同参 surplus 退款 ⇒ 最终**只产生一个** TXRF + 一个 PMRF（三层防线）[SC-B7-03]
- [x] **T17** 补 `RefundPolicy` 边界单测（**`>` vs `>=`** 比较符；spec §15.3 标注该结论此前**未验证**）[FR-233]
- [x] **T18** 补可观测：TXRF 停留 `REQUESTED` 超阈值 ⇒ **指标**可发现（**告警规则属后续 Feature**，本 Feature 至少补指标）[FR-234][SC-B7-05]

## Phase 3 —— A 统一渠道契约 + 凭证透传（**payment-service，零破坏**）

- [x] **T19** 新增 `payment-service/src/main/java/com/payment/payment/application/channel/PaymentScene.java`：枚举 `WEB/H5/NATIVE/JSAPI/MINI_PROGRAM/APP` + 四家映射对照 Javadoc [FR-101]
- [x] **T20** 新增 `.../application/channel/Goods.java`：`(String title, String description)` + `of(title)` [FR-102]
- [x] **T21** 新增 `.../application/channel/CallbackUrls.java`：`(notifyUrl, returnUrl)` + `notifyOnly(url)`；Javadoc **显式声明**「`returnUrl` 不承载资金事实，MUST NOT 据其推进支付状态」 [FR-103]
- [x] **T22** 新增 `.../application/channel/Payer.java`：`(payerId, clientIp)` + `of(payerId)` / `byClientIp(ip)` [FR-104]
- [x] **T23** 新增 `.../application/channel/PayCredential.java`：`(Kind kind, String payload, Instant expiresAt)`、六值 `Kind`（`REDIRECT_URL`/`FORM_HTML`/`QR_CODE`/`H5_URL`/`JSAPI_PARAMS`/`CLIENT_SECRET`）、`redirectUrl(...)`、`isRedirectFamily()` [FR-111]
- [x] **T24** 改 `.../application/channel/ChargeRequest.java`：扩为 12 字段（含 `channelExtra`，`Map<String,String>` **可空**）+ **保留 5 参兼容构造器** [FR-105][FR-108]
- [x] **T25** 改 `.../application/channel/RefundRequest.java`：扩为 9 字段（含 `channelTransactionId` / `outRequestNo` / `reason` / `refundNotifyUrl`）+ **保留 5 参构造器** [FR-106]
- [x] **T26** 改 `.../application/channel/QueryStatusRequest.java`：扩为 4 字段（含 `channelTransactionId`）+ **保留 3 参构造器** [FR-107]
- [x] **T27** 改 `.../application/channel/ChannelResult.java`：加**可空** `credential`；加 `accepted(ref, reason, credential)`；既有 7 个工厂委托 `null`；`withReason(...)` **MUST 保留** `credential`；保留 5 参构造重载 [FR-112]
- [x] **T28** 改 `.../application/channel/PaymentChannel.java`：加 `default Set<PaymentScene> supportedScenes()`（默认全支持）、`default boolean supportsRealMode()`（默认 `false`）。**MUST NOT 新增 `close`、MUST NOT 新增 `callback`**（刻意的不做）[FR-109][§7.2][N15]
- [x] **T29** 改 `.../application/reliability/PaymentRetryService.java`：重试结果携带最后一次 `charge` 的 `credential` [FR-114]
- [x] **T30** 改 `.../application/PaymentApplicationService.java`：`RoutedPayment(payment, channelCode, credential)`；`credential != null` ⇒ **不调 `applyAndPersist`**（payment 停 `PROCESSING`，不记账、不通知 order）[FR-115][INV-6]
- [x] **T31** 改 `.../application/PaymentApplicationService.java`：构造 `ChargeRequest` 时填充 `scene`/`goods`/`callbackUrls`/`expireAt`；调 `charge` **之前**做场景校验（`scene != null && !supportedScenes().contains(scene)` ⇒ `400 INVALID_ARGUMENT`；`scene == null` **不校验**）[FR-110][INV-8]
- [x] **T32** 改 `.../api/PaymentController.java`：`payUrl` **优先取** `credential.payload()`，否则回落既有 mock 收银台链接 [FR-114]
- [x] **T33** 编译验证：既有 4 处 `new ChargeRequest(...)`、6 处 `PaymentChannel` 测试桩、21 个引用 `ChannelResult` 的测试文件 **零改动**编译通过 [SC-A-02]
- [x] **T34** 确认 `common/common-dto/**` 与 `order-service`（除 Phase 2 外）**零改动**；`CreatePaymentResponse` 字段集不变 [C6][SC-A-15]

## Phase 4 —— 染色骨架（**common-core，必须整体一次落地**）

- [x] **T35** 新增 `common/common-core/src/main/java/com/payment/common/core/dye/DyeMode.java`：`MOCK`/`SANDBOX` + 大小写不敏感严格 `parse`（`null`/空白 → `MOCK`；非法 → 抛异常）[FR-160]
- [x] **T36** 新增 `.../common/core/dye/DyeContext.java`：ThreadLocal + `isSandbox()` / `current()` / `set` / `clear` / `runWith(mode, Runnable)` [FR-161]
- [x] **T37** 新增 `.../common/core/dye/DyeFilter.java`：`OncePerRequestFilter` 读 `X-Dye-Tag` → 解析 → `DyeContext.set` + `MDC.put("dyeMode", ...)` → 响应头回写 → `finally` 清理；非法值 → `400` + 指标 `dye_tag_rejected_total{reason=invalid}` [FR-162]
- [x] **T38** 新增 `.../common/core/dye/DyeRequestInterceptor.java`：Feign `RequestInterceptor`，`DyeContext` **非空才写**出站头（**空则不写**，不把缺省语义硬编码进协议）[FR-163]
- [x] **T39** 改 `.../common/core/config/CommonCoreAutoConfiguration.java`：加 `dyeFilter` + `dyeFilterRegistration(order = -190)` [FR-164][FR-165]
- [x] **T40** 改 `.../common/core/config/FeignTraceAutoConfiguration.java`：加 `dyeRequestInterceptor`（`@ConditionalOnMissingBean`）[FR-164]
- [x] **T41** ⚠️ **成对性自查**：确认 T37 与 T38 在**同一次提交**内（`git show --stat <commit>` 中 `DyeFilter.java` 与 `DyeRequestInterceptor.java` **同时出现**）[INV-5][SC-A-10]
- [x] **T42** 确认 `META-INF/spring/...AutoConfiguration.imports` **未被修改**（新增的是已有自动配置类中的 Bean）[FR-166]
- [x] **T43** 确认过滤链定序为 `TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)` [FR-165]
- [x] **T44** 改 `payment-service/.../api/PaymentController.java`：`deferChannel = mockCashier.isEnabled() && !DyeContext.isSandbox()`（**染色唯一消费点**；沙箱**不延迟**，必须真调 `charge` 才拿得到凭证）[FR-167]
- [x] **T45** 确认 `ChannelRouter` / `ConfiguredChannelRouter` **未读取** `DyeContext`（染色不参与选路）[FR-120][INV-3]

## Phase 5 —— 模态落库（**schema + domain + recorder**）

- [x] **T46** 改 `deployment/schema/03-payment-schema.sql`：`payment_attempts` 建表语句补 **`extra_json TEXT NULL`**（**TEXT 存 JSON**，沿用 `payload_json` 先例；**MUST NOT** 用 MySQL 原生 `JSON` 类型）[FR-301①][FR-300]
- [x] **T47** 新增 `deployment/schema/030-payment-attempt-extra-json.sql`：`ALTER TABLE payment_attempts ADD COLUMN extra_json TEXT NULL COMMENT '...'`（存量库迁移）；**MUST 可重复执行且幂等**（本 Feature 不引入 Flyway）[FR-301②][FR-308]
- [x] **T48** 改 `payment-service/src/test/resources/schema.sql`：H2 测试 schema 同步（**无 `ENGINE` 子句**，与既有风格一致）[FR-301③]
- [x] **T49** 改 `payment-service/src/main/java/com/payment/payment/domain/PaymentAttempt.java`：新增 `extra`（`Map<String,String>`，**可空**）+ **只读派生访问器 `getChannelMode()`**（由 `extra` 解析，缺失/非法一律 `MOCK`——**反向路径读取模态的唯一入口**）；全部工厂方法 / `rehydrate` 同步 [FR-302][FR-304]
- [x] **T50** JSON 编解码落在 `payment-service/src/main/java/com/payment/payment/infra/persistence/`；**领域对象 MUST NOT 依赖 Jackson** [FR-302]
- [x] **T51** 改 `.../infra/persistence/ChannelAttemptRecorderImpl.java`：创建 attempt 时读 `DyeContext`（空 → `MOCK`）**写入 `channelMode` 键**；**接口方法签名不变**（调用点零改动）[FR-151][FR-303]
- [x] **T52** 改 `payment-service/src/test/java/com/payment/payment/infra/InMemoryPaymentAttemptRepository.java`：同步 `extra` 字段 [FR-302]
- [x] **T53** ⚠️ **写入侧强制验证**：断言「新建 attempt 行解析后 `channelMode` **存在**且取值合法」——**新写入行 MUST NOT 缺失该键** [FR-303]
- [x] **T54** ⚠️ **读取侧 fail-safe 验证**：`NULL` / 非法 JSON / 缺 `channelMode` 键 / 值不在 `{MOCK,SANDBOX}` **四类坏数据** ⇒ **一律 `MOCK`**；**MUST NOT** 抛异常中断反向路径；**MUST NOT** 误判为 `SANDBOX` [FR-304][SC-A-11]
- [x] **T55** 幂等重复路径：命中已存在支付单时返回**库内**值，**MUST NOT** 用当前请求染色值覆盖 [FR-152][FR-306]
- [x] **T56** 存量行：`extra_json` 为 `NULL` ⇒ 读为 `MOCK`，**不回填、不修正** [FR-155][FR-307]
- [x] **T57** 确认 `extra_json` **未被用于渠道归属判定**（归属恒读 `payment_attempts.channel_code` **列**）[FR-305]
- [x] **T58** 确认**未**为 `extra_json` 建索引、**未**做按模态的 SQL 统计 [FR-309]
- [x] **T59** 双路径验证：**全新库**（reset 重放）与**存量库 + 迁移脚本**两条路径均可用 [SC-A-11]

## Phase 6 —— 反向路径自足性（**查询 / 退款 / 超时扫描**）

- [x] **T60** 改 `payment-service/.../application/reliability/ChannelQueryService.java:95-96`：查询请求传 **`attempt.getChannelReference()`**（修 C-12 / S21）[FR-270]
- [x] **T61** 改 `.../application/reliability/ChannelQueryService.java`：渠道调用包裹在 `DyeContext.runWith(attempt.getChannelMode(), ...)` 内 [FR-271][FR-153]
- [x] **T62** 改 `.../application/reliability/ChannelQueryService.java:116-132`：`resolveRecordedChannel` 加**确定性排序**（修 S22 的无 `ORDER BY` `findFirst`）；**同时**解析 `channel_code` **列**与 `extra_json` 的 `channelMode` 键 [FR-272][FR-154]
- [x] **T63** 确认 `resolveRecordedChannel` **不回落默认渠道**（找不到即 `INTERNAL_ERROR`，不污染别的渠道事实）[FR-273]
- [x] **T64** 改 `payment-service/.../application/PaymentRefundService.java`：退款调用包裹在模态内 [FR-153]
- [x] **T65** 确认 `refund` 的**重试也在模态包裹内**（`DyeContext.runWith(...)`），**MUST NOT** 丢失模态 [FR-263]
- [x] **T66** 确认 `payment-service/.../application/reliability/TimeoutScanner.java` **未改动**：扫描对象仍**仅** `PROCESSING`；**MUST NOT** 改为扫 `UNKNOWN`；**MUST NOT** 写 `FAILED` [FR-280][FR-282][INV-9]
- [x] **T67** 改 `payment-service/.../application/PaymentUnknownResolutionService.java`：补 `payment.unknown_age` **分桶**指标（**只加指标、不加自动动作**；分级阈值属 H5 裁决）[FR-257]
- [x] **T68** 断言反向路径**未调用** `ChannelRouter`（渠道仍取自 attempt 记录）[INV-4][FR-121][SC-A-12]

## Phase 7 —— 支付宝沙箱适配器 + Gateway + SDK

- [x] **T69** 改根 `pom.xml`：`dependencyManagement` 锁定 `com.alipay.sdk:alipay-sdk-java` 版本 [FR-135]
- [x] **T70** 改 `payment-service/pom.xml`：引入该依赖（**不写版本**）[FR-135]
- [x] **T71** 新增 `payment-service/.../application/channel/AlipayGateway.java`：端口 `pagePay(...)` / `query(...)` / `refund(...)` / `verifyNotify(...)` [FR-132][INV-7]
- [x] **T72** 新增 `payment-service/.../infra/config/AlipaySandboxProperties.java`：`@ConfigurationProperties("payment.channel.adapters.alipay.sandbox")`；`enabled` 默认 **`false`**（装配门控 + 一键 kill switch）；`enabled=true` 时**启动期强校验**必需项非空，缺失 ⇒ **启动失败并列出缺失项** [FR-133][FR-134]
- [x] **T73** 新增 `payment-service/.../infra/channel/alipay/AlipaySdkGateway.java`：SDK 收口实现（签名 / 验签 / 参数组装 / 金额换算 / 错误归一化为 `ChannelResult`）[FR-132][FR-141]
- [x] **T74** 金额换算用 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`，**禁 `double`/`float`** [FR-139][INV-1]
- [x] **T75** 改 `payment-service/.../infra/channel/AlipayChannelAdapter.java`：**双模态分发**；**`MOCK` 分支 MUST `super` 委托**（基类 4 件横切行为口径 100% 不变）[FR-130]
- [x] **T76** `AlipayChannelAdapter.supportsRealMode()` 返回 `true`；`supportedScenes()` 按真实能力**收窄**；其余三个渠道维持 `default false` [FR-131]
- [x] **T77** `charge`（沙箱分支）调 `alipay.trade.page.pay`，取 **自动提交表单 HTML**（⚠️ 实况修正：`pageExecute().getBody()` 返回的是整段 `<form>` HTML，**不是 GET 签名 URL**）→ `accepted(null, "awaiting buyer", PayCredential.formHtml(html, expireAt))`（`Kind = FORM_HTML`，见 spec §2.5 F1）[FR-136]
- [x] **T78** `queryStatus`（沙箱分支）调 `alipay.trade.query` 并映射三态：`TRADE_SUCCESS`/`TRADE_FINISHED` → `success(trade_no)`；`TRADE_CLOSED` → `businessFailure`；`WAIT_BUYER_PAY` → `businessUnknown`（**不臆断**）[FR-137]
- [x] **T79** `refund`（沙箱分支）调 `alipay.trade.refund`（**同步返回**，`code=10000` 即 `success(refund_no)`）[FR-138]
- [x] **T80** 沙箱 HTTP 超时独立配置 `http-timeout-ms`（默认 `10000`），且 **MUST < `payment.reliability.timeout`(30s)**；配置注释显式说明 [FR-140][R4]
- [x] **T81** 密钥全部 env 注入（`PAYMENT_ALIPAY_*`），确认**无硬编码 / 无入库 / 无明文日志** [FR-290][INV-2]
- [x] **T82** 染色 `SANDBOX` 而 `sandbox.enabled=false` ⇒ `400 INVALID_ARGUMENT`，**不静默回落 mock** [FR-241][INV-8]
- [x] **T83** 确认渠道私有错误码**未泄漏**到 `application/**`（全部在 Adapter 内映射）[FR-141][FR-240]
- [x] **T84** 确认 `PayCredential.payload` 与任何密钥**未入库 / 未进 git / 未进明文日志** [FR-291][FR-113][INV-2]

## Phase 8 —— 回调三段式校验 + 支付宝 notify 端点（**含 B2 / B4**）

- [x] **T85** 新增 `payment-service/.../api/AlipayNotifyController.java`：`POST /internal/channels/alipay/notify`，`consumes=application/x-www-form-urlencoded` [FR-201]
- [x] **T86** 新增 `payment-service/.../api/dto/AlipayNotifyRequest.java`：**接收原始 `Map<String,String>`**（保留全部参与签名参数）[FR-202]
- [x] **T87** ① **Signature Validation**：剔除 `sign`/`sign_type` → 参数名排序拼串 → `AlipayGateway.verifyNotify(...)`（SDK RSA2 + 支付宝公钥）；失败 → **`403`** 且**不触达** `PaymentCallbackService`（不写任何状态）[FR-202][FR-292][INV-10]
- [x] **T88** notify 业务校验：`app_id` / `seller_id` / `out_trade_no` / `total_amount` **四者一致**；任一不符 ⇒ 记录并拒绝（**不推进状态**）[FR-203]
- [x] **T89** `trade_status` 映射：`TRADE_SUCCESS`/`TRADE_FINISHED` → `success(trade_no)`；`TRADE_CLOSED` → `businessFailure`；`WAIT_BUYER_PAY` → `businessUnknown`（**不推进**）[FR-204][CB-10]
- [x] **T90** 收敛**复用**现有 `PaymentCallbackService.handleCallback`（终态吸收 + 乱序保护 + 幂等），**不新建收敛链路** [FR-205]
- [x] **T91** HTTP 响应体 **MUST 恰好为纯文本 `success`**（无引号、无换行、无 JSON 包装）；处理异常 → 返回非 `success` 触发支付宝重试 [FR-206]
- [x] **T92** 本期**同步处理**；若实测耗时逼近支付宝超时窗口，改为「先应答后异步处理」并**记入已知限制** [FR-207]
- [x] **T93** ② **Channel Reference Validation（两条路径共用）**：attempt 已记录引用且**不一致** ⇒ 拒绝；该引用**已被其他 `payment_no` 占用** ⇒ 拒绝（唯一约束只挡「同 ref 第二条」，**不挡串号**，故需应用层校验）[FR-212][CB-7][CB-8]
- [x] **T94** ③ **Amount Validation**：回调携带 `amountMinor` 且 `payment.amountMinor` 已知 ⇒ 不等则**拒绝推进**（保留原状态 + 指标 + 审计）；`amountMinor == null` 时**不校验**（向后兼容既有 mock 回调）[FR-210][CB-5]
- [x] **T95** ③ **Currency Validation**：回调携带币种时与 `payment.currencyCode` 不等 ⇒ 拒绝推进 [FR-211][CB-6]
- [x] **T96** 校验失败**三件套**（缺一不可）：① **不推进任何状态**；② 计指标；③ 写审计（`FINANCIAL_AUDIT`）——**静默拒绝不可接受** [FR-213][FR-293][INV-10]
- [x] **T97** **B4**：改 `payment-service/.../application/PaymentResultProcessor.java` —— `converge` 的 **UNKNOWN 分支补 `channelReference` 回填**（受理流水号不丢）[B4][SC-B4-01][SC-B4-02]
- [x] **T98** ⚠️ 确认**未放行** `UNKNOWN → ACCEPTED` 迁移（H13 未裁决前**不得**自行放行）[§6.2][H13]
- [x] **T99** 确认 `payment-service/.../web/ChannelCallbackSignatureFilter.java` **未改动**（`verifySignature` 恒 `true` 的 HMAC 占位保持，ADR-0025）[FR-292 尾注]
- [x] **T100** 确认 `payment-service/src/test/java/com/payment/payment/web/ChannelCallbackSecurityTest.java` **零改动**（占位断言保持）[T-R-03]
- [x] **T101** 确认 notify 路径**不在** `ChannelCallbackSignatureFilter` 的 urlPatterns 内，且**未复用** HMAC 验签（算法与报文形态都不同）[FR-201]
- [x] **T102** notify 日志只记 `out_trade_no` / `trade_status` / 验签结果 / 是否推进；**禁止打印完整通知报文** [FR-208][FR-294]
- [x] **T103** 确认**重复回调仍被幂等吸收**（CB-1 / CB-9）——B2 **不得**把重复回调误判为「金额不一致」而反复拒绝 [SC-B2-05]
- [x] **T104** 确认**两条路径同口径**：JSON 回调路径与 notify 路径的 ②③ 校验行为**一致**（消除「新端点严、老端点松」）[SC-B2-06][§8.2]
- [x] **T105** 确认校验链在**收敛之前**执行，且校验失败**不污染幂等语义**（拒绝即拒绝，不产生 `changed = true`）[§9.4]
- [x] **T106** 确认回调错误响应**未泄漏**内部细节（不返回堆栈、不返回期望值）[FR-245]

## Phase 9 —— demo 环境开关

- [x] **T107** 改 `deployment/mock-channel-web/src/main/resources/static/demo.html`：新增环境选择器（本地 mock / 支付宝沙箱），默认本地 mock
- [x] **T108** 解除 `placeOrder(...)` 中硬编码 `{channelCode:'MOCK'}`：mock → `MOCK`（不带染色头）；沙箱 → `ALIPAY` + `X-Dye-Tag: SANDBOX`
- [x] **T109** 同步处理 `demoOverrun(...)` 的同一处硬编码
- [x] **T110** 确认 `window.open(pr.data.payUrl)` **无需改动**；沙箱时直接打开支付宝沙箱收银台
- [x] **T111** 改 `deployment/demo/README.md`：环境开关说明 + 两条动线的预期结果
- [x] **T112** 改 `deployment/start-all.sh`：透传 `PAYMENT_ALIPAY_*` 环境变量
- [x] **T113** 确认 `DemoProxyController` 与 `cashier.html` **零改动**（代理已黑名单式逐头透传，S12）

## Phase 10 —— 测试与门禁收敛

- [x] **T114** 新增 `common-core/src/test/java/com/payment/common/core/dye/DyeModeTest`：缺省 `MOCK` / 大小写不敏感 / 非法值抛异常 [FR-160]
- [x] **T115** 新增 `.../dye/DyeFilterTest`：非法值 → `400` + 指标；正常值 → MDC 与响应头回写；`finally` 清理 [FR-162][SC-A-07]
- [x] **T116** 新增 `.../dye/DyeRequestInterceptorTest`：**非空才写**出站头 [FR-163][SC-A-10]
- [x] **T117** 新增 `payment-service/src/test/java/com/payment/payment/contract/ChannelContractCompatTest`：5 参 / 3 参兼容构造器可用；`withReason` **保留** `credential` [SC-A-02]
- [x] **T118** 新增 `.../contract/PayCredentialTest`：`isRedirectFamily()` 语义 [FR-111]
- [x] **T119** 新增 `.../infra/channel/AlipayAmountConversionTest`：固定向量 `amountMinor=1 → "0.01"`、`100000000 → "1000000.00"`；无 `double`/`float` [SC-A-03]
- [x] **T120** 新增 `.../infra/channel/AlipaySandboxChargeTest`（**mock `AlipayGateway`**）：沙箱产出 **`FORM_HTML`** 凭证（⚠️ 实况修正：原写 `REDIRECT_URL`，见 spec §2.5 F1）；**`MOCK` 分支尾号 `11` 仍 `timeout`**（零分叉）[SC-A-13][T-A-15]
- [x] **T121** 新增 `.../api/AlipayNotifyControllerTest`（`@SpringBootTest` + `MockMvc`）：验签失败 `403` 且不触达收敛服务；成功**恰好返回纯文本 `success`**；`WAIT_BUYER_PAY` **不推进** [SC-A-14]
- [x] **T122** 新增 `.../domain/PaymentAttemptExtraJsonTest`：写入侧强制含键；读取侧四类坏数据 fail-safe 一律 `MOCK`；幂等重复不覆盖；存量 `NULL` ⇒ `MOCK` [FR-303][FR-304][SC-A-11]
- [x] **T123** 新增 `.../infra/channel/DyeNotAffectingRoutingTest`：相同 `RouteContext` 在两种染色下选出**同一** `channelCode` [FR-120][INV-3][SC-A-08]
- [x] **T124** 新增 `.../application/PaymentCallbackValidationTest`：金额 / 币种 / 引用归属三类拒绝各自断言「不推进 + 指标 + 审计」 [SC-B2-01~04]
- [x] **T125** 新增 `.../application/PaymentCallbackPathParityTest`：JSON 路径与 notify 路径校验行为**一致** [SC-B2-06]
- [x] **T126** 新增 `.../integration/AlipaySandboxNotifyScenarioTest`：沙箱下单 → 凭证 → notify → 收敛 `SUCCEEDED`（全程离线，mock `AlipayGateway` + 固定签名向量）[SC-A-05][SC-A-13]
- [x] **T127** 新增 `deployment/architecture-tests/src/test/java/com/payment/arch/ServiceBoundaryTest` 断言：`application/**` **MUST NOT** 依赖 SDK Java 包 `com.alipay.api`（含阳性对照）；`ChannelRouter` **不读** `DyeContext` [INV-7][FR-122][INV-3][SC-A-09]
- [x] **T128** 全量门禁：`./mvnw -B clean verify -fae` **全绿**（含 `architecture-tests`）[SC-A-01]
- [x] **T129** 零回归核验：不染色路径下既有支付 / 退款 / 可靠性 / 集成 / E2E 测试 **零改动**通过（`git diff --stat` 中**无既有测试文件被改**，Phase 1/2 的**修正型**断言除外并已在 PR 说明）[SC-A-15][T-R-01]
- [x] **T130** 凭证泄漏核查：全仓 grep 确认**无**签名 URL / 私钥 / `client_secret` 出现在日志、测试夹具或文档示例中 [INV-2][SC-A-04]
- [x] **T131** 标注载体要求：T11（B1 并发）/ T16（B7 并发）在 H2 上**可能假绿** ⇒ 标为**需 Testcontainers-MySQL**（`design-review` H16 / 附 A 033）；本 Feature 先写用例 [§15.3]
- [x] **T132** 确认 `TransactionRefundTest` 之外的既有测试**未被修改**；若确需修改，**MUST** 在 PR 给出缺陷证据链并说明是**加强**而非放宽断言 [§15.4][宪法红线]
- [x] **T133** **渠道配置语义零变化**：确认 `payment.channel.*`（`http-timeout-ms` / `mock-scenario` / `refund-async*` / `adapters.{ALIPAY,WECHAT,DOUYIN}.scenario`）与 `payment.routing.*` 的**默认值全部不变**（本 Feature 只**新增** `payment.channel.adapters.alipay.sandbox.*`）[FR-123]
- [x] **T134** **既有错误处理哲学零变化**：确认 ① 记账失败**不回滚**支付成功事实（只记指标 + ERROR 日志，ADR-0009）[FR-242]；② 订单通知失败**不回滚**支付成功事实（WARN + 指标）[FR-243]；③ 渠道配置坏值 **FAIL FAST**（Bean 创建期，附合法取值清单）[FR-244]；④ `BizException` 错误码与 HTTP 映射**沿用**既有口径（`INVALID_ARGUMENT`→400 / `NOT_FOUND`→404 / `CHANNEL_UNAVAILABLE`→409 等）[FR-246]
- [x] **T135** **UNKNOWN 语义纪律零变化**：确认 ① 渠道实现把超时 / 断连 / 不完整响应映射为 `UNKNOWN`（不臆断）[FR-250]；② `UNKNOWN` 收敛**只能**来自权威结果（回调 / 主动查询明确结果 / 人工 `resolve`）[FR-251]；③ 文档中**明确否决**「超时自动终态化」[FR-252]；④ `credential != null` ⇒ 停 `PROCESSING` 而**不是** `UNKNOWN`[FR-253]；⑤ **未**新增任何自动终态出口 [FR-254]；⑥ 主动查询仍走既有 `ChannelQueryScheduler`（15s / 上限 5 次），达上限**停止**转人工 [FR-255][FR-256]；⑦ 查询返回 `UNKNOWN` ⇒ **不推进**[FR-274]
- [x] **T136** **重试与扫描语义零变化**：确认 ① 重试触发条件**仍只看 `TransportCode`**（不因契约扩展而变）[FR-260]；② 重试期间**不落库**，最终结果与重试次数**一次性写入**[FR-261]；③ 重试 **MUST NOT** 改选渠道 [FR-262]；④ `TimeoutScanner` 依赖 `payment_attempts.requested_at`（**不依赖入站请求上下文**）[FR-281]
- [x] **T137** **安全边界零变化**：确认 ① 染色头 `X-Dye-Tag` **未被**用作鉴权或权限判定（它只决定协议实现）[FR-296]；② SDK 供应链风险已**显式接受**并记入 ADR-0076（端口收口，将来可换纯 JDK 实现零扩散）[FR-295]；③ notify 端点**未**依赖「不暴露公网」作为唯一保护（**自带验签 + 语义校验**）[FR-297]

## Phase 11 —— L0 文档回写（**实现完成后**）

- [x] **T138** 改 `docs/architecture/systems/payment-service.md`：补 `### 3.11 渠道内部契约`（消除悬空锚点）；补 §2.5 / §3.2 的模态落库与 notify 端点**现状**
- [x] **T139** 改 `docs/architecture/technical-solution.md`：§2.4 / §3.5 / §4.3 / §5.2 同步
- [x] **T140** 改 `CHANGELOG.md`：置顶本 Feature 条目；如需发版按 `VERSION` → 发版流程
- [x] **T141** 跑 `docs/guides/engineering-standards.md` §11 **五条 grep 自检**（ADR 引用一致性 / 编号唯一性 / 版本集中 / 产物污染 / 链接与锚点可达）[SC-A-16]
- [x] **T142** 确认**未**把 `stage-design` 的 `【目标】` 项写入 L0 文档（避免虚假合规）[N21]

---

## 依赖与并行说明

### 阶段依赖

| 阶段 | 依赖 | 可独立合并 |
|---|---|---|
| Phase 0 门 0 | 无 | ✅ 独立（docs-only，可直推 master） |
| Phase 1 B1 | 无 | ✅ 独立 |
| Phase 2 B7 | 无 | ✅ 独立 |
| Phase 3 契约 | 无 | ✅ 独立（零破坏） |
| Phase 4 染色 | 无（但 T44 需 Phase 3 的 `PaymentController` 已就位） | ✅ 独立 |
| Phase 5 落库 | Phase 4（`ChannelAttemptRecorderImpl` 读 `DyeContext`） | ⚠️ 依赖 |
| Phase 6 反向 | Phase 5（`getChannelMode()`）+ Phase 3（`QueryStatusRequest.channelTransactionId`） | ⚠️ 依赖 |
| Phase 7 沙箱 | Phase 3（`ChargeRequest` / `PayCredential` / `AlipayGateway` 端口） | ⚠️ 依赖 |
| Phase 8 回调 | Phase 3 / Phase 5 / Phase 7 | ⚠️ 依赖 |
| Phase 9 demo | Phase 4（染色头）+ Phase 7（沙箱可用） | ⚠️ 依赖 |
| Phase 10 测试与零回归核验 | 随各阶段内联；T128~T137 最后执行 | ⚠️ 依赖全部 |
| Phase 11 L0 | 全部完成 | ⚠️ 依赖全部 |

### 可并行（不同文件、无依赖）

- **Phase 0 / 1 / 2 三组可完全并行**（文档 / payment-service / order-service，文件零交集）；
- Phase 3 内 T19~T23（五个新类型）互相独立，可并行；
- Phase 10 内 T114~T127 各组测试互相独立，可并行；
- **不可并行**：Phase 4 的 T37 / T38（**必须同批提交**，INV-5）。

### MVP 切分

- **增量 1（可独立交付）**：Phase 0 + 1 + 2 + 3 + 4 ⇒「契约就位 + 染色可用 + 两个资金缺陷修复」；
- **增量 2（完整 Feature）**：Phase 5~9 ⇒「沙箱真调 + 回调闭环」；
- **收尾**：Phase 10 + 11。

---

## 开工前待确认（阻塞项标注）

| # | 待确认 | 阻塞 | 推荐 |
|---|---|---|---|
| Q1 | H17：B7 是否同时加 order 侧退款补偿扫描器 | T18（FR-234 完整形态） | 本 Feature 只做方案 A + 指标，扫描器留 034 |
| Q2 | H13：`UNKNOWN → ACCEPTED` 是否放行 | T98 | 放行（语义更准，与 `backfillChannelReference` 一致） |
| Q3 | Phase 0 是否先单独 PR 合并 | SC-A-16 | **先做**（不依赖裁决） |
| Q4 | ~~旧 030 三份余件「加横幅保留」还是「移入 `docs/archive/design/`」~~ | T5 | ✅ **已裁决：整目录删除**（2026-09-19） |
| Q5 | `notify_url` 配置单值 vs 按单动态拼 | T72 | 配置单值 |
| Q6 | 沙箱 `enabled=false` 时是否装配 Gateway Bean | T72 | 不装配（`@ConditionalOnProperty`） |
| Q7 | `ChargeRequest.scene == null` 是否推导默认场景 | T31 | 不推导（零回归） |
| Q8 | B1 存量旧键 posting 处理口径 | T7~T9 文档 | 保留为历史事实，不迁移 |
