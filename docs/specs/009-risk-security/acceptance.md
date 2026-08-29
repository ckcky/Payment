# Acceptance: Phase 9 风险 / 安全底座（009-risk-security）

**Feature**: `009-risk-security` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md) | **ADR**: [0009-risk-security-decisions.md](../../adr/0009-risk-security-decisions.md)

> 实现已完成（最简方案），`mvn -o verify -fae` 全量 13 模块 BUILD SUCCESS。功能项全部勾选；**决策验收仍需负责人确认**（Constitution §8）。

## 功能验收

### US1 - 渠道回调必须验签，伪造回调无法翻转支付（Priority: P1）

- [x] `POST /internal/payments/{id}/channel-callback` 校验 `X-Channel-Signature` 与 `X-Channel-Timestamp`（FR-001）
- [x] HMAC-SHA256 验签串为 `timestamp + "." + rawBody`，常数时间比对（FR-001 / ADR-0025）
- [x] 签名合法 → 放行并委托 `PaymentCallbackService.handleCallback`，原始 body 被完整反序列化（`CachedBodyHttpServletRequest` 生效）（SC-001）
- [x] 签名不匹配 → `403`，**`PaymentCallbackService` 一次都没被调用**（FR-002 / SC-001）
- [x] 签名头缺失 → `403`，不调用业务处理（FR-002）
- [x] 时间戳超出防重放窗口（默认 5min）→ `403`，签名本身正确也拒绝（FR-001）
- [x] body 被篡改（用 A 的签名投递 B）→ `403`，不调用业务处理（FR-001 / SC-001）
- [x] 未配置 `PAYMENT_CHANNEL_SECRET` → `503`，**不静默放行**（FR-005 / ADR-0026）
- [x] 拒绝时递增 `payment.callback_signature_rejected`（带 `reason` 维度）并留 WARN 日志
- [x] `SignatureVerifier` 位于 `common-core`，渠道侧与 Mock 可复用同一算法签名（FR-004）

### US2 - 内部端点鉴权（Priority: P1）

- [x] `/internal/**`（回调路径除外）受 `X-Service-Token` 守卫（FR-003 / ADR-0024）
- [x] 合法 token → 正常处理（如 `POST /internal/payments/query-amount` 返回 200）（SC-002）
- [x] token 缺失 → `403`；token 不匹配 → `403`，常数时间比对（SC-002）
- [x] 启用但未配置 `PAYMENT_INTERNAL_TOKEN` → `503`，不静默放行（FR-003）
- [x] 开关关闭（默认 `false`）→ 放行，不破坏既有本地联调与集成测试（FR-003）
- [x] 渠道回调路径不要求内部服务令牌（外部渠道不持有），其安全性由 HMAC 验签独立保证（SC-002）

### US3 - 密钥与敏感数据（Priority: P2）

- [x] 三类密钥（`PAYMENT_ADMIN_TOKEN` / `PAYMENT_INTERNAL_TOKEN` / `PAYMENT_CHANNEL_SECRET`）全部经 env 注入，代码中 0 处硬编码（FR-005 / ADR-0026）
- [x] `SensitiveDataMasker.maskToken` 保留前后各 4 位、中间 `****`；长度 ≤ 8 或 null 整体 `****`（ADR-0027）
- [x] 脱敏结果不含原始密钥片段（单测断言），日志/审计不落明文密钥（SC-003）
- [x] 未引入 Vault / KMS（本期刻意简化，ADR-0026 待确认）

### US4 - 最小风控（Priority: P3）

- [x] `payment.risk` 配置块：`enabled` / `single-max-amount-minor` / `window-limit-count`（FR-006 / ADR-0028）
- [x] 默认 `enabled=false`：全放行，`onPaymentCreated` 返回空命中列表（FR-006）
- [x] 金额 > `single-max-amount-minor` → 命中 `SINGLE_MAX_AMOUNT`；等于上限不命中（FR-006）
- [x] 60s 窗口内笔数 > `window-limit-count` → 命中 `WINDOW_LIMIT_COUNT`（FR-006）
- [x] 命中**只**记录 `payment.risk_triggered` 指标 + 审计 + WARN，**不抛异常、不改变支付状态、不阻断资金主流程**（FR-006 / ADR-0028）
- [x] 不引入规则引擎 / 反欺诈模型（FR-006）

## 非功能验收

- [x] 验签过滤器注册为 `FilterRegistrationBean`，**MockMvc 与生产链路行为一致**（避免「测试全绿、生产裸奔」）
- [x] 未引入 Spring Security / OAuth2 / mTLS / MQ / 2PC（本期最简方案）
- [x] 未改动既有资金主流程语义（支付/退款/对账/结算/记账行为不变）
- [x] `mvn -o verify -fae` 全量 13 模块 BUILD SUCCESS，0 失败 0 错误（SC-004）
- [x] 新增测试：`SignatureVerifierTest`、`SensitiveDataMaskerTest`、`ChannelCallbackSecurityTest`、`InternalServiceAuthTest`、`RiskCheckServiceTest`（SC-004）

## 决策验收（Constitution §8）

- [ ] ADR-0024（内部服务间调用鉴权：共享密钥 API Key / 默认关闭 / 遗留出站令牌拦截器）经负责人确认并置 Accepted
- [ ] ADR-0025（渠道回调 HMAC-SHA256 验签 + 防重放 + 过滤器注册方式）经负责人确认并置 Accepted
- [ ] ADR-0026（密钥 env 注入，不接 Vault）经负责人确认并置 Accepted
- [ ] ADR-0027（脱敏口径与字段清单）经负责人确认并置 Accepted
- [ ] ADR-0028（最小风控只观测不拦截）经负责人确认并置 Accepted
- [ ] 新增入站端点 `POST /internal/payments/{id}/channel-callback` 经确认（§8.4）

## 已知未闭环（不在本期范围，需后续 Feature）

1. **出站内部服务令牌缺失**：`internal-auth-enabled=true` 时调用方必须带 `X-Service-Token`，但 common-core 尚无统一出站令牌拦截器 → 一开就全线 403。已记为 tasks T013。
2. **风控窗口计数为进程内**：多实例部署不精确（ADR-0028 已知简化）。
3. **回调验签仅覆盖 payment-service**：refund / settlement 等暂无入站外部回调面，接入真实渠道时需按同一 `SignatureVerifier` 复用。
