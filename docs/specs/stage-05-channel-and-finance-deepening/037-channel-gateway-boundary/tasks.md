# Tasks: 037-channel-gateway-boundary

**推进方式**：TDD（红 → 绿 → 重构）。每个 T 先落失败测试，再实现，最后跑全量单测。
**测试策略**：单测为主，不起全链路（NFR-1 / D5）。

**验证命令**（每个 T 结束后跑）：

```bash
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
```

---

## T1　channelNo 业务单号　[FR-001]

- [x] **红**：`BusinessNosTest` 增加用例——`BusinessNos.of(BusinessNoType.CHANNEL)` 前缀为 `CH`、`isValid` 通过、10k 并发生成不重复
- [x] **绿**：`BusinessNoType` 增加 `CHANNEL("CH")`
- [x] **重构**：无
- [x] 验证：`common-core` 单测全绿

## T2　common-dto 渠道网关契约　[FR-003][FR-004][FR-005][FR-006]

- [x] **红**：新增 `ChannelContractTest`——断言契约位于 `com.payment.common.dto.channel`；`ChannelQueryCommand` 以 `channelNo` 为主键；`ChannelPayNotified` 含 `channelCode`
- [x] **绿**：`common/common-dto` 新建 `dto/channel/`：`ChannelPayCommand` / `ChannelRefundCommand` / `ChannelQueryCommand` / `ChannelPayReceipt` / `ChannelRefundReceipt` / `ChannelQuerySnapshot` / `ChannelPayNotified` / `ChannelRefundNotified`
- [x] **重构**：`PaymentScene` / `Goods` / `Payer` / `CallbackUrls` 是否下沉 common-dto（拆微服务时可复用性评估）
- [x] 验证：`common-dto` 单测全绿

## T3　payment_attempts.channel_no 列　[FR-002]

- [ ] **红**：`PaymentAttempt` 单测断言 `channelNo` 必填、不可变
- [ ] **绿**：`03-payment-schema.sql` 加 `channel_no VARCHAR(32)` + UNIQUE；`PaymentAttempt` 实体加字段（含 `rehydrate` 重载）；`ChannelAttemptRecorder` 生成并写入
- [ ] **重构**：移除 `ChargeRequest.attemptId`
- [ ] 验证：`payment-service` 单测全绿；`deployment/demo/reset.sh` 重放建表通过

## T4　ChannelGateway 门面收口　[FR-007][FR-008][INV-1]

- [ ] **红**：`ChannelGatewayTest`——Payment 侧经门面调用，断言 6 个调用点不再引用 Registry/Router/Plugin
- [ ] **绿**：新增 `ChannelGateway` 接口 + 实现；替换 6 处 `channelRegistry.resolve()`（`PaymentApplicationService:301`、`PaymentRefundService:104`、`ChannelQueryService:236`、`PaymentRetryService:79,116`、`RefundUnknownQueryScheduler:167`、`ChannelPluginCallbackController:166`）
- [ ] **重构**：`ChannelAdminController` / `RoutingProperties` 的引用一并收口
- [ ] 验证：既有单测断言**零变化**（NFR-2）

## T5　回调两层：模板方法 + PaymentNotifyPort　[FR-009][FR-010][FR-011][FR-012][FR-013][INV-2][INV-5]

- [ ] **红**：`ChannelCallbackHandlerTest`——断言四步顺序（选插件 → 验签 → 转换 → 网关单更新）；`PaymentNotifyPortTest`——断言收到事件含 `channelCode` 且不含渠道私有类型
- [ ] **绿**：
  - 新增 `AbstractChannelCallbackHandler`（`final` 入口，四步模板方法）
  - 新增 `PaymentNotifyPort`（Payment 定义 + 实现），`onChannelPayResult` / `onChannelRefundResult`
  - `ChannelPluginCallbackController.validate()` 中的 Payment 逻辑迁入 Port 实现
  - 以 `PaymentNotifyPort.onChannelRefundResult` 取代 `RefundResultListener`
  - `DyeContext` 读取点收敛至渠道网关域
- [ ] **重构**：`ChannelPluginCallbackController` 只剩「收报文、交网关」
- [ ] 验证：`payment-service` 单测全绿

## T6　存量渠道迁移　[FR-014][FR-015]

- [ ] **红**：断言 5 家渠道全部 `extends AbstractChannelPlugin`
- [ ] **绿**：MOCK / WECHAT / ALIPAY / DOUYIN 迁至 `AbstractChannelPlugin`
- [ ] **重构**：删除 `AlipayNotifyController` 及其 2 个测试，回调统一走通用端点
- [ ] 验证：`payment-service` 单测全绿

## T7　ArchUnit 门禁　[FR-016]

- [ ] **红**：三条规则先落阳性对照（故意违规能触发）
- [ ] **绿**：① Payment 应用/api 层禁依赖渠道内部件；② 渠道域禁依赖 Payment/refund 应用实现（`PaymentNotifyPort` 除外）；③ `DyeContext` 仅限渠道域
- [ ] 验证：`ServiceBoundaryTest` 全绿，阳性对照存在

## T8　收口

- [ ] 回填 `acceptance.md` 实测结论
- [ ] 更新 `docs/adr/README.md` 与 `traceability.md`（若新增 ADR）
- [ ] feature 分支 → `--no-ff` 合入 master
