# Spec: 026-containerized-local-stack

- 版本: v1
- 日期: 2026-09-15
> **Status**: Implemented — 2026-09-15 收口：P1~P7 全部完成并合入 master（提交、实测与遗留如下） <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->

> **口径注明（2026-09-22 文档治理）**：本文件撰写时「10 个服务」指 **10 个应用进程** = **9 个核心业务服务 + 1 个演示组件 `mock-channel-web`**。当前统一口径：描述**业务服务**用 **9**，描述**运行进程**用 **10**（见 [docs/standards/documentation-governance.md](../../../standards/documentation-governance.md)）。本文为已实现 Feature 的历史记录，正文措辞保留原样。
  ①`61e9e3d` / `6a07a3d`（P1/P2/P3/P6：Dockerfile、compose 编排、模式守卫、启动脚本）；
  ②`08f3bb1`（P4/P5/P7：容器模式可观测、`restart-payment.sh` 双模、验证矩阵全绿）；
  ③`01193ff`（验收后缺陷补丁：容器模式收银台 payUrl 客户端不可达，见 tasks.md「补丁」节）。
  实测：容器模式 demo 5 场景退出码 0、e2e 24/25（唯一红为已知 `MISSING_POSTING` 本地代理伪影，CI 为准）、
  Prometheus 10 target UP。**遗留**：spec 此前的 Draft 标记系文件未刷新，非任务未完成。
- 输入: 用户需求——「搞个 spec，把 docker 化做上，相应的文档还有什么启动脚本这些也要更新。用了 docker 之后我之前的 start-all 那个脚本还能用吗」
- 关联: ADR-0070
- 已决策（用户 2026-09-15 拍板）：①双轨并存 + 模式开关；②宿主打 jar + 镜像只 COPY

## 背景

**现状核实**（2026-09-15 实测，证据见 §8）：

- 全仓库无 `Dockerfile` / `.dockerignore` / k8s / helm（`find -iname Dockerfile*` 为空）。
- `deployment/docker-compose.yml` 只编排 **7 个中间件**：mysql / redis / nacos / prometheus / grafana / loki / promtail。
- **9 领域服务 + mock-channel-web 全部是宿主进程**，靠 `./mvnw spring-boot:run` 起，端口 8081–8091。
  compose 自身注释已声明「各微服务镜像与编排在 Dockerfile 就绪后补齐」「服务以宿主进程运行（非容器）」。
- 因此项目处于**半 docker 化**：依赖受控，应用不受控。

**真实痛点**（本次会话实跑踩到，非理论）：

1. **宿主 JDK 版本绑架启动**：`restart-payment.sh` 走 `./mvnw`，本机默认 java 为 11，而
   `spring-boot-maven-plugin:3.5.15` 的 `RunMojo` 要求 class version 61（Java 17+）→
   `UnsupportedClassVersionError`，payment-service 起不来 → demo UNKNOWN 场景中断。
   必须先 `export JAVA_HOME=/Users/feizhai/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home`。
2. **进程生命周期脆弱**：宿主进程由 shell 后台起，宿主重登/任务回收即丢，出现过 payment 8084=000
   却被后续压测当作「正常」使用，导致 97 条链路全 500、报告作废。
3. **配置硬编码本地回环**：9 个服务 `spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848`，
   这是容器化的唯一实质代码级障碍——容器内 `127.0.0.1` 指向容器自身。
4. **可观测链路绑定宿主**：`prometheus.yml` 抓取目标是 `host.docker.internal:PORT`；
   `promtail-config.yml` 采集宿主 `deployment/logs/*.log`。服务一旦进容器，这两条链路都会断。

**可行性已具备**：Docker Desktop **29.7.2**，宿主 12 核 / 32GB，容器配额当前 12 核 / 7.75GiB，
7 个中间件容器已稳定运行 5 天。障碍是「没做」，不是「不能做」。

---

## User Scenarios & Testing

### User Story 1 - 一条命令拉起全栈（Priority: P1）

作为本地开发/演示者，我希望不依赖本机 JDK、Maven 版本，只用 Docker 就能把 10 个服务 + 7 个中间件
完整拉起，并且每次拉起的行为完全一致。

**Why this priority**: 直接消除痛点 1、2——宿主 JDK 与进程生命周期都不再是启动的前置条件。
这是「docker 化」这个词本身承诺的核心价值。

**Independent Test**: 在一台只装 Docker（宿主 java 为 11，不满足 RunMojo）的机器上，
给定已构建好的 fat jar，执行一条 compose 命令后 10 个服务 `/actuator/health` 全返回 200。

**Acceptance Scenarios**:

1. **Given** 仓库已检出且 `deployment/output/jars/` 下 10 个 fat jar 就绪，
   **When** 执行容器模式启动命令，**Then** 10 个服务健康检查全部 200，Nacos 注册列表出现 10 个实例。
2. **Given** 宿主 `java` 为 11（不满足 `RunMojo` 的 Java 17+ 要求），
   **When** 跑容器模式，**Then** 启动成功——证明不再依赖宿主 JDK。
3. **Given** 全栈运行中，**When** 宿主机重启后重跑同一条命令，**Then** 环境与重启前等价（数据卷保留）。

---

### User Story 2 - 宿主模式不被破坏（Priority: P1）

作为正在做 feature 开发的人，我要在 IDE 里断点调试单个服务、改一行代码热重启，
原来的 `bash deployment/start-all.sh` 宿主模式必须继续可用，且行为与今天完全一致。

**Why this priority**: 存量 CI 资产全部绑在宿主进程上——e2e 22/23、压测脚本、demo 5 场景，
其中 demo 的 **UNKNOWN 收敛场景靠 `restart-payment.sh` 以不同 mock-scenario 重载 JVM**。
一旦强制切容器，这些链路全部要重写，风险远超收益。

**Independent Test**: 停掉容器后执行 `bash deployment/start-all.sh`，`bash deployment/demo/run-all.sh`
5 个场景仍全部通过、退出码 0——与容器化改造前基线一致。

**Acceptance Scenarios**:

1. **Given** 宿主模式（全部或部分）正在运行、端口 8081–8091 已被宿主进程占用，
   **When** 尝试启动容器模式，**Then** 前置守卫检测到冲突并**中止**，输出「先 stop-all.sh」的明确提示，
   不产生「容器起来了但 bound 失败」的假成功。
2. **Given** 容器模式运行中，**When** 执行 `bash deployment/start-all.sh`，
   **Then** 同样被守卫拦住并给出对称提示（双向互斥，不是单向）。
3. **Given** 容器模式已完全停止，**When** 执行原 `start-all.sh`，
   **Then** 启动流程与改造前逐步骤一致（含 Nacos 就绪等待、clean 构建、10 个进程后台起）。

---

### User Story 3 - 演示链路在容器模式下同样可跑（Priority: P2）

作为演示者，我要在容器模式下仍能完整走完 demo 五场景，**尤其是 UNKNOWN 权威收敛**——
该场景当前实现是「重启 payment-service 并注入 `payment.channel.mock-scenario=BUSINESS_UNKNOWN`」。

**Why this priority**: 演示是这个学习项目的主出口；若容器模式下演示残缺，
容器化反而成了倒退。但它依赖 US2 解决后才可迁移，故排 P2。

**Independent Test**: 容器模式下执行 demo 全量脚本，5 个场景（主链 / 退款 / UNKNOWN 收敛 /
每日对账 / 审计闭环）全部断言通过、退出码 0。

**Acceptance Scenarios**:

1. **Given** 容器模式全栈运行中，**When** 需要切换到 `BUSINESS_UNKNOWN` 场景，
   **Then** 存在等价手段（重建 payment 容器并注入该环境变量），行为与宿主模式重载 JVM 等价。
2. **Given** UNKNOWN 场景完成，**When** 恢复 `SUCCESS` 场景，**Then** 支付在 20s 内收敛为 SUCCEEDED。
3. **Given** 容器模式，**When** 跑 demo 全量脚本，**Then** 退出码 0，与宿主模式结果一致。

---

### User Story 4 - 可观测链路不因容器化而断裂（Priority: P2）

作为使用者，我要在容器模式下 Grafana 看板仍能看到 10 个服务的业务指标，
Loki Explore 仍能按 `service` / `level` 过滤日志。

**Why this priority**: 可观测是当前唯一把「服务是否真的健康」讲清楚的入口；
容器化若让看板变空，等于用交付物换了一幅不包括仪表的仪表盘。

**Independent Test**: 容器模式下 Grafana「PaymentArch 业务指标」看板有数据；
Loki 中 `{job="payment-arch"}` 能查到全部 10 个服务的日志。

**Acceptance Scenarios**:

1. **Given** 容器模式全栈运行，**When** 查询 Prometheus targets，**Then** 10 个服务 job 全部 UP。
2. **Given** 容器模式全栈运行，**When** 在 Loki 按 `{job="payment-arch"}` 查询，
   **Then** 返回包含全部 10 个服务 `service` 标签的日志流。
3. **Given** 任一模式，**When** 切换另一种模式，**Then** 可观测配置随之适配，不出现 hardcoded 目标失联。

---

### Edge Cases

- **双模式端口抢占**：两种模式同时启动时，`start-all.sh` 与容器模式必须双向检测到对方占用的 8081–8091
  并中止，绝不能「看起来起来了、实则 bind 失败」。
- **Nacos 未就绪就起服务**：容器内 `depends_on` 必须依赖 nacos 的 healthcheck（不能只依赖启动顺序），
  否则 Feign 全部落在 Connection refused 上——这是比报错更难排查的假成功。
- **Nacos gRPC 端口**：compose 网络内需给客户端 gRPC 9848/9849 留出通路，缺失时报
  `Client not connected, current status:STARTING`。
- **fat jar 缺失**：用户直接 `compose up` 但从未构建 → 必须在**启动前**给出「先执行构建」的明确错误，
  而不是容器起来后 COPY 失败再崩。
- **内存配额不足**：当前配额 7.75GiB，放不下 10 个 JVM + 7 个中间件 → 需上调至 16GiB（宿主 32GB），
  或对单个 JVM 施加 `-Xmx384m` 堆上限（宿主模式已在用该值，见 `start-all.sh`）。
- **日志落盘**：容器模式下服务日志不再写宿主 `deployment/logs/`，若 Promtail 仍指向该目录则采集中断。
- **`host.docker.internal` 在 Linux 上不可用**：Prometheus 抓取目标若保留该写法，Linux 宿主会失联。

---

## Requirements

### Functional Requirements

- **FR-001**: 仓库 MUST 提供 **1 个通用 `Dockerfile`**，通过 `ARG JAR_FILE` / 参数化端口供 10 个服务复用，
  不产生 10 份近乎重复的 Dockerfile。
- **FR-002**: Docker image MUST **只做 COPY + ENTRYPOINT**，不在镜像内执行 Maven 构建
  （避免 10 个服务各自全量构建 reactor，公共模块被重复编译 10 次）。
- **FR-003**: 构建链路 MUST 复用**父 POM 已配置的 repackage 输出目录**
  `${maven.multiModuleProjectDirectory}/deployment/output/jars`（当前 10 个 fat jar 已在此）。
- **FR-004**: `docker-compose.yml` MUST 新增 10 个应用服务定义，与 7 个中间件处于**同一个 bridge 网络**。
- **FR-005**: 服务容器 MUST 通过 compose `environment:` 覆盖 Nacos / MySQL / Redis 地址，
  **不修改任何 `application*.yml` 源码**（依据 Spring Boot 环境变量优先级高于 yml 的 relaxed binding）。
- **FR-006**: 启动入口 MUST 提供**模式守卫**：宿主脚本与容器模式双向检测 8081–8091 端口占用，
  冲突即中止并输出可操作提示。
- **FR-007**: compose MUST 用 **profiles 区分 `infra`（仅中间件）与 `full`（全栈）**，
  使宿主模式仍能只起中间件复用同一份 compose 文件。
- **FR-008**: compose MUST 为应用服务配置 `depends_on` + **nacos healthcheck 条件**，
  确保注册中心就绪后才起应用。
- **FR-009**: 容器模式 MUST 提供**等价的 mock-scenario 切换手段**，使 demo 的 UNKNOWN 收敛场景可用。
- **FR-010**: Prometheus 抓取 MUST 在容器模式下从 `host.docker.internal:PORT` 切换为**容器内服务名:端口**，
  且配置不得写死单一模式。
- **FR-011**: Promtail 日志采集 MUST 在两种模式下都能取到 10 个服务日志
  （推荐：容器内仍写文件并挂载到同一日志卷，使 Promtail 配置零改动）。
- **FR-012**: 仓库 MUST 提供 `.dockerignore`，排除 `**/target`、`logs`、`.workbuddy`、`.git` 等构建上下文噪声。
- **FR-013**: 容器模式 MUST 施加 JVM 堆上限（沿用 `-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m`），
  并在文档中声明推荐容器配额 16GiB。
- **FR-014**: 文档 MUST 同步更新，至少覆盖：`README.md`、`deployment/README.md`、
  `docs/operations/runbook.md`、`docs/architecture/technical-solution.md`、
  `docs/architecture/diagrams/08-deployment.puml`（及据此重生成的 SVG；当时文件名为
  `02-deployment-topology.puml`，2026-09-22 文档治理按 C4 层级重命名）。
- **FR-015**: 两种模式的启动入口 MUST 在各自帮助信息里说明「另一种模式怎么起、怎么停」。

### Key Entities

- **运行模式（Run Mode）**: `host`（宿主进程）| `container`（容器）——全局互斥状态，按端口集合判定。
- **应用镜像（App Image）**: `paymentarch/<service>:local`，入参为一个 fat jar + 一个监听端口。
- **端口契约**: 8081 merchant / 8082 catalog / 8083 order / 8084 payment / 8086 fulfillment /
  8087 entitlement / 8088 reconciliation / 8089 settlement / 8090 ledger / 8091 mock-channel-web。
  两种模式对外暴露**相同端口**，这是现有自动化测试零改动的前提。

---

## 非目标

- 不做 Kubernetes / Helm / Operator（单机学习项目，收益为零，理由见 ADR-0070）。
- 不做镜像 registry 推送与 CI 内的 image build。
- 不改动任何业务代码（Java 源码零变更）。
- 不在 Docker 内执行 Maven 构建（多阶段 Maven-in-Docker）。
- 不改 ports 契约（8085 refund 已退役，不复用）。

---

## Success Criteria

### Measurable Outcomes

- **SC-001**: 在一台**只装 Docker、宿主 java 为 11** 的机器上，≤2 条命令
  （构建 jar + 容器模式启动）后，10 个服务 `/actuator/health` 全部 200。
- **SC-002**: 宿主模式下 e2e（22/23 通过、1 例为本地代理伪影 CI 为准）、压测、
  demo 5 场景结果与**改造前基线完全一致**（无回归）。
- **SC-003**: 容器模式下 `bash deployment/demo/run-all.sh` 5 个场景全部通过、退出码 0
  （含 UNKNOWN 权威收敛）。
- **SC-004**: 模式互斥守卫 100% 命中：任一侧先占用端口，另一侧必被拦截，无静默端口抢占。
- **SC-005**: 容器模式下 Prometheus 10 个 job 全部 UP；Loki `{job="payment-arch"}`
  可查到全部 10 个服务的日志流。
- **SC-006**: 全栈容器模式总内存占用 ≤ 16GiB，单个应用容器 JVM 堆上限 384m。
- **SC-007**: `docs/` 与 `deployment/` 下所有引用 `start-all.sh` 的地方，
  均明确标注其所属模式及另一种模式的入口（无过时指引）。

---

## Assumptions

- 目标环境是**本机单机学习与演示**，非生产环境；两种模式都假定「开发者本机 32GB 内存」这一规格。
- 宿主具备构建 fat jar 的能力（实测可行：Java 26 位于
  `/Users/feizhai/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home`）。
- 采用 Spring Boot 环境变量优先级高于 `application.yml` 这一既有事实，故配置适配**不触碰源码**。
- 宿主模式的胖 jar 与容器模式的胖 jar 是**同一份产物**，不存在两套构建。
- Docker Desktop 配额将建议由 7.75GiB 上调至 16GiB（宿主 32GB，配额可调）。
- Prometheus/Promtail 的改造在本 spec 范围内完成（否则可观测断裂会反向拖累 SC-002 的判读）。

---

## 附录：现状实测证据（2026-09-15）

| 项 | 实测结果 |
|---|---|
| Dockerfile / k8s / helm | 无（`find -iname Dockerfile*` 为空） |
| compose 容器数 | 7（mysql / redis / nacos / prometheus / grafana / loki / promtail） |
| 应用服务运行形态 | 10 个宿主 JVM 进程（`./mvnw spring-boot:run`） |
| Nacos 地址写法 | 9 个服务均为 `127.0.0.1:8848`（硬编码） |
| Docker Desktop | 29.7.2 / 12 核 / 7.75GiB，中间件容器已稳定运行 5 天 |
| fat jar 产出 | `deployment/output/jars/` 下 10 个 jar 已存在 |
| Prometheus 目标 | `host.docker.internal:8081~8091` |
| Promtail 源路径 | 宿主 `deployment/logs/{*-service,mock-channel-web}.log` |
