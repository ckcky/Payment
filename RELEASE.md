# Payment Platform —— 发布说明

发布形态为**可直接运行的二进制发行包**（预构建 fat jar，非源码）：目标机器只需
**JDK 21 + Docker**，解压 → `bash start.sh` 即可拉起完整支付平台（基础设施容器 +
9 个领域服务 + 1 个演示组件，共 10 个 JVM 进程）。

> 版本号见仓库根 `VERSION`。历史版本到 [GitHub Releases](https://github.com/ckcky/Payment/releases)
> 下载对应 `payment-platform-*-bin.tar.gz`。

---

## 1. 发布流程

```
tag push (v*)  →  GitHub Actions（.github/workflows/release.yml）
                  mvnw verify → deployment/release/make-release.sh 打包
                  → 自动创建 Release 并上传 payment-platform-*-bin.tar.gz(+sha256)
```

手动打包：`bash deployment/release/make-release.sh`
（产物落仓库根目录 `payment-platform-<版本>-bin.tar.gz`，已 gitignore）

---

## 2. 使用发行包

包内自带完整使用说明（`README.md`，源文件在 `deployment/release/README.md`）。
速览：

```bash
tar -xzf payment-platform-<version>-bin.tar.gz
cd payment-platform-<version>
bash start.sh        # 基础设施容器 + 幂等建表 + java -jar 起 10 进程
bash reset-demo.sh   # 复位演示数据 + 灌种子
```

| 入口 | 地址 |
|---|---|
| 演示控制台（下单→收银台→退款→全链路落库） | http://localhost:8091/demo |
| 审计对账控制台 | http://localhost:8091/audit |
| Grafana（SRE 黄金指标看板） | http://localhost:3000 （admin/admin） |
| 各服务 Swagger | http://localhost:8081~8090/swagger-ui.html |

环境变量（`PAYMENT_NACOS_IP` / JVM 内存上限等）与端口速查见包内 README。

---

## 3. 源码仓库内的等价操作

| 场景 | 入口 |
|---|---|
| 本地起开发栈 | `deployment/start-all.sh` / `deployment/stop-all.sh` |
| 单元测试 | `./mvnw test` |
| 性能压测 | `deployment/performance/run-stress.sh`（Node 零依赖版）或 `deployment/performance/*-k6.js`（k6 版） |
| 演示场景 | `deployment/demo/run-all.sh`（happy-path / 退款 / 对账 / 审计…） |
| 发行打包 | `deployment/release/make-release.sh` |

---

## 4. 常见问题

- **多 JDK**：用 `JAVA_HOME` 指向 JDK 21 后再启动。
- **Nacos 一直没就绪**：检查 8848/9848/9849 是否被占用；`docker logs payment-nacos`。
- **想清数据重来**：`docker compose -f deployment/docker-compose.yml down -v`（删数据卷）。
- **分布式部署**：发行包默认服务向 Nacos 注册 `127.0.0.1`（单机最稳）；跨机部署时
  `export PAYMENT_NACOS_IP=<本机局域网IP>` 后再 `start.sh`。
