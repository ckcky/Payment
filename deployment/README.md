# 部署与运行说明

> 本地单机运行与 Docker Compose 的最小 how-to。完整架构见 [docs/architecture/overview.md](../docs/architecture/overview.md)。

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

## 数据库连接配置

各服务的 `src/main/resources/application.yml` 中的 `spring.datasource`（除 `merchant-service`，它无数据源）：

- URL：`jdbc:mysql://localhost:3306/<db>?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`
- 用户名 / 密码：`root` / `root`（与 `deployment/docker-compose.yml` 的 `MYSQL_ROOT_PASSWORD` 一致）
- Driver：`com.mysql.cj.jdbc.Driver`

本地开发用同一个 MySQL 实例承载多个逻辑库（Database-per-Service），服务间不共享表。

## Docker Compose（本地 MySQL）

当前 Compose 只提供 MySQL 8（各微服务镜像与编排在 Dockerfile 就绪后补齐）：

```sh
# 启动 MySQL（首次会执行 initdb/01-create-databases.sql，只建空库）
docker compose -f deployment/docker-compose.yml up -d

# 校验 Compose 配置
docker compose -f deployment/docker-compose.yml config

# 查看容器状态（等待 STATUS 变 healthy）
docker compose -f deployment/docker-compose.yml ps

# 查看 MySQL 日志
docker compose -f deployment/docker-compose.yml logs -f mysql

# 进入 MySQL CLI
docker exec -it payment-mysql mysql -uroot -proot

# 停止（保留数据卷）
docker compose -f deployment/docker-compose.yml down

# 停止并清空数据（下次 up -d 会重新建空库）
docker compose -f deployment/docker-compose.yml down -v
```

- 首次 `up -d` 时，`./initdb/01-create-databases.sql` 只创建 8 个**空数据库**：`catalog / order / payment / refund / fulfillment / entitlement / reconciliation / settlement`（`merchant` 无库）。**不创建任何业务表**。
- 完整业务表 DDL 参考见 [`schema/`](schema/)（不挂载、不自动执行）；表结构由后续各服务自己的 migration 负责。
- MySQL 实例：`mysql:8.0`，容器名 `payment-mysql`，宿主机端口 `3306`，命名卷 `mysql-data` 持久化数据。

## 本地运行（Maven Wrapper）

先构建并安装所有模块到本地仓库（使 `spring-boot:run` 能解析 `common-*` 快照依赖）：

```sh
./mvnw install -DskipTests      # Linux / macOS
mvnw.cmd install -DskipTests    # Windows
```

各服务是独立进程，用 `./mvnw` 分别启动（端口见上表，`-pl` 参数为模块名）：

```sh
# Linux / macOS
./mvnw -pl <service> spring-boot:run

# Windows
mvnw.cmd -pl <service> spring-boot:run
```

构建与测试（编译 + 全部测试）：

```sh
./mvnw test       # 仅测试（H2，不依赖 MySQL）
./mvnw verify     # 编译 + 全部测试
```

> 服务当前以独立 `spring-boot:run` 进程运行，尚未容器化（无 Dockerfile）。

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

## 后续 schema / migration 位置

当前 `deployment/initdb/` 只负责「建空库」，不再承载表结构。完整业务表 DDL 参考保留在 [`deployment/schema/`](schema/)（原 initdb 01–08 脚本，仅作参考、不自动执行）。业务表结构随各 Feature 以 **migration** 方式落地，推荐位置：

- 每个服务 `src/main/resources/db/migration/V{版本}__{描述}.sql`（建议引入 Flyway，由启动时自动执行版本化迁移；可参照 `deployment/schema/` 的既有 DDL）。

> 引入 Flyway 属「新增中间件/工具」，需走 Spec Kit 并经宪法 §3.4 基础设施决策 + 人类确认后再落地。

## 回滚

- 清空 MySQL 数据（连库一并删除，下次 `up -d` 时 initdb 脚本会重新建空库）：

  ```sh
  docker compose -f deployment/docker-compose.yml down -v
  ```

- 代码级回滚：直接 revert 出问题的提交（`git revert <commit>`）。本地单机方案无镜像版本与迁移脚本，不存在数据迁移回滚问题。
