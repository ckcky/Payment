# Plan: 034-reliability-hardening（可靠性加固与失败恢复闭环）

**版本**：v1.0（实现轮）　**日期**：2026-09-21
**前置**：[spec.md](spec.md)（v1.0，已批准）；[ADR-0082](../../../../docs/adr/0082-failure-recovery-ownership-and-compensation.md)
**裁决**：2026-09-21 负责人按 spec 推荐方案批准全部四组决策
（C-19 同事务化·方案 A / 出站失败台账 `pending_postings` 归调用方 / Resilience4j 移除 / 退款双扫描器 + late-success 复用超付分支）。

**边界声明**：本 Feature 与 032（reconciliation 真实化）并行。reconciliation 的差异处置策略、审计场景关批等业务语义属 032 —— 034 只在 reconciliation-service 落 `pending_postings` 台账（`AUDIT_ADJUSTMENT` 事件）与 TT-8 测试，**不触碰其差异处置逻辑**。spec 与代码冲突时停下报告，不即兴。

---

## 1. 总体 approach

按 spec §14 六个切片推进，**每个切片可独立构建通过**，逐批提交：

| 切片 | 内容 | 本 plan 的落地形态 |
|---|---|---|
| **034-A**（C-19，最小高危） | `onRefundResult` 终态迁移同事务化（方案 A） | `TransactionApplicationService` 注入 `TransactionTemplate`，把「TXRF complete + `refunded_minor` 累加 + `order.applyRefund` + 两表 save」包进同一事务；MQ/审计/notify 保持在事务外（成功后）；TT-1 用 `FaultHooks.killConnection` 证明崩溃窗口不再产生「TXRF 终态但 order 未记账」 |
| **034-B**（台账，主体） | `pending_postings` 三 schema 落地 + 四调用方接入 + M7 通知入账 | 全量 schema（03/22/26）+ 增量 `034-*.sql` + 各服务 `posting` 包（domain/infra/recorder/scheduler）+ 四个 Feign gateway 包装 + `ORDER_NOTIFY_SUCCEEDED/REFUND_RESULT` 事件 + 管理重放端点 + gauge |
| **034-C**（退款扫描 + 观测） | RefundUnknownQueryScheduler + StrandedRefundOrderScanner + UNKNOWN 队列视图 + 指标/告警 | payment 退款域新调度器；order 域搁浅扫描器（复用 034-B 无关的本地重放上限）；`/internal/payments/unknown` 队列视图；`*_unknown_age` 扫描期指标 + `deployment/prometheus/rules/payment-alerts.yml` |
| **034-D**（late success） | C-23 关单后成功分支 | `PaymentResultProcessor` 识别 CLOSED 上的 SUCCESS → `payment.late_success_on_closed` 计数 + `PaymentSucceededRequest.late=true` → order 现有超付分支吸收 |
| **034-E**（DLQ 管理） | MQ DLQ 读/重放/清空 + 尺寸 gauge | `common-redis-mq` 新增 DLQ 管理组件（`@ConditionalOnClass` 保护，非 web 环境零影响）；重放 = 原 envelope XADD 回主 stream + XDEL；`mq_dlq_size` gauge；`/internal/mq/dlq/**` 管理端点带 admin token 守卫 |
| **034-F**（依赖清理） | Resilience4j 移除 | payment-service pom 删 `spring-cloud-starter-circuitbreaker-resilience4j` + application.yml 删 `spring.cloud.openfeign.circuitbreaker.enabled`；验证零引用 |

**诊断三条（walkthrough ③的执行项）**：① 新增调度器入口一律 `TraceContext.runWithNewTrace(...)`（既有 6 个入口已合规，只管新增）；② `ChannelQueryService.queryRound` 的毒丸 payment（无 recorded attempt）改为 per-payment try/catch + 一次性 warn + 计数，不再让单条脏数据 abort 整轮并每 15s 刷栈；③ `DemoProxyController` 的每次代理 INFO 降级（GET 静默 / 非 GET debug），消除演示控制台 2s 轮询刷屏。

## 2. `pending_postings` 设计（对 spec §9.1 / ADR-0079 待办 1 的落实）

### 2.1 表结构（三 schema 同构：`payment` / `settlement` / `reconciliation`）

```sql
CREATE TABLE IF NOT EXISTS pending_postings (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type    VARCHAR(64)  NOT NULL COMMENT 'PAYMENT_CAPTURE/REFUND_CAPTURE/SETTLEMENT_MERCHANT/AUDIT_ADJUSTMENT/ORDER_NOTIFY_SUCCEEDED/ORDER_NOTIFY_REFUND_RESULT',
  source_id     VARCHAR(64)  NOT NULL COMMENT '调用方业务单号（幂等源）',
  idempotency_key VARCHAR(128) NOT NULL COMMENT '传给 Ledger 的派生幂等键（与请求体一致）',
  payload       TEXT         NOT NULL COMMENT 'AccountingEventRequest JSON（重放时原样重发）',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REPOSTED/ABANDONED',
  retry_count   INT          NOT NULL DEFAULT 0,
  last_error    VARCHAR(512) NULL,
  next_retry_at DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_pending_postings_source UNIQUE (event_type, source_id),
  KEY idx_pending_postings_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

要点：
- **UNIQUE(event_type, source_id)**：同事件同源只允许一行 —— 「先查再插」的并发竞态由捕获 `DuplicateKeyException` 吸收（已在窗口内的行视为已登记，不重复重放）。
- **不落 `ledger` schema**：A-01~A-04 全部为调用方本地库；Ledger 自身的幂等键（ADR-0077 D4）仍是最终防线。
- `payload` 存完整请求体 JSON：重放 = 原样重发，不重算派生键（与首次请求逐字节一致，杜绝二次漂移）。
- 列序遵循 ADR-0066 规范（自增 id → 业务列 → 状态列 → 审计列）；无 BaseEntity 审计五件套（台账是基础设施行为记录，非业务聚合，`created_by/updated_by/version` 无意义，用裸 created_at/updated_at）——在 lint 审查中如被质疑，按 reconciliation `03-payment-schema.sql` 先例说明。

### 2.2 写入与重放语义（单计数器模型）

- **登记时机**：gateway 调用 Ledger **失败时**（连接异常/非 2xx/超时），在调用方本地库记录 `PENDING` 行（retry_count=1，next_retry_at=now+1s）。**成功绝不登记** —— 表是「失败台账」，不是「发送日志」，避免每笔成功交易多一行写放大。
- **重试节奏**：调度器每 10s 扫 `status=PENDING AND next_retry_at<=now`，退避 **1s/5s/30s/2m/10m**；每次重试失败 retry_count++ 并推进 next_retry_at；**retry_count 达到 6**（首次登记 + 5 次重试全部失败）→ `ABANDONED` + `ledger_posting_abandoned` 计数。**retry_count 是唯一计数器**（spec §16 风险 2：不另设 attempt 列双写）。
- **重放成功**：该行置 `REPOSTED`（终态保留，供审计追溯），gauge `ledger_posting_pending` 按 PENDING 行数取值。
- **人工重放**：`POST /internal/postings/{id}/replay`（admin token 守卫，复用 ResolveAuthorizationInterceptor 模式）→ 立即执行一次投递，成功即 REPOSTED；`retry_count` 重置为 0 以便重新进入自动退避（spec §12.1）。
- **同事务边界**：登记动作与业务主事务**同库同事务**（payment 结束态写入与台账写入在同一本地事务；settlement/reconciliation 同理）——崩溃时要么都在（重试器会补投），要么都不在（无幽灵台账）。Feign 调用本身在事务外：失败后回到事务内只做一行 INSERT。

### 2.3 事件类型与调用方矩阵

| 调用方 | event_type | source_id | 幂等键 | 落库 schema |
|---|---|---|---|---|
| payment `FeignLedgerPostingGateway` | PAYMENT_CAPTURE | paymentNo | `PAYMENT_CAPTURE:{paymentNo}` | payment |
| payment `RefundFeignLedgerPostingGateway` | REFUND_CAPTURE | refundNo | `REFUND_CAPTURE:{refundNo}` | payment |
| settlement `FeignLedgerPostingGateway` | SETTLEMENT_MERCHANT | batchNo | `SETTLEMENT_MERCHANT:{batchNo}` | settlement |
| reconciliation `FeignAuditLedgerGateway` | AUDIT_ADJUSTMENT | 调整单号 | `AUDIT_ADJUSTMENT:{adjustmentNo}` | reconciliation |
| payment → order 通知（M7） | ORDER_NOTIFY_SUCCEEDED | paymentRef | —（非记账） | payment |
| payment → order 通知（M7） | ORDER_NOTIFY_REFUND_RESULT | refundNo | —（非记账） | payment |

- M7 通知失败台账**只对齐表结构，不走记账调度器语义**：payload 存 OrderGateway 请求体，重放直接 HTTP 重发；`idempotency_key` 列存空串（列 NOT NULL，语义上「无派生键」）。
- 四个 gateway 的**成功路径零改动**（不入表、不加延迟）；失败路径从「只打点」升级为「打点 + 落台账」。
- `FeignAuditLedgerGateway` 现状为「失败直接上抛」（NFR-008）：保留上抛语义（审计写入方的错误处理不变），**上抛前**先落台账行 —— 补偿机会 + 原错误语义两者兼得。

## 3. 退款/搁浅扫描设计（对 spec §7 的落实）

### 3.1 RefundUnknownQueryScheduler（payment 退款域）

- 复用 `RefundRepository.findByStatus(UNKNOWN)`（现有端口，零扩展）；退避参数复用 `ReliabilityConfig` 的 queryMaxAttempts=5 / queryIntervalMs=15s。
- **不新增 schema**：UNKNOWN 期间 `refunds.updated_at` 稳定（无任何写路径触碰该行），以其为 age 事实源；尝试次数用**时间窗**推导：`age ≤ queryMaxAttempts × queryIntervalMs` 内才允许发起查询（第 n 次窗口边界的越界沿只触发一次 `refund.query_exhausted` 计数，`age < window + interval` 守卫防重放）。
- 查询实现与 `RefundResultProcessor`（Source.RESOLVE）同构：命中 → 统一收敛；渠道无记录 → 保持 UNKNOWN。
- 入口 `runWithNewTrace`（诊断①）。

### 3.2 StrandedRefundOrderScanner（order 退款域）

- 前置：`TransactionRefundRepository` 增加 `List<TransactionRefund> findByStatus(RefundOrderStatus)`（纯端口扩展，order 自己的表）。
- 扫 `PROCESSING` 且 `updated_at` 早于 `order.refund.stranded-threshold`（默认 30s）的 TXRF；对每个搁浅单**重新调用 doCreateRefund 的 unaccepted-retry 分支**（现有代码路径，已处理「已有 PROCESSING 单则复用/拒绝」幂等）。
- **重放上限 ≤2**：内存 ConcurrentHashMap<refundNo, Integer> 计数，达上限后写 `FINANCIAL_AUDIT` 审计事件并停扫该单（等待 payment 侧 RefundPolicy 兜底/人工收敛）；重启后计数归零可再扫 2 次（可接受：上限是防风暴不是防重放的总闸，spec §7.3 允许）。

### 3.3 UNKNOWN 队列视图与指标

- payment 新增 `GET /internal/payments/unknown?olderThan=`：返回 UNKNOWN 队列聚合（数量/最老 age/分桶），供告警与人工排查（无状态、只读）。
- 扫描期指标 `payment_unknown_age` / `refund_unknown_age`（Counter，bucket=0_5m/5_30m/30m_24h/gt_24h，按 035 目录命名）：与既有收敛期 `payment.unknown_age` 同名同标签（035 目录视为同一指标的两个发射点）。
- `deployment/prometheus/rules/payment-alerts.yml` 增补 A-05（台账积压）/ A-06（DLQ>0）/ A-08（unknown>24h）/ A-09（ABANDONED>0）四条规则，命名与现有规则文件风格一致。

## 4. late-success 设计（对 spec §8 的落实）

- `PaymentResultProcessor.applyAndNotify` 在收到 SUCCESS 但 payment 已 CLOSED（`closeByOrderCancelled`）时：**不修改状态机**（close 幂等吸收既有语义），置 `late=true`，发 `payment.late_success_on_closed` 计数，`PaymentSucceededRequest.withoutItems(...)` → `.late(true)` 传给 order。
- order 的 `onPaymentSucceeded` 现有超付分支（paid 金额 > 订单应付）自然吸收 late 成功（生成 REFUND 方向记账/退款单），**不改 order 判断逻辑** —— spec §15 H-034-4「复用超付分支」即此意。
- 已知边界（记入 acceptance 备注，不发明新行为）：late success 不产生 PAYMENT_CAPTURE 记账（capture 时点已过），order 超付分支产生的 REFUND 记账在 ledger 视角是「单边补偿」——由 reconciliation 差异处置（032 范围）发现与处置。

## 5. DLQ 管理设计（对 spec §10 的落实）

- `common-redis-mq` 新增 `dlq` 包：`MqDlqAdminService`（XRANGE 读 / XADD 回主 stream + XDEL 重放 / DEL 清空）/ `MqDlqSizeGauge` / `MqDlqAdminController`。
- **Web 可选**：`common-redis-mq` 不依赖 spring-boot-starter-web；controller 类挂 `@ConditionalOnClass(RestController.class)` + 配置开关 `payment.mq.dlq-admin.enabled`（默认 false），auto-config `@Import` 进来即可，非 web 服务（无）加载失败由条件注解挡住。
- **envelope 原样重放**：读出的 fields 经 `EnvelopeCodec.fromFields` 校验后**原 fields XADD** 回 `mq:stream:{topic}` + XDEL DLQ 条目 —— 消费端以原 msgId 幂等吸收（不改 MqKeys、不改 envelope 结构，spec §10 红线）。
- 管理端点守卫复用 X-Admin-Token 模式（`payment.mq.dlq-admin.admin-token`，空则 503）。
- gauge `mq_dlq_size`（label: topic），注册时以 `RedisTemplate` 回调读 XLEN（低频调用由 Micrometer 采样控制）。

## 6. 测试计划（对 spec §11 TT-1~TT-12 的落实）

真库用例统一走 033 基座：`RealMysqlTestSupport` + `SchemaBootstrap.Script`（requiredMarkers）+ `FaultHooks`（TT-1 killConnection / TT-2 慢查询）+ `MetricsAssert`。

| TT | 层级 | 载体 | 断言核心 |
|---|---|---|---|
| TT-1 | L2b（order-service，real-db） | C-19：注入 `FaultHooks` killConnection 于「TXRF save 后、order save 前」 | 崩溃后重放回调 → TXRF/order 一致收敛（旧代码该场景必现「TXRF 终态 + order 未记账」） |
| TT-2 | L2b（payment，real-db） | Ledger 假死（slowQuerySeconds） | gateway 失败 → pending 行生成；恢复后调度器补投成功 → REPOSTED |
| TT-3 | L2b（payment，real-db） | 连续 6 次失败 | retry_count=6 → ABANDONED + abandoned 计数 + 退出扫描 |
| TT-4 | L2b（payment，real-db） | 并发双写同 (event_type, source_id) | 一行成功一行 DuplicateKeyException 吸收，无重复台账 |
| TT-5 | L2b（payment，real-db） | refund UNKNOWN + 渠道恢复 | 调度器查询命中 → 收敛 + 状态迁移 |
| TT-6 | L2b（payment，real-db） | refund UNKNOWN 超 75s 窗口 | query_exhausted 单次发射 + 停扫 |
| TT-7 | L1（order-service） | 搁浅 TXRF（时钟回拨注入 updated_at） | scanner 触发重试 ≤2 次 → FINANCIAL_AUDIT 审计 |
| TT-8 | L2b（reconciliation，real-db） | audit gateway 失败 | 台账行生成后**异常仍上抛**（NFR-008 语义保持）+ 补投成功 |
| TT-9 | L1（payment） | CLOSED payment 收到 SUCCESS | late=true + 计数 + 状态不被改写 |
| TT-10 | L2（common-redis-mq） | embedded-redis | 消费 3 次失败 → DLQ；read/replay/清空；重放后消费端按原 msgId 幂等吸收 |
| TT-11 | L1（settlement） | 台账记录/重试/放弃单元路径 | recorder/scheduler 逻辑（真库由 TT-2 模式覆盖同构代码） |
| TT-12 | L1（全模块，既有门禁复跑） | ServiceBoundaryTest / RpcEdgeAllowListTest / schema-lint / schema-replay | 无新增跨服务 Feign 边（rpc-edges.txt 零变化）；lint/replay 双路径过 |

**架构门禁自查**：`posting` 包落各服务自身（`com.payment.posting` / `com.payment.settlement.posting` / `com.payment.reconciliation.posting`），无跨服务 import；domain 不引 Spring/infra；api/web 不直连 persistence；不新增任何 `@FeignClient`。MqKeys / envelope / TraceContext 零修改（common 库只增不改）。

## 7. 风险与回退

| 风险 | 缓解 |
|---|---|
| 台账写入与业务事务同库，失败时主事务被连坐 | 台账 INSERT 放在业务事务**最后一步**且自身失败不回滚主事务（catch 包裹 + error 计数，台账丢失可由下轮对账发现，优于打断业务）——Feign 调用在事务外，此风险仅在 DB 写台账时存在，概率与普通 INSERT 相当 |
| 未知旧库快照与增量重放冲突 | 增量 `034-*.sql` 用 `CREATE TABLE IF NOT EXISTS`，双路径重放天然幂等（无 ALTER） |
| settlement/reconciliation 复制三份 posting 包漂移 | 表结构/退避常量/状态机逐字节一致由 TT 复跑 + code review 把关；不做「公共模块抽象」（三服务库表各自独立，强行抽象反增耦合——033 前例） |
| late=true 请求与旧 order 版本共存 | order 对未知字段零依赖（Jackson 默认忽略），灰度顺序无约束 |
