# 041-payment-service-governance — 迁移映射（Migration Map）

> **T03 交付物**：「旧包 / 类 / API / DTO / 配置 / Schema / 调用方 → 新目标」的逐项映射。
> 依赖：T01 / T02。追溯：FR-013 / FR-015。验收：映射覆盖生产、测试、脚本与文档。
> **基线**：master `5ce4853`（2026-09-26）。实测规模：payment-service 187 生产 java / 18,493 行；78 测试 java / 12,351 行。

⚠️ 本表是**实施依据**，不是设计讨论。凡标注「**待定**」的项，**MUST 先补齐目标再动代码**——
本 Feature 禁止新旧 API 混跑，任何没有明确目标的端点都会变成无法收敛的残留。

---

## 1. 包与类映射

### 1.1 顶层包（FR-001）

| 现状包 | 生产/测试文件 | 目标 | 动作 |
|---|---|---|---|
| `com.payment.payment.{api,application,domain,infra}` | 111 / 51 | `com.payment.payment.{api,application,domain,infra}` | **保留**（四层已成立，只清旁路） |
| `com.payment.channelgateway.{api,application,domain,infra,web}` | 63 / 26 | `com.payment.channel.{api,application,domain,infra}` | **改名**（不是新建）；`web/` 收进 `api` 或 `infra` |
| `com.payment.posting.{api,application,domain,infra}` | 13 / 1 | `com.payment.payment.*`（PendingPosting 归 Payment） | **并入并删包** |

### 1.2 旁路包（FR-001「不得保留历史散落包」）

| 现状包 | 目标 | 说明 |
|---|---|---|
| `payment/limit/web`（`LimitController`） | `payment/api` | Controller 只做 HTTP，限额 application 入口落 `payment/application` |
| `payment/mq` | `payment/application` + `payment/infra` | 消费侧编排进 application，通道实现进 infra |
| `payment/web`（`WebConfig` / Filter 等） | `payment/api` 或 `payment/infra` | 按性质分：CORS/Interceptor → api；Filter → infra |
| `channelgateway/web`（`ChannelCallbackSignatureFilter`） | `channel/api` 或 `channel/infra` | 同上 |

### 1.3 插件目录（FR-007；目标 `channel/infra/plugins/<vendor>/`）

| 渠道 | 现状落点 | 完整度 | 待迁入 |
|---|---|---|---|
| alipay | `channelgateway/infra/alipay/`（`AlipayGateway`、`AlipaySdkGateway`）+ **目录外** `infra/AlipayChannelAdapter.java`、`infra/config/AlipaySandboxProperties.java` | ⚠️ **半成品** | 把 Adapter 与 Sandbox 配置移入 `plugins/alipay/` |
| wechat | `channelgateway/infra/wechat/`（Plugin / Factory / Gateway / Properties / Signer / SdkGateway） | ✅ **完整** | 整体迁至 `plugins/wechat/` |
| stripe | `channelgateway/infra/stripe/`（Plugin / Factory / Gateway / SandboxProperties / SdkGateway） | ✅ **完整** | 整体迁至 `plugins/stripe/` |
| douyin | **目录外** `infra/DouyinChannelAdapter.java` | ❌ **未成型** | 建 `plugins/douyin/`（并补 Factory/Plugin 形态） |
| mock | **目录外** `infra/MockChannelAdapter.java`、`infra/AbstractMockChannelAdapter.java`、`infra/config/MockCashierProperties.java` | ❌ **未成型** | 建 `plugins/mock/` |

### 1.4 领域模型（保留语义，只改归属）

| 现状 | 目标 | 说明 |
|---|---|---|
| `channelgateway/domain/ChannelOrder`、`ChannelOrderStatus`、`ChannelOrderErrorType`、`ChannelOrderRepository` | `channel/domain/*` | 旧 041 `57e12c3` 已由 `PaymentAttempt` 正名，**类名与表名 `channel_orders` 保留**，只迁包 |
| `posting/domain/*`（PendingPosting） | `payment/domain/*` | 表 `pending_postings` 归 Payment |
| `payment/domain/*`（Payment / Refund） | 不变 | **状态枚举与合法迁移一律不变**（spec §8） |
| `payment/limit/*` | `payment/domain/*` + `payment/application/*` | 表 `user_payment_limits` / `user_limit_usage` / `limit_operations` 归 Payment |

---

## 2. Controller 与端点映射

### 2.1 12 个 Controller → 目标归属

| # | 现状 Controller | 现状包 | 目标包 | 目标 Controller |
|---|---|---|---|---|
| 1 | `PaymentController` | `payment/api` | `payment/api` | `PaymentController`（保留） |
| 2 | `PaymentRefundCommandController` | `payment/api` | `payment/api` | **并入** `RefundController` |
| 3 | `RefundController` | `payment/api` | `payment/api` | `RefundController`（保留并吸收 #2 #4） |
| 4 | `RefundRpcController` | `payment/api` | `payment/api` | **并入** `RefundController` |
| 5 | `ReconciliationFactsController` | `payment/api` | `payment/api` | `FactsController`（支付 + 退款事实合一） |
| 6 | `UnknownQueueController` | `payment/api` | `payment/api` | **并入** `PaymentController` 或独立运维 Controller |
| 7 | `ChannelAdminController` | `channelgateway/api` | `channel/api` | `ChannelAdminController`（保留） |
| 8 | `ChannelOrderController` | `channelgateway/api` | `channel/api` | `ChannelOrderController`（保留） |
| 9 | `ChannelPluginCallbackController` | `channelgateway/api` | `channel/api` | **唯一回调入口** `ChannelCallbackController` |
| 10 | `ChannelCallbackController` | `channelgateway/api` | — | **删除**（旧支付回调面，被 #9 取代） |
| 11 | `LimitController` | `payment/limit/web` | `payment/api` | `LimitController`（迁出旁路包） |
| 12 | `PendingPostingAdminController` | `posting/api` | `payment/api` | `PendingPostingAdminController`（并入 Payment） |

### 2.2 端点路径映射

#### A. spec `plan.md §3` 已给出目标（4 组路径变更）

| # | 现状端点 | 目标端点 | 调用方（需同步） |
|---|---|---|---|
| 1 | `POST /payments/pay`（旧 `POST /payments`） | `POST /api/payments` | `order-service/PaymentFeignClient`、`demo/*.sh`、`demo.html`、`e2e-tests/Api.java`、k6 压测、runbook、CHANGELOG |
| 2 | `GET /payments?paymentNo=&transactionId=` | `GET /api/payments/{paymentNo}` + `GET /api/payments?transactionNo=` | demo、运维、runbook |
| 3 | `POST /payments/{paymentNo}/resolve` | `POST /api/payments/{paymentNo}/resolve` | demo、运维 |
| 4 | `POST /internal/payments/refunds`、`GET /internal/payments/refunds/{refundNo}`、`POST /internal/payments/refunds/{refundNo}/resolve` | `POST /api/payments/{paymentNo}/refunds`、`GET /api/refunds/{refundNo}`、`POST /api/refunds/{refundNo}/resolve` | `order-service`、demo、e2e、runbook |
| 5 | `POST /internal/channels/{channelCode}/callback` | `POST /callbacks/channels/{channelCode}` | `mock-channel-web/{ChannelCallbackProxy,RefundCallbackProxy,routing.html,demo.html}`、`demo/start-tunnel.sh`、`docker-compose.yml`（`PAYMENT_CHANNEL_NOTIFY_URL`）、`start-all.sh`、`start-container.sh`、runbook |
| 6 | `GET /internal/payments/confirmed-facts` | `/internal/payments/confirmed-facts`（保留）+ 新增 `/internal/refunds/confirmed-facts` | `reconciliation-service/{PaymentFacts,RefundFacts}FeignClient` |

#### B. 🔴 spec 未给目标但今日存在（**实施前 MUST 补齐，否则必然残留**）

| # | 现状端点 | 所在 Controller | 目标 | 备注 |
|---|---|---|---|---|
| B1 | `POST /internal/payments/query-amount` | `RefundRpcController` | **待定** | 退款可退金额查询；FR-011 覆盖「查询」但未给路径 |
| B2 | `POST /internal/payments/refund-attempt` | `RefundRpcController` | **待定** | 疑似 067 遗留的 attempt 面；**待确认是否仍在使用** |
| B3 | `POST /internal/payments/refund-command` | `PaymentRefundCommandController` | **待定** | 与 `/refunds` 语义重叠，**待确认取舍（合并 or 保留）** |
| B4 | `POST /internal/payments/{paymentNo}/channel-callback` | `ChannelCallbackController` | **待定** | 疑似 037 前遗留的回调面（037 后回调走 `/internal/channels/{code}/callback`）；**待确认是否可删** |
| B5 | `POST /internal/payments/refunds/{refundNo}/channel-callback` | `RefundController` | **待定** | 同上，退款回调面；**待确认是否可删** |
| B6 | `POST /internal/channels/{code}/status` | `ChannelAdminController` | `POST /internal/channels/{channelCode}/availability` | spec 用的是 `availability`；**路径变量名 `{code}` → `{channelCode}`** |
| B7 | `GET /internal/payments/unknown` | `UnknownQueueController` | **待定**（FR-011 覆盖「UNKNOWN 收敛」） | UNKNOWN 队列只读端点 |
| B8 | `GET /internal/limits/users/{userId}`、`PUT /internal/limits/users/{userId}`、`GET /internal/limits/payments/{paymentNo}/operations`、`GET /internal/limits/diagnostics` | `LimitController` | **待定**（FR-011 覆盖「运维」） | 4 个端点；ADR-0071 明确 `/internal/limits/**` 是 ADR-0048 的显式例外 |
| B9 | `POST /internal/payments/pending-postings/{id}/replay` | `PendingPostingAdminController` | **待定** | ⚠️ 路径变量是**数值 `id`**，违反 ADR-0063「数值主键不跨 HTTP」⇒ 正名为业务单号 |

> **B 组的处置必须在 T19/T21 之前定案**。建议一并写入 ADR-0084 的补充或 spec 修订，
> 否则「禁止新旧混跑」下这些端点无处可去。

### 2.3 🔴 跨 ADR 强制同步项（ADR-0084 X-2）

新回调路径 `/callbacks/channels/**` **脱离** `AccessLogProperties` 默认排除路径
（`["/actuator/**", "/internal/channels/**"]`，ADR-0083 决策 3）⇒ 必须同步补 `/callbacks/**`：

| 文件 | 动作 |
|---|---|
| `common/common-core/src/main/java/.../AccessLogProperties.java` | `@DefaultValue` 增补 `/callbacks/**` |
| `common/common-core/src/test/java/.../AccessLogFilterTest.java` | 断言同步 |
| `payment-service/src/{main,test}/resources/application.yml` | 若显式覆盖 `exclude-paths` 则同步 |
| `docs/adr/0083-observability-baseline-slo-and-cardinality.md` | 落点描述同步 |

**不补即重开「渠道报文含 `sign` 整段进 ACCESS_LOG」的漏洞。**

---

## 3. 调用方映射

### 3.1 上下游服务（T22）

| 服务 | 文件 | 现状引用 | 动作 |
|---|---|---|---|
| `order-service` | `infra/client/PaymentFeignClient.java` | `/payments/pay` 等 | 改新路径 |
| `reconciliation-service` | `infra/client/PaymentFactsFeignClient.java` | `/internal/payments/confirmed-facts` | 保留 + 对齐字段名 |
| `reconciliation-service` | `infra/client/RefundFactsFeignClient.java` | `/internal/payments/refunds/confirmed-facts` | 改 `/internal/refunds/confirmed-facts` |
| `reconciliation-service` | `infra/client/PaymentFactDto.java` / `RefundFactDto.java` / `domain/PlatformFact.java` | 事实 DTO | 随端点契约同步 |
| `reconciliation-service` | `test/.../FactReadRetryTest.java` / `FeignFactsResilienceConfigTest.java` | 契约测试 | 同步 |
| `fulfillment-service` / `entitlement-service` | — | 未见直接引用 payment 端点 | 复核（本轮扫描未命中，仍需 T22 复核） |

### 3.2 deployment（T23）

| 位置 | 引用 | 动作 |
|---|---|---|
| `deployment/mock-channel-web/.../ChannelCallbackProxy.java` | `/internal/channels/{code}/callback` | 改 `/callbacks/channels/{code}` |
| `deployment/mock-channel-web/.../RefundCallbackProxy.java` | 同上 | 同上 |
| `deployment/mock-channel-web/src/main/resources/static/demo.html` | 9 处 | 同步 |
| `deployment/mock-channel-web/src/main/resources/static/routing.html` | 7 处 | 同步 |
| `deployment/e2e-tests/src/test/java/.../support/Api.java` | 4 处 | 同步 |
| `deployment/e2e-tests/.../recon/ReconciliationAccuracyE2ETest.java` | 事实端点 | 同步 |
| `deployment/demo/scenario-routing.sh` | 6 处 | 同步 |
| `deployment/demo/scenario-limit.sh` | 5 处 | 同步 |
| `deployment/demo/scenario-refund.sh` | 2 处 | 同步 |
| `deployment/demo/scenario-mq.sh` | order 侧（`/api/orders/...`） | 复核是否连带 |
| `deployment/demo/{README.md,start-tunnel.sh,stripe-listen.sh,traffic-gen.sh}` | 路径与说明 | 同步 |
| `deployment/performance/{order-payment-refund-k6.js,order-payment-refund-loadgen.js,generate-chain-report.js}` | 压测路径 | 同步 |
| `deployment/{docker-compose.yml,start-all.sh,start-container.sh}` | `PAYMENT_CHANNEL_NOTIFY_URL` 等 | 同步 |
| `deployment/grafana/dashboards/payment-arch.json` | 面板直连端点 | 同步 |
| `deployment/prometheus/rules/{payment-alerts.yml,slo-recording.yml}` | 告警/Recording 引用路径 | 同步（注意 SLO 排除 `/internal/channels/**` 亦需补 `/callbacks/**`） |
| `deployment/architecture-tests/.../ServiceBoundaryTest.java` | 白名单里的具体类名 | 随包改名同步 |

### 3.3 文档（T27）

`docs/operations/runbook.md`（10 处）、`docs/architecture/technical-solution.md`、
`docs/architecture/systems/payment-service.md`（23 处）、`docs/architecture/roadmap.md`、
`docs/specs/README.md`、`CHANGELOG.md`（11 处）、以及引用旧端点的历史 spec
（030 / 035 / 036 / 037 / 038 / 039 / 040、009、027 的 acceptance）。

> ⚠️ **历史 spec 属「历史记录型」文档，不改**（该判据见 2026-09-25 037 收口结论）。
> 只改**现状描述型**：runbook / technical-solution / systems / roadmap / specs README / CHANGELOG。

---

## 4. 配置映射

| 现状键前缀 | 归属 | 目标 |
|---|---|---|
| `payment.channel.*` | Channel | 保留（渠道域配置随插件目录走） |
| `payment.wechat.*` / `payment.alipay.*` / `payment.stripe.*` | Channel | 随 `plugins/<vendor>/` 就近 |
| `payment.routing.channels.*` | Channel | 保留（ADR-0073 不变） |
| `payment.limit.*` | Payment | 保留（ADR-0071 不变） |
| `payment.resolve.*` | Payment | 保留 |
| `payment.mq.*` | Payment | 保留 |
| `payment.mock-cashier.*` | Channel | 迁至 `plugins/mock/` |
| `payment.reliability.*` / `payment.metrics.*` | Payment | 保留 |
| `payment.fulfillment.*` | Payment | 保留 |
| `common.access-log.exclude-paths` | common-core | **增补 `/callbacks/**`**（X-2） |

---

## 5. Schema 映射（FR-012）

### 5.1 表归属

| 表 | 现状所有者（代码域） | 目标所有者 | 动作 |
|---|---|---|---|
| `payments` | payment | Payment | 保留 |
| `refunds` / `refund_items` / `refund_intake_locks` / `refund_post_process_attempts` | payment | Payment | 保留 |
| `channel_orders`（旧 `payment_attempts`） | channelgateway | Channel | **表名已由旧 041 正名**（`041-channel-orders-rename.sql`）；本轮只补索引/唯一键口径 |
| `user_payment_limits` / `user_limit_usage` / `limit_operations` | payment/limit | Payment | 保留 |
| `pending_postings` | posting | Payment | 保留（`posting` 包删除不动表） |

### 5.2 重建与门禁

- 从空库按 **Payment/Refund → ChannelOrder → Limit → PendingPosting → 消息与审计辅助表** 顺序建表；
- 每张表有**业务唯一键**与必要查询索引；跨域**只保存业务单号、不建跨域外键**；
- DDL / H2 / MySQL / replay 由**同一 Schema 来源**生成（ADR-0081 双路径重放门禁）；
- 清库工具 **MUST** 显式要求 `APP_ENV=development` 或 `APP_ENV=test`，其他一律 **fail-closed 零删除**；
- ⚠️ 本机 `deployment/logs/*.log` 在实时写入 ⇒ **清库前必须先停 live 栈**。

---

## 6. 删除清单（FR-013 / T24）

| 类别 | 对象 |
|---|---|
| 包 | `com.payment.channelgateway.*`（改名后）、`com.payment.posting.*`（并入后）、`payment/{limit,mq,web}`、`channelgateway/web` |
| Controller | `ChannelCallbackController`（被插件回调入口取代）、`PaymentRefundCommandController`、`RefundRpcController`（并入后）、`UnknownQueueController`（并入后） |
| 端点 | `/payments/pay`、`/internal/payments/**` 旧面、`/internal/channels/{code}/callback`、`/internal/channels/{code}/status`（→ availability） |
| DTO | 旧支付/退款请求响应 DTO（随新契约替换） |
| 测试 | 与上述对象绑定的测试类（**逐个确认，不得连带删无关用例**） |
| 兼容垫片 | 任何转发 / 别名 / `@Deprecated` 兼容层 |

---

## 7. 待定 / 未决项（实施前 MUST 定案）

| # | 项 | 阻塞的任务 |
|---|---|---|
| U-1 | B1~B9 共 9 个端点的新目标（spec `plan.md §3` 未给） | T19 / T21 |
| U-2 | B2/B4/B5 是否为可删的遗留面 | T13 / T19 / T24 |
| U-3 | B9 的数值 `id` 正名为哪个业务单号（ADR-0063） | T18 / T19 |
| U-4 | spec 040 置 `Superseded by 041` | T02 / T27 |
| U-5 | ADR-0084 **Accept**（含 H-041-4~6） | **全部 T04 起** |
| U-6 | 实测回归基线（全量 `mvnw clean test`） | acceptance §5 |
