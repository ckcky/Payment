# Tasks: 039-wechat-pay-channel-plugin

**对应 Spec**：[spec.md](spec.md)｜**Plan**：[plan.md](plan.md)
**推进方式**：TDD（红 → 绿 → 重构）；**串行**执行 T1→T7
**前置**：038 **已合入** master @ `0a81b29`（决定插件包落点）；036 已合入。
**开工前必读**：spec §3.1「开工前裁决」——C-1~C-4 四项已由负责人裁决，**执行方 MUST 按裁决执行，不得按 spec 原文**。
**037 执行时 MUST 跳过 WECHAT**（见 spec §12）

**验证命令（每步结束跑）**：

```bash
./mvnw -B -pl payment-service -am test -Dtest='Wechat*' -Dsurefire.failIfNoSpecifiedTests=false
```

> ⚠️ **必须带 `-am`**，否则 `common-redis-mq` / `test-infra` 快照依赖解析失败。

---

## T1　SDK 依赖 + `WechatPayProperties`　[FR-002][FR-008][FR-009][INV-1]

- [x] **红**：`WechatPayPropertiesTest` —— `enabled=true` 且 env 缺 `mchId`/私钥/APIv3 密钥时，`@PostConstruct` MUST 抛错；`enabled=false` 时不读任何 env
- [x] **绿**：`payment-service/pom.xml` 加 `com.github.wechatpay-apiv3:wechatpay-java:0.2.17`
- [x] **绿**：新建 `WechatPayProperties`，前缀 `PAYMENT_WECHAT_`，字段：`mchId` / `appId` / `apiV3Key` / `merchantSerialNo` / `privateKeyPath`（或 env 内联内容）/ `platformCertPath`（或公钥）/ `notifyUrl` / `enabled=false`
- [x] **绿**：`@PostConstruct` 强校验（同 `StripeSandboxProperties` 体例）
- [x] 复核：`git status` 无任何证书 / 密钥文件被加入（**禁落盘**）
- [x] 复核：SDK 引用仅出现在 `wechat` 插件包内

## T2　`WechatSdkGateway`：V3 签名 + HTTP　[FR-004][FR-005][FR-006][INV-4]

- [x] **红**：`WechatSdkGatewaySignatureTest` —— **签名金标准**：固定密钥 + 固定 `timestamp` + 固定 `nonce` + 固定 body ⇒ 断言 `Authorization` 头**逐字节一致**（固定向量）
- [x] **绿**：实现 V3 请求签名（`WECHATPAY2-SHA256-RSA2048`，`mchid` / `nonce_str` / `timestamp` / `serial_no` / `signature`）
- [x] **绿**：实现 NATIVE 下单（`POST /v3/pay/transactions/native`）→ 取 `code_url`
- [x] **绿**：实现 JSAPI 下单（`POST /v3/pay/transactions/jsapi`，带 `payer.openid`）→ 取 `prepay_id`
- [x] **绿**：实现 H5 / MINI_PROGRAM 下单（FR-003 声明的场景必须全部可实现）
- [x] **绿**：实现查询（按 `out_trade_no` 与 `transaction_id` 两种键）
- [x] **绿**：实现退款（`POST /v3/refund/domestic/refunds`，`out_refund_no = refundNo`）
- [x] **红→绿**：金额单位测试 —— `amountMinor` 直传微信 `amount.total`，**禁止**任何 `*100` / `/100`
- [x] 复核：本地生成的测试密钥对**不进 git**（放 `src/test/resources` 的 gitignore 路径或运行时生成）

## T3　`WechatChannelPlugin` + Factory（含删除旧 Adapter）　[FR-001][FR-003][FR-010][FR-015][FR-016][INV-8]

- [x] **红**：`WechatChannelPluginDescriptorTest` —— `channelCode()=WECHAT`；`supportedScenes() ⊇ {NATIVE, JSAPI, H5, MINI_PROGRAM}`；`supportsRealMode()` 受 `enabled` 门控
      > 实现期修订 **M-1**：测试类并入 `WechatChannelPluginTest`（描述符断言 + 双模态 + 凭证形态 + 错误码映射同处一文件，避免碎片化）。
- [x] **绿**：`WechatChannelPlugin extends AbstractChannelPlugin`，只实现 `descriptor()` + `isRealModeEnabled()` + `doRealCharge` / `doRealRefund` / `doRealQuery`
- [x] **绿（C-3 裁决）**：`WechatChannelPluginFactory` **照 `StripeChannelPluginFactory` 只注册为 Spring `@Component`**；**不**新建 `META-INF/services/…ChannelPluginFactory`
- [x] **绿（C-2 裁决 / INV-8）**：`git rm payment-service/src/main/java/com/payment/channelgateway/infra/WechatChannelAdapter.java`——同 `channelCode` 两路注册会触发结构性错误
- [x] **绿**：`WechatGateway` 渠道端口（领域侧契约，隔离 SDK 类型）
- [x] **红**：断言插件内**无**自写 mock 语义（MOCK 全交内核，FR-010 / D7）
      > 以「`enabled=false` 时 charge/query/refund 全不触达网关（`prepayCalls==0` / `lastQueryOutTradeNo==null` / `lastRefund==null`）」实现。
- [x] **红（C-1 裁决 / FR-016）**：`enabled=false` 时插件**仍注册**且 MOCK 可路由 —— 断言 `GET /internal/channels` 含 `WECHAT` 且 `enabled:false`（**不得**加 `@ConditionalOnProperty`）
      > 单测层断言 `isRealModeEnabled()==false` 但 `channelCode()==WECHAT` 且 MOCK charge 成功；并反射断言工厂**无**任何 `Conditional*` 注解。**活栈断言由 T7 的 `scenario-routing.sh` 承接**。
- [x] 复核：`application.yml` 的 `adapters.WECHAT.scenario` 迁移后为死配置，按 C-4 在 acceptance 登记

## T4　`parseCallback`：验签 + 解密　[FR-007][FR-014][INV-5]

- [x] **红**：`WechatCallbackParseTest` —— ① 本地生成密钥对构造合法 V3 通知（加密 + 签名）⇒ 插件解析结果与原文一致 ② **验签失败 ⇒ 拒绝且不进入解密**（顺序断言）③ 解密失败 ⇒ 拒绝
- [x] **绿**：实现三步：平台证书验签（`Wechatpay-Signature`/`Timestamp`/`Nonce`/`Serial`）→ APIv3 密钥 AES-256-GCM 解密 `resource` → 转 `ParsedCallback`
- [x] **绿**：`callbackAckBody()` 返回微信期望的应答（`{"code":"SUCCESS","message":"成功"}` 或失败体）
- [x] 复核：复用通用端点 `/internal/channels/WECHAT/callback`，**未**新增微信专属 Controller

## T5　本地仿真桩 + 四步全链路　[FR-012][FR-013][SC-005]

- [x] 建仿真桩（WireMock 或自建测试 Controller），模拟 V3：`/v3/pay/transactions/native`、`/v3/pay/transactions/jsapi`、`/v3/pay/transactions/out-trade-no/{no}`、`/v3/refund/domestic/refunds`、回调通知
      > 实现期修订 **M-2**：**不引 WireMock**，改用 JDK 内置 `com.sun.net.httpserver.HttpServer`（`WechatStubServerTest`）——桩只需「路由 + 读头 + 读体 + 回 JSON」，JDK 够用，且**零新增测试依赖**。桩只监听 `127.0.0.1:0`（随机端口），把 `WechatPayProperties.apiBaseUrl` 指过去，**微信域名不会出现在本测试里**。
      > 桩额外覆盖 `/v3/pay/transactions/h5`（H5 下单）与 `/v3/pay/transactions/id`（按 `transaction_id` 查询），并用 `PM-DECLINE` 前缀模拟 4xx 业务拒绝。
- [x] **红→绿**：全链路用例 —— 下单 → 查询 → 退款 → 回调，**不依赖微信网络**
      > 实测 7/7 绿。`fourStepChainRunsEntirelyAgainstLocalStub` 走通 NATIVE 下单 → 两种键查询（并断言两条路径各命中一次、均带 `mchid`）→ 退款 → 回调（金额/币种/单据号一致）。
      > **关键强化**：`stubReceivesVerifiableV3Signature` 用**桩收到的** `timestamp`/`nonce_str`/报文重建基串、以**商户公钥**验签 —— 只断言「返回了凭证」证明不了签名对（桩不会像微信那样 401），验签通过才说明真实微信侧也会接受。
- [x] **红→绿**：渠道模态写入 `payment_attempts.extra_json` 的 `channelMode` 键；归属恒读 `channel_code` 列
      > **落点说明（不新增重复测试）**：模态写/读属**内核职责**，既有测试已钉死——`PaymentAttemptChannelModeTest`（写入侧键存在 + 坏数据回退 MOCK）、`PaymentAttemptExtraJsonTest`（`[FR-303]` 写入口盖章 + 非法值绝不误判 SANDBOX）、`AttemptExtraCodecTest`（编解码）、`ChannelAttemptRecorderContractTest`（写入口必须盖 `channelMode`）。插件侧本次补 `channelIdentityIsStable`：`channelCode()` 在 MOCK/SANDBOX 两模态下**恒为 `WECHAT`**（归属不随模态漂移，FR-013）。
- [x] **红→绿**：状态映射用例 —— `USERPAYING`/`NOTPAY` ⇒ `UNKNOWN`；`SUCCESS` ⇒ `SUCCESS`；`CLOSED`/`PAYERROR`/`REFUND` ⇒ `FAILURE`（INV-7）
      > 分两处落点：**回调翻译**在 `WechatCallbackParseTest`（`pendingStatesMapToUnknown` / `closedStatesMapToFailure`）；**查询翻译**在 `WechatSdkGatewayAmountTest` 与插件查询用例（`NOTPAY`/`USERPAYING` ⇒ `businessUnknown`）。

## T6　全量回归 + L0 文档同步　[FR-011][SC-008]

- [x] `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿
      > 实测：**全 reactor 16 模块 BUILD SUCCESS**；`architecture-tests` **21/21 绿**（`ServiceBoundaryTest` 16/16、`RpcEdgeAllowListTest` 2/2、`AccountingVocabularyBoundaryTest` 2/2）；`payment-service` 含新增 54 个 `Wechat*` 测试全绿。删除旧 `WechatChannelAdapter` **零回归**。
- [x] 零改动判据（SC-002）：`git diff --stat` 中 `application` / `api` / `domain` / 内核 **为空**
      > 实测：`payment/application`、`payment/api`、`payment/domain`、`channelgateway/application/spi` 四条 `git diff --stat master --` **均为空**。
- [x] 同步 `docs/architecture/systems/payment-service.md` 渠道清单（WECHAT 支持真实模式）
      > 顺带修正既有漂移：`MOCK` 行原写「走 `AbstractMockChannelAdapter`」（插件族不适用）→ 改为「内核统一语义 + Adapter 族/插件族分列」；`SANDBOX` 行原写「当前仅支付宝」→ 补 `StripeChannelPlugin` 与 `WechatChannelPlugin`。
- [x] `CHANGELOG.md` 记一笔
      > 已随收口写入（含「已知变更 CHG-1/CHG-2」与沙箱结论留档指引）。
- [x] **ADR 正文不回改**（历史决策留痕）

## T7　起全链路验证　[SC-006][SC-008]

- [x] `deployment/start-all.sh`（先确认 Docker Desktop Running）
      > ⚠️ **必须同时设 `SPRING_PROFILES_ACTIVE=demo`**：`POST /internal/channels/{code}/status` 标注 `@Profile("demo")`，
      > 未激活则 404，`scenario-routing.sh` 在 ⓪a 步即失败（本次实测首跑复现）。`demo/README.md` 称「宿主模式经
      > `start-demo.sh` 同值传递」，但 `start-demo.sh` **并未设置该变量** —— 属**既有文档/脚本漂移**（非本 Feature 引入），
      > 已在收口报告中登记，本次未改动该脚本。
      > ⚠️ **全新数据卷时必须先落 schema 再起服务**：`ledger-service` 启动期即查 `ledger.account_definitions`，
      > 空库会导致其 `spring-boot:run` 以 exit code 1 退出（本次实测复现）。处置：按 `demo/reset.sh` 的同一文件清单
      > 先重放 `deployment/schema/*.sql` + `027`/`032`/`034` 三条迁移，再起服务。`start-all.sh` 不含建表步骤，属既有缺口。
- [x] `deployment/demo/reset.sh`
      > 实测 EXIT=0（`复位完成：商户=1 商品=1 SKU=101/102/103`）。
- [x] `deployment/demo/scenario-refund.sh` 通过；演示控制台三层退款单逐层可见
      > 实测 **EXIT=0，17 条断言全 PASS**；含三层退款单可见性硬断言：`transaction_refunds(TXRF)` / `refunds(PMRF)` /
      > `payment_attempts(REFUND)` 三 section 均有数据。
- [x] ⭐ `deployment/demo/scenario-routing.sh` **必须通过** —— 其 S2/S3/S5/S6 与 `assert_contains "$REGISTERED" "WECHAT"` 是 C-1 裁决的验收证据（WECHAT 删 Adapter 改插件后仍须可路由）
      > 实测 **EXIT=0，40 条断言全 PASS**。关键证据：`已注册 WECHAT (contains 'WECHAT')` ✅；
      > S2 `channel_code == WECHAT`（显式优先）、S3（ALIPAY=DOWN 后 auto 改选 WECHAT）、S5（两渠道独立实例）、
      > S6（退款 attempt 回原渠道 WECHAT）全 PASS；`payment_routing_total` 已暴露。
      > S6 退款**一次即收敛 SUCCEEDED**，与 CHG-2（WECHAT mock 退款同步化）口径一致。
- [x] 确认 WECHAT 在 `enabled=false` 时**仍注册**（`GET /internal/channels` 显示 `enabled:false`）、MOCK 可路由、**不误触真实扣款**
      > ⚠️ **spec 此处口径需修正**：`GET /internal/channels` 的 `enabled` 字段来自 `payment.routing.channels.*.enabled`
      > （**选路资格**，WECHAT 配置为 `true`），**不是** `payment.wechat.enabled`（**真实模式门控**，默认 `false`）。
      > 两者是**独立坐标轴**，故该清单实际显示 `WECHAT {enabled: true, priority: 20}`——这与「真实模式未启用」并不矛盾。
      > 本次以**行为证据**替代该字段断言（更强）：同订单双发对照实测——
      > ① 染 `X-Dye-Tag: SANDBOX` + 显式 WECHAT ⇒ **400 INVALID_ARGUMENT**，报文 `requires channel 'WECHAT' real mode
      > enabled; refusing to silently fall back to mock mode`（**不误触真实扣款、不静默回落**）；
      > ② 同订单不染色 ⇒ **201** + `channelCode=WECHAT` + `payUrl`（**MOCK 可路由**）。
      > 结论：**「仍注册 + MOCK 可路由 + 真实模式关闭」三条全部成立**，仅 SC-006 中「清单显示 `enabled:false`」一句措辞与实现不符，已在 `acceptance.md` 勘误。
- [x] ⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`
      > 本机 `SERVER__PORT=63371` 默认存在，未 unset 会让全部服务挤到同一端口。已按要求执行。

## T8　收口

- [x] 回填 `acceptance.md` 实测结论（**含沙箱实测章节**）
      > 已回填 SC-001~SC-009 全 PASS；新增「活栈实测证据」章节（渠道清单原始报文 + C-1 双发对照 400/201 + 两脚本 EXIT=0）；
      > 回归基线表勘误（`payment-service` 基线 **370 → 344**，经 master 独立 worktree 实测）；SC-006 判据措辞勘误。
- [x] 更新 `docs/adr/README.md` 与 `docs/adr/traceability.md`（若新增 ADR）
      > **N/A**：本 Feature **未新增 ADR**（实现遵循既有 ADR-0075 统一渠道契约 / ADR-0076 染色与沙箱 / ADR-0016 恒全退），故两文件无需改动。
- [x] feature 分支 → `--no-ff` 合入 master（本机无 `gh` CLI，走本地 merge + push）
