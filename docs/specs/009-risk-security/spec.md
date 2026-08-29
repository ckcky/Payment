# Spec: 009-risk-security（风险 / 安全底座）

**版本**：0.1
**日期**：2026-08-29
**状态**：Proposed（代码按最简实现已落地，ADR-0024~0028 待负责人确认）

## 1. 背景与目标

主线 001–007 已交付资金业务闭环，但安全底座不完整：
- 入站渠道回调**未校验签名**（伪造回调可翻转支付成功，最高危）。
- `/internal/**` 内部端点（退款、对账）**无鉴权**（跨服务越权面）。
- 密钥、脱敏、风控缺乏统一约定。

本 Feature 补齐 Phase 9 的**最小可行安全底座**，遵循 Constitution §9（安全）与 Technical-Solution「渠道回调 MUST 验签」。

## 2. 范围

**包含（MVP）**
- 入站渠道回调 HMAC-SHA256 签名校验 + 防重放（ADR-0025）。
- 内部服务间调用 `X-Service-Token` 鉴权，覆盖全部 `/internal/**`（ADR-0024）。
- `SignatureVerifier` / `SensitiveDataMasker` 通用工具（common-core，ADR-0025/0027）。
- 最小风控配置占位（默认放行，ADR-0028）。

**不包含**
- 完整 OAuth2 / mTLS / 密钥管理服务（ADR 留待演进）。
- 复杂风控规则引擎、反欺诈模型。

## 3. 关键用户故事

- **US1 渠道回调验签**：渠道回调 MUST 携带签名与时间戳；验签失败/过期/缺失/被篡改 → 拒绝，绝不触发支付状态变更。
- **US2 内部端点鉴权**：跨服务 `/internal/**` 调用 MUST 携带合法 `X-Service-Token`；非法/缺失 → 拒绝。
- **US3 敏感脱敏**：密钥等敏感字段在日志/审计中 MUST 脱敏。
- **US4 最小风控**：支付受理时可配两条阈值规则；命中只记录指标与审计，**不阻断**资金主流程。

## 4. 功能需求（FR）

- FR-001 回调端点 `POST /internal/payments/{id}/channel-callback` 校验 `X-Channel-Signature`（HMAC-SHA256）与 `X-Channel-Timestamp`，防重放窗口可配（默认 5min）。
- FR-002 验签/防重放失败 → `403`，不调用 `PaymentCallbackService`。
- FR-003 `/internal/**`（回调路径除外）受 `X-Service-Token` 鉴权；可配置开关（默认关，生产开）；启用未配 token → `503`。
- FR-004 `SignatureVerifier`、`SensitiveDataMasker` 置于 common-core，可跨服务复用。
- FR-005 密钥经 env 注入，禁止硬编码/明文日志（ADR-0026）。
- FR-006 最小风控：可配 `enabled` / `single-max-amount-minor` / `window-limit-count`，默认关闭；命中仅记 `payment.risk_triggered` 指标 + 审计，不改资金主流程、不抛异常。
- FR-007 验签必须发生在业务处理之前：签名校验失败时 `PaymentCallbackService` 一次都不被调用（由单元测试直接观测，而非靠支付状态间接推断）。

## 5. 验收标准（SC）

- SC-001 合法签名回调被正确处理；非法/缺失/过期/被篡改的签名被 `403` 拒绝。
- SC-002 合法 `X-Service-Token` 通过内部端点；非法/缺失被 `403`。
- SC-003 密钥不在日志/审计中以明文出现。
- SC-004 `mvn verify` 全量通过，含回调验签、内部鉴权与风控测试。
