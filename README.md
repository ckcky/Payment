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
| `refund-service` | 退款编排 |
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

**本地运行**：各服务用 `./mvnw -pl <service> spring-boot:run` 启动（端口见各服务 `application.yml`，8081–8090（另 `mock-channel-web` 演示收银台 8091））。

**Docker Compose**（最小依赖 MySQL）：`docker compose -f deployment/docker-compose.yml up -d`

**一键演示入口**：

```sh
bash deployment/demo/start-demo.sh
bash deployment/demo/run-all.sh
```

说明：本轮新增决策明确采用“Docker 基础设施 + 本机 Java 服务”的启动方式。
保留底层 `docker compose` 命令作为排查入口，但日常使用统一走 `start-demo.sh` / `start-stack.sh`。
不是“全量服务容器化”；本机仍负责启动各 Java 微服务与 mock 收银台。

## 从哪里开始

- 文档导航：[docs/README.md](docs/README.md)
- 开发流程：[docs/guides/development-guide.md](docs/guides/development-guide.md)
- 项目宪法：[.specify/memory/constitution.md](.specify/memory/constitution.md)
- 总体技术方案：[docs/architecture/technical-solution.md](docs/architecture/technical-solution.md)
- Roadmap：[docs/architecture/roadmap.md](docs/architecture/roadmap.md)
- 架构决策：[docs/adr/](docs/adr/)
- 特性设计：`docs/specs/<feature>/`
- 本特性快速验证：[docs/specs/001-core-business-model/quickstart.md](docs/specs/001-core-business-model/quickstart.md)

## 当前边界（重要）

- Payment / Refund / Settlement 的资金变动**经 `ledger-service`（8090）复式记账**（ADR-0011/0018/0023）**，存在可追溯账务事实；仅出款/银行对接仍 mock。
- 跨服务一致性用 Feign（同步）+ 事务性 Outbox（异步，无 MQ）；Database-per-Service；集成测试用 Testcontainers。
- 任何真实资金路径必须先经 Ledger 建立可追溯账务事实（见宪法 §2.2）。
