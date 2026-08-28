# Architecture Decision Records（ADR）

> 记录项目的重要、不可逆架构决策：为什么这么选、备选方案、后果、当前是否仍有效。ADR 永不删除，只通过状态演进。

## 索引

| 编号 | 标题 | 状态 | 关联 |
|---|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md) | 采用 Spring Cloud 微服务架构 | Accepted | 取代 Constitution §3.1；→ 0002 |
| [0002](0002-technology-stack.md) | 技术栈选型 | Accepted | ← 0001 |
| [0003](0003-payment-reliability-decisions.md) | 支付可靠性决策集合（ADR-0003~0007） | 混合 | Feature 003；含：UNKNOWN 收敛触发(Accepted) / 超时进 UNKNOWN(Accepted) / 重试模型(Accepted) / 人工收敛(**Not Implemented，延后 Phase 9**) / 终态冲突(Accepted) |
| [0004](0004-ledger-design-decisions.md) | Ledger 设计决策集合（ADR-0008~0011） | 混合（全 Proposed） | Feature 004；含：复式记账数据模型(Proposed) / 记账触发与一致性(Proposed) / 金额表示 Money VO(Proposed) / MVP 记账范围(Proposed) — **待负责人确认** |
| [0005](0005-payment-reliability-impl-decisions.md) | 支付可靠性**实现期**决策集合（ADR-0012~0015） | Proposed（**已按最简方式生效运行，待确认**） | Feature 003；含：重试错误分类与 UNKNOWN 不重试 / 重试调度载体（字段最小化） / 同 attempt 重放 / UNKNOWN 真实时长度量 — 若被否决需回滚对应实现 |

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
