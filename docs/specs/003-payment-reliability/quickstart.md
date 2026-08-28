# Quickstart: 支付可靠性验证指南（Phase 1）

> 验证本 Feature（003-payment-reliability）端到端可工作的场景。**你的本地环境已启动数据库且工具链正常**，直接运行即可；本仓库沙箱环境 Maven 启动器损坏，无法在此代跑。

## 0. 前置条件

- JDK 21、Maven（`./mvnw` 或本地 `mvn`）。
- MySQL 运行中（默认 `localhost:3306`，`root/root`，payment-service 自有 Schema 已建）。
- 已确认 ADR-0003~0007（实现前 MUST 经负责人批准，见 plan.md Constitution Check）。

## 1. 自动化测试（权威验证，H2 无需 DB）

```sh
# 运行 payment-service 全部测试（含本 Feature 可靠性测试）
./mvnw test -pl payment-service

# 或全量
./mvnw verify
```

重点测试类（实现时补齐，见 tasks.md）：

- `TimeoutScanTest`：PROCESSING 超阈值 → UNKNOWN，订单保持 PENDING（US1/FR-001/FR-002）。
- `UnknownQueryConvergenceTest`：UNKNOWN → 调度器查询渠道 → 收敛 SUCCESS/ FAILED，且只触发一次下游（US2/FR-003/FR-004）。
- `PaymentRetryTest`：瞬时失败按上限+退避重试并最终成功；硬拒绝 0 重试直接 FAILED；耗尽→UNKNOWN（US3/FR-005~FR-007）。
- `ManualResolutionTest`：UNKNOWN 人工裁定 SUCCESS（带理由）→ 订单/交易推进一次 + FINANCIAL_AUDIT；已终态裁定被拒（US4/FR-008/FR-009，ADR-0006/0007）。
- `TerminalConflictTest`：先 FAILURE 后 SUCCESS → 保持 FAILED（ADR-0007）。
- `ReliabilityMetricsTest`：payment.timeout / payment.retry / payment.retry_exhausted / manual.resolution 计数递增（US5/FR-010）。

## 2. 本地手动 e2e（服务运行 + MySQL）

先按 002 quickstart 建立可售 SKU 与订单（默认 Mock Channel 为 SUCCESS，故正常下单会同步 PAID）。为验证可靠性，需在 Mock Channel 注入异常场景（临时将渠道配置为超时/失败/挂起），再发起支付。

```sh
# 2.1 超时→UNKNOWN（US1）
#   配置 Mock Channel 为「无响应/挂起」，发起支付后等待 > 超时阈值（默认 30s）
POST /orders            # 创建订单并触发支付（渠道挂起）
sleep 35
GET  /payments/{id}    # 预期 status=UNKNOWN，订单仍 PENDING
GET  /actuator/prometheus | grep 'payment_timeout'   # 预期计数 +1

# 2.2 主动查询收敛（US2）
#   将 Mock Channel 切为「查询返回 SUCCESS」，触发查询调度器（默认间隔 15s）
GET  /payments/{id}    # 预期 status=SUCCEEDED，订单 PAID（仅一次）
GET  /actuator/prometheus | grep 'payment_unknown'    # 收敛计数 +1

# 2.3 有限重试（US3）
#   配置 Mock Channel 瞬时失败 2 次后成功
POST /orders
# 观察支付经历 3 次 attempt（index 1/2/3），最终 SUCCEEDED；prometheus 中 payment_retry 计数反映重试次数
GET  /actuator/prometheus | grep -E 'payment_retry|payment_retry_exhausted'

# 2.4 硬拒绝不重试（US3）
#   配置 Mock Channel 返回硬拒绝（如 insufficient_funds）
POST /orders
GET  /payments/{id}    # 预期 FAILED，attempt_count=1，无重试

# 2.5 人工收敛（US4，受控端点）
#   先制造一笔 UNKNOWN（同 2.1），再以授权角色裁定
POST /internal/payments/{id}/resolve \
  -H "X-Operator-Id: ops-001" \
  -d '{"targetStatus":"SUCCESS","reason":"渠道电话确认已扣款"}'
# 预期 200，status=SUCCEEDED，订单 PAID；检查 FINANCIAL_AUDIT 日志含操作人/理由/前后状态
# 对一笔已 SUCCEEDED 的支付再发同上请求 → 预期 409/400（终态吸收，ADR-0007）

# 2.6 告警（US5）
#   人为堆积多笔 UNKNOWN 或制造重试耗尽，验证 observability（Prometheus/Grafana）产生
#   「支付状态 UNKNOWN 堆积」「重试耗尽」业务告警（非仅基础设施告警）
```

## 3. 验收对照

逐项对照 spec 的 SC-001~SC-005 与 FR-001~FR-012；所有状态迁移经状态机 + 乐观锁（无重复推进），所有资金动作有审计。

## 4. 已知缺口（实现时注意）

- 既有 `PaymentUnknownResolutionService` 以 `Duration.ZERO` 记录 UNKNOWN 时长（领域聚合无 updatedAt），本 Feature 须在持久化层补全真实计时（data-model R6）。
- 当前为单节点调度器；多节点分布式调度锁不在本 Feature（如需要另立 ADR）。
