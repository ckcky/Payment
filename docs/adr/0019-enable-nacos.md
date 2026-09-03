<a id="adr-0059"></a>

- **状态**：Accepted（2026-09-04 负责人裁决，落实 ADR-0002 既有意图，撤销 ADR-0056「暂不启用」偏离）
- **日期**：2026-09-04
- **关联**：`ADR-0002`（技术栈选型）、`ADR-0056`（原偏离条目，已反转）、`pom.xml`、`deployment/docker-compose.yml`、`deployment/start-all.sh`

## ADR-0059：启用 Nacos 服务发现与注册中心

### Context（背景）

- `ADR-0002`（技术栈选型）将 Nacos 纳入 Spring Cloud 技术栈，承担服务发现（注册中心）职责。
- `ADR-0056` 因单机/Compose 学习环境，将 Nacos 标为「暂不启用」，12 个 `@FeignClient` 全部硬编码 `url=`，属对 ADR-0002 的临时偏离。
- 2026-09-04 负责人裁决：**需要启用 Nacos**（R1），故撤销该偏离，落实 ADR-0002 的原意。

### Decision（决策）

启用 Nacos 作为注册中心与服务发现：

1. **依赖**：父 POM `dependencyManagement` 引入 `spring-cloud-alibaba-dependencies` BOM **2025.0.0.0**（适配 Spring Boot 3.5.x / Spring Cloud 2025.0.x，内置 Nacos Client 3.0.3）；各服务声明 `spring-cloud-starter-alibaba-nacos-discovery`。
2. **配置**：各服务 `application.yml` 增加 `spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848` 与 `enabled: true`；服务名沿用既有的 `spring.application.name`。
3. **寻址**：移除 12 个 `@FeignClient` 的 `url=` 硬编码，改为由 Nacos 服务发现按服务名解析（LoadBalancer 生效）。
4. **基础设施**：`deployment/docker-compose.yml` 新增 `nacos` 容器（镜像 `nacos/nacos-server:3.0.3`，端口 8848，`MODE=standalone`）；`start-all.sh` 在 `docker compose up -d` 阶段一并拉起 Nacos。
5. **配置中心**：本期仅启用**服务发现**，不引入 Nacos Config 配置中心（`spring.cloud.nacos.config.enabled=false` 或不引入 `nacos-config` starter），避免配置拉取耦合；配置仍走各服务本地 `application.yml`。

### Consequences（后果）

- ✅ 服务间调用经 Nacos 服务发现，去除 IP/端口硬编码，符合 ADR-0002 技术栈意图。
- ✅ 为后续的弹性伸缩、灰度、多实例部署打下基础。
- ⚠️ 本地运行**必须**先启动 Nacos（`start-all.sh` 已覆盖）；若 Nacos 不可用，跨服务 Feign 调用在首次解析时失败（fail-fast 于调用侧，不影响服务自身启动）。
- ⚠️ 版本强约束：Spring Cloud Alibaba 必须与 Spring Boot / Spring Cloud 主版本对齐（本项目锁定 2025.0.0.0 ↔ Boot 3.5 / Cloud 2025.0.x）；升级任一侧须同步校对该矩阵，否则出现 `NoSuchMethod`/BOM 冲突。
- ⚠️ `ADR-0056` 不再作为「偏离」存在；其承载文件保留为决策演进留痕。

### 实施核对清单

- [x] 父 POM 引入 `spring-cloud-alibaba-dependencies` BOM 2025.0.0.0
- [x] 10 个服务 POM 声明 `spring-cloud-starter-alibaba-nacos-discovery`
- [x] 10 个服务 `application.yml` 配置 `spring.cloud.nacos.discovery`
- [x] 12 个 `@FeignClient` 移除 `url=` 硬编码
- [x] `deployment/docker-compose.yml` 新增 `nacos` 服务
- [x] `deployment/start-all.sh` 启动顺序纳入 Nacos
- [x] `mvn` 全量编译通过（在线解析 alibaba BOM）
