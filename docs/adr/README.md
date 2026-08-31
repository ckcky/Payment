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
| [0006](0006-refund-decisions.md) | 退款决策集合（ADR-0016~0018、ADR-0047） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0016 部分退款 ❌ **Rejected（不做，代码已回退）**；0017 / 0018 ✅ **Accepted**；0047 退款金额校验口径 🟡 **Proposed**） | Feature 005；含：**部分退款支持模型(裁决不做·已回退，累计一律按申请额)** / refund→fulfillment 编排(Accepted) / refund→ledger 记账接入(Accepted) / **退款金额校验口径(只做累计不超付，不做全额等值校验——与 001 spec「支持部分退款和多次退款」基线对齐)** |
| [0007](0007-reconciliation-decisions.md) | 对账决策集合（ADR-0019~0021） | ✅ **Accepted**（2026-08-30 裁决 accept） | Feature 006；含：批次差异处理生命周期 / 渠道账单按周期 fixture + 显式回退 / 事实读取 RPC 弹性（不引 Resilience4j） |
| [0008](0008-settlement-decisions.md) | 结算决策集合（ADR-0022~0023） | Proposed（待负责人确认） | Feature 007；含：调整项模型（方向/持久化/门禁/净额公式） / 闸门纵深防御 + settlement→ledger 记账归属与时机 / 幂等键错配行为变更 / N1 商户维度缺口归属 |
| [0009](0009-risk-security-decisions.md) | 风险 / 安全决策集合（ADR-0024~0028） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0024 鉴权 ✅ **方案 Accepted／实现=预留空函数**；0025 验签 ✅ **方案 Accepted／实现=预留空函数**；0026 密钥 ✅ **Accepted（明文）**；0027 脱敏 ⛔ **Not Implemented（不管，类已删）**；0028 风控 ⛔ **Not Implemented（不管，类已删）**） | Feature 009；含：内部服务鉴权(**接入点保留**·`verifyServiceToken` 空实现恒放行，`/internal/**` 恒可达) / 渠道回调 HMAC 验签(**骨架保留**·`verifySignature` 空实现恒通过，`SignatureVerifier` 工具类留作接入时复用) / 密钥明文 env 注入(当前无密钥需注入) / **脱敏(不做，类已删)** / **风控(不做，类已删，不留挂点)** |
| [0010](0010-distributed-evolution-decisions.md) | 分布式演进决策集合（ADR-0029~0033） | **混合**（2026-08-30 裁决：0029 / 0030 / 0032 / 0033 ✅ **Accepted（保持现状）**；0031 异步消息 ⛔ **Not Implemented（不使用 MQ）**） | Feature 010；含：不拆分转而建门禁 / 拆库触发判据(保持现状) / **引入异步消息判据(不做)** / T0~T3 分层 / 提案模板与运行手册作为门禁 |
| [0011](0011-internal-token-decisions.md) | 内部服务令牌闭环（ADR-0034~0037） | ⛔ **Not Implemented（不做，2026-08-30 裁决「出入站鉴权令牌都先不做」，2026-08-31 代码已清理）** | Feature 009 收尾 T013；含：**出站令牌传播范围(不做·代码已删)** / **入站鉴权推广范围(不推广)** / **令牌轮换(不做)** / **鉴权失败可观测(不做)**；`platform.security.*` 配置已移除，将来启用时按本文档与 0009 的 0024 成对实施 |
| [0012](0012-demo-showcase-decisions.md) | 端到端演示形态（ADR-0048~0051） | ✅ **Accepted**（2026-08-31 裁决；**ADR-0048 已修订**——推翻「不做 `mock-channel-web`」，改为**新增收银台组件**，含 payUrl 跳转链路与演示页面） | Feature 011；含：**演示形态(新增 `mock-channel-web`：收银台页+回调签名转发+演示控制台+同源代理)** / **Mock 渠道场景配置化(`payment.channel.mock-scenario`，已落地)** / **对账演示账单(生成 CSV 写入 `target/classes`，不改生产代码)** / **演示脚本纪律(只编排不伪造、断言失败即非零退出)** |
| [0013](0013-channel-callback-signature-decisions.md) | 渠道回调验签接入（ADR-0052） | ⛔ **Not Implemented**（2026-08-31 用户确认回退到 ADR-0025 空实现） | Feature 011 前置候选；**不落地** `SignatureVerifier` 真实校验，代码维持 `ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`（占位放行）；`application.yml` 已移除 `payment.security.*` 配置；`ChannelCallbackSecurityTest` 维持占位期放行断言（`invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed` 等） |
| [0015](0015-wip-ahead-of-roadmap.md) | 库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置（ADR-0053） | **Accepted**（2026-08-31，提交负责人复盘；若否决则回退 013/014 代码） | 偏离 / 处置日志：working tree 含 013-inventory-reservation / 014-seckill-and-cache 实质性实现，**超前顺序、缺 spec/ADR-0041~0046、014 的 Redis 引入未经 roadmap §7 论证闸门**；决策=保留代码（编译+测试通过，且与 011 在 order-service 纠缠不可干净拆分），spec/ADR 补写列为 TODO，待复盘收口 |

## 编号规则

- 编号**只增不改、不复用**；一个 ADR 文档可容纳同一 Feature 的多条决策标签（如 0006 含 0016~0018 与 0047）。
- **下一可用编号：ADR-0054**（ADR-0053 已用于「013/014 超前落地处置」，见 `0015-wip-ahead-of-roadmap.md`）。
- ⚠️ **ADR-0038~0046 为预留号段**，已由 `docs/architecture/next-stage-design.md` §9 分配给下一阶段九项提案（Mock 收银台 / 幂等键签发 / 并发幂等接管 / 库存域归属 / 库存扣减时机 / 超时释放机制 / Redis 引入论证 / Redis 用途边界 / 秒杀限流）。这些编号**尚未写入 ADR 文档**，但在提案落地前不得挪作他用。
  - ⚠️ **注意**：ADR-0038（演示形态）的议题已由 **ADR-0048** 处理；2026-08-31 负责人裁决修订后，结论与原提案一致（**做 `mock-channel-web` 收银台组件**）。0038 号段仍保留不动，避免打乱 §9 的编号对应关系。
- ADR-0047 是预留段之后的第一条实际决策（退款金额校验口径），故跳号——详见 `0006-refund-decisions.md` 头部说明。
- ADR-0048~0051 为 `011-demo-showcase` 的四条决策，见 `0012-demo-showcase-decisions.md`；其中 ADR-0048 已于 2026-08-31 按负责人裁决修订（新增收银台组件）。
- ADR-0052 为渠道回调验签接入候选，见 `0013-channel-callback-signature-decisions.md`（⛔ **Not Implemented**：2026-08-31 用户确认回退到 ADR-0025 空实现，不落地真实验签）。

## 状态机

一条 ADR 的状态按以下路径演进，永不删除，只改状态：

```text
Proposed（提案） → Accepted（已接受/生效） → Superseded（被新 ADR 取代）或 Deprecated（废弃）
```

- **Proposed**：提案讨论中，尚未生效。
- **Accepted**：已批准，是当前权威约束。
- **Rejected**：负责人明确否决，方案不采用；已有实现须回退（回退清单记于该 ADR 内，便于日后重新开放时复原）。
- **Not Implemented**：方案认可但本期不落地；需区分两种形态——
  - **预留空实现**：结构骨架（挂点 / 注册 / 路径匹配）保留，业务判定方法为空实现并留 `TODO(ADR-00xx)`；
  - **不做（代码已删）**：本期完全不实现，相关类与配置一并删除，ADR 保留为「将来要启用时的实施方案」。
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
