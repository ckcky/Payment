# Plan: 009-risk-security

**对应 Spec**：`spec.md`
**决策**：见 `docs/adr/0009-risk-security-decisions.md`（ADR-0024~0028，均 Proposed）

## 总体方案

在既有 `ResolveAuthorizationInterceptor`（resolve 端点 `X-Admin-Token`）基础上，补齐两道外部/跨服务安全边界：

1. **入站渠道回调 HMAC 验签**（最高危）：新增 `ChannelCallbackSignatureFilter` + `ChannelCallbackController` + `SignatureVerifier`（common-core）。验签前置到 Servlet 过滤器层——校验失败直接 `403` 且不继续过滤链，业务 Controller 与 `PaymentCallbackService` 根本不会被触达。
2. **内部服务间调用鉴权**：新增 `InternalServiceAuthInterceptor`（`X-Service-Token`），注册到全部 `/internal/**`（回调路径除外）。

并落地 `SensitiveDataMasker`（common-core）与最小风控 `RiskCheckService`（默认关闭、只观测不拦截）。

## 落点

- `common-core/security`：`SignatureVerifier`（HMAC-SHA256 + 常数时间比对 + 防重放窗口）、`SensitiveDataMasker`（`maskToken`）。
- `payment-service/web`：
  - `ChannelCallbackSignatureFilter` —— 验签过滤器（**注册为 `FilterRegistrationBean`**，见下方踩坑）。
  - `CachedBodyHttpServletRequest` —— 原始 body 可重复读包装器。
  - `InternalServiceAuthInterceptor` —— `/internal/**` 的 `X-Service-Token` 守卫。
  - `WebConfig` —— 注册过滤器与拦截器。
- `payment-service/api`：`ChannelCallbackController`、`api/dto/ChannelCallbackRequest`。
- `payment-service/application/risk`：`RiskCheckService`（挂点 `PaymentApplicationService#createPaymentIntent`）。
- `payment-service/src/main/resources/application.yml`：`payment.security.*` / `payment.risk.*`。
- 测试：`SignatureVerifierTest`、`SensitiveDataMaskerTest`、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`、`RiskCheckServiceTest`。

## 实现踩坑（已在 ADR-0025 记录，避免后续重犯）

1. **过滤器必须注册为 `FilterRegistrationBean`**：Spring Boot 的 MockMvc **只收集 `FilterRegistrationBean`**，只标 `@Component` 的 `Filter` bean 在集成测试里会被绕过 → 出现「测试全绿、生产裸奔」的假绿。
2. **Servlet url pattern 不支持中段通配**：注册 pattern 用 `/internal/payments/*`，具体路径用 `AntPathMatcher` 在过滤器内判定（起初用 `String.equals` 匹配含 `*` 的模式，恒不匹配，过滤器被整体跳过）。
3. **读原始 body 后必须换包装器**：`StreamUtils.copyToByteArray` 消费掉输入流后，下游 `@RequestBody` 会读到空，需 `CachedBodyHttpServletRequest` 兜底。

## 关键决策（最简实现，待确认）

- 鉴权 = 共享密钥 API Key（非 mTLS/OAuth2）。
- 验签 = HMAC-SHA256（非非对称）；验签串 `timestamp + "." + rawBody`。
- 密钥 = env 注入（非 Vault）。
- 风控 = 只观测不拦截的 `RiskCheckService`（非规则引擎），默认关闭。
