# Acceptance: 037-channel-gateway-boundary

**对应 Spec**：[spec.md](spec.md)｜**Plan**：[plan.md](plan.md)｜**Tasks**：[tasks.md](tasks.md)
**状态**：✅ 已实测回填（T1~T8 全部完成，2026-09-25）

---

## 验收清单

| # | Success Criterion | 判据 | 实测结论 |
|---|---|---|---|
| SC-001 | 新增渠道改动面 | 只新增插件包 + `application.yml` 一段 + 账本 seed 2 行 + pom 1 个依赖；Payment 域与网关内核零改动 | ✅ **由 039 独立佐证**：WECHAT 整渠道以「新增 `channelgateway/infra/wechat/**` + 配置段 + pom 1 依赖 + 删旧 Adapter」落地，**零内核改动**（039 CHANGELOG 明写「零新增微服务、零新增中间件、零内核改动（SC-002 零改动判据通过）」）。037 自身未新增渠道（非目标） |
| SC-002 | 门面收口 | Payment 侧对 `ChannelRegistry`/`ChannelRouter`/`ChannelPlugin` 引用数 = 0 | ✅ `ServiceBoundaryTest#paymentApplicationAndApiMustNotReachChannelGatewayInternals` 绿（含三重防空转阳性对照：被检主体 > 20 类、被禁目标恰 3 个、网关域确有类在用它们）。白名单 4 → **2**（余 2 条属独立裁决项，非本 Spec 范围） |
| SC-003 | channelNo 唯一性 | `BusinessNos.of(CHANNEL)` 前缀 `CH`；`channel_no` 有 UNIQUE 索引；10k 并发不重复 | ✅ ① `BusinessNosTest#channelNo_前缀与校验`（前缀 `CH`、长度 20~21、跨类型校验必须失败）；② `deployment/schema/03-payment-schema.sql:68` `UNIQUE KEY uk_attempts_channel_no (channel_no)` + 迁移脚本 `037-payment-attempt-channel-no.sql`（含存量回填 `CONCAT('CH', LPAD(id,18,'0'))` 后再收紧 NOT NULL）；③ `BusinessNosTest#channelNo_并发生成不重复` **8 线程 × 1250 = 10k 并发零重复**；④ `PaymentAttemptChannelNoTest` 7/7（必填 / 不可变 / 两次尝试互异 / 退款 attempt 也携带 / rehydrate 不重新铸造） |
| SC-004 | 回调两层 | 报文翻译在渠道域完成；`PaymentNotifyPort` 收到事件含 `channelCode` 且无渠道私有类型 | ✅ `ChannelCallbackHandlerTest` 11/11（四步顺序 + 验签失败不触达 Payment 侧）；`PaymentNotifyPortTest` 12/12（端口定义权归 `payment.application`、只暴露两条操作、两条入向事件都带 `channelCode`、**分量类型全部来自 `common-dto`/JDK**——按 `RecordComponent` 反射逐字段断言不含 `com.payment.channelgateway` / `com.payment.payment` 前缀）；`AlipayCallbackParseTest` 14/14（渠道域完成翻译） |
| SC-005 | 门禁生效 | FR-016 三条 ArchUnit 规则全绿，阳性对照能触发违规 | ✅ `ServiceBoundaryTest` **18/18** 绿。① `paymentApplicationAndApiMustNotReachChannelGatewayInternals`（本 T 新增，含三重阳性对照）；② `paymentAndChannelGatewayMustKeepOneWayDependency`（038 已有 + 本 T 补 `INBOUND_PORT_EXCEPTION`）；③ `dyeContextMustStayInsideChannelGatewayDomain`（本 T 新增，含「网关域确有类在读 `DyeContext`」的可命中性对照）。三条均为否定式规则，全部配了防空转对照——「全绿但什么都没检查」这一形态被显式排除 |

## 回归基线

| 模块 | 基线（037 开工前） | 实测（`./mvnw -B clean test`，2026-09-25） |
|---|---|---|
| payment-service | 367 + 155 + 55 + 28 + 25 全绿 | ✅ **482 / 0F / 0E** |
| common-core | 全绿 | ✅ **70 / 0F / 0E** |
| common-dto | 无测试（纯 DTO） | ✅ 15 / 0F / 0E（`RpcContractTest` 等，由后续 Feature 补齐） |
| ServiceBoundaryTest | 12/12 绿 | ✅ **18 / 0F / 0E**（本 Spec 新增 2 条规则，另 4 条由 038 引入） |
| **全 reactor（18 模块）** | — | ✅ **1112 / 0F / 0E / BUILD SUCCESS** |

**计数口径**：一律以 `./mvnw -B clean test` 为准。`target/surefire-reports/` 会残留**已删除类**的旧 XML
（本轮实测见到 `AlipayNotifyControllerTest`(5) 与 `AlipayNotifyValidationTest`(10) 在删除后仍在列），
直接把目录加总会得到**虚高且含幽灵用例**的数字。

**1112 的来源核对**：1041（T5/T5b/T7 收口时全 reactor）＋ 61（039 微信插件用例，随 `origin/master` 合入）
＋ 14（T6c 新增 `AlipayCallbackParseTest`）＋ 3（T6b 新增 `ChannelPluginMigrationTest`）
− 15（T6d 删除的两个专属端点测试类）＋ 8（T6d 新增 `ChannelPluginCallbackControllerTest`）= **1112** ✓

> **已知环境噪声**：`AccountingVocabularyBoundaryTest` 会报 `.workbuddy/p3-removed-refund-service/**`
> （ArchUnit 扫全仓 `.java` 未排除点目录）。负责人裁定「不处置」，**不计入本 Feature 失败**。
> 本轮另见 `Surefire is going to kill self fork JVM`（测试 JVM 退出时被另一 worktree 争用
> 同一 MySQL/Redis 拖住）——**BUILD SUCCESS 且 0F/0E**，属环境噪声，非缺陷。

## NFR 核对

| NFR | 要求 | 实测 |
|---|---|---|
| **NFR-1** | 只跑单测，不起全链路 | ✅ 全程只跑 `mvnw test`；未起 Docker 栈、未跑 demo 脚本 |
| **NFR-2** | 既有单测断言**零变化**（除已裁决放行者） | ✅ 唯一变更：`PaymentCallbackValidationTest#appIdMismatchRejects` 的 `reason=app_id` → `reason=signature`（**已裁决放行**）。另有两处**非断言**变化：① `AlipaySandboxNotifyScenarioTest` 的应答断言由 `ResponseEntity` 改为 `ChannelCallbackAck`（**同一事实的两层表达**，映射由新增的 `ChannelPluginCallbackControllerTest` 钉住）；② 随删除的 `AlipayNotifyControllerTest` 一并消失的「415」断言（通用端点**刻意不限制 content-type**）。`PaymentCallbackPathParityTest` **断言零变化**；审计币种断言 `currencyCode().isEqualTo("CNY")` **零变化通过**（靠口径合并，见下） |

## 已登记的有意差异 / 残余项

1. **`app_id` 监控维度消失**：身份校验下沉到插件的「①签名/身份」段，与验签同源 ⇒ 拒绝指标由
   `reason=app_id` 归入 `reason=signature`。已裁决放行。
2. **通用端点不限制 content-type**：要同时承载表单协议的支付宝与 JSON 协议的 Stripe，
   故非表单请求不再被 415 拒绝，而是原样交给插件。
3. **审计币种口径合并**：两条拒绝路径原先不一致（支付宝端点写死 `"CNY"`、通用端口写 `null`），
   合并后取**被拒支付单自己的币种**——对 CNY 单与既有记录逐字相同，且任何币种下记录的都是事实。
4. **两处 fixture 字符串仍指向旧路径**（非断言、不影响语义，未改动以守住「最小变更」）：
   `common-core` 的 `AccessLogFilterTest`（样本路径，其断言针对通配 `/internal/channels/**`，
   新旧路径都在排除范围内，**无安全回归**）与 `payment-service` 的 `ChannelCallbackUrlConfigTest`
   （不透明配置值，仅做透传相等断言）。
5. **本 Spec 未新增 ADR**：`docs/adr/README.md` 与 `traceability.md` 无需更新（T8 第 2 项的
   「若新增 ADR」条件不成立）。ADR-0072 / 0073 / 0075 / 0076 已覆盖渠道架构决策；
   ADR 正文按「历史决策留痕」纪律**不回改**（`0076` 中仍记着旧端点路径，属当时的决策记录）。
6. **FR-014 推翻了 038 `ChannelPlugin` 类注释里的「刻意不做强制迁移」**：该注释已在 T8 同步更正，
   并写明两者结论相反、经裁决按 FR-014 执行（避免后来者读到一段与代码事实相反的注释）。

## 验收命令

```bash
# 本 Spec 的验收命令（18 模块全量单测）
./mvnw -B clean test

# 仅本 Spec 触及的三个模块
./mvnw -B clean test -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am
```
