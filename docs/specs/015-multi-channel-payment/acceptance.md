# Acceptance: 015-multi-channel-payment

> 验收对照 `spec.md` 的 SC-001~007。每条需实测通过并记录证据（curl 响应 / 断言 / 指标）。

## SC-001 全量构建门禁
- [ ] `mvn -o clean verify -fae` BUILD SUCCESS
- [ ] `architecture-tests` 服务边界门禁通过，服务数 **10 → 9**（refund 已移除）

## SC-002 主链路（INV-1 订单层扣库存 + INV-2 换渠道新单旧单 FAILED）
**步骤与断言**：
1. `POST :8083/orders` → 响应 `paymentId == null`、`paymentStatus == null`、`payUrl == null`
2. `POST :8083/orders/{id}/payments` `{"channelCode":"WECHAT"}` → 返回 `paymentId / attemptSeq=1 / channelCode=WECHAT / status=PENDING`
3. `POST :8091/mock-channel/callback` 模拟 WECHAT **FAILURE** → 支付单 FAILED；订单仍 `PENDING_PAYMENT`、交易仍 `PROCESSING`（不动）
4. `POST :8083/orders/{id}/payments` `{"channelCode":"ALIPAY"}` → 返回新 `paymentId / attemptSeq=2 / channelCode=ALIPAY`；旧 WECHAT 支付单仍 `FAILED`，**未调用 `Payment.close()`**
5. `POST :8091/mock-channel/callback` 模拟 ALIPAY **SUCCESS** →
   - 订单 `PAID`、交易 `SUCCEEDED`
   - 库存确认扣减（`catalog` 侧扣减，由 order-service `confirmStock` 发起，INV-1）
   - fulfillment 生成、entitlement 生成
   - ledger `/internal/ledger/balance` `balanced == true`，按 `PAYMENT/{paymentId}` 能查到分录

## SC-003 重复成功自动退款（FR-013）
1. 同一交易创建两张支付单（ALIPAY / DOUYIN）均回调 SUCCESS
2. 第一张：订单 PAID、交易 SUCCEEDED（正常）
3. 第二张：支付单 **保持 SUCCEEDED**，**自动退款** → `refunds` 表产生 `SUCCEEDED` 退款
4. 账本反向记账平衡（不产生重复履约，fulfillment 数量仍为 1）

## SC-004 已关闭订单自动退款（FR-013）
1. 订单 15 分钟超时 `CANCELLED`（演示可缩短超时或直连超时调度）
2. 超时后渠道回调 SUCCESS → 自动退款，支付单保持 `SUCCEEDED`，**不产生履约**

## SC-005 旧场景脚本在 8084 跑通
- [ ] `demo/scenario-refund.sh`、`demo/scenario-reconciliation.sh` 在 refund 并入 payment（8084）后断言通过

## SC-006 流量脚本实测
- [ ] `bash -n deployment/demo/traffic-gen.sh` 通过
- [ ] 实测 ~2 TPS（默认），成功率接近配置值
- [ ] UNKNOWN 全部收敛（延迟 2s 后 resolve 裁定 FAILURE → 换渠道再付）
- [ ] 运行 10 分钟无 5xx、无库存耗尽中断
- [ ] JSONL 汇总与滚动统计正常产出

## SC-007 脚本语法
- [ ] `bash -n deployment/demo/traffic-gen.sh` 与 `bash -n deployment/demo/stop-traffic.sh` 均通过

## 验收报告需记录
- 主链路 / 重复成功 / 已关闭订单 三场景的 curl 流水与关键断言
- `mvn` 全量门禁结果截图/文本
- 流量脚本 2 TPS 实测汇总
- 已知限制（L1~L5）在实测中的表现
