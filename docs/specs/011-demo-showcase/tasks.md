# Tasks: 011-demo-showcase

**对应 Spec / Plan**：[spec.md](spec.md) · [plan.md](plan.md)
**最后更新**：2026-08-31（负责人裁决修订：新增 `mock-channel-web` + 收银台 payUrl；验签维持 ADR-0025 占位，ADR-0052 经用户确认回退为 Not Implemented，见 `docs/adr/0013`）

> 状态说明：`[x]` 已落地；`[ ]` 待办（多归属 Phase 5 全量验证与文档同步）。

## 批次 A — 设计与决策（前序会话已完成）

- [x] **T001** 编写 `spec.md`（含现状核实，修正 next-stage-design 三处偏差）
- [x] **T002** 编写 `plan.md` / `tasks.md`
- [x] **T003** `docs/adr/0012-demo-showcase-decisions.md`（ADR-0048~0051）+ `README.md` 同步
- [x] **T003b** ADR-0048 按裁决修订（推翻「不做收银台」）→ `spec.md` v0.2 / ADR-0012 状态 Accepted

## 批次 B — 生产改动（带测试）

- [x] **T011** `MockChannelAdapter` 的 `Scenario` 可由 `payment.channel.mock-scenario` 配置（默认 SUCCESS，非法值 FAIL FAST）（前序会话）
- [x] **T012** T011 单元测试：场景生效 + 非法值启动失败（前序会话）
- [x] **T013** `fulfillment-service`：`GET /fulfillments/by-order/{orderId}` 只读端点（前序会话）
- [x] **T014** T013 测试：命中返回 / 未命中 404（前序会话）
- [x] **T015** `entitlement-service`：`GET /entitlements/by-order/{orderId}` 只读端点（前序会话）
- [x] **T016** T015 测试：命中返回 / 未命中 404（前序会话）
- [x] **T017** `CreatePaymentResponse` / `CreateOrderResponse` 新增 `payUrl` 字段（仅 `payment.mock-cashier.enabled=true` 时返回，同步主链零影响）
- [x] **T018** `PaymentApplicationService.createPaymentIntent` 支持 `deferChannel` 重载（mock-cashier 开启时跳过同步 charge，支付单落 `PROCESSING` 等回调）
- [x] **T019** `payment-service` 新增 `MockCashierProperties`（`enabled` 默认 false，`base-url` 指向 8091）
- [x] **T020** `payment-service` 测试：defer 路径不触达渠道；payUrl 在 enabled 时返回 / disabled 时为空（PaymentDeferredChannelTest / PaymentControllerPayUrlTest，5 个新增用例全绿）
- [x] **T021** 新增 `mock-channel-web` Maven 模块（端口 8091，纯 HTTP 转发 + 静态页，**不依赖任何服务模块**，ArchUnit `ServiceBoundaryTest.SERVICES` 不含它）
- [x] **T022** `mock-channel-web`：`PageController`（静态页）、`ChannelCallbackProxy`（HMAC 签名转发 + VALID/FORGED/NONE 三模式）、`DemoProxyController`（同源代理各服务，免 CORS）
- [x] **T023** `mock-channel-web` 测试：`ChannelCallbackProxyTest` 覆盖 VALID 验签通过 / FORGED 验签失败（签名层）/ NONE 无头 / 未知模式返回 null / SimpleJson 序列化（5 用例全绿；注意：仅验证代理签名逻辑，payment 侧是否拒绝取决于 ADR-0025/0052）
- [x] **T024** `mock-channel-web` 静态页：`cashier.html`（六按钮：SUCCESS/FAILURE/TIMEOUT/UNKNOWN/REPLAY/FORGED_SIGNATURE）、`demo.html`（主链演示控制台，含 SKU 列表 / 下单 / 轮询权益 / 对账入口）

## 批次 C — 演示脚本（bash）

- [x] **T025** `demo/lib.sh`：颜色、`http` 封装、`json_get`/`jget`、`assert_eq`/`assert_contains`/`assert_status`、`wait_for_services`、`wait_until`
- [x] **T026** `demo/reset.sh`：重建 9 业务 Schema（仅业务库，不碰 `mysql`）→ 重放 `deployment/schema/*.sql` → API 灌种子（1 商户 + 1 商品 + 3 SKU：101/102/103）
- [x] **T027** `demo/scenario-happy-path.sh`：建单（收银台路径）→ 经 `mock-channel-web` 成功回调 → 履约 → 权益 → 记账平衡 → **重复回调幂等吸收**（另含「明文/伪造签名回调同样放行」步骤，演示 ADR-0025 占位边界），断言全过
- [x] **T028** `demo/scenario-payment-unknown.sh` + `demo/scenario-refund.sh`：分别演示 UNKNOWN 权威收敛（无令牌 403 / 带令牌裁定 FAILED）与退款（累计超额 409 / 幂等重放同一 id）；原设想的 `scenario-callback-signature.sh` 因 ADR-0025 占位（验签不拦截）不单独成脚本，伪造签名放行行为已并入 happy-path 步骤 ③b
- [x] **T029** `demo/scenario-reconciliation.sh`：以演示周期触发批次 → 列差异 → **关闭门禁反例（未处理差异时 400 拒）** → 处理全部差异 → 关闭 CLOSED → 结算汇总（走真实对账闭环，无周期账单时回退 sample.csv）
- [x] **T029b** `demo/restart-payment.sh`：重启 payment-service 并切换 Mock 渠道场景（构造期注入，ADR-0049）；`demo/seed.sh` 仅重灌种子（不重建 Schema）
- [x] **T030** `demo/run-all.sh`：reset → 四场景按序执行，失败即中断（注：本环境无 Docker，run-all 全栈实测见 T035）

## 批次 D — 部署接线与全量验证（部分待 Phase 5）

- [x] **T031** 根 `pom.xml` 增加 `mock-channel-web` 模块
- [x] **T032** `deployment/start-all.sh`：加入 `mock-channel-web`(8091) 与 `ledger-service`(8090)，默认 export `PAYMENT_CHANNEL_SECRET` 演示密钥
- [x] **T033** `deployment/stop-all.sh` / `prometheus/prometheus.yml`：纳入 8091 观测
- [ ] **T034** `bash -n demo/*.sh` 语法检查（本地执行一次）
- [ ] **T035** 全栈实测：启动 `deployment/start-all.sh` → `bash demo/run-all.sh` 全场景断言通过（需本机 MySQL + 9 服务）
- [ ] **T036** `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁，确认未误纳 `mock-channel-web`）
- [ ] **T037** 同步 `docs/architecture/roadmap.md`（Current Status / Next Feature）
- [ ] **T038** 同步 `docs/operations/runbook.md`（新增 8091 组件 + demo 章节）
- [ ] **T039** 编写 `acceptance.md` 记录实测结果
- [ ] **T040** Conventional Commits 提交并合并到 `master`

## 备注

- 收银台 `cashier.html` 六按钮与 `demo.html` 控制台由 `mock-channel-web` 托管于 8091；**FORGED_SIGNATURE 按钮在当前 ADR-0025 占位形态下不会触发 403**（payment 恒放行），页面与 README 已如实标注。
- UNKNOWN / 退款 现由独立脚本 `scenario-payment-unknown.sh` / `scenario-refund.sh` 演示（非仅手动），断言见各自文件。
- `demo/README.md` 已创建，汇总前置条件、运行步骤、四场景断言与 Mock 渠道场景切换。
