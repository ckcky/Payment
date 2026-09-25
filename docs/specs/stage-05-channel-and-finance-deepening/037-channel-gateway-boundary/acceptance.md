# Acceptance: 037-channel-gateway-boundary

**对应 Spec**：[spec.md](spec.md)｜**Plan**：[plan.md](plan.md)｜**Tasks**：[tasks.md](tasks.md)
**状态**：待实测回填（T1~T7 完成后填写「实测结论」列）

---

## 验收清单

| # | Success Criterion | 判据 | 实测结论 |
|---|---|---|---|
| SC-001 | 新增渠道改动面 | 只新增插件包 + `application.yml` 一段 + 账本 seed 2 行 + pom 1 个依赖；Payment 域与网关内核零改动 | 待填 |
| SC-002 | 门面收口 | Payment 侧对 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin` 引用数 = 0 | 待填 |
| SC-003 | channelNo 唯一性 | `BusinessNos.of(CHANNEL)` 前缀 `CH`；`channel_no` 有 UNIQUE 索引；10k 并发不重复 | 待填 |
| SC-004 | 回调两层 | 报文翻译在渠道域完成；`PaymentNotifyPort` 收到事件含 `channelCode` 且无渠道私有类型 | 待填 |
| SC-005 | 门禁生效 | FR-016 三条 ArchUnit 规则全绿，阳性对照能触发违规 | 待填 |

## 回归基线

| 模块 | 基线（037 开工前） | 实测 |
|---|---|---|
| payment-service | 367 + 155 + 55 + 28 + 25 全绿 | 待填 |
| common-core | 全绿 | 待填 |
| common-dto | 无测试（纯 DTO） | 待填 |
| ServiceBoundaryTest | 12/12 绿 | 待填 |

> **已知环境噪声**：`AccountingVocabularyBoundaryTest` 会报 `.workbuddy/p3-removed-refund-service/**`
> （ArchUnit 扫全仓 `.java` 未排除点目录）。负责人裁定「不处置」，**不计入本 Feature 失败**。

## 验收命令

```bash
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
```
