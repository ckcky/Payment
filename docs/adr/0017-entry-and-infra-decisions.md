<a id="adr-0055"></a>


- **状态**：Accepted（0056 涉及偏离既有 ADR-0002，按 §十 R1 待负责人二次确认）
- **日期**：2026-09-03
- **关联**：`002-payment-order-callback`、`deployment/`、`pom.xml` / `docker-compose.yml`

## ADR-0055：支付意图幂等键由 order-service 生成

- **决策**：幂等键 `payment:{orderId}` 由 **order-service** 在创建订单时生成并随创建支付意图请求下发；payment-service 仅消费，不反向生成。
- **理由**：订单是资金动作的源头，幂等边界应与业务边界一致，避免 payment-service 重复生成导致跨订单碰撞。
- **落点**：`OrderController` → `paymentGateway.createPayment(CreatePaymentRequest(..., idempotencyKey=payment:{orderId}))`。

<a id="adr-0056"></a>


- **决策**：注册中心/配置中心 **Nacos 暂不启用**（0 依赖、0 配置）；12 个 `@FeignClient` 全部硬编码 `url=`，无注册中心，LoadBalancer 未生效。
- **偏离说明**：与 `ADR-0002`（技术栈含 Nacos）存在偏离。当前为单机/Compose 学习环境，硬编码 URL 可接受；**启用 Nacos 前 MUST 先补服务发现 + 配置中心接入**，并相应更新 `ADR-0002`。
- **⚠️ 待裁决（R1）**：是否将本条正式 `Supersedes: ADR-0002（Nacos 部分）`，由负责人二次确认。

<a id="adr-0057"></a>


- **决策**：10 个服务**均无 Dockerfile**，未容器化；本地以 `deployment/start-all.sh` + 各 `application.yml` 端口（8081–8090 + 8091 mock-channel-web）启动。
- **理由**：学习项目，容器化非当前目标（Constitution Anti-Goals 亦未要求 K8s）。
- **后果**：无镜像构建流水线；部署形态以 `docker-compose.yml`（仅 MySQL/Redis 依赖）为准，应用进程在宿主机运行。
