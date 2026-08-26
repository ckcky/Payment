<!--
Sync Impact Report:
- Version: 1.0.0 → 2.0.0（MAJOR：架构原则从「模块化单体」重定义为「Spring Cloud 微服务」）
- 修订：架构原则（单体 → 微服务，见 Core Principle IV）
- 修订：技术栈 Go → Java 21 + Spring Boot 3.x + Spring Cloud + Maven + MyBatis-Plus（见 Engineering Standards）
- 新增：领域职责边界（§2.5）、基础设施决策门槛（§3.4）、扩充的业务观测指标
- 迁移来源：docs/constitution.md（已删除）+ CLAUDE.md 领域细节
- TODO: 无
-->

# PaymentArch Constitution

> Commerce & Payment Platform — 长期有效的工程与架构约束（最高宪法）。
> 任何需求分析、架构设计、编码、测试、Review、重构、部署、故障处理都必须遵守本文。
> 本文用 **MUST**（必须）/ **SHOULD**（应当）/ **MUST NOT**（禁止）表达强制程度。

## Core Principles

### I. Production-Oriented（不是 Demo）

这是一个 **Production-Oriented Learning Project**，不是 Demo、不是玩具、不是一次性脚本。每个实现的功能 MUST 同时满足八性：

1. **业务模型真实（Realistic）** —— 领域模型经得起真实场景拷问（并发、幂等、超时、对账、退款、结算）。
2. **架构合理（Sound）** —— 边界清晰、依赖方向正确、不为了炫技加复杂度。
3. **工程完整（Complete）** —— 有测试、有文档、有可观测、有 CI，而非只有 happy path 的代码。
4. **可运行（Runnable）** —— 一条命令可启动、可验证，不依赖无法复现的环境。
5. **可测试（Testable）** —— 核心逻辑可被单元测试与集成测试覆盖。
6. **可观测（Observable）** —— 核心流程有 metrics / logs / traces，能定位问题。
7. **可部署（Deployable）** —— 有确定的构建与发布方式，配置与代码分离。
8. **可演进（Evolvable）** —— 允许在清晰边界内逐步扩展，而非一次性堆砌。

**首要态度**：宁可**正确地实现一个较小的范围**，也不做**范围大但不真实、不可验证的表面功能**。真实 > 全面。

### II. 资金正确性铁律（Fundamental Money Invariants）

1. 金额一律用**最小货币单位（整数 `long` 分）或 `BigDecimal`（明确 scale）**；全库 **MUST NOT** 用 `float`/`double` 表示或计算金额。
2. 封装 `Money` 值对象（金额 + 币种），禁止裸 `long` 满天飞。
3. 任何资金变动 **MUST** 经 `ledger-service` 复式记账，借贷必须平衡；**MUST NOT** 直接改余额字段。
4. 资金路径（支付、退款、结算）**MUST** 具备幂等性（见 Core Principle V）。

### III. 领域边界（Domain Boundaries）

系统由以下领域构成，每个领域有**独立的模型、状态机与数据所有权**：

`Merchant` · `Product/SKU` · `Order` · `Payment` · `Channel` · `Refund` · `Fulfillment` · `Entitlement` · `Reconciliation` · `Settlement` · `Ledger`（资金账本）

**核心业务链路**：商品 → 订单 → 交易 → 支付 → 支付渠道 → 支付成功 → 履约 → 权益发放 → 用户消费
**资金链路**：支付 → 对账 → 结算
**退款链路**：退款申请 → 支付退款 → 履约/权益处理 → 对账

**六条关键边界（必须时刻区分）**：

| # | 区分 | 含义 |
|---|---|---|
| 1 | **Order ≠ Payment** | Order 是商业意图（买什么、多少钱、谁买），Payment 是资金动作（钱如何被收取）。二者有独立生命周期与状态机；订单金额、已支付金额、已退款金额是三个不同字段。 |
| 2 | **Payment ≠ Channel** | Payment 是领域编排层（支付意图、金额、币种、状态、幂等），Channel 是对外支付提供方的技术适配（协议、签名、回调）。Payment 依赖 **Channel 接口抽象**，**MUST NOT** 依赖具体渠道实现。 |
| 3 | **Payment Success ≠ Entitlement Granted** | 支付成功是财务事件，权益授予是消费权利，不同概念、不同生命周期。支付成功**触发**权益授予，但权益也可因试用/赠送授予、可独立撤销、有有效期。**MUST NOT** 把「已支付」等价为「有权益」。 |
| 4 | **Reconciliation ≠ Settlement** | Reconciliation 是对账（比对两本账找差异，校验/审计），Settlement 是结算（资金实际划转）。对账周期 ≠ 结算周期（如 T+1），二者 MUST 解耦。 |
| 5 | **Refund ≠ Payment Refund** | Refund 是领域级退款决策记录，是跨多领域的编排：渠道退款（资金）、权益撤销（权利）、账本冲正（会计）、对账调整（校验）。**MUST NOT** 实现成「调用渠道退款接口」一句话。 |
| 6 | **Fulfillment 不强耦合 Payment** | Fulfillment 是履约交付（发货/数字交付），有自己的状态机。可被支付成功触发，但 **MUST NOT** 依赖支付内部实现，也不被支付状态反向阻塞。二者通过事件/编排解耦。 |

**各领域职责（负责 / 不负责）**：

- **Order**：负责用户购买什么、订单商品、订单金额、订单生命周期与状态；不负责支付渠道细节、第三方支付协议、用户权益发放。
- **Transaction**：负责商业交易生命周期、订单与支付之间的交易关系、交易状态；Transaction ≠ Payment（在 ADR-0001 中与订单同归 order-service 状态机）。
- **Payment**：负责支付生命周期、支付尝试、支付状态、支付结果、支付幂等；不包含具体渠道实现细节。
- **Channel**：负责与第三方支付机构通信、渠道协议、请求/响应转换、渠道回调、渠道特有错误处理；渠道差异必须被隔离，核心 Payment 不依赖具体渠道实现。
- **Refund**：负责退款申请、退款生命周期、退款金额、退款状态、退款执行；还必须考虑订单状态、履约状态、权益回收、对账、结算。
- **Fulfillment**：负责商品交付、服务交付、权益发放、履约状态；支付成功不应依赖同步完成权益发放。
- **Entitlement**：负责会员权益、额度、Credits、License、虚拟商品、权益生命周期；Payment 不直接改 Entitlement 内部状态，推荐链路：Payment Success → Payment Event → Fulfillment → Entitlement。
- **Reconciliation**：负责内部账务与渠道账单比较、差异发现/记录/处理；Reconciliation ≠ Settlement。
- **Settlement**：负责商户结算、结算金额计算、结算批次、结算状态与结果；结算基于已确认的财务数据。

**领域依赖方向（核心约束）**：

- 领域依赖 MUST **单向、向内**：编排层（Order/Payment/Refund）可依赖底层领域，底层领域 **MUST NOT** 反向依赖编排层。
- `Ledger` 是被依赖方，不依赖任何其他业务领域；`Channel` 只依赖外部协议，不依赖业务领域。

### IV. 架构边界（Architecture：Spring Cloud 微服务）

**总体架构**：Spring Cloud 微服务（见 ADR-0001）。按 **Bounded Context** 划分服务（一个领域上下文一个服务），每个服务拥有独立的数据逻辑边界；在单机部署阶段允许多个服务使用同一物理数据库，但必须使用独立 Schema，禁止跨服务访问或修改他服务的数据。跨服务默认通过同步 API/RPC 交互，后置流程也通过明确的 HTTP/RPC 用例触发；不以 MQ 或跨服务异步事件作为当前默认方案。分布式一致性用 Saga + RPC + 幂等，**禁止** 2PC/XA 分布式事务（见 Core Principle V）。

服务清单（详见 ADR-0001）：`gateway`、`merchant-service`、`catalog-service`、`order-service`、`payment-service`（含 Channel 适配）、`refund-service`、`fulfillment-service`、`entitlement-service`、`ledger-service`、`reconciliation-service`、`settlement-service`。

**六条架构边界**：

1. **Domain Boundary**：领域间只通过对方暴露的领域服务接口/应用服务交互，不共享表、不共享仓储实现。
2. **Module Boundary**：每个服务按 `com.payment.<service>` 分包，对外只暴露最小 API，内部结构不泄露。
3. **Dependency Direction**：依赖指向内层/稳定层，禁止循环依赖（Maven 多模块 + lint 强制）。
4. **Data Ownership**：每个服务**独占**自己的表；其他服务读写该数据 MUST 通过该服务的 API，**MUST NOT** 直接 SQL 其他服务的表。
5. **API Boundary**：对外 HTTP API 与领域模型分离（`api → application → domain ← infra` 分层），API 不直接暴露数据库实体。
6. **RPC Boundary**：跨服务通过对方公开的 API/RPC 用例交互，不通过共享状态或直接调用内部实现。领域事件可以在服务内部使用，但当前不作为跨服务通信机制。

**禁止清单（MUST NOT）**：

- ❌ 跨服务直接修改其他服务数据（直接 SQL 他服务表）。
- ❌ 核心领域（Payment/Order/Ledger）依赖具体渠道实现。
- ❌ 为技术炫技过度拆分（CQRS、Event Sourcing、DDD 全套仪式，除非业务真实需要）。
- ❌ 无理由新增微服务或中间件（服务边界已由 ADR-0001 固定，新增须立 ADR）。
- ❌ 引入 2PC/XA 分布式事务（用 Saga + RPC + 幂等替代）。
- ❌ 为体现复杂度引入中间件（Kafka/Redis/MQ/ES 等，除非对应阶段有真实需要且经 ADR 论证）。

**引入基础设施的决策门槛**：不为了分布式而分布式。引入任何基础设施/中间件前 MUST 回答：①解决什么问题？②为什么当前方案解决不了？③引入后有什么收益？④引入后有什么成本？⑤是否值得长期维护？答不出或答不全，视为「为了炫技」，禁止引入。

### V. 一致性（Consistency）

支付系统的一致性必须显式设计，以下每一项都是核心流程的**必答题**：

1. **Idempotency（幂等）**：支付、退款、结算等资金入口 MUST 有幂等键；相同幂等键的重复请求 MUST NOT 产生重复资金动作。幂等键由调用方提供，服务端持久化并唯一约束。
2. **State Machine（状态机）**：Order / Payment / Refund / Fulfillment / Entitlement / Settlement 都 MUST 有**显式、单向**的状态机。禁止非法状态跳转；状态流转 MUST 通过集中状态转换函数，禁止散落直接 set 状态。
3. **Eventual Consistency（最终一致）**：与外部系统（渠道、网关）的交互采用最终一致；单服务内部状态变更用本地事务保证原子；跨服务通过同步 RPC 编排和幂等重试实现最终一致，暂不引入 MQ 或跨服务异步事件。三者分层，不可混淆。
4. **Retry（重试）**：对幂等的外部调用才允许自动重试，重试 MUST 有退避与上限；非幂等调用禁止盲目重试。
5. **Duplicate Message / Callback（重复消息/回调）**：消费/处理侧 MUST 假设消息与回调会重复到达，靠幂等键 + 状态机幂等吸收，不重复入账。
6. **Timeout（超时）**：所有外部调用 MUST 有超时；超时**不等于失败或成功**，需进入「未知状态」处理（见下条）。
7. **Unknown Payment Status（支付状态未知）**：结果不确定时 **MUST NOT** 猜成败直接落账，进入 UNKNOWN/PENDING 状态，靠查询接口 / 对账 / 人工介入收敛。这是支付系统最核心的正确性保障。

## Engineering Standards

1. **Code Quality**：Checkstyle + Spotless（CI 强制）；命名遵循 Java 惯例，包名 `com.payment.<service>.<layer>`；错误显式传递，业务错误（`BizException`）与系统错误（`SystemException`）分离；全局异常处理器兜底。
2. **分层**：`api → application → domain ← infra`，依赖单向；`domain` 不依赖任何框架层；DTO / Entity 分离，跨服务只传 DTO / 事件。
3. **Testing**：JUnit 5 + Mockito + AssertJ；集成测试用 Testcontainers。资金逻辑 MUST 有测试；表驱动测试优先；关键路径（支付成功/失败/超时/重复回调/渠道失败/服务重启/最终一致）有集成测试。**MUST NOT** 删测试或改测试迎合错误实现。
4. **Documentation & ADR**：不可逆/重要决策 MUST 立 ADR（`docs/adr/NNNN-*.md`）；领域需求以 Spec（`docs/specs/<feature>/spec.md`）为单一事实源。
5. **CI/CD**：Maven Wrapper（mvnw）锁定版本；`mvnw verify`（compile+test）→ lint → 打包；配置与代码分离（Nacos / 环境变量）；Conventional Commits + 功能分支 + PR Review。
6. **Dependency Management**：父 POM + `dependencyManagement` 统一版本（Spring Boot BOM + Spring Cloud BOM）；最小化依赖，每个新依赖 MUST 有理由（ADR 或 commit）。
7. **Backward Compatibility**：已发布的 API / 跨服务接口 / 对外 schema 变更 MUST 向后兼容或提供迁移路径；破坏性变更 MUST 经人类确认（见 Governance）。

## Observability

所有核心业务流程 MUST 具备：

1. **Metrics**：Micrometer 暴露请求量、延迟、错误率、关键业务计数。核心业务指标至少覆盖：支付（成功率/失败率/超时率/耗时/渠道成功率/渠道耗时）、退款（成功率/失败率/耗时）、履约（成功率/失败率/权益发放失败率）、对账（差异数量/差异金额）、结算（成功率/失败数量）。
2. **Logs**：结构化日志（logback，含 traceId、orderId、paymentId 关联字段）；**资金动作 MUST 有审计日志**；敏感信息（卡号、密钥）脱敏。
3. **Traces**：Micrometer Tracing 跨服务调用传播 traceId/spanId；初期不强行上分布式追踪基础设施。
4. **Business Alerts**：对「支付状态未知堆积」「对账差异」「退款失败」「重试耗尽」等业务异常 MUST 有告警，而非只告警基础设施。
5. **SLO**：为核心接口定义目标（可用性、P99 延迟、对账达成率），有错误预算意识。

**约束**：可观测性 MUST 用轻量方案起步（Micrometer + 结构化日志 + 计数器），不得为 Trace 提前上重基础设施——「可观测」优先于「可观测工具链的复杂度」。

## Security

1. 密钥、签名、金额等**禁止硬编码**，走环境变量 / 配置中心 / 密钥管理。
2. 渠道回调 MUST 验证签名与来源，防止伪造回调。
3. 对外 API 有鉴权（Spring Security / OAuth2）与输入校验（Bean Validation）。
4. 敏感信息（卡号、密钥）进日志前脱敏。

## AI Development

AI Agent 在项目内的一切工作，除遵守本文其他条款外，还 MUST 遵守：

1. **不得绕过架构规则**：不得通过「方便」的方式突破领域边界、依赖方向、数据所有权。
2. **不得修改无关代码**：一次改动只做一件事；重构若必须跨文件，先立 Plan/ADR 说明范围并经同意，禁止静默顺带改。
3. **不得删除测试来解决失败**：失败必须被理解并修复，不得删测试使其消失。
4. **不得通过修改测试迎合错误实现**：不得把断言改松/改对来让错误实现通过；只有测试本身错误时才可改测试，且需说明理由。
5. **不得擅自改变领域模型 / 核心架构**：领域模型、状态机、服务边界的变更 MUST 先提出方案并经确认（见 Governance）。
6. **重大架构变化必须先提出方案**：先写 ADR/Plan，评审通过后再实现。
7. **需求不明确时必须先澄清**：遇到歧义先问，不得自行臆测并实现（尤其资金路径）。
8. **实现前必须理解现有代码**：先读相关代码与 Spec/ADR，理解现状与约定，禁止基于假设重写。
9. **完成功能后必须测试和 Review**：实现 MUST 附带测试，并对照 Spec 回检缺口。

## Governance

### 人类决策边界（Human Decision Boundary）

以下决策 **MUST** 由人类确认，AI Agent 只能提出方案，不得自行执行：

1. **Domain Boundary**：领域边界的划分与调整。
2. **Major Architecture Change**：重大架构变化（如新增/合并服务、引入新中间件、更换数据层）。
3. **Database Schema Migration**：对既有数据的破坏性/不可逆 schema 迁移，或新增关键资金表。
4. **API Breaking Change**：已发布 API 契约、跨服务接口的破坏性变更。
5. **Security Policy**：安全策略（认证授权、密钥管理、签名校验、脱敏规则）。
6. **Production Deployment Strategy**：生产部署策略（发布方式、回滚、灰度、环境配置）。
7. **Data Migration**：任何生产数据迁移/回填。
8. **Payment State Machine Change**：支付状态机及其状态流转规则的变更。

### 冲突优先级（Conflicts & Precedence）

1. **资金正确性 > 一切**：正确性永远高于性能、简洁、进度。
2. **简单性 > 过早演进**：演进是能力不是现在的复杂度。
3. **已发布契约的向后兼容 > 内部重构自由**：契约对外前允许破坏性变更（须记 ADR），对外后才上升为硬约束。
4. **内部强一致 / 外部最终一致**：二者分层不矛盾（内部本地事务，跨外部系统最终一致 + 对账兜底）。
5. **可观测性 > 工具链复杂度**：先满足「能观测」，再考虑「用多重的工具」。

冲突无法用以上优先级解决时，提交人类裁决。

### 修订流程（Amendment）

1. 任何修订以 PR/提案提出，说明动机、变更点、影响范围。
2. 涉及「人类决策边界」所列决策的修订 MUST 人类批准。
3. 修订后按语义化版本递增版本号：MAJOR（原则删除/重定义）、MINOR（新增原则/扩展）、PATCH（措辞/澄清）。
4. 更新版本行并记录修订历史。

**Version**: 2.0.0 | **Ratified**: 2026-08-26 | **Last Amended**: 2026-08-26
