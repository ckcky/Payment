# Payment Platform（可执行发行包）

电商支付平台演示版 —— 预构建二进制，**无需源码、无需 Maven**，解压即跑。

## 环境要求

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | 21 LTS | 运行 10 个服务进程 |
| Docker | 含 Compose v2 | MySQL / Redis / Nacos / Prometheus / Grafana / Loki |
| 内存 | 建议 ≥ 4GB 空闲 | 10 个 JVM（默认每个 -Xmx512m）+ 容器 |
| 磁盘 | ≥ 3GB | Docker 镜像首次拉取 |

## 快速开始

```bash
tar -xzf payment-platform-<版本>-bin.tar.gz
cd payment-platform-<版本>
bash start.sh          # 一键启动（首次拉镜像较慢）
bash reset-demo.sh     # 首次使用灌演示种子（商户/商品/SKU）
```

打开 **http://localhost:8091/demo** 即演示控制台：下单 → 选渠道建支付单 → 收银台
→ 渠道回调 → 履约/权益 → 记账 → 退款 → 全链路落库透视。

## 目录结构

```
start.sh / stop.sh        一键启停（java -jar 直跑，无构建）
reset-demo.sh             复位演示数据 + 灌种子
jars/                     10 个 Spring Boot fat jar
deployment/
  docker-compose.yml      基础设施容器编排
  schema/                 各服务建表 SQL（start.sh 幂等重放）
  demo/                   演示场景脚本（run-all.sh 一键全跑）
  performance/            k6 压测脚本
  logs/                   运行日志（.pids 记录进程）
```

## 服务与端口

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| merchant 商户 | 8081 | reconciliation 对账 | 8088 |
| catalog 商品 | 8082 | settlement 结算 | 8089 |
| order 订单 | 8083 | ledger 账务 | 8090 |
| payment 支付（含退款域） | 8084 | mock-channel-web 演示组件 | 8091 |
| fulfillment 履约 | 8086 | entitlement 权益 | 8087 |

## 常用操作

```bash
bash demo/run-all.sh      # 一键跑全部演示场景（主链/退款/对账/审计）
bash reset-demo.sh        # 随时复位重来
tail -f deployment/logs/payment-service.log   # 看日志
bash stop.sh              # 全部停止（保留数据卷，下次 start.sh 数据还在）
```

## 可选环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PAYMENT_NACOS_IP` | `127.0.0.1` | 覆盖服务向 Nacos 注册的 IP（分布式部署时改） |
| `JAVA_TOOL_OPTIONS` | `-Xmx512m ...` | JVM 内存上限 |
| `PAYMENT_ADMIN_TOKEN` | `demo-admin-token` | 支付管理端点令牌 |
| `PAYMENT_CHANNEL_SECRET` | `demo-channel-secret-2026` | 渠道回调签名演示密钥 |

## 说明

- 这是**学习/演示用途**的单机发行包：服务为宿主机进程，基础设施容器化；
  资金变动一律经 ledger 复式记账，退款/对账/结算/审计闭环可完整演示。
- 架构与设计文档见仓库源码 `docs/`（ADR、spec、系统设计）。
