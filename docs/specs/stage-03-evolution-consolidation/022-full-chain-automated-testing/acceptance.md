# Acceptance: 022-full-chain-automated-testing

> 验收执行方式与 DoD。状态：✅ Accepted → **待实施**（验收标准随 spec 022 拍板；执行待代码落地，本次仅文档）。

## 验收执行方式

1. **一键全量（SC-001）**：`bash deployment/start-all.sh` → `bash deployment/e2e-tests/run.sh`，P0 四组用例全绿并产出 surefire 报告。
2. **测试有效性反证（SC-002/SC-003）**：故意注入缺陷（去掉退款累计校验 / 不回填 `payment_refund_no`），对应用例必须变红，`target/e2e-dump/<case>/` 可定位。
3. **单条可重跑（SC-005）**：`mvn -pl deployment/e2e-tests test -Dtest=OverRefundGuardE2ETest` 通过。
4. **既有回归不破（SC-007）**：`./mvnw -B verify` 既有 ~450 个单服务测试全绿。
5. **nightly 干净环境（SC-006）**：GitHub Actions 上 compose 起栈跑通，上传报告与 dump 产物。
6. **对账验收必须走 LIVE**：`audit.html` 的 MOCK 模式纯前端执行，不得作为验收依据（陷阱 T2）。

## DoD 检查表

- [ ] 模块 `deployment/e2e-tests` 可独立运行，业务代码零改动（FR-001 / NFR-006）
- [ ] `Env` 支持 `local`（默认）/ `ci` 两种环境（FR-002）
- [ ] 异步等待一律 Awaitility 轮询，代码中无 `Thread.sleep` 等待（FR-005 / NFR-004）
- [ ] 行级断言复用 `/demo/trace`；聚合不变量直连 MySQL，两类分工清晰（D5）
- [ ] `Invariants` 十类原语齐备（退款不超付 / 单号链 / 账本平衡 / 状态 / 权益 / 履约 / 对账差异 / 结算门禁 / 库存回补 / 幂等重放 / 无孤儿）（FR-006）
- [ ] 退款主链：六库状态与金额收敛，TXRF/PMRF 双号就位，后效（履约终止 + 权益撤销 + 账本冲正）齐全（AC1.1~AC1.5）
- [ ] 超退：单次 / 累计 / 并发三类均被拦，DB 侧 `refunded_minor ≤ paid_minor` 恒真（AC2.1~AC2.3）
- [ ] 对账：CLEAN 0 差异、F2~F9 八类全检出且分类正确、调账后清零、close 门禁 400、试算平衡 Σ=0 且 SUSPENSE 归零（AC3.1~AC3.3 / AC3.5）
- [ ] 渠道对账：注入 5 类差异检出率 100%（AC3.4）
- [ ] 单号：前缀 + 雪花唯一 + 双号互记 + attempt 归属 + 无孤儿单（AC4.1~AC4.5）
- [ ] P1：幂等重放、回调重复/乱序/丢失 + resolve 后处理不丢、秒杀回补、结算门禁（AC5.1~AC5.6）
- [ ] 失败自动产出 dump 包，断言消息含期望/实际/单号/库表（FR-007 / NFR-003）
- [ ] 数据隔离：唯一前缀 + 按单号过滤，用例可乱序/单条重跑（FR-013 / NFR-002）
- [ ] CI：PR 快层（单元 + 集成 + 快照 + ArchUnit），nightly E2E（FR-014 / D4）

## 用例矩阵

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| TC-01 | 一键全量 | `bash deployment/e2e-tests/run.sh` | P0 四组全绿，`target/surefire-reports/` 有报告 |
| TC-02 | 部分退款主链 | 下单 → 建支付单 → 回调 SUCCESS → 发起部分退款 → 轮询 | 订单 PARTIALLY_REFUNDED；`transactions.refunded_minor` == `refunds` 累计；权益撤销；履约终止；账本按 `REFUND/{pmrf}` 借贷平衡 |
| TC-03 | 全额退款主链 | 退全额 | 订单 REFUNDED；`refunded_minor == paid_minor`；六库一致 |
| TC-04 | 单次超退 | 退款额 > 实付额 | HTTP 409 + 明确错误码；DB 无新增退款行 |
| TC-05 | 累计超退 | 多笔部分退款至第 N 笔超额 | 第 N 笔 409；`refundNotExceedPaid` 恒真 |
| TC-06 | 并发退款竞态 | 并发两笔（总额超付） | 成功总额恰等于可退额；失败笔被拒；DB 侧不变量恒真（验证 intake 锁） |
| TC-07 | 会计四核对 CLEAN | LIVE 触发 `scope=ALL`（F1 平账） | 0 差异；可直接 close |
| TC-08 | 会计四核对 FAULT | LIVE 触发（F2~F9） | 8 类差异全检出，kind/severity 分类与预期一致 |
| TC-09 | 挂账→调账→recheck→close | 处理 F2~F9 差异 | 差异清零；试算平衡 Σ=0；SUSPENSE 归零；有未收口差异时 close 返回 400 |
| TC-10 | 渠道对账注入 | 新增 period CSV 注入 5 类差异 | 检出率 100%，分类正确 |
| TC-11 | 结算门禁 | 存在未收口差异时发起结算 | 4xx 拒绝 |
| TC-12 | 单号前缀与唯一性 | 全链路跑完后扫描各库本轮单号 | OR/TR/PM/PA/TXRF/PMRF 前缀正确、雪花无重复 |
| TC-13 | 双号互记 | 退款后交叉比对两库 | `transaction_refunds.payment_refund_no == refunds.refund_no`；`refunds.transaction_refund_no == transaction_refunds.refund_no` |
| TC-14 | attempt 归属 | 查 `payment_attempts` | 归属正确 `payment_no`；退款 attempt 的 `attempt_type='REFUND'` |
| TC-15 | 无孤儿单 | 按本轮前缀扫描上下游 | 所有单号均可追溯，无悬空引用 |
| TC-16 | 幂等-下单 | 同 `Idempotency-Key` 重复下单 | 恰好 1 笔订单（行数不变、金额不变） |
| TC-17 | 幂等-退款 | 同 TXRF 重放 | 返回同一 PMRF，不新增退款行 |
| TC-18 | 回调重复 | 渠道回调投递 3 次 | 状态与金额不变（幂等吸收） |
| TC-19 | 回调丢失 | 渠道受理不发回调 → UNKNOWN | `resolve` 收敛后后处理不丢：记账/权益/订单状态补齐（与同步成功一致） |
| TC-20 | 回调乱序 | SUCCESS 先到、PROCESSING 后到 | 终态不被回退 |
| TC-21 | 秒杀回补 | 秒杀 SKU 退款 | 配额回补；普通 SKU 库存不变 |
| TC-22 | 单条重跑 | `-Dtest=OverRefundGuardE2ETest` | 独立通过（不依赖其他用例） |
| TC-23 | 反证-超退校验 | 临时去掉退款累计校验 | TC-05/TC-06 必须变红，dump 可定位 |
| TC-24 | 反证-双号互记 | 临时不回填 `payment_refund_no` | TC-13 必须变红 |
| TC-25 | 既有回归 | `./mvnw -B verify` | 既有 ~450 用例全绿 |

## 明确不纳入验收

- 回调验签（ADR-0025 占位恒放行，断言会得到假绿）。
- audit 控制台 MOCK 模式（纯前端，不代表系统行为）。
- 压测指标（沿用 `deployment/performance/` 与 `run-stress.sh`，不在本 spec 范围）。
