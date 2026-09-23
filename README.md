# PaymentArch

面向生产环境的 Commerce & Payment Platform 学习项目，采用 Java 21、Spring Boot 3.x、Spring Cloud 和 Maven，重点实践订单、支付、履约、权益、账本、对账与结算的边界和一致性。

## 架构

Spring Cloud 微服务（见 [docs/adr/0001](docs/adr/0001-adopt-spring-cloud-microservices.md) 与 [docs/adr/0002](docs/adr/0002-technology-stack.md)）。当前 MVP 模块：

| 模块 | 职责 |
|---|---|
| `common/common-core` | 共享值对象、错误模型、关联 ID、幂等基元 |
| `common/common-dto` | 跨服务 DTO 与公开领域事件契约 |
| `common/common-mybatis` | MyBatis-Plus 通用配置、拦截器、审计字段 |
| `merchant-service` | 商户与结算资格 |
| `catalog-service` | Product / SKU / 价格 |
| `order-service` | Order 1:1 Transaction 状态机 |
| `payment-service` | Payment / PaymentAttempt / Channel 适配 / 幂等 / 回调 |
| ~~`refund-service`~~ | Feature 015：退款编排已并入 `payment-service`（`com.payment.refund` 包，服务数 10→9，端口 8085 退役） |
| `fulfillment-service` | 支付成功后的履约 |
| `entitlement-service` | 权益授予 / 使用 |
| `reconciliation-service` | 基础对账 |
| `settlement-service` | 基础结算批次 |

`gateway` 不在本 MVP 范围；`ledger-service`（8090）**已实现**并接入 payment/refund/settlement 记账。

## 快速开始

**构建与测试**（Maven Wrapper 锁定版本）：

```sh
# Linux / macOS
./mvnw verify

# Windows
mvnw.cmd verify
```

**本地运行（两种模式，二选一）**：项目支持**宿主模式**与**容器模式**，两者对外都使用 8081–8090（另 `mock-channel-web` 演示收银台 8091），**端口互斥、不能同时运行**（spec 026 / ADR-0070）。

| 模式 | 启动 | 适用场景 |
|---|---|---|
| 容器模式（推荐） | `bash deployment/start-container.sh` | 不想被本机 JDK/Maven 版本影响；要环境可复现 |
| 宿主模式 | `bash deployment/start-all.sh` | IDE 断点调试、改代码热重启 |

两种模式共用同一批 fat jar（构建产物输出到 `deployment/output/jars/`）与同一套端口契约，
因此 e2e / 压测 / 演示脚本在两种模式下都无需改动。启动脚本内置**双向模式守卫**：
检测到另一侧在跑会直接中止并提示，不会静默抢占端口。

停止（两种模式都停，保留数据卷）：`bash deployment/stop-all.sh`

> ⚠️ 早期文档中的「Docker 基础设施 + 本机 Java 服务，不是全量服务容器化」已过时：
> spec 026 / **ADR-0070** 引入了全栈容器模式（并 Supersede 了 **ADR-0057「服务未容器化」**），
> 宿主模式作为调试路径保留。

**一键演示入口**：

```sh
bash deployment/demo/start-demo.sh
bash deployment/demo/run-all.sh
```

## 从哪里开始

- 文档导航：[docs/README.md](docs/README.md)
- 开发流程：[docs/guides/ai-standards.md](docs/guides/ai-standards.md)（AI 流程规范）
- 项目宪法：[.specify/memory/constitution.md](.specify/memory/constitution.md)
- 总体技术方案：[docs/architecture/technical-solution.md](docs/architecture/technical-solution.md)
- Roadmap：[docs/architecture/roadmap.md](docs/architecture/roadmap.md)
- 架构决策：[docs/adr/](docs/adr/)
- 特性设计：`docs/specs/<stage>/<feature>/`
- 本特性快速验证：[docs/specs/stage-01-core-mvp/001-core-business-model/quickstart.md](docs/specs/stage-01-core-mvp/001-core-business-model/quickstart.md)

## 当前边界（重要）

- Payment / Refund / Settlement 的资金变动**经 `ledger-service`（8090）复式记账**（ADR-0011/0018/0023）**，存在可追溯账务事实；仅出款/银行对接仍 mock。
- 跨服务一致性用 Feign（同步）+ 编排器顺序扇出/逐步重试/落库留痕；跨服务**异步解耦**用**既有 Redis（`redis:7`）Streams 模拟 MQ** 承载事务消息通道（**不引入 MQ 中间件**——复用已存在的 Redis 依赖，以减少需要新增与运维的组件，见 [ADR-0074](docs/adr/0074-redis-transactional-message.md#adr-0074)；无 Outbox）；Database-per-Service；集成测试默认 H2（MySQL 兼容模式），**真库子层已落地**——`deployment/test-infra` 提供 Testcontainers 真实 MySQL（仅测试作用域，见 [ADR-0081](docs/adr/0081-test-carrier-and-schema-replayability.md#adr-0081)）。
- 任何真实资金路径必须先经 Ledger 建立可追溯账务事实（见宪法 §2.2）。
