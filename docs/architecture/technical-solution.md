# PaymentArch 技术方案

**状态**：已确认，作为当前实现基线（综合性总体技术方案，指导后续所有 Feature）

**生效日期**：2026-08-26

**最近修订**：2026-08-31（2026-08-30 负责人裁决的**落地同步**：鉴权 / 验签 = 预留空函数；出站令牌 / 风控 / 脱敏 = 代码已删除；部分退款 = 代码已回退；退款金额校验口径新增 ADR-0047 —— 见 §2.4、§4.3.3、§8.3、§10）

**关联决策**：[ADR-0001](../adr/0001-adopt-spring-cloud-microservices.md)、[ADR-0002](../adr/0002-technology-stack.md)、[ADR-0006](../adr/0006-refund-decisions.md)、[ADR-0009](../adr/0009-risk-security-decisions.md)、[ADR-0011](../adr/0011-internal-token-decisions.md)

**权威来源**：本文是 Constitution（最高约束）、ADR（决策日志）、Spec 001（业务模型）在「当前系统」层面的**落地化综合**。本文不得与 [Constitution](../../.specify/memory/constitution.md) 冲突；若需调整领域边界、服务边界、状态机或数据层，属于 Constitution §8 人类决策边界，须另立 ADR / 提案并经人类确认。

> 本文是「全局」视角的技术方案。各子系统的字段级、状态机级、接口级细节见 [systems/](systems/) 下对应文档。

---

## 1. 背景与现状

### 1.1 项目定位

PaymentArch 是一个 **Production-Oriented 的 Commerce & Payment Platform**（Java / Spring Cloud 微服务），用于学习并实践支付、交易、履约、权益、对账、结算体系与高质量后端工程。**不是 CRUD Demo、不是玩具脚本。**

落地到工程，任何功能须同时满足八性（Constitution §1）：业务模型真实（Realistic）、架构合理（Sound）、工程完整（Complete）、可运行（Runnable）、可测试（Testable）、可观测（Observable）、可部署（Deployable）、可演进（Evolvable）。

### 1.2 当前现状（Phase 0 — Foundation）

- 架构基线已确认：**Spring Cloud 微服务**，按限界上下文划分（ADR-0001），技术栈 Java 21 + Spring Boot 3.x + MyBatis-Plus + Nacos + OpenFeign（ADR-0002）。
- 已建 **10 个服务模块 + 3 个共享库**（见 §3），`gateway` 不在本 MVP 范围；`ledger-service`（8090）已按 `004-ledger` 实现并接入 payment/refund/settlement 三处复式记账（ADR-0008~0011 已于 2026-08-29 Accepted）。
- 根 Maven 工程 `validate` 已通过；各服务有启动类与上下文测试，部分服务已有领域/应用/契约/集成测试。
- 当前 Feature `001-core-business-model` 已有 Spec/Plan/Tasks；业务主链路（下单→支付→回调/收敛→履约→权益）与资金闭环（对账→结算）**均已落地**，Ledger 复式记账已接入，形成完整业务闭环（roadmap 主链走至 `014-seckill-and-cache`）。
- **尚未引入**：真实支付渠道（当前 Mock Channel）、MQ / 跨服务异步事件、API 网关、熔断组件、K8s/服务网格（Ledger 复式记账已按 `004-ledger` 前置实现）。

---

## 2. 目标

### 2.1 总目标

建立一个可运行、可测试、可观测、可部署、可演进的**真实支付/交易/履约/对账/结算技术基线**，在清晰边界内逐步扩展，而非一次性堆砌。

**首要态度**：宁可**正确地实现一个较小的范围**，也不做**范围大但不真实、不可验证的表面功能** —— 真实 > 全面。

### 2.2 最高优先级

**资金正确性 > 一切**：幂等、复式记账、未知状态不猜成败。其他优先级与冲突裁决见 Constitution §10。

### 2.3 非目标（MVP 明确不做）

- 不接真实支付机构、不做真实出款/入账（当前仅 Mock Channel + 模拟业务事实）。
- （注：Ledger 复式记账已按 `004-ledger` 前置实现，结算侧记账由 `007-settlement` 承接，详见 §4.3.5；本 MVP 仍不接真实出款/银行。）
- 不引入 MQ / Kafka / ES / K8s / Service Mesh / 2PC-XA（除非对应阶段有真实需要且经 ADR 论证）。**例外：Redis 已随 `014-seckill-and-cache` 引入（ADR-0044/0045），仅用于入口幂等 / SKU 缓存 / 秒杀预扣 / 超时时间轮，非数据源**；熔断组件（Resilience4j）在 payment-service 已引入并保留（2026-09-04 裁决），缺独立 ADR（见 backlog #5），对账侧按 ADR-0021 明确不引入。
- 不做多币种清分、税费、复杂分账、多级商户、复杂风控平台。

**本阶段范围裁剪（2026-08-30 负责人裁决）**：以下能力**明确不做**，只保留预留挂点，落地形态与启用条件见 §2.4：

- 不做**出站内部服务令牌**（`X-Service-Token` 传播）—— 代码已清理（ADR-0034）。
- ⭕ **入站内部鉴权**（`/internal/**` 守卫）只**预留空函数**、恒放行，不推广到其余服务（ADR-0024 / 0035）。
- 不做**对外 API 鉴权 / 身份体系**（Spring Security、OAuth2、用户态 API Key）—— 预留空实现函数。
- 不做**部分退款**—— 本期只支持**全额退款**，代码已回退（§4.3.3 / ADR-0016）。
- 不做**敏感数据脱敏治理**—— 类与配置一并删除（ADR-0027）。
- 不做**风控规则与判定**—— `RiskCheckService` 已删除，不留挂点（ADR-0028）。
- ⭕ **渠道回调验签**只**预留空函数**、恒通过（ADR-0025）。

---

## 2.4 本阶段范围裁剪与预留契约

> **本节是 2026-08-30 负责人裁决的落地口径**，裁决范围涵盖鉴权、内部令牌、部分退款、敏感数据、风控五类能力。相关 ADR（[0006](../adr/0006-refund-decisions.md) / [0009](../adr/0009-risk-security-decisions.md) / [0011](../adr/0011-internal-token-decisions.md)）中对应条目以本节裁决为准，状态标注见 [ADR 索引](../adr/README.md)。

### 2.4.1 裁决总表

| # | 能力 | 裁决 | 落地形态 | 代码现状（预留位置） | 启用条件 / 后续路径 |
|---|---|---|---|---|---|
| 1 | 出站内部令牌（`X-Service-Token` 传播） | ⛔ **不做（代码已删除）** | 整条链路**已清理**：拦截器、Feign 自动配置、`platform.security.*` 配置全部移除，调用链零改动 | 无（原 `common-core/client/InternalTokenRequestInterceptor.java` 与 `config/FeignInternalTokenAutoConfiguration.java` 已删除） | 需要服务间网络边界防护时**重新立项**（ADR-0034） |
| 2 | 入站内部鉴权（`/internal/**` 守卫） | ⭕ **预留空函数** | 拦截器保留并仍挂载在 `/internal/**`，`verifyServiceToken()` **恒放行**；无开关（开关语义随实现一并移除）；**不推广**到其余 7 个服务 | `payment-service/web/InternalServiceAuthInterceptor.java`（唯一接入点）；`WebConfig` 注册保留 | 接入时只改 `verifyServiceToken()`，**且必须同时**补出站令牌，否则调用方全线 `403`（ADR-0024 / 0034 / 0035） |
| 3 | 对外 API 鉴权 / 身份体系 | ⭕ **预留空实现鉴权函数** | 不接入 Spring Security / OAuth2 | `payment-service/web/ResolveAuthorizationInterceptor.java`（admin 端点守卫，默认关闭）；鉴权扩展点见 §2.4.2 | 引入外部调用方 / 多租户时立项（Phase 9） |
| 4 | 风控 | ⛔ **不做（代码已删除）** | 原「只预留空实现」裁决已被 2026-08-30 裁决**覆盖为不做**：`RiskCheckService` 类、`payment.risk.*` 配置、`PaymentApplicationService` 调用点全部移除 | 无（原 `payment-service/application/risk/RiskCheckService.java` 已删除） | 需风控时**重新立项**（ADR-0028） |
| 5 | 敏感数据脱敏 | ⛔ **不做（代码已删除）** | 原「工具类保留」裁决已被 2026-08-30 裁决**覆盖为不管**：`SensitiveDataMasker` 类与测试移除；不新增脱敏点、不做响应层统一脱敏 | 无（原 `common-core/security/SensitiveDataMasker.java` 已删除） | 接入真实卡号 / 凭证 / 真实渠道时**重新引入**（ADR-0027） |
| 6 | 部分退款（单笔退款的部分成功追踪） | ⛔ **不做（代码已回退）** | **单笔退款只回三态**（`SUCCEEDED`/`FAILED`/`UNKNOWN`），成功恒为全额；`PARTIALLY_SUCCEEDED` 枚举与 `partiallySucceed()` 保留为**不可达的预留实现**，不开放任何入口。实体 `refundedAmountMinor` 字段与 DDL 列**已回退删除**。**同一支付的多笔退款仍支持**（每笔独立幂等键、按申请额累计占用额度，ADR-0047） | `payment-service/src/main/java/com/payment/refund/domain/RefundStatus.java`（枚举保留，Feature 015 起退款域在 payment-service 内）；`RefundApplicationService.java:99-103` 当前只处理 `SUCCEEDED/FAILED/UNKNOWN`；回退清单见 [ADR-0016](../adr/0006-refund-decisions.md#adr-0016-部分退款支持模型如何让-partially_succeeded-可达部分金额如何跟踪)、口径收口见 [ADR-0047](../adr/0006-refund-decisions.md#adr-0047-退款金额校验口径adr-0016-回退后是否强制申请额--可退全额) | 需支持「单笔退款部分成功」时立项，届时须同步改状态机与退款单模型（属 Constitution §8 边界） |
| 7 | 渠道回调验签（HMAC） | ⭕ **预留空函数** | **过滤器骨架保留**（路径 Ant 匹配、原始 body 读、`CachedBodyHttpServletRequest` 可重复读包装、403 拒绝分支），`verifySignature()` **恒通过**，回调一律放行 | `common-core/security/SignatureVerifier.java`（算法工具类保留，8 单测覆盖）；`payment-service/web/ChannelCallbackSignatureFilter.java`（空实现接入点） | 接入真实渠道时只实现 `verifySignature()`（ADR-0025） |

> **关于 #7 的补充说明（2026-08-30 裁决已覆盖原边界说明）**：负责人裁决**明确包含渠道回调验签**——「ADR-0025 加验签预留函数空实现就行」。因此原「验签不在裁剪范围、默认开启不可关闭」的表述已失效。当前**伪造渠道回调可翻转支付状态**，这是**已知且已被负责人接受的风险**。
>
> **部署前置条件**：payment-service **不得暴露到公网**，仅内网 / VPC 可达；`/internal/**` 同理依赖安全组 / 服务网格做网络层隔离。上述两条与 #2 / #7 的空实现直接相关，接入真实渠道或生产部署前必须先补齐实现。

### 2.4.2 预留契约（空实现的硬性约定）

凡标注「⭕ 预留空实现」的能力，其实现 MUST 满足以下四条，防止占位代码演化为隐性故障源：

1. **纯 no-op，不干扰主流程**：不抛异常、不修改任何状态、不改变资金主链路的返回结果。调用方无需感知其存在。
2. **不得留下"假绿"**：空实现必须在**测试与命名上显式暴露**——测试方法名带 `...IsAllowedWhileXxxIsStubbed`，Javadoc 标明「本期为空实现」，方法内留 `TODO(ADR-00xx)`。禁止让后人误以为校验已生效。
3. **失败不静默、也不阻断**：预留点自身出错时，只记录日志/指标，**不得**导致业务主流程失败；同时不得吞掉业务异常。
4. **挂点位置即契约**：预留点必须挂在**明确、单一**的位置，后续启用时不得散落到业务代码各处。当前挂点：
   - 入站内部鉴权 → `payment-service/web/InternalServiceAuthInterceptor#verifyServiceToken`（MVC 拦截器层，覆盖 `/internal/**`）
   - 渠道回调验签 → `payment-service/web/ChannelCallbackSignatureFilter#verifySignature`（Servlet 过滤器层，业务 Controller 之前）
   - 对外 API 鉴权 → `payment-service/web/ResolveAuthorizationInterceptor`（admin 端点，默认关闭）

> **关于「显式开关」的调整**：2026-08-30 裁决后，鉴权与验签的空实现**不再带开关**（`payment.security.internal-auth-enabled` / `platform.security.outbound-token-enabled` 已随实现一并移除）。理由：恒放行的空实现配一个开关只会造成「开关已开但没生效」的错觉。将来接入真实实现时，由实现方自行决定是否需要开关与灰度策略（建议保留，见 ADR-0024 / 0025）。

---

## 3. 总体架构设计

### 3.1 分层架构

![PaymentArch 系统架构分层](diagrams/01-system-architecture.svg)

> 该图的 PlantUML 源码：[diagrams/01-system-architecture.puml](diagrams/01-system-architecture.puml)（供 AI 阅读与后续编辑，改动后需重新渲染为 SVG）

- **接入层**：`gateway` 作为统一入口/鉴权/限流，本 MVP **延后**（虚线）；当前调用方直连各服务暴露的 REST。
- **编排层**：order / payment / refund 承接业务意图并编排跨域流程（§3.2），可调用下游；独立进程、独立端口、独立部署单元。
- **执行层**：catalog / fulfillment / entitlement 自持状态机，**不得反向依赖编排层**。
- **资金层**：ledger / reconciliation / settlement / merchant 承载账务事实、核对与结算。其中 `ledger-service` 已按 `004-ledger` **前置实现**（原定 Roadmap Phase 8），只被依赖、不调用任何业务服务。
- **数据层**：MySQL 8.0，Database-per-Service 的**访问边界**（§3.4）；Nacos（注册 + 配置）为 `[目标]`，生产启用前本地直连。

> **注**：reconciliation / settlement 对 payment / refund 的依赖是**只读事实抽取**（读已确认业务事实，不回写、不修改），不构成对编排层的反向业务依赖，不违反 §3.4 的单向依赖原则。

### 3.2 核心模块职责

| 服务 | 负责领域 | 核心职责 | 状态 |
|---|---|---|---|
| gateway | 接入层 | 统一入口、路由、鉴权、限流 | 延后 |
| merchant-service | Merchant | 商户注册、资质、结算账户 | 已实现 |
| catalog-service | Product / SKU | 商品、SKU、价格、可售性 | 已实现 |
| order-service | Order / Transaction | 订单、明细、价格快照、交易状态机 | 已实现 |
| payment-service | Payment + Channel | 支付编排、幂等、渠道适配、回调、UNKNOWN 收敛 | 已实现 |
| ~~refund-service~~ | Refund | **Feature 015 已并入 `payment-service`**（`com.payment.refund` 包，端口 8085 退役）；退款编排（渠道退款 + 权益撤销 + 对账）由 payment-service 提供 | 已并入（ADR-0016/0017/0018，[ADR-0064](../adr/0024-multi-payment-per-transaction.md)） |
| fulfillment-service | Fulfillment | 履约、发货 | 已实现 |
| entitlement-service | Entitlement | 权益授予 / 撤销 / 查询 | 已实现 |
| ledger-service | Ledger | 复式记账（资金核心） | 已实现（`004-ledger` 前置，8090） |
| reconciliation-service | Reconciliation | 异步对账（状态机全链路 + 周期 fixture） | 已实现（ADR-0019/0020/0021） |
| settlement-service | Settlement | 结算批次、调整项、已确认事实闸门、收敛/关闭与结算侧记账（不真实出款） | 已实现（ADR-0022/0023 缺口补齐） |

> Channel 不单独成服务：以「接口 + 模块」内聚在 payment-service（`application/channel` 接口 + `infra/channel` 实现），落实 Payment ≠ Channel。

> **状态列口径**：上表基于本文 2026-08-26 基线。`ledger-service` 已按 `004-ledger` 前置实现并接入 payment 侧记账；refund / reconciliation / settlement 的进展以 [roadmap.md](roadmap.md) Current Status 与 `docs/specs/005~007` 为准，本文相关表述待下次基线刷新时统一修订。

### 3.3 系统间通讯协议

- **服务内**：本地事务保证原子。
- **跨服务**：统一走**公开的同步 HTTP/RPC 用例**（Spring Cloud OpenFeign + LoadBalancer），契约 DTO 集中在 `common-dto`。
- **对外渠道**：通过 Channel Adapter 抽象与第三方交互（当前 Mock Channel）。
- **MQ / 跨服务异步事件**：当前**不引入**（Constitution §4）；服务内部可用事件表达本地状态变化，但**不跨服务发布**。
- **后置流程**：由负责方通过同步 RPC 调用下游公开用例（如 Payment 成功 → 请求履约），任何同步边界不得要求一次调用完成跨领域全链路。

### 3.4 领域边界与数据架构

**12 领域 + 六条关键边界 + 依赖方向**（Constitution §2.3）：

| # | 区分 | 含义 |
|---|---|---|
| 1 | Order ≠ Payment | Order 是商业意图，Payment 是资金动作，独立生命周期与状态机 |
| 2 | Payment ≠ Channel | Payment 是编排层，Channel 是渠道技术适配；Payment 只依赖接口抽象 |
| 3 | Payment Success ≠ Entitlement Granted | 支付成功是财务事件，权益是消费权利，成功只「触发」授予 |
| 4 | Reconciliation ≠ Settlement | 对账是比对找差异，结算是资金划转，二者解耦 |
| 5 | Refund ≠ Payment Refund | Refund 是跨多领域编排，不是「调一次渠道退款」 |
| 6 | Fulfillment 不强耦合 Payment | 履约有自己的状态机，不被支付状态反向阻塞 |

**依赖方向**：领域依赖 MUST **单向、向内**——编排层（Order/Payment/Refund）可依赖底层领域，底层领域不得反向依赖编排层；`Ledger` 只被依赖；`Channel` 只依赖外部协议。

**数据所有权**：每服务独占自己的 Schema（`merchant` / `catalog` / `order` / `payment` / `refund` / `fulfillment` / `entitlement` / `reconciliation` / `settlement`；实际命名以 [deployment/schema/](../../deployment/schema/) DDL 为准；`merchant` 无独立数据源，使用内存 `ConcurrentHashMap`）。跨服务读写一律经对方公开 API/RPC，**禁止**任何服务直接 SQL 他服务 Schema 的表。单机/Compose 阶段多服务可共用一个物理库，但必须独立 Schema。

### 3.5 技术栈

| 维度 | 选择 | 说明 |
|---|---|---|
| 语言 / JDK | Java 21 LTS | Spring Boot 3.x 全面支持 |
| 框架 | Spring Boot 3.x + Spring Cloud | 主流企业级 |
| 构建 | Maven（mvnw Wrapper 锁版本） | 父 POM + `dependencyManagement` 统一版本 |
| ORM | MyBatis / MyBatis-Plus | SQL 显式可控，适合资金/对账复杂查询 |
| 注册 + 配置 | Nacos | 同时提供注册与配置能力（**[目标] 未启用**，0 依赖 0 配置，见 ADR-0056） |
| 服务调用 | Spring Cloud OpenFeign + LoadBalancer | 声明式服务间调用 |
| API 网关 | Spring Cloud Gateway | 响应式网关（本 MVP 延后启用） |
| 熔断 | Resilience4j 或 Sentinel | 延迟到需要时再引入 |
| 可观测 | Micrometer + Micrometer Tracing | 指标与链路追踪（**[目标] 未落地**：当前 0 依赖，实际为 `TraceIdFilter` + MDC，见宪法 §Obs.3） |
| 测试 | JUnit 5 + Mockito + AssertJ；Testcontainers | 集成测试用容器（**[目标] 未落地**：实际全 H2 MySQL 兼容模式，见 backlog） |
| 代码质量 | Checkstyle + Spotless | CI 强制（**[目标] 未落地**：根 pom 与 CI 均无插件） |

---

## 4. 详细功能和流程设计

### 4.1 领域职责

| 领域 | 解决的问题 | 不负责的问题 | 核心实体 | 核心状态 |
|---|---|---|---|---|
| Merchant | 谁可经营商品、接收交易并参与结算 | 订单、支付执行、履约、对账差异 | Merchant、Settlement Account | 待审核 → 有效 → 暂停/终止 |
| Product | 面向用户与商家的商品概念与生命周期 | 销售价格快照、订单、支付 | Product、Product Version | 草稿 → 上架 → 下架 → 归档 |
| SKU | 哪个具体销售单元可被购买、以何属性交付 | 订单金额确认、支付状态 | SKU、Price、Delivery Definition | 草稿 → 可售 → 暂停 → 失效 |
| Order | 用户买什么、向谁买、订单金额与购买生命周期；支付成功后的订单侧动作（markPaid/confirmStock/履约驱动，ADR-0054） | 渠道协议、资金收取、退款执行 | Order、Order Item、Price Snapshot | 待确认 → 待支付 → 已支付 → 履约中 → 已完成/取消/关闭 |
| Transaction | 商业交易如何关联订单与支付、是否完成；交易动作编排（正常/surplus 判定 + 自动退款发起，ADR-0054） | 渠道通信、履约交付、权益管理、退款执行 | Transaction、Transaction Relation | 待处理 → 处理中 → 成功/失败/取消/未知 |
| Payment | 一次资金收取意图与支付尝试的生命周期；支付指令编排（渠道支付 + 记账，记账保留在 payment，ADR-0054） | 商品、履约、具体渠道协议、退款决策与发起 | Payment、Payment Attempt、Payment Result | 待支付 → 处理中 → 成功/失败/未知 → 已关闭 |
| Payment Channel | 如何与外部支付机构交互并解释其结果 | 平台订单、履约、权益、最终业务判断 | Channel、Channel Attempt、Channel Reference | 可用 → 不可用/停用 |
| Refund | 为什么退、退多少、是否可退、退款整体进度 | 单独替代支付退款、履约撤销、对账 | Refund、Refund Item、Refund Decision | 申请中 → 处理中 → 成功/部分/失败/未知/拒绝/关闭（**本期只做全额退款，「部分」不开放，见 §2.4**） |
| Fulfillment | 如何交付商品或服务、交付是否完成 | 支付结果确认、权益内部生命周期 | Fulfillment、Fulfillment Item、Delivery | 待履约 → 履约中 → 已交付/部分/失败/取消 |
| Entitlement | 用户获得什么消费权利、如何用与撤销 | 判断是否已付款、渠道退款 | Entitlement、Grant、Consumption | 待授予 → 可用 → 部分/已用尽 → 过期/撤销/失败 |
| Reconciliation | 平台事实与外部事实是否一致、差异如何处理 | 资金划转、修改原始交易事实 | Batch、Match、Difference | 待处理 → 对账中 → 一致/有差异 → 处理中/关闭 |
| Settlement | 商户应结算多少、批次是否完成 | 发现全部原始差异、代替支付成功判断 | Batch、Item、Adjustment | 待结算 → 计算中 → 待执行 → 执行中 → 成功/失败/未知/关闭 |

### 4.2 核心基数关系与状态机

**MVP 基数关系**：

```text
Order (1) ───── (1) Transaction (1) ───── (N) Payment (1) ───── (1) PaymentAttempt
   │                                                                      │
   └─ Order Items / Price Snapshots                             每次尝试 ≤ 1 个渠道引用
```

> **基数修订**：`Transaction : Payment = 1:N`（ADR-0064：一交易多支付单，用户每选一个支付方式即新建一张支付单，`payments.attempt_seq` 区分）；`Payment : PaymentAttempt = 1:1`（ADR-0054：每张支付单仅一条渠道尝试记录，渠道重试在同一 attempt 行内 `retry_count` 递增、不新建行）。

**核心状态机**（领域自持，集中状态转换函数，禁止散落 set）：

| 实体 | 允许的状态流 |
|---|---|
| Order | 待确认 → 待支付 → 已支付 → 履约中 → 已完成；取消/关闭仅当业务允许 |
| Transaction | 待处理 → 处理中 → 成功/失败/取消/未知 |
| Payment | 待支付 → 处理中 → 成功/失败/未知 → 已关闭 |
| PaymentAttempt | 待处理 → 已受理 → 成功/失败/未知 |
| Fulfillment | 待履约 → 履约中 → 已交付/部分交付/失败/取消 |
| Entitlement | 待授予 → 可用 → 部分使用/已用尽 → 已过期/已撤销/失败 |
| Refund | 申请中 → 处理中 → 成功/部分成功/失败/未知/拒绝/关闭（**部分成功本期不开放**，见 §2.4 #6） |
| Reconciliation | 待处理 → 对账中 → 一致/有差异 → 处理中/关闭 |
| Settlement | 待结算 → 计算中 → 待执行 → 执行中 → 成功/失败/未知/关闭 |

**金额铁律**：金额一律用最小货币单位（`long` 分）+ 独立 `currencyCode` 字段，或 `BigDecimal`（明确 scale）；`Money` 值对象**不启用**（ADR-0010，已由 Constitution v2.3.0 追认）；全库禁止 `float`/`double`；任何真实资金变动须经 Ledger 复式记账。

### 4.3 核心业务流程

#### 4.3.1 购买主链路

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户/调用方
    participant O as order-service
    participant Cat as catalog-service
    participant P as payment-service
    participant Ch as Channel Adapter
    participant F as fulfillment-service
    participant E as entitlement-service

    U->>O: 提交购买（商品/SKU/数量）
    O->>Cat: 校验 SKU 可售 + 取销售数据（RPC）
    Cat-->>O: 可售 + 销售/交付定义
    O->>O: 创建订单 + 明细 + 价格快照（本地事务）
    O->>P: 创建支付意图（RPC，携带幂等键）
    P->>P: 创建 Payment + PaymentAttempt（本地事务）
    P->>Ch: 发起支付（渠道接口抽象）
    Ch-->>P: 明确成功/失败/未知
    alt 支付成功
        P->>P: 编排支付指令：记账 ledger postPaymentCapture（保留在 payment）
        P->>O: 支付成功通知（业务侧仅通知 order，ADR-0054）
        O->>O: transaction 层判定正常/surplus → 委派 order 层
        O->>O: order 层：markPaid + transaction.succeed() + confirmStock
        O->>F: 请求履约（RPC，order 层驱动）
        F->>E: 履约完成后请求权益授予（RPC）
    else 支付未知
        P->>P: 进入 UNKNOWN，等待查询/回调/对账/人工收敛
    end
```

> **现状 vs 目标（ADR-0054 / spec 016，Proposed 未实施）**：当前代码仍是 payment 直调履约（`PaymentResultProcessor:73`）并自行 catch 409 发起自动退款（`:82`）；目标链路为上图——payment 业务侧仅通知 order，order transaction 层判定正常/surplus（surplus 时以 `transactionNo + paymentNo` 发起自动退款），order 层执行 confirmStock + 驱动履约，记账保留在 payment。实施按 `docs/specs/016-order-payment-orchestration/` T1~T5 推进，完成后本图即为现状。

#### 4.3.2 支付回调与 UNKNOWN 收敛

渠道通知**可能重复、乱序、延迟**到达。payment-service 依据「渠道交易引用 + 支付尝试」幂等吸收重复通知，不回退已确认的合法状态；终态成功不被后到的失败回调覆盖。回调只更新 Payment/PaymentAttempt；payment 完成自身支付指令编排（含记账，保留在 payment 内）后，**业务侧仅通过同步 RPC 通知 order-service**，由 order 编排下游（回写订单/交易状态、履约触发），不直接改写其他领域内部数据（ADR-0054，现状 payment 直调履约待迁移，见 §4.3.1 注）。

渠道超时/断连/响应不完整时，Payment/Refund **进入 UNKNOWN**（不是失败别名）。收敛路径：主动查询接口、后续回调、对账、人工处理；在未收敛前**不得重复执行不可确认的资金动作**。

> **决策记录（Feature 003 / ADR 集合 `docs/adr/0003-payment-reliability-decisions.md`）**：
> - 超时进 UNKNOWN、主动查询收敛、有限重试、终态冲突策略（迟到成功不覆盖已失败）已 **Accept**（ADR-0003/0004/0005/0007）。
> - **人工收敛端点（原 ADR-0006 / spec US4）已实现**：`POST /payments/{id}/resolve` + `ResolveAuthorizationInterceptor`（未配置 `PAYMENT_ADMIN_TOKEN` 时返回 503 拒绝，不放行），详见 `runbook.md`。高危资金操作由 admin-token 守卫 + 审计日志兜底；完整权限体系仍待路线图 Phase 9 统一建设。自动收敛（主动查询 + 超时 + 重试）覆盖绝大多数 UNKNOWN，剩余保持 UNKNOWN 依赖对账流程兜底（ADR-0007）。

#### 4.3.3 退款链路

**退款编排三步链**（负责人 2026-09-06 明确，ADR-0054 / spec 016 FR-017）：

```mermaid
sequenceDiagram
    autonumber
    participant T as order transaction 层
    participant RF as payment 退款域
    participant A as payment_attempts (渠道交互记录)
    participant Ch as Channel Adapter
    T->>RF: ① 退款请求 (transactionNo+paymentNo) → 生成 refundNo (RF+雪花, 幂等+防超退)
    RF->>A: ② 落退款渠道尝试记录 (channel_reference = 渠道退款流水号)
    RF->>Ch: ③ channel.refund 外部渠道退款
    Ch-->>RF: SUCCEEDED / FAILED / UNKNOWN → 收敛退款状态机
    RF->>RF: 成功 → 后处理编排 (履约撤销→权益吊销→记账冲正)
```

- 常规退款入口与 surplus 自动退款共用此链（发起方不同：用户申请 vs order transaction 层）。
- 退款渠道流水号 MUST 落库（修复缺口：现状 `PaymentRefundService` 调渠道后 `channelReference` 仅回传不落库，对账退款事实用 `"refund-{id}"` 合成引用，见 spec 016 N4）。

```mermaid
flowchart LR
    A["退款申请<br/>(幂等键)"] --> B["退款资格判断<br/>可退款金额校验"]
    B -->|"拒绝超限"| X["拒绝 + 保留原因"]
    B -->|"通过"| C["发起支付退款<br/>(refund→payment RPC)"]
    C --> D["履约/权益处理<br/>(refund→fulfillment/entitlement RPC)"]
    C --> E["对账<br/>(refund 事实纳入 Reconciliation)"]
```

**本期范围：单笔退款无「部分成功」，只支持「整笔全额」结果**（§2.4 #6，2026-08-30 负责人裁决；金额校验口径见 **ADR-0047**）：

- **单笔退款只有两种终局：全额成功或失败**。渠道只回 `SUCCEEDED / FAILED / UNKNOWN` 三态，成功即视为该笔申请额全额退回；若真实发生部分退回，按 `UNKNOWN` 处理并走对账收敛，**不得**落 `PARTIALLY_SUCCEEDED`、不记 `refundedAmountMinor`。
- **不做**「申请额 = 可退全额」的等值校验（ADR-0047 决策）：`RefundPolicy.decide` 只做三条校验——币种一致 / 金额为正 / **累计申请额 + 本次申请额 ≤ 已支付金额**（H1 防超退，超额 `REJECTED` 且不发起渠道尝试）。同一支付**允许多笔退款**（每笔独立幂等键），由 `refund_intake_locks` 行锁串行化受理。
  > **为何不强制全额等值**：`001-core-business-model/spec.md` 第 66–67、289 行明确「退款默认支持部分退款和多次退款，累计不得超过已支付且尚未退款金额」，强制等值会与这条已 Accepted 的基线冲突，并使现有防超退测试（300 + 400 + 400 / paid=1000）失效。取舍全记录见 [ADR-0047](../adr/0006-refund-decisions.md#adr-0047-退款金额校验口径adr-0016-回退后是否强制申请额--可退全额)。
- `RefundStatus.PARTIALLY_SUCCEEDED` 与 `partiallySucceed()` 仅作为**状态机枚举/方法保留**，本期无任何入口可到达该状态（枚举删除会导致历史行 `valueOf` 抛异常，故保留）。
- 后续若要开放**单笔退款的部分成功追踪**，须先解决：退款单与支付尝试的拆分模型、多次退的累计口径、权益/履约的按比例回收、Ledger 冲正的部分金额分录 —— 属 Constitution §8 人类决策边界，须另立 ADR。

#### 4.3.4 履约与权益

`PaymentSucceeded`（Payment 服务内部事实）→ payment 业务侧仅通知 **order-service**（ADR-0054）；由 order transaction 层判定「正常到账 / surplus」后**委派 order 层**执行 markPaid + confirmStock 并通过 RPC 请求 fulfillment-service 履约（**confirmStock 与履约驱动属 order 层**）；履约完成后再请求 entitlement-service 授予权益（`fulfillment → entitlement` 链保留）。支付成功只**触发**履约，不决定履约最终状态；履约失败不回写支付为失败；权益授予失败保留履约事实、可重试/人工补发，不重复扣款。重复/超额支付（surplus）由 order transaction 层以 `transactionNo + paymentNo` 发起自动退款（payment 仅执行）。

#### 4.3.5 资金闭环：记账、对账与结算

资金链路只有一条准入门槛：**未确认的事实不得进入账务与结算**。

![PaymentArch 资金闭环与复式记账映射](diagrams/03-funds-closed-loop.svg)

> 该图的 PlantUML 源码：[diagrams/03-funds-closed-loop.puml](diagrams/03-funds-closed-loop.puml)（供 AI 阅读与后续编辑，改动后需重新渲染为 SVG）

**复式记账映射**（`ledger-service`，四个预置科目，MVP 仅 CNY）：

| 场景 | 借方 DEBIT | 贷方 CREDIT | 平衡 |
|---|---|---|---|
| 支付成功（金额 A，手续费 F，净额 N = A − F） | `CUSTOMER_CASH` A | `MERCHANT_PAYABLE` N ＋ `PLATFORM_FEE_REVENUE` F | A = N + F |
| 退款成功（实退金额 R） | `MERCHANT_PAYABLE` R | `CUSTOMER_CASH` R | 与支付方向相反 |
| 结算批次（净额 S） | `MERCHANT_PAYABLE` S | `SETTLEMENT_PAYABLE` S | 借贷相等 |

- **记账**：仅对**已确认**的支付/退款/结算事实记账，`UNKNOWN` / 处理中 / 失败 / 拒绝**一律不记账**（Constitution §V.7）。幂等键 `PAYMENT:<key>` / `REFUND:<key>` / `SETTLEMENT:<batchId>` 保证重复请求只产生一份分录；借贷不平衡由 Posting 聚合根强校验拒绝，不落任何分录。记账 RPC 失败**不回滚**业务事实，记 `ledger.posting_failed` 并进入「待记账」清单由对账补齐。
- **对账**：reconciliation-service 读取已确认的 Payment/Refund 事实，与 Mock/预置渠道账单比对，产出一致/金额差异/状态差异/平台独有/渠道独有。**对账只产生匹配/差异事实，永不修改原始 Payment/Refund 事实。**
- **结算**：settlement-service 只消费「已确认且差异可解释」的财务事实（校验商户结算资格 → 净额计算 → 生成结算批次）。同一商户周期不重复生成批次；未知执行结果不等于成功。
- **分录不可变**：已提交分录禁止 UPDATE/DELETE，更正只能新增反向分录（冲正）。

> **注**：`ledger-service` 已按 `004-ledger` **前置实现**（原定 Roadmap Phase 8），设计决策见 [ADR-0004](../adr/0004-ledger-design-decisions.md)；§2.3 非目标中「不实现 Ledger 复式记账」的表述应以 Roadmap Current Status 为准。

#### 4.3.6 典型跨服务调用

```text
order-service → catalog-service               校验 SKU + 取销售数据
order-service → payment-service               创建支付意图
payment-service → Channel Adapter             发起支付 / 查询渠道结果
payment-service → ledger-service              支付/退款记账（支付指令编排的一部分，保留在 payment）
payment-service → order-service               支付成功通知（业务侧仅通知 order，ADR-0054）
order-service → fulfillment-service           支付成功后由 order 层请求履约（ADR-0054）
fulfillment-service → entitlement-service     履约完成后请求权益授予
order-service → payment-service               退款命令（surplus 自动退款，transactionNo+paymentNo，ADR-0054）
payment-service（refund 包）→ 渠道            退款渠道尝试（进程内，Feature 015 起退款域并入 payment）
reconciliation-service → payment-service      读已确认支付/退款事实
settlement-service → merchant/reconciliation  校验结算资格 + 生成结算批次
```

> **现状标注**：上述 order→fulfillment 履约触发与 order→payment 退款命令为 ADR-0054 目标链路（spec 016 未实施）；当前代码仍是 payment→fulfillment 直调 + payment catch 409 自发起退款。

### 4.4 一致性模型（幂等 / 状态机 / 重试 / UNKNOWN）

| 机制 | 落地 |
|---|---|
| **幂等** | 支付、退款、结算等资金入口 MUST 有幂等键；幂等键由调用方提供、服务端持久化并唯一约束；同键重复请求返回同一业务结果，不产生重复资金动作 |
| **状态机** | Order/Payment/Refund/Fulfillment/Entitlement/Settlement 均显式、单向；状态流转集中在状态转换函数，禁止散落 set |
| **本地事务** | 单服务内部状态变更用本地事务保证原子 |
| **最终一致** | 跨服务通过同步 RPC 编排 + 幂等重试实现最终一致；对外部系统（渠道）采用最终一致；暂不引入 MQ / 跨服务异步事件 |
| **重试** | 仅对幂等的外部调用允许自动重试，须有退避与上限；非幂等调用禁止盲目重试 |
| **重复消息/回调** | 处理侧假设消息与回调会重复，靠幂等键 + 状态机幂等吸收，不重复入账 |
| **超时** | 所有外部调用有超时；超时 ≠ 失败/成功，进入未知状态 |
| **UNKNOWN** | 结果不确定时不猜成败直接落账，靠查询/对账/人工收敛 —— 支付系统最核心的正确性保障 |

### 4.5 分布式事务处理策略

**不使用 2PC/XA 分布式事务**。跨服务资金流转用 **Saga + 同步 RPC + 幂等** 实现最终一致：

- 每个服务只在自己的本地事务内原子提交自身事实（如 Payment 成功只在 payment-service 内落库）。
- 跨服务副作用通过幂等的同步 RPC 逐段推进（支付成功 → 履约 → 权益），每段失败**不回滚前序已成功事实**，而是靠幂等重试 / 对账 / 人工收敛补齐。
- 后置 RPC 失败**不得回写前序成功事实**（如履约失败不回写支付为失败），只记录独立失败并重试。
- 金额/币种/累计金额校验在支付、退款、对账、结算**各边界分别执行**，不依赖上游校验。

**明确禁止**：2PC/XA 分布式事务；跨服务直接改他服务数据；散落直改 `status` 绕过状态机。

---

## 5. 非功能性设计

### 5.1 性能与容量目标（`[目标]`，待确认）

> 以下为**建议目标值**，非已实现/已测得事实；面向 MVP 单机部署，不做生产级压测承诺。

| 指标 | 目标值 | 说明 |
|---|---|---|
| 同步查询接口 P99 延迟 | ≤ 500ms | 本地 MySQL 单机、单次查询 |
| 同步命令接口 P99 延迟 | ≤ 1s | 含单次跨服务 RPC 编排（如下单含 catalog+payment 两次 RPC） |
| 资金入口可用性 | ≥ 99.9% | 支付/退款入口 |
| 对账达成率 | ≥ 99% | 对账周期内可解释差异占比 |

### 5.2 安全

> **本节口径已按 2026-08-30 负责人裁决修订**：鉴权、内部令牌、敏感数据脱敏 **本阶段不做**，仅保留预留挂点（§2.4）。下文分「本期强制」与「本期不做（有意接受）」两组，避免把未实现能力写成现状。

**本期强制（MUST）**：

- 密钥、签名、金额等禁止硬编码，走环境变量 / 配置中心 / 密钥管理（ADR-0026）。
- **渠道回调 MUST 验证签名与来源**（HMAC-SHA256 + 防重放），**当前为预留空函数恒放行**（ADR-0025 / 0052）：`verifySignature()` 恒返回 `true`，伪造回调可翻转支付状态。**已知且负责人已接受的风险**；接入真实渠道前 MUST 实现，此前 payment-service 不得暴露公网（详见 §2.4 #7）。
- 对外入参 MUST 做输入校验（Bean Validation）。
- 资金动作 MUST 有审计日志（§5.3）。

**本期不做（有意接受的风险，见 §8）**：

- **对外 API 鉴权 / 身份体系**：不做，预留空实现函数（§2.4 #3）。所有 REST 端点当前**无身份校验**，任何人可直连服务端口调用。
- **内部服务间鉴权令牌**：不做，出站不发、入站不校验，且**不推广**（§2.4 #1 / #2）。8 个服务的 `/internal/**` 端点全部裸奔，防护完全依赖网络层隔离（同 VPC、不对公网暴露）。
- **敏感数据脱敏**：本阶段不管（§2.4 #5），不新增脱敏点、不做响应层统一脱敏。当前项目无真实卡号/凭证，风险敞口有限；接入真实渠道前 MUST 重新评估。
- **风控**：只预留空实现挂点，不启用任何规则（§2.4 #4）。

**运维前提**：上述「本期不做」成立的前提是**部署环境不对公网暴露**（当前为单机/Compose 本地部署，§6）。一旦服务暴露到不可信网络，鉴权 MUST 先于功能上线补齐。

### 5.3 可观测性（全局）

- **Metrics（Micrometer）**：请求量、延迟、错误率 + 关键业务计数（支付成功率/失败率/超时率/渠道成功率/渠道耗时；退款成功率/失败率；履约/权益失败率；对账差异数量/金额；结算成功率/失败数）。
- **Logs**：结构化日志（logback），关联字段含 `traceId` / `orderId` / `paymentId`；**资金动作 MUST 有审计日志**（`FINANCIAL_AUDIT` logger）。**敏感数据脱敏本期不做**（ADR-0027，`StructuredAuditLogger.mask()` 保留但生产零调用）；当前无真实卡号/凭证，接入真实渠道前 MUST 重新引入。
- **Traces**：Micrometer Tracing 跨服务传播 `traceId`/`spanId`，初期不上独立分布式追踪基础设施。
- **告警/SLO**：对「支付状态未知堆积」「对账差异」「退款失败」「重试耗尽」等业务异常 MUST 告警，而非只告警基础设施；核心接口定义可用性、P99、对账达成率目标。

> 各服务的**精确埋点键 / 日志键**见 [systems/](systems/) 下对应文档（要素 6）。

---

## 6. 实施与部署策略

**当前部署形态：单机多进程 + 单 MySQL 独立 Schema**（不是模块化单体）：

![PaymentArch 单机部署拓扑](diagrams/02-deployment-topology.svg)

> 该图的 PlantUML 源码：[diagrams/02-deployment-topology.puml](diagrams/02-deployment-topology.puml)（供 AI 阅读与后续编辑，改动后需重新渲染为 SVG）

- 服务是**独立进程、独立端口、独立部署单元**；单机只是多个进程跑在同一台服务器，不改变服务边界。
- `gateway` 本 MVP **不创建、不部署**；`ledger-service`（8090）已按 `004-ledger` 前置创建并纳入部署。
- 服务当前**未容器化**（无 Dockerfile），以宿主进程运行；只有 MySQL / Prometheus / Grafana 由 `docker compose` 承载，Prometheus 经 `host.docker.internal` 回抓宿主端口的 `/actuator/prometheus`。
- 每个服务暴露 Swagger UI、`/actuator/health`、`/actuator/prometheus`；`deployment/start-all.sh` / `stop-all.sh` 一键起停，日志落 `deployment/logs/<service>.log`（详见 [deployment/README.md](../../deployment/README.md)）。
- **演进路径**：本地多服务 → Docker Compose → 单机部署 → CI/CD → 可观测增强 → 有证据的部分服务独立数据库迁移（Roadmap Phase 10）。

> 各服务的**运行态配置**（环境变量、启动依赖顺序、端口）见 [systems/](systems/) 下对应文档（要素 6），与本节「物理机部署」区分。

---

## 7. 项目计划与资源

**当前阶段**：Roadmap 主链 Phase 0~10 已走完，终点 `014-seckill-and-cache`（含 Phase 4 压测）；当前处于演示/打磨期。

| 阶段 | 目标 | 交付边界 |
|---|---|---|
| Phase 0 · Foundation | 收口架构裁决、服务目录、端口、Schema、Spec Kit 入口 | 不实现业务、不接真实支付、不建 Ledger、不引 MQ/K8s |
| Phase 1 · Commerce Core | Merchant/Product/SKU/Order/Transaction 最小可运行 | 不含 Payment、退款、权益、结算、库存/促销/税费 |
| Phase 2 · Payment Core | Payment/Attempt/Channel Adapter + Mock Channel | 不含真实渠道、Ledger、路由/风控/多币种 |
| Phase 3 · Payment Reliability | UNKNOWN 收敛、重复/乱序/延迟回调、有限重试、审计 | 不含生产级 SLA、多活、复杂风控、自动补偿 |
| Phase 4 · Fulfillment & Entitlement | 支付成功后履约 → 权益授予 | 不含复杂仓储物流、权益商城、退款回收政策 |
| Phase 5 · Refund | **单笔退款只回三态**（成功恒为全额；部分退款追踪本期不做，§2.4 #6 / ADR-0047）、支持同一支付多笔退款、幂等、退款后处理 | 不含单笔部分成功追踪、审批、权益回收政策、真实出款；**退款侧 Ledger 冲正已接入**（退款成功经 `FeignLedgerPostingGateway` 留反向分录，ADR-0018） |
| Phase 6 · Reconciliation | 平台事实与渠道账单比对、差异处理 | 不含真实账单、自动调账、真实资金修正 |
| Phase 7 · Settlement | 商户周期结算批次、调整项、模拟结算结果 | 不真实出款、不接银行、不接多币种清分（结算侧记账经 ledger-service，见 §4.3.5） |
| Phase 8 · Ledger | 复式记账、科目、分录、记账幂等 | 不含复杂会计准则、多币种清分、总账 |
| Phase 9 · Risk / Security | 认证、授权、签名校验、敏感数据、最小风控 | 不含全量合规、复杂风控平台 |
| Phase 10 · Distributed Evolution | 有证据地独立数据库/服务治理演进 | 不默认引入 Service Mesh/K8s/CQRS/ES |

**Feature 依赖图**：

```text
Phase 0 Foundation → 001 Core Business Model → 002 payment-order-callback → 003 payment-reliability → 004 ledger
→ 005 refund → 006 reconciliation → 007 settlement → 009 → 010 → 011 → 012 → 013 → 014（含 Phase 4 压测）
注：`008` 为历史缺口，有意保留不补号；`002~007` 均已落地，主链走至 `014-seckill-and-cache`。
```

可并行不阻塞主链路：`009 Observability Baseline`、`010 Delivery/CI-CD Baseline`。

**每个 Feature 完成后的 SOP**：Spec → Clarify → Plan → 确认 → Tasks → Implement → 测试/verify/quickstart → Review → 更新 Roadmap（详见 [roadmap.md](roadmap.md)）。

---

## 8. 风险评估与应急预案

> 下表综合 Constitution 约束与当前架构推导的既有风险，**不新造决策**；涉及领域边界/状态机/Schema/API 的处置须走 §8 人类决策边界。

| 风险 | 影响 | 缓解（设计约束） | 应急（异常处置） |
|---|---|---|---|
| **支付状态 UNKNOWN 堆积** | 资金事实悬空，无法结算 | 超时/断连/不完整响应一律进 UNKNOWN，不猜成败；配 `payment.unknown` 业务指标与告警 | 主动查询 / 后续回调 / 对账 / 人工收敛；未收敛前不重复执行不可确认资金动作 |
| **跨服务最终一致断裂** | 前序成功、后序失败造成链路断点 | 每段本地事务原子提交 + 幂等重试；后置失败不回写前序成功 | 独立记录后序失败、可查询、可重试、可补偿或人工补发 |
| **无 Ledger 的资金风险** | 用状态替代账务事实，无法复式审计 | Phase 0-7 只模拟业务事实；金额铁律（long/BigDecimal+Money）全程生效 | 任何真实资金变动必须先引入 Ledger（Phase 8），禁止直改余额 |
| **渠道回调伪造/重复/乱序** | 错误入账、重复入账 | 幂等键 + 状态机幂等吸收；终态成功不被迟到失败覆盖 | 回调查签名（Phase 9）；重复回调映射同一渠道引用去重 |
| **对账差异** | 漏单、重复、金额/状态差异被静默 | 对账只产生匹配/差异事实，永不修改原始 Payment/Refund | 差异记录独立处理状态与依据，人工跟进 |
| **结算基于未确认事实** | 错误结算批次 | 结算只消费已确认且差异可解释的事实；商户-周期批次幂等 | 未确认/重大差异暂停批次或进 UNKNOWN，禁止重复结算 |
| **单服务故障** | 影响该链路，不拖垮全局 | 服务独立进程/端口，故障隔离 | 依赖同步 RPC 超时 + 幂等重试，跨服务不共享状态 |
| **金额溢出/浮点** | 资金计算错误 | 金额用 long 分 + `Math.addExact/multiplyExact` 防溢出；禁 float/double | 金额不变量校验（AMOUNT_INVARIANT_VIOLATION）拒绝非法金额 |
| **服务无鉴权（本期有意接受，§2.4 #1~#3）** | 任何能触达端口者可调用资金入口与 `/internal/**`，含伪造退款请求 | 仅本地 / 内网部署，**不对公网暴露**（§5.2 运维前提）；预留鉴权挂点待启用 | 暴露到不可信网络前 MUST 先补齐鉴权或加网络层 ACL；怀疑越权时以审计日志 + 对账差异兜底核对 |
| **单笔退款无部分成功（§2.4 #6 / ADR-0047）** | 渠道实际只退回一部分时，系统会按 `UNKNOWN` 挂起而非记为部分成功 | 渠道只回三态；部分退回按 `UNKNOWN` 收敛；**累计一律按申请额**占位，超额显式 `REJECTED` 且**不静默截断** | 靠对账差异发现并人工收敛（与支付侧 UNKNOWN 同一口径）；**禁止**绕过状态机直改退款金额/状态 |
| **风控缺失（§2.4 #4，代码已删）** | 异常交易模式无拦截、无埋点 | 资金正确性由幂等 + 状态机 + 对账保障 | 靠对账差异与人工复核发现异常；需风控时重新立项（ADR-0028） |
| **鉴权/验签为空实现（§2.4 #2 / #7）** | 伪造渠道回调可翻转支付状态；`/internal/**` 可越权调用 | 仅内网/VPC 部署，**payment-service 不得暴露公网**；`/internal/**` 依赖安全组/服务网格隔离 | 接入真实渠道前 MUST 先实现 `verifySignature` 与 `verifyServiceToken`；怀疑越权时以审计日志 + 对账差异兜底核对 |

---

> 本文是「当前有效总体架构」的**综合快照**，随 Roadmap 阶段与 ADR 演进而更新。任何修改若触及领域边界、服务边界、状态机、数据库 Schema 或公共 API，须遵循 Constitution §8，先立提案并经人类确认。

---

---

## 9. 已决策 ADR 在本文档的体现（追溯索引）

> **审计要求（Phase 5）**：已决策的 ADR 必须在技术方案与系统设计文档中体现。全部 53 条 ADR 的「编号 → 承载文件 → 锚点」见 [`docs/adr/README.md` 跳转表](adr/README.md)。本节给出与本文档 / `systems/` 设计直接相关的决策落点，便于审计与防漂移。
>
> 标记：**【P0·内联】** = 本次审计已在正文对应章节修正并体现；**【P1·索引】** = 本表集中索引；**【ADR 文件】** = 仅纪录于 `docs/adr/`，正文未展开（按惯例以 ADR 文件为权威）。

### 9.1 已在正文内联体现的 ADR（P0，本次审计修正）

| ADR | 决策 | 落点 |
|---|---|---|
| ADR-0002 | 技术栈选型 | §3.5（Nacos 未启用见 ADR-0056） |
| ADR-0010 | 金额表示：long 分 + currencyCode，Money VO 不启用 | §4.2 |
| ADR-0018 | 退款 → Ledger 记账接入（冲正分录） | §7 Phase 5 |
| ADR-0019 / ADR-0020 / ADR-0021 | 对账：状态机接线 / 渠道账单周期 fixture / 事实读取弹性 | §4.3.5（reconciliation-service.md） |
| ADR-0021 | 不引入熔断中间件 | payment-service 弹性口径 |
| ADR-0024 / ADR-0025 / ADR-0027 / ADR-0028 | 安全：鉴权空实现 / 验签空实现 / 脱敏不做 / 风控不做 | §2.4、§5.2 |
| ADR-0026 | 密钥明文 env 注入 | §5.2 |
| ADR-0039 / ADR-0040 | 下单入口幂等键（Redis 唯一存储、409+Retry-After） | order-service.md §4.3.1 |
| ADR-0041 / ADR-0042 / ADR-0043 | 库存域归属 catalog / 三段式扣减 / ZSet 超时释放 | catalog-service.md、order-service.md |
| ADR-0044 / ADR-0045 / ADR-0046 | Redis 引入论证 / 用途边界（非数据源）/ 固定窗口限流 | §2.3、§3.5、§5 |
| ADR-0047 | 退款金额校验口径（累计不超付） | §4.3.3 |
| ADR-0048 ~ ADR-0051 | 演示形态：收银台 / 场景配置化 / 演示账单 / 脚本纪律 | §3.1、§6、§4.3.5 |
| ADR-0052 | 渠道回调验签接入（回退至 ADR-0025 空实现） | §5.2 |
| ADR-0053 | 013/014 代码超前 roadmap 落地处置 | §7 |

### 9.2 P1 决策索引（集中列出，避免散落漂移）

| ADR | 决策要点 | 本文落点 | systems 落点 |
|---|---|---|---|
| ADR-0012 | 双响应码错误分类（通信失败一律重试，业务失败不重试） | §4.4 | payment-service 错误分类 |
| ADR-0013 | 重试不落库、请求内联重试（3 次退避 1s/2s/4s） | §4.4 | payment-service |
| ADR-0014 | 同 attempt 重放（重试复用同一 attempt，靠幂等吸收） | §4.4 | payment-service |
| ADR-0015 | UNKNOWN 真实收敛时长度量（entered_unknown_at） | §5.1 / §5.3 | payment-service |
| 超时口径 | 出站 RPC 1s / 对外 HTTP 1.5s（全服务统一） | §3.3 / §4.4 | 全服务 |
| ADR-0038 | 演示形态 → **Superseded by ADR-0048** | §6 | — |
| ADR-0048 | 新增 `mock-channel-web` 收银台组件（payUrl 跳转链路） | §3.1 / §6 | mock-channel-web (8091) |
| ADR-0049 | Mock 渠道场景配置化（`payment.channel.mock-scenario`） | §4.3 | payment-service |
| ADR-0050 | 对账演示账单生成 CSV 写入 `target/classes` | §4.3.5 | reconciliation-service |
| ADR-0051 | 演示脚本纪律（只编排不伪造、断言失败即非零退出） | §6 | deployment/ |

### 9.3 仅纪录于 ADR 文件（正文未展开，按惯例以 ADR 为权威）

ADR-0001（Spring Cloud 架构）、ADR-0003~0007（支付可靠性集合）、ADR-0008~0011（Ledger 设计集合）、ADR-0016~0017（退款模型/编排）、ADR-0022~0023（结算调整项/闸门）、ADR-0029~0033（分布式演进）、ADR-0034~0037（内部令牌，已不做）、ADR-0054~0058（核心资金正确性 / 入口与基础设施 / 性能基线，见 `docs/adr/0016~0018-*.md`）。

## 10. Feature 017：审计四核对 + 挂账调账闭环（ADR-0065）

**职责变化**：reconciliation-service 从「渠道对账 + 差异记录」升级为「会计审计中心」——账证（业务事实 ↔ 账本分录）、账账（借贷平衡 + 科目勾稽 + 跨账）、账实（账本 ↔ 渠道账单）、账表（006 报表 ↔ 业务回算）四核对，以及挂账 → 调账 → 复核 → 关批处置闭环。ledger 新增第 5 科目 `SUSPENSE`（待处理差错款，ASSET）；settlement 建批前接入审计门禁（fail-closed）。

**新增调用清单**（全部只读 + 标准记账通道，Feign + common-dto）：

| 调用 | 端点 | 用途 |
|---|---|---|
| reconciliation → payment / refund | GET /internal/payments|refunds/confirmed-facts | 账证事实 |
| reconciliation → settlement | GET /internal/settlements/audit-facts?period= | 账证 + 跨账事实（sourceId = 批次 id） |
| reconciliation → ledger | GET /internal/ledger/postings/all、GET /internal/ledger/balance | 分录与平衡视图 |
| reconciliation → ledger | POST /internal/ledger/postings | 挂账/调账记账（source_type=ADJUSTMENT，幂等键 adjust:{adjustNo}） |
| settlement → reconciliation | GET /internal/audit/settlement-gate?period= | 结算门禁 |

**数据模型 delta**：reconciliation 库 `audit_batches` / `audit_differences`（11 类差异 × 三级 severity × 5 态状态机）/ `audit_adjustments`（处置台账）；ledger `accounts` 新增 id=5 SUSPENSE seed；业务单号新增 AB（审计批）/ AD（调账）前缀（ADR-0062 扩展）。

**关联文档**：spec/plan/tasks → `docs/specs/017-accounting-audit/`；服务视角 → `docs/architecture/systems/reconciliation-service.md` §7。
