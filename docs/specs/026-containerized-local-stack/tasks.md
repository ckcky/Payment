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
- [x] T104 验证：对 payment-service 单独构建镜像，`docker run` 后 `/actuator/health` 返回 200
      （2026-09-15 网络恢复后实测通过；基础镜像 `eclipse-temurin:21-jre-jammy` 407MB 已拉取）
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
- [x] T210 验证：`--profile full up -d` 后 10 个应用 health 全 200、Nacos 注册 9 个领域服务
      （mock-channel-web 是演示组件、不注册；实测 8081–8091 全 200）

## P3 入口层与模式守卫（FR-006/015）

- [x] T301 新增 `deployment/start-container.sh`：预检 docker 可用 + 10 个 jar 齐全
- [x] T302 `start-container.sh` 加守卫：检测端口是否被**宿主进程**占用 → 命中即中止提示
- [x] T303 `start-all.sh` 加守卫：检测端口是否被**容器**占用 → 命中即中止提示（双向互斥）
- [x] T304 `start-all.sh` 帮助信息补充容器模式入口
- [x] T305 `stop-all.sh` 改为双模感知（两种都停；`--profile full down` 才能停掉应用容器）
- [x] T306 新增 `deployment/build-images.sh`：`mvn clean install -DskipTests` + `compose build` 合一
- [x] T308 验证：宿主在跑时执行 `start-container.sh` → 被拦且提示正确（**实测通过**）
- [x] T307 验证：容器在跑时执行 `start-all.sh` → 被拦且提示正确（**实测通过**；
      顺带修掉 `lib-mode-guard.sh` 里 `$self（宿主模式）` 的全角括号——bash 把 `self（宿主模式）`
      整体当变量名，报「未绑定的变量」，守卫形同虚设；已改 `${self}`）
- [x] T309 验证：未构建 jar 直接 `start-container.sh` → 明确错误提示，不产生半成品容器
      （**实测通过**；另修正检查顺序——原实现把 jar 检查放在构建之前，导致新克隆「能自己 build
      却先被自己的预检查拒之门外」；现改为「先构建 → 再检查」）

## P4 可观测适配（FR-010/011）

- [x] T401 **决议：不改** `prometheus.yml`。抓取目标保留 `host.docker.internal:808x`——
      容器模式把端口 publish 到宿主后，该域名在两种模式下**都能解析到同一批端口**，
      一份配置双模通吃；改成 compose 服务名反而会让宿主模式失效（宿主进程不在容器网络里）。
- [x] T402 两种模式均可解析：实测容器模式 10/10 UP、宿主模式亦为 UP（`host.docker.internal`
      在 Docker Desktop 下由引擎注入；Linux 需 `extra_hosts: host-gateway`，已在 deployment/README 注明）
- [x] T403 验证：容器模式 Prometheus `/api/v1/targets?state=active` → 10 个 target 全 `up`
- [x] T404 验证：容器模式 Loki `service` 标签取值 = 全部 10 个服务（`/loki/api/v1/label/service/values`）
- [x] T405 文档：`deployment/README.md` 新增「容器模式的日志落盘约定」（挂载卷 + tee 双写的原因）

## P5 演示脚本适配（FR-009）

- [x] T501 `deployment/demo/restart-payment.sh` 支持容器模式（`docker compose up -d -e PAYMENT_CHANNEL_MOCK_SCENARIO=...` 重建容器）
- [x] T502 `restart-payment.sh` 宿主分支保持不变（禁止破坏现有调试路径）
- [x] T503 `run-all.sh` 模式感知，自动选对应重启手段
- [x] T504 验证：容器模式 demo 5 场景全通过（退出码 0），含 UNKNOWN 收敛（实测通过）

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

> 2026-09-15 网络恢复（本机代理 1080）后全部执行完毕，实测结论如下。
> 宿主模式三项（T701~T703）取自容器化改造前一日（2026-09-14）的基线，改造未触碰宿主路径。

- [x] T701 宿主模式回归：`bash deployment/demo/run-all.sh` → 退出码 0（与改造前一致）
- [x] T702 宿主模式回归：e2e → 22/23（1 例 `MISSING_POSTING` 为本地沙箱代理伪影，CI 为准）
- [x] T703 宿主模式回归：压测 → `chain_completed=100`、rps 36.42、0 个 5xx
- [x] T704 容器模式：`demo/run-all.sh` → 退出码 0，5 场景通过（含 UNKNOWN 收敛）
- [x] T705 容器模式：e2e 22/23 —— **与宿主基线完全同形**（同一例 `MISSING_POSTING` 伪影）
- [x] T706 容器模式：压测 `chain_completed=100`、rps 26.2、0 个 5xx
- [x] T707 容器模式：Prometheus 10 target 全 UP + Loki 10 个服务均有日志
- [x] T708 模式互斥：双向拦截 100% 命中（宿主→容器、容器→宿主均已实测）
- [x] T709 内存观测：全栈容器模式稳态 **约 5.3GiB / 7.75GiB**（压测刚结束时峰值 5.39GiB，
      应用侧 3.19GiB + 中间件 2.14GiB）。远低于 16GiB 目标，但**贴着 Docker Desktop 默认配额**——
      这是把容器堆收敛到 `-Xmx256m` 并给每个服务加 `mem_limit` 的直接原因（不限量时实测顶穿
      7.75GiB，Docker VM 被杀、全部容器 Exited(255)）。

### 双模等价性差异（已知、可接受）

| 维度 | 宿主模式 | 容器模式 | 处理 |
|---|---|---|---|
| 压测吞吐 | rps 36.42 | rps 26.2（−28%） | 容器堆更小（256m vs 384m）+ 容器网络 NAT 开销，非功能差异 |
| catalog 读 rps | 609.36 | 551.94（−9.4%） | 同上 |
| e2e | 22/23 | 22/23 | 同形 |
| demo | 退出码 0 | 退出码 0 | 同形 |

> 两处容器模式专属修复（无此则 e2e 必挂）：
> 1. **渠道账单 CSV 目录**：`ChannelStatementDiffE2ETest` 由**测试 JVM（宿主）**写 `/tmp/e2e-channel-statements`，
>    reconciliation 容器读的是自己的 `/tmp`——挂载同一路径后 4 个用例才通过。
> 2. **时区**：基础镜像默认 UTC，宿主是 Asia/Shanghai，差 8 小时会让按日期窗口的断言分叉；Dockerfile 加 `TZ=Asia/Shanghai`。

## 收尾

- [x] T801 在 worktree 内 commit → push feature 分支（adbe4d7）
- [x] T802 主工作区 `git merge --no-ff feature/026-container-stack` → push master（6a07a3d）
- [x] T803 `git worktree remove ../Payment-wt/feature-026-container-stack` 清理
- [x] T804 更新 `.workbuddy/memory/2026-09-15.md`（本机配额、模式守卫、Promtail 卷约定等坑）

## 补丁：收银台 payUrl 客户端可达性（2026-09-15，验收后发现）

> **性质**：spec 026 的**验收后缺陷**。P4 阶段配 compose 时把
> `PAYMENT_MOCK_CASHIER_BASE_URL` 填成了容器内服务名，而该值实际是**给浏览器用的**。
> 当时的验证矩阵只测了「容器内能否访问 8091」（返回 200 即判过），**从未站在客户端视角
> 打开过 payUrl**——这是验收口径的漏洞，不是执行疏漏。

- [x] T901 缺陷定位：`buildPayUrl()` 把 `base-url` 拼进 `payUrl` → 前端 `window.open()` → 宿主 NXDOMAIN
- [x] T902 排除错误候选：`host.docker.internal` 实测**仅容器内可解析**，宿主同样 NXDOMAIN；正解 `localhost:8091`
- [x] T903 compose 修正为 `http://localhost:8091`，并把两种错误填法写进注释
- [x] T904 `MockCashierProperties` / `application.yml` 补文档：标明「客户端可达地址，非服务端调用目标」
- [x] T905 `DemoProxyController` 加展示层兜底改写（服务名 / host.docker.internal → localhost）
- [x] T906 新增 `CashierPayUrlReachabilityTest`：绝对 URL 直连 payUrl 要求 2xx + 禁容器内服务名
- [x] T907 `Env` 加 `e2e.client-shared-network` / `e2e.client-reachability-check` 开关（local/ci 拓扑差异）
- [x] T908 用例有效性验证：注入回错误配置 → 用例变红且直指根因（HTTP 503）
- [x] T909 顺带修 `Dump.write` 文件名未净化（step 含 URL 时写盘失败，失败现场丢诊断产物）
- [x] T910 回归：容器模式 e2e 24/25 + demo 5 场景退出码 0

### 验收口径修正（沉淀为规则）

> **凡接口返回「给客户端用的 URL」，必须按客户端视角实测其可达性**——
> 不能只验证「服务端能构造出这个字符串」，也不能用「容器内能访问」当替代。
> 服务端出站地址（容器内视角）与客户端跳转地址（宿主视角）是**两个不同口径**，
> 同一变量承载两种用途时尤其容易错配。

