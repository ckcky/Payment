# 部署与运行说明

> 本地单机运行与 Docker Compose 的最小 how-to。完整架构见 [docs/architecture/overview.md](../architecture/overview.md)。

## 本地运行（Maven Wrapper）

各服务是独立进程，用 `./mvnw` 分别启动（端口见各服务 `application.yml`，8081–8089）：

```sh
# Linux / macOS
./mvnw -pl <service> spring-boot:run

# Windows
mvnw.cmd -pl <service> spring-boot:run
```

构建与测试：

```sh
./mvnw verify        # Linux / macOS
mvnw.cmd verify      # Windows
```

## Docker Compose（最小依赖 MySQL）

当前 Compose 只提供 MySQL 8（各微服务镜像与编排在 Dockerfile 就绪后补齐）：

```sh
docker compose -f docs/deployment/docker-compose.yml up -d
```

- Database-per-Service：生产/集成测试每个服务独立数据库；本地单机为降低门槛，用同一 MySQL 实例承载多个 Schema，服务间仍不共享表。
- 停止：`docker compose -f docs/deployment/docker-compose.yml down`
