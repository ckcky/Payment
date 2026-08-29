# Tasks: 009-risk-security

**Current Progress（2026-08-30）**：实现完成（含 T013 出站令牌闭环），`mvn -o clean verify -fae` 全量 15 模块 BUILD SUCCESS。ADR-0024~0028 写入 `docs/adr/0009-risk-security-decisions.md`、ADR-0034~0037 写入 `docs/adr/0011-internal-token-decisions.md`（均 Proposed，待负责人确认）。

## Implementation

- [x] T001 [P] `common-core` 新增 `security/SignatureVerifier.java`（HMAC-SHA256 + 常数时间比对 + 防重放窗口）与 `security/SensitiveDataMasker.java`（`maskToken`）。
- [x] T002 `payment-service` 新增 `api/dto/ChannelCallbackRequest.java`：回调入站 DTO（status / channelReference / reason / amountMinor），`toResult()` 映射为 `ChannelResult`。
- [x] T003 `payment-service` 新增 `api/ChannelCallbackController.java`：`POST /internal/payments/{id}/channel-callback`，委托 `PaymentCallbackService.handleCallback`。
- [x] T004 `payment-service` 新增 `web/ChannelCallbackSignatureFilter.java` + `web/CachedBodyHttpServletRequest.java`：HMAC 验签 + 防重放 + 可重复读包装器；失败 → `403`/`503`，不继续过滤链。
- [x] T005 `payment-service` 新增 `web/InternalServiceAuthInterceptor.java`：`X-Service-Token` 鉴权（`/internal/**` 除回调）。
- [x] T006 扩展 `web/WebConfig.java`：以 `FilterRegistrationBean` 注册验签过滤器，并注册内部鉴权拦截器。
- [x] T007 `application.yml` 增加 `payment.security.*`（service-token / internal-auth-enabled / channel-secret / signature-replay-window-ms）与 `payment.risk.*`；复用既有 `resolve.auth-enabled` / `PAYMENT_ADMIN_TOKEN`。
- [x] T008 `payment-service` 新增 `application/risk/RiskCheckService.java`（`SINGLE_MAX_AMOUNT` / `WINDOW_LIMIT_COUNT` 两条规则，只记录不阻断）并挂到 `PaymentApplicationService#createPaymentIntent`。
- [x] T009 测试：`SignatureVerifierTest`、`SensitiveDataMaskerTest`、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`、`RiskCheckServiceTest`。

## Verification

- [x] T010 运行 `mvn -o verify -fae`：全量 13 模块 BUILD SUCCESS，0 失败 0 错误。
- [x] T011 更新 `docs/architecture/roadmap.md`：Current Status 推进 009、Feature 状态表、Next Feature 指向 Phase 10。
- [ ] T012 负责人确认 ADR-0024~0028 状态为 Accepted（代码已按最简实现，确认后无需改实现）。
- [x] T013（ADR-0024 遗留）补「出站内部服务令牌」Feign 拦截器，使 `internal-auth-enabled=true` 可安全开启。
  - [x] T013a `common-core` 新增 `client/InternalTokenRequestInterceptor.java`（仅对含 `/internal/` 段的目标附加 `X-Service-Token`；默认关闭；不覆盖已有头）。
  - [x] T013b `common-core` 新增 `config/FeignInternalTokenAutoConfiguration.java`（类级 `@ConditionalOnClass(feign.RequestInterceptor.class)`），并注册进 `AutoConfiguration.imports`。
  - [x] T013c `payment-service` 入站令牌改为「首个非空」：`payment.security.service-token` → `platform.security.internal-token`；**不用** `${a:${b:}}` 嵌套默认值（YAML 空串不算未定义，会静默失效）。
  - [x] T013d 5 个服务的 `application.yml` 新增 `platform.security.internal-token` / `outbound-token-enabled`（payment / refund / settlement / reconciliation / fulfillment）。
  - [x] T013e 鉴权失败埋点（ADR-0037）：`payment.internal_auth_rejected`（`reason=unconfigured|missing_token|token_mismatch`）+ WARN 日志。
  - [x] T013f 测试：`InternalTokenRequestInterceptorTest`(6)、`InternalTokenOutboundTest`(3)、`InternalServiceAuthInterceptorTest`(6)、`InternalServiceAuthTest$PlatformTokenFallback`(2)。
  - [x] T013g `mvn -o clean verify -fae`：全量 BUILD SUCCESS（14 个 Maven 模块 + root，共 15 个 reactor 条目）。
  - [x] T013h ADR-0034~0037 写入 `docs/adr/0011-internal-token-decisions.md`（均 Proposed，待负责人确认）；`docs/adr/README.md` 索引补齐 0009~0011。
  - [x] T013i `docs/operations/runbook.md` 补充 `PLATFORM_INTERNAL_TOKEN` 与「内部端点 403」处置口径。
- [ ] T014 负责人确认 ADR-0034~0037 状态为 Accepted（代码已按最简实现，确认后无需改实现）。
- [ ] T015（ADR-0035）决定是否把入站鉴权推广到其余 7 个暴露 `/internal/**` 的服务（建议独立 Feature 立项）。
