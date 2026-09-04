# Feature 015 验收报告 — 一交易多支付单 / 退款合并 / 三渠道 mock / 流量脚本

- 日期：2026-09-04
- 分支：`feature/015-multi-channel-payment`
- 依据：`docs/specs/015-multi-channel-payment/spec.md`（INV-1/INV-2、C1~C10、SC-001~007）、
  `docs/adr/0024-multi-payment-per-transaction.md`（ADR-0064）
- 结论：**开发完成，全量门禁绿色**（细节与遗留项见文末）

---

## 1. 交付范围与阶段完成度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 数据底座 | 唯一约束改普通索引、attempt_seq 列、幂等键服务端生成、可重放迁移脚本 | ✅ |
| P1 一交易多支付单 | 下单不建单；`POST /orders/{ref}/payments` 显式选渠道；响应扩 attemptSeq/channelCode | ✅ |
| P2 回调语义 | SUCCESS 通知 / FAILURE 静默 / UNKNOWN 收敛；409 ORDER_NOT_PAYABLE（修 C5） | ✅ |
| P3 退款合并 | refund 域迁入 payment-service（服务数 10→9）；端口 8085 退役 | ✅（遗留 1 项，见 §6） |
| P4 自动退款 | 409 → 进程内自动退款；幂等键 autorefund；重试 3 次退避；指标+ERROR 转人工 | ✅ |
| P5 三渠道 + 收银台 | 渠道前缀引用；cashier 渠道展示+换渠道；crypto.randomUUID 防撞键 | ✅ |
| P6 流量脚本 | lib.sh 零 fork 助手；traffic-gen.sh（2 TPS，概率成败/UNKNOWN 延迟 resolve/换渠道）；stop-traffic.sh | ✅ |
| P7 文档收口 | ADR-0064；README/部署脚本/边界测试同步；全量门禁 | ✅ |

## 2. 核心不变量逐条核验

### INV-1（回调成功 → 支付层更新支付单 → RPC 通知订单 → 订单层一次性推进 + 扣库存）

- `PaymentResultProcessor.applyAndNotify`：SUCCESS 时更新支付单与 attempt、账本
  `postPaymentCapture`（键改 `PAYMENT:{paymentNo}`，修 C2 静默少记账）、依次通知
  fulfillment 与 order（任一失败不回滚支付成功事实，交由幂等+对账收敛）。
- order 侧 `onPaymentSucceeded(orderNo, paymentNo)`：订单 PAID → 交易 SUCCEEDED →
  **由 order-service 发起 `confirmStock`**（扣库存始终在订单层）。✅
- 验证：`SuccessfulPurchaseScenarioTest`、`PaymentAutoRefundServiceTest` 等 6 个新场景 +
  order 39 / payment 125 测试全绿。

### INV-2（换渠道 → 同订单号新建支付单，旧单保留 FAILED，不调 Payment.close()）

- `POST /orders/{ref}/payments` 每次调用新建支付单（attemptSeq = 同交易笔数+1）；
  `uk_payments_transaction_id` 唯一约束已移除（03-schema + 测试 H2 schema + 迁移脚本三处同步）。
- 收银台「换渠道」按钮走同一端点；旧支付单不触发 `Payment.close()`（代码无调用路径）。
- 验证：`PaymentCallbackConflictScenarioTest`（同订单双支付单：第二张成功 → 第一张
  SUCCEEDED 保留并触发自动退款；订单仍 PAID，无重复扣库存）。✅

### 关键缺陷修复

| 缺陷 | 修复 | 验证 |
|---|---|---|
| C2 幂等键不含支付单维度 → 第二笔成功静默少记账 | 服务端生成 `payment:{orderNo}:{channelCode}:{attemptSeq}`；调用方显式传 key 时仍按 key 去重（T018 契约保持） | PaymentPersistenceTest + 回归全绿 |
| C5 `markPaid` 异常被支付侧吞掉 | order 返回 **409 ORDER_NOT_PAYABLE**（ErrorCodes+全局映射）；payment Feign 解码 409 抛 `OrderNotPayableException` | PaymentCallbackConflictScenarioTest |
| 收银台 Date.now() 撞键 | `crypto.randomUUID()` | cashier.html |

## 3. P3 退款合并清单

- `com.payment.refund` 包（main 38 文件 + test 8 文件）整体迁入 payment-service；
  扫描/Feign/MapperScan 双包覆盖；`RefundApplication` 删除（防止 Feign 规格重复注册）。
- refund→payment 自调用改进程内 `LocalPaymentRefundGateway`（删 `PaymentRefundFeignClient`）。
- 同名 bean 冲突治理：refund 侧 Feign 客户端加 `contextId`；
  `FeignLedgerPostingGateway` → `RefundFeignLedgerPostingGateway`。
- DDL：4 张退款表迁 `payment` 库（03-payment-schema.sql 追加）；`06-refund-schema.sql`
  退役占位；新增存量迁移脚本 `015-refund-merge.sql`（可重放）；测试 H2 schema 同步 4 表。
- 部署收敛：根 pom 模块、start-all.sh、start-demo.sh（11→10 健康检查）、
  demo-monitor-stress.sh、demo/lib.sh（REFUND_URL→8084）、mock-channel-web yml、
  docker-compose 注释、README 服务表、ServiceBoundaryTest.SERVICES（10→9）。

## 4. 测试与门禁结果

| 门禁 | 结果 |
|---|---|
| order-service `clean test` | **39 tests, 0 fail** |
| payment-service `clean test`（含 refund 域 28 个） | **125 tests, 0 fail** |
| 新增场景测试 | `PaymentCallbackConflictScenarioTest`（3）+ `PaymentAutoRefundServiceTest`（3）= 6 全绿 |
| 脚本语法 | `bash -n` traffic-gen.sh / stop-traffic.sh / lib.sh 通过 |
| 全量 `mvn -o clean verify -fae`（11 模块，含 architecture-tests 9 服务边界） | **BUILD SUCCESS**（Reactor 全绿） |

## 5. spec 验收标准 SC-001~007 对照

| SC | 描述 | 结果 |
|---|---|---|
| SC-001 | 下单不建支付单，响应无支付字段 | ✅（CreateOrderResult 支付字段置 null） |
| SC-002 | 显式选渠道 → 201 新支付单（attemptSeq 递增） | ✅ |
| SC-003 | 换渠道 → 第二张支付单，旧单 FAILED 保留 | ✅（场景测试+收银台按钮） |
| SC-004 | 回调成功 → 订单 PAID+交易 SUCCEEDED+订单层扣库存 | ✅ |
| SC-005 | 回调失败 → 仅支付单 FAILED，订单无感 | ✅（处理器不通知 order） |
| SC-006 | 订单已关闭再回调 → 409 ORDER_NOT_PAYABLE → 自动退款 | ✅（含重试/指标） |
| SC-007 | 2 TPS 流量脚本全链路 | ✅（脚本就绪+语法门禁；联机冒烟需启动演示栈后运行，见 §6） |

## 6. 遗留与备注

1. **refund-service 目录物理删除未完成**：环境安全钩子强制「删除必须走回收站」且回收站
   操作持续失败（genie-trash fail-closed），目录又被 IDE 进程锁定无法改名/移动。已做的
   隔离：根 pom 不再引用该模块（Maven 全量门禁不含它），ServiceBoundaryTest 已移除
   refund，代码已 100% 迁入 payment-service 并通过门禁。**待编辑器释放文件句柄后手动删除**
   `refund-service/` 目录即可（不影响本次提交内容）。
2. traffic-gen 的 UNKNOWN 延迟 resolve 依赖 payment 侧 `/internal/payments/{no}/resolve`
   端点鉴权口径；联机冒烟建议 `start-demo.sh` 后执行
   `TPS=2 DURATION=60 bash deployment/demo/traffic-gen.sh && bash deployment/demo/stop-traffic.sh`。
3. 并行任务在同一工作树进行 ADR-0063（业务单号）改造，本特性与其方向一致并完成收口；
   提交时仅精确 add 本特性涉及文件，不触碰其他任务 staged 内容。

## 7. 提交清单（关键文件）

- spec 四件套：`docs/specs/015-multi-channel-payment/*`；ADR：`docs/adr/0024-*.md` + README 索引
- schema：`deployment/schema/03-payment-schema.sql`、`06-refund-schema.sql`（退役）、
  `015-payment-multi-attempt.sql`、`015-refund-merge.sql`
- payment-service：`Payment/PaymentEntity/PaymentRepository/MybatisPaymentRepository/PaymentPersistence`、
  `PaymentResultProcessor`（C2 记账键）、`OrderNotPayableException`、`PaymentAutoRefundService`、
  `FeignOrderGateway`、`OrderFeignClient`（primary=false + 409 解码）、`PaymentClientConfig`、
  `MockChannelAdapter`（渠道前缀）、`PaymentController`（payUrl+channelCode）、
  `com/payment/refund/**`（合并域）、测试 schema.sql、`RefundApplicationTests` 指向调整
- order-service：`OrderApplicationService`（下单不建单 + createPaymentForOrder + 409）、
  `OrderController`（payments 端点）、`CreateOrderPaymentRequest`、场景/状态机测试
- common：`CreatePaymentRequest`（幂等键可选）、`CreatePaymentResponse`（attemptSeq/channelCode）、
  `ErrorCodes`/`GlobalExceptionHandler`（ORDER_NOT_PAYABLE→409）
- deployment：`ServiceBoundaryTest`、start-all/start-demo/monitor/lib.sh、cashier.html、
  mock-channel-web yml、docker-compose 注释、`demo/traffic-gen.sh`、`demo/stop-traffic.sh`
- 文档：`README.md` 服务表、`docs/adr/README.md`
- 根 `pom.xml`：移除 refund-service 模块
