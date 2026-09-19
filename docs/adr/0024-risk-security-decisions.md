# ADR 集合：Phase 9 风险 / 安全（ADR-0024 ~ ADR-0028）

**Feature**：`009-risk-security`（Roadmap Phase 9）
**日期**：2026-08-29（裁决 2026-08-30，落地 2026-08-31）

## 状态总览（2026-08-30 负责人裁决，2026-08-31 落定）

| ADR | 方案状态 | 本期实现 | 裁决原文 |
|---|---|---|---|
| ADR-0024 内部服务间调用鉴权 | ✅ Accepted | **预留空函数** | 「鉴权预留函数空实现」 |
| ADR-0025 渠道回调签名校验 | ✅ Accepted | **预留空函数** | 「加验签预留函数空实现就行」 |
| ADR-0026 密钥管理 | ✅ Accepted | **明文配置** | 「目前就用明文就行了」 |
| ADR-0027 敏感数据脱敏 | ⛔ Not Implemented | **不做** | 「敏感数据不管」 |
| ADR-0028 最小风控规则 | ⛔ Not Implemented | **不做（留空）** | 「忽略，风控先不做，留空」 |

> **口径说明**：ADR-0024 / 0025 的方案（共享密钥 `X-Service-Token`、HMAC-SHA256 过滤器层验签）**经裁决确认为最终方案**，
> 但本期**只落结构骨架 + 一个空实现的预留函数**，真正接入时只需实现一个方法、无需改动调用链。
> ADR-0027 / 0028 为本期**明确不做**，`SensitiveDataMasker` 与 `RiskCheckService` 已从代码库删除，
> 待接入真实卡号/凭证/真实渠道时重新立项。
>
> 「预留空实现」的四条硬性约定与风险接受说明见
> [technical-solution §2.4](../architecture/technical-solution.md#24-本阶段范围裁剪与预留契约)。

**范围**：服务/操作权限、渠道回调签名校验、密钥管理、敏感数据脱敏、最小风控。

> 复用现状：`payment-service` 已有 `ResolveAuthorizationInterceptor` + `WebConfig`，对 `/payments/*/resolve` 收敛端点做 `X-Admin-Token` 鉴权（`payment.resolve.auth-enabled` / `PAYMENT_ADMIN_TOKEN`）。该端点属**运维收敛面**，与 ADR-0024 的「服务间调用面」是两个独立关注点，**不在本次裁决范围、维持原样**。

## 落地清单（2026-08-31 最终状态）

| ADR | 落地位置 | 本期形态 |
| --- | --- | --- |
| 0024 内部服务鉴权 | `payment-service/web/InternalServiceAuthInterceptor.java`；`WebConfig#addInterceptors` 注册到 `/internal/**`（排除回调路径） | 骨架保留，`verifyServiceToken(...)` **空实现恒放行**，带 `TODO(ADR-0024)` |
| 0025 回调验签 | `common-core/security/SignatureVerifier.java`（工具类保留，未被任何生产代码调用）；`payment-service/web/ChannelCallbackSignatureFilter.java` + `CachedBodyHttpServletRequest.java` + `WebConfig` 的 `FilterRegistrationBean` | 骨架保留（路径匹配、原始 body 读取 + 可重复读包装），`verifySignature(...)` **空实现恒通过**，带 `TODO(ADR-0025)` |
| 0026 密钥管理 | —— | **明文配置**；不引入 KMS/Vault。因鉴权/验签均空实现，**`payment.security.*` 配置块已整体移除**，接入时补回 |
| 0027 敏感脱敏 | —— | **已删除** `SensitiveDataMasker` 及其测试 |
| 0028 最小风控 | `PaymentApplicationService#createPaymentIntent` | **已删除** `RiskCheckService` 及其测试；挂点处保留 `TODO(ADR-0028)` 注释，`payment.risk.*` 配置已移除 |

**验证**：`mvn -o clean verify -fae` 全量 15 模块 **BUILD SUCCESS**（2026-08-31）。
保留的测试：`SignatureVerifierTest`（工具类自测）、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`——后两者已改写为**断言占位期放行契约**，
并在 Javadoc 中写明「实现鉴权/验签后必须整体反转」的反转清单。

---

<a id="adr-0024"></a>
## ADR-0024 内部服务间调用鉴权

**状态**：✅ **Accepted**（方案确认）／ 实现 = **预留空函数**（2026-08-30 裁决「鉴权预留函数空实现」）

**背景**：`/internal/**` 端点（退款金额查询、退款尝试、对账确认事实等）当前**无鉴权**，任何能触达端口的调用方均可调用，属跨服务越权面。

**决策（最简）**：采用**共享密钥 API Key** 模型。
- 请求头 `X-Service-Token` 与配置 `payment.security.service-token`（env 注入）比对，不一致/缺失 → `403`。
- 由 `InternalServiceAuthInterceptor` 统一注册到 `/internal/**`（回调路径除外，其用 HMAC 独立校验）。
- `payment.security.internal-auth-enabled` 默认 `false`（与 resolve 端点「本地学习环境可关」约定一致），生产 MUST 置 `true`；启用但未配置 token → 默认拒绝（`503`）。
- 后续演进：mTLS / OAuth2 client-credentials（ADR 待立）。

**影响**：refund-service、reconciliation-service 等调用方 MUST 在 Feign 客户端带上 `X-Service-Token`（本期在测试/本地以配置值联通，生产由配置中心下发）。

**影响（重要未闭环项）**：`internal-auth-enabled` 生产置 `true` 时，**所有调用方 MUST 在出站 Feign 请求上带 `X-Service-Token`**。当前 common-core 只有 `TraceIdRequestInterceptor` 传播 traceId，**尚无统一的出站内网令牌拦截器**，因此本 Feature 默认关闭该开关——默认关闭是为了不破坏既有本地联调与集成测试，而非安全上可接受。

**待确认**：
1. 是否默认 `true`（需先补出站令牌拦截器，否则一开就全线 403）。
2. token 下发机制（配置中心 vs env）。
3. 每个服务各自持有独立 token，还是全平台共用一把（当前实现是单服务单 token）。

---

<a id="adr-0025"></a>
## ADR-0025 渠道回调签名校验

**状态**：✅ **Accepted**（方案确认）／ 实现 = **预留空函数**（2026-08-30 裁决「加验签预留函数空实现就行」）

**背景**：Technical-Solution 明确要求「渠道回调 MUST 验证签名与来源，防止伪造回调」（当前 Mock Channel 未接真实签名，Phase 9 落地）。伪造回调可把支付翻转为 SUCCESS 并触发下游履约，是最高危外部面。

**决策（最简）**：采用 **HMAC-SHA256** 来源签名，校验前置到 **Servlet 过滤器**。
- 新增入站端点 `POST /internal/payments/{id}/channel-callback`，由 `ChannelCallbackController` 接收；验签由 `ChannelCallbackSignatureFilter` 在**过滤器层**完成，通过后 `chain.doFilter` 才放行，未通过**根本不触达 Controller**，因此不可能调用 `PaymentCallbackService`。
- 回调头：`X-Channel-Signature`（HMAC-SHA256 hex）、`X-Channel-Timestamp`（毫秒）。
- 验签串：`timestamp + "." + rawBody`；用 `channelSecret` 派生 HMAC，常数时间比对。
- **防重放**：`|now - timestamp| <= payment.security.signature-replay-window-ms`（默认 300000ms = 5min），越界 → `403`。
- 签名缺失/不匹配/过期/body 被篡改/时间戳缺失 → `403`，并记录 `payment.callback_signature_rejected` 指标（带 `reason` 维度）。
- `SignatureVerifier`（HMAC-SHA256，常数时间比对）置于 `common-core`，渠道侧（含 Mock 测试）可复用同算法签名。
- 未配置 `PAYMENT_CHANNEL_SECRET` → `503`（见 ADR-0026：验签不可关闭，配置缺失只能拒绝服务）。

**实现要点（踩坑记录，供后续评审）**
1. **必须注册为 `FilterRegistrationBean`，不能只标 `@Component`**。Spring Boot 的 MockMvc 只收集 `FilterRegistrationBean` / `DelegatingFilterProxyRegistrationBean`；若只注册为普通 `Filter` bean，集成测试链路会**绕过验签**，出现「测试全绿、生产裸奔」的假绿。故由 `WebConfig#channelCallbackSignatureFilter()` 显式注册。
2. **Servlet url pattern 只支持前缀/后缀/精确匹配**，不支持 Ant 风格中段通配。故注册 pattern 用 `/internal/payments/*`，具体路径判定放在过滤器内用 `AntPathMatcher.match("/internal/payments/*/channel-callback", requestURI)`。
3. 过滤器读完原始 body 后必须换上可重复读包装器（`CachedBodyHttpServletRequest`），否则下游 `@RequestBody` 拿到的流已被消费。

**影响**：渠道侧（含 Mock）MUST 以 `channelSecret` 对回调签名；本实现默认开启，**不可关闭**（外部面 MUST 校验）。

**待确认**：签名算法是否升阶 HMAC-SHA256→EdDSA/非对称；`channelSecret` 与 `internal service-token` 是否合并为统一密钥体系；是否需要按渠道拆分多套密钥（当前单密钥）。

---

<a id="adr-0026"></a>
## ADR-0026 密钥管理

**状态**：✅ **Accepted**（2026-08-30 裁决「目前就用明文就行了」；env 注入 + 明文配置，不引入 KMS/Vault）

**背景**：Phase 9 引入 `admin-token`、`service-token`、`channelSecret` 三类密钥，需明确管理边界。

**决策（最简）**：**配置项 + 环境变量注入**，不引入 Vault/KMS。
- 全部经 env（`PAYMENT_ADMIN_TOKEN` / `PAYMENT_INTERNAL_TOKEN` / `PAYMENT_CHANNEL_SECRET`）或部署配置下发；禁止硬编码、禁止入库、禁止明文日志。
- 按环境（local / test / prod）隔离；本地学习环境允许空值 + 显式关闭开关。
- 后续演进：接 KMS / Vault / Spring Cloud Config encrypt。

**待确认**：是否接入密钥管理服务（本期不接）；轮换策略（本期手动重启生效）。

---

<a id="adr-0027"></a>
## ADR-0027 敏感数据脱敏

**状态**：⛔ **Not Implemented（不做）** —— 2026-08-30 裁决「**敏感数据不管**」；`SensitiveDataMasker` 已删除

**背景**：审计日志、响应、异常中不得出现明文密钥/全卡号/凭证。

**决策（最简）**：提供 `SensitiveDataMasker` 工具（置于 `common-core`）：
- `maskToken`：保留前后各 4 位、中间 `****`；过短则整体 `****`。
- 审计/日志输出密钥、channel reference 等敏感字段时 MUST 经脱敏；结构化审计 `FINANCIAL_AUDIT` 仅记录幂等键、金额、状态等必要字段，不记录密钥原文。
- 本项目当前无真实卡号/凭证，脱敏点先行落地，待接入真实支付渠道时复用。

**待确认**：脱敏字段清单是否需扩展（如用户标识）；是否需要在响应 DTO 层统一脱敏。

---

<a id="adr-0028"></a>
## ADR-0028 最小风控规则

**状态**：⛔ **Not Implemented（不做）** —— 2026-08-30 裁决「**忽略，风控先不做，留空**」；`RiskCheckService` 已删除，挂点处留 `TODO(ADR-0028)`

**背景**：Roadmap Phase 9 含「与支付流程匹配的最小风控规则」，但当前阶段重心是签名/鉴权底座。

**决策（最简）**：**落一个只观测、不拦截的 `RiskCheckService`，默认关闭**。
- `payment.risk` 配置块：`enabled`（默认 `false`）、`single-max-amount-minor`（单笔金额上限，`0` 表示不限）、`window-limit-count`（60s 窗口内笔数上限，`0` 表示不限）。
- 只有两条规则：`SINGLE_MAX_AMOUNT`（金额 > 上限）与 `WINDOW_LIMIT_COUNT`（窗口内累计笔数 > 上限）。
- 挂载点：`PaymentApplicationService.createPaymentIntent` 在登记支付成功后、调用渠道前调用 `riskCheckService.onPaymentCreated(payment)`。
- 命中后果**仅是**：打 `payment.risk_triggered` 指标（带 `rule` 维度）+ 一条审计日志 + WARN 日志；**不抛异常、不返回拒绝、不改变资金主流程**（刻意避免误杀，规则未校准前绝不拦截真实交易）。
- 不引入规则引擎 / 复杂风控平台。

**已知简化（刻意）**：窗口计数是**进程内**的（`volatile int` + 60s 固定窗口），多实例部署下不精确、也不共享；真实风控需要独立计数服务（Redis / 风控平台）。当前阶段只用于打通「规则挂点 + 命中记录」的骨架。

**待确认**：是否在本期启用某条默认规则；阈值口径（按用户/商户/全局）与窗口定义；命中后是否需要在未来演进为「阻断 + 人工复核」两态。
