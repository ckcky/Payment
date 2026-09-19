# Tasks: 027-user-payment-limit（用户支付限额：日 / 月 / 年周期额度）

> 承载目标：payment 内限额子域 + 两阶段预占（RESERVE/CONFIRM/RELEASE）+ 四道幂等闸门 +
> 在途占用 TTL（Redis 惰性回收）+ 软超限口径 + 演示可视化。
> **当前状态：✅ 全部完成（2026-09-16，批次 A~J）。** 实现于 `feature/027-user-payment-limit`
> worktree；ADR-0071 已转 🟢 Accepted → Implemented。实际验收结果见 [acceptance.md](acceptance.md)。
> 每个任务完成后跑对应模块测试门禁，最后统一 `mvn -o clean verify -fae`。
> 技术方案见 [plan.md](plan.md)（含 DDL、挂点清单、包结构论证），需求见 [spec.md](spec.md)。

## 批次 A — 文档与决策（已完成）

- [x] **T101** 编写 spec 027：`docs/specs/stage-04-new-directions/027-user-payment-limit/spec.md`（现状 C1~C14 / 不变量 INV-1~9 / US1~US4 / FR-001~043 / SC-001~016 / 限制 L1~L11 / 决策 D9~D13）
- [x] **T102** 立项 [ADR-0071](../../adr/0071-user-payment-limit.md)：D1~D13（含 **D13 允许 payment 使用 Redis 作为过期索引**，显式反转 ADR-0044 的「payment 不用 Redis」）
- [x] **T103** `docs/adr/README.md` 索引新增 ADR-0071 行（**索引表 + 编号速查表两处**）+ 与 ADR-0048 互链
  - 实现期完成（026 / spec 028 均已合入 master，本 worktree 基于含其改动的基线，无覆盖风险）；下一可用编号已是 ADR-0074（0072/0073 为 spec 028）

## 批次 B — 数据层

- [x] **T104** DDL：payment 库新增 `user_payment_limits` / `user_limit_usage` / `limit_operations`（列序守 spec 018 / ADR-0066，DDL 见 [plan.md](plan.md) §2）
- [x] **T105** 迁移脚本 `deployment/schema/` 下新增独立幂等文件 + **H2 schema 同步**（`src/test/resources`），否则 SC-002 过不去
- [x] **T106** 实体与仓储映射：`UserPaymentLimit` / `UserLimitUsage` / `LimitOperation` + 对应 Repository（JDBC 或 JPA 对齐 payment 既有风格）；`BusinessNoType` 新增 `LIMIT_OP("LO")`（ADR-0062）

## 批次 C — 限额子域（`com.payment.payment.limit`，包名论证见 [plan.md](plan.md) §5）

- [x] **T107** 领域层 `limit/domain`：周期枚举 `LimitPeriod`（DAY/MONTH/YEAR）+ `periodStart` 现算（D10，零定时任务）；`LimitUsage`（双金额 + `GREATEST(0,…)` 下限，INV-7）；超限判定领域异常
- [x] **T108** 应用层 `limit/application`：`LimitReserveService`（FR-006 原子 UPDATE，0 行=超限）、`LimitSettlementService`（CONFIRM/RELEASE/EXPIRED）、`LimitQueryService`
- [x] **T109** **幂等核心**：所有操作先插 `limit_operations`，撞 `uk_limitop_biz_type` 即跳过金额变更（FR-015，INV-4）
- [x] **T110** 金额口径：预占与确认一律 `payment.getAmountMinor()`（INV-6）；**禁止**用渠道回传实付额

## 批次 D — 挂点接入

- [x] **T111** 建单预占：在 `PaymentApplicationService.createPaymentIntent` 的 `insertPending` **之前**插入回收 + 预占 → 超限抛 `LIMIT_EXCEEDED` 使整个建单事务回滚（INV-3，payment 表不落行）
  - ⚠️ 三个周期各自独立判定，任一超限即整笔拒绝，未超限周期**不得部分扣减**（FR-010）
- [x] **T112** 终态结算：挂 `PaymentResultProcessor.applyAndNotify` 的 `changed=true` 分支，**与记账同级**；`SUCCEEDED`→CONFIRM、`FAILED`/`CLOSED`→RELEASE、`UNKNOWN`→不结算（INV-5）
- [x] **T113** 补偿扫描：`LimitCompensationScanner` + Scheduler（复用 `TimeoutScanScheduler` 模式，含 `TraceContext.runWithNewTrace`，spec 021 / AC3.1），30s 间隔，**跳过 UNKNOWN**（FR-016 / FR-017）

## 批次 E — Redis 与惰性回收（D13）

- [x] **T114** 依赖与配置：`payment-service/pom.xml` 加 `spring-boot-starter-data-redis`（**本 Spec 唯一新增依赖**，SC-012）；`application.yml` 加 `payment.limit.*`（`enabled` / `reserve-ttl` / `redis.key-prefix`）
- [x] **T115** `LimitExpiryIndex` 接口 + 三实现：`RedisLimitExpiryIndex`（生产）/ `NoopLimitExpiryIndex`（未配置·降级，INV-9.2）/ `FakeLimitExpiryIndex`（测试）；Key 规范 `limit:pending:{paymentNo}`，TTL = `reserve-ttl`
  - ⚠️ **Redis 绝不参与 `used`/`pending` 的权威计算**（INV-9.1）
- [x] **T116** `LimitPendingRecycler`：在**每次 RESERVE 判定前**执行——查未结算在途（`idx_limitop_user_type`）→ `MGET` 判存 → 缺失者插 `EXPIRED` + `pending = GREATEST(0, pending - ?)`；Redis 不可用则跳过回收并记 `payment_limit_redis_unavailable`（FR-038，INV-9.2）
  - **无调度器、无全表扫描**（D13 明确否决定时扫描方案）

## 批次 F — 接口与错误码

- [x] **T117** `ErrorCodes` 新增 `LIMIT_EXCEEDED`（common-core），HTTP 映射 **409**；错误体说明**哪个周期**超限与当前额度（FR-019，对齐 ADR-0049「给出合法取值清单」）
- [x] **T118** 内部端点 `LimitController`：`GET /internal/limits/users/{userId}`（三周期 limit/used/pending/available + `overrun` 标记 + 最近流水）、`PUT` 设置/更新（幂等 upsert，FR-020）

## 批次 G — 可观测

- [x] **T119** 指标 `payment_limit_total{op,result,period}`、`payment_limit_exceeded`、`payment_limit_compensated`、`payment_limit_overrun`、`payment_limit_redis_unavailable` / `_error`
- [x] **T120** 审计：超限落 `FINANCIAL_AUDIT`（`action=limit.exceeded`）；软超限落 `limit.overrun`（含 userId/period/limit/used/overrun）（FR-027 / FR-040）

## 批次 H — 测试（H2，零 Redis 依赖）

- [x] **T121** 单测：原子预占 SQL、三周期独立判定、`GREATEST(0,…)` 下限（SC-008）、金额口径（SC-009）、`FakeLimitExpiryIndex` 驱动的惰性回收与 `EXPIRED` 幂等（SC-014）
- [x] **T122** 集成：并发 3×¥99 / 限 ¥250 → 恰好 2 成 1 拒（SC-004）；重复回调 `CONFIRM` 恰好 1 条（SC-005）；补偿扫描补结算且二次不重复（SC-006）；`UNKNOWN` 不结算（SC-007）；超限不落 `payments` 行（SC-003）
- [x] **T123** 兼容与降级：`enabled=false` 与无配置用户行为逐字节一致（SC-002 / SC-010）；**无 Redis 环境全量通过**（SC-016）；软超限断言 `used` 如实累加且下一笔被拒（SC-015）

## 批次 I — 演示组件（FR-029~034 / FR-041）

- [x] **T124** `demo.html`：左栏下单步骤加限额输入组 + 「设置限额」+「一键演示超限」；右栏新增**额度水位卡**（`used` 实心 + `pending` 半透明 + 最近流水 + 超限红条）
- [x] **T125** `/demo/trace` 新增限额分组（`user_limit_usage` 按 userId、`limit_operations` 按 paymentNo，只读 SELECT）；`portal.html` T1 chips 加「限额」
- [x] **T126** 新增 `deployment/demo/scenario-limit.sh`（照抄 `scenario-happy-path.sh` 的 `http`/`assert_status`/`jget`/`assert_eq` 模式）七步断言链：
  1. 设日限额 ¥150 → 2. 首笔 ¥99 成功（used=9900）→ 3. 第二笔 ¥99 断言 **409** 且 `payments` 无第二行（INV-3 关键断言）→ 4. 重发回调 2 次断言 `CONFIRM` 恰 1 条 → 5. FAILED 支付断言 pending 归零 → 6. `deferChannel` 建单不回调，等 TTL（演示期 `reserve-ttl` 调至 5s）断言 pending 归零且出现 `EXPIRED` → 7. 清理限额配置（守 FR-025）
- [x] **T127** 若采纳 D9 方案 ②：`mock-channel-web` 加同源代理 `/proxy/internal/limits/**`，并在 ADR-0071 登记为 ADR-0048 显式例外（**范围仅限该路径**）

## 批次 J — 收尾

- [x] **T128** 全量回归：`mvn -o clean verify -fae`，reactor 条目数**不变**，`architecture-tests` 通过（SC-001）
- [x] **T129** 文档防漂移（SC-013）：`payment-service.md` 新增限额章节（数据模型 / 建单挂点 / 错误码 / 指标）；`docs/operations/runbook.md` 补充 `payment.limit.*`（含 `reserve-ttl` 与 `order.timeout.ttl-seconds` 需人工一致的告警）
- [x] **T130** spec/ADR 状态推进 + CHANGELOG；`acceptance.md` 记录验收结果

## 明确不做（负责人 2026-09-16 拍板）

- ❌ 风控评分 / 反欺诈（ADR-0028 已否决，不复用 `payment.risk.*`）
- ❌ 退款回补额度（D7）；单笔 / 商户 / 渠道维度限额；多币种折算
- ❌ `PENDING` 显式「放弃支付」关单出口（改状态机，TTL 已覆盖）
- ❌ **在途占用定时扫描器 / 60s 轮询**（D13 否决，改用 Redis TTL + 惰性回收）
- ❌ Redis 做额度计数（INV-9.1，C7 实质约束未放宽）
- ❌ 额度冻结解冻 / 白名单 / 人工提额审批流 / 变更审计台 / 流水归档
