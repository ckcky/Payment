# Plan: 009-risk-security

**对应 Spec**：`spec.md`
**决策**：见 `docs/adr/0009-risk-security-decisions.md`（ADR-0024~0028）与 `docs/adr/0011-internal-token-decisions.md`（ADR-0034~0037）
**负责人裁决（2026-08-30）**：

| ADR | 裁决 | 实现形态 |
|-----|------|----------|
| ADR-0024 内部服务间调用鉴权 | ✅ Accepted | ⭕ **预留空函数** |
| ADR-0025 渠道回调验签 | ✅ Accepted | ⭕ **预留空函数** |
| ADR-0026 密钥 env 注入 / 明文 | ✅ Accepted | 明文 env 注入 |
| ADR-0027 敏感数据脱敏 | ⛔ Not Implemented | 类已删除 |
| ADR-0028 最小风控 | ⛔ Not Implemented | 类已删除 |
| ADR-0034~0037 出入站鉴权令牌 | ⛔ Not Implemented | 代码已清理 |

## 总体方案（按裁决降级后的最终形态）

在既有 `ResolveAuthorizationInterceptor`（resolve 端点 `X-Admin-Token`）基础上，**只保留两道安全边界的接入点骨架，校验逻辑本期留空**：

1. **入站渠道回调验签接入点**（ADR-0025）：`ChannelCallbackSignatureFilter` 保留完整的过滤器骨架——路径 Ant 匹配、原始 body 读取、`CachedBodyHttpServletRequest` 可重复读包装、拒绝分支与 403 输出——但 `verifySignature(body, timestamp, signature)` **恒返回 `true`**，回调一律放行。校验前置到 Servlet 过滤器层这一定位不变，将来接入真实渠道时业务 Controller 与 `PaymentCallbackService` 天然不会被未授权请求触达。
2. **内部服务间调用鉴权接入点**（ADR-0024）：`InternalServiceAuthInterceptor` 仍注册到 `/internal/**`（回调路径除外），但 `verifyServiceToken(request)` **恒返回 `true`**，恒定放行。它作为鉴权的**唯一归口**，将来不必把校验散落到各 Controller。

**不落地**（按裁决删除）：`SensitiveDataMasker`（ADR-0027）、`RiskCheckService`（ADR-0028）、出站 `InternalTokenRequestInterceptor` + `FeignInternalTokenAutoConfiguration`（ADR-0034）、鉴权失败埋点（ADR-0037）。

## 落点

**保留**
- `common-core/security`：`SignatureVerifier`（HMAC-SHA256 + 常数时间比对 + 防重放窗口）——纯工具类，无配置无 bean，空实现期零副作用，8 个单测覆盖。
- `payment-service/web`：
  - `ChannelCallbackSignatureFilter` —— 验签过滤器骨架（**注册为 `FilterRegistrationBean`**，见下方踩坑），`verifySignature` 空实现。
  - `CachedBodyHttpServletRequest` —— 原始 body 可重复读包装器。
  - `InternalServiceAuthInterceptor` —— `/internal/**` 鉴权接入点，`verifyServiceToken` 空实现。
  - `WebConfig` —— 注册过滤器与拦截器（无参构造，不再注入密钥）。
- `payment-service/api`：`ChannelCallbackController`、`api/dto/ChannelCallbackRequest`。
- 测试：`SignatureVerifierTest`、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`（后两者改写为「空实现放行性」断言）。

**删除**
- `common-core/security/SensitiveDataMasker`（ADR-0027）
- `common-core/client/InternalTokenRequestInterceptor`、`common-core/config/FeignInternalTokenAutoConfiguration`（ADR-0034）
- `payment-service/application/risk/RiskCheckService`（ADR-0028）
- 对应测试 4 个：`SensitiveDataMaskerTest`、`RiskCheckServiceTest`、`InternalTokenRequestInterceptorTest`、`InternalTokenOutboundTest`、`InternalServiceAuthInterceptorTest`
- 配置项：payment 的 `payment.security.*` / `payment.risk.*`，以及 payment / refund / settlement / reconciliation / fulfillment 的 `platform.security.*`

## 实现踩坑（已在 ADR-0025 记录，骨架保留的价值所在）

1. **过滤器必须注册为 `FilterRegistrationBean`**：Spring Boot 的 MockMvc **只收集 `FilterRegistrationBean`**，只标 `@Component` 的 `Filter` bean 在集成测试里会被绕过 → 出现「测试全绿、生产裸奔」的假绿。**这是骨架保留的首要理由。**
2. **Servlet url pattern 不支持中段通配**：注册 pattern 用 `/internal/payments/*`，具体路径用 `AntPathMatcher` 在过滤器内判定（起初用 `String.equals` 匹配含 `*` 的模式，恒不匹配，过滤器被整体跳过）。
3. **读原始 body 后必须换包装器**：`StreamUtils.copyToByteArray` 消费掉输入流后，下游 `@RequestBody` 会读到空，需 `CachedBodyHttpServletRequest` 兜底。

> 这三条即使验签为空也**必须固化在代码里**：回调过滤器已经读了 body，若不放包装器，回调接口会永远收到空报文。

## 关键决策（最简实现，已裁决）

- 鉴权 = 共享密钥 API Key（非 mTLS/OAuth2）——方案保留，**实现留空**。
- 验签 = HMAC-SHA256（非非对称）；验签串 `timestamp + "." + rawBody`——算法在 `SignatureVerifier` 中保留，**调用点留空**。
- 密钥 = env 注入（非 Vault）——ADR-0026 Accepted；鉴权/验签空实现期无密钥可注入，配置块暂不引入。
- 风控 = **不做**（ADR-0028 裁决删除），原计划的「只观测不拦截」一并搁置。
- 脱敏 = **不做**（ADR-0027 裁决删除）。
- 出入站令牌传播 = **不做**（ADR-0034~0037 裁决删除）。
