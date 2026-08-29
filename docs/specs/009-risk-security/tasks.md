# Tasks: 009-risk-security

**Current Progress（2026-08-29）**：实现完成，`mvn -o verify -fae` 全量 13 模块 BUILD SUCCESS。ADR-0024~0028 写入 `docs/adr/0009-risk-security-decisions.md`（均 Proposed，待负责人确认）。

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
- [ ] T013（ADR-0024 遗留）补「出站内部服务令牌」Feign 拦截器，使 `internal-auth-enabled=true` 可安全开启。
