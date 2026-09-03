<a id="adr-0055"></a>


- **状态**：Accepted（2026-09-04 负责人裁决：启用 Nacos，撤销「暂不启用」偏离，ADR-0002 维持有效，原 R1 待裁决项关闭）
- **日期**：2026-09-03（决策更新 2026-09-04）
- **关联**：`002-payment-order-callback`、`deployment/`、`pom.xml` / `docker-compose.yml`、`ADR-0059`（启用实施记录）

## ADR-0055：支付意图幂等键由 order-service 生成

- **决策**：幂等键 `payment:{orderId}` 由 **order-service** 在创建订单时生成并随创建支付意图请求下发；payment-service 仅消费，不反向生成。
- **理由**：订单是资金动作的源头，幂等边界应与业务边界一致，避免 payment-service 重复生成导致跨订单碰撞。
- **落点**：`OrderController` → `paymentGateway.createPayment(CreatePaymentRequest(..., idempotencyKey=payment:{orderId}))`。

<a id="adr-0056"></a>


- **决策（2026-09-04 负责人裁决）**：**启用 Nacos** 作为注册中心与服务发现（实施记录见 `ADR-0059`）。本环境为单机/Compose 学习环境，但服务发现为默认寻址方式；12 个 `@FeignClient` 的硬编码 `url=` 已移除，改由 Nacos 服务发现解析服务名。
- **与 ADR-0002 的关系**：本决策**不 Supersede ADR-0002**，而是落实 ADR-0002（技术栈含 Nacos）的既有意图——此前 ADR-0056「暂不启用」属临时偏离，现已撤销。
- **实施落点**：`pom.xml`（父 BOM 引入 `spring-cloud-alibaba` 2025.0.0.0）+ 各服务 `application.yml` 的 `spring.cloud.nacos.discovery` + `deployment/docker-compose.yml` 新增 `nacos` 容器（端口 8848）+ `start-all.sh` 启动顺序纳入 Nacos。

<a id="adr-0057"></a>


- **决策**：10 个服务**均无 Dockerfile**，未容器化；本地以 `deployment/start-all.sh` + 各 `application.yml` 端口（8081–8090 + 8091 mock-channel-web）启动。
- **理由**：学习项目，容器化非当前目标（Constitution Anti-Goals 亦未要求 K8s）。
- **后果**：无镜像构建流水线；部署形态以 `docker-compose.yml`（仅 MySQL/Redis 依赖）为准，应用进程在宿主机运行。
