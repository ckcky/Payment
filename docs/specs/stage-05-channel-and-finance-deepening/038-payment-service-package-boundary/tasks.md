# Tasks: 038-payment-service-package-boundary

**对应 Spec**：[spec.md](spec.md) ｜ **Plan**：[plan.md](plan.md)
**推进方式**：**串行**执行 T1→T8（⚠️ 批量改同一目录 MUST NOT 并行，后写会覆盖先写且仍报 Success）
**每步收口动作**：改完 → 编译 → grep 复核 → 才进下一步。

**状态**：T1~T8 全部完成并实测通过（2026-09-25，分支 `feature/038-payment-service-package-boundary`）。实测结论见 [acceptance.md](acceptance.md)。

**验证命令（每步结束跑）**：

```bash
./mvnw -B -pl payment-service -am compile
```

---

## T1　建 `channelgateway` 包，迁 40 个渠道主源码文件　[FR-001]

根目录：`payment-service/src/main/java/com/payment/`

- [x] 建目录：`channelgateway/api/dto`、`channelgateway/application/spi`、`channelgateway/infra/alipay`、`channelgateway/infra/stripe`、`channelgateway/infra/config`、`channelgateway/web`
- [x] `git mv` 4 个 Controller：`payment/api/` → `channelgateway/api/`（`AlipayNotifyController`、`ChannelAdminController`、`ChannelCallbackController`、`ChannelPluginCallbackController`）
- [x] `git mv` `payment/api/dto/ChannelCallbackRequest.java` → `channelgateway/api/dto/`
- [x] `git mv` `payment/application/channel/*.java`（11 个）→ `channelgateway/application/`
- [x] `git mv` `payment/application/channel/spi/*.java`（6 个）→ `channelgateway/application/spi/`
- [x] `git mv` `payment/infra/channel/*.java`（8 个）→ `channelgateway/infra/`
- [x] `git mv` `payment/infra/channel/alipay/*.java`（2 个）→ `channelgateway/infra/alipay/`
- [x] `git mv` `payment/infra/channel/stripe/*.java`（5 个）→ `channelgateway/infra/stripe/`
- [x] `git mv` `payment/infra/config/{AlipaySandboxProperties,RoutingProperties}.java` → `channelgateway/infra/config/`
- [x] `git mv` `payment/web/ChannelCallbackSignatureFilter.java` → `channelgateway/web/`
- [x] 按 spec §3.1 **长前缀优先**顺序重写包名（9 条规则，顺序不可颠倒）
- [x] 编译，逐个补齐同包引用缺失的 import（⚠️ R3，036 已踩坑）
- [x] 清理残留空目录：`find payment-service/src/main/java/com/payment -type d -empty -delete`
- [x] 复核：`find channelgateway -name "*.java" | wc -l` = **40** ✅

> 实现期修订 **M-1**（5 个 `common-dto` 渠道契约类包名错位）、**M-3**（两处同包可见性放宽为 `public`）已按负责人裁决执行并回写 spec §3.5。

## T2　`PaymentApplication` 扫描包登记　[FR-002][INV-4]

文件：`payment-service/src/main/java/com/payment/payment/PaymentApplication.java`

- [x] `scanBasePackages` 改为 `{"com.payment.payment", "com.payment.channelgateway", "com.payment.posting"}`（**移除** `com.payment.refund`）
- [x] `EnableFeignClients(basePackages = ...)` 改为 `{"com.payment.payment", "com.payment.channelgateway"}`
- [x] `MapperScan` 移除 `com.payment.refund.infra.persistence`，改为 `com.payment.payment.infra.persistence.refund`
- [x] 复核：`grep -n "scanBasePackages\|EnableFeignClients\|MapperScan" PaymentApplication.java` 三处均已含 `channelgateway` 且不含 `refund`
- [x] ⚠️ **R1 最高风险**：漏登记编译不报错、启动即崩，必须 grep 复核 ✅ 并由 T8 实跑验证（10 进程全起，SPI 定位 `[STRIPE]`）

## T3　迁渠道测试 8 个　[FR-001]

根目录：`payment-service/src/test/java/com/payment/`

- [x] `git mv` `payment/application/channel/ChannelAttemptRecorderContractTest.java` → `channelgateway/application/`
- [x] `git mv` `payment/infra/channel/{AlipaySandboxChargeTest,ConfiguredChannelRouterTest,DyeNotAffectingRoutingTest,MockChannelAdapterScenarioTest}.java` → `channelgateway/infra/`
- [x] `git mv` `payment/infra/channel/alipay/{AlipayAmountConversionTest,AlipayDualModeTest,AlipayNotifySignatureVerificationTest}.java` → `channelgateway/infra/alipay/`
- [x] 重写包名 + 补 import
- [x] `./mvnw -B -pl payment-service -am test-compile` 通过

## T4　消灭 `com.payment.refund`（迁 32 主 + 10 测试）　[FR-003][FR-004]

- [x] `git mv` `refund/api/*.java`（4 个）→ `payment/api/`（`RefundController`、`RefundFactsController`、`RefundResponse`、`ResolveRefundRequest`）
- [x] `git mv` `refund/api/dto/RefundFactResponse.java` → `payment/api/dto/`
- [x] `git mv` `refund/application/*.java`（10 个）→ `payment/application/refund/`
- [x] `git mv` `refund/domain/*.java`（6 个）→ `payment/domain/`
- [x] `git mv` `refund/infra/InMemoryRefundRepository.java` → `payment/infra/`
- [x] `git mv` `refund/infra/client/*.java`（4 个）→ `payment/infra/client/`
- [x] `git mv` `refund/infra/persistence/refund/*.java`（6 个）→ `payment/infra/persistence/refund/`
- [x] 迁 10 个测试文件（映射见 spec §3.2 测试表）
- [x] 按 spec §3.2 重写包名（7 条规则）
- [x] 编译并补齐 import
- [x] 删除空的 `com/payment/refund` 目录树
- [x] 复核：`grep -rn "com\.payment\.refund" payment-service/src/ | wc -l` = **0**（SC-001）✅

> 实现期修订 **M-2**：`refund/infra/client/LedgerFeignClient` 与既有同名类冲突，按 §3.2 自身原则重命名为 `RefundLedgerFeignClient`（`contextId=refundLedgerClient` 不变），已回写 spec §3.5。

## T5　退款 HTTP 入口收口 + 6 个调用方同步　[FR-005][FR-006][FR-007]

- [x] 合并 `RefundController` + `RefundFactsController` → 单个 `com.payment.payment.api.RefundController`，`@RequestMapping("/internal/payments/refunds")`
- [x] 四条端点改为：`GET /{refundNo}`、`POST /{refundNo}/resolve`、`POST /{refundNo}/channel-callback`、`GET /confirmed-facts`
- [x] 改 `reconciliation-service/.../infra/client/RefundFactsFeignClient.java:22` → `/internal/payments/refunds/confirmed-facts`
- [x] 改 `deployment/mock-channel-web/.../web/RefundCallbackProxy.java:73` → `/internal/payments/refunds/{refundNo}/channel-callback`
- [x] 改 `deployment/e2e-tests/.../support/Api.java:107` → `/internal/payments/refunds/{refundNo}`
- [x] 改 `deployment/demo/scenario-refund.sh:69,97`
- [x] 改 `deployment/demo/scenario-routing.sh:188`
- [x] 改 `deployment/mock-channel-web/src/main/resources/static/demo.html:774`
- [x] 复核：`grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . | grep -v "\.workbuddy/" | wc -l` = **0**（SC-005）✅

> 另同步 `deployment/performance/*.js`（2 个压测脚本的退款端点路径），同属 §3.4 的调用方收口范围。

## T6　ArchUnit 三条包级门禁　[FR-009][INV-1][INV-2][INV-3]

模块：`deployment/architecture-tests`

- [x] **先落阳性对照**（故意违规能触发），确认规则不空转 ✅ 四条新规则均内联阳性对照（断言被检包类数 > 0 / 被禁目标真实存在），空转即红
- [x] 规则①：`com.payment.payment.**` 与 `com.payment.channelgateway.**` 单向依赖——`channelgateway` 只允许依赖 `com.payment.common.**` 与自身；**显式白名单**：`com.payment.payment.domain.PaymentAttempt`、`ChannelAttemptRecorder` 相关既有 DIP
- [x] 规则②：`com.payment.channelgateway.**` 内 MUST NOT 引用 `com.payment.refund`
- [x] 规则③：渠道插件 MUST 位于 `com.payment.channelgateway.infra.<channel>` 包下
- [x] `./mvnw -B -pl deployment/architecture-tests -am test` 全绿 ✅ `ServiceBoundaryTest` **16/16**，全模块 **21/21**
- [x] ⚠️ 若既有代码违规：**登记为技术债并在 acceptance.md 记录**，**不得静默放宽规则** ✅ 4 个既有反向依赖类逐条 FQN 白名单化 → 技术债 **TD-4 / TD-5**

> 另按 spec 038 要求补第 4 条 **覆盖修补**规则 `channelGatewayMustNotDependOnOtherServicesAtCompileTime`——渠道件搬出 `com.payment.payment..` 后会脱离既有跨服务门禁（假绿），必须单独覆盖。

## T7　全量单测零回归 + L0 文档同步　[FR-010][FR-011]

- [x] `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿 ✅（common-core 68 / common-dto 6 / payment-service 370 / architecture-tests 21，`Failures: 0, Errors: 0`）
- [x] 核对 `acceptance.md` 回归基线表，逐项填「实测」列 ✅ 含 3 项基线勘误如实登记
- [x] 同步 `docs/architecture/systems/payment-service.md` 中 `/internal/refunds/**` 端点清单
- [x] `CHANGELOG.md` 记一笔破坏性变更（退款端点前缀）
- [x] **ADR 正文不回改**（历史决策留痕）✅ 本 Feature **未新增 ADR**，故 `docs/adr/README.md` / `traceability.md` 无需变更

> 另同步 `docs/architecture/systems/reconciliation-service.md`（退款事实端点 + Controller 链接）与 `docs/architecture/technical-solution.md`（服务表退款行 + 模块树）；`deployment/docs-lint.py` 除规则 9 外 **15/16 PASS**。
> 契约资产 `deployment/architecture-tests/src/test/resources/rpc-edges.txt` 同步 `payment -> ledger` 的客户端类标签（**边未变**，仅类重命名）。

## T8　起全链路验证（用户要求）　[SC-008]

- [x] `deployment/start-all.sh` 起容器（先确认 Docker Desktop Running）✅ `START_ALL_EXIT=0`，10 进程全部就绪
- [x] `deployment/demo/reset.sh` 重建 schema（⚠️ 会 TRUNCATE 演示数据）✅ `RESET_EXIT=0`
- [x] `deployment/demo/scenario-refund.sh` 跑退款场景 ✅ `SCENARIO_EXIT=0`，全部断言通过
- [x] 断言三层可见：交易层 `transaction_refunds`(TXRF) / 支付层 `refunds`(PMRF) / 渠道层 `payment_attempts`(REFUND) ✅ 三条 demo 追踪断言均 `True`；DB 直查三层各 2 行、全部 `SUCCEEDED`
- [x] 演示控制台（:8091）「查库」面板三层逐层可见，与改造前一致 ✅ `/demo` HTTP 200，查库面板数据来自同一 `/demo/trace` 端点（已由脚本断言）
- [x] ⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`

> ⚠️ 执行期发现**本机 MySQL 数据卷陈旧**（schema 落后于代码，`payment.payments` 仍为 `transaction_no` 时代结构），首次 T8 因此报 500。已删除 `deployment_mysql-data` 卷重建；**未改动仓库内任何 schema 脚本**。举证与处置详见 [acceptance.md](acceptance.md)「环境前置修正」。

## T9　收口

- [x] 回填 `acceptance.md` 实测结论 ✅
- [x] 更新 `docs/adr/README.md` 与 `docs/adr/traceability.md`（若新增 ADR）✅ 本 Feature 未新增 ADR，无需变更
- [x] feature 分支 → `--no-ff` 合入 master（本机无 `gh` CLI，走本地 merge + push）
