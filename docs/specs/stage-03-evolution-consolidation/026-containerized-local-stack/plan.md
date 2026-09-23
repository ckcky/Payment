# Plan: 026-containerized-local-stack

> **口径注明（2026-09-22 文档治理）**：本文件撰写时「10 个服务」指 **10 个应用进程** = **9 个核心业务服务 + 1 个演示组件 `mock-channel-web`**。当前统一口径：描述**业务服务**用 **9**，描述**运行进程**用 **10**（见 [docs/standards/documentation-governance.md](../../../standards/documentation-governance.md)）。本文为已实现 Feature 的历史记录，正文措辞保留原样。

- 版本: v1
- 日期: 2026-09-15
- 状态: Implemented
- 上游: [spec 026](./spec.md) / ADR-0070
- 已决策：双轨并存 + 模式开关；宿主打 jar + 镜像只 COPY

---

## 0. 总体策略

**一句话**：新增「容器模式」这条平行链路，**不拆除**现有「宿主模式」。
两条链路共用同一份 fat jar 产物、同一套端口契约（8081–8091）、同一个 PostgreSQL/MySQL 数据卷，
由**模式守卫**保证互斥，由 `compose profiles` 区分「只起中间件」与「起全栈」。

为什么这样切：

- 现有 CI 资产（e2e / 压测 / demo 演示）全部建立在宿主进程上，尤其 demo 的 UNKNOWN 收敛
  依赖「以不同 mock-scenario 重载 JVM」。强制单模会造成大面积返工；
- 容器模式的价值恰恰在于**消除宿主 JDK 依赖**（本次实跑踩到 java 11 vs RunMojo 需 17+），
  这个价值与宿主模式并存不冲突；
- 宿主模式下在 IDE 里断点调试的学习场景不能丢。

**关键前提（已实测）**：父 POM 的 `spring-boot-maven-plugin` repackage 输出目录为
`${maven.multiModuleProjectDirectory}/deployment/output/jars`，当前 **10 个 fat jar 已存在**。
因此 Dockerfile 只需 COPY，不需要任何构建配置改造。

**Java 版本**：`<java.version>21</java.version>`，Spring Boot 3.5.15 →
基础镜像选 `eclipse-temurin:21-jre-jammy`（glibc，避免 musl 上偶发的类库差异）。

---

## P1 — 镜像层：通用 Dockerfile + .dockerignore

目标：FR-001 / FR-002 / FR-003 / FR-012

产物：

- `deployment/docker/Dockerfile`（**一份**，10 个服务复用）
- `deployment/docker/.dockerignore`（排除 `**/target`、`logs`、`.git`、`.workbuddy`）

设计要点：

```dockerfile
# syntax=docker/dockerfile:1
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JRE_IMAGE}
ARG JAR_FILE
ARG SERVICE_PORT
ENV JAVA_TOOL_OPTIONS="-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m"
COPY ${JAR_FILE} /app/app.jar
EXPOSE ${SERVICE_PORT}
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

- 每个服务的端口通过 compose 的 `--server.port` 或 `SERVER_PORT` 传入，避免 10 份文件。
- **构建上下文必须是仓库根**（因为 fat jar 在 `deployment/output/jars/`），
  由 compose 的 `context: ..` 指定，`.dockerignore` 限制上下文体积。
- 堆上限沿用宿主模式既有值（`-Xmx384m`），保证两种模式行为可比。

---

## P2 — 编排层：compose 引入 10 个应用服务

目标：FR-004 / FR-005 / FR-007 / FR-008

产物：`deployment/docker-compose.yml` 变更（顺手更新文件头注释里「Dockerfile 就绪后补齐」这句已过期的说明）

设计要点：

1. **网络**：新增 `networks: pay-arch: driver: bridge`，中间件与应用同网络。
2. **profiles**：
   - 中间件 → `profiles: [infra, full]`（或直接不加 profile = 始终起）；
   - 应用服务 → `profiles: [full]`。
   这样**宿主模式仍可复用同一份 compose 只起中间件**（替代今天的 `compose up -d` 全量中间件语义）。
3. **配置覆盖**（不碰源码）：
   ```yaml
   environment:
     SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR: nacos:8848
     SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR: nacos:8848
     SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/<schema>?...
   ```
   依据 Spring Boot 环境变量优先级高于 `application.yml`（relaxed binding）。
4. **依赖顺序**：`depends_on: { nacos: { condition: service_healthy } }`
   —— Nacos 是所有 `@FeignClient` 的硬依赖（ADR-0059），只依赖启动顺序会造 Connection refused 假成功。
5. **端口**：对外映射与宿主模式**完全一致**（8081–8091），这是自动化测试零改动的前提。
   注意 8085 已退役（ADR-0064 退款并库），不复用。
6. **日志卷**：每个应用容器把日志写到挂载卷 `./logs:/var/log/payment-arch`，
   使 Promtail 现配置零改动即可采集（见 P4）。

**Mock 场景注入点**：`payment.channel.mock-scenario` 通过 compose 环境变量暴露，
供 demo UNKNOWN 场景切换（见 P5）。

---

## P3 — 入口层：模式守卫与启动/停止脚本

目标：FR-006 / FR-015

产物：

- `deployment/start-all.sh` **增量改造**（保留宿主模式主干）：
  - 开头新增模式守卫：检测 8081–8091 是否被**容器**占用（如 `docker compose ps` 中有应用 profile 在跑，
    或直接探测端口 + 判断占用者），命中则中止并提示「先 `bash deployment/stop-all.sh --mode container`」。
  - 帮助信息补充另一种模式入口。
- `deployment/start-container.sh`（新增）：容器模式入口
  - 前置校验：`docker` 可用；`deployment/output/jars/` 下 10 个 jar 齐全，缺失则提示先构建并中止（Edge Case）；
  - 守卫：检测端口是否被宿主进程占用；
  - `docker compose --profile full up -d --build`；
  - 等待 Nacos + 10 服务 health 全部 200（沿用现有 45×2s 超时 + `exit 1` 而非 `break` 的纪律）。
- `deployment/stop-all.sh`：改为**双模感知**，自动停止当前处于活动的那一侧（或两种都尝试）。
- `deployment/build-images.sh`（建议新增）：`mvn clean install -DskipTests` → `docker compose --profile full build`
  两步合一，避免用户忘记先构建 jar。

---

## P4 — 可观测适配

目标：FR-010 / FR-011

产物：`deployment/prometheus/prometheus.yml`、`deployment/promtail/promtail-config.yml`

- **Prometheus**：目标从 `host.docker.internal:808X` 改为 **compose 服务名**（`order-service:8083` 等）。
  服务名在容器网络内解析；由于两种模式对外端口一致，可用同一份 targets 文件 + 一个变量替换
  （宿主模式需要 `host.docker.internal`，容器模式需要服务名）→ 交由 entrypoint 脚本渲染，或明确接受
  「可观测配置随模式切换」并在文档写明，避免 `host.docker.internal` 在 Linux 宿主失联。
- **Promtail**：推荐保持零改动——P2 已让容器把日志写到同一挂载卷，
  现有 `service`（文件名）与 `level`（日志行）提取规则继续生效。仅在文档中说明该约定。

---

## P5 — 演示脚本适配（含 UNKNOWN 场景）

目标：FR-009 / SC-003

产物：`deployment/demo/restart-payment.sh`、`deployment/demo/run-all.sh`（感知模式）

- `restart-payment.sh <SUCCESS|BUSINESS_UNKNOWN>`：宿主模式维持 `./mvnw` 重载；
  容器模式改为**带环境变量重建 payment 容器**（保留宿主最喜欢的 `: "${MAVEN_BIN:-./mvnw}"` 分支不动）。
- `run-all.sh`：通过模式感知自动选用对应重启手段，使 5 个场景在两种模式下都能跑。

---

## P6 — 文档同步

目标：FR-014 / SC-007

必须更新：

| 文件 | 更新内容 |
|---|---|
| `README.md` | 双模式快速开始；明确「二选一」 |
| `deployment/README.md` | 脚本清单、模式守卫、profiles 用法、配额建议（16GiB） |
| `docs/operations/runbook.md` | 启动/停止/排障按模式分节 |
| `docs/architecture/technical-solution.md` | 部署形态描述更新 |
| `docs/architecture/diagrams/08-deployment.puml` | 拓扑图：补容器模式形态；重生成 SVG（当时文件名为 `02-deployment-topology.puml`，2026-09-22 文档治理按 C4 层级重命名为 `08-deployment.puml`） |
| `deployment/docker-compose.yml` 头注释 | 移除「Dockerfile 就绪后补齐」等已过期表述 |
| `CHANGELOG.md` | 记录本次容器化 |

所有引用 `start-all.sh` 的既有文档，均标注所属模式与另一种模式入口。

---

## P7 — 验证矩阵

必须按模式各跑一遍，用于判定 SC-001~SC-007：

| 套件 | 宿主模式（回归基线） | 容器模式（新增能力） |
|---|---|---|
| `bash deployment/demo/run-all.sh` | 退出码 0，5 场景通过 | 退出码 0，5 场景通过 |
| e2e（`./mvnw -pl deployment/e2e-tests test -De2e.env=local`） | 与改造前一致（22/23） | 同左 |
| 压测（`bash deployment/performance/run-stress.sh`） | 链路 completed + 0 个 5xx | 同左 |
| 模式互斥守卫 | 容器在跑时被拦 | 宿主在跑时被拦 |
| 可观测 | — | Prometheus 10 job UP / Loki 10 服务有日志 |

---

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 容器模式内存不足导致随机 OOM | 配额上调 16GiB；JVM `-Xmx384m`；文档写明最低配额 |
| Nacos 未就绪服务即启动（假成功） | `depends_on: service_healthy` + 启动后 health 全绿才宣告成功 |
| 忘记构建 jar 导致 COPY 失败 | 入口脚本前置校验 jar 齐全并给出构建命令 |
| 两种模式端口静默抢占 | 双向模式守卫，冲突即 `exit 1` |
| 可观测在容器模式下变空（不易察觉） | P7 验证矩阵把 Prometheus/Loki 数据可用性列为硬性放行条件 |
| 10 份 Dockerfile 腐化 | 单份通用 Dockerfile + compose 参数化 |
