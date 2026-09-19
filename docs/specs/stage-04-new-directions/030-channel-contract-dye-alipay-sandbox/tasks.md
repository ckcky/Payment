# Tasks: 030 聚合支付统一渠道契约 + 全链路染色分流 + 支付宝沙箱接入

> **状态**：**未开工**（本轮只写文档）。开工前置：ADR-0075 / ADR-0076 转 ✅ Accepted。
> **实现顺序约束**：A（契约）→ C（染色骨架，**必须整体落地**）→ D（落库）→ E（反向还原）→ B（凭证）→ F（沙箱）→ G（回调）→ H（demo）→ I（测试收敛）。
> **编号引用**：`[FR-nnn]` 见 [spec.md §5](spec.md#5-功能需求fr)；`[INV-n]` 见 [spec.md §3](spec.md#3-硬性不变量不可破坏)。

## Phase A —— 统一渠道契约（`application/channel/`）

- [ ] **T1** 新增 `payment-service/src/main/java/com/payment/payment/application/channel/PaymentScene.java`：枚举 `WEB/H5/NATIVE/JSAPI/MINI_PROGRAM/APP` + 四家映射对照 Javadoc [FR-001]
- [ ] **T2** 新增 `.../application/channel/Goods.java`：`(String title, String description)` + `of(title)` [FR-002]
- [ ] **T3** 新增 `.../application/channel/CallbackUrls.java`：`(notifyUrl, returnUrl)` + `notifyOnly(url)`；Javadoc 显式声明 `returnUrl` 不承载资金事实 [FR-003]
- [ ] **T4** 新增 `.../application/channel/Payer.java`：`(payerId, clientIp)` + `of(payerId)` / `byClientIp(ip)` [FR-004]
- [ ] **T5** 新增 `.../application/channel/PayCredential.java`：`(Kind kind, String payload, Instant expiresAt)`、六值 `Kind`、`redirectUrl(...)`、`isRedirectFamily()` [FR-012]
- [ ] **T6** 改 `.../application/channel/ChargeRequest.java`：扩为 12 字段 + **保留 5 参兼容构造器** [FR-005][INV-1]
- [ ] **T7** 改 `.../application/channel/RefundRequest.java`：扩为 9 字段（含 `channelTransactionId` / `outRequestNo` / `reason` / `refundNotifyUrl`）+ **保留 5 参构造器** [FR-006]
- [ ] **T8** 改 `.../application/channel/QueryStatusRequest.java`：扩为 4 字段（含 `channelTransactionId`）+ **保留 3 参构造器** [FR-007]
- [ ] **T9** 改 `.../application/channel/ChannelResult.java`：加可空 `credential`、加 `accepted(ref, reason, credential)`、旧工厂委托 `null`、`withReason` 保留 `credential`、保留 5 参构造重载 [FR-013]
- [ ] **T10** 改 `.../application/channel/PaymentChannel.java`：加 `default Set<PaymentScene> supportedScenes()`（默认全支持）与 `default boolean supportsRealMode()`（默认 `false`）[FR-009]
- [ ] **T11** 契约对照 [payment-service.md §3.11](../../architecture/systems/payment-service.md#311-渠道内部契约spec-030--adr-0075) 逐字段复核，**确认无自行增删** [SC-002]
- [ ] **T12** 编译验证：既有 6 处 `PaymentChannel` 测试桩、4 处 `new ChargeRequest(...)`、21 个引用 `ChannelResult` 的测试文件**零改动**编译通过 [FR-068][SC-002]

## Phase B —— 凭证透传到 payUrl

- [ ] **T13** 改 `.../application/PaymentRetryService.java`：重试结果携带最后一次 `charge` 的 `credential` [FR-015]
- [ ] **T14** 改 `.../application/PaymentApplicationService.java`：`RoutedPayment` 加 `credential` 分量 [FR-015]
- [ ] **T15** 改 `.../application/PaymentApplicationService.java`：`credential != null` ⇒ **不调 `applyAndPersist`**，payment 停 `PROCESSING` [FR-016][INV-6]
- [ ] **T16** 改 `.../application/PaymentApplicationService.java`：构造 `ChargeRequest` 时填充 `scene` / `goods` / `callbackUrls` / `expireAt` [FR-005]
- [ ] **T17** 改 `.../application/PaymentApplicationService.java`：调 `charge` **之前**做场景校验（`scene != null && !supportedScenes().contains(scene)` → `BizException(INVALID_ARGUMENT)`）[FR-010][INV-8]
- [ ] **T18** 改 `.../api/PaymentController.java`：`payUrl` 优先取 `credential.payload()`，否则回落既有 mock 收银台链接 [FR-017]
- [ ] **T19** 改 `.../api/PaymentController.java`：`deferChannel = mockCashier.isEnabled() && !DyeContext.isSandbox()` [FR-057]
- [ ] **T20** 确认 `common-dto` 与 order-service **零改动**（`CreatePaymentResponse` 不变）[D6][SC-015]

## Phase C —— common-core 染色骨架（**必须整体落地**）

- [ ] **T21** 新增 `common/common-core/src/main/java/com/payment/common/core/dye/DyeMode.java`：`MOCK/SANDBOX` + 大小写不敏感严格 `parse`（`null`/空白 → `MOCK`；非法 → 抛异常）[FR-019]
- [ ] **T22** 新增 `.../common/core/dye/DyeContext.java`：ThreadLocal + `isSandbox/current/set/clear/runWith` [FR-020]
- [ ] **T23** 新增 `.../common/core/dye/DyeFilter.java`：`OncePerRequestFilter` 读 `X-Dye-Tag` → ThreadLocal + MDC(`dyeMode`) → 响应头回写 → finally 清理；非法值 400 + `dye_tag_rejected_total{reason=invalid}` [FR-021][FR-064]
- [ ] **T24** 新增 `.../common/core/dye/DyeRequestInterceptor.java`：Feign 出站写 `X-Dye-Tag`，**非空才写** [FR-022]
- [ ] **T25** 改 `.../common/core/config/CommonCoreAutoConfiguration.java`：加 `dyeFilter` + `dyeFilterRegistration(order = -190)` [FR-023]
- [ ] **T26** 改 `.../common/core/config/FeignTraceAutoConfiguration.java`：加 `dyeRequestInterceptor`（`@ConditionalOnMissingBean`）[FR-023]
- [ ] **T27** **成对性自查**：确认 T23 与 T24 在**同一次提交**内（`git show --stat` 两个文件同时出现）[INV-5][SC-010]
- [ ] **T28** 确认过滤链定序为 `TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)` [FR-024]
- [ ] **T29** 确认 `META-INF/spring/...AutoConfiguration.imports` **未被修改**（无需新增行）[FR-025]
- [ ] **T30** 确认 `ChannelRouter` / `ConfiguredChannelRouter` **未读取** `DyeContext` [FR-026][INV-3]

## Phase D —— 渠道模态落库

- [ ] **T31** 改 `deployment/schema/03-payment-schema.sql`：`payment_attempts` 建表语句补 `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'` [FR-028]
- [ ] **T32** 新增 `deployment/schema/030-payment-attempt-channel-mode.sql`：`ALTER TABLE payment_attempts ADD COLUMN ...`（存量库迁移）[FR-029]
- [ ] **T33** 改 `payment-service/src/test/resources/schema.sql`：H2 测试 schema 同步 [FR-029]
- [ ] **T34** 改 `.../domain/PaymentAttempt.java`：加 `channelMode` 字段 + 全部工厂 / `rehydrate` 同步 [FR-030]
- [ ] **T35** 改 `.../infra/persistence/ChannelAttemptRecorderImpl.java`：创建 attempt 时读 `DyeContext`（空 → `MOCK`）落库；**方法签名不变** [FR-031]
- [ ] **T36** 改 `payment-service/src/test/java/.../infra/InMemoryPaymentAttemptRepository.java`：同步字段 [FR-030]
- [ ] **T37** 幂等重复路径：命中已存在支付单时返回**库内** `channel_mode`，不被当前请求覆盖 [FR-032][SC-011]
- [ ] **T38** 双路径验证：**全新库**（reset.sh 重放）与**存量库 + 迁移脚本**两条路径均可用 [SC-011]

## Phase E —— 反向路径模态还原

- [ ] **T39** 改 `.../application/PaymentRefundService.java`：退款调用包裹在 `DyeContext.runWith(attempt.getChannelMode(), ...)` 内 [FR-033][INV-4]
- [ ] **T40** 改 `.../application/reliability/ChannelQueryService.java`：主动查询同样包裹（覆盖 `TimeoutScanScheduler` 路径）[FR-033]
- [ ] **T41** 断言反向路径**未调用** `ChannelRouter`（渠道仍取自 attempt 记录）[INV-4][SC-012]

## Phase F —— 支付宝沙箱适配器与 Gateway

- [ ] **T42** 改根 `pom.xml`：`dependencyManagement` 锁定 `com.alipay.sdk:alipay-sdk-java` 版本 [FR-040]
- [ ] **T43** 改 `payment-service/pom.xml`：引入该依赖（**不写版本**）[FR-040]
- [ ] **T44** 新增 `.../application/channel/AlipayGateway.java`：`pagePay` / `query` / `refund` / `verifyNotify` 端口 [FR-037][INV-7]
- [ ] **T45** 新增 `.../infra/config/AlipaySandboxProperties.java`：`@ConfigurationProperties("payment.channel.adapters.alipay.sandbox")` + `enabled=true` 时启动强校验必需项 [FR-038][FR-055]
- [ ] **T46** 新增 `.../infra/channel/alipay/AlipaySdkGateway.java`：SDK 收口实现（签名 / 验签 / 参数组装 / 金额换算 / 错误归一化为 `ChannelResult`）[FR-037]
- [ ] **T47** `AlipaySdkGateway` 金额换算用 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`，**禁 `double`/`float`** [FR-044][INV-1]
- [ ] **T48** 改 `.../infra/channel/AlipayChannelAdapter.java`：双模态分发；**`MOCK` 分支必须 `super` 委托** [FR-035][D8]
- [ ] **T49** `AlipayChannelAdapter.supportsRealMode()` 返回 `true`；`supportedScenes()` 按真实能力收窄 [FR-036]
- [ ] **T50** `charge`（沙箱）调 `alipay.trade.page.pay`，GET 签名 URL → `accepted(null, "awaiting buyer", PayCredential.redirectUrl(...))` [FR-041]
- [ ] **T51** `queryStatus`（沙箱）调 `alipay.trade.query` 并映射三态（`TRADE_SUCCESS`/`TRADE_FINISHED` → SUCCESS；`TRADE_CLOSED` → FAILURE；`WAIT_BUYER_PAY` → businessUnknown）[FR-042]
- [ ] **T52** `refund`（沙箱）调 `alipay.trade.refund`（同步 `code=10000` → `success`）[FR-043]
- [ ] **T53** 沙箱 HTTP 超时独立配置 `http-timeout-ms`（默认 10000），且 **< `payment.reliability.timeout`(30s)** [FR-045]
- [ ] **T54** 密钥全部 env 注入（`PAYMENT_ALIPAY_*`），确认无硬编码 / 无入库 / 无明文日志 [FR-046][INV-2]
- [ ] **T55** 染色 `SANDBOX` 而 `sandbox.enabled=false` → `400 INVALID_ARGUMENT`，**不静默回落 mock** [FR-039][INV-8]

## Phase G —— 支付宝通知入站

- [ ] **T56** 新增 `.../api/AlipayNotifyController.java`：`POST /internal/channels/alipay/notify`，`consumes=application/x-www-form-urlencoded` [FR-047]
- [ ] **T57** 新增 `.../api/dto/AlipayNotifyRequest.java`：**接收原始 `Map<String,String>`**（保留全部参与签名参数）[FR-048]
- [ ] **T58** 验签：剔除 `sign`/`sign_type` → 参数名排序拼串 → `AlipayGateway.verifyNotify(...)`；失败 → `403` 且**不触达** `PaymentCallbackService` [FR-048]
- [ ] **T59** 业务校验：`app_id` / `seller_id` / `out_trade_no` / `total_amount` 四者一致；不符则拒绝且不推进 [FR-049]
- [ ] **T60** `trade_status` 映射 → `ChannelResult` → 复用 `PaymentCallbackService.handleCallback`（**不新建收敛链路**）[FR-050][FR-051]
- [ ] **T61** 响应体**恰好为纯文本 `success`**（无引号 / 无换行 / 无 JSON 包装）；异常时返回非 `success` [FR-052]
- [ ] **T62** notify 路径确认**不在** `ChannelCallbackSignatureFilter` 的 urlPatterns 内，且**未复用** HMAC 验签 [FR-047]
- [ ] **T63** `ChannelCallbackSecurityTest` **保持零改动**（HMAC 占位留给 mock 路径）[FR-070]
- [ ] **T64** notify 日志只记 `out_trade_no` / `trade_status` / 验签结果 / 是否推进，**不打印完整报文** [FR-066]

## Phase H —— demo 环境开关

- [ ] **T65** 改 `deployment/mock-channel-web/src/main/resources/static/demo.html`：新增环境选择器（本地 mock / 支付宝沙箱），默认本地 mock [FR-058]
- [ ] **T66** 解除 `placeOrder(...)` 中硬编码 `{channelCode:'MOCK'}`：mock → `MOCK`（不带染色头）；沙箱 → `ALIPAY` + `X-Dye-Tag: SANDBOX` [FR-059]
- [ ] **T67** 同步处理 `demoOverrun(...)` 的同一处硬编码 [FR-059]
- [ ] **T68** 确认 `window.open(pr.data.payUrl)` 无需改动；沙箱时直接打开支付宝沙箱收银台 [FR-060]
- [ ] **T69** 改 `deployment/demo/README.md`：环境开关说明 + 两条动线的预期结果 [FR-061]
- [ ] **T70** 改 `deployment/start-all.sh`：透传 `PAYMENT_ALIPAY_*` 环境变量 [FR-061]
- [ ] **T71** 确认 `DemoProxyController` 与 `cashier.html` **零改动** [FR-062]
- [ ] **T72**（可选）新增 `deployment/demo/scenario-alipay-sandbox.sh`：手工 live 验证脚本，**不进 CI** [FR-061][L6]

## Phase I —— 测试与门禁收敛

- [ ] **T73** 新增 `common-core/src/test/.../dye/DyeModeTest`：缺省 `MOCK` / 大小写不敏感 / 非法值抛异常 [FR-019]
- [ ] **T74** 新增 `common-core/src/test/.../dye/DyeFilterTest`：非法值 → 400 + 指标；正常值 → MDC 与响应头回写；finally 清理 [FR-021]
- [ ] **T75** 新增 `common-core/src/test/.../dye/DyeRequestInterceptorTest`：非空才写出站头 [FR-022]
- [ ] **T76** 新增 `payment-service/src/test/.../channel/ChannelContractCompatTest`：5 参 / 3 参兼容构造器可用；`withReason` 保留 `credential` [SC-002]
- [ ] **T77** 新增 `payment-service/src/test/.../channel/PayCredentialTest`：`isRedirectFamily()` 语义 [FR-012]
- [ ] **T78** 新增 `payment-service/src/test/.../channel/AlipayAmountConversionTest`：固定向量 `1 → "0.01"`、`100000000 → "1000000.00"` [SC-003]
- [ ] **T79** 新增 `payment-service/src/test/.../channel/AlipaySandboxChargeTest`（mock `AlipayGateway`）：沙箱产出 `REDIRECT_URL`；**`MOCK` 分支尾数 11 仍 `timeout`** [US4-2]
- [ ] **T80** 新增 `payment-service/src/test/.../api/AlipayNotifyControllerTest`：验签失败 403 / 成功恰好纯文本 `success` / `WAIT_BUYER_PAY` 不推进 [SC-014]
- [ ] **T81** 新增 `payment-service/src/test/.../domain/PaymentAttemptChannelModeTest`：落库读 `DyeContext`；幂等重复不覆盖 [SC-011]
- [ ] **T82** 新增 `payment-service/src/test/.../routing/DyeNotAffectingRoutingTest`：两种染色下同一 `RouteContext` 选出同一 code [SC-008][INV-3]
- [ ] **T83** 新增 `deployment/architecture-tests/.../ServiceBoundaryTest` 断言：`application/**` MUST NOT 依赖 `com.alipay.sdk` [INV-7][SC-009]
- [ ] **T84** 全量门禁：`./mvnw -B clean verify -fae` 全绿 [SC-001]
- [ ] **T85** 零回归核验：不染色路径下既有测试**零改动**通过（对比 `git diff --stat` 中无既有测试文件）[SC-015]
- [ ] **T86** 凭证泄漏核查：全仓 grep 确认无签名 URL / 私钥 / `client_secret` 出现在日志、测试夹具或文档示例中 [INV-2][SC-004]

## Phase J —— 文档同步与收尾

- [ ] **T87** 按 [spec.md §11](spec.md#11-文档与决策影响) 逐条核对文档同步项已全部落地
- [ ] **T88** 跑 `engineering-standards.md` §11 五条 grep 自检（ADR 引用一致性 / 编号唯一性 / 版本集中 / 产物污染 / 链接可达）
- [ ] **T89** ADR-0075 / ADR-0076 状态由 🟡 Proposed 更新为 ✅ Accepted，并同步 `docs/adr/README.md` 两张表与 `traceability.md`
- [ ] **T90** `docs/CHANGELOG.md` 置顶本 Feature 条目；如需发版按 `VERSION` → `docs/releases/<tag>.md` 流程
