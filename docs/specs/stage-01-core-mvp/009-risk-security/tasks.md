# Tasks: 009-risk-security

> ## ⛔ 负责人裁决（2026-08-30 / 2026-08-31 落地）
>
> | ADR | 主题 | 裁决 | 落地形态 |
> |-----|------|------|----------|
> | ADR-0024 | 内部服务间调用鉴权 | ✅ Accepted（方案）／⭕ 实现=**预留空函数** | `InternalServiceAuthInterceptor` 保留挂载，`verifyServiceToken()` 恒放行 |
> | ADR-0025 | 渠道回调 HMAC 验签 | ✅ Accepted（方案）／⭕ 实现=**预留空函数** | `ChannelCallbackSignatureFilter` 保留骨架，`verifySignature()` 恒通过 |
> | ADR-0026 | 密钥 env 注入 / 明文 | ✅ Accepted | 保留 env 注入；鉴权+验签空实现后密钥配置已移除 |
> | ADR-0027 | 敏感数据脱敏 | ⛔ **Not Implemented（不做）** | `SensitiveDataMasker` 已删除 |
> | ADR-0028 | 最小风控 | ⛔ **Not Implemented（不做）** | `RiskCheckService` 已删除 |
> | ADR-0034 | 出站令牌传播 | ⛔ **Not Implemented（不做）** | `InternalTokenRequestInterceptor` 已删除 |
> | ADR-0035 | 入站鉴权推广到其余服务 | ⛔ **Not Implemented（不做）** | 本期不立项 |
> | ADR-0036 | 令牌平滑轮换 | ⛔ **Not Implemented（不做）** | 随 ADR-0034 一并搁置 |
> | ADR-0037 | 鉴权失败埋点告警 | ⛔ **Not Implemented（不做）** | 埋点随鉴权实现一并移除 |
>
> **图例**：`[x]` 已完成 ｜ `[ ]` 待办 ｜ `[-]` 不做／已回退 ｜ `[~]` 部分完成（保留一半、回退一半）

**Current Progress（2026-08-31）**：按裁决完成降级与清理。`SignatureVerifier`（common-core 工具类）+ 回调链路骨架 + 两个鉴权/验签接入点保留为空实现；`SensitiveDataMasker` / `RiskCheckService` / 出站令牌拦截器及其测试全部删除；5 个服务的 `platform.security.*` 与 payment 的 `payment.security.*` / `payment.risk.*` 配置移除。`mvn -o clean verify -fae` 全量 **15 个 reactor 条目 BUILD SUCCESS**。

## Implementation

- [~] T001 [P] `common-core` 新增 `security/SignatureVerifier.java`（HMAC-SHA256 + 常数时间比对 + 防重放窗口）—— ✅ **保留**（8 个单测通过，作为将来接入验签的算法归口）；`security/SensitiveDataMasker.java` —— ❌ **已删除**（ADR-0027 不做）。
- [x] T002 `payment-service` 新增 `api/dto/ChannelCallbackRequest.java`：回调入站 DTO（status / channelReference / reason / amountMinor），`toResult()` 映射为 `ChannelResult`。
- [x] T003 `payment-service` 新增 `api/ChannelCallbackController.java`：`POST /internal/payments/{id}/channel-callback`，委托 `PaymentCallbackService.handleCallback`。
- [~] T004 `payment-service` 新增 `web/ChannelCallbackSignatureFilter.java` + `web/CachedBodyHttpServletRequest.java` —— ✅ **骨架保留**（路径 Ant 匹配、原始 body 读、可重复读包装、拒绝分支、403 输出）；❌ **验签逻辑降级为空实现** `verifySignature() → true`（ADR-0025），密钥缺失 `503`、防重放、`callback_signature_rejected` 埋点一并移除。
- [~] T005 `payment-service` 新增 `web/InternalServiceAuthInterceptor.java` —— ✅ **保留并仍挂在 `/internal/**`**（回调路径除外），作为鉴权唯一接入点；❌ **鉴权逻辑降级为空实现** `verifyServiceToken() → true`（ADR-0024），`X-Service-Token` 常数时间比对、未配置 `503`、不匹配 `403`、`internal_auth_rejected` 埋点全部移除。
- [~] T006 扩展 `web/WebConfig.java` —— ✅ 过滤器仍以 `FilterRegistrationBean` 注册（`Ordered.HIGHEST_PRECEDENCE`），拦截器注册保留；❌ 移除了对 `payment.security.*` 的构造注入，改为无参构造。
- [-] T007 `application.yml` 增加 `payment.security.*`（service-token / internal-auth-enabled / channel-secret / signature-replay-window-ms）与 `payment.risk.*` —— ⛔ **已回退**：空实现不需要密钥配置，整块删除（payment 及 fulfillment / reconciliation / refund / settlement 的 `platform.security` 块同步删除）。复用既有 `resolve.auth-enabled` / `PAYMENT_ADMIN_TOKEN` 的部分**保留**（resolve 人工收敛端点，不在本期裁决范围）。
- [-] T008 `payment-service` 新增 `application/risk/RiskCheckService.java`（`SINGLE_MAX_AMOUNT` / `WINDOW_LIMIT_COUNT` 两条规则）并挂到 `PaymentApplicationService#createPaymentIntent` —— ⛔ **已删除**（ADR-0028 风控先不做）。`PaymentApplicationService` 的调用点、构造参数、import 全部清理。
- [~] T009 测试 —— ✅ `SignatureVerifierTest`(8) 保留；✅ `ChannelCallbackSecurityTest` / `InternalServiceAuthTest` **改写**为「空实现放行性」断言（见下）；❌ `SensitiveDataMaskerTest`、`RiskCheckServiceTest` **已删除**。

## Verification

- [x] T010 运行 `mvn -o clean verify -fae`：全量 **15 个 reactor 条目**（14 个 Maven 模块 + root）BUILD SUCCESS，0 失败 0 错误。
- [x] T011 更新 `docs/architecture/roadmap.md`：Current Status 推进 009、Feature 状态表、Next Feature 指向 Phase 10。
- [x] T012 负责人确认 ADR-0024~0028 状态 —— ✅ **已裁决**（2026-08-30）：0024/0025 空实现、0026 明文 accept、0027/0028 不做。代码已按裁决改完。
- [-] T013（ADR-0024 遗留）补「出站内部服务令牌」Feign 拦截器 —— ⛔ **整条已回退**（ADR-0034~0037 不做），下列子项**全部撤销**：
  - [-] T013a `common-core` 新增 `client/InternalTokenRequestInterceptor.java` —— ❌ **文件已删除**。
  - [-] T013b `common-core` 新增 `config/FeignInternalTokenAutoConfiguration.java` 并注册进 `AutoConfiguration.imports` —— ❌ **文件已删除**，`imports` 恢复为仅 `CommonCoreAutoConfiguration` / `FeignTraceAutoConfiguration`。
  - [-] T013c `payment-service` 入站令牌改为「首个非空」`payment.security.service-token` → `platform.security.internal-token` —— ❌ 随鉴权空实现一并撤销。
  - [-] T013d 5 个服务的 `application.yml` 新增 `platform.security.internal-token` / `outbound-token-enabled` —— ❌ **已删除**。
  - [-] T013e 鉴权失败埋点（ADR-0037）`payment.internal_auth_rejected` —— ❌ 已移除。
  - [-] T013f 测试 `InternalTokenRequestInterceptorTest`(6)、`InternalTokenOutboundTest`(3)、`InternalServiceAuthInterceptorTest`(6)、`InternalServiceAuthTest$PlatformTokenFallback`(2) —— ❌ 前三个文件**已删除**；`$PlatformTokenFallback` 内置嵌套类改写为空实现放行断言。
  - [-] T013g `mvn -o clean verify -fae` —— 🔄 已按**回退后**的代码重跑，BUILD SUCCESS。
  - [x] T013h ADR-0034~0037 写入 `docs/adr/0011-internal-token-decisions.md` —— ✅ 文档保留，但状态由 Proposed 改为 **⛔ Not Implemented（不做，代码已清理）**；`docs/adr/README.md` 索引同步。
  - [-] T013i `docs/operations/runbook.md` 补充 `PLATFORM_INTERNAL_TOKEN` 与「内部端点 403」处置 —— ⛔ 待随架构文档同步修订（令牌链已不存在）。
- [x] T014 负责人确认 ADR-0034~0037 状态 —— ✅ **已裁决**（2026-08-30）：出入站鉴权令牌都先不做。代码已清理。
- [-] T015（ADR-0035）决定是否把入站鉴权推广到其余 7 个暴露 `/internal/**` 的服务 —— ⛔ **本期不立项**，随 ADR-0035 一并搁置。

## 回退落地清单（代码层面已执行）

| # | 位置 | 动作 |
|---|------|------|
| 1 | `common-core/.../security/SensitiveDataMasker.java` | 删除（ADR-0027） |
| 2 | `common-core/.../security/SensitiveDataMaskerTest.java` | 删除 |
| 3 | `common-core/.../client/InternalTokenRequestInterceptor.java` | 删除（ADR-0034） |
| 4 | `common-core/.../client/InternalTokenRequestInterceptorTest.java` | 删除 |
| 5 | `common-core/.../config/FeignInternalTokenAutoConfiguration.java` | 删除（ADR-0034） |
| 6 | `common-core/.../AutoConfiguration.imports` | 移除 Feign 令牌自动配置行 |
| 7 | `payment-service/.../application/risk/RiskCheckService.java` | 删除（ADR-0028） |
| 8 | `payment-service/.../application/risk/RiskCheckServiceTest.java` | 删除 |
| 9 | `payment-service/.../infra/client/InternalTokenOutboundTest.java` | 删除 |
| 10 | `payment-service/.../web/InternalServiceAuthInterceptorTest.java` | 删除 |
| 11 | `payment-service/.../web/ChannelCallbackSignatureFilter.java` | 降级为空实现（保留骨架） |
| 12 | `payment-service/.../web/InternalServiceAuthInterceptor.java` | 降级为空实现（保留挂载） |
| 13 | `payment-service/.../application/PaymentApplicationService.java` | 移除风控调用点/构造参数/import |
| 14 | `payment` + 4 个服务的 `application.yml` | 移除 `platform.security.*` / `payment.security.*` / `payment.risk.*` |

## 依赖与策略

- T002 / T003 无依赖，可先做；T004~T006 依赖 T002 / T003 暴露的端点。
- T004 与 T005 同属 `WebConfig`（T006），串行修改避免冲突。
- 回退后 T004 / T005 的骨架**刻意保留**：接入真实渠道 / 真实鉴权时只需实现 `verifySignature` / `verifyServiceToken` 两个方法，不必重踩「过滤器层验签 + body 可重复读 + `FilterRegistrationBean` 注册」三个坑。

## 备注

- `SignatureVerifier` 作为 common-core **纯工具类**保留（有 8 个单测覆盖），不引入任何配置或 bean，空实现期零副作用。
- `payment.resolve.auth-enabled` / `PAYMENT_ADMIN_TOKEN`（resolve 人工收敛端点）**不在本期裁决范围**，保留原样。
- 遗留项：`docs/operations/runbook.md` §4「开启顺序」仍描述令牌链，需随本 Feature 最终形态修订（见 tasks T013i）。
