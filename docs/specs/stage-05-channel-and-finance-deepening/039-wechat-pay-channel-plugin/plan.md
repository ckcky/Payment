# Plan: 039-wechat-pay-channel-plugin

**对应 Spec**：[spec.md](spec.md)
**推进方式**：TDD（红 → 绿 → 重构）+ 仿真桩驱动
**测试策略**：单测为主；**无沙箱可联调**，用「签名金标准 + 本地仿真桩」替代；收尾起全链路跑 demo 场景

---

## 1. 目标形态

```
com.payment.channelgateway.infra.wechat/          （038 已合入 master @ 0a81b29，落点确定）
├── WechatChannelPlugin.java          extends AbstractChannelPlugin  —— 只填差异
├── WechatChannelPluginFactory.java   Spring @Component 注册（照 Stripe，不用 ServiceLoader）
├── WechatGateway.java                渠道端口（领域侧契约）
├── WechatSdkGateway.java             V3 SDK 封装（签名 / HTTP / 验签 / 解密）
└── WechatPayProperties.java          env 注入 + enabled 门控 + 启动期强校验

同批删除：com.payment.channelgateway.infra.WechatChannelAdapter（同 channelCode 不可两路注册）
```

**与 Stripe 插件同构**（`infra/channel/stripe/` 五件套一一对应）——这是刻意的设计复用，
不是巧合：Stripe 插件已经把「插件该长什么样」跑通了，微信只需照形填空。

**调用链**：

```
Payment 域 → ChannelGateway(037) → ChannelRegistry → WechatChannelPlugin(AbstractChannelPlugin)
                                                        ├─ MOCK    → 内核统一 mock（插件不写）
                                                        └─ SANDBOX → doRealCharge/Refund/Query → WechatSdkGateway → 微信 V3
微信回调 → POST /internal/channels/WECHAT/callback → WechatChannelPlugin.parseCallback（验签+解密）→ Payment
```

---

## 2. 关键决策与理由

| # | 决策 | 理由 |
|---|---|---|
| D1 | **不做沙箱接入**，改用三层替代验证 | V3 沙箱实测 404；V2 沙箱仅支持付款码支付、官方明言不支持下单接口（spec §1.2）。做沙箱 = 做一套用不上的 V2 代码 |
| D2 | 插件包五件套与 Stripe **同构** | Stripe 已验证的形态直接复用；接新渠道对内核零改动（INV-6）的判据才成立 |
| D3 | 只做 **V3**，不做 V2 | V2 已进入淘汰阶段；且 V2 唯一能进沙箱的场景（付款码）本项目用不到 |
| D4 | 凭据**只走 env**，`enabled` 默认 `false` | 与 Stripe 同纪律；未配置时渠道完全不可见，不会误触真实扣款 |
| D5 | 金额一律**分（int）** | 微信 V3 用分；平台 `amountMinor` 也是分；直接对应，无换算（INV-4） |
| D6 | 回调复用通用端点，**不建微信专属 Controller** | 036 的通用回调端点就是为了消灭「每接一家写一个 Controller」 |
| D7 | MOCK 语义**完全交给内核** | 全渠道一致的确定性故障注入（金额尾数 11→超时 / 12→无结论 / 15→拒绝）是 E2E 确定性的基础；插件自写会破坏一致性 |

---

## 3. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **R1（最高）** 无法联调 | 代码写完无从验证 | 三层替代验证：① 签名金标准单测（固定向量）② 本地仿真桩走通四步链路 ③ 真商户 0.01 元闭环（需资质，可选） |
| **R2** 金额单位混用（元 / 分） | 金额放大 100 倍 | INV-4 + 单测断言：`amountMinor` 直传，禁止任何 `*100` / `/100` |
| **R3** 回调验签/解密顺序颠倒 | 接受伪造通知 | FR-007 明确三步顺序；单测钉死「验签失败不得进入解密」 |
| **R4** 凭据误落盘 | 泄露 | FR-009 + 启动期校验；提交前 `git status` 复核无证书/密钥文件 |
| **R5** 与 037 T6 撞车 | WECHAT 被两个分支各迁一次 | 037 执行时 MUST 跳过 WECHAT（spec §12） |
| **R6** SDK 版本不兼容 | 编译/运行时错 | 固定 `0.2.17`；载入后先跑一个 smoke 单测确认类可用 |
| **R7（C-1）** 误把 `enabled` 当注册门控（加 `@ConditionalOnProperty`） | `scenario-routing.sh` 的「已注册 WECHAT」与 S2/S3/S5/S6 全红；MOCK 模态消失 | 按裁决 C-1：`enabled` **只门控真实模式**（FR-016）；T7 把 `scenario-routing.sh` 列为必过验收 |
| **R8（C-2）** 忘记删除 `WechatChannelAdapter` | 同 `channelCode` 两路注册 ⇒ 启动期结构性错误，或路由选了 A 实际调用 B | FR-001 / INV-8；T3 显式 `git rm`；断言渠道清单无重复码 |
| **R9（C-4）** 迁移后 mock 口径静默变化（丢 `adapters.WECHAT.scenario` 配置场景、退款 mock 由异步变同步） | 演示行为与改造前不一致却无人知 | 接受为**有意收窄**；MUST 在 `acceptance.md` 登记；MUST NOT 在插件内自写 mock 兼容（违反 FR-010 / D7） |

---

## 4. 步骤与依赖

| 步骤 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| T1 | 引入 SDK 依赖 + `WechatPayProperties`（env + 门控 + 强校验） | 无 | R6 / R4 |
| T2 | `WechatSdkGateway`：V3 签名 + HTTP（用本地生成的测试密钥对） | T1 | R1 / R2 |
| T3 | `WechatChannelPlugin` + `WechatChannelPluginFactory`（Spring `@Component`）：`descriptor` + `isRealModeEnabled` + 三个 `doRealXxx`；**并删除旧 `WechatChannelAdapter`** | T2 | R2 / **C-2 / C-3** |
| T4 | `parseCallback`：V3 通知验签 + AES-256-GCM 解密 | T2 | **R3** |
| T5 | 本地仿真桩 + 四步全链路（下单 / 查询 / 退款 / 回调） | T3、T4 | R1 |
| T6 | 全量单测零回归 + L0 文档同步 | T1~T5 | — |
| T7 | 起全链路跑 demo 场景（MOCK 模态，验证渠道可注册、可路由） | T6 | 环境 |

---

## 5. 验证命令

```bash
# 单测（快跑）
./mvnw -B -pl payment-service -am test -Dtest='Wechat*' -Dsurefire.failIfNoSpecifiedTests=false

# 全量回归
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test

# 零改动判据（SC-002）
git diff --stat master -- payment-service/src/main/java/com/payment/payment/application \
                          payment-service/src/main/java/com/payment/payment/api \
                          payment-service/src/main/java/com/payment/payment/domain   # 期望为空
```

## 6. 全链路验证（T7）

```bash
deployment/start-all.sh
deployment/demo/reset.sh
deployment/demo/scenario-refund.sh
```

⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`（工具会注入 `SERVER__PORT=61628`，
Spring Boot 会误当成 `server.port`）。

⚠️ `enabled` 默认 `false`，全链路跑的是 **MOCK 模态**——这验证了「渠道可注册、可路由、可回调」，
**不验证真实微信协议**（真实协议由 T2/T4/T5 的单测与仿真桩覆盖）。
