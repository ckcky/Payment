# Acceptance: 038-payment-service-package-boundary

**对应 Spec**：[spec.md](spec.md) ｜ **Plan**：[plan.md](plan.md) ｜ **Tasks**：[tasks.md](tasks.md)
**状态**：已实测回填（2026-09-25，分支 `feature/038-payment-service-package-boundary`，基线 master @ `5b9ed09`）

---

## 验收清单

| # | Success Criterion | 判据 | 实测结论 |
|---|---|---|---|
| SC-001 | `com.payment.refund` 顶层包消灭 | `grep -rn "com\.payment\.refund" payment-service/src/ \| wc -l` = 0；无残留空目录 | ✅ **0 命中**；`find payment-service/src -type d -empty` = **0**。一级包收敛为 `channelgateway` / `payment` / `posting` |
| SC-002 | 渠道件 100% 收拢 `channelgateway` | `find channelgateway -name "*.java" \| wc -l` = 40；`com.payment.payment.**` 下 `ChannelPlugin`/`ChannelRegistry`/`ChannelRouter`/`AbstractMockChannelAdapter` 零命中（`ChannelAttemptRecorder` / `ChannelResult` 接口引用除外，须显式列出） | ✅ **40**；上述四符号在 `com.payment.payment.**` 下 **0 文件命中**。`ChannelAttemptRecorder` / `ChannelResult`（DIP 端口）位于 `com.payment.channelgateway.application`，**不在** `payment.**`，故无需例外 |
| SC-003 | `PaymentApplication` 扫描包与实际顶层包逐一对应 | 三处注解（`scanBasePackages` / `EnableFeignClients` / `MapperScan`）grep 复核 + 应用能启动 | ✅ 三注解均已含 `channelgateway`、不含 `refund`；`start-all.sh` 10 进程全部就绪（`START_ALL_EXIT=0`），启动日志 `ChannelPluginFactoryLocator … channel plugin factories located: [STRIPE]` 证明 SPI 资源在搬包后仍被定位 |
| SC-004 | 编译通过 | `./mvnw -B -pl payment-service -am compile` 零错误 | ✅ 零错误（全量 reactor 16 模块 `test` 构建成功） |
| SC-005 | 调用方全量同步 | `grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . \| grep -v "\.workbuddy/" \| wc -l` = 0 | ✅ **0 命中**（6 文件 / 8 处全部同步：`reconciliation` Feign、`mock-channel-web` 代理 + `demo.html`、`e2e-tests` `Api`、`scenario-refund.sh` ×2、`scenario-routing.sh`、performance 脚本） |
| SC-006 | ArchUnit 三条包级门禁生效 | 三条规则全绿 + 阳性对照能触发违规 | ✅ `ServiceBoundaryTest` **16/16 绿**（12 既有 + 4 新增）；4 条新规则**均内联阳性对照**（断言被检包类数 > 0 / 被禁目标真实存在），空转即红 |
| SC-007 | 全量单测零回归 | 见「回归基线」表；既有断言一行未改 | ✅ 见下表；测试文件 **65 → 65（零删除）**，全部改动行仅 `package` / `import` / 内联 FQN，**无一行断言变更** |
| SC-008 | 全链路退款三层可见 | `scenario-refund.sh` 通过；演示控制台查库面板 TXRF / PMRF / `payment_attempts`(REFUND) 三层逐层可见 | ✅ `scenario-refund.sh` **全部断言通过**（`SCENARIO_EXIT=0`），含三条 demo 追踪断言（TXRF / PMRF / REFUND attempt 均 `True`）；DB 侧独立复核三层各 2 行；演示控制台 `/demo` HTTP 200。明细见下「SC-008 实测明细」 |

## 回归基线

> 基线为 spec 交付时记录的「已实测」值。执行期复测发现 2 项不符，**如实登记不改判据**（见下方勘误）。

| 模块 | 基线（038 开工前，master @ `ee9f1b3`） | 实测（2026-09-25，`feature/038`） |
|---|---|---|
| `common/common-core` | **68** tests，全绿 | ✅ **68**，全绿 |
| `common/common-dto` | **15** tests，全绿 | ⚠️ **6**，全绿（基线笔误，见勘误 1） |
| `payment-service` | **348** tests，全绿 | ⚠️ **370**，全绿（基线不可复现，见勘误 2） |
| `deployment/architecture-tests` | **17** tests，16 绿 / **1 红（已知噪声）**；其中 `ServiceBoundaryTest` **12/12 绿** | ✅ **21** tests 全绿；`ServiceBoundaryTest` **16/16 绿**（+4 新增）；基线标注的「1 红」本次**未复现**（见勘误 3） |
| `reconciliation-service` | 编译通过（1 处 Feign 路径变更） | ✅ 编译通过；`RefundFactsFeignClient` 路径已同步 |

**复测命令**（口径与基线一致）：

```bash
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
```

### 基线勘误（执行期实测，如实登记）

1. **`common-dto` 15 → 6**：master 上该模块仅 6 个测试（`RpcContractTest`）。`15` 疑为把 037 计划的 `ChannelContractTest`（9 个）计入（6 + 9 = 15）。
2. **`payment-service` 348 → 370**：`348` 在仓库与本机日志中**无任何产物可追溯**，实测 master 与 038 分支均为 **370**。**不构成回归**，依据三条：
   - ① 测试文件 **65 → 65**，`git diff -M --name-status` 无 `D`（**零删除**）；
   - ② 全部改动行仅为 `package` / `import` / 内联 FQN 重写，**无一行断言变更**；
   - ③ 370 全绿、`Failures: 0, Errors: 0`。
3. **`architecture-tests` 17 → 21**：增量 +4 = 本 Feature 新增的四条包级门禁。基线标注的「1 红（已知噪声）」**未复现**——该噪声源目录（`.workbuddy/` 下的历史服务快照）在本机已不存在，`AccountingVocabularyBoundaryTest` 2/2 绿。

### 环境前置修正（非本 Feature 代码问题，T8 执行期发现并处置）

首次 T8 在 `POST /orders/{no}/payments` 报 500：`Unknown column 'transaction_id'`。根因是**本机 MySQL 数据卷陈旧**——卷内 schema 落后于代码（`payment.payments` 仍是 `transaction_no` 时代结构，且残留已退役的 `refund` 库）。

举证与 038 无关：① `git status` 显示 038 **未改动任何 `.sql` / mapper XML / `PaymentEntity`**；② `deployment/schema/03-payment-schema.sql` 与未改动的 `PaymentEntity.transactionId` 均要求 `transaction_id`（master 同样如此）；③ 卷内残留 pre-015 的 `refund` 库。

处置：删除 `deployment_mysql-data` 数据卷重建（initdb 建空库 → `reset.sh` 重放全量 schema）。**未改动仓库内任何 schema 脚本**。

## 验收命令

```bash
# 结构断言
grep -rn "com\.payment\.refund" payment-service/src/ | wc -l                                    # 期望 0
grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . | grep -v "\.workbuddy/" | wc -l   # 期望 0
find payment-service/src/main/java/com/payment/channelgateway -name "*.java" | wc -l            # 期望 40

# 编译 + 单测
./mvnw -B -pl payment-service -am compile
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
./mvnw -B -pl deployment/architecture-tests -am test

# 全链路（T8）
deployment/start-all.sh
deployment/demo/reset.sh
deployment/demo/scenario-refund.sh
```

### SC-008 实测明细（2026-09-25）

`deployment/start-all.sh` → `deployment/demo/reset.sh` → `deployment/demo/scenario-refund.sh` 三段全部 `EXIT=0`。

关键断言（改造后端点 `/internal/payments/refunds/**` 全部生效）：

```
PASS: 选渠道建支付单 (== 201)
PASS: 支付 → SUCCEEDED
PASS: 退款受理 (== 200)  /  退款单受理（状态 PROCESSING）
PASS: 异步回调收敛 → 退款 SUCCEEDED（PMRF=PMRF229154227346427904）
PASS: payment 侧 refunds.transaction_refund_no == TXRF（双号互记）
PASS: 第二笔部分退款受理 (== 200)
PASS: 超额退款 → 409 AMOUNT_INVARIANT_VIOLATION（refundable=2900）
PASS: 第二笔退款收敛 SUCCEEDED（PMRF=PMRF229154240030003201）
PASS: 订单状态 → PARTIALLY_REFUNDED（7000/9900 已退）
PASS: demo 追踪含交易层退款单 transaction_refunds（TXRF）且有数据 (== True)
PASS: demo 追踪含支付层退款单 refunds（PMRF）且有数据 (== True)
PASS: demo 追踪含渠道层退款尝试 payment_attempts(REFUND) 且有数据 (== True)
```

DB 侧独立复核（`payment-mysql` 直查，三层各 2 行、全部 `SUCCEEDED`）：

| 层 | 表 | 记录 |
|---|---|---|
| 交易层 | `order.transaction_refunds` | `TXRF229154227145101312`（5000）、`TXRF229154239904174081`（2000） |
| 支付层 | `payment.refunds` | `PMRF229154227346427904` → TXRF…1312、`PMRF229154240030003201` → TXRF…4081 |
| 渠道层 | `payment.payment_attempts`（`attempt_type='REFUND'`） | `PM229154207343792128` × 2，`channel_code=ALIPAY` |

演示控制台 `http://localhost:8091/demo` HTTP **200**。

## 人工演示步骤（SC-008）

1. Docker Desktop 处于 Running。
2. `deployment/start-all.sh`；首次或需干净数据时 `deployment/demo/reset.sh`。
3. 演示控制台 http://localhost:8091 → 下单 → 支付 → 发起退款。
4. 「查库」面板应见三段退款记录，逐层命名：
   - 交易层 `transaction_refunds`（TXRF）
   - 支付层 `refunds`（PMRF）
   - 渠道层 `payment_attempts`（REFUND）
5. `deployment/demo/scenario-refund.sh` 的三层断言全过。

⚠️ 起服务时**必须** `env -u SERVER__PORT -u SERVER__HOST`（工具会注入 `SERVER__PORT=61628`，
Spring Boot 会误当成 `server.port`，报「Port 61628 was already in use」）。

## 已知技术债（本 Feature 登记，不修复）

| # | 债务 | 位置 | 留给 |
|---|---|---|---|
| TD-1 | 两份同名 `LedgerPostingGateway`（记账口径重复） | `payment/application/` 与 `payment/application/refund/` | 后续 Feature |
| TD-2 | 两份退款应用服务职责重叠 | `PaymentRefundService` 与 `RefundApplicationService` | 后续 Feature |
| TD-3 | `channelgateway` 反向依赖 `payment.application`（`RefundResultListener` 等）若 T6 规则①报红 | `channelgateway/application/` | 037 T5（`PaymentNotifyPort` 修正定义权） |
| TD-4 | `channelgateway` 的 3 个回调 Controller 反向依赖 `payment` 接入/应用层：`AlipayNotifyController`、`ChannelCallbackController`、`ChannelPluginCallbackController`（FR-009 ① 的 FQN 白名单前 3 条） | `channelgateway/api/` | 037（回调分层） |
| TD-5 | `channelgateway.web.ChannelCallbackSignatureFilter` 反向依赖 `payment.web.CachedBodyHttpServletRequest`（M-3 放宽可见性后形成，FR-009 ① 白名单第 4 条） | `channelgateway/web/` ← `payment/web/` | 037（回调分层） |

> TD-4 / TD-5 在 `ServiceBoundaryTest.LEGACY_GATEWAY_TO_PAYMENT_DEPENDENCIES` 中**逐条 FQN 白名单化**（非静默放宽规则）：新增任何一条反向依赖都会立即红。
