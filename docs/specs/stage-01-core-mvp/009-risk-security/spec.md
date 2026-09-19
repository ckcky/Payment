# Spec: 009-risk-security（风险 / 安全底座）

**版本**：0.2
**日期**：2026-08-29（初稿）／2026-08-31（按负责人裁决修订）
**状态**：✅ 已裁决 —— 部分 Accepted、部分 ⛔ Not Implemented（代码已按裁决改完，`mvn -o clean verify -fae` 全量 15 reactor 条目 BUILD SUCCESS）

> ## ⛔ 负责人裁决（2026-08-30）
>
> | ADR | 主题 | 裁决 | 落地 |
> |-----|------|------|------|
> | ADR-0024 | 内部服务间调用鉴权 | ✅ Accepted（方案）／⭕ 实现=**预留空函数** | 拦截器保留挂载，恒放行 |
> | ADR-0025 | 渠道回调 HMAC 验签 | ✅ Accepted（方案）／⭕ 实现=**预留空函数** | 过滤器保留骨架，恒通过 |
> | ADR-0026 | 密钥 env 注入 / 明文 | ✅ Accepted | 明文 env，不接 Vault |
> | ADR-0027 | 敏感数据脱敏 | ⛔ **不做** | `SensitiveDataMasker` 已删 |
> | ADR-0028 | 最小风控 | ⛔ **不做** | `RiskCheckService` 已删 |
> | ADR-0034 | 出站令牌传播 | ⛔ **不做** | 拦截器+自动配置已删 |
> | ADR-0035 | 入站鉴权推广其余服务 | ⛔ **不做** | 本期不立项 |
> | ADR-0036 | 令牌平滑轮换 | ⛔ **不做** | 随 ADR-0034 搁置 |
> | ADR-0037 | 鉴权失败埋点告警 | ⛔ **不做** | 埋点随鉴权移除 |
>
> 裁决原文：*「ADR-0024 鉴权预留函数空实现／ADR-0025 加验签预留函数空实现就行／ADR-0026 目前就用明文就行了／ADR-0027 敏感数据不管／ADR-0028 忽略，风控先不做，留空／ADR-0034~0037 出入站的鉴权令牌都先不做」*

## 1. 背景与目标

主线 001–007 已交付资金业务闭环，但安全底座不完整：
- 入站渠道回调**未校验签名**（伪造回调可翻转支付成功，最高危）。
- `/internal/**` 内部端点（退款、对账）**无鉴权**（跨服务越权面）。
- 密钥、脱敏、风控缺乏统一约定。

本 Feature 的目标是**把安全边界的位置与骨架固定下来**，遵循 Constitution §9（安全）与 Technical-Solution「渠道回调 MUST 验签」。

> **裁决后的范围收敛**：负责人裁决本期**不实现**具体校验逻辑，只保留「校验发生在哪儿」的接入点。这样做的取舍是——
> - **收益**：不为当前阶段（单机/内网演示、无真实渠道）引入无收益的配置与埋点负担；接入真实渠道时不必重踩过滤器注册、body 可重复读、MockMvc 假绿三个坑。
> - **代价**：当前**伪造渠道回调可翻转支付状态**、**`/internal/**` 可越权调用**。这两条是**已知且已被负责人接受的风险**，部署时必须依赖网络层隔离（内网/VPC 不对外暴露），见 ADR-0024 / ADR-0025 的「风险与前置条件」。

## 2. 范围

**包含（本期实际交付）**
- 渠道回调验签**接入点**：`ChannelCallbackSignatureFilter` 骨架（路径匹配 / body 读 / 可重复读包装 / 拒绝分支），`verifySignature` ⭕ 空实现（ADR-0025）。
- 内部端点鉴权**接入点**：`InternalServiceAuthInterceptor` 挂在 `/internal/**`，`verifyServiceToken` ⭕ 空实现（ADR-0024）。
- `SignatureVerifier` 通用工具（common-core，HMAC-SHA256 + 常数时间比对 + 防重放窗口），供将来接入时直接复用（ADR-0025）。
- 密钥 env 注入约定（ADR-0026，Accepted）；当前无密钥需注入，配置块暂不引入。

**不包含（按裁决删除或不立项）**
- ❌ 敏感数据脱敏 `SensitiveDataMasker`（ADR-0027 不做，类已删除）。
- ❌ 最小风控 `RiskCheckService`（ADR-0028 不做，类已删除）。
- ❌ 出站内部服务令牌传播（ADR-0034 不做，拦截器与自动配置已删除）。
- ❌ 入站鉴权推广到其余 7 个服务（ADR-0035 不立项）。
- ❌ 令牌平滑轮换（ADR-0036 不做）。
- ❌ 鉴权失败埋点告警（ADR-0037 不做）。
- 完整 OAuth2 / mTLS / 密钥管理服务（留待演进）。
- 复杂风控规则引擎、反欺诈模型。

## 3. 关键用户故事

- ~~**US1 渠道回调验签**：渠道回调 MUST 携带签名与时间戳；验签失败/过期/缺失/被篡改 → 拒绝，绝不触发支付状态变更。~~
  → ⛔ **本期降级**：端点与过滤器骨架已就绪，`verifySignature` 空实现 → **当前一律放行**。接入真实渠道时只需实现该方法。
- ~~**US2 内部端点鉴权**：跨服务 `/internal/**` 调用 MUST 携带合法 `X-Service-Token`；非法/缺失 → 拒绝。~~
  → ⛔ **本期降级**：拦截器已挂在 `/internal/**`，`verifyServiceToken` 空实现 → **当前恒定放行**。
- ~~**US3 敏感脱敏**：密钥等敏感字段在日志/审计中 MUST 脱敏。~~
  → ⛔ **ADR-0027 不做**，类已删除。
- ~~**US4 最小风控**：支付受理时可配两条阈值规则；命中只记录指标与审计，不阻断。~~
  → ⛔ **ADR-0028 不做**，类已删除。

## 4. 功能需求（FR）

- ~~FR-001~~ ⭕ **降级**：回调端点 `POST /internal/payments/{id}/channel-callback` 仍存在并可正常工作；`X-Channel-Signature` / `X-Channel-Timestamp` 已读取并传入 `verifySignature`，但**校验为空实现**（恒通过）。防重放窗口待接入时配置。
- ~~FR-002~~ ⭕ **降级**：拒绝分支（403 + JSON 错误体）已实现且路径完整，但当前**不可达**（`verifySignature` 恒 `true`）。
- ~~FR-003~~ ⭕ **降级**：`/internal/**`（回调路径除外）仍受 `InternalServiceAuthInterceptor` 覆盖，但 `verifyServiceToken` 空实现恒定放行；`X-Service-Token` 比对、未配置 `503`、不匹配 `403` 均已移除。
- ~~FR-004~~ ⭕ **部分**：`SignatureVerifier` 保留在 common-core 可跨服务复用（8 个单测覆盖）；`SensitiveDataMasker` ❌ 已删除（ADR-0027）。
- FR-005 ✅ **键全经 env 注入，禁止硬编码/明文日志**（ADR-0026 Accepted）。当前生效的仅 `PAYMENT_ADMIN_TOKEN`（resolve 人工收敛端点，不在本期裁决范围）；渠道密钥与内部令牌待接入验签/鉴权时补回。
- ~~FR-006~~ ⛔ **删除**：最小风控（ADR-0028 不做），`RiskCheckService` 与 `payment.risk.*` 配置均已移除。
- FR-007 ✅ 验签位置约束不变：校验必须发生在业务处理**之前**的过滤器层（当前为空实现，放行时仍走 `CachedBodyHttpServletRequest` 包装，保证 `@RequestBody` 正常反序列化）。

## 5. 验收标准（SC）

- ~~SC-001 合法签名回调被正确处理；非法/缺失/过期/被篡改的签名被 `403` 拒绝。~~
  → ⭕ **改为**：回调端点功能正常；空实现期非法签名**被放行**（测试以 `...IsAllowedWhileSignatureVerificationIsStubbed` 明确断言此行为，避免后人误以为已生效）。
- ~~SC-002 合法 `X-Service-Token` 通过内部端点；非法/缺失被 `403`。~~
  → ⭕ **改为**：`/internal/**` 端点在无令牌时正常可达；回调路径不经鉴权拦截器；拦截器已注册且有测试覆盖。
- ~~SC-003 密钥不在日志/审计中以明文出现。~~
  → ⭕ **改为**：代码中 0 处硬编码密钥；`PAYMENT_ADMIN_TOKEN` 经 env 注入；脱敏工具按 ADR-0027 不做。
- SC-004 ✅ `mvn -o clean verify -fae` 全量 15 个 reactor 条目 BUILD SUCCESS，含 `SignatureVerifierTest`、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`。

## 6. 已知风险（负责人已接受）

1. **伪造渠道回调可翻转支付状态**：`verifySignature` 空实现 → 任何人可 `POST /internal/payments/{id}/channel-callback` 把支付置为 SUCCESS 并触发履约/记账。**部署前置条件：payment-service 不得暴露到公网，仅内网/VPC 可达。**
2. **`/internal/**` 可越权调用**：`verifyServiceToken` 空实现 → 兄弟服务间无身份校验。**部署前置条件：服务网格/安全组做网络层隔离。**
3. **风控缺失**：无单笔/窗口限额观测，异常交易无埋点（ADR-0028）。
4. **日志可能落明文敏感数据**：无脱敏工具（ADR-0027）。

> 上述 4 条在接入真实渠道/生产部署前**必须**先补齐 ADR-0024 / ADR-0025 的实现。
