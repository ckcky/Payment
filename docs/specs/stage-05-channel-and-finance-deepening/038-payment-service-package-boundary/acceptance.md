# Acceptance: 038-payment-service-package-boundary

**对应 Spec**：[spec.md](spec.md) ｜ **Plan**：[plan.md](plan.md) ｜ **Tasks**：[tasks.md](tasks.md)
**状态**：待实测回填（T1~T9 完成后填写「实测结论」列）

---

## 验收清单

| # | Success Criterion | 判据 | 实测结论 |
|---|---|---|---|
| SC-001 | `com.payment.refund` 顶层包消灭 | `grep -rn "com\.payment\.refund" payment-service/src/ \| wc -l` = 0；无残留空目录 | 待填 |
| SC-002 | 渠道件 100% 收拢 `channelgateway` | `find channelgateway -name "*.java" \| wc -l` = 40；`com.payment.payment.**` 下 `ChannelPlugin`/`ChannelRegistry`/`ChannelRouter`/`AbstractMockChannelAdapter` 零命中（`ChannelAttemptRecorder` / `ChannelResult` 接口引用除外，须显式列出） | 待填 |
| SC-003 | `PaymentApplication` 扫描包与实际顶层包逐一对应 | 三处注解（`scanBasePackages` / `EnableFeignClients` / `MapperScan`）grep 复核 + 应用能启动 | 待填 |
| SC-004 | 编译通过 | `./mvnw -B -pl payment-service -am compile` 零错误 | 待填 |
| SC-005 | 调用方全量同步 | `grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . \| grep -v "\.workbuddy/" \| wc -l` = 0 | 待填 |
| SC-006 | ArchUnit 三条包级门禁生效 | 三条规则全绿 + 阳性对照能触发违规 | 待填 |
| SC-007 | 全量单测零回归 | 见「回归基线」表；既有断言一行未改 | 待填 |
| SC-008 | 全链路退款三层可见 | `scenario-refund.sh` 通过；演示控制台查库面板 TXRF / PMRF / `payment_attempts`(REFUND) 三层逐层可见 | 待填 |

## 回归基线

> 基线**已实测**（2026-09-25，master @ `ee9f1b3`，命令见下）。执行方只需填「实测」列。

| 模块 | 基线（038 开工前，master @ ee9f1b3，实测） | 实测 |
|---|---|---|
| `common/common-core` | **68** tests，全绿 | 待填 |
| `common/common-dto` | **15** tests，全绿 | 待填 |
| `payment-service` | **348** tests，全绿 | 待填 |
| `deployment/architecture-tests` | **17** tests，16 绿 / **1 红（已知噪声）**；其中 `ServiceBoundaryTest` **12/12 绿** | 待填 |
| `reconciliation-service` | 编译通过（1 处 Feign 路径变更） | 待填 |

⚠️ **已知环境噪声（负责人已裁定「不处置」，不计入本 Feature 失败）**：
`AccountingVocabularyBoundaryTest.accountCodeAndDirectionLiteralsMustBeConfinedToLedgerAndContractEnums`
报 2 条违规，路径均在 `.workbuddy\p3-removed-refund-service\...`（gitignored 的项目数据目录）。
成因：ArchUnit 扫全仓 `.java` 未排除点目录。**不要改测试、不要删 `.workbuddy/`**。
⚠️ 注意：该目录下仍存在 `com/payment/refund/...`，故 SC-001 的 grep **必须限定在 `payment-service/src/`**，
不可对全仓执行，否则必然非零（见 SC-001 判据）。

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
