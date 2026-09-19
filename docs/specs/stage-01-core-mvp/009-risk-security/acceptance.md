# Acceptance: Phase 9 风险 / 安全底座（009-risk-security）

**Feature**: `009-risk-security` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md) | **ADR**: [0009-risk-security-decisions.md](../../adr/0009-risk-security-decisions.md) / [0011-internal-token-decisions.md](../../adr/0011-internal-token-decisions.md)

> **最终验收（2026-08-31）**：已按负责人 2026-08-30 裁决完成降级与清理。`mvn -o clean verify -fae` 全量 **15 个 reactor 条目 BUILD SUCCESS**。
>
> | ADR | 裁决 | 实现 |
> |-----|------|------|
> | ADR-0024 | ✅ Accepted | ⭕ 预留空函数 |
> | ADR-0025 | ✅ Accepted | ⭕ 预留空函数 |
> | ADR-0026 | ✅ Accepted | 明文 env 注入 |
> | ADR-0027 | ⛔ Not Implemented | 类已删除 |
> | ADR-0028 | ⛔ Not Implemented | 类已删除 |
> | ADR-0034~0037 | ⛔ Not Implemented | 代码已清理 |
>
> **图例**：`[x]` 通过 ｜ `[-]` 不做／已回退 ｜ `[~]` 降级为接入点保留

## 功能验收

### US1 - 渠道回调验签（Priority: P1）⭕ 降级为「接入点保留 + 空实现」

- [x] `POST /internal/payments/{id}/channel-callback` 端点可用，委托 `PaymentCallbackService.handleCallback`
- [x] `ChannelCallbackSignatureFilter` 以 `FilterRegistrationBean` 注册，`Ordered.HIGHEST_PRECEDENCE`，MockMvc 与生产链路行为一致
- [x] 路径匹配正确：`/internal/payments/*/channel-callback` 由 Servlet 前缀 + 过滤器内 `AntPathMatcher` 双重判定
- [x] 原始 body 被完整反序列化（`CachedBodyHttpServletRequest` 生效）
- [~] HMAC-SHA256 验签 —— ⛔ **空实现**，`verifySignature()` 恒 `true`；算法保留在 `common-core` 的 `SignatureVerifier`（8 单测通过）
- [-] 签名不匹配 → `403` —— ⛔ **本期不可达**（ADR-0025 空实现）
- [-] 签名头缺失 → `403` —— ⛔ **本期不可达**
- [-] 时间戳超出防重放窗口 → `403` —— ⛔ **本期不可达**（无密钥配置，窗口不可配）
- [-] body 被篡改 → `403` —— ⛔ **本期不可达**
- [-] 未配置 `PAYMENT_CHANNEL_SECRET` → `503` —— ⛔ **回退**（配置块已移除）
- [-] `payment.callback_signature_rejected` 埋点 —— ⛔ **已移除**（ADR-0037 不做）
- [x] `SignatureVerifier` 位于 `common-core`，渠道侧与 Mock 可复用同一算法（`SignatureVerifierTest` 覆盖 sign/verify/常数时间/防重放窗口边界）
- [x] 放行性由测试明确断言（防后人误读）：`ChannelCallbackSecurityTest` 的 `invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed` / `missingSignatureHeadersIsAllowed...` / `staleTimestampIsAllowedWhileReplayProtectionIsStubbed` / `tamperedBodyIsAllowed...` 以及 `@Nested UnconfiguredSecret#allowsCallbackWhileSignatureVerificationIsStubbed`

### US2 - 内部端点鉴权（Priority: P1）⭕ 降级为「接入点保留 + 空实现」

- [x] `InternalServiceAuthInterceptor` 仍挂在 `/internal/**`（回调路径除外），是鉴权的**唯一归口**
- [-] 合法 token 通过 / 缺失 `403` / 不匹配 `403` 常数时间比对 —— ⛔ **已移除**（ADR-0024 空实现，`verifyServiceToken()` 恒放行）
- [-] 启用但未配置 `PAYMENT_INTERNAL_TOKEN` → `503` —— ⛔ **已移除**
- [-] 开关关闭（默认 false）→ 放行 —— ⛔ 无开关概念了，恒定放行
- [x] 渠道回调路径不经鉴权拦截器（其安全性由验签过滤器独立负责，ADR-0024 排除项保留）
- [-] ~~出站令牌闭环（T013 / ADR-0034）~~ —— ⛔ **整条已回退**：`InternalTokenRequestInterceptor` / `FeignInternalTokenAutoConfiguration` / `platform.security.*` 全部删除
- [-] 出站令牌不外泄到对外 API（单测断言）—— ⛔ 随拦截器删除
- [-] 入站令牌回退到 `platform.security.internal-token` —— ⛔ 已删除
- [-] 鉴权失败埋点 `payment.internal_auth_rejected` —— ⛔ **已移除**（ADR-0037 不做）
- [x] 空实现期行为由测试断言：`InternalServiceAuthTest` 的 `missingServiceTokenIsAllowedWhileAuthIsStubbed` / `invalidServiceTokenIsAllowedWhileAuthIsStubbed` / `@Nested UnconfiguredToken#allowsRequestWhileAuthIsStubbed` / `@Nested PlatformTokenFallback#allowsOtherTokensWhileAuthIsStubbed` / `@Nested Disabled#allowsRequestWithoutToken`

### US3 - 密钥与敏感数据（Priority: P2）⛔ 部分不做

- [x] `PAYMENT_ADMIN_TOKEN` 经 env 注入，代码中 0 处硬编码（FR-005 / ADR-0026；resolve 人工收敛端点，不在本期裁决范围）
- [-] `PAYMENT_INTERNAL_TOKEN` / `PAYMENT_CHANNEL_SECRET` —— ⛔ **配置已移除**（鉴权/验签空实现，无密钥可注入）
- [-] `SensitiveDataMasker.maskToken` 保留前后各 4 位 —— ⛔ **ADR-0027 不做，类已删除**
- [-] 脱敏结果不含原始密钥片段（单测断言） —— ⛔ 随类删除
- [x] 未引入 Vault / KMS（ADR-0026 Accepted：明文 env 即可）

### US4 - 最小风控（Priority: P3）⛔ 整节不做

- [-] `payment.risk` 配置块 —— ⛔ **已移除**（ADR-0028）
- [-] 默认 `enabled=false` 全放行 —— ⛔ `RiskCheckService` 类已删除
- [-] `SINGLE_MAX_AMOUNT` / `WINDOW_LIMIT_COUNT` 两条规则 —— ⛔ 已删除
- [-] 命中只记指标不阻断 —— ⛔ 已删除
- [x] `PaymentApplicationService` 已彻底清理风控调用点、构造参数与 import（代码级确认，非仅注释）

## 非功能验收

- [x] 验签过滤器注册为 `FilterRegistrationBean`，**MockMvc 与生产链路行为一致**（避免「测试全绿、生产裸奔」）
- [x] 未引入 Spring Security / OAuth2 / mTLS / MQ / 2PC（本期最简方案）
- [x] 未改动既有资金主流程语义（支付/退款/对账/结算/记账行为不变）
- [x] `mvn -o clean verify -fae` 全量 **15 个 reactor 条目**（14 Maven 模块 + root）BUILD SUCCESS，0 失败 0 错误（SC-004）
- [~] 新增测试 —— ✅ `SignatureVerifierTest`(8)；✅ `ChannelCallbackSecurityTest` / `InternalServiceAuthTest` 改写为空实现放行断言；❌ `SensitiveDataMaskerTest` / `RiskCheckServiceTest` / `InternalTokenRequestInterceptorTest` / `InternalTokenOutboundTest` / `InternalServiceAuthInterceptorTest` **已删除**

## 决策验收（Constitution §8）

- [x] ADR-0024（内部服务间调用鉴权）→ **2026-08-30 裁决：Accepted，实现=预留空函数**。已落地。
- [x] ADR-0025（渠道回调 HMAC 验签 + 防重放 + 过滤器注册方式）→ **2026-08-30 裁决：Accepted，实现=预留空函数**。已落地。
- [x] ADR-0026（密钥 env 注入，不接 Vault）→ **2026-08-30 裁决：Accepted，用明文**。已落地。
- [-] ADR-0027（脱敏口径与字段清单）→ **2026-08-30 裁决：不做**。`SensitiveDataMasker` 已删除。
- [-] ADR-0028（最小风控只观测不拦截）→ **2026-08-30 裁决：不做**。`RiskCheckService` 已删除。
- [x] 新增入站端点 `POST /internal/payments/{id}/channel-callback` → 端点保留（§8.4）
- [-] ADR-0034（出站令牌传播）→ **2026-08-30 裁决：不做**。代码已清理。
- [-] ADR-0035（入站鉴权推广其余 7 个服务）→ **2026-08-30 裁决：不做**。本期不立项。
- [-] ADR-0036（令牌平滑轮换）→ **2026-08-30 裁决：不做**。随 ADR-0034 搁置。
- [-] ADR-0037（鉴权失败埋点告警）→ **2026-08-30 裁决：不做**。埋点已移除。
- [-] 新增环境变量 `PLATFORM_INTERNAL_TOKEN` 纳入部署配置（§8.4）→ ⛔ **不再需要**，令牌链已删除。

## 已知未闭环（需后续 Feature）

1. ⚠️ **伪造渠道回调可翻转支付状态**（`verifySignature` 空实现）。**部署前置条件：payment-service 不得暴露公网，仅内网/VPC 可达。** 接入真实渠道时实现 `ChannelCallbackSignatureFilter#verifySignature` 即可。
2. ⚠️ **`/internal/**` 可越权调用**（`verifyServiceToken` 空实现）。**部署前置条件：安全组/服务网格做网络层隔离。** 接入时实现 `InternalServiceAuthInterceptor#verifyServiceToken`，**并必须同时**补出站令牌（否则调用方全线 403，见 ADR-0034 拓扑约束）。
3. ⚠️ **无风控观测**（ADR-0028 不做）：无单笔/窗口限额，异常交易无埋点。
4. ⚠️ **日志可能落明文敏感数据**（ADR-0027 不做）：无脱敏工具。
5. **回调验签仅覆盖 payment-service**：refund / settlement 等暂无入站外部回调面，接入真实渠道时复用同一 `SignatureVerifier`。
6. **入站鉴权仅覆盖 payment-service**：其余 7 个服务（ledger / order / refund / settlement / reconciliation / fulfillment / entitlement）的 `/internal/**` 仍无鉴权，依赖网络层隔离。见 ADR-0035（本期不立项）。
7. **`docs/operations/runbook.md` §4「开启顺序」仍描述令牌链**：令牌已删除，需随本 Feature 最终形态修订（tasks T013i 遗留）。

## 验收结论

✅ **已通过**（以负责人 2026-08-30 裁决为准）。空实现项已在代码与测试中显式标注为「stubbed」，不存在「以为已生效」的假绿。遗留项为上述 7 条，其中 1~4 条为**已被接受的部署风险**，5~7 条为后续 Feature / 文档同步项。
