# Tasks: 023 审计中性项收尾

> 批次 A/C 可并行；B 独立；D 收尾。代码批次在 `feature/023-audit-ops-remediation` 分支，完成 `--no-ff` 合并 master。

## 批次 A：可观测收尾

- [x] T1 删除 `deployment/prometheus/prometheus.yml` 的 `refund` job（:8085 死目标）
- [x] T2 核对并修正 Grafana dashboard JSON 的 `job="refund"` 引用（如有）——结论：无引用，无需改
- [x] T3 9 服务 `application.yml` 加 `server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s`
- [x] T4 `deployment/stop-all.sh` 注释补停机语义
- [ ] T5 验证：compose 重启后 Prometheus 无 `job="refund"`；`stop-all.sh` 日志出现 graceful shutdown 完成且无被掐断的在途请求报错

## 批次 B：onPaymentSucceeded 事务收窄

- [x] T6 拆 DB 段（`markPaidAndTransaction`，TransactionTemplate 编程式事务，模式同 catalog StockApplicationService；幂等重放返回 null 整体吸收）
- [x] T7 `confirmStock` 移至事务外 + 失败路径补 `order.stock_confirm_failed` 指标与 WARN
- [x] T8 单测：confirm 失败 → 订单仍 PAID、异常不上抛；幂等重放回归
- [x] T9 order-service 全量测试绿（62 用例）

## 批次 C：文档漂移

- [x] T10 `order-service.md` createOrder 两步式改写（含时序图；4.1/4.2 全部按 ADR-0054 已实施 + spec 023 事务收窄后实况重写）
- [x] T11 `payment-service.md` 退款归属章节复核（ADR-0054/0067 口径）——修复「归属 refund-service」「供 refund-service」「Proposed 未实施」迁移标注 ×2、autoRefundGateway 残留、3.8 出站清单、4.3 退款异步语义
- [x] T12 H1/H5 及基线项核对结论：**H1 已修复**（全仓 docs 无「Nacos 暂不启用」类过时表述，随 ADR-0059/019 批次清理）；**H3 已修复**（T10）；**H4 已修复**（README/导航无 8085 残留，019 批次已清）；**H5 本次修复**（docs/README.md 目录导航补 operations/runbook）

## 收尾

- [x] T13 `./mvnw verify` 15 模块全绿（2026-09-08 00:08 实测）
- [ ] T14 demo happy path 实跑一轮 + 优雅停机演示（live 栈实测项：需 start-all.sh 全栈就绪后执行）
- [x] T15 CHANGELOG + feature 分支 `--no-ff` 合并 master + 推送

## 顺手项（不阻塞验收，做到即勾）

- [ ] T16 `start-all.sh` Nacos 等待超时 break 改 exit（假成功修复）
- [ ] T17 根目录 580MB 旧 tar.gz 及 `deployment/output/jars` 旧版本产物清理（VERSION=1.0.0 对齐）
- [ ] T18 死代码 `InMemoryIdempotencyRegistry`（注册但零使用）移除或注释说明用途
