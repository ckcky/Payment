# Plan: 027-user-payment-limit

> 技术方案版。状态：🟡 **Proposed**（spec 已定稿，代码未实施）。
> 决策 D1~D13 见 [ADR-0071](../../adr/0032-user-payment-limit.md)；需求与验收见 [spec.md](spec.md)；
> 任务清单见 [tasks.md](tasks.md)；验收记录见 [acceptance.md](acceptance.md)。

## 1. 架构总览

```text
┌──────────────────────── payment-service ────────────────────────┐
│                                                                 │
│  PaymentApplicationService.createPaymentIntent(...)              │
│         │                                                       │
│         ├─(1) LimitPendingRecycler.recycle(userId)  ──── 惰性回收 ──┐
│         │       └─ 查未结算在途 → MGET Redis → 过期则插 EXPIRED      │  ┌──────────┐
│         ├─(2) LimitReserveService.reserve(...)          ────────────┼─▶│  Redis   │
│         │       └─ UPDATE ... WHERE used+pending+? <= ?  （0 行=超限）│  │ redis:7  │
│         │          └─ 成功：写 limit_operations + SET key EX ttl ────┘  │ 仅过期索引│
│         ├─(3) paymentPersistence.insertPending(...)                    └──────────┘
│         └─(4) channel.charge(...)
│                                                                 │
│  PaymentResultProcessor.applyAndNotify(...)  ── changed=true ────┤
│         └─ LimitSettlementService：SUCCEEDED→CONFIRM / FAILED,CLOSED→RELEASE
│            （与记账同级；失败不回滚支付事实，由补偿扫描收敛）          │
│                                                                 │
│  LimitCompensationScanner（既有 @Scheduled 模式，30s）            │
│         └─ payment 已终态但只有 RESERVE → 补 CONFIRM / RELEASE     │
│                                                                 │
│  com.payment.payment.limit.{domain,application,infra,web}         │
│     权威数据：user_payment_limits / user_limit_usage / limit_operations
└─────────────────────────────────────────────────────────────────┘
         ▲ 只读查询（HTTP，ADR-0063 业务单号）
         │ GET /internal/limits/users/{userId}
    mock-channel-web（演示） / 运维
```

**三条铁律**：① 判定走 DB 原子 UPDATE，Redis 不参与；② 结算与记账同级挂 `changed=true`；③ 结算失败不回滚支付事实。

## 2. 数据模型（payment 库，列序守 spec 018 / ADR-0066）

### 2.1 `user_payment_limits` — 限额配置

```sql
CREATE TABLE IF NOT EXISTS user_payment_limits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户标识（与 payments.user_id 同口径）',
    currency_code VARCHAR(8) NOT NULL COMMENT '币种（ADR-0010 最小货币单位）',
    daily_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '日限额（分）；0=不限',
    monthly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '月限额（分）；0=不限',
    yearly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '年限额（分）；0=不限',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
    created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
    created_by VARCHAR(64), updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uplimit_user_ccy (user_id, currency_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户支付限额配置；无行=不限额（FR-012）';
```

### 2.2 `user_limit_usage` — 周期占用（双金额）

```sql
CREATE TABLE IF NOT EXISTS user_limit_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL COMMENT 'DAY | MONTH | YEAR',
    period_start DATE NOT NULL COMMENT '该行所属周期起始日；跨周期现算建新行，旧行自然闲置',
    used_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已确认（支付 SUCCEEDED）',
    pending_minor BIGINT NOT NULL DEFAULT 0 COMMENT '在途占用（已预占未终态）',
    created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ulusage_user_ccy_period (user_id, currency_code, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户周期额度占用；周期重置靠 period_start 现算（D10）';
```

### 2.3 `limit_operations` — 幂等流水（含过期索引）

```sql
CREATE TABLE IF NOT EXISTS limit_operations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_no VARCHAR(32) NOT NULL COMMENT '业务单号 LO+雪花（ADR-0062）',
    biz_no VARCHAR(32) NOT NULL COMMENT '关联支付单 paymentNo（ADR-0063）',
    op_type VARCHAR(16) NOT NULL COMMENT 'RESERVE | CONFIRM | RELEASE | EXPIRED',
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL COMMENT '操作金额（分）',
    expires_at DATETIME NULL COMMENT '仅 RESERVE：在途到期时刻（审计/兜底），日常判定走 Redis',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_limitop_biz_type (biz_no, op_type),
    KEY idx_limitop_user_type (user_id, op_type),
    KEY idx_limitop_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额度操作流水；UK 是幂等的数据库级兜底（INV-4）';
```

> **DDL 交付**：新增 `deployment/schema/` 下独立迁移文件（幂等），并**同步 H2 schema**（`src/test/resources`），否则 SC-002 过不去。

## 3. 关键流程

### 3.1 建单预占（`createPaymentIntent`，挂点见 §6）

```text
insertPending 之前：
  1. enabled=false 或该用户无配置 → 直接跳过（FR-024 / FR-012）
  2. LimitPendingRecycler.recycle(userId)   ← 惰性回收（FR-038）
  3. 对 DAY/MONTH/YEAR 三个周期依次 reserve（FR-006 原子 UPDATE）
  4. 任一周期 0 行 → 抛 LIMIT_EXCEEDED，整个建单事务回滚（INV-3）
  5. 全部成功 → 每条插 RESERVE 流水 + Redis SET key EX ttl（FR-036）
```

三个周期**各自独立判定**，任一超限即整笔拒绝，未超限的周期不得部分扣减（FR-010）。

### 3.2 终态结算

挂在 `PaymentResultProcessor.applyAndNotify` 的 `changed=true` 分支，与记账**同级**：

| payment 终态 | 操作 | 金额变动 |
|---|---|---|
| `SUCCEEDED` | `CONFIRM` | `used += a`、`pending -= a` |
| `FAILED` / `CLOSED` | `RELEASE` | `pending = GREATEST(0, pending - a)` |
| `UNKNOWN` | 不结算 | `pending` 保持占用（INV-5） |

金额一律取 `payment.getAmountMinor()`（INV-6），**不用**渠道回传的实付额。

### 3.3 惰性回收（Redis TTL，FR-038）

```text
recycle(userId):
  pending = 查未结算 RESERVE 流水（idx_limitop_user_type）
  if Redis 不可用 → 记指标，return（保守占用，INV-9.2）
  alive = MGET limit:pending:{paymentNo}...
  for 每条 alive 中缺失的：
      同一短事务：插 (paymentNo, EXPIRED) + pending = GREATEST(0, pending - a)
```

无调度器。自愈性见 spec FR-038 论证：被挂住的只有本人额度，他下次支付必先回收。

### 3.4 补偿扫描（FR-016）

复用既有 `@Scheduled` 模式，30s 一次，只处理「payment 已终态但只有 RESERVE」：

```sql
SELECT ... FROM payments p
  JOIN limit_operations r ON r.biz_no = p.payment_no AND r.op_type = 'RESERVE'
  LEFT JOIN limit_operations s
         ON s.biz_no = p.payment_no AND s.op_type IN ('CONFIRM','RELEASE','EXPIRED')
WHERE s.id IS NULL AND p.status IN ('SUCCEEDED','FAILED','CLOSED')
```

**跳过 UNKNOWN**（FR-017）。因 FR-015 唯一键，重复扫描天然幂等。

### 3.5 软超限（FR-039）

`CONFIRM` 无条件累加 `used`，即使 `used > limit`；产出 `payment_limit_overrun` 指标 + `limit.overrun` 审计 + 查询接口 `overrun` 标记。**不拒绝、不 clamp、不回滚。**

## 4. Redis 设计（D13）

| 项 | 值 |
|---|---|
| Key | `limit:pending:{paymentNo}`（前缀可配 `payment.limit.redis.key-prefix`） |
| Value | 金额（分），仅作可读性，判定不读它 |
| TTL | `payment.limit.reserve-ttl`，默认 900s |
| 写入时机 | `RESERVE` 成功后 |
| 删除时机 | `CONFIRM` / `RELEASE` / `EXPIRED` 后（尽力而为） |
| 依赖 | `spring-boot-starter-data-redis`（与 order-service 同一 parent）；实例已在 `docker-compose`（`redis:7`） |

**接口与三实现**：

```java
public interface LimitExpiryIndex {
    void mark(String paymentNo, long amountMinor, Duration ttl);  // 失败仅记指标
    void clear(String paymentNo);                                  // 失败仅记指标
    Set<String> alive(Collection<String> paymentNos);               // Redis 不可用 → 抛/返回 null → 调用方跳过回收
}
// RedisLimitExpiryIndex（生产） / NoopLimitExpiryIndex（未配置·降级） / FakeLimitExpiryIndex（测试）
```

**失效方向（INV-9）**：不可用 → 保守占用、不拦截；数据丢失 → 提前释放落入软超限并留痕；**绝不**静默修正 `used`。

## 5. 包结构与架构门禁 ⚠️

**结论：使用 `com.payment.payment.limit.{domain,application,infra,web}`，不用 `com.payment.limit`。**

`ServiceBoundaryTest` 的 `SERVICES` 白名单是 `{merchant, catalog, order, payment, ...}`（`refund` 因 Feature 015 合并历史不在其中）。交叉依赖规则生成的是「`com.payment.<service>..` 不得依赖 `com.payment.<其他 service>..`」——若包名取 `com.payment.limit`：

- `com.payment.limit..` **不在**任何 service 的 `others` 列表里 → **order 服务 import 限额类不会被拦截**；
- `everyServiceMustActuallyBeImported` 也不会覆盖它 → 门禁对该包**空转**。

spec SC-001 要求「不得被其他服务直接访问表」，用平级包名这条门禁形同虚设。放在 `com.payment.payment.limit` 下则自动被 payment 的既有规则完整覆盖，**零测试改动**。

（`com.payment.refund` 是服务合并的历史产物，限额是全新子域，无此包袱，不应照抄。）

## 6. 代码挂点清单

| 现有类 | 改动 | 说明 |
|---|---|---|
| `PaymentApplicationService.createPaymentIntent` | 插入回收 + 预占 | 在 `insertPending` **之前**；超限抛异常使整个建单事务回滚 |
| `PaymentResultProcessor.applyAndNotify` | 挂结算 | 与 `ledgerGateway.postPaymentCapture` 同级，`changed=true` 分支内 |
| `PaymentPersistence` | 无改动 | 额度操作走独立短事务，不并入既有事务 |
| `ErrorCodes`（common-core） | 新增 `LIMIT_EXCEEDED` | HTTP 409 |
| `BusinessNoType` | 新增 `LIMIT_OP("LO")` | ADR-0062 雪花业务单号 |
| `payment-service/pom.xml` | 加 `spring-boot-starter-data-redis` | D13 唯一新增依赖 |
| `application.yml` | `payment.limit.*` | 见 spec §5.5 |
| 新增 `LimitCompensationScanner` + `Scheduler` | 补偿扫描 | 复用 `TimeoutScanScheduler` 模式（含 `TraceContext.runWithNewTrace`，spec 021 / AC3.1） |
| 新增 `LimitPendingRecycler` / `LimitExpiryIndex` | 惰性回收 | 见 §4 |
| 新增内部端点 `LimitController` | 查询 / 设置 | `GET` / `PUT /internal/limits/users/{userId}` |

## 7. 测试策略

- **单测（H2，零 Redis）**：原子预占 SQL、三周期独立判定、`GREATEST(0,…)` 下限、金额口径（INV-6）、`FakeLimitExpiryIndex` 驱动的惰性回收与 `EXPIRED` 幂等。
- **集成**：并发 3 笔 ¥99 / 限额 ¥250 → 恰好 2 成 1 拒（SC-004）；重复回调 `CONFIRM` 恰好 1 条（SC-005）；补偿扫描补结算且二次不重复（SC-006）。
- **降级**：无 Redis 环境全量测试通过（SC-016）。
- **live 冒烟**：`deployment/demo/scenario-limit.sh`（含 Redis TTL 真值路径第 7 步）。

## 8. 演示改动

见 spec §5.7 + FR-041：`demo.html` 左栏限额输入与「一键演示超限」、右栏**额度水位卡**（`used` 实心 + `pending` 半透明）、`/demo/trace` 限额分组、`portal.html` chips、`scenario-limit.sh` 七步断言链。

## 9. 风险与回滚

| 风险 | 应对 |
|---|---|
| 限额误伤既有链路 | `payment.limit.enabled=false` 一键退回今天行为（FR-024）；默认不限额（FR-012） |
| Redis 不可用 | 降级 `NoopLimitExpiryIndex`，保守占用，不拦截（INV-9.2） |
| 补偿扫描与惰性回收抢同一条 | 回收只在建单路径触发且以 `EXPIRED` 唯一键互斥；补偿扫描跳过已有 `EXPIRED` |
| 跨服务 TTL 配置漂移 | `payment.limit.reserve-ttl` 与 `order.timeout.ttl-seconds` 需人工保持一致，写入 `runbook.md` |
