# Plan: 023 审计中性项收尾

对应 [spec.md](spec.md)。四个批次相互独立，可按 A→D 顺序执行；A/B/C 为代码批次（同一条 feature 分支），D 为纯文档批次。

## 批次 A：可观测收尾（R1 + R2）

### A1 删除 Prometheus 死目标
- `deployment/prometheus/prometheus.yml`：删 `job_name: "refund"` 整块（:35-38）。
- `grep -r 'job="refund"\|job=refund' deployment/grafana/`：dashboard JSON 如按 job 分组引用 refund，改 `payment`（REFUND 指标在同进程暴露，指标名不变）。
- 验证：`docker compose restart prometheus` 后 `up` 无 `job="refund"` 序列。

### A2 优雅停机
- 9 个服务 `src/main/resources/application.yml` 统一追加：
  ```yaml
  server:
    shutdown: graceful
  spring:
    lifecycle:
      timeout-per-shutdown-phase: 30s
  ```
  （各 yml 已有 server:/spring: 块的并入既有结构，勿重复顶层键。）
- `deployment/stop.sh`：头部注释补一句「SIGTERM → graceful drain（≤30s）→ 退出」。
- 风险评估：graceful 只影响停机窗口，不改变运行时行为；单测无感。集成验证靠 demo 起停脚本。

## 批次 B：onPaymentSucceeded 事务收窄（R3）

改动集中在 `order-service/.../application/OrderApplicationService.java`：

1. 现状结构（已核实）：
   ```java
   @Transactional public void onPaymentSucceeded(req) {
       // 幂等检查 → order.markPaid+save（本地事务范围）
       // catalogClient.confirmStock(...)   ← Feign 在事务内（要移出）
       // fulfillmentGateway.notifyPaymentSucceeded(...) ← 已在 try-catch，语义"失败不回滚"
   }
   ```
2. 目标结构：
   - `@Transactional markOrderPaid(order, paymentNo)`：仅幂等吸收 + 状态迁移 + 落库；
   - `onPaymentSucceeded`（无事务）：先调 `markOrderPaid`，事务提交后再 `confirmStock`（逐项）→ `notifyPaymentSucceeded`；
   - `confirmStock` 包 try-catch：失败 `metrics.counter("order.stock_confirm_failed")` + WARN，不重试不回滚（对账兜底语义与现状一致，只是补观测）。
3. 事务边界注意：`markOrderPaid` 必须经代理调用（自注入或拆到独立 bean，避免同类内部调用绕过 `@Transactional`）——项目惯例看 `catalog` 乐观锁独立事务的做法（独立 bean）。
4. 单测（`OrderApplicationServiceTest` 或 scenario 测试）：
   - confirm 抛 RuntimeException → 订单仍 PAID、不抛出到调用方（对齐「通知失败不回滚」既有契约）；
   - 重复回调同一 paymentNo → 幂等吸收（现有断言保留）。

## 批次 C：文档漂移修复（R4，纯文档，可在 feature 分支或直推）

1. `order-service.md`：createOrder 流程改为两步式（步骤 6-8 重写：`POST /orders` 仅建单返回 `transactionNo`；支付意图由 `POST /orders/{orderNo}/payments` 创建；时序图同步改）。
2. `payment-service.md`：复核退款章节归属表述（ADR-0054/0067 口径：order 发起收口、payment 管渠道事实+记账、端口 8085 退役）。
3. H1/H5 与 2026-09-06 基线未修项逐条核对，结果记录进 tasks.md 勾选行。
4. ADR README 无需新条目（本 spec 无新决策；M2 拆出后另行立项 ADR-0070）。

## 回归

- `./mvnw verify`（15 模块全绿）。
- `deployment/start-all.sh` 起 compose 栈 → demo happy path 一轮 → `stop.sh` 观察优雅停机日志（"Commencing graceful shutdown" / 无异常堆栈）。
