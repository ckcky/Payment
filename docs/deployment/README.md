# 部署与运行说明

> 本地单机运行与 Docker Compose 的最小 how-to。完整架构见 [docs/architecture/overview.md](../architecture/overview.md)。

## 服务与端口

| 服务 | 模块名（`-pl` 参数） | 端口 | 健康检查 |
|---|---|---|---|
| Merchant（商户） | `merchant-service` | 8081 | `/actuator/health` |
| Catalog（目录） | `catalog-service` | 8082 | `/actuator/health` |
| Order（订单） | `order-service` | 8083 | `/actuator/health` |
| Payment（支付） | `payment-service` | 8084 | `/actuator/health` |
| Refund（退款） | `refund-service` | 8085 | `/actuator/health` |
| Fulfillment（履约） | `fulfillment-service` | 8086 | `/actuator/health` |
| Entitlement（权益） | `entitlement-service` | 8087 | `/actuator/health` |
| Reconciliation（对账） | `reconciliation-service` | 8088 | `/actuator/health` |
| Settlement（结算） | `settlement-service` | 8089 | `/actuator/health` |

## 本地运行（Maven Wrapper）

各服务是独立进程，用 `./mvnw` 分别启动（端口见上表，`-pl` 参数为模块名）：

```sh
# Linux / macOS
./mvnw -pl <service> spring-boot:run

# Windows
mvnw.cmd -pl <service> spring-boot:run
```

构建与测试（编译 + 全部测试）：

```sh
./mvnw verify        # Linux / macOS
mvnw.cmd verify      # Windows
```

> 服务当前以独立 `spring-boot:run` 进程运行，尚未容器化（无 Dockerfile）。

## Docker Compose（最小依赖 MySQL）

当前 Compose 只提供 MySQL 8（各微服务镜像与编排在 Dockerfile 就绪后补齐）：

```sh
docker compose -f docs/deployment/docker-compose.yml up -d
```

- Database-per-Service：生产/集成测试每个服务独立数据库；本地单机为降低门槛，用同一 MySQL 实例承载多个 Schema，服务间仍不共享表。
- 首次启动时，`./initdb` 下的 `01-08-*-schema.sql` 按序自动执行，为 9 个服务各自创建独立数据库与表。
- 停止：`docker compose -f docs/deployment/docker-compose.yml down`

## 健康检查

每个服务暴露 Spring Boot Actuator 健康端点，用 `curl` 验证（端口见上表）：

```sh
curl http://localhost:8081/actuator/health   # Merchant
curl http://localhost:8082/actuator/health   # Catalog
curl http://localhost:8083/actuator/health   # Order
curl http://localhost:8084/actuator/health   # Payment
curl http://localhost:8085/actuator/health   # Refund
curl http://localhost:8086/actuator/health   # Fulfillment
curl http://localhost:8087/actuator/health   # Entitlement
curl http://localhost:8088/actuator/health   # Reconciliation
curl http://localhost:8089/actuator/health   # Settlement
```

预期返回 `{"status":"UP"}`。

## 回滚

- 清空 MySQL 数据（连库一并删除，下次 `up -d` 时 initdb 脚本会重新建库）：

  ```sh
  docker compose -f docs/deployment/docker-compose.yml down -v
  ```

- 代码级回滚：直接 revert 出问题的提交（`git revert <commit>`）。本地单机方案无镜像版本与迁移脚本，不存在数据迁移回滚问题。
