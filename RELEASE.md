# Payment Platform —— 发布包使用说明

本包是一个**可直接下载运行的发布包**：解压后执行 `./run.sh` 即可在本机拉起完整支付平台
（基础设施容器 + 9 个领域服务 + 1 个演示组件，共 10 个 JVM 进程），并内置**单元测试**
与**性能压测**入口。

> 版本：见同目录 `VERSION`。想获取更新版本，到 GitHub Releases 下载对应 `payment-platform-*.tar.gz`。

---

## 1. 前置条件

| 依赖 | 版本 / 说明 | 验证命令 |
|---|---|---|
| JDK | **21 LTS**（强制，ADR-0059 等基于 Spring Boot 3.5） | `java -version` |
| Docker | 含 **Compose v2**（用 `docker compose` 子命令） | `docker --version` |
| Node.js | **>= 18**（仅压测用，跑零依赖负载生成器） | `node --version` |
| 网络 | 首次 `./mvnw` 会拉取 Maven 依赖（一次性） | — |

> 不需要本地装 MySQL / Redis / Nacos —— 它们由 `docker compose` 以容器形式拉起。

---

## 2. 目录结构（发布包内）

```
payment-platform-<version>/
├── VERSION / RELEASE.md        # 版本与本文档
├── run.sh  stop.sh             # 一键启动 / 停止（含 JDK 检查）
├── run-tests.sh                # 单元测试入口
├── run-stress.sh               # 性能压测入口
├── mvnw / .mvn / pom.xml       # Maven Wrapper + 多模块构建
├── deployment/
│   ├── docker-compose.yml      # MySQL/Redis/Nacos/Prometheus/Grafana/Loki/Promtail
│   ├── start-all.sh stop-all.sh
│   ├── initdb/ schema/         # 建库与建表 DDL
│   ├── demo/                   # 演示场景脚本（happy-path / 退款 / 对账…）
│   └── performance/            # 压测脚本（k6 + Node 零依赖版）+ 报告生成
└── <各服务源码与模块 pom>
```

---

## 3. 快速开始

```bash
# 1) 解压
tar -xzf payment-platform-<version>.tar.gz
cd payment-platform-<version>

# 2) 一键启动全栈（首次会全量构建，约 1~3 分钟）
./run.sh
```

启动完成后可用入口：

| 入口 | 地址 |
|---|---|
| 演示控制台（下单→收银台→轮询） | http://localhost:8091/demo |
| 各服务 Swagger | http://localhost:8081~8090/swagger-ui.html |
| Grafana（业务看板） | http://localhost:3000 （admin/admin） |
| Prometheus | http://localhost:9090 |
| MySQL | localhost:3306 （root/root） |

实时日志：`tail -f deployment/logs/payment-service.log`

---

## 4. 运行测试

```bash
./run-tests.sh          # 等价 ./mvnw test，运行全部单元测试
```

首次运行会拉取 Maven 依赖（需网络）。纯单元测试不依赖基础设施。

---

## 5. 运行压测

压测需要全栈已启动并就绪（`./run.sh` 跑起来、Nacos 8848 可访问）。

```bash
./run-stress.sh         # 跑 catalog 缓存读+秒杀 + 全链路下单→支付→退款，并出 HTML 报告
```

报告与原始数据落在 `deployment/performance/results/`：

- `stress-chain-perf-report.html` —— 自包含 HTML 性能报告（内联 SVG）
- `stress-catalog-load.json` / `stress-chain-load.json` —— 原始结果

> 项目同时提供 k6 版压测脚本（`deployment/performance/*-k6.js`），如需使用请自行安装
> [k6](https://k6.io)；本包默认用 Node 版负载生成器，**零外部二进制依赖**。

---

## 6. 停止

```bash
./stop.sh               # 杀掉 JVM 进程 + 停止容器（保留 MySQL 数据卷）
```

---

## 7. 常见问题

- **`需要 JDK 21`**：本机装了多个 JDK 时，用 `JAVA_HOME` 指向 21，或在 `run.sh` 前
  `export JAVA_HOME=/path/to/jdk-21`。
- **`未找到 docker`**：确认 Docker Desktop 已启动（Windows/macOS）或 docker 引擎在运行（Linux）。
- **Nacos 一直没就绪**：检查 8848/9848/9849 端口是否已被占用；`docker logs payment-nacos` 看注册中心日志。
- **首次构建慢 / 依赖拉取失败**：确认网络可访问 Maven Central；可配置 `MAVEN_ARGS='-o'` 走离线（需先有本地仓库）。
- **想清掉数据重来**：`docker compose -f deployment/docker-compose.yml down -v`（会删除 MySQL 数据卷）。

---

## 8. 端口速查

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| merchant-service | 8081 | reconciliation-service | 8088 |
| catalog-service | 8082 | settlement-service | 8089 |
| order-service | 8083 | ledger-service | 8090 |
| payment-service（含退款） | 8084 | mock-channel-web（演示） | 8091 |
| fulfillment-service | 8086 | Nacos | 8848 / 9848 / 9849 |
| entitlement-service | 8087 | MySQL / Redis / Prometheus / Grafana / Loki | 3306 / 6379 / 9090 / 3000 / 3100 |
