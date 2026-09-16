# Spec: 027-user-payment-limit（用户支付限额：日 / 月 / 年周期额度）

**版本**：0.1
**日期**：2026-09-16
**状态**：Draft（D1~D8 已由负责人确认；**§9 的 D9~D10 待确认**，确认后方可进入 `/speckit-plan`）
**分支**：`docs/spec-027-user-payment-limit`（纯文档，经 `./spec-worktree.sh`）→ 实现期另开 `feature/027-user-payment-limit`
**决策**：ADR-0071（[0032-user-payment-limit.md](../../adr/0032-user-payment-limit.md)，🟡 Proposed）

> ⚠️ **与 ADR-0028 的切割（必读）**：ADR-0028「最小风控」已于 2026-08-30 裁决 ⛔ **Not Implemented**，代码已删除。
> 那是「阈值评分 + 命中**只记录不阻断**」；本 Spec 是「确定性额度比较 + 命中**硬拒绝**」。
> **本 Spec 不是 ADR-0028 的翻案**，是新增一项独立的业务合规能力。实现期 MUST NOT 复用 `payment.risk.*` 配置命名空间，避免与已否决决策混淆。

## 1. 背景与目标

### 1.1 背景

平台目前**没有任何额度约束**：只要支付单能建出来、渠道返回成功，用户单日可以无限额支付。`payments` 表已具备 `user_id` / `amount_minor` / `currency_code`，是唯一的资金入口事实源，但从未被聚合为「用户已付多少」。

真实支付平台中，用户维度的日/月/年限额是**监管与风控的基线要求**（反洗钱、未成年人保护、账户盗用损失封顶）。对本项目而言，它的价值还在于逼出两个此前不可见的中间态：**在途占用**与**额度结算的事务边界**。

### 1.2 目标

1. **让用户维度限额成为平台能力**：日 / 月 / 年三个周期，硬拒绝超限支付；
2. **守住资金入口纪律**：预占判定为单条原子 SQL，不引入分布式锁、不引入新中间件；
3. **把幂等做成数据库级**：重复回调、事务外重试、补偿重跑三种入口一次堵死；
4. **让中间态可观测**：`pending`（在途）与 `used`（已确认）分离，并在 demo 中直接可见；
5. **默认不打扰**：无配置行 = 不限额，既有 E2E / 流量脚本零影响。

### 1.3 为什么现在做

- 它是「资金正确性」一侧唯一还缺的准入闸门：目前平台能防重复扣款、防超退，但**防不住合法请求堆出来的超额**；
- 改动面可控（payment-service 内新增子域 + 3 张表），不新增服务、不改跨服务契约方向；
- demo 价值直观：限额是少数几个「肉眼能看见正确性」的特性（进度条 + 拒绝提示）。

## 2. 现状核实（基于真实代码，2026-09-16 核对）

| # | 约束 | 证据 | 影响 |
|---|---|---|---|
| C1 | `payments` 含 `user_id` / `amount_minor` / `currency_code` | `deployment/schema/03-payment-schema.sql` | 限额所需维度已齐备，无需改既有表 |
| C2 | 全仓无 limit / quota 概念；`RiskCheckService` 与 `payment.risk.*` 已删 | ADR-0028；`docs/specs/009-risk-security/spec.md` §2 | 全新子域，无历史包袱；但**必须显式切割**以免被误认为翻案 |
| C3 | `createPaymentIntent` **无 `@Transactional`**；只有 `insertPending` / `applyAndPersist` 是短事务；渠道调用与 RPC 在事务外 | `PaymentApplicationService.java`、`PaymentPersistence.java` | 额度结算是**事务外副作用**，必须自带幂等（见 §5.3） |
| C4 | `PaymentResultProcessor.applyAndNotify` 亦无事务注解；`changed=true` 后才触发通知 order 与记账 | `PaymentResultProcessor.java:95` | 额度结算挂点必须与记账同级（同一 `changed` 分支） |
| C5 | 渠道回调会重复且乱序，靠状态机终态吸收 | `PaymentResultApplier.apply`；`payment-service.md` §5.2 | 第一道幂等闸门；但**不足以覆盖事务外崩溃** |
| C6 | 一交易多支付单（`transaction_no : payment_no = 1 : N`） | ADR-0064 | 每笔支付单独立预占、独立结算；失败的必须释放 |
| C7 | payment-service **当前不依赖 Redis**（支付事实需强一致，ADR-0044）；但 Redis 已由 `docker-compose` 提供（`redis:7`），order-service 已用 `StringRedisTemplate` 做订单超时时间轮 | `payment-service.md` §5.1；ADR-0044；`OrderTimeoutScheduler.java`；`deployment/docker-compose.yml` | **禁止**用 Redis 做额度**计数**（权威在 DB）；但 **D13 已批准**将其用于「在途占用过期索引」这一**非权威**用途（FR-036 / FR-038 / INV-9） |
| C8 | 演示组件同源代理**只读**接口 | ADR-0048 | 设置限额是写操作，需显式例外（见 §9 D9） |
| C9 | 演示链种子 SKU `DEMO-SKU-101` 单价 ¥99.00（9900 分） | `deployment/demo/scenario-happy-path.sh:39` | 演示场景可直接用「日限额 ¥150 / ¥250」撞线 |
| C10 | 下单步骤已有 `userId` 输入框（默认 `demo-user`） | `demo.html:60` | 限额演示的天然挂载点，无需新增用户概念 |
| C11 | `traffic-gen.sh` 持续下单；nightly E2E 全量跑链路 | `deployment/demo/traffic-gen.sh`；ADR-0069 | **限额默认关闭**是硬要求，否则既有脚本被 409 打断 |
| C12 | `createPaymentIntent(cmd, deferChannel=true)` 跳过渠道调用，支付单停留 PROCESSING 等收银台回调，30s 后转 UNKNOWN | `PaymentApplicationService.java:85-98`（注释「超时 30s 后由 TimeoutScanner 转 UNKNOWN」）；`TimeoutScanScheduler` | **在途占用可长期存在**，是 §5.8 TTL 需求的根因 |
| C13 | 时间参数：`payment.reliability.timeout=30s`、`query-max-attempts=5`、`query-interval-ms=15000`；`order.timeout.ttl-seconds=900` | `ReliabilityConfig.java`；`application.yml:58-64`；`OrderTimeoutProperties.java` | 在途占用 TTL **下界 = 30 + 5×15 = 105s**；默认值取 900s 与订单超时对齐（FR-037） |
| C14 | **order-service 全量源码无 `createPaymentIntent` / `PaymentClient` 引用** | grep（`order-service/src/main/java`）零命中 | 下单与支付解耦：下单只落 `PENDING_PAYMENT`、不建支付单 → **「下单未支付」额度零占用**，也是预占挂点选建单而非下单的依据（FR-006） |

## 3. 硬性不变量（不可破坏）

- **INV-1（事实源单一）**：权威的「用户已发生金额」**只能**由 payment-service 提供。任何服务（含 order）MUST NOT 自行维护用户消费累计，只能**读** payment 的额度服务。
- **INV-2（不阻断既有链路）**：未配置限额的用户，行为与今天**逐字节一致**。既有支付 / 退款 / 可靠性 / E2E 测试零改动通过。
- **INV-3（硬拒绝且不落库）**：超限时 MUST 返回 `409 LIMIT_EXCEEDED`，且 `payments` / `payment_attempts` **不得新增任何行**。是「未创建」，不是「创建了再拒」。
- **INV-4（幂等覆盖三种入口）**：同一 `paymentNo` 的同一种额度操作（`RESERVE` / `CONFIRM` / `RELEASE`）**至多生效一次**，覆盖：重复回调、事务外重试、补偿扫描重跑。
- **INV-5（不猜成败）**：`UNKNOWN` 状态的支付，其 `pending` **持续占用不释放**，直到收敛为终态。禁止以超时为由臆断失败而释放。
- **INV-6（金额不残差）**：预占与确认 MUST 使用同一金额（`payment.amountMinor`）。禁止用渠道回调回传的实付额参与额度结算。
- **INV-7（下限保护）**：`pending_minor` MUST NOT 为负数。释放一律走 `GREATEST(0, ...)`。
- **INV-8（无新中间件）**：不引入 MQ、规则引擎、调度框架。周期重置靠 `period_start` 现算（§5.2），补偿扫描复用既有 Scheduler 模式。
  - **已批准例外（D13，负责人 2026-09-16 拍板）**：引入 `spring-boot-starter-data-redis`，**用途仅限**「在途占用过期索引」（FR-036 / FR-038）。理由见 §5.8 与 ADR-0071 D13——它替代的是「TTL 扫描器 + 60s 轮询 + 全表扫描」这一整套机制，净复杂度更低。
- **INV-9（Redis 失效的方向性）**——本 Spec 允许引入 Redis 的**前提**：
  1. Redis MUST NOT 参与 `used_minor` / `pending_minor` 的**权威计算**，权威值恒在 DB；
  2. Redis 不可用 MUST **fail-open 保守占用**（跳过惰性回收、不做任何释放），**不得**因此拦截支付或抛错中断建单；
  3. Redis 数据丢失导致的提前释放 MUST 落入 FR-039 软超限口径并留痕，**不得**静默修正 `used`；
  4. 一句话：**Redis 出错只能让约束变松（可见），绝不能让已发生的事实被篡改（不可见）**。

## 4. 关键用户故事

### US1 - 日限额拦截超额支付（Priority: P1）

为 `demo-user` 配置日限额 ¥150.00，用户当日已成功支付 ¥99.00 后，再发起一笔 ¥99.00 的支付，被平台拒绝。

**Why this priority**：这是「限额」的最小可用价值——没有硬拒绝，本特性等于没做。

**Independent Test**：设日限额 15000 分 → 第一笔 9900 分支付成功 → 第二笔同额支付 → 断言 `409 LIMIT_EXCEEDED` 且 `payments` 表无第二行。

**Acceptance Scenarios**：

1. **Given** 日限额 ¥150.00、当日已付 ¥99.00，**When** 发起 ¥99.00 支付，**Then** 返回 `409 LIMIT_EXCEEDED`，`payments` 无新增行。
2. **Given** 日限额 ¥150.00、当日已付 ¥0，**When** 发起 ¥99.00 支付，**Then** 正常创建支付单，`pending_minor=9900`。
3. **Given** 日限额 ¥300.00、当日已付 ¥99.00，**When** 发起 ¥99.00 支付，**Then** 放行（99+99=198 ≤ 300）。
4. **Given** 未配置任何限额，**When** 发起任意金额支付，**Then** 行为与今天完全一致（INV-2）。
5. **Given** 月限额 ¥2000.00 已用 ¥1990.00、日限额充足，**When** 发起 ¥99.00 支付，**Then** 因月限额被拒（三个周期**各自独立判定、任一超限即拒**）。

---

### US2 - 在途占用可见，且不被并发击穿（Priority: P1）

支付在途（已预占、未终态）时，额度已被占用；并发的同类请求不会因「都读到未超限」而全部放行。

**Why this priority**：这是限额模型的核心正确性。只统计成功额，并发必然击穿。

**Independent Test**：日限额 25000 分、当日已付 9900 分 → 并发发起 2 笔 9900 分支付 → 断言**只有 1 笔**预占成功（9900+9900+9900 = 29700 > 25000），另一笔 409。

**Acceptance Scenarios**：

1. **Given** 日限额 ¥250.00、已付 ¥99.00，**When** 发起 ¥99.00 且停留 PROCESSING（收银台未回调），**Then** `used=9900`、`pending=9900`，可用额度为 ¥52.00。
2. **Given** 同上在途状态，**When** 再发起 ¥99.00，**Then** 因 99+99+99=297 > 250 被 `409` 拒绝。
3. **Given** 日限额 ¥250.00 且**无**已付额，**When** 并发发起 3 笔 ¥99.00，**Then** 恰好 2 笔预占成功、1 笔 409（第三条原子 UPDATE 影响 0 行）。
4. **Given** 一笔在途支付收敛为 FAILED，**When** 结算完成，**Then** `pending` 归零，额度完全归还。

---

### US3 - 幂等：重复回调与重试不重复计额（Priority: P1）

渠道重复回调、事务外结算失败重试、补偿扫描重跑，都不会让 `used` / `pending` 发生第二次变化。

**Why this priority**：这是「限额静默失效」的唯一防线（见 §2 C3/C4）。`used` 少计 = 用户可无限超额，比报错危险得多。

**Independent Test**：一笔支付成功后，重发 3 次成功回调 → 断言 `limit_operations` 中该 `paymentNo` 的 `CONFIRM` **恰好 1 条**，`used_minor` 等于单笔金额。

**Acceptance Scenarios**：

1. **Given** 支付已 `SUCCEEDED`，**When** 重发成功回调 N 次，**Then** 状态机返回 `changed=false`，额度操作**一次都不触发**。
2. **Given** 结算在事务外崩溃（支付已 `SUCCEEDED`、额度未 `CONFIRM`），**When** 补偿扫描运行，**Then** 补一条 `CONFIRM`，`used` 正确增加；**再运行一次**扫描不产生第二条。
3. **Given** 同一 `paymentNo`，**When** 并发提交两次 `CONFIRM`，**Then** 撞 `UK(biz_no, op_type)`，仅一条生效。
4. **Given** 支付处于 `UNKNOWN`，**When** 补偿扫描运行，**Then** **不结算**，`pending` 保持占用（INV-5）。

---

### US4 - 演示：限额在控制台可见可触发（Priority: P2）

运维/学习者能在演示控制台看到额度水位（已确认 / 在途），并能一键触发超限拒绝。

**Why this priority**：限额的正确性恰好是「肉眼不可见」的类型（都是 DB 里的数字），没有演示就等于没有交付。但它是 P1 的附属能力。

**Independent Test**：在 `demo.html` 设日限额 ¥150.00 → 点击「一键演示超限」→ 断言第二笔出现红色拒绝提示且额度卡 `used` 未变。

**Acceptance Scenarios**：

1. **Given** 打开演示控制台，**When** 查看右栏，**Then** 存在「用户限额」卡，显示日/月/年三条进度条与 `used` / `pending` 拆分。
2. **Given** 设置日限额 ¥150.00 并点击「一键演示超限」，**When** 连续建 3 笔支付单，**Then** 第 1 笔成功、第 2 笔 `409`、卡片出现拒绝说明。
3. **Given** 一次支付在途，**When** 刷新额度卡，**Then** 进度条出现「在途」分段（与「已用」视觉区分）。
4. **Given** 执行 `bash deployment/demo/scenario-limit.sh`，**When** 脚本结束，**Then** 退出码 0 且全部断言通过。

## 5. 功能需求（FR）

### 5.1 数据模型与 DDL

- **FR-001** 在 `payment` schema 新增配置表 `user_payment_limits`：

```sql
CREATE TABLE IF NOT EXISTS user_payment_limits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户引用（逻辑外键，不跨服务约束）',
    currency_code VARCHAR(8) NOT NULL COMMENT 'ISO-4217；限额按币种独立，不做汇率折算',
    daily_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '日限额（分）；0 = 该周期不限',
    monthly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '月限额（分）；0 = 不限',
    yearly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '年限额（分）；0 = 不限',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
    created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
    created_by VARCHAR(64), updated_by VARCHAR(64), version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_limits_user_ccy (user_id, currency_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户支付限额配置；无行 = 不限额';
```

- **FR-002** 新增累计表 `user_limit_usage`（**双金额**）：

```sql
CREATE TABLE IF NOT EXISTS user_limit_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL COMMENT 'DAY | MONTH | YEAR',
    period_start DATE NOT NULL COMMENT '周期起始日：DAY=当天 / MONTH=当月1号 / YEAR=当年1月1号',
    used_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已确认占用（支付 SUCCEEDED）',
    pending_minor BIGINT NOT NULL DEFAULT 0 COMMENT '在途占用（已预占未终态）',
    created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_user_ccy_period (user_id, currency_code, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户周期额度占用；跨周期靠 period_start 现算，旧行自然闲置';
```

  > **`period_start` 的设计要点**：不存「上次重置时间」，而存「这条记录属于哪个周期」。跨周期时按当前日期算出新的 `period_start`，查不到即建行——**零定时任务、零跨天临界**。

- **FR-003** 新增幂等流水表 `limit_operations`：

```sql
CREATE TABLE IF NOT EXISTS limit_operations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_no VARCHAR(32) NOT NULL COMMENT '业务单号 LO+雪花（ADR-0062）',
    biz_no VARCHAR(32) NOT NULL COMMENT '关联支付单 paymentNo（ADR-0063）',
    op_type VARCHAR(16) NOT NULL COMMENT 'RESERVE | CONFIRM | RELEASE | EXPIRED（FR-035）',
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL COMMENT '操作金额（分）',
    expires_at DATETIME NULL COMMENT '仅 RESERVE 有值：在途占用到期时刻（FR-036），其余类型为 NULL',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_limitop_biz_type (biz_no, op_type),
    KEY idx_limitop_user_type (user_id, op_type),
    KEY idx_limitop_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额度操作流水；UK 是幂等的数据库级兜底（INV-4）';
```

  > **两个索引的分工**：`idx_limitop_user_type` 服务于**惰性回收**（FR-038 第 1 步，按用户查未结算在途，日常路径）；`idx_limitop_expiry` 仅服务于**运维兜底与审计**（FR-043），日常判定不依赖。

  > `UK(biz_no, op_type)` 是本模型的**幂等核心**：一张支付单的每种操作只允许一条流水。撞键 = 已执行过，跳过。
  > `EXPIRED` 与 `CONFIRM` / `RELEASE` 互斥地占用同一 `biz_no` 的「结算位」——三者在扫描 SQL 的 `NOT EXISTS` 中并列（FR-038），任一存在即不再处理。

- **FR-004** 全部金额一律 `BIGINT` 最小货币单位（ADR-0010），**禁止 `float` / `double`**。
- **FR-005** 表结构列序遵循 spec 018 / ADR-0066 规范：自增 `id` → 业务主键 → 唯一索引列 → 其余列 → 审计 + `version`。

### 5.2 预占 / 确认 / 释放

- **FR-006** **预占（`RESERVE`）** 发生在创建支付意图时，`paymentPersistence.insertPending` **之后、`channel.charge` 之前**：
  - 插入 `(paymentNo, RESERVE)` 流水 + 原子累加 `pending_minor`，同一本地事务；
  - 判定 SQL（`used + pending + 本次 ≤ 限额` 由 `WHERE` 保证原子性）：

    ```sql
    UPDATE user_limit_usage SET pending_minor = pending_minor + ?
    WHERE user_id = ? AND currency_code = ? AND period = ?
      AND used_minor + pending_minor + ? <= ?
    ```

  - 影响 0 行 → 超限 → 抛 `LIMIT_EXCEEDED`，**整个建单事务回滚**（`payments` 不落行，守 INV-3）。
- **FR-007** **确认（`CONFIRM`）** 在支付真正迁移为 `SUCCEEDED` 时触发，与记账同挂 `changed=true` 分支（§2 C4）：`used_minor += amount`、`pending_minor -= amount`，同一事务。
- **FR-008** **释放（`RELEASE`）** 在支付迁移为 `FAILED` / `CLOSED` 时触发：`pending_minor = GREATEST(0, pending_minor - amount)`（INV-7）。
- **FR-009** `UNKNOWN` **不结算**，`pending` 保持占用（INV-5），等待现有 `TimeoutScanner` / `ChannelQueryScheduler` / 人工 resolve 收敛后按终态结算。**占用上限受 TTL 约束**：超期未结算则由 FR-038 自动 `EXPIRED` 释放，不会永久挂住。
- **FR-010** 三个周期**各自独立判定**：任一周期超限即整笔拒绝；未超限的周期**不得部分扣减**（拒绝时三个周期的 `pending` 全部回滚）。
- **FR-011** 预占与确认 MUST 使用 `payment.getAmountMinor()`（INV-6）。渠道回调的 `amountMinor`（实付额）**不参与额度结算**，保持其现有「仅落观测」语义。
- **FR-012** `limit` 值为 `0` 或该用户无配置行 → **跳过该周期判定**（不限额），且不创建 `usage` 行（避免堆积无意义记录）。
- **FR-013** 币种：按 `currency_code` 独立限额，一期**不做汇率折算**（roadmap 明确不做多币种清分）。

### 5.3 幂等与补偿（三道闸门，INV-4）

- **FR-014** **第一道 — 状态机终态吸收**：额度结算 MUST 挂在 `PaymentResultApplier.apply` 返回 `changed=true` 之后。重复/乱序回调返回 `changed=false` → 不触发任何额度操作。
- **FR-015** **第二道 — 幂等流水**：每次 `RESERVE` / `CONFIRM` / `RELEASE` MUST 先写 `limit_operations`，撞 `uk_limitop_biz_type` 即判定已执行并**跳过金额变更**（不得抛异常中断主流程）。
- **FR-016** **第三道 — 补偿扫描**：新增 `LimitSettlementScanner`（复用既有 Scheduler 模式，不引入调度框架），按固定间隔扫描：

  ```sql
  SELECT ... FROM payments p
    JOIN limit_operations r ON r.biz_no = p.payment_no AND r.op_type = 'RESERVE'
    LEFT JOIN limit_operations s
           ON s.biz_no = p.payment_no AND s.op_type IN ('CONFIRM','RELEASE')
  WHERE s.id IS NULL AND p.status IN ('SUCCEEDED','FAILED','CLOSED')
  ```

  命中则按 `p.status` 补 `CONFIRM` 或 `RELEASE`；因 FR-015 的存在，重复扫描天然幂等。
- **FR-017** 补偿扫描 MUST **跳过 `UNKNOWN`**（INV-5）。扫描任务只解决「终态已定但额度未结算」，绝不替业务判断成败。
- **FR-018** 额度结算失败 MUST NOT 回滚支付事实——沿用 ADR-0009 的既有口径（记账失败亦不回滚），交由 FR-016 收敛。

### 5.4 接口契约

- **FR-019** 新增全局错误码 `LIMIT_EXCEEDED`（`common-core` `ErrorCodes`），HTTP 映射 `409`，错误体 `ApiError` 复用现有结构。错误信息 MUST 说明**哪个周期**超限与当前额度（对齐 ADR-0049「给出合法取值清单」的纪律，避免只回一个码）。
- **FR-020** payment-service 新增内部端点（供演示组件与运维调用，ADR-0063 一律用业务单号）：
  - `GET  /internal/limits/users/{userId}` → 返回三个周期的 `limit` / `used` / `pending` / `available` 与最近 N 条操作流水；
  - `PUT  /internal/limits/users/{userId}` → 设置/更新限额配置（幂等，按 `user_id + currency_code` upsert）。
- **FR-021** 若采纳 §9 D9 方案 ②，`mock-channel-web` 新增同源代理 `/proxy/internal/limits/**`，**例外范围仅限该路径**，并在 ADR-0071 登记为 ADR-0048 的显式例外。
- **FR-022** 对 payment 的**既有对外契约零改动**：`CreatePaymentRequest` / `CreatePaymentResponse` 字段不变；超限以错误码表达，不新增响应字段。

### 5.5 配置与默认行为

- **FR-023** 新增配置挂点 `payment.limit.*`：

  ```yaml
  payment:
    limit:
      enabled: true                 # false = 完全跳过限额子域（灰度开关，可一键退回今天的行为）
      settlement-scan-interval: 30s # 补偿扫描间隔（FR-016）
      reserve-ttl: 900s             # 在途占用存活时长（FR-037），须 > 105s，建议与 order.timeout.ttl-seconds 一致
      redis:
        key-prefix: "limit:pending:"  # 在途占用过期索引的 key 前缀（FR-036）
      default-currency: CNY         # 未显式指定币种时的默认限额币种
  ```

- **FR-023a** Redis 连接复用 Spring Boot 既有 `spring.data.redis.*` 自动配置（与 order-service 一致）；**未配置或不可用时 MUST 降级为 `NoopLimitExpiryIndex`**（FR-042），不得因缺少 Redis 导致服务启动失败（INV-9.2 / INV-2）。

- **FR-024** `enabled=false` 时，限额子域 MUST NOT 参与建单路径，行为与今天逐字节一致（由既有测试原样通过来证明，INV-2）。
- **FR-025** **默认不限额**（FR-012）：`deployment/demo/seed.sh` **不得**为 `demo-user` 播种限额配置，避免 `traffic-gen.sh` 与 E2E 被 409 打断（§2 C11）。

### 5.6 可观测

- **FR-026** 新增计数器 `payment_limit_total`，标签 `op ∈ {reserve, confirm, release}`、`result ∈ {ok, exceeded, duplicate}`、`period ∈ {DAY, MONTH, YEAR}`。
- **FR-027** 超限 MUST 落 `FINANCIAL_AUDIT` 审计日志（`action=limit.exceeded`，含 `userId` / `period` / `limit` / `used+pending` / `requested`），并计 `payment_limit_exceeded` 专用指标。
- **FR-028** 补偿扫描补结算成功 MUST 落 WARN + 指标 `payment_limit_compensated`——它意味着主路径曾失败，需要被看见（对齐 spec 002 / T024「资金风险信号单独留痕」的既有纪律）。

### 5.7 演示组件（demo）

> 本节是限额的**交付重点**：限额正确性全部藏在 DB 数字里，没有可视化等于没交付。

- **FR-029** `demo.html` **左栏下单步骤**新增限额输入组（挂在既有 `userId` 输入框下方，§2 C10）：
  - 日 / 月 / 年限额三个输入（单位：元，提交前转分）；
  - 「设置限额」按钮 → `PUT /proxy/internal/limits/users/{userId}`；
  - 「一键演示超限」按钮 → 连续建 3 笔支付单，第 3 笔必然撞线（配合 C9 的 ¥99.00 与日限额 ¥150 / ¥250 两档）。
- **FR-030** `demo.html` **右栏新增「用户限额」卡**（`pa-card`，位于「订单状态」之下）：
  - 日 / 月 / 年三条进度条，**`used` 实心 + `pending` 半透明**拼在同一条内，视觉区分「已确认 / 在途」；
  - 每周期显示 `已用 ¥X / 在途 ¥Y / 剩余 ¥Z`（`font-variant-numeric: tabular-nums`）；
  - 底部列出最近 3~5 条 `limit_operations`（`paymentNo` + `op_type` + 金额）；
  - 超限时卡片底部出现红色提示条，说明「哪个周期超限、差多少、支付单未创建」。
- **FR-031** `/demo/trace` 新增**限额分组**（只读 SELECT）：`user_limit_usage` 按 `userId` 查、`limit_operations` 按 `paymentNo` 查；默认折叠，与既有分组样式一致。
- **FR-032** `portal.html` T1「业务演示」的 chips 新增一枚「限额」，tile-lead 补一句描述。
- **FR-033** 新增 `deployment/demo/scenario-limit.sh`，模式照抄 `scenario-happy-path.sh`（`http` + `assert_status` + `jget` + `assert_eq`），断言链：
  1. 设日限额 ¥150.00（15000 分）；
  2. 第一笔 ¥99.00 支付成功 → 断言 `used_minor=9900`、`pending_minor=0`；
  3. 第二笔 ¥99.00 → 断言 `409 LIMIT_EXCEEDED`，**并断言 `payments` 无第二行**（INV-3 的关键断言）；
  4. 重发成功回调 2 次 → 断言 `limit_operations` 中 `CONFIRM` **恰好 1 条**（INV-4）；
  5. 制造一笔 FAILED 支付 → 断言 `pending` 归零（释放生效）；
  6. 结束时清理限额配置（避免污染后续脚本，守 FR-025）。
- **FR-034** 演示纪律（ADR-0048）：脚本**只编排不伪造**，断言失败即非零退出；`run-all.sh` 是否纳入本脚本由实现期决定（默认**不纳入**，避免拖慢主链路演示）。

### 5.8 在途占用 TTL 与软超限口径（2026-09-16 追加，D11 / D12 已决策）

> **为什么追加**：FR-009 规定 `UNKNOWN` 不结算、`pending` 保持占用（INV-5）。该口径单独存在时，在
> `deferChannel=true`（演示收银台，§2 C12）路径下，用户「点了支付不回调」会让 `pending` **永久挂住**，
> 日额度被吃死——等价于一个可被主动触发的拒绝服务面（原 §7 L4）。本组需求为该口子补上出口。
>
> **同节澄清（「下单了但没支付」）**：因 §2 C14（order 不调 payment），下单仅落 `PENDING_PAYMENT`、
> 不创建支付单，故**下单未支付 = 额度零占用**，payment 侧无任何记录；超时由
> `OrderTimeoutScheduler`（Redis ZSet 时间轮）取消订单并释放库存，与额度无关。这是预占挂点选
> `createPaymentIntent`（FR-006）而非下单事件的直接收益，无需任何额外处理。

- **FR-035** **`EXPIRED` 操作类型**：`limit_operations.op_type` 在 `RESERVE` / `CONFIRM` / `RELEASE` 之外增加 `EXPIRED`，语义为「在途占用超期未结算，自动释放」。它**不是**业务终态判定，MUST NOT 反向修改 `payments.status`（守 INV-1 与 ADR-0004「不猜成败」）。
- **FR-036** **在途占用登记到 Redis（过期索引）**：`RESERVE` 成功后（DB `pending` 已累加）MUST 写

  ```
  SET limit:pending:{paymentNo} {amountMinor} EX {reserve-ttl}
  ```

  - Key 前缀可由 `payment.limit.redis.key-prefix` 覆盖；TTL 取 `payment.limit.reserve-ttl`，与 DB 侧 `expires_at` 同源同值。
  - 写 Redis 失败 MUST NOT 影响建单结果（INV-9.2）：仅记 WARN + `payment_limit_redis_error` 指标，该笔在途退化为「无索引」（只能靠终态结算或 FR-043 兜底回收）。
  - `CONFIRM` / `RELEASE` / `EXPIRED` 时 MUST `DEL` 该 key（尽力而为，失败同上，不中断主流程）。
  - `expires_at` 列**仍落库**，但语义降级为**审计与降级兜底**：正常路径的过期判定由 Redis TTL 负责，DB 侧不参与日常判定。
- **FR-037** **TTL 取值下界**：`reserve-ttl` MUST **大于支付侧最大自动收敛窗口** = `payment.reliability.timeout`（30s，PROCESSING→UNKNOWN）+ `query-max-attempts × query-interval-ms`（5 × 15s = 75s）= **105s**；否则会释放一笔正在被主动查询收敛的支付（§2 C13）。默认值取 **900s（15min）**，与 `order.timeout.ttl-seconds` 对齐：订单超时即被取消并释放库存，此后继续保留支付侧额度占用已无业务意义。
  - ⚠️ 该值为 payment 侧独立配置，**跨服务无法共享** `order.timeout.*`，两处需人工保持一致（实现期在 `payment-service.md` 与 `runbook.md` 同时标注）。
- **FR-038** **惰性回收（`LimitPendingRecycler`，无调度器）**：在**每次 `RESERVE` 判定之前**执行，共三步：

  1. 查该用户**未结算**的 `RESERVE` 流水（`op_type='RESERVE'` 且同 `biz_no` 无 `CONFIRM`/`RELEASE`/`EXPIRED`），走 `idx_limitop_user_type`；单用户在途通常 0~几条，成本可忽略；
  2. 对这批 `paymentNo` 批量判存（一次 `MGET` / pipeline）：Redis 中**不存在**即视为已过期；
  3. 判定过期的，在同一短事务内插 `(paymentNo, EXPIRED)` 流水 + `pending_minor = GREATEST(0, pending_minor - ?)`（INV-7）；撞 `uk_limitop_biz_type` 即跳过（FR-015 天然幂等）。

  回收完成后**再**执行 FR-006 的超限判定，保证过期的在途不会误伤本人。

  - **为什么这样就够**：被挂住的只有**该用户自己**的额度——他下一次发起支付时必然先回收，即**自愈**；他不再发起则占用对他无任何影响。相比「60s 轮询 + 全表扫描 + 新建调度器」，本方案无调度器、无全表扫描、无跨实例时钟依赖，过期判定交给 Redis TTL。
  - **Redis 不可用**：跳过第 2~3 步，**保守占用**、不做任何释放，记 `payment_limit_redis_unavailable`（INV-9.2）。
  - **竞态兜底**：回收与 `CONFIRM` 并发时，两条执行序都会多减一次 `pending`，由 `GREATEST(0, …)` 兜底为 0，`used` 始终按实付额正确累加。**`GREATEST` 是最终防线，全程不加锁**。
- **FR-042** **测试替身与降级实现**：定义 `LimitExpiryIndex` 接口——生产为 `RedisLimitExpiryIndex`；Redis 未配置或不可用时降级为 `NoopLimitExpiryIndex`（行为 = 永不回收、保守占用）。单测使用 `FakeLimitExpiryIndex`（可手动置过期）验证回收逻辑，使 **H2 测试零 Redis 依赖**通过（INV-2 / SC-002）。
- **FR-043** **DB 兜底查询**：保留 `expires_at` 列与 `idx_limitop_expiry`，仅供 Redis 长期不可用时的运维/人工回收与审计查询；日常判定路径不依赖它。
- **FR-039** **软超限口径（D12）**——一句话：**新支出硬约束，已发生事实软记账**。
  - `RESERVE`（准入）**硬**：`used + pending + 本次 > limit` → `409 LIMIT_EXCEEDED`，建单事务回滚（FR-006 / INV-3）；
  - `CONFIRM`（事实）**软**：支付已 `SUCCEEDED` 时 MUST **无条件**累加 `used`，即使结果 `used > limit`。**不得**因超限而拒绝结算、clamp 金额或回滚支付事实——钱已从用户账户真实扣出，篡改已发生金额正是 §2 C2 所述「静默少扣 → 限额失效」，比超额危险得多。
  - 三种触发场景：① TTL 已释放 `pending` 后支付才成功（最主要）；② `UNKNOWN` 人工判定成功时已超；③ **限额被运营调低**（如 1000→500 而当日已付 800，最常见却最易被忽略）。
- **FR-040** 软超限 MUST 可观测：计 `payment_limit_overrun` 指标（标签 `period`），落 `FINANCIAL_AUDIT`（`action=limit.overrun`，含 `userId` / `period` / `limit` / `used` / `overrun`），并在 `GET /internal/limits/users/{userId}` 响应中返回 `overrun: true` 与超出量（超出量**现算** `max(0, used - limit)`，不落冗余列）。**收敛机制**：下一笔 `RESERVE` 必然被拒，直至周期重置。
- **FR-041** **演示补录**（并入 §5.7）：`demo.html` 额度卡的 `pending` 分段在 TTL 到期后回落并出现一条 `EXPIRED` 流水；`scenario-limit.sh` 增补第 7 步——走 `deferChannel` 路径建单后不回调，等待 TTL（演示期可临时将 `reserve-ttl` 调至 5s）后断言 `pending` 归零且出现 `EXPIRED` 流水。

## 6. 验收标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿，reactor 条目数**不变**，`architecture-tests` 边界门禁通过（限额子域 MUST 落在 payment-service 内，不得被其他服务直接访问表）。
- **SC-002** 既有支付 / 退款 / 可靠性 / 集成测试**零改动**通过（证明 FR-024 / INV-2 生效）。
- **SC-003（US1）** 日限额 ¥150、已付 ¥99 → 第二笔 ¥99 返回 `409 LIMIT_EXCEEDED`，`payments` 与 `payment_attempts` **无新增行**；错误体说明超额的周期。
- **SC-004（US2 并发）** 单测/集成测试：日限额 ¥250，并发发起 3 笔 ¥99 → 恰好 2 笔成功、1 笔 409；`used+pending` 恒等于 19800。**不得出现 29700**。
- **SC-005（US3 幂等）** 一笔成功后重发回调 N 次 → `limit_operations` 中该 `paymentNo` 的 `CONFIRM` 恰好 1 条，`used` 等于单笔金额。
- **SC-006（补偿）** 人为构造「payment=SUCCEEDED 但无 CONFIRM 流水」→ 运行补偿扫描 → 补 1 条 `CONFIRM`、`used` 正确；**再运行一次不产生第二条**。
- **SC-007（UNKNOWN）** 支付停留 `UNKNOWN` → 补偿扫描运行 → **不结算**，`pending` 保持占用。
- **SC-008（下限保护）** 人为触发两次 `RELEASE` → `pending_minor` 不为负数（INV-7）。
- **SC-009（金额口径）** 渠道回调携带与请求不同的 `amountMinor` → 额度按请求额结算，`pending` 无残差（INV-6）。
- **SC-010（默认关闭）** 无配置用户 → 行为与今天一致；`traffic-gen.sh` 连续运行不被 409 打断（FR-025）。
- **SC-011（演示 US4）** `bash deployment/demo/scenario-limit.sh` 退出码 0；`demo.html` 限额卡显示三条进度条，在途状态出现「在途」分段，超限时出现红色拒绝提示。
- **SC-012（无新中间件）** 未引入 MQ / 规则引擎 / 调度框架；新增第三方依赖**仅** `spring-boot-starter-data-redis` 一项（D13 已批准例外，与 order-service 同一 parent），且 Redis 不参与额度权威计数（INV-8 / INV-9）。
- **SC-013（文档防漂移）** `payment-service.md` 新增限额章节（数据模型 / 建单流程挂点 / 错误码 / 指标）；`docs/operations/runbook.md` 补充 `payment.limit.*` 配置说明。
- **SC-014（在途占用 TTL）** 用 `FakeLimitExpiryIndex` 单测：构造 `RESERVE` 后手动置过期 → 触发惰性回收 → 断言 `pending` 归零、`limit_operations` 出现**恰好 1 条** `EXPIRED`，且 `payments.status` **未被改动**（FR-035 / FR-038）。Redis 真值路径由 `scenario-limit.sh` 第 7 步在 live 冒烟验证（FR-041）。
- **SC-015（软超限）** 构造「TTL 已释放 `pending`、随后支付成功」→ 断言 `used` 按实付额**如实**累加（哪怕 `used > limit`）、`pending` 不为负、产出 `payment_limit_overrun` 指标与 `limit.overrun` 审计；随后发起新支付 → 断言被 `409 LIMIT_EXCEEDED` 拒绝（FR-039 / FR-040）。
- **SC-016（Redis 降级）** 未配置 Redis / Redis 不可用时：服务正常启动、建单链路不受影响（不报错、不拦截），在途占用**保持不被回收**，且产出 `payment_limit_redis_unavailable` 指标（INV-9.2 / FR-042）。H2 全量测试在**无 Redis** 环境下通过（INV-2）。

## 7. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
|---|---|---|---|
| L1 | **退款不回补额度**（D7） | 额度是「支付发生额」而非「净支出」；用户退款后当日额度不恢复 | §9 D7；ADR-0071 |
| L2 | 无单笔限额、无商户/渠道维度限额 | 只能约束用户周期累计 | §8 |
| L3 | 多币种不折算，按币种独立限额 | 跨币种消费无法合并计算（roadmap 本就不做多币种清分） | FR-013 |
| L4 | `UNKNOWN` / 未回调支付会在 TTL 内**持续占用**额度（INV-5 的有意代价，上限见 FR-037） | 占用最长 `reserve-ttl`（默认 15min）后自动释放；释放后若支付最终成功则转为软超限（L9） | FR-009 / FR-035~038 |
| L5 | 一交易多支付单（C6）时，失败的支付单若未及时终态，会**虚占**额度 | 换渠道重试场景下额度可能短暂偏高，上限同 L4 | FR-008 / FR-016 |
| L6 | 补偿扫描有延迟（默认 30s） | 结算失败到补偿生效之间存在窗口，期间 `used` 偏低 | FR-016 / FR-023 |
| L7 | 限额配置无审批流、无变更审计台 | 改限额不留下「谁改的、为什么」 | §8 |
| L8 | `limit_operations` 与 `user_limit_usage` 只增不清理 | 长期运行后 `limit_operations` 行数随支付量线性增长 | §8（一期不做归档） |
| L9 | **软超限**：已发生的支付可能使 `used > limit`（FR-039 有意设计） | 限额是「准入约束」而非「事实约束」；超额部分可观测、不阻断，下一笔被拒直至周期重置 | FR-039 / FR-040；ADR-0071 D12 |
| L10 | 引入 Redis 作为在途占用过期索引（D13），payment-service 首次依赖 Redis | 多一个运行时依赖；Redis 不可用退化为「保守占用」（回到今天行为）；极端情况下（Redis 数据丢失）提前释放落入软超限 | FR-036 / FR-042；INV-9；ADR-0071 D13 |
| L11 | 惰性回收只在用户**下次发起支付**时触发 | 用户若在途挂起后长期不再支付，其 `pending` 会留在 DB 直到他下次支付或运维回收（FR-043）；不影响他人 | FR-038 |

## 8. 不做（Out of Scope）

- ❌ **不做风控评分 / 反欺诈**（ADR-0028 已否决，本 Spec 不触碰、不复用 `payment.risk.*`）。
- ❌ 不做单笔限额、商户维度限额、渠道维度限额（L2）。
- ❌ 不做多币种汇率折算（L3）。
- ❌ 不做退款回补额度（L1 / D7）。
- ❌ 不做额度冻结 / 解冻、白名单、人工提额审批流（L7）。
- ❌ 不做额度变更审计台、不做运营后台 UI（配置只经 `PUT /internal/limits/*` 与演示页）。
- ❌ 不做 `limit_operations` 归档与 `user_limit_usage` 历史周期清理（L8，一期不做）。
- ❌ 不引入 MQ / 规则引擎 / 调度框架（INV-8）；不做 **Redis 计数方案**（C7 / INV-9.1）——Redis 仅作在途占用过期索引（D13 / FR-036）。
- ❌ **不做在途占用的定时扫描器 / 60s 轮询**（D13 已否决该实现路径）：过期判定交给 Redis TTL，触发交给惰性回收（FR-038）。
- ❌ 不改 `payments` / `payment_attempts` 既有表结构；不改支付状态机；不改记账链路；不改对账抽取。
- ❌ 不做周期重置的定时任务（FR-002 的 `period_start` 现算已覆盖）。
- ❌ **不做 `PENDING` 的显式「放弃支付」关单出口**（D11 同期否决）：不改 `Payment.close()` 的 `SUCCEEDED/FAILED → CLOSED` 迁移约束，在途占用统一由 TTL 回收（FR-038）。

## 9. 待负责人确认的口径（D9~D10）

> D1~D8 已于 2026-09-16 由负责人确认并写入 ADR-0071。以下两项为 AI 自选默认值，**任一项修订需同步调整 §5.7 与 §6**。

| # | 口径 | AI 自选默认值 | 备选 | 修订的影响面 |
|---|---|---|---|---|
| **D9** | 演示组件的写权限 | **开放 `/internal/limits/**` 读写代理**，并在 ADR-0071 登记为 ADR-0048 的显式例外（理由：限额配置不产生资金动作） | ① 只由 `demo/seed.sh` 播种、演示页**只读**（严格守 ADR-0048，但演示是死的，无法现场调限额）② 复用 ADR-0049 场景注入模式（语义别扭，配置不是渠道模拟） | 选 ① 则 FR-029 的「设置限额」按钮与「一键演示超限」降级为固定脚本演示，FR-030 卡片仍保留；SC-011 相应调整 |
| **D10** | 周期重置机制 | **`period_start` 现算，零定时任务** | ① 每日 00:00 定时任务重置计数 ② 用 Redis TTL 自动过期（**D13 仅批准 Redis 用于「在途占用」；周期重置语义是「属于哪个周期」而非「创建后 N 秒」，且跨周期旧行需保留审计，故此处仍不采用**） | 选 ① 需新增调度器并处理跨天临界与失败补偿，且与 INV-8「不引入调度框架」冲突；本 Spec 不推荐 |

> **补记（2026-09-16 后续决策）**：**D11**（在途占用 TTL）、**D12**（软超限口径）、**D13**（允许 payment 使用 Redis 作为
> 过期索引，并以惰性回收替代定时扫描）已由负责人拍板采纳，不再属于待确认项。内容见 §5.8（FR-035~FR-043）与 ADR-0071 D11~D13。
> 同期否决项：不为 `PENDING` 开显式「放弃支付」关单出口——`Payment.close()` 现仅接受 `SUCCEEDED/FAILED → CLOSED`，
> 放开属改状态机（人类决策边界），且 TTL 已覆盖该诉求，成本不划算。

## 10. 文档与决策影响

| 类型 | 对象 | 动作 |
|---|---|---|
| 新增 ADR | `docs/adr/0032-user-payment-limit.md`（ADR-0071，🟡 Proposed） | 已创建 |
| 新增 Spec | `docs/specs/027-user-payment-limit/`（本文档） | 已创建 |
| 新增 Spec 三件套 | `plan.md`（技术方案：架构 / DDL / 流程 / Redis 设计 / 包结构论证 / 挂点清单）、`tasks.md`（T101~T130，批次 A~J）、`acceptance.md`（SC 逐条验收清单） | 已创建（2026-09-16） |
| **切割声明** | ADR-0028「最小风控」⛔ Not Implemented | **不改变**其结论；本 Spec §0 与 ADR-0071 背景段均显式切割，避免被误读为翻案 |
| **例外登记** | ADR-0048「演示组件只读代理」 | 若 D9 采纳 ②，需在 ADR-0071 登记例外，**范围仅限 `/internal/limits/**`** |
| 实现期同步 | `payment-service.md`（新增限额章节：数据模型 / 建单挂点 / 错误码 / 指标）；`docs/operations/runbook.md`（`payment.limit.*` 配置）；`deployment/schema/`（新增 DDL 文件） | 实现期（SC-013） |
| ⚠️ 待人工补 | `docs/adr/README.md`：索引新增 ADR-0071 行 + 「下一可用编号」改为 **ADR-0072** | **本文档编写在 worktree 内，未改动 README**（避免与主工作区未提交的 026 相关 README 改动冲突），需在 026 合并后由主工作区补 |
| 保持不变 | ADR-0028 / ADR-0064 / ADR-0009 / ADR-0044 / ADR-0062 / ADR-0063 / ADR-0010；spec 018 列序规范；spec 019 退款链路 | 不做改动 |
