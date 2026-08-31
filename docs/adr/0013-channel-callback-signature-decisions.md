# ADR 集合：渠道回调验签接入（ADR-0052）

**Feature**：`011-demo-showcase` 前置（收银台演示「签名 / 伪造签名 / 重放」的候选方案）
**日期**：2026-08-31
**状态**：⛔ **Not Implemented（2026-08-31 用户确认回退到 ADR-0025 空实现）** —— 本 ADR 描述的真实验签**不落地**，代码维持 `ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`（占位放行）。
**关联**：`0009-risk-security-decisions.md`（ADR-0025，本 ADR 曾被草稿标为 Supersede 其占位形态，现撤回）、`0012-demo-showcase-decisions.md`（ADR-0048 收银台形态）、`docs/specs/011-demo-showcase/`

---

## ADR-0052 渠道回调验签接入真实现 —— ⛔ 未实施（回退至 ADR-0025 占位）

### Context（背景）

ADR-0025（2026-08-30）决议：渠道回调 HMAC 验签「预留函数、空实现就行」——`ChannelCallbackSignatureFilter.verifySignature` 恒返回 `true`，骨架（路径匹配 / 原始 body 读取 / 可重复读包装 / 403 拒绝分支）保留，`SignatureVerifier`（common-core）工具类留作接入时复用。

本 ADR 曾作为 011 演示的候选，主张：演示需求（收银台现场演示「伪造签名被拒」）构成接入验签的真实触发条件，故将 `verifySignature` 接入 `SignatureVerifier` 真实校验，未配置密钥时放行 + WARN，配置后 fail-closed 403。

### Decision（决策 —— 回退）

**2026-08-31 用户确认：回到 ADR-0025 的空实现形态。** 即：

1. **维持占位放行**：`ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`，回调一律放行；403 拒绝分支保留为骨架但不触发。
2. **不引入 `payment.security.channel-secret` / `signature-replay-window-ms` 配置**：`application.yml` 中相关配置块已移除，`WebConfig` 不再向过滤器注入密钥/重放窗口。
3. **`SignatureVerifier`（common-core）保留**作为接入时的复用工具，但本期不调用。
4. **测试契约维持占位期放行断言**：`ChannelCallbackSecurityTest` 的 `invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed` 等用例继续把「验签未做」钉在测试里，防止有人悄悄实现验签却漏掉拒绝路径（假绿防护不变）。

### 备选方案（原提案，本次未采纳）

| 方案 | 取舍 |
| --- | --- |
| ❌ 接入真实验签（原 ADR-0052 主张） | 能演示「伪造签名被拒」；但违背 2026-08-30 用户裁决「加验签预留函数空实现就行」与「最简实现」原则，故回退 |
| ✅ 维持占位，收银台不演示签名拒绝（本决策） | 忠实于 ADR-0025；收银台只演示回调 / 重复重放 / 状态流转，不演示签名拒绝 |

### Consequences（后果）

- 回调路径**没有**真实的准入防线；伪造回调改变支付状态在当前形态下**可能被接受**（这正是 ADR-0025 占位所接受的已知风险，见 `0009` 集合与 `runbook.md` §6「疑似伪造渠道回调」）。
- 收银台（`mock-channel-web`）的「伪造签名 403」按钮在当前形态下**无效**（点下去依旧放行）——这是 ADR-0025 占位的诚实结果，演示时应明确说明「验签尚未接入」。
- `InternalServiceAuthTest` 的回调用例本就以合法签名发送，无需改动；内部鉴权（ADR-0024 / 0034~0037）仍为占位空实现，不在本 ADR 范围。
- 0009 集合中 ADR-0025 的「实现=预留空函数」形态**继续有效**；本 ADR-0052 仅作为「若将来要接入真实验签」的候选方案存档，落地前需重新立项并由用户裁决。

### 待用户后续裁决（不阻塞本期）

- 是否在某阶段接入真实验签？若接入，需同步补：密钥注入（`PAYMENT_CHANNEL_SECRET`）、重放窗口、fail-closed 测试反转、以及 `runbook.md` 中「疑似伪造渠道回调」的处置从「观测」升级为「拦截」。届时本 ADR 可从 Not Implemented 转为 Proposed/Accepted。
