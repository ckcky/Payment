# Tasks: 037-channel-gateway-boundary

**推进方式**：TDD（红 → 绿 → 重构）。每个 T 先落失败测试，再实现，最后跑全量单测。
**测试策略**：单测为主，不起全链路（NFR-1 / D5）。

**验证命令**（每个 T 结束后跑）：

```bash
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
```

---

## ⚠️ 基线变更（2026-09-25）：038 已先于本 Spec 合入 master

**事实**：master 已推进到 `0a81b29`（`Merge branch 'feature/038-payment-service-package-boundary'`）。
038 的 spec 自己写明「前置条件：037 必须已合入 master」，但实际是**038 先合入**。
于是本 Spec 的路径与部分范围需要就地适配：

| 影响 | 事实 | 本 Spec 的处置 |
|---|---|---|
| 包搬迁 | 渠道网关件整体搬入顶层包 `com.payment.channelgateway..`（40 文件）；`com.payment.refund` 被消灭（并入 `com.payment.payment`） | T4 起全部按新包结构落点；`spec.md` §1.1 的 `path:line` 证据表按当时快照保留不改（历史记录型） |
| T2 的「重构」项 | 038 对 `PaymentScene`/`Goods`/`Payer`/`CallbackUrls`/`PayCredential` 做了**完全相同**的包下沉（`→ com.payment.common.dto.channel`） | 与 T2 成果一致，无冲突；合并时按 038 侧取值 |
| T7（FR-016） | 038 已落 **14 条包级 ArchUnit 规则**，其中 `channelGatewayMustNotDependOnOtherServicesAtCompileTime` / `paymentAndChannelGatewayMustKeepOneWayDependency` / `channelGatewayMustNotReferenceLegacyRefundPackage` / `channelPluginsMustResideInTheirInfraChannelPackage` 覆盖了 FR-016 的**大部分**意图，且是包级（非硬编码渠道名） | T7 收窄为「补 FR-016 中 038 **未**覆盖的两条」：① Payment 应用/api 层禁依赖渠道内部件（038 只约束了反方向）；③ `DyeContext` 仅限渠道网关域 |
| 技术债归属 | 038 在 `ServiceBoundaryTest.LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` 白名单登记 4 个类（3 回调 Controller + 1 验签过滤器），注释写明**「收口归 037」** | 归 T5 处理 |

**合并记录**：`feature/037-*` 以 `--no-ff` 合入 `origin/master`，10 处冲突全部是「T2 补的 import」vs「038 的包搬迁」，
统一取 038 侧（新包路径）。合并后 `./mvnw -B clean test` = **1016 tests / 0F / 0E / 18 模块 SUCCESS**（基线）。
> ⚠️ 计数必须用 `clean test`：`target/` 里残留 038 搬迁前的旧包路径 surefire XML
> （`com.payment.payment.infra.channel.*` / `com.payment.refund.*`），不 clean 会把
> payment-service 从 388 虚报成 492、全量从 1016 虚报成 1120。

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

- [x] **红**：`PaymentAttempt` 单测断言 `channelNo` 必填、不可变
- [x] **绿**：`03-payment-schema.sql` 加 `channel_no VARCHAR(32)` + UNIQUE；`PaymentAttempt` 实体加字段（含 `rehydrate` 重载）；`ChannelAttemptRecorder` 生成并写入
- [ ] **重构**：移除 `ChargeRequest.attemptId`
- [x] 验证：`payment-service` 单测全绿；`deployment/demo/reset.sh` 重放建表通过

> ⚠️ **`重构` 项未执行（待裁决）**：移除 `ChargeRequest.attemptId` 会让
> `payment-service/src/test/java/com/payment/payment/contract/ChannelContractCompatTest.java`
> 的 3 处（构造 2 + 断言 1，`assertThat(req.attemptId()).isEqualTo(7L)`）无法编译——
> 即「既有单测断言必须改」。按交接提示词 §4「若某个断言必须改，停下报告，不要自己改」，
> 本项挂起待人类裁决。影响面已实测：`attemptId` 在 main 下**无任何读者**，
> 仅在 1 处生产构造点（`PaymentApplicationService:211`）与 9 处测试构造点传入。

## T4　ChannelGateway 门面收口　[FR-007][FR-008][INV-1]

- [x] **红**：`ChannelGatewayTest`——门面行为等价（pay/refund/query/route/能力查询/未注册拒绝）+ SC-002 反射断言（Payment 侧字段与构造器参数不含 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`）
- [x] **绿**：新增 `ChannelGateway` 接口 + `DefaultChannelGateway` 实现；Payment 侧 5 个消费点全部改走门面（`PaymentApplicationService`、`PaymentRefundService`、`ChannelQueryService`、`PaymentRetryService`、`RefundUnknownQueryScheduler`）
- [x] **重构**：`ChannelAdminController` / `RoutingProperties` 的引用一并收口
- [x] 验证：既有单测断言**零变化**（NFR-2）

### T4 执行记录（基线 = 038 已合入后的包结构）

**落点**：门面与默认实现均在 `com.payment.channelgateway.application`（**不**放 `infra`）。
理由沿用 `SingleChannelRegistry` javadoc 里登记的 2026-09-20 FIX-1 纪律：该类零 infra 依赖，
是「门面」这个**应用层抽象**的退化实现，放 `application` 才能让依赖方向恒为 `infra → application`，
避免 Payment 侧兼容构造反向依赖 `channelgateway.infra..` 而穿透包边界。

**方法族**（7 个）：`route` / `pay` / `refund` / `query` / `supportedScenes` /
`registeredChannelCodes` / `requireRegistered`，另有两个兼容静态工厂
`ChannelGateway.none()` 与 `ChannelGateway.ofSingleChannel(PaymentChannel)`。

**三个设计裁决（均为「行为零变化」而做，不是自由发挥）**：

1. **`pay` / `refund` 取显式渠道码**（`pay(String channelCode, ChargeRequest)`），
   与既有 `query(String, QueryStatusRequest)` 对齐。原因：`PaymentRetryService` 的兼容路径下
   请求可能**不带**渠道码（回落唯一注册渠道），若门面从 `request.channelCode()` 取值会
   `resolve(null)` 抛错——显式传码既保住该路径，又把 INV-6（禁止重新选路）编码进签名。
2. **新增 `requireRegistered(String)`**。`ChannelQueryService#resolveRecordedTarget` 的解析
   发生在 `recordQueryAttempt()` **之前**（脏数据不得消耗查询次数，spec 034 诊断②）。
   若把校验推迟到 `query()` 内部，那次计数就已落库。故保留一个只回答「可不可以」的先验校验，
   `RefundUnknownQueryScheduler` 同型复用。
3. **`RecordedTarget` 承载渠道码字符串而非 `PaymentChannel`**（两个反向路径类各自私有），
   实现由门面在调用时解析——Payment 侧因此不持有任何渠道实现类型。

**第 6 处（`ChannelPluginCallbackController`）刻意不走门面**：
038 已把它搬进 `com.payment.channelgateway.api`，它不再是「Payment 侧越界调用方」，
而是网关域自己的 HTTP 入口；其 `registry.resolve()` 是**域内合法**用法。
更关键的是 FR-007 明确要求 `ChannelPlugin` 成为网关域私有实现——
若为它开一个「取回调插件」的门面方法，等于把 `ChannelPlugin` 重新泄出。
该处的收口由 **T5**（`ChannelCallbackHandler` + `PaymentNotifyPort`）在域内完成。

**`ChannelAdminController` / `RoutingProperties` 的收口状态**：
038 已把两者整体搬入 `channelgateway.api` / `channelgateway.infra.config`，
「Payment 可触达」这一越界事实**已由 038 消除**，故本项不再需要额外改动，标记为完成。
（对照：038 的 `ServiceBoundaryTest.LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` 白名单
登记的 4 个类，其收口归 T5。）

**实测**：`ChannelGatewayTest` 11/11 绿；payment 侧 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin`
的**代码引用**为零（仅 `PaymentApplicationService` 一处 javadoc 提及）；
`./mvnw -B clean test` = 1016 tests / 0F / 0E / 18 模块 SUCCESS（含 038 的 16 条 `ServiceBoundaryTest` 门禁）。

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
