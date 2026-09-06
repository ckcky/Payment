<a id="adr-0065"></a>
<!-- 兼容旧链接：本 ADR 曾误用 ADR-0054（与 0016-core-payment-correctness.md 重号），2026-09-06 更正为 ADR-0065 -->
<a id="adr-0054"></a>

# ADR-0065: 支付编排职责归位——order-service 升为业务编排者，payment-service 退回能力提供方

- 状态：✅ **Accepted**（2026-09-06 spec 016 评审通过并落地；代码事实已与本 ADR 对齐）
- **编号更正（2026-09-06 文档治理）**：本文原标 `ADR-0054`，与 [`0016-core-payment-correctness.md`](0016-core-payment-correctness.md) 的 ADR-0054 **重号**，现更正为 **ADR-0065**（ADR-0064 之后的下一个可用号）。旧锚点 `adr-0054` 保留以兼容既有链接；新引用请一律使用 **ADR-0065**。
- 关联：ADR-0063（跨系统业务单号）、ADR-0064（一交易多支付单 / 退款域并入 payment-service）、spec 016、Constitution §7（领域边界）、Constitution §8（人类决策边界）
- 需求源头：负责人裁决「重复支付 / 超额支付的处理归属订单 / 交易编排层，用 `transaction_no + payment_no` 去发起自动退款；支付成功回调通知到 order-service 这层，再由 order-service 去通知履约和权益相关操作。order-service 内含 order 层（订单创建 / 商品 / 金额）与 transaction 层（交易动作含重复支付自动退款），order-no 与 transaction-no 一比一；payment 层负责支付流程编排（调用 payment_attempts 各渠道支付 + 记账），transaction-no 与 payment-no 一比多。保留 fulfillment→entitlement 链。」

## 背景

ADR-0064 解决了「一订单多支付单」的建模问题，但其第 4 条把**自动退款的决策与发起**放在了 payment-service：payment 通知 order 时若捕获 `OrderNotPayableException(409)`，就就地 `autoRefundGateway.autoRefund(paymentNo)`。这带来两个领域边界问题：

1. **业务权威与执行错位**：「这笔钱多收了」这一业务判定由 order-service 做出（它拥有订单可支付状态），但「发起退款」的决定却由 payment-service 做出。payment 在「通知订单」的上下文里悄悄决定了退款，把 order 的领域判断和 payment 的补偿动作耦合在跨服务异常里。
2. **order 不是编排者**：支付成功后，履约（FulfillmentGateway）、记账（ledgerGateway）由 payment-service 直接扇出（`PaymentResultProcessor.java:73/90`、`PaymentApplicationService.java:118`），order-service 的 `onPaymentSucceeded` 只改自身状态（markPaid + transaction.succeed() + confirmStock），**不驱动任何下游**。这导致 order 作为「商户订单 / 交易」聚合根，却对支付成功后的业务流程没有编排权。

当前代码事实（已核实，`be136d3`）：

| 现象 | 代码证据 |
|---|---|
| payment 直接扇出履约 | `payment-service/.../PaymentResultProcessor.java:73` `fulfillmentGateway.notifyPaymentSucceeded(...)`；`PaymentApplicationService.java:118` 同步 charge 路径同样直调 |
| payment 捕获 409 自行退款 | `PaymentResultProcessor.java:78-82` catch `OrderNotPayableException` → `autoRefundGateway.autoRefund(payment.getPaymentNo(), ex)`；`PaymentAutoRefundService.java:47` `autoRefund(String paymentNo, OrderNotPayableException cause)`，无 transactionNo |
| order 不驱动下游 | `order-service/.../OrderApplicationService.java:196-236` `onPaymentSucceeded` 仅 markPaid + transaction.succeed() + confirmStock |
| transaction 层空壳 | order-service 仅有 `OrderApplicationService`，**无** `TransactionApplicationService`；`Transaction` 仅是领域状态机 |
| 履约→权益链 | `fulfillment-service/.../application/EntitlementGateway.java:7`（履约完成后触发权益，本 ADR 保留不变） |
| **退款渠道流水号被丢弃（对账缺口）** | `PaymentRefundService.java:52-67` 调渠道退款后 `channelReference` 仅回传、不落库——`refunds` 表无渠道流水号列、无退款渠道尝试记录；`RefundFactsService.java:33` 对账退款事实用 `"refund-" + r.getId()` **合成引用**，对账退款侧无法按真实渠道退款流水号比对（demo 里 `payment_attempts` 的 `refund-*` 行是脚本回调副作用，非领域记录） |

这与业内主流对齐不佳：Stripe `PaymentIntent`（一个意图、多次 attempt/charge）、支付宝 `out_trade_no`（一单多付）都把「支付意图 / 交易」作为业务编排主体，渠道只是能力提供方；超额 / 重复支付的补偿归**商户订单 / 交易编排层**决策，支付提供方仅暴露 `refund` 命令。

## 决策

1. **order-service 升为业务编排者，内部分两层**：
   - **order 层**（`OrderApplicationService`）：订单创建、SKU / 价格校验、价格快照、订单状态机（pending→paid→fulfilling→completed）、订单金额（已付 / 已退）追踪；**以及支付成功后的订单侧动作**——markPaid + transaction.succeed() + confirmStock + **驱动履约**（`FulfillmentGateway.notifyPaymentSucceeded`，transaction 层判定「正常到账」后委派执行）。负责人 2026-09-06 明确：**confirmStock 与履约驱动属 order 层，不在 transaction 层**。`order_no ↔ transaction_no` **1:1**。
   - **transaction 层**（**新增** `TransactionApplicationService`）：交易动作编排。接收支付成功通知，**主动判定**本支付单是「正常到账」还是「重复 / 超额（surplus）」，并据此：
     - 正常：**委派 order 层**执行「markPaid + transaction.succeed() + confirmStock + 驱动履约」；权益由既有 `fulfillment → entitlement` 链授予（**保留，不改**）；
     - surplus：记录多收，**以 `transactionNo + paymentNo` 经 `PaymentGateway.refund(...)` 发起自动退款**（属 transaction 层职责）。
   - `transaction_no : payment_no = 1:N`（沿用 ADR-0064 的一交易多支付单建模；transaction 层拥有「本交易是否已 PAID」权威状态）。

2. **payment-service 退回能力提供方**：
   - 支付流程编排：渠道支付（`payment_attempts`；基数：`transaction_no : payment_no = 1:N`，**`payment_no : payment_attempts = 1:1`**——每张支付单仅一条渠道尝试记录，渠道重试在同一 attempt 行内 `retry_count` 递增、不新建行；代码证据 `PaymentPersistence.java:56`、`PaymentAttempt.java:182`）、**记账**（`ledgerGateway.postPaymentCapture`，属 payment 层支付指令编排的一部分，**保留在 payment 内**，负责人 2026-09-06 明确）；
   - 支付成功回调 → payment 层**编排支付指令**：完成自身支付动作——渠道支付落 `payment_attempts` + **记账**（`ledgerGateway.postPaymentCapture`，属支付指令编排的一部分，负责人 2026-09-06 明确记账留在 payment 编排内）；随后**业务侧扇出仅 order-service**（`orderGateway.notifyPaymentSucceeded`），**移除** `PaymentResultProcessor` 中的 `fulfillmentGateway` 直调（`:73`）与 `autoRefundGateway` 自发起（`:82`），以及 `PaymentApplicationService.java:118` 的同步 charge 路径扇出——**记账不在移除之列**；
   - 暴露退款**命令**（执行者）：`PaymentGateway.refund(transactionNo, paymentNo, ...)`，由 order 调用；执行落入既有 `PaymentRefundService`（退款域已并入 payment-service，ADR-0064 #5）。

3. **自动退款触发语义变更（取代 ADR-0064 #4）**：
   - 旧：`payment 通知 order → order 抛 409 → payment catch → autoRefund(paymentNo)`；
   - 新：`payment 通知 order → order transaction 层主动判定 surplus（order 拥有自身状态，最权威）→ 在 order 内调用 paymentGateway.refund(transactionNo, paymentNo)`。**不再依赖跨服务 409 异常传递**；order 把「多收」当正常业务分支处理。
   - 退款请求 `RefundAttemptRequest` **新增 `transactionNo`**（业务上下文）；幂等键由 `autorefund:{paymentNo}` 改为 `autorefund:{transactionNo}:{paymentNo}`（保留 paymentNo 维度，加 transactionNo 用于审计 / 关联）。
   - **退款编排三步链（负责人 2026-09-06 明确）**：transaction 层发起退款后，payment 退款域按三步执行——
     1. 调用退款接口（`/internal/refunds`）**生成退款单号 `refundNo`**（RF+雪花；幂等键回查 + intake lock + 防超退校验不变，`RefundApplicationService.createRefund`）；
     2. **生成退款渠道尝试记录**（对称支付侧 `payment 1:1 payment_attempts`：复用渠道交互记录 `payment_attempts` 落一条退款尝试——`payment_no` 关联 + `channel_reference` = 渠道退款流水号，唯一约束兜底），**修复「渠道退款流水号被丢弃」缺口**；
     3. 调用外部渠道执行退款（`channel.refund`，SUCCEEDED/FAILED/UNKNOWN 三态收敛退款状态机）。
     成功后既有后处理编排（履约撤销 → 权益吊销 → 记账冲正）不变。对账退款事实（`RefundFactsService`）改用**真实渠道退款流水号**（自退款尝试记录取），废弃 `"refund-{id}"` 合成引用。

4. **契约与数据归属（沿用 ADR-0063 业务单号纪律）**：
   - `PaymentSucceededRequest`（payment→order）**新增 `transactionNo`**（payment 侧已知其所属 transaction）；
   - order→payment `RefundAttemptRequest` 新增 `transactionNo`；
   - 跨服务一律业务单号（orderNo / transactionNo / paymentNo / refundNo），不暴露数值 ID。

5. **明确不动的部分**：
   - `fulfillment → entitlement` 链**保留**（负责人明确）；
   - 记账（ledger postPaymentCapture）**保留在 payment-service**（属支付能力，不归 order 编排）；
   - 对账 / 结算链路**闸门语义不受影响**（其引用仍是渠道流水号 `out_channel_no`）；但**退款事实的渠道引用从合成 `"refund-{id}"` 修正为真实渠道退款流水号**（退款尝试记录落库后自然获得，属缺口修复而非语义变更）；
   - transaction 与 payment 的 1:N 建模已由 ADR-0064 建立，本 ADR 不重复，只明确**归属与编排方向**。

## 后果

- **领域边界清晰**：order 拥有业务流程（支付成功后的履约 / 权益 / 退款决策），payment 仅提供支付 / 退款 / 记账能力。符合 Constitution §7 服务边界，也与业内 PaymentIntent / out_trade_no 范式对齐。
- **业务权威回归 order**：surplus 判定不再靠跨服务 409 异常，由 order transaction 层基于自身状态直接决定，链路更短、语义更直白。
- **payment-service 瘦身**：移除 `FulfillmentGateway` / `AutoRefundGateway` 两个出站依赖与包耦合（其接口 / 实现可降级或删除）。
- **测试视角迁移**：既有 `PaymentCallbackConflictScenarioTest`（payment catch 409→autoRefund）需迁到 order 视角（`TransactionCallbackConflictTest`：第二笔支付到账 → order 判定 surplus → 调 refund → 退款 SUCCEEDED）。
- **文档漂移需同步收口**：`docs/specs/001-core-business-model/data-model.md` 仍写「Transaction 1:1 Payment」，须更新为 1:N（对齐 ADR-0064 现实）；本 ADR **Supersedes ADR-0064 §决策#4**（自动退款归属），ADR-0064 其余条款（一交易多支付单、退款域并入、三渠道 mock）保持不变。

## 验收（与 spec 016 对齐）

见 `docs/specs/016-order-payment-orchestration/spec.md`、`plan.md`（SC-001~006 逐条对照 + `mvn -o clean verify -fae` 门禁 + 端到端「重复支付 → order 判定 surplus → 自动退款 SUCCEEDED」演示）。
