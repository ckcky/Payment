# 部署与运行说明

> 本地单机运行与 Docker Compose 的最小 how-to。完整架构见 [docs/architecture/technical-solution.md](../docs/architecture/technical-solution.md)。

## 环境要求（前置条件）

- **JDK 21 LTS**：项目基于 Spring Boot 3.x，**必须 JDK 21**；用 JDK 11/17 直接 `./mvnw` 会编译失败。确认 `java -version` 输出含 `21`。
- **Docker / Docker Desktop**：本地运行依赖 Compose 拉起 MySQL 8、Redis 7、Nacos（业务必需）与 Prometheus / Grafana / Loki / Promtail（可观测，可选）。Windows 在 **Git Bash** 里跑脚本，macOS/Linux 直接跑。
- **Maven**：用仓库自带的 `./mvnw`（Wapper，锁版本）；无需另行安装。
- **Nacos（注册中心，硬依赖）**：跨服务 Feign 调用经 Nacos 服务名发现（ADR-0059）。**未起 Nacos 则所有跨服务调用 `Connection refused`**——`start-all.sh` 会自动 `docker compose up -d` 拉起，但手动 `spring-boot:run` 前务必先启动 Nacos。

## 目录收口原则

仓库根目录**只放领域服务模块与工程元数据**（`*-service/`、`common/`、`pom.xml`、`mvnw`、`README.md`、`CLAUDE.md`）。
非领域资产一律收口在 `deployment/` 下：

| 目录 | 用途 | Maven 模块 |
|---|---|---|
| `schema/` | 各服务建表脚本（当前手工执行，未挂 Flyway） | 否 |
| `initdb/` | Compose 首次启动建库脚本 | 否 |
| `docker-compose.yml` | MySQL + Redis + Nacos（业务必需）+ Prometheus / Grafana / Loki / Promtail（可观测）本地编排 | 否 |
| `mock-channel-web/` | Mock 渠道收银台 + 演示控制台（8091，演示组件非领域服务） | **是** |
| `architecture-tests/` | ArchUnit 服务边界门禁（无业务代码，必须最后构建） | **是** |
| `demo/` | 演示脚本：种子数据、四场景链路、复位 | 否 |
| `performance/` | k6 压测脚本与报告 | 否 |
| `output/` | 构建产物与运行日志（git 不跟踪） | 否 |
| `logs/` | 服务进程运行日志（git 不跟踪） | 否 |

> **构建输出与调试日志 MUST 落在 `deployment/output/logs/`**，不得写到仓库根目录。
> `.gitignore` 已忽略 `*.log` 与 `deployment/output/`，但落在根目录仍会污染工作区视图。

### 移动模块目录时的注意事项

`architecture-tests` 与 `mock-channel-web` 是 Maven 模块，移动时 MUST 同步修改三处：

1. 根 `pom.xml` 的 `<modules>` 路径
2. 模块自身 `pom.xml` 的 `<parent><relativePath>`（收口在 `deployment/` 下需上溯两级：`../../pom.xml`）
3. `architecture-tests` 的 `ServiceBoundaryTest#moduleClassesDir` —— 它以相对路径
   `../../<service>-service/target/classes` 读取各服务编译产物，层级写错会导致结构规则**静默空转**
   （已内置防空转断言兜底，会直接失败而非放行）

`demo/*.sh` 用 `ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"` 反推仓库根，
移动 demo 目录时 MUST 同步调整上溯层级。

## 服务与端口

| 服务 | 模块名（`-pl` 参数） | 端口 | 健康检查 |
|---|---|---|---|
| Merchant（商户） | `merchant-service` | 8081 | `/actuator/health` |
| Catalog（目录） | `catalog-service` | 8082 | `/actuator/health` |
| Order（订单） | `order-service` | 8083 | `/actuator/health` |
| Payment（支付） | `payment-service` | 8084 | `/actuator/health` |
| Fulfillment（履约） | `fulfillment-service` | 8086 | `/actuator/health` |
| Entitlement（权益） | `entitlement-service` | 8087 | `/actuator/health` |
| Reconciliation（对账） | `reconciliation-service` | 8088 | `/actuator/health` |
| Settlement（结算） | `settlement-service` | 8089 | `/actuator/health` |
| Ledger（账本） | `ledger-service` | 8090 | `/actuator/health` |
| Mock 渠道收银台（演示组件，非领域服务） | `deployment/mock-channel-web` | 8091 | `/actuator/health` |

> `refund-service`（原 8085）已于 Feature 015 并入 `payment-service`（`com.payment.refund` 包，端口退役）；退款相关接口现由 `payment-service` 提供。共 **9 个领域服务 + 1 个演示组件 = 10 个进程**。

## 数据库连接配置

各服务的 `src/main/resources/application.yml` 中的 `spring.datasource`（除 `merchant-service`，它无数据源）：

- URL：`jdbc:mysql://localhost:3306/<db>?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`
- 用户名 / 密码：`root` / `root`（与 `deployment/docker-compose.yml` 的 `MYSQL_ROOT_PASSWORD` 一致）
- Driver：`com.mysql.cj.jdbc.Driver`

本地开发用同一个 MySQL 实例承载多个逻辑库（Database-per-Service），服务间不共享表。

## Docker Compose（本地 MySQL + 可观测）

Compose 提供 **MySQL 8（业务库）+ Redis 7（下单入口幂等，ADR-0039/0040/0044）+ Nacos（服务发现，ADR-0059，硬依赖）** 三项业务必需组件，以及 **Prometheus / Grafana / Loki / Promtail** 可观测组件（可选）：

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

- 首次 `up -d` 时，`./initdb/01-create-databases.sql` 只创建 8 个**空数据库**：`catalog / order / payment / fulfillment / entitlement / reconciliation / settlement / ledger`（`merchant` 无库；`refund` 库已随退款域并入 payment-service 于 Feature 015 退役）。**不创建任何业务表**。
- 完整业务表 DDL 参考见 [`schema/`](schema/)（不挂载、不自动执行）；表结构由后续各服务自己的 migration 负责。
- MySQL 实例：`mysql:8.0`，容器名 `payment-mysql`，宿主机端口 `3306`，命名卷 `mysql-data` 持久化数据。

## 一键启动 / 停止（推荐）

项目提供两个脚本（Windows 在 **Git Bash** 里跑，macOS/Linux 直接跑）：

```sh
bash deployment/start-all.sh   # 起 MySQL/Redis/Nacos（业务必需）+ Prometheus/Grafana/Loki（可观测）+ 9 个领域服务（+ `mock-channel-web` 演示收银台，共 10 个进程），日志落 deployment/logs/
bash deployment/stop-all.sh    # 停全部微服务 + 容器（保留 MySQL 数据卷）
```

`start-all.sh` 依次做三件事：`docker compose up -d`（基础设施：MySQL/Redis/Nacos + 可观测）→ `./mvnw -q install -DskipTests`（首次构建，后续可跳过）→ 后台启动 **9 个领域服务（含 `ledger-service`）+ `mock-channel-web` 演示收银台，共 10 个进程**，每个服务控制台输出重定向到 `deployment/logs/<service>.log`。

> 前提：已安装并**启动 Docker Desktop**（Windows/macOS）或 docker 引擎（Linux），且 `docker` 在 PATH 上。首次 `install` 较慢属正常。


> **Redis 依赖（2026-09-03 补充）**：`014-seckill-and-cache` 已引入 Redis 7（端口 6379），由 `docker-compose.yml` 一并拉起。用途边界见 `docs/adr/0014-next-stage-decisions.md`（ADR-0044/0045）：仅入口幂等 / SKU 缓存 / 秒杀预扣 / 超时时间轮，**非数据源**；秒杀预扣 fail-closed，其余 fail-open。

## 日志在哪看

服务当前没有文件日志 appender，日志 = 每个服务的控制台输出：

- **一键启动后**：`deployment/logs/<service>.log`，实时跟踪 `tail -f deployment/logs/payment-service.log`。
- **手动 `spring-boot:run` 时**：日志直接打在启动该服务的终端窗口里。
- **资金动作审计**：`StructuredAuditLogger` 输出 `FINANCIAL_AUDIT` 的 JSON 行，检索：
  ```sh
  grep FINANCIAL_AUDIT deployment/logs/payment-service.log
  ```
- **容器日志**（MySQL/Prometheus/Grafana）：`docker compose -f deployment/docker-compose.yml logs -f <service>`。

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
curl http://localhost:8086/actuator/health   # Fulfillment
curl http://localhost:8087/actuator/health   # Entitlement
curl http://localhost:8088/actuator/health   # Reconciliation
curl http://localhost:8089/actuator/health   # Settlement
curl http://localhost:8090/actuator/health   # Ledger
curl http://localhost:8091/actuator/health   # Mock 渠道收银台（演示组件）
```

预期返回 `{"status":"UP"}`。

## 可观测（Swagger + Prometheus + Grafana）

每个服务暴露（端口见上表）：

- **Swagger UI**：`http://localhost:<port>/swagger-ui.html` —— 浏览并试调该服务的全部 HTTP 接口。
- **Prometheus 指标**：`http://localhost:<port>/actuator/prometheus` —— Micrometer 业务指标以 Prometheus 格式导出。
- **Actuator**：`/actuator/health`、`/actuator/metrics`、`/actuator/info`。

服务以宿主进程运行；Prometheus/Grafana 以容器运行并抓取宿主（`host.docker.internal`）：

```sh
docker compose -f deployment/docker-compose.yml up -d prometheus grafana

# Prometheus 目标/查询：http://localhost:9090
# Grafana（admin/admin）：http://localhost:3000  → 内置「PaymentArch 业务指标」看板
```

**Windows 能看到 Grafana 吗？** 能。前提是装了 Docker Desktop 并处于 Running。Prometheus 抓取目标用 `host.docker.internal:8081~8091`（Docker Desktop 会把该主机名映射到宿主机，覆盖 9 个领域服务 + 演示组件），服务默认绑定 `0.0.0.0`，容器内可达。两点注意：

- 若 `docker` 命令在 Git Bash 里 `command not found`，通常是 Docker Desktop 未启动或未装，先启动再跑脚本。
- Windows Defender 防火墙首次可能拦截 `java`/端口入站，允许即可；若 Prometheus 里 target 状态为 DOWN 且日志是连接拒绝，多半就是防火墙。

核心业务指标（Micrometer 计数器，点号→下划线、加 `_total` 后缀，维度 `module=<service>`）：

- 支付：`payment_initiated_total` / `payment_succeeded_total` / `payment_failed_total` / `payment_unknown_total` / `payment_duplicate_total` / `payment_duplicate_callback_total`
- 退款：`refund_initiated_total` / `refund_succeeded_total` / `refund_failed_total` / `refund_unknown_total` / `refund_rejected_total`
- 履约/权益：`fulfillment_completed_total` / `fulfillment_failed_total` / `entitlement_granted_total` / `entitlement_grant_failed_total`
- 对账/结算：`reconciliation_run_total` / `reconciliation_difference_total` / `settlement_batch_initiated_total` / `settlement_failed_total` / `settlement_unknown_total`
- 订单：`order_initiated_total` / `order_create_failed_total`

业务告警规则见 `prometheus/rules/payment-alerts.yml`（支付 UNKNOWN 堆积 / 退款失败 / 对账差异）。

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
