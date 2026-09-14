# Tasks: 026-containerized-local-stack

- 版本: v1
- 日期: 2026-09-15
- 对应: [spec 026](./spec.md) / [plan 026](./plan.md) / ADR-0070
- 说明：本文件只登记**实施任务**；实际代码改动须走 feature 分支 + PR（engineering-standards §6），
  不得在 master 直接提交。

---

## 前置：分支纪律

- [ ] T0.1 `git checkout master && git pull`
- [ ] T0.2 `git worktree add ../Payment-wt/feature-026-container-stack -b feature/026-container-stack origin/master`
- [ ] T0.3 实操提示：所有命令在该 worktree 内进行（沙箱后台任务不继承前台 cd，长命令须写包装脚本 + `wait` 保活）

---

## P1 镜像层（FR-001/002/003/012）

- [ ] T101 新建 `deployment/docker/Dockerfile`：参数化 `JRE_IMAGE` / `JAR_FILE` / `SERVICE_PORT`，只 COPY + ENTRYPOINT
- [ ] T102 Dockerfile 内置 JVM 堆上限 `-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m`（与宿主模式一致）
- [ ] T103 新建 `deployment/docker/.dockerignore`：排除 `**/target`、`logs/`、`*.log`、`.git/`、`.workbuddy/`、`node_modules`
- [ ] T104 验证：对 payment-service 单独构建镜像，`docker run` 后 `/actuator/health` 返回 200
- [ ] T105 验证：镜像内无 Maven / 无源码 / 无 `.git`（确认「只 COPY」而非「镜像内构建」）

## P2 编排层（FR-004/005/007/008）

- [ ] T201 compose 新增 `networks.pay-arch`（bridge），中间件与应用同网络
- [ ] T202 新增 10 个应用服务定义，端口映射 8081/8082/8083/8084/8086/8087/8088/8089/8090/8091（**无 8085**）
- [ ] T203 应用服务加 `profiles: [full]`；中间件保持可用 `infra` 语义
- [ ] T204 每个应用服务 `environment` 覆盖：Nacos `server-addr`（→ `nacos:8848`）、MySQL / Redis 地址
- [ ] T205 应用服务 `depends_on: nacos: condition: service_healthy`（**必须**，否则 Connection refused 假成功）
- [ ] T206 payment-service 把 `payment.channel.mock-scenario` 提升为可注入 compose 变量（供 demo UNKNOWN 场景）
- [ ] T207 应用容器挂 `./logs:/var/log/payment-arch`，使 Promtail 现配置零改动
- [ ] T208 更新 compose 头注释（移除「Dockerfile 就绪后补齐」「服务以宿主进程运行」等过期表述）
- [ ] T209 验证：`docker compose --profile infra up -d` 只起 7 个中间件
- [ ] T210 验证：`docker compose --profile full up -d` 后 Nacos 注册列表含 10 个实例、10 个 health 全 200

## P3 入口层与模式守卫（FR-006/015）

- [ ] T301 新增 `deployment/start-container.sh`：预检 docker 可用 + 10 个 jar 齐全
- [ ] T302 `start-container.sh` 加守卫：检测端口是否被**宿主进程**占用 → 命中即中止提示
- [ ] T303 `start-all.sh` 加守卫：检测端口是否被**容器**占用 → 命中即中止提示（双向互斥）
- [ ] T304 `start-all.sh` 帮助信息补充容器模式入口
- [ ] T305 `stop-all.sh` 改为双模感知（自动停活动侧 / 两侧尝试）
- [ ] T306 新增 `deployment/build-images.sh`：`mvn clean install -DskipTests` + `compose build` 合一
- [ ] T307 验证：容器在跑时执行 `start-all.sh` → 被拦且提示正确
- [ ] T308 验证：宿主在跑时执行 `start-container.sh` → 被拦且提示正确
- [ ] T309 验证：未构建 jar 直接 `start-container.sh` → 明确错误提示，不产生半成品容器

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

- [ ] T601 `README.md`：双模快速开始 + 「二选一」警告
- [ ] T602 `deployment/README.md`：脚本清单 / profiles 用法 / 推荐配额 16GiB
- [ ] T603 `docs/operations/runbook.md`：启动、停止、排障按模式分节
- [ ] T604 `docs/architecture/technical-solution.md`：部署形态更新
- [ ] T605 `docs/architecture/diagrams/02-deployment-topology.puml` 补容器模式形态并重生成 SVG
- [ ] T606 `CHANGELOG.md` 记录容器化
- [ ] T607 全仓扫描 `start-all.sh` / `spring-boot:run` 的引用，逐处标注所属模式
- [ ] T608 扫描复核：所有过时指引已清理（SC-007）

## P7 验证矩阵（SC-001~SC-007）

- [ ] T701 宿主模式回归：`bash deployment/demo/run-all.sh` → 退出码 0（与改造前一致）
- [ ] T702 宿主模式回归：e2e → 与基线一致（22/23，1 例为本地代理伪影，CI 为准）
- [ ] T703 宿主模式回归：压测 → 链路 completed 且 0 个 5xx
- [ ] T704 容器模式：`demo/run-all.sh` → 退出码 0，5 场景通过
- [ ] T705 容器模式：e2e 通过
- [ ] T706 容器模式：压测链路 completed 且 0 个 5xx
- [ ] T707 容器模式：Prometheus 10 job UP + Loki 10 服务有日志
- [ ] T708 模式互斥：双向拦截 100% 命中
- [ ] T709 内存观测：全栈容器模式实测占用 ≤ 16GiB，记录实测值

## 收尾

- [ ] T801 在 worktree 内 commit → push feature 分支
- [ ] T802 主工作区 `git merge --no-ff feature/026-container-stack` → push master
- [ ] T803 `git worktree remove ../Payment-wt/feature-026-container-stack` 清理
- [ ] T804 更新 `.workbuddy/memory/YYYY-MM-DD.md`（本机配额、模式守卫、Promtail 卷约定等坑）
