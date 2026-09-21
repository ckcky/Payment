# Tasks: 034-reliability-hardening（可靠性加固与失败恢复闭环）

> 执行顺序即切片顺序（A→F）；每个切片构建通过后提交一批。
> 标记：✅ 完成并验证；⬜ 待做。验收证据见 [acceptance.md](acceptance.md)。

## 034-A：C-19 同事务化（方案 A）

- ✅ T1 `TransactionApplicationService` 注入 `TransactionTemplate`（构造器，对齐 OrderApplicationService 先例），把 `onRefundResult` 的「TXRF complete + `refunded_minor` 累加 + `order.applyRefund` + refund/order 两表 save」包进同一事务；`!firstTerminal` 提前返回、MQ 通知、审计保持事务外（成功后执行）
- ✅ T2 TT-1（L2b，order-service real-db）：`FaultHooks.killConnection` 注入「TXRF save 后、order save 前」崩溃窗口 → 重放回调 → 断言 TXRF 与 order `refunded_minor` 一致收敛；order-service pom 增 `deployment/test-infra` test 依赖
- ✅ T3 构建通过 + 提交 `fix(034): C-19 ...`

## 034-B：出站失败台账 pending_postings

- ⬜ T4 DDL：全量 `03-payment-schema.sql`（payment）/ `22-...`（settlement）/ `26-...`（reconciliation）各加 `pending_postings` 表 + 增量 `deployment/schema/034-pending-postings.sql`（三库 CREATE TABLE IF NOT EXISTS）；过 `schema-lint.sh` + `schema-replay.sh` 双路径
- ⬜ T5 payment-service `com.payment.posting` 包：`PendingPosting`（domain）、`PendingPostingEntity`+mapper（infra）、port/adapter、`PostingPendingRecorder`（失败登记，DuplicateKeyException 吸收）、`PostingRetryScheduler`（10s 扫描、1s/5s/30s/2m/10m 退避、retry_count=6 → ABANDONED、REPOSTED 终态）、`runWithNewTrace` 入口
- ⬜ T6 settlement/reconciliation 同构 `posting` 包（各自包名、各自库表、各自 gateway 包装）
- ⬜ T7 四 gateway 接入：`FeignLedgerPostingGateway`（PAYMENT_CAPTURE）、`RefundFeignLedgerPostingGateway`（REFUND_CAPTURE）、settlement `FeignLedgerPostingGateway`（SETTLEMENT_MERCHANT）、`FeignAuditLedgerGateway`（AUDIT_ADJUSTMENT，失败上抛语义保留、上抛前落台账）；成功路径零改动
- ⬜ T8 M7 通知失败入账：order notify 失败 → `ORDER_NOTIFY_SUCCEEDED:{paymentRef}` / `ORDER_NOTIFY_REFUND_RESULT:{refundNo}` PENDING 行；重放 = 原请求重发
- ⬜ T9 管理端点 `POST /internal/postings/{id}/replay`（admin token 守卫，retry_count 重置 0）+ gauge `ledger_posting_pending`（BusinessMetrics 增 gauge default 方法 + Micrometer 实现 + Noop）
- ⬜ T10 TT-2/TT-3/TT-4（payment real-db）+ TT-8（reconciliation real-db）+ TT-11（settlement L1）
- ⬜ T11 构建通过 + 提交 `feat(034): pending_postings ...`

## 034-C：退款扫描器与 UNKNOWN 观测

- ⬜ T12 RefundUnknownQueryScheduler（payment 退款域）：`RefundRepository.findByStatus(UNKNOWN)` + 时间窗（75s）+ `updated_at` 作 age + 一次性 `refund.query_exhausted`；复用 ReliabilityConfig 参数；RESOLVE 路径收敛同 RefundResultProcessor
- ⬜ T13 `TransactionRefundRepository.findByStatus` 端口扩展 + adapter；StrandedRefundOrderScanner（order）：`order.refund.stranded-threshold=30s`、doCreateRefund unaccepted-retry 分支重放、内存 ≤2 上限 + FINANCIAL_AUDIT 审计
- ⬜ T14 `GET /internal/payments/unknown?olderThan=` 队列视图（只读聚合）；扫描期 `payment_unknown_age`/`refund_unknown_age` Counter（bucket 0_5m/5_30m/30m_24h/gt_24h）
- ⬜ T15 `deployment/prometheus/rules/payment-alerts.yml`：A-05/A-06/A-08/A-09 四条
- ⬜ T16 TT-5/TT-6（payment real-db）+ TT-7（order L1）；诊断②：ChannelQueryService per-payment try/catch + 一次性 warn + 计数
- ⬜ T17 构建通过 + 提交 `feat(034): refund scanners ...`

## 034-D：late success（C-23）

- ⬜ T18 `PaymentSucceededRequest` 增 `late` 标志（builder/静态工厂对齐现有风格）；`PaymentResultProcessor` CLOSED 上 SUCCESS → `late=true` + `payment.late_success_on_closed` 计数；order 超付分支吸收（不改 order 判断逻辑）
- ⬜ T19 TT-9（payment L1）；构建通过 + 提交 `feat(034): late success ...`

## 034-E：MQ DLQ 管理

- ⬜ T20 `common-redis-mq` 新增 dlq 包：MqDlqAdminService（XRANGE / XADD+XDEL 重放 / 清空）、`mq_dlq_size` gauge、`MqDlqAdminController`（`@ConditionalOnClass(RestController)` + `payment.mq.dlq-admin.enabled` 默认 false + admin token 守卫）；pom 增 spring-web provided；auto-config imports 登记
- ⬜ T21 服务侧启用：需要 DLQ 管理的服务 yml 开 `payment.mq.dlq-admin.enabled=true` + admin token env；MqKeys/envelope 零改动
- ⬜ T22 TT-10（embedded-redis L2）；诊断③：`DemoProxyController` GET 代理日志降级（静默/debug）；构建通过 + 提交 `feat(034): mq dlq ...`

## 034-F：Resilience4j 移除

- ⬜ T23 payment-service pom 删 `spring-cloud-starter-circuitbreaker-resilience4j`；application.yml 删 `spring.cloud.openfeign.circuitbreaker.enabled: true`；全仓 grep 零引用确认
- ⬜ T24 构建通过 + 提交 `chore(034): remove Resilience4j ...`

## 收口

- ⬜ T25 §8 超时策略表落 `technical-solution.md` + 相关 WHY 注释核对
- ⬜ T26 TT-12：全量 verify（ServiceBoundaryTest/RpcEdgeAllowListTest/schema-lint/schema-replay 复跑零回归）
- ⬜ T27 L0 系统文档同步（payment/order/settlement/reconciliation 四份，紧凑增补）
- ⬜ T28 acceptance.md 实证回填（verify 统计 + TT 结果）
- ⬜ T29 docs 同步：CHANGELOG 头条 `feat(034)`、specs/README 034 → 🟢、roadmap 行、（ADR-0082 🟢 已随首批提交翻转）
- ⬜ T30 push + `gh pr create`（不 merge，编排器控序）
