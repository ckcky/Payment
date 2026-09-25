# Tasks: 038-payment-service-package-boundary

**对应 Spec**：[spec.md](spec.md) ｜ **Plan**：[plan.md](plan.md)
**推进方式**：**串行**执行 T1→T8（⚠️ 批量改同一目录 MUST NOT 并行，后写会覆盖先写且仍报 Success）
**每步收口动作**：改完 → 编译 → grep 复核 → 才进下一步。

**验证命令（每步结束跑）**：

```bash
./mvnw -B -pl payment-service -am compile
```

---

## T1　建 `channelgateway` 包，迁 40 个渠道主源码文件　[FR-001]

根目录：`payment-service/src/main/java/com/payment/`

- [ ] 建目录：`channelgateway/api/dto`、`channelgateway/application/spi`、`channelgateway/infra/alipay`、`channelgateway/infra/stripe`、`channelgateway/infra/config`、`channelgateway/web`
- [ ] `git mv` 4 个 Controller：`payment/api/` → `channelgateway/api/`（`AlipayNotifyController`、`ChannelAdminController`、`ChannelCallbackController`、`ChannelPluginCallbackController`）
- [ ] `git mv` `payment/api/dto/ChannelCallbackRequest.java` → `channelgateway/api/dto/`
- [ ] `git mv` `payment/application/channel/*.java`（11 个）→ `channelgateway/application/`
- [ ] `git mv` `payment/application/channel/spi/*.java`（6 个）→ `channelgateway/application/spi/`
- [ ] `git mv` `payment/infra/channel/*.java`（8 个）→ `channelgateway/infra/`
- [ ] `git mv` `payment/infra/channel/alipay/*.java`（2 个）→ `channelgateway/infra/alipay/`
- [ ] `git mv` `payment/infra/channel/stripe/*.java`（5 个）→ `channelgateway/infra/stripe/`
- [ ] `git mv` `payment/infra/config/{AlipaySandboxProperties,RoutingProperties}.java` → `channelgateway/infra/config/`
- [ ] `git mv` `payment/web/ChannelCallbackSignatureFilter.java` → `channelgateway/web/`
- [ ] 按 spec §3.1 **长前缀优先**顺序重写包名（9 条规则，顺序不可颠倒）
- [ ] 编译，逐个补齐同包引用缺失的 import（⚠️ R3，036 已踩坑）
- [ ] 清理残留空目录：`find payment-service/src/main/java/com/payment -type d -empty -delete`
- [ ] 复核：`find channelgateway -name "*.java" | wc -l` = **40**

## T2　`PaymentApplication` 扫描包登记　[FR-002][INV-4]

文件：`payment-service/src/main/java/com/payment/payment/PaymentApplication.java`

- [ ] `scanBasePackages` 改为 `{"com.payment.payment", "com.payment.channelgateway", "com.payment.posting"}`（**移除** `com.payment.refund`）
- [ ] `EnableFeignClients(basePackages = ...)` 改为 `{"com.payment.payment", "com.payment.channelgateway"}`
- [ ] `MapperScan` 移除 `com.payment.refund.infra.persistence`，改为 `com.payment.payment.infra.persistence.refund`
- [ ] 复核：`grep -n "scanBasePackages\|EnableFeignClients\|MapperScan" PaymentApplication.java` 三处均已含 `channelgateway` 且不含 `refund`
- [ ] ⚠️ **R1 最高风险**：漏登记编译不报错、启动即崩，必须 grep 复核

## T3　迁渠道测试 8 个　[FR-001]

根目录：`payment-service/src/test/java/com/payment/`

- [ ] `git mv` `payment/application/channel/ChannelAttemptRecorderContractTest.java` → `channelgateway/application/`
- [ ] `git mv` `payment/infra/channel/{AlipaySandboxChargeTest,ConfiguredChannelRouterTest,DyeNotAffectingRoutingTest,MockChannelAdapterScenarioTest}.java` → `channelgateway/infra/`
- [ ] `git mv` `payment/infra/channel/alipay/{AlipayAmountConversionTest,AlipayDualModeTest,AlipayNotifySignatureVerificationTest}.java` → `channelgateway/infra/alipay/`
- [ ] 重写包名 + 补 import
- [ ] `./mvnw -B -pl payment-service -am test-compile` 通过

## T4　消灭 `com.payment.refund`（迁 33 主 + 10 测试）　[FR-003][FR-004]

- [ ] `git mv` `refund/api/*.java`（4 个）→ `payment/api/`（`RefundController`、`RefundFactsController`、`RefundResponse`、`ResolveRefundRequest`）
- [ ] `git mv` `refund/api/dto/RefundFactResponse.java` → `payment/api/dto/`
- [ ] `git mv` `refund/application/*.java`（10 个）→ `payment/application/refund/`
- [ ] `git mv` `refund/domain/*.java`（6 个）→ `payment/domain/`
- [ ] `git mv` `refund/infra/InMemoryRefundRepository.java` → `payment/infra/`
- [ ] `git mv` `refund/infra/client/*.java`（4 个）→ `payment/infra/client/`
- [ ] `git mv` `refund/infra/persistence/refund/*.java`（6 个）→ `payment/infra/persistence/refund/`
- [ ] 迁 10 个测试文件（映射见 spec §3.2 测试表）
- [ ] 按 spec §3.2 重写包名（7 条规则）
- [ ] 编译并补齐 import
- [ ] 删除空的 `com/payment/refund` 目录树
- [ ] 复核：`grep -rn "com\.payment\.refund" payment-service/src/ | wc -l` = **0**（SC-001）

## T5　退款 HTTP 入口收口 + 6 个调用方同步　[FR-005][FR-006][FR-007]

- [ ] 合并 `RefundController` + `RefundFactsController` → 单个 `com.payment.payment.api.RefundController`，`@RequestMapping("/internal/payments/refunds")`
- [ ] 四条端点改为：`GET /{refundNo}`、`POST /{refundNo}/resolve`、`POST /{refundNo}/channel-callback`、`GET /confirmed-facts`
- [ ] 改 `reconciliation-service/.../infra/client/RefundFactsFeignClient.java:22` → `/internal/payments/refunds/confirmed-facts`
- [ ] 改 `deployment/mock-channel-web/.../web/RefundCallbackProxy.java:73` → `/internal/payments/refunds/{refundNo}/channel-callback`
- [ ] 改 `deployment/e2e-tests/.../support/Api.java:107` → `/internal/payments/refunds/{refundNo}`
- [ ] 改 `deployment/demo/scenario-refund.sh:69,97`
- [ ] 改 `deployment/demo/scenario-routing.sh:188`
- [ ] 改 `deployment/mock-channel-web/src/main/resources/static/demo.html:774`
- [ ] 复核：`grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . | grep -v "\.workbuddy/" | wc -l` = **0**（SC-005）

## T6　ArchUnit 三条包级门禁　[FR-009][INV-1][INV-2][INV-3]

模块：`deployment/architecture-tests`

- [ ] **先落阳性对照**（故意违规能触发），确认规则不空转
- [ ] 规则①：`com.payment.payment.**` 与 `com.payment.channelgateway.**` 单向依赖——`channelgateway` 只允许依赖 `com.payment.common.**` 与自身；**显式白名单**：`com.payment.payment.domain.PaymentAttempt`、`ChannelAttemptRecorder` 相关既有 DIP
- [ ] 规则②：`com.payment.channelgateway.**` 内 MUST NOT 引用 `com.payment.refund`
- [ ] 规则③：渠道插件 MUST 位于 `com.payment.channelgateway.infra.<channel>` 包下
- [ ] `./mvnw -B -pl deployment/architecture-tests -am test` 全绿
- [ ] ⚠️ 若既有代码违规：**登记为技术债并在 acceptance.md 记录**，**不得静默放宽规则**

## T7　全量单测零回归 + L0 文档同步　[FR-010][FR-011]

- [ ] `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿
- [ ] 核对 `acceptance.md` 回归基线表，逐项填「实测」列
- [ ] 同步 `docs/architecture/systems/payment-service.md` 中 `/internal/refunds/**` 端点清单
- [ ] `CHANGELOG.md` 记一笔破坏性变更（退款端点前缀）
- [ ] **ADR 正文不回改**（历史决策留痕）

## T8　起全链路验证（用户要求）　[SC-008]

- [ ] `deployment/start-all.sh` 起容器（先确认 Docker Desktop Running）
- [ ] `deployment/demo/reset.sh` 重建 schema（⚠️ 会 TRUNCATE 演示数据）
- [ ] `deployment/demo/scenario-refund.sh` 跑退款场景
- [ ] 断言三层可见：交易层 `transaction_refunds`(TXRF) / 支付层 `refunds`(PMRF) / 渠道层 `payment_attempts`(REFUND)
- [ ] 演示控制台（:8091）「查库」面板三层逐层可见，与改造前一致
- [ ] ⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`

## T9　收口

- [ ] 回填 `acceptance.md` 实测结论
- [ ] 更新 `docs/adr/README.md` 与 `docs/adr/traceability.md`（若新增 ADR）
- [ ] feature 分支 → `--no-ff` 合入 master（本机无 `gh` CLI，走本地 merge + push）
