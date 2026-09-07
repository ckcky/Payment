# Tasks: 022 全链路自动化测试体系

> 承载目标：把「demo 控制台发起支付/退款 → 观察各系统状态与 DB 数据」变成可重复、可报告、可进 CI 的自动化测试；
> 覆盖四件事：**退款功能正常 / 超退能拦截 / 对账准确 / 单号记对**。
> **当前状态：批次 A（文档）已完成；批次 B~E 待实施**（负责人 2026-09-07：「不用进行开发，写 spec 文档推送到 master 就行」）。
> 实施时按顺序推进，每批次结束跑对应门禁。

## 批次 A — 文档与决策（已完成）

- [x] **T401** 编写 spec 022 四件套：spec.md（现状 G1~G6 / 两个控制台功能盘点 / 三个假绿陷阱 / 业内对比 / 决策 D1~D8 / US1~US5 / FR-001~015 / NFR / SC-001~007 / 明确不做七项）
- [x] **T402** 立项 [ADR-0069](../../adr/0030-end-to-end-automated-testing.md)（全链路自动化测试体系）+ `docs/adr/README.md` 注册（跳转表 + 编号速查 + 下一可用编号）

## 批次 B — E2E 模块骨架（依赖：无）

- [ ] **T403** 新建 `deployment/e2e-tests/pom.xml`：黑盒模块（不依赖业务模块），依赖 junit-jupiter / assertj / awaitility / jackson / mysql-connector-j / slf4j-simple；`ci` profile 才引 testcontainers（FR-001）
- [ ] **T404** `support/Env.java`：`-De2e.env=local`（默认，读 `e2e-local.properties`：10 个服务端口 + MySQL 3306 各 schema）/ `ci`（Testcontainers 或 CI service 容器地址）
- [ ] **T405** `support/Api.java`：HTTP 封装（JDK HttpClient）—— 下单 / 建支付单 / 渠道回调 / 退款 / 查状态 / 对账 / 结算；**4xx/5xx 原样返回**供状态码断言（FR-003）
- [ ] **T406** `support/Db.java`：按 schema 注册多数据源 JDBC 探针（order/payment/fulfillment/entitlement/ledger/settlement/reconciliation/catalog/merchant）（FR-004）
- [ ] **T407** `support/Await.java`：Awaitility 统一轮询（默认超时 15s 可配，**禁用 Thread.sleep**）（FR-005 / NFR-004）
- [ ] **T408** `support/Trace.java`：`/demo/trace?orderId=` 客户端，按 section 取 `system/table/label/rows`（FR-008）
- [ ] **T409** `support/Dump.java`：失败自动落盘 `target/e2e-dump/<case>/`（响应体 / trace 快照 / 相关表 SELECT * / invariants.log）（FR-007）
- [ ] **T410** `run.sh` + `README.md`：检查栈就绪 → 跑测试 → 出报告；README 写清起栈、跑单条/全量、看报告与 dump（FR-001 / FR-015）

## 批次 C — 断言原语库（依赖：批次 B）

- [ ] **T411** `support/Invariants.java#refundNotExceedPaid`：payment 累计已退（在途按申请额保守计）≤ 实付，且与 `order.transactions.refunded_minor` 相等
- [ ] **T412** `#businessNoChain`：OR/TR/PM/PA/TXRF/PMRF 前缀 + 雪花长度；**双号互记**（`transaction_refunds.payment_refund_no == refunds.refund_no`、`refunds.transaction_refund_no == transaction_refunds.refund_no`）；`transactions.payment_no` == 生效 `payments.payment_no`；attempt 归属与 `attempt_type='REFUND'`
- [ ] **T413** `#ledgerBalanced`：按单 `SUM(借)==SUM(贷)` + 全局试算平衡 + append-only 检查
- [ ] **T414** `#orderStatus` / `#entitlementRevoked` / `#fulfillmentTerminated`：订单状态一致性、权益全部非 AVAILABLE、履约逐条终止（spec 018 后按 item 多条）
- [ ] **T415** `#reconDiffDetected` / `#settlementGated`：差异检出率 100% 且 kind/severity 分类正确；未收口差异时结算被拒
- [ ] **T416** `#stockRestoredForSeckill` / `#idempotentReplay` / `#noOrphanRows`：秒杀回补且普通 SKU 不变；同号重放行效不变；本轮无孤儿单
- [ ] **T417** 断言失败信息规范：必含期望值 / 实际值 / 关联业务单号 / 涉及库表（NFR-003）

## 批次 D — P0 用例（依赖：批次 C）

- [ ] **T418** `refund/RefundChainE2ETest`：部分退款主链 + 全额退款主链（AC1.1~AC1.5，六库一致 + 后效齐全 + dump 产物）
- [ ] **T419** `refund/OverRefundGuardE2ETest`：①单次超已付 ②多次累计超退 ③**并发两笔退款总额不超付**（AC2.1~AC2.3，DB 侧不变量恒真）
- [ ] **T420** `recon/ReconciliationAccuracyE2ETest`：会计四核对 **LIVE** 模式 CLEAN 0 差异 / FAULT(F2~F9) 八类全检出且分类正确；挂账→调账→recheck→close 闭环后差异清零；有未收口差异 close 返回 400；试算平衡 Σ=0 且 SUSPENSE 归零（AC3.1~AC3.3、AC3.5）
- [ ] **T421** `recon` 渠道对账差异注入：新增 period 的 CSV 注入长款/短款/金额不符/单边账/重复 5 类差异 → 检出率 100% 且分类正确（AC3.4）
- [ ] **T422** `numbering/BusinessNoChainE2ETest`：前缀与雪花唯一性、双号互记、attempt 归属、跨库可追溯无孤儿（AC4.1~AC4.5）
- [ ] **T423** 数据隔离落地：每用例唯一前缀 `e2e-{runId}-{case}`，断言按单号过滤（FR-013 / NFR-002）

## 批次 E — P1 用例（依赖：批次 D）

- [ ] **T424** `reliability/IdempotencyAndCallbackE2ETest`：同 Idempotency-Key 重复下单、同 TXRF 重放、渠道回调重复 3 次（AC5.1~AC5.3）
- [ ] **T425** 回调异常：回调丢失 → UNKNOWN → `resolve` 收敛后**后处理不丢**（记账/权益/订单状态补齐）；回调乱序不回退终态（AC5.4~AC5.5）
- [ ] **T426** `inventory/SeckillRestockE2ETest`：秒杀 SKU 退款后库存回补，普通 SKU 不变（AC5.6）
- [ ] **T427** 结算门禁：对账差异未处理时不允许结算（AC3.3 独立用例化）
- [ ] **T428** `contract/InternalApiSnapshotTest`（L3）：内部 API 请求/响应 schema 快照（字段集合 + 类型），基线存 `src/test/resources/api-snapshots/*.json`（FR-011 / D3）

## 批次 F — 故障注入与 CI（依赖：批次 D）

- [ ] **T429** 扩展 mock-channel 为请求级确定性触发（金额尾数 / remark，见 [plan.md §5](plan.md#5-确定性-mock--故障注入扩展现有机制)），保留现有 `PAYMENT_MOCK_SCENARIO` 基���场景（FR-012）
- [ ] **T430** 扩展 `.github/workflows/verify.yml`：PR 追加 L3 快照 + ArchUnit（分钟级）（FR-014 / D4）
- [ ] **T431** 新增 `.github/workflows/e2e.yml`：nightly + workflow_dispatch + release 前强制；起 MySQL/Redis service + Nacos；起 9 服务轮询 health；跑 `-De2e.env=ci`；上传 surefire 报告与 dump 产物（FR-014 / SC-006）
- [ ] **T432** flaky 策略落地：用例不自动重试，连续 flaky 降级 `@Disabled` 并登记待办

## 批次 G — 收尾

- [ ] **T433** 测试有效性验证（SC-002/SC-003）：注入缺陷（去掉退款累计校验 / 不回填 `payment_refund_no`），对应用例必须变红且 dump 可定位
- [ ] **T434** 全量回归：`./mvnw -B verify` 既有 450 用例不受影响（SC-007）；E2E 全量本地跑通（SC-001）
- [ ] **T435** 文档收口：spec/ADR 状态推进为 Implemented；tasks 勾结；CHANGELOG；`deployment/e2e-tests/README.md` 定稿
- [ ] **T436** 评估 `deployment/demo/run-all.sh` 是否改为调 E2E 模块（阶段 1 后，非必须）

## 明确不做（负责人 2026-09-07 拍板）

- 引入 Spring Cloud Contract / Pact（D3），改用 API schema 快照。
- 改造或退役 `deployment/demo/scenario-*.sh` 演示脚本（服务现场演示）。
- 引入 k6/Gatling 等新压测框架（压测沿用 Node 脚本 + `run-stress.sh`）。
- 回调验签相关断言（ADR-0025 占位恒放行，断言会得到假绿）。
- PR 阶段跑 E2E（10 个 JVM 服务在 GH Actions 上过重）。
- 集中式日志/链路采集验证（Loki 列后续期，见 spec 021）。
