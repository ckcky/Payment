<a id="adr-0070"></a>

# ADR-0070: 本地全栈容器化——Compose 而非 K8s，双轨并存而非强制迁移（spec 026 立项）

- 状态：🟡 **Proposed**（2026-09-15 由负责人提出；决策已采纳，实施尚未开始）
- 关联：[spec 026](../specs/026-containerized-local-stack/spec.md)、ADR-0059（Nacos 硬依赖）、
  ADR-0064（refund 并库，8085 退役）、ADR-0048（mock-channel-web 为演示组件）
- **Supersedes: ADR-0057**（「服务未容器化」，理由「容器化非当前目标」于 2026-09-15 被本决策推翻：
  宿主 JDK 版本绑架与进程生命周期脆弱两个真实痛点已证明容器化对本项目具备学习与实践价值）
- 需求源头：负责人 2026-09-15——「搞个 spec，把 docker 化做上，相应的文档还有什么启动脚本这些也要更新。
  用了 docker 之后我之前的 start-all 那个脚本还能用吗」
- 追加决策（同日拍板）：①双轨并存 + 模式开关；②宿主打 jar + 镜像只 COPY。

## 背景

**现状核实（2026-09-15 实测）**：

- 项目处于**半 docker 化**：`deployment/docker-compose.yml` 只编排 7 个中间件
  （mysql / redis / nacos / prometheus / grafana / loki / promtail），运行良好（已稳定 5 天）；
  而 9 领域服务 + mock-channel-web 全是**宿主 JVM 进程**，靠 `./mvnw spring-boot:run` 起。
- 全仓库**没有 Dockerfile / .dockerignore / k8s / helm**。
- compose 头注释自己就写着「各微服务镜像与编排在 **Dockerfile 就绪后补齐**；
  当前本地运行以 `./mvnw` 直接启动各服务」「服务以**宿主进程运行（非容器）**」——
  即容器化一直是「计划中未做」，不是被否决。

**本次会话踩到的具体痛点**（不是理论推演）：

1. **宿主 JDK 版本绑架启动**：`restart-payment.sh` 走 `./mvnw`，本机默认 java 为 11，
   而 `spring-boot-maven-plugin:3.5.15` 的 `RunMojo` 要求 class version 61（Java 17+）
   → `UnsupportedClassVersionError`，payment-service 起不来，demo UNKNOWN 场景被迫中断。
   修复方式竟是手工 `export JAVA_HOME=/Users/feizhai/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home`。
2. **宿主进程生命周期脆弱**：演示任务一结束，其后台起的 payment 进程被回收（8084=000），
   而后续压测未察觉，导致 97 条链路全 `payment_create:500`、报告作废，必须重跑。
3. **配置硬编码本地回环**：9 个服务 `spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848`
   ——容器内 `127.0.0.1` 指向容器自身，这是唯一实质性的代码级障碍。

**可行性已具备**：Docker Desktop 29.7.2 / 宿主 12 核 32GB / 容器配额 12 核 7.75GiB（可调）；
且父 POM 已把 fat jar repackage 到 `deployment/output/jars/`，**10 个 jar 现成就绪**。

## 决策

### D1. 用 Docker Compose，**不用 Kubernetes**

本项目是**单机学习与演示**项目，全部 10 个服务跑在一台 Mac 上。K8s 的收益——跨节点调度、
自动扩缩、滚动发布、自愈漂移——要到「多机器 + 生产流量」才成立；在单机上它只增加一层抽象和一批
概念（Pod / Service / Ingress / ConfigMap / Deployment），收益为零而认知成本为正。

Compose 就能完整交付「环境可复现、一条命令拉起、不再依赖宿主 JDK」这三个真实诉求。
**若日后真要学 K8s，应作为独立学习任务，而不是把它塞进本项目的部署链路。**

### D2. 双轨并存 + 模式开关，**不强制迁移**

保留 `bash deployment/start-all.sh`（宿主模式）完整可用，同时新增容器模式入口。
两条链路**共用同一份 fat jar、同一套端口契约（8081–8091）、同一个数据卷**，由模式守卫保证互斥。

理由：

- 存量 CI 资产全部建立在宿主进程上——e2e、压测、demo 5 场景；尤其 demo 的 **UNKNOWN 权威收敛**
  依赖「以 `payment.channel.mock-scenario=BUSINESS_UNKNOWN` 重载 JVM」，容器化后必须提供等价手段，
  否则演示残缺等于倒退。
- IDE 里断点调试单个服务、改一行热重启，是学习项目的高频场景，宿主模式不可替代。
- 容器模式的价值（消除宿主 JDK 依赖、进程由 Docker 托管）与宿主模式**并不冲突**。

**代价**：模式守卫必须做到双向 100% 命中。若出现「看起来起来了、实则 bind 失败」的静默抢占，
那比任何报错都更难排查——这是本设计的**主要风险**，已在 spec Edge Cases 与 plan 验证矩阵中列为硬性放行条件。

### D3. 宿主打 jar，**镜像只 COPY**

`mvn clean install -DskipTests` 在宿主产出 fat jar 到 `deployment/output/jars/`，
Dockerfile 仅 `COPY` + `ENTRYPOINT`。

理由：10 个服务共享 3 个 common 模块。若在 Docker 内多阶段构建（Maven-in-Docker），
**每个服务的构建都要重跑整个 reactor 并把公共模块重编译一遍 ×10**，首次构建时间极其糟糕；
而宿主只需构建一次。既然父 POM 早已把所有 fat jar 统一输出到 `deployment/output/jars/`，
这条路径几乎是零成本。

**代价**：容器模式启动前必须先构建 jar。缓解方式是在入口脚本做前置校验，
缺失时给出明确构建命令而非让 COPY 失败后崩溃，并提供 `build-images.sh` 把两步合一。

### D4. 配置适配走**环境变量覆盖**，不改源码

依赖 Spring Boot「环境变量优先级高于 `application.yml`」的 relaxed binding 事实：
在 compose 里设置 `SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=nacos:8848` 即可覆盖硬编码的
`127.0.0.1:8848`，**业务源码零变更**。这符合本项目「接口/交付边界清晰」的一贯取向，
也避免为了本地部署把配置写死成不可移植的形态。

### D5. 统一 network + profiles + healthcheck 依赖

- 中间件与应用同处 `pay-arch` bridge 网络；
- 用 **compose profiles** 区分 `infra`（仅中间件）与 `full`（全栈），使宿主模式仍能复用同一份 compose；
- 应用服务必须对 nacos `depends_on: condition: service_healthy`。Nacos 是所有 `@FeignClient`
  的硬依赖（ADR-0059），只依赖启动顺序会造成「服务起来了但所有跨服务调用 Connection refused」的假成功
  ——这类问题比直接失败难排查一个数量级。

## 影响

**正面**：

- 摆脱宿主 JDK 版本绑架，启动不再依赖 `JAVA_HOME` 手工设置；
- 进程生命周期由 Docker 托管，不再因任务/终端生命周期而被回收；
- 环境可复现：任意装了 Docker 的机器一条命令拉起完整学习环境。

**负面 / 需持续维护**：

- 模式守卫属新增约定，必须持续被强制校验（入口脚本双向检测 + 验证矩阵），否则静默端口抢占；
- 可观测配置（Prometheus targets / Promtail 路径）需随模式适配，否则容器模式下看板为空却不易察觉；
- 内存占用上升；建议宿主配额由 7.75GiB 上调至 16GiB，并对单 JVM 施加 `-Xmx384m`。

**明确不做**：

- 不做 K8s / Helm / Operator（见 D1）；
- 不做镜像 registry 推送与 CI 内 image build；
- 不改动任何 Java 业务源码（见 D4）；
- 不在 Docker 内跑 Maven 构建（见 D3）；
- 不复用已退役的 8085（ADR-0064）。

## 复核清单

- [ ] 容器模式：10 服务 health 全 200，Nacos 注册 10 实例
- [ ] 容器模式：demo 5 场景通过（含 UNKNOWN 收敛）
- [ ] 宿主模式：e2e / 压测 / demo 结果与改造前一致（无回归）
- [ ] 模式互斥守卫双向 100% 命中
- [ ] Prometheus 10 job UP + Loki 10 服务有日志
