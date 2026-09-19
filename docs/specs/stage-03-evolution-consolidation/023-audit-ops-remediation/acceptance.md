# Acceptance: 023 审计中性项收尾

对照 [spec.md](spec.md) 的 R1-R4。全部满足才可合并。

## R1 Prometheus 死目标

- [ ] `prometheus.yml` 无 `refund` job；`docker compose up -d prometheus` 后查询 `up` 不存在 `job="refund"` 序列
- [ ] Grafana 面板无按 `job="refund"` 的恒空分组（或已改为 `payment`）

## R2 优雅停机

- [ ] 9 服务 `application.yml` 均含 `server.shutdown: graceful` 与 30s drain 超时
- [ ] 实测：`stop.sh` 停栈日志出现 graceful shutdown 完成（Spring `Graceful shutdown completed`），无「连接被拒/请求中断」噪音
- [ ] 运行时行为无变化（全量回归绿）

## R3 事务收窄

- [ ] `onPaymentSucceeded` 内不再有 Feign 调用位于 `@Transactional` 边界内（代码评审确认）
- [ ] 新增单测证明：`confirmStock` 抛异常 → 订单仍 PAID、方法不向上抛、指标 `order.stock_confirm_failed` +1
- [ ] 幂等重放（同 paymentNo 重复回调）行为不变
- [ ] order-service 模块测试全绿

## R4 文档漂移

- [ ] `order-service.md` createOrder 与两步式下单契约一致（含时序图）
- [ ] `payment-service.md` 退款章节与 ADR-0054/0067 口径一致
- [ ] H1/H5/基线 M 级项核对结论已记录在 tasks.md T12

## 总体

- [ ] `./mvnw verify` 15 模块全绿
- [ ] demo happy path 实跑通过
- [ ] 无安全类改动（负责人裁决：桩实现保持现状，本 spec 不涉及）
