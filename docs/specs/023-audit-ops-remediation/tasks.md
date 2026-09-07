# Tasks: 023 审计中性项收尾

> 批次 A/C 可并行；B 独立；D 收尾。代码批次在 `feature/023-audit-ops-remediation` 分支，完成 `--no-ff` 合并 master。

## 批次 A：可观测收尾

- [ ] T1 删除 `deployment/prometheus/prometheus.yml` 的 `refund` job（:8085 死目标）
- [ ] T2 核对并修正 Grafana dashboard JSON 的 `job="refund"` 引用（如有）
- [ ] T3 9 服务 `application.yml` 加 `server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s`
- [ ] T4 `deployment/stop.sh` 注释补停机语义
- [ ] T5 验证：compose 重启后 Prometheus 无 `job="refund"`；`stop.sh` 日志出现 graceful shutdown 完成且无被掐断的在途请求报错

## 批次 B：onPaymentSucceeded 事务收窄

- [ ] T6 拆 `markOrderPaid`（独立事务边界，注意 Spring 代理自调用坑，参照 catalog 乐观锁独立 bean 惯例）
- [ ] T7 `confirmStock` 移至事务外 + 失败路径补 `order.stock_confirm_failed` 指标与 WARN
- [ ] T8 单测：confirm 失败 → 订单仍 PAID、异常不上抛；幂等重放回归
- [ ] T9 order-service 全量测试绿

## 批次 C：文档漂移

- [ ] T10 `order-service.md` createOrder 两步式改写（含时序图）
- [ ] T11 `payment-service.md` 退款归属章节复核（ADR-0054/0067 口径）
- [ ] T12 H1/H5 及 2026-09-06 基线 M 级未修项逐条核对并修复，结论记录于此

## 收尾

- [ ] T13 `./mvnw verify` 15 模块全绿
- [ ] T14 demo happy path 实跑一轮 + 优雅停机演示
- [ ] T15 CHANGELOG + feature 分支 `--no-ff` 合并 master + 推送

## 顺手项（不阻塞验收，做到即勾）

- [ ] T16 `start-all.sh` Nacos 等待超时 break 改 exit（假成功修复）
- [ ] T17 根目录 580MB 旧 tar.gz 及 `deployment/output/jars` 旧版本产物清理（VERSION=1.0.0 对齐）
- [ ] T18 死代码 `InMemoryIdempotencyRegistry`（注册但零使用）移除或注释说明用途
