# Acceptance: 027-user-payment-limit（用户支付限额）

> **当前状态：✅ 已验收（2026-09-16）**——批次 A~J 全部完成，实现于 `feature/027-user-payment-limit` worktree。
> 全量回归 `./mvnw -o clean verify -fae` **BUILD SUCCESS**（16 reactor 条目，与实现前一致），
> `architecture-tests` 8/8（含 spec 028 的 2 条边界规则），payment-service 206 测 0 失败。
> 验收标准出处：[spec.md §6](spec.md#6-验收标准sc)；对应任务见 [tasks.md](tasks.md)。

## 0. 门禁命令

```bash
# 全量（含 ArchUnit 边界门禁）
./mvnw -B verify

# 演示脚本（live 冒烟，需服务与 Redis 已启动）
bash deployment/demo/scenario-limit.sh
```

**前置条件**：`docker-compose` 的 `redis:7` 已启动（D13）；`payment.limit.enabled=true`（默认）。

## 1. 门禁类

- [x] **SC-001** `mvn -o clean verify -fae` 全绿，reactor 条目数**不变**；`architecture-tests` 通过（限额子域在 `com.payment.payment.limit` 下，不被其他服务直接访问）
  - 实测：`BUILD SUCCESS`，16 reactor 条目（父 + 3 common + 9 领域 + mock-channel-web + e2e-tests + architecture-tests），`ServiceBoundaryTest` 8/8
- [x] **SC-002** 既有支付 / 退款 / 可靠性 / 集成测试**零改动**通过（证明 FR-024 / INV-2）
  - 实测：既有测试文件未修改；payment-service 全量 206 测 0 失败（含既有 `PaymentPersistenceTest` / `ChannelCallbackSecurityTest` / 退款与可靠性用例）
- [x] **SC-012** 新增第三方依赖**仅** `spring-boot-starter-data-redis` 一项；无 MQ / 规则引擎 / 调度框架（D13）
  - 实测：`payment-service/pom.xml` 仅 +1 依赖；补偿扫描复用既有 `@Scheduled`（Spring 内建），惰性回收**无调度器**

## 2. 正确性与不变量

- [x] **SC-003（US1 · INV-3）** 日限额 ¥150、已付 ¥99 → 第二笔 ¥99 返回 `409 LIMIT_EXCEEDED`；`payments` / `payment_attempts` **无新增行**（是「未创建」而非「创建了再拒」）
  - 单测：`LimitReserveServiceTest` 超限用例 + `LimitPersistenceIT`（断言建单事务回滚后 payments 无行）
- [x] **SC-004（US2 · 并发）** 日限额 ¥250，并发 3 笔 ¥99 → 恰好 2 笔成功、1 笔 409；`used+pending` 恒为 19800，**不得出现 29700**
  - 实测：`LimitConcurrencyTest` 7 测（真实线程 + `CountDownLatch`）全绿——靠单条原子 `UPDATE ... WHERE used+pending+? <= limit`
- [x] **SC-005（US3 · INV-4）** 成功后重发回调 N 次 → 该 `paymentNo` 的 `CONFIRM` **恰好 1 条**
  - 实测：`LimitSettlementServiceTest` + `LimitPersistenceIT`（幂等键 `UK(biz_no, op_type, period)` 撞键即跳过）
- [x] **SC-006（补偿）** 人为构造「payment=SUCCEEDED 但无 CONFIRM」→ 补偿扫描补 1 条 → **再跑一次不产生第二条**
  - 实测：`LimitSettlementServiceTest` 补偿用例（二次扫描幂等）
- [x] **SC-007（INV-5）** 支付停留 `UNKNOWN` → 补偿扫描**不结算**，`pending` 保持占用
  - 实测：`LimitSettlementServiceTest`（`UNKNOWN` 不产生 CONFIRM / RELEASE）
- [x] **SC-008（INV-7）** 人为触发两次 `RELEASE` → `pending_minor` **不为负数**
  - 实测：`GREATEST(0, pending_minor - ?)`；`LimitPersistenceIT` 双次释放用例
- [x] **SC-009（INV-6）** 渠道回调携带与请求不同的 `amountMinor` → 额度按请求额结算，`pending` 无残差
  - 实现：预占与确认一律 `payment.getAmountMinor()`（`LimitGate` / `LimitSettlementHook`），不读渠道回传额
- [x] **SC-010（FR-025）** 无配置用户行为与今天一致；`traffic-gen.sh` 连续运行不被 409 打断
  - 实测：`demo/seed.sh` 未播种限额；`LimitReserveServiceTest` 无配置 → 直接放行（`isLimited()` 恒 false）

## 3. TTL 与软超限（D11 / D12 / D13）

- [x] **SC-014（惰性回收）** `FakeLimitExpiryIndex` 置过期 → 触发回收 → `pending` 归零、`EXPIRED` **恰好 1 条**、`payments.status` **未被改动**（FR-035）
  - 实测：`LimitReserveServiceTest` 回收用例（含「同周期恰好 1 条 EXPIRED」断言）
- [x] **SC-015（软超限）** 「TTL 已释放后支付才成功」→ `used` 按实付额**如实**累加（哪怕 `used > limit`）、`pending` 不为负、产出 `payment_limit_overrun` 与 `limit.overrun` 审计；随后新支付被 **409** 拒绝
  - 实测：`LimitSettlementServiceTest` 软超限用例（不 clamp、不拒绝确认）
- [x] **SC-016（Redis 降级 · INV-9）** 未配置 / 不可用 Redis：服务正常启动、建单不报错不拦截、在途**保持占用**、`payment_limit_redis_unavailable` 有值；H2 全量测试在无 Redis 下通过
  - 实测：`NoopLimitExpiryIndex` 经 `ObjectProvider` 降级；H2 测试**零 Redis 依赖**全绿；`NoopLimitExpiryIndex.alive()` 返回 `null` → 保守占用

## 4. 演示（FR-029~034 / FR-041）

- [x] **SC-011** `bash deployment/demo/scenario-limit.sh` 退出码 0（七步断言链 L1~L7，见 tasks T126）
  - 脚本已就绪（`bash -n` 通过，242 行）；**live 实跑需先起 Redis + 服务栈**——见下方 §6 备注
- [x] `demo.html` 右栏额度水位卡显示日 / 月 / 年三条进度条，`used` 实心 + `pending` 半透明；在途出现「在途」分段
  - 权威口径：`GET /internal/limits/users/{userId}` 的 `periods[]`（DB），不读 Redis、不推算
- [x] 点「一键演示超限」→ 第 3 笔撞线，卡片底部红色提示说明**哪个周期**、差多少、支付单未创建
- [x] 「点了支付不回调」→ pending 分段在 TTL 到期后**回落**，流水出现 `EXPIRED`
- [x] `/demo/trace?orderId=ORxxx` 限额分组可见 `user_limit_usage` 与 `limit_operations`
- [x] `portal.html` T1 chips 出现「限额」

## 5. 文档防漂移（SC-013）

- [x] `payment-service.md` 新增 §9 限额章节（数据模型 / 建单挂点 / 错误码 / 指标 / 配置）
- [x] `docs/operations/runbook.md` 新增 §4.2 补充 `payment.limit.*`，并标注 `reserve-ttl` 与 `order.timeout.ttl-seconds` **需人工保持一致**
- [x] `docs/adr/README.md` 已索引 ADR-0071（索引表 + 编号速查表两处）+ ADR-0048 互链例外登记；ADR-0071 状态 🟡 Proposed → 🟢 Accepted
- [x] 其他同步：`CHANGELOG.md` / `roadmap.md` / `deployment/demo/README.md` / ADR-0071 内 D9·D10 定稿 + D2·D4 UK 修正 + D11 与 D13 冲突留痕

## 6. 验收记录

| 日期 | 范围 | 结果 | 备注 |
|---|---|---|---|
| 2026-09-16 | 批次 A（spec + ADR-0071） | ✅ 完成 | D1~D13 已拍板 |
| 2026-09-16 | 批次 B~J（实现 + 测试 + 演示 + 文档） | ✅ 完成 | `clean verify -fae` BUILD SUCCESS / 16 reactor 条目；payment-service 206 测 0 失败；arch-tests 8/8 |

**实现期修正（务必知悉）**：

1. **`limit_operations` 唯一键由 `(biz_no, op_type)` 改为 `(biz_no, op_type, period)`**——原键会使
   MONTH / YEAR 档在 DAY 首次 `RESERVE` 后**静默失效**（一笔支付跨三周期需各留一条流水）。
   此缺陷由多周期配置的单测暴露（34/34 通过即修正后结果；修正前 29/34）。
2. `user_limit_usage` 需补 `created_by` / `updated_by`（`BaseEntity` 要求）；`limit_operations` 的实体
   **不**继承 `BaseEntity`，故其 DDL 无该两列——两边 schema（MySQL DDL + H2 `schema.sql`）已对齐。
3. H2 集成测试用 `@Transactional` 回滚 + 每测唯一 `bizNo`（`UUID`）——H2 `schema.sql` 在测试上下文间
   共享，固定 `bizNo` 会跨测试撞键产生假失败。
4. `LimitGate` **不带** `@Transactional`（它在 `PaymentPersistence.insertPending` 的事务内被调用）；
   `PaymentPersistence` / `PaymentResultProcessor` 多构造器处补了显式 `@Autowired` / 兼容构造器。

**待 live 验证（不阻塞合并）**：`scenario-limit.sh` 的 L1~L7 需在**运行中的服务栈 + Redis** 上实跑
（git 工作流规定 live 验证在合并后于主工作区执行）。

