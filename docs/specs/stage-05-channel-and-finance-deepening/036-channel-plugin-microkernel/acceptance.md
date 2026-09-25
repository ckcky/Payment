# 036-channel-plugin-microkernel — Acceptance

> **Status**: 已通过（代码于 `b73f960` 合入 master 并稳定运行）
> **Spec**: [spec.md](./spec.md)　**Plan**: [plan.md](./plan.md)　**Tasks**: [tasks.md](./tasks.md)
> **复核基线**：`master` @ `d61185a`（复核时间 2026-09-25）

---

## 1. 验收场景实测

| ID | 场景 | 判据 | 实测结果 |
|---|---|---|---|
| SC-001 | 接 Stripe 时 `application/` 只新增内核 `spi/` 包 | 内核包内无渠道特有代码 | ✅ `spi/` 6 类全部渠道无关 |
| SC-002 | 通用回调端点 | 路径 `/internal/channels/{channelCode}/callback` | ✅ `ChannelPluginCallbackController.java:109` |
| SC-003 | SDK 收口 | `import com.stripe` 仅命中 `StripeSdkGateway` | ✅ 全仓仅 1 个类命中 |
| SC-004 | 凭据门控 | `enabled=true` 且缺 `webhook-secret` ⇒ 启动期拒绝启动 | ✅ `StripeSandboxProperties` `@PostConstruct` 强校验 |
| SC-005 | 模态门控 | 染色 SANDBOX + `enabled=false` ⇒ 400，不回落 mock | ✅ `requireRealModeIfSandboxRequested()` |
| SC-006 | ArchUnit 两条规则绿 + 阳性对照 | `ServiceBoundaryTest` | ✅ R1 SDK 收口 / R2 内核不依赖具体渠道 |
| SC-007 | STRIPE 账本账户存在 | seed 含 owner=STRIPE 两行 | ✅ 两个 schema 文件各 3 处命中（含 id 15/16） |
| SC-008 | Stripe 联调 | `stripe-listen.sh` + 测试卡闭环 | ✅ 脚本已交付；测试卡 `4242...`（成功）/ `4000...0002`（被拒） |

## 2. 复核命令与实测输出（可重跑）

```bash
# [1] 模板方法三个 final 入口
grep -n "public final ChannelResult" \
  payment-service/src/main/java/com/payment/payment/application/channel/spi/AbstractChannelPlugin.java
# 实测：79 charge / 92 refund / 105 queryStatus —— 三处

# [2] SDK 收口（期望只有 StripeSdkGateway.java）
grep -rln "import com\.stripe" payment-service/src/main/java --include=*.java
# 实测：StripeSdkGateway.java —— 仅 1 个

# [3] 账本渠道账户
grep -c "STRIPE" deployment/schema/09-ledger-schema.sql \
                 deployment/schema/031-ledger-accounting-foundation.sql
# 实测：各 3 处

# [4] 通用回调端点
grep -n "internal/channels" \
  payment-service/src/main/java/com/payment/payment/api/ChannelPluginCallbackController.java
# 实测：:109 @PostMapping("/internal/channels/{channelCode}/callback")
```

## 3. 遗留项与目标验收的关系

| 遗留 | 是否在 036 验收范围内 | 承接 |
|---|---|---|
| `AlipayNotifyController` 未删（双轨） | ❌ 不在——036 只要求「新增渠道走通用端点」，存量端点保留兼容 | 037 T6 |
| 存量 4 家未迁 `AbstractChannelPlugin` | ❌ 不在——036 的判据是「接新渠道内核零改动」，不是「全部渠道已迁移」 | 037 T6 |
| ArchUnit 硬编码渠道名 | ⚠️ 已知脆弱，但 R2 在 036 范围内有效 | 038 T6 |
| `channelgateway` 包未建 | ❌ 不在 | 038 T1 |

> **说明**：036 的验收判据是「**接新渠道不改内核**」，不是「渠道域已完全重构」。
> 后者拆分为 037（门面 + 存量迁移）与 038（包级边界），避免单个 Feature 改动面过大。

## 4. 后续 Feature 的准入检查

执行 037 / 038 / 039 前，本 Feature 的下列事实 MUST 仍然成立（作为前置基线）：

- [ ] `AbstractChannelPlugin` 三入口仍为 `final`
- [ ] `com.stripe` 仍只被 `StripeSdkGateway` 引用
- [ ] `/internal/channels/{channelCode}/callback` 仍存在
- [ ] STRIPE 账本账户 id 15/16 仍在 seed 中
