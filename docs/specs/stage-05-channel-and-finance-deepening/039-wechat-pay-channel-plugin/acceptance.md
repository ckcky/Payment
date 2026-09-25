# Acceptance: 039-wechat-pay-channel-plugin

**对应 Spec**：[spec.md](spec.md)｜**Plan**：[plan.md](plan.md)｜**Tasks**：[tasks.md](tasks.md)
**状态**：待实测回填（T1~T8 完成后填写「实测结论」列）

---

## 沙箱实测（2026-09-25，本机 curl，无代理）

> 用户要求「看看微信的沙箱能不能用，试试」。以下为**原始实测输出**，结论：**不可用**。

| # | 探测命令 | 原始结果 | 结论 |
|---|---|---|---|
| 1 | `curl -X POST https://api.mch.weixin.qq.com/xdc/apiv2sandbox/pay/getsignkey -H "Content-Type: application/xml" -d '<xml><mch_id>1900000109</mch_id><nonce_str>...</nonce_str><sign>...</sign></xml>'` | **HTTP 200**<br>`<xml><return_code><![CDATA[FAIL]]></return_code>`<br>`<return_msg><![CDATA[商户号非法]]></return_msg></xml>` | V2 沙箱端点**活着**；但需**真实商户号**才能换到沙箱密钥（此处用文档示例号 `1900000109`） |
| 2 | `curl -X POST https://api.mch.weixin.qq.com/v3/sandboxnew/pay/transactions/native -H "Authorization: WECHATPAY2-SHA256-RSA2048 test" -d '{}'` | **HTTP 404** | **V3 没有沙箱** |
| 3 | `curl https://api.mch.weixin.qq.com/v3/certificates -H "Authorization: WECHATPAY2-SHA256-RSA2048 test"` | **HTTP 401**<br>`{"code":"SIGN_ERROR","message":"Authorization不合法"}` | 网络可达；必须真实商户 API 证书 |
| 4 | 官方文档《支付验收指引》<br>`pay.weixin.qq.com/wiki/doc/api/native_sl.php?chapter=23_1` | 「仿真系统……只需将正式 API 的调用 URL 增加一层 `xdc/apiv2sandbox` 路径……**目前只支持付款码支付成功用例与付款码支付异常用例中的接口调用，下单接口 `https://api.mch.weixin.qq.com/pay/unifiedorder` 等目前暂不支持使用**」 | V2 沙箱**只覆盖付款码支付**（线下被扫），**不支持下单接口** |

### 结论

**微信支付沙箱不可用，且不是配置问题：**

1. **协议错配**：沙箱只有 V2（XML + MD5/HMAC），本项目用 V3（JSON + RSA-SHA256 + 商户证书 + 平台证书 + AES-256-GCM）。沙箱练不到 V3 的签名/验签/证书链路。
2. **V3 沙箱不存在**：`/v3/sandboxnew/**` 实测 404。
3. **场景错配**：即使退回 V2 沙箱，也只支持**付款码支付**（micropay，线下被扫），本项目是电商下单（Native / JSAPI），正是官方写明「暂不支持」的接口。

**横向对比**：支付宝沙箱 ✅ 已接入 · Stripe test mode ✅ 已接入 · **微信 ❌ 无可联调环境**。

**⇒ 替代验证方案**（本 Feature 采用，见 SC-003 / SC-004 / SC-005）：

| 层 | 手段 | 覆盖 |
|---|---|---|
| L1 | **签名金标准单测**（固定密钥 + 固定 timestamp/nonce ⇒ 断言 Authorization 逐字节一致） | V3 签名正确性 |
| L2 | **回调往返单测**（本地生成密钥对，构造通知 → 验签 → 解密） | 回调验签 + AES-256-GCM 解密 |
| L3 | **本地仿真桩全链路**（下单 / 查询 / 退款 / 回调，不走微信网络） | 插件编排正确性 |
| L4（可选） | **真商户 0.01 元实付 + 即时退款**闭环 | 端到端真实性（需企业资质，本 Feature 不执行） |

---

## 验收清单

| # | Success Criterion | 判据 | 实测结论 |
|---|---|---|---|
| SC-001 | WECHAT 注册为 `AbstractChannelPlugin` 插件 | 单测断言 Factory 产出 + `descriptor()` 字段；`enabled=false` 时不注册 | 待填 |
| SC-002 | **零改动判据** | `git diff --stat` 中内核 / `application` / `api` / `domain` **为空**；改动仅限 `infra/wechat/**` + `pom.xml` + 配置 | 待填 |
| SC-003 | 签名金标准 | 固定向量下 `Authorization` 头逐字节一致 | 待填 |
| SC-004 | 回调往返 | 本地密钥对构造通知 → 验签 + 解密 → 结果一致；验签失败用例能触发拒绝 | 待填 |
| SC-005 | 仿真桩全链路 | 不依赖微信网络走通「下单 → 查询 → 退款 → 回调」 | 待填 |
| SC-006 | `enabled=false` 门控 | 渠道不注册、不读密钥、不出现在 `GET /internal/channels` | 待填 |
| SC-007 | 启动期强校验 | `enabled=true` + env 缺项 ⇒ 拒绝启动 | 待填 |
| SC-008 | 全量单测零回归 + 全链路 | 见回归基线表；demo 场景通过 | 待填 |
| SC-009 | 沙箱结论留档 | 本文档「沙箱实测」章节保留四条证据 | 待填 |

## 回归基线

| 模块 | 基线（master @ ee9f1b3，**已实测**） | 实测 |
|---|---|---|
| `common/common-core` | **68** tests 全绿 | 待填 |
| `common/common-dto` | **15** tests 全绿 | 待填 |
| `payment-service` | **348** tests 全绿 | 待填 |
| `deployment/architecture-tests` | **17** tests，16 绿 / **1 红（已知噪声）**；`ServiceBoundaryTest` **12/12 绿** | 待填 |

> **已知环境噪声（负责人已裁定「不处置」，不计入失败）**：
> `AccountingVocabularyBoundaryTest` 报 2 条违规，路径均在 `.workbuddy\p3-removed-refund-service\...`
> （ArchUnit 扫全仓 `.java` 未排除点目录）。**不要改测试、不要删 `.workbuddy/`**。

## 验收命令

```bash
./mvnw -B -pl payment-service -am test -Dtest='Wechat*' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test

# 零改动判据
git diff --stat master -- payment-service/src/main/java/com/payment/payment/application \
                          payment-service/src/main/java/com/payment/payment/api \
                          payment-service/src/main/java/com/payment/payment/domain   # 期望为空

# 全链路
deployment/start-all.sh
deployment/demo/reset.sh
deployment/demo/scenario-refund.sh
```

⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`。
⚠️ `enabled` 默认 `false`，全链路跑的是 **MOCK 模态**，验证「渠道可注册 / 可路由 / 可回调」，
**不验证真实微信协议**（真实协议由 SC-003 / SC-004 / SC-005 覆盖）。

## 已知限制（交付时 MUST 向用户明示）

| # | 限制 | 影响 |
|---|---|---|
| LIM-1 | **无沙箱可联调** | 真实微信调用未经端到端验证；凭据到位后需按 L4 层（0.01 元闭环）自行验证 |
| LIM-2 | 需**企业资质**商户号 | 个人/未认证主体无法获取 `mchid` + API 证书 |
| LIM-3 | 未覆盖付款码支付（micropay） | 线下被扫场景不在本项目范围内 |
| LIM-4 | 平台证书需**定期轮换** | 微信平台证书有效期有限；生产需实现自动下载更新（本 Feature 仅支持静态配置） |
