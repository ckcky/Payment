# Architecture Decision Records（ADR）

> 记录项目的重要、不可逆架构决策：为什么这么选、备选方案、后果、当前是否仍有效。ADR 永不删除，只通过状态演进。

## 索引

| 编号 | 标题 | 状态 | 关联 |
|---|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md) | 采用 Spring Cloud 微服务架构 | Accepted | 取代 Constitution §3.1；→ 0002 |
| [0002](0002-technology-stack.md) | 技术栈选型 | Accepted | ← 0001 |
| [0003](0003-payment-reliability-decisions.md) | 支付可靠性决策集合（ADR-0003~0007） | 混合 | Feature 003；含：UNKNOWN 收敛触发(Accepted) / 超时进 UNKNOWN(Accepted) / 重试模型(Accepted) / 人工收敛(**Not Implemented，延后 Phase 9**) / 终态冲突(Accepted) |
| [0004](0004-ledger-design-decisions.md) | Ledger 设计决策集合（ADR-0008~0011） | **Accepted**（2026-08-29 确认；0010 已修订） | Feature 004；含：复式记账数据模型(Accepted) / 记账触发与一致性(Accepted) / **金额只用 long 分、不启用 Money VO(Accepted·修订)** / MVP 记账范围(Accepted) |
| [0005](0005-payment-reliability-impl-decisions.md) | 支付可靠性**实现期**决策集合（ADR-0012~0015） | **Accepted**（2026-08-29 确认；0012/0013 已修订） | Feature 003；含：**双响应码错误分类 + 通信失败一律重试(修订)** / **重试不落库、请求内联重试(修订)** / 同 attempt 重放 / UNKNOWN 真实时长度量 / **超时口径 RPC 1s·HTTP 1.5s(新增)** |
| [0006](0006-refund-decisions.md) | 退款决策集合（ADR-0016~0018） | Proposed（待负责人确认） | Feature 005；含：部分退款支持模型 / refund→fulfillment 编排 / refund→ledger 记账接入（与 004 的归属划分） |
| [0007](0007-reconciliation-decisions.md) | 对账决策集合（ADR-0019~0021） | Proposed（待负责人确认） | Feature 006；含：批次差异处理生命周期 / 渠道账单按周期 fixture + 显式回退 / 事实读取 RPC 弹性（不引 Resilience4j） |
| [0008](0008-settlement-decisions.md) | 结算决策集合（ADR-0022~0023） | Proposed（待负责人确认） | Feature 007；含：调整项模型（方向/持久化/门禁/净额公式） / 闸门纵深防御 + settlement→ledger 记账归属与时机 / 幂等键错配行为变更 / N1 商户维度缺口归属 |
| [0009](0009-risk-security-decisions.md) | 风险 / 安全决策集合（ADR-0024~0028） | Proposed（待负责人确认） | Feature 009；含：内部服务鉴权 / 渠道回调 HMAC 验签 + 防重放 / 密钥 env 注入 / 脱敏口径 / 最小风控只观测 |
| [0010](0010-distributed-evolution-decisions.md) | 分布式演进决策集合（ADR-0029~0033） | Proposed（待负责人确认） | Feature 010；含：不拆分转而建门禁 / 拆库触发判据 / 引入异步消息判据 / T0~T3 分层 / 提案模板与运行手册作为门禁 |
| [0011](0011-internal-token-decisions.md) | 内部服务令牌闭环（ADR-0034~0037） | Proposed（待负责人确认） | Feature 009 收尾 T013；含：出站令牌传播范围 / 入站鉴权推广范围 / 令牌轮换 / 鉴权失败可观测 |

## 状态机

一条 ADR 的状态按以下路径演进，永不删除，只改状态：

```text
Proposed（提案） → Accepted（已接受/生效） → Superseded（被新 ADR 取代）或 Deprecated（废弃）
```

- **Proposed**：提案讨论中，尚未生效。
- **Accepted**：已批准，是当前权威约束。
- **Superseded**：被更新的 ADR 取代；新旧两端都必须互相链接（新 ADR 写 `Supersedes`，旧 ADR 写 `Superseded by`）。
- **Deprecated**：决策不再适用但无替代者，保留作为历史。
- 已接受的 ADR 视为不可变：要改变决策就写一条新 ADR 去 supersede 旧的，而不是直接编辑旧文件。

## 何时写 ADR

遇到以下情况之一，写一条 ADR：

- 架构/技术选型（框架、中间件、数据库边界、通信方式）
- 引入或移除一个服务 / 中间件 / 依赖
- 服务边界或数据所有权变化
- 破坏性迁移、安全策略、生产部署策略

涉及 Constitution §8「人类决策边界」的决策，必须先经负责人确认，再落 ADR。
