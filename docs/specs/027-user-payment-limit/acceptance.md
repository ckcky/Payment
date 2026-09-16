# Acceptance: 027-user-payment-limit（用户支付限额）

> **当前状态：⏸ 未验收**——批次 A（文档与决策）已完成，代码未实施（T104~T130 未开始）。
> 本文件在 T130 收尾时回填实际结果。验收标准出处：[spec.md §6](spec.md#6-验收标准sc)；
> 对应任务见 [tasks.md](tasks.md)。

## 0. 门禁命令

```bash
# 全量（含 ArchUnit 边界门禁）
./mvnw -B verify

# 演示脚本（live 冒烟，需服务与 Redis 已启动）
bash deployment/demo/scenario-limit.sh
```

**前置条件**：`docker-compose` 的 `redis:7` 已启动（D13）；`payment.limit.enabled=true`（默认）。

## 1. 门禁类

- [ ] **SC-001** `mvn -o clean verify -fae` 全绿，reactor 条目数**不变**；`architecture-tests` 通过（限额子域在 `com.payment.payment.limit` 下，不被其他服务直接访问）
- [ ] **SC-002** 既有支付 / 退款 / 可靠性 / 集成测试**零改动**通过（证明 FR-024 / INV-2）
- [ ] **SC-012** 新增第三方依赖**仅** `spring-boot-starter-data-redis` 一项；无 MQ / 规则引擎 / 调度框架（D13）

## 2. 正确性与不变量

- [ ] **SC-003（US1 · INV-3）** 日限额 ¥150、已付 ¥99 → 第二笔 ¥99 返回 `409 LIMIT_EXCEEDED`；`payments` / `payment_attempts` **无新增行**（是「未创建」而非「创建了再拒」）
- [ ] **SC-004（US2 · 并发）** 日限额 ¥250，并发 3 笔 ¥99 → 恰好 2 笔成功、1 笔 409；`used+pending` 恒为 19800，**不得出现 29700**
- [ ] **SC-005（US3 · INV-4）** 成功后重发回调 N 次 → 该 `paymentNo` 的 `CONFIRM` **恰好 1 条**
- [ ] **SC-006（补偿）** 人为构造「payment=SUCCEEDED 但无 CONFIRM」→ 补偿扫描补 1 条 → **再跑一次不产生第二条**
- [ ] **SC-007（INV-5）** 支付停留 `UNKNOWN` → 补偿扫描**不结算**，`pending` 保持占用
- [ ] **SC-008（INV-7）** 人为触发两次 `RELEASE` → `pending_minor` **不为负数**
- [ ] **SC-009（INV-6）** 渠道回调携带与请求不同的 `amountMinor` → 额度按请求额结算，`pending` 无残差
- [ ] **SC-010（FR-025）** 无配置用户行为与今天一致；`traffic-gen.sh` 连续运行不被 409 打断

## 3. TTL 与软超限（D11 / D12 / D13）

- [ ] **SC-014（惰性回收）** `FakeLimitExpiryIndex` 置过期 → 触发回收 → `pending` 归零、`EXPIRED` **恰好 1 条**、`payments.status` **未被改动**（FR-035）
- [ ] **SC-015（软超限）** 「TTL 已释放后支付才成功」→ `used` 按实付额**如实**累加（哪怕 `used > limit`）、`pending` 不为负、产出 `payment_limit_overrun` 与 `limit.overrun` 审计；随后新支付被 **409** 拒绝
- [ ] **SC-016（Redis 降级 · INV-9）** 未配置 / 不可用 Redis：服务正常启动、建单不报错不拦截、在途**保持占用**、`payment_limit_redis_unavailable` 有值；H2 全量测试在无 Redis 下通过

## 4. 演示（FR-029~034 / FR-041）

- [ ] **SC-011** `bash deployment/demo/scenario-limit.sh` **退出码 0**（七步断言链，见 tasks T126）
- [ ] `demo.html` 右栏额度水位卡显示日 / 月 / 年三条进度条，`used` 实心 + `pending` 半透明；在途出现「在途」分段
- [ ] 点「一键演示超限」→ 第 3 笔撞线，卡片底部红色提示说明**哪个周期**、差多少、支付单未创建
- [ ] 「点了支付不回调」→ pending 分段在 TTL 到期后**回落**，流水出现 `EXPIRED`
- [ ] `/demo/trace?orderId=ORxxx` 限额分组可见 `user_limit_usage` 与 `limit_operations`
- [ ] `portal.html` T1 chips 出现「限额」

## 5. 文档防漂移（SC-013）

- [ ] `payment-service.md` 新增限额章节（数据模型 / 建单挂点 / 错误码 / 指标）
- [ ] `docs/operations/runbook.md` 补充 `payment.limit.*`，并标注 `reserve-ttl` 与 `order.timeout.ttl-seconds` **需人工保持一致**
- [ ] `docs/adr/README.md` 已索引 ADR-0071 且下一可用编号为 **ADR-0072**（tasks T103，人工补）

## 6. 验收记录

| 日期 | 范围 | 结果 | 备注 |
|---|---|---|---|
| 2026-09-16 | 批次 A（spec + ADR-0071） | ✅ 完成 | D1~D13 已拍板；代码未实施 |
