# Tasks: 026-containerized-local-stack

- 版本: v1
- 日期: 2026-09-15
- 对应: [spec 026](./spec.md) / [plan 026](./plan.md) / ADR-0070
- 说明：本文件只登记**实施任务**；实际代码改动须走 feature 分支 + PR（engineering-standards §6），
  不得在 master 直接提交。

---

## 前置：分支纪律

- [x] T0.1 `git checkout master && git pull`
- [x] T0.2 `git worktree add ../Payment-wt/feature-026-container-stack -b feature/026-container-stack origin/master`
- [x] T0.3 实操提示：所有命令在该 worktree 内进行（沙箱后台任务不继承前台 cd，长命令须写包装脚本 + `wait` 保活）
- [x] T0.4 合并收尾：`--no-ff` 合入 master（6a07a3d）→ push → worktree 已清理

---

## P1 镜像层（FR-001/002/003/012）

- [x] T101 新建 `deployment/docker/Dockerfile`：参数化 `JRE_IMAGE` / `JAR_FILE` / `SERVICE_PORT`，只 COPY + ENTRYPOINT
- [x] T102 Dockerfile 内置 JVM 堆上限 `-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m`（与宿主模式一致）
- [x] T103 新建 `.dockerignore`（**置于仓库根**，因构建上下文是仓库根）：排除 `**/target`、`logs/`、`*.log`、`.git/`、`.workbuddy/`、`node_modules`
- [ ] T104 验证：对 payment-service 单独构建镜像，`docker run` 后 `/actuator/health` 返回 200 —— ⛔ **阻塞**：基础镜像拉不到（Docker Hub 不可达）
- [x] T105 验证（部分）：以 `FROM scratch` 离线验证 10 个 jar 的 COPY 路径全部可解析；构建上下文实测 950B，确认 `.dockerignore` 生效且未误伤 jar

## P2 编排层（FR-004/005/007/008）

- [x] T201 compose 新增 `networks.pay-arch`（bridge），中间件与应用同网络
- [x] T202 新增 10 个应用服务定义，端口映射 8081/8082/8083/8084/8086/8087/8088/8089/8090/8091（**无 8085**）
- [x] T203 应用服务加 `profiles: [full]`；中间件 `infra` 语义可用
- [x] T204 每个应用服务 `environment` 覆盖：Nacos（→ `nacos:8848`）、MySQL / Redis 地址；
      另修正 `mock-channel-web` 的 `/proxy` 目标（`MOCK_CHANNEL_SERVICES_*`，原硬编码 localhost 会全 502）
- [x] T205 应用服务 `depends_on: nacos: condition: service_healthy`（**必须**，否则 Connection refused 假成功）
- [x] T206 payment-service 把 `payment.channel.mock-scenario` 暴露为 `PAYMENT_CHANNEL_MOCK_SCENARIO`
      （**注意真实键在 channel 层**，非 `payment.mock-scenario`）
- [x] T207 应用容器挂 `./logs:/var/log/payment-arch`，使 Promtail 现配置零改动
- [x] T208 更新 compose 头注释（移除「Dockerfile 就绪后补齐」「服务以宿主进程运行」等过期表述）
- [x] T209 验证：`docker compose --profile infra up -d` 只起 7 个中间件（实测通过）
- [ ] T210 验证：`--profile full up -d` 后 Nacos 注册 10 个实例、health 全 200 —— ⛔ **阻塞**：同上（基础镜像不可达）

## P3 入口层与模式守卫（FR-006/015）

- [x] T301 新增 `deployment/start-container.sh`：预检 docker 可用 + 10 个 jar 齐全
- [x] T302 `start-container.sh` 加守卫：检测端口是否被**宿主进程**占用 → 命中即中止提示
- [x] T303 `start-all.sh` 加守卫：检测端口是否被**容器**占用 → 命中即中止提示（双向互斥）
- [x] T304 `start-all.sh` 帮助信息补充容器模式入口
- [x] T305 `stop-all.sh` 改为双模感知（两种都停；`--profile full down` 才能停掉应用容器）
- [x] T306 新增 `deployment/build-images.sh`：`mvn clean install -DskipTests` + `compose build` 合一
- [x] T308 验证：宿主在跑时执行 `start-container.sh` → 被拦且提示正确（**实测通过**）
- [ ] T307 验证：容器在跑时执行 `start-all.sh` → 被拦且提示正确 —— ⛔ 待基础镜像可拉取后补测
- [ ] T309 验证：未构建 jar 直接 `start-container.sh` → 明确错误提示，不产生半成品容器 —— ⛔ 待补测

## P4 可观测适配（FR-010/011）

- [ ] T401 `prometheus.yml` 容器模式目标改为 compose 服务名（`payment-service:8084` 等）
- [ ] T402 保证 Prometheus 目标在两种模式下均可解析（避免 Linux 上 `host.docker.internal` 失联）
- [ ] T403 验证：容器模式 Prometheus targets 页面 10 个 job 全部 UP
- [ ] T404 验证：容器模式 Loki `{job="payment-arch"}` 能查到全部 10 个服务日志
- [ ] T405 文档说明「容器日志写挂载卷」这一约定

## P5 演示脚本适配（FR-009）

- [ ] T501 `deployment/demo/restart-payment.sh` 支持容器模式（带 mock-scenario 环境变量重建容器）
- [ ] T502 `restart-payment.sh` 宿主分支保持不变（禁止破坏现有调试路径）
- [ ] T503 `run-all.sh` 模式感知，自动选对应重启手段
- [ ] T504 验证：容器模式 demo 5 场景全通过（退出码 0），含 UNKNOWN 收敛

## P6 文档同步（FR-014）

- [x] T601 `README.md`：双模快速开始 + 「二选一」警告（并订正原「不是全量服务容器化」的过时表述）
- [x] T602 `deployment/README.md`：脚本清单 / profiles 用法 / 容器模式说明 / 宿主模式 JDK 17+ 前提
- [x] T603 `docs/operations/runbook.md`：启动先选模式；UNKNOWN 场景两种模式各自的切换手段
- [x] T604 `docs/architecture/technical-solution.md`：部署形态改为双模式表 + 「明确不做 K8s」
- [x] T605 `docs/architecture/diagrams/02-deployment-topology.puml` 补容器模式形态（**SVG 待重新渲染**，需 PlantUML）
- [x] T606 `CHANGELOG.md` 记录容器化与已知未完成项
- [x] T607 全仓扫描 `start-all.sh` / `spring-boot:run` 的引用，逐处标注所属模式
- [x] T608 扫描复核：主要过时指引已清理（SC-007 部分达成）

## P7 验证矩阵（SC-001~SC-007）

> ⛔ **整段阻塞**：镜像构建需拉取 `eclipse-temurin:21-jre-jammy`，当前环境**无法访问 Docker Hub**
> （经代理 / 不经代理均 HTTP 000，`docker pull hello-world` 亦超时）。网络恢复后按序执行。

- [ ] T701 宿主模式回归：`bash deployment/demo/run-all.sh` → 退出码 0（与改造前一致）
- [ ] T702 宿主模式回归：e2e → 与基线一致（22/23，1 例为本地代理伪影，CI 为准）
- [ ] T703 宿主模式回归：压测 → 链路 completed 且 0 个 5xx
- [ ] T704 容器模式：`demo/run-all.sh` → 退出码 0，5 场景通过
- [ ] T705 容器模式：e2e 通过
- [ ] T706 容器模式：压测链路 completed 且 0 个 5xx
- [ ] T707 容器模式：Prometheus 10 job UP + Loki 10 服务有日志
- [ ] T708 模式互斥：双向拦截 100% 命中（目前仅验证宿主→容器一侧）
- [ ] T709 内存观测：全栈容器模式实测占用 ≤ 16GiB，记录实测值

## 收尾

- [x] T801 在 worktree 内 commit → push feature 分支（adbe4d7）
- [x] T802 主工作区 `git merge --no-ff feature/026-container-stack` → push master（6a07a3d）
- [x] T803 `git worktree remove ../Payment-wt/feature-026-container-stack` 清理
- [x] T804 更新 `.workbuddy/memory/2026-09-15.md`（本机配额、模式守卫、Promtail 卷约定等坑）
