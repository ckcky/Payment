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

- [ ] **红**：`WechatPayPropertiesTest` —— `enabled=true` 且 env 缺 `mchId`/私钥/APIv3 密钥时，`@PostConstruct` MUST 抛错；`enabled=false` 时不读任何 env
- [ ] **绿**：`payment-service/pom.xml` 加 `com.github.wechatpay-apiv3:wechatpay-java:0.2.17`
- [ ] **绿**：新建 `WechatPayProperties`，前缀 `PAYMENT_WECHAT_`，字段：`mchId` / `appId` / `apiV3Key` / `merchantSerialNo` / `privateKeyPath`（或 env 内联内容）/ `platformCertPath`（或公钥）/ `notifyUrl` / `enabled=false`
- [ ] **绿**：`@PostConstruct` 强校验（同 `StripeSandboxProperties` 体例）
- [ ] 复核：`git status` 无任何证书 / 密钥文件被加入（**禁落盘**）
- [ ] 复核：SDK 引用仅出现在 `wechat` 插件包内

## T2　`WechatSdkGateway`：V3 签名 + HTTP　[FR-004][FR-005][FR-006][INV-4]

- [ ] **红**：`WechatSdkGatewaySignatureTest` —— **签名金标准**：固定密钥 + 固定 `timestamp` + 固定 `nonce` + 固定 body ⇒ 断言 `Authorization` 头**逐字节一致**（固定向量）
- [ ] **绿**：实现 V3 请求签名（`WECHATPAY2-SHA256-RSA2048`，`mchid` / `nonce_str` / `timestamp` / `serial_no` / `signature`）
- [ ] **绿**：实现 NATIVE 下单（`POST /v3/pay/transactions/native`）→ 取 `code_url`
- [ ] **绿**：实现 JSAPI 下单（`POST /v3/pay/transactions/jsapi`，带 `payer.openid`）→ 取 `prepay_id`
- [ ] **绿**：实现 H5 / MINI_PROGRAM 下单（FR-003 声明的场景必须全部可实现）
- [ ] **绿**：实现查询（按 `out_trade_no` 与 `transaction_id` 两种键）
- [ ] **绿**：实现退款（`POST /v3/refund/domestic/refunds`，`out_refund_no = refundNo`）
- [ ] **红→绿**：金额单位测试 —— `amountMinor` 直传微信 `amount.total`，**禁止**任何 `*100` / `/100`
- [ ] 复核：本地生成的测试密钥对**不进 git**（放 `src/test/resources` 的 gitignore 路径或运行时生成）

## T3　`WechatChannelPlugin` + Factory（含删除旧 Adapter）　[FR-001][FR-003][FR-010][FR-015][FR-016][INV-8]

- [ ] **红**：`WechatChannelPluginDescriptorTest` —— `channelCode()=WECHAT`；`supportedScenes() ⊇ {NATIVE, JSAPI, H5, MINI_PROGRAM}`；`supportsRealMode()` 受 `enabled` 门控
- [ ] **绿**：`WechatChannelPlugin extends AbstractChannelPlugin`，只实现 `descriptor()` + `isRealModeEnabled()` + `doRealCharge` / `doRealRefund` / `doRealQuery`
- [ ] **绿（C-3 裁决）**：`WechatChannelPluginFactory` **照 `StripeChannelPluginFactory` 只注册为 Spring `@Component`**；**不**新建 `META-INF/services/…ChannelPluginFactory`
- [ ] **绿（C-2 裁决 / INV-8）**：`git rm payment-service/src/main/java/com/payment/channelgateway/infra/WechatChannelAdapter.java`——同 `channelCode` 两路注册会触发结构性错误
- [ ] **绿**：`WechatGateway` 渠道端口（领域侧契约，隔离 SDK 类型）
- [ ] **红**：断言插件内**无**自写 mock 语义（MOCK 全交内核，FR-010 / D7）
- [ ] **红（C-1 裁决 / FR-016）**：`enabled=false` 时插件**仍注册**且 MOCK 可路由 —— 断言 `GET /internal/channels` 含 `WECHAT` 且 `enabled:false`（**不得**加 `@ConditionalOnProperty`）
- [ ] 复核：`application.yml` 的 `adapters.WECHAT.scenario` 迁移后为死配置，按 C-4 在 acceptance 登记

## T4　`parseCallback`：验签 + 解密　[FR-007][FR-014][INV-5]

- [ ] **红**：`WechatCallbackParseTest` —— ① 本地生成密钥对构造合法 V3 通知（加密 + 签名）⇒ 插件解析结果与原文一致 ② **验签失败 ⇒ 拒绝且不进入解密**（顺序断言）③ 解密失败 ⇒ 拒绝
- [ ] **绿**：实现三步：平台证书验签（`Wechatpay-Signature`/`Timestamp`/`Nonce`/`Serial`）→ APIv3 密钥 AES-256-GCM 解密 `resource` → 转 `ParsedCallback`
- [ ] **绿**：`callbackAckBody()` 返回微信期望的应答（`{"code":"SUCCESS","message":"成功"}` 或失败体）
- [ ] 复核：复用通用端点 `/internal/channels/WECHAT/callback`，**未**新增微信专属 Controller

## T5　本地仿真桩 + 四步全链路　[FR-012][FR-013][SC-005]

- [ ] 建仿真桩（WireMock 或自建测试 Controller），模拟 V3：`/v3/pay/transactions/native`、`/v3/pay/transactions/jsapi`、`/v3/pay/transactions/out-trade-no/{no}`、`/v3/refund/domestic/refunds`、回调通知
- [ ] **红→绿**：全链路用例 —— 下单 → 查询 → 退款 → 回调，**不依赖微信网络**
- [ ] **红→绿**：渠道模态写入 `payment_attempts.extra_json` 的 `channelMode` 键；归属恒读 `channel_code` 列
- [ ] **红→绿**：状态映射用例 —— `USERPAYING`/`NOTPAY` ⇒ `UNKNOWN`；`SUCCESS` ⇒ `SUCCESS`；`CLOSED`/`PAYERROR`/`REFUND` ⇒ `FAILURE`（INV-7）

## T6　全量回归 + L0 文档同步　[FR-011][SC-008]

- [ ] `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿
- [ ] 零改动判据（SC-002）：`git diff --stat` 中 `application` / `api` / `domain` / 内核 **为空**
- [ ] 同步 `docs/architecture/systems/payment-service.md` 渠道清单（WECHAT 支持真实模式）
- [ ] `CHANGELOG.md` 记一笔
- [ ] **ADR 正文不回改**（历史决策留痕）

## T7　起全链路验证　[SC-006][SC-008]

- [ ] `deployment/start-all.sh`（先确认 Docker Desktop Running）
- [ ] `deployment/demo/reset.sh`
- [ ] `deployment/demo/scenario-refund.sh` 通过；演示控制台三层退款单逐层可见
- [ ] ⭐ `deployment/demo/scenario-routing.sh` **必须通过** —— 其 S2/S3/S5/S6 与 `assert_contains "$REGISTERED" "WECHAT"` 是 C-1 裁决的验收证据（WECHAT 删 Adapter 改插件后仍须可路由）
- [ ] 确认 WECHAT 在 `enabled=false` 时**仍注册**（`GET /internal/channels` 显示 `enabled:false`）、MOCK 可路由、**不误触真实扣款**
- [ ] ⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`

## T8　收口

- [ ] 回填 `acceptance.md` 实测结论（**含沙箱实测章节**）
- [ ] 更新 `docs/adr/README.md` 与 `docs/adr/traceability.md`（若新增 ADR）
- [ ] feature 分支 → `--no-ff` 合入 master（本机无 `gh` CLI，走本地 merge + push）
