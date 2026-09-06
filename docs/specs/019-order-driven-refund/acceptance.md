# Acceptance: 019-order-driven-refund

> 验收执行方式与 DoD。状态：✅ Accepted（验收标准随 spec 019 拍板；执行待代码实施）。

## 验收执行方式

1. **自动化门禁**：`mvn -o clean verify -fae` 全绿。
2. **迁移脚本验证**：存量库执行 `deployment/schema/019-order-driven-refund.sql` 两遍均成功（幂等）。
3. **demo live 冒烟**：起全栈 → portal → ① 手工退款（`POST /internal/orders/refund`）② surplus 自动退款（同订单二次支付）→ ④ 全链路 DB 数据逐项核对（见用例矩阵）。
4. **逆向联动核查**：履约/权益/库存/账务四落点逐项肉眼可见。

## DoD 检查表

- [ ] transaction_refunds 表就位：多次退款多个 TXRF 逐行记录，1 transaction : N（AC1.1）
- [ ] TXRF ↔ PMRF 双向互记成对出现（transaction_refunds.payment_refund_no / refunds.transaction_refund_no）（AC2.1）
- [ ] transactions 表有 payment_no（生效支付单，不随 surplus 覆盖）+ refunded_minor 正确累加（AC3.x）
- [ ] refunds 新单 PMRF+雪花；存量 RF 保留；同 TXRF 重试回放同一执行单不重复扣款（FR-003/004）
- [ ] 渠道退款异步回调端点就位；同步/异步/resolve 三路收敛到同一后处理（AC4.1 / FR-006）
- [ ] 回调通知 order 后：交易退款单终态 → 订单状态流转（PARTIALLY_REFUNDED/REFUNDED）→ 秒杀回补 → 履约终止 → 权益撤销 全链联动（AC4.2/AC5.x）
- [ ] payment 直调创建入口下线；演示脚本走 order 入口（FR-008）
- [ ] 记账幂等键单一前缀 `REFUND:{PMRF}`；surplus 退款失败有 WARN+指标痕迹（FR-009）
- [ ] 回调重放幂等；普通商品不触发库存回补（AC4.3/AC5.2）
- [ ] `mvn -o clean verify -fae` 全绿（SC-001）

## 用例矩阵

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| TC-01 | 单测全绿 | `mvn -o clean verify -fae` | BUILD SUCCESS |
| TC-02 | 手工退款全链路 | order 入口发起部分退款 | TXRF 落单（REQUESTED→PROCESSING→SUCCEEDED）；payment 落 PMRF；两单互记；异步回调后终态一致 |
| TC-03 | surplus 自动退款 | 同订单第二笔支付成功 | 自动生成 TXRF 退款单 → 渠道退款 → 双层单闭环；transactions.payment_no 仍是首张生效支付单 |
| TC-04 | 多次部分退款 | 同一支付连续两次部分退款 | 两个 TXRF + 两个 PMRF 一一对应；refunded_minor 累加正确 |
| TC-05 | 超退拦截（payment 权威层） | 请求累计超过实付 | REJECTED（RefundPolicy）；transaction_refunds 记 REJECTED |
| TC-06 | 超退拦截（order 第二道） | 逻辑漏洞导致上层多发 | Order.recordRefund() 校验拦截 |
| TC-07 | 重试可重入 | 同 TXRF 重复发起 | 回放原执行单结果，不产生新单不重复扣款 |
| TC-08 | 回调重放幂等 | 同 PMRF 回调重发 | 状态不重复推进、账务不重复冲正 |
| TC-09 | 秒杀回补 | 秒杀订单退款 | 秒杀库存回补（幂等键 refund:{TXRF}:sku:{skuId}） |
| TC-10 | 普通商品不回补 | 普通订单退款 | 库存表无变化 |
| TC-11 | 履约终止 + 权益撤销 | PENDING 履约存在时退款成功 | PENDING 履约 → CANCELLED；AVAILABLE 权益 → REVOKED |
| TC-12 | 账务冲正 | 退款成功 | ledger 冲正分录（借 MERCHANT_PAYABLE / 贷 CUSTOMER_CASH），幂等键 REFUND:{PMRF} |
| TC-13 | 直调入口下线 | curl `POST /internal/refunds` | 404/405；resolve 端点仍可用 |
| TC-14 | 演示脚本 | `scenario-refund.sh` 全流程 | 改走 order 入口后断言全过 |
| TC-15 | 三路一致性 | 同一退款分别经同步成功/异步回调/resolve 收敛 | 后处理结果完全一致（编排器统一） |
