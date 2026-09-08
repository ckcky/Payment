# 金融支付 Infra JD 匹配审计报告

- **审计日期**：2026-09-08
- **审计对象**：PaymentArch
- **对照岗位**：资深后端开发专家 - 金融支付 Infra
- **审计模型标注**：gpt-5.6-sol（按委托要求标注）
- **执行说明**：本报告由当前 Claude Code 会话依据仓库文件、测试实现、CI 配置、部署脚本与 Git 历史整理；“gpt-5.6-sol”为归档署名，不代表当前会话的实际模型。
- **范围**：只读审计，不修改业务代码；审计期间实际执行 `./mvnw -B verify`。

## 1. 结论

PaymentArch 不是空壳 Demo。项目在支付领域边界、资金正确性、幂等、状态机、未知支付结果、复式记账、退款、对账、结算、黑盒 E2E 和基础可观测性方面，明显高于普通个人项目。

但按该 JD 的完整要求，项目当前更准确的定位是：

> **支付核心领域工程基线 + AI 协作治理与自动化测试基线。**

目前不能客观地表述为已经完成的生产级支付 Infra、AI Agent 交付平台或 K8s 多环境治理平台。核心原因是：真实支付渠道和出款未接入，回调验签与内部鉴权为空实现，Agent/Loop 自动执行闭环尚未落地，K8s/容器化/自动回滚也未实现。

综合匹配度是**中等偏上，但高度依赖岗位实际侧重**：若岗位偏支付后端、资金正确性和可靠性工程，匹配度较好；若岗位要求已经在生产环境主导 AI Native 交付平台、K8s、精准回归和自动回滚，单凭本项目证据不足。

## 2. 成熟度矩阵

| 能力 | 成熟度 | 客观判断 |
|---|---:|---|
| 支付核心领域建模 | 4/5 | Order、Payment、Channel、Refund、Ledger、Reconciliation、Settlement 边界清楚 |
| 资金正确性 | 4/5 | 最小货币单位、幂等、UNKNOWN、复式记账和不变量均有实现/测试 |
| 退款/对账/结算 | 3.5/5 | 已形成可运行闭环，但资金与渠道仍是模拟事实 |
| Java / Spring Cloud / 微服务 | 3.5/5 | Maven 多模块、分层和服务边界较完整 |
| 分布式一致性 | 3/5 | 同步 RPC、本地事务、幂等重试明确；异步解耦能力有限 |
| 测试与可靠性 | 3.5/5 | 单测、状态机、ArchUnit、API 快照、E2E 和故障注入较丰富 |
| 可观测性 | 3/5 | Metrics、结构化日志、traceId、Prometheus/Grafana/Loki 已有 |
| CI/CD | 2.5/5 | 有 verify、nightly E2E 和 tag release，无完整生产发布治理 |
| 精准回归 | 2/5 | 有分层测试和单测选择，没有自动 affected-test 选择系统 |
| AI Agent / Harness | 1.5/5 | 有 SpecKit 与 AI 协作规范，没有 Agent Runtime 或工具沙箱 |
| Loop Engineering | 1.5/5 | 有监控、诊断、测试积木，没有自动变更到回滚的闭环 |
| K8s / 云原生环境治理 | 1/5 | 明确采用宿主机 JVM + Docker Compose，未落地 K8s |
| 自动监控/诊断/回滚 | 1.5/5 | 有人工 runbook 和告警规则，没有自动执行链路 |
| 生产安全 | 1.5/5 | 验签、内部鉴权、对外鉴权和脱敏均未形成生产能力 |

## 3. 与岗位职责的匹配

### 3.1 支付核心链路和平台能力

项目覆盖 Merchant、Catalog、Order、Payment、Refund、Fulfillment、Entitlement、Ledger、Reconciliation 和 Settlement。总体职责、状态机和关键边界见 [technical-solution.md:185](../../architecture/technical-solution.md#L185) 和 [technical-solution.md:212](../../architecture/technical-solution.md#L212)。

有价值的设计包括：

- Order 不等于 Payment，二者保持独立生命周期。
- Payment 只依赖 Channel 抽象，不依赖具体渠道实现。
- 支付成功不直接等同于权益授予。
- Refund 是渠道退款、权益撤销、账本冲正和对账调整的跨域编排。
- Reconciliation 与 Settlement 解耦。
- UNKNOWN 不等于失败，也不能直接触发资金后处理。

这部分足以证明支付领域建模和架构判断力。但当前仍是 Mock Channel，未证明真实机构协议、渠道签名、渠道 SLA、银行出款、多商户资金隔离或生产清结算。

### 3.2 资金正确性和可靠性

这是项目最强的方向。代码和测试体现了：

- 支付、退款、结算入口使用幂等键和状态机。
- 通信失败才允许有限重试，业务拒绝不重试。
- 超时或渠道无结论进入 UNKNOWN。
- 权威查询/回调收敛后只触发一次后处理。
- 终态不被迟到冲突结果覆盖。
- Ledger posting 使用唯一幂等键，冲突后回查已存在记录。
- 借贷必须平衡，Payment、Refund、Settlement 均可追溯到来源。

支付 UNKNOWN 收敛测试见 [PaymentUnknownResolutionTest.java:13](../../../payment-service/src/test/java/com/payment/payment/integration/PaymentUnknownResolutionTest.java#L13)，重试测试见 [PaymentRetryTest.java:30](../../../payment-service/src/test/java/com/payment/payment/application/reliability/PaymentRetryTest.java#L30)，账本实现见 [LedgerPostingService.java:48](../../../ledger-service/src/main/java/com/payment/ledger/application/LedgerPostingService.java#L48)。

需要保留的限制是：部分集成测试使用 H2 或内存 Repository，不能自动等价于真实 MySQL 并发、隔离级别和故障恢复验证。

### 3.3 测试、诊断和可观测性

项目有较好的工程验证意识：

- 黑盒 E2E 不依赖业务模块内部代码，通过 HTTP 和 JDBC 验证真实链路。
- 覆盖重复请求、重复/迟到回调、超时、UNKNOWN、退款、对账差异、结算门禁和账务平衡。
- E2E 用例按唯一前缀隔离数据。
- 失败时保存响应、trace、数据库快照和不变量日志。
- API schema snapshot 能发现字段和类型漂移。
- Prometheus/Grafana/Loki/Promtail 提供基础监控和日志聚合。

E2E 模块的黑盒原则和默认执行策略见 [e2e-tests/pom.xml:3](../../../deployment/e2e-tests/pom.xml#L3)，失败诊断封装见 [E2eBase.java:25](../../../deployment/e2e-tests/src/test/java/com/payment/e2e/support/E2eBase.java#L25)，API 快照见 [InternalApiSnapshotTest.java:23](../../../deployment/e2e-tests/src/test/java/com/payment/e2e/contract/InternalApiSnapshotTest.java#L23)。

但当前 trace 主要是自研 `traceId` + MDC 透传，并非完整 Micrometer Tracing/OpenTelemetry。告警规则也没有形成 Alertmanager、值班通知、自动根因分析和自动修复闭环。

### 3.4 AI Native、Agent、Harness 和 Loop Engineering

项目已经建立了 AI 协作治理资产：

- Spec → Plan → Tasks → Implement 流程。
- Spec 和 Plan 的人工 review gate。
- Agent 不得绕过领域边界、资金规则和测试约束。
- 重大架构、状态机、安全策略和生产部署必须由人类批准。

这些资产可以证明你具备 **Spec-Driven AI 协作设计能力**。但是仓库中没有足够证据证明已经实现：

- Agent Runtime、任务队列或多 Agent 调度。
- Tool sandbox、权限隔离和执行审计。
- 自动创建 PR、自动审查、自动修复和自动合并。
- 自动选择受影响测试。
- 发布后指标门禁、自动回滚和结果反馈给 Agent。
- Agent 成功率、误修复率、成本和人工介入率度量。

因此当前结论是：**具备 AI Native 流程设计和治理基础，不具备可证明的 AI Agent 交付平台。**

### 3.5 CI/CD、K8s 和环境治理

已有交付能力：

- Push/PR 执行 `./mvnw -B verify`。
- nightly、手动和 tag 触发全链路 E2E。
- tag 触发构建并发布 tarball。
- 发行包包含 fat jar、启动脚本、schema 和 SHA-256 校验文件。

证据见 [verify.yml:1](../../.github/workflows/verify.yml#L1)、[e2e.yml:3](../../.github/workflows/e2e.yml#L3) 和 [release.yml](../../.github/workflows/release.yml)。

当前部署形态是 Docker Compose 承载 MySQL、Redis、Nacos、Prometheus、Grafana、Loki 等基础设施，Java 服务以宿主机多 JVM 进程启动，见 [docker-compose.yml:34](../../../deployment/docker-compose.yml#L34)。未发现 Dockerfile、Kubernetes manifest、Helm、GitOps、Terraform、HPA、PDB、环境晋级或自动流量回退。

这与项目自身“没有真实需求就不增加 K8s/MQ/Service Mesh 复杂度”的原则一致，但不能满足 JD 对 K8s 和多环境治理的直接要求。

## 4. 生产阻断项与可信度风险

### P0：回调验签和内部鉴权为空实现

技术方案明确记载：渠道回调验签恒通过，`/internal/**` 守卫恒放行，对外身份体系也未实现，见 [technical-solution.md:54](../../architecture/technical-solution.md#L54) 至 [technical-solution.md:62](../../architecture/technical-solution.md#L62)。

这意味着当前系统不能作为真实支付渠道或公网支付服务部署。学习项目可以明确裁剪该范围，但在金融支付 Infra JD 中，这是生产安全阻断项，必须在面试中主动披露。

### P1：真实数据库和真实渠道验证不足

根 POM 声明了 Testcontainers 版本，但现有服务测试仍大量使用 H2；项目也没有真实渠道和真实银行出款。不能把本地单测、Mock Channel 或已有压测结果包装成生产容量证明。

### P1：E2E 默认不进入普通 PR verify

E2E 模块默认 `skipTests=true`，只有显式设置 `-De2e.env=local|ci` 才运行，见 [e2e-tests/pom.xml:8](../../../deployment/e2e-tests/pom.xml#L8)。这是一种合理的开发速度取舍，但不等同于每次变更都有全链路精准回归。

### P2：文档和实现存在少量漂移

已知问题包括旧的模块注释、退款服务退役后的历史描述、schema 初始化口径、目标能力和当前能力混写，以及部分验收文档落后于最新测试结果。项目能够自我披露这些问题，但正式交付前仍应收口。

## 5. 已验证结果

本次审计期间实际执行：

```text
./mvnw -B verify
```

结果：`BUILD SUCCESS`，16 个 Maven reactor 条目成功。该结果证明当前代码可完成默认编译和测试门禁，但由于 E2E 默认跳过，不能解释为全链路 live 环境已经通过。

项目还保存了缓存、秒杀、幂等和订单-支付-退款链路的性能结果，可作为面试量化材料。但这些结果需要同时说明机器配置、测试窗口、错误分类、409 业务拒绝和未达成的 SLO，不能只展示吞吐和 p95。

## 6. 求职指导

### 建议定位

建议把项目定位为：

> **Payment Core Correctness & AI-Assisted Engineering Baseline**

中文可以表述为：

> 我构建了一个 Java 21 / Spring Cloud 的支付领域工程基线，覆盖订单、支付、退款、履约、权益、账本、对账和结算，重点实现资金入口幂等、支付 UNKNOWN 收敛、重复回调吸收、Ledger 复式记账和跨服务资金不变量测试；同时建立了基于 Claude/SpecKit 的 Spec-Driven AI 协作规范和人工决策门禁。

必须补充边界：当前渠道为 Mock，运行形态为宿主机多 JVM 加 Docker Compose，真实渠道、生产安全、K8s、自动修复、精准回归和自动回滚仍是后续建设项。

### 不建议声称

- 做过生产级支付平台。
- 已实现 AI Agent 交付平台或多 Agent Harness。
- 已实现自动改码、自动发布、自动诊断和自动回滚闭环。
- 具备 K8s/GitOps 生产治理经验。
- 接入过真实支付机构或完成过真实银行出款。
- CI 已具备智能精准回归。

### 面试准备材料

1. 一张 Payment 状态机图：明确成功、失败、超时、UNKNOWN、查询收敛和终态冲突处理。
2. 一张 Ledger 分录图：展示支付、退款、结算的借贷平衡、幂等键和补偿边界。
3. 一张故障矩阵：覆盖超时、丢回调、重复/乱序回调、退款失败、短款、重复流水和孤儿账务。
4. 一份 E2E/CI 运行证据：成功率、耗时、失败 dump、环境差异和重试情况。
5. 一份明确的安全整改计划：HMAC、防重放、内部鉴权、Secret、脱敏和公网边界。
6. 一个最小 K8s 垂直切片：优先 payment-service + ledger-service，配套镜像、探针、Secret、滚动升级和 `rollout undo`。
7. 一个可量化的 AI Loop：Agent 生成变更、人工审批、沙箱验证、受影响测试、临时部署、指标门禁和修复 PR。

## 7. 最终判断

项目最能证明的是：你理解支付系统的资金正确性约束，并能把支付、账务、对账、结算、异常测试和工程治理组织成一个可运行体系。

项目目前不能充分证明的是：你已经在生产环境主导过 AI Native 支付 Infra、K8s 多环境治理、自动回滚或完整智能诊断平台。

因此，面向该 JD 的最佳策略不是夸大项目范围，而是把强项集中在支付正确性和可靠性工程，把 AI Native 部分诚实定位为流程治理与自动化基础，并主动说明尚未完成的生产化边界。
