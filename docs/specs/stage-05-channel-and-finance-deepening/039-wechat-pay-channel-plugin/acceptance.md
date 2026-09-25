# Acceptance: 039-wechat-pay-channel-plugin

**对应 Spec**：[spec.md](spec.md)｜**Plan**：[plan.md](plan.md)｜**Tasks**：[tasks.md](tasks.md)
**状态**：**已实测回填**（2026-09-25，T1~T8 全部完成；SC-001~SC-009 **全 PASS**，SC-006 判据措辞已勘误）

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
| SC-001 | WECHAT 注册为 `AbstractChannelPlugin` 插件 | 单测断言 `WechatChannelPluginFactory` 产出 + `descriptor()` 字段；**始终注册**（与 Stripe 同构，`enabled` 不门控注册），`enabled=false` 时真实模式未启用 | ✅ **PASS**。`WechatChannelPluginTest` 16/16（含反射断言工厂无任何 `Conditional*` 注解）；活栈 `GET /internal/channels` 含 `WECHAT`（`routing` 侧 `enabled:true, priority:20`）；`scenario-routing.sh` ⓪b 步 `已注册 WECHAT` PASS |
| SC-002 | **零改动判据** | `git diff --stat` 中内核（`AbstractChannelPlugin` / `ChannelPlugin` / `ChannelPluginFactory`）/ `application` / `api` / `domain` **为空**；改动仅限 `infra/wechat/**` + `pom.xml` + 配置 + **删除 `infra/WechatChannelAdapter.java`** | ✅ **PASS**。`git diff --stat master -- payment-service/src/main/java/com/payment/payment/{application,api,domain}` 与 `.../channelgateway/application/spi` **四条均为空**；改动集 = 新增 `infra/wechat/**`（6 类）+ 删除 `infra/WechatChannelAdapter.java` + 根 `pom.xml` + `application.yml` + 测试 |
| SC-003 | 签名金标准 | 固定向量下 `Authorization` 头逐字节一致 | ✅ **PASS**。`WechatSdkGatewaySignatureTest` 6/6，三层替代：① 基串逐字节字面量 ② 公钥验签 ③ **与官方 SDK 交叉校验**（取 SDK 的 `timestamp`/`nonce` 以本方基串重签，签名逐字节相同——PKCS#1 v1.5 确定性 ⇒ 相等 ⟺ 基串相等）。**未能用真·固定向量**：SDK `WechatPay2Credential#getAuthorization` 的 `timestamp`/`nonce` 不可注入，故改为上述等价证明 |
| SC-004 | 回调往返 | 本地密钥对构造通知 → 验签 + 解密 → 结果一致；验签失败用例能触发拒绝 | ✅ **PASS**。`WechatCallbackParseTest` 9/9；**INV-5 顺序**由两条互补断言钉死（篡改签名报 `signature`、签名正确但篡改密文报 `decrypt`）；`serial` 不匹配 / 缺签名头 / 缺 `out_trade_no` 均拒绝 |
| SC-005 | 仿真桩全链路 | 不依赖微信网络走通「下单 → 查询 → 退款 → 回调」 | ✅ **PASS**。`WechatStubServerTest` 7/7（JDK `HttpServer` 桩，`apiBaseUrl` 指向 `127.0.0.1:<随机端口>`，**零新增测试依赖**）；并断言「桩收到的 `Authorization` 可用商户公钥验签」 |
| SC-006 | `enabled=false` 门控（C-1 裁决口径） | 渠道**仍注册且可路由**（MOCK 模态）、**不读取任何凭据 env**、染 SANDBOX ⇒ 400 硬失败 | ✅ **PASS（判据措辞已勘误，见下）**。活栈双发对照：染 `X-Dye-Tag: SANDBOX` + 显式 WECHAT ⇒ **400 INVALID_ARGUMENT**（`requires channel 'WECHAT' real mode enabled; refusing to silently fall back to mock mode`）；同订单不染色 ⇒ **201** + `channelCode=WECHAT`。⚠️ spec 原写「`GET /internal/channels` 显示 `enabled:false`」**与实现不符**——该字段来自 `payment.routing.channels.*.enabled`（选路资格，WECHAT=true），与 `payment.wechat.enabled`（真实模式门控，默认 false）是两个独立坐标轴 |
| SC-007 | 启动期强校验 | `enabled=true` + env 缺项 ⇒ 拒绝启动 | ✅ **PASS**。`WechatPayPropertiesTest` 11/11；`@PostConstruct` 在 `enabled=true` 时**一次列全**缺失项后抛 `IllegalStateException`；`enabled=false` 时不读任何 env |
| SC-008 | 全量单测零回归 + 全链路 | 见回归基线表；demo 场景通过 | ✅ **PASS**。全 reactor **18 模块 BUILD SUCCESS**；四模块实测见回归基线表；`scenario-routing.sh` **EXIT=0 / 40 断言全 PASS**、`scenario-refund.sh` **EXIT=0 / 17 断言全 PASS** |
| SC-009 | 沙箱结论留档 | 本文档「沙箱实测」章节保留四条证据 | ✅ **PASS**（见上方「沙箱实测」章节） |

> **SC-006 勘误说明**：C-1 裁决的**实质**（`enabled` 只门控真实模式、不门控注册；不静默回落 mock）**已完整实现并经活栈验证**。
> 仅判据中「清单显示 `enabled:false`」一句把**两个同名字段**混为一谈，属**判据措辞缺陷**，非实现缺陷。
> 本次以更强的**行为证据**（400 / 201 对照）替代该字段断言，并在 `tasks.md` T7 同步登记。

## 回归基线

> 基线取 **038 合入后**（master @ `0a81b29`；`ac60f49` 为其上的 **docs-only** 提交，代码/测试零变化，故实测值通用）。
> 038 只改包边界与测试文件重命名，测试总数与全绿状态均已复验。

| 模块 | 基线（master，**本次复测**） | 039 实测 | 差值 |
|---|---|---|---|
| `common/common-core` | **68** tests 全绿 | **68** 全绿 | 0 |
| `common/common-dto` | **6** tests 全绿 | **6** 全绿 | 0 |
| `payment-service` | **344** tests 全绿 | **405** 全绿 | **+61**（新增 `Wechat*` 用例） |
| `deployment/architecture-tests` | **21** tests 全绿 | **21** 全绿 | 0 |

> **基线勘误（相对本 spec 首稿，2026-09-25 复测确认）**：
> ① 首稿记 `payment-service 370` —— 本次在 master 上**实测为 `344`**（`git worktree` 独立检出 + `-pl … -am test` 全绿），
> 首稿数字有误。校验口径：源码级 `@Test` 注解数 master **383** → 当前 **444**，净增 **61**，
> 与 `344 + 61 = 405` **完全吻合**，故 405 与 344 互为自洽（370 与任一实测值都无法自洽）。
> ② 首稿记 `common-dto 15` / `architecture-tests 17（含 1 红）` 是 **038 重构之前**的值；
> 038（`0a81b29`）后渠道契约类迁至 `com.payment.common.dto.channel` ⇒ **6**；`architecture-tests` ⇒ **21 且全绿**
> （首稿所记「已知噪声 1 红」源自 038 前的陈旧 `.workbuddy` 历史快照目录，该目录已不存在，本次复验噪声**未复现**）。
> ③ 全量回归另跑 `./mvnw -B test`（**全 reactor 18 模块**）⇒ **BUILD SUCCESS**，全绿。

## 验收命令

```bash
./mvnw -B -pl payment-service -am test -Dtest='Wechat*' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test

# 零改动判据
git diff --stat master -- payment-service/src/main/java/com/payment/payment/application \
                          payment-service/src/main/java/com/payment/payment/api \
                          payment-service/src/main/java/com/payment/payment/domain   # 期望为空

# 全链路（C-1 的核心验收证据：WECHAT 始终注册 + 路由可命中）
# ⚠️ MUST 带 SPRING_PROFILES_ACTIVE=demo：POST /internal/channels/{code}/status 是 @Profile("demo")，
#    未激活 ⇒ 404 ⇒ scenario-routing.sh 在 ⓪a 步即失败（本次首跑实测复现）。
SPRING_PROFILES_ACTIVE=demo env -u SERVER__PORT -u SERVER__HOST bash deployment/start-all.sh
env -u SERVER__PORT -u SERVER__HOST bash deployment/demo/reset.sh
env -u SERVER__PORT -u SERVER__HOST bash deployment/demo/scenario-routing.sh     # ⭐ MUST 通过：硬断言「已注册 WECHAT」与 S2/S3/S5/S6
env -u SERVER__PORT -u SERVER__HOST bash deployment/demo/scenario-refund.sh
```

⚠️ **全新数据卷时 MUST 先落 schema 再起服务**：`ledger-service` 启动期即查 `ledger.account_definitions`，
空库会让其 `spring-boot:run` 以 exit code 1 退出。按 `demo/reset.sh` 的同一文件清单先重放
`deployment/schema/[0-9][0-9]-*.sql` + `027-user-payment-limit.sql` / `032-reconciliation-statement.sql` /
`034-pending-postings.sql`，再起服务（`start-all.sh` 不含建表步骤，属既有缺口，非本 Feature 引入）。
> 本次实测另遇一次建支付单 500 `Field 'channel_no' doesn't have a default value`。
> **归因（2026-09-25 复核修正，首稿曾误判为「018 归一化前的遗留列」）**：`channel_no` **不是**历史遗留列，
> 而是 **Feature 037 的在飞功能列**——`f213cad feat(037): T3 payment_attempts.channel_no 列` 新增的
> 「渠道网关业务单号 `CH+雪花`」（spec 037 / FR-001），带 `NOT NULL` + `UNIQUE`。
> 本地 MySQL 是**多 worktree 共用的同一个容器/数据卷**，037 的 worktree 跑过一次
> `037-payment-attempt-channel-no.sql` 迁移，共享卷便**超前于 master**；master 的代码不写该列 ⇒ 插入失败。
> 处置按 `deployment/README.md`「回滚」节：`docker compose -f deployment/docker-compose.yml down -v` 后重建
> （`reset.sh` 用 `CREATE TABLE IF NOT EXISTS`，**不改造已存在的表**，故旧卷必须重建而非重放）。
> **教训**：并行 worktree 共用一套中间件时，跨分支做 live 验证前 MUST 先确认共享库的 schema 归属，
> 否则会把「另一条分支的迁移」误判成代码缺陷或历史漂移。

⚠️ 起服务必须 `env -u SERVER__PORT -u SERVER__HOST`。
⚠️ `payment.wechat.enabled` 默认 `false`。按 **C-1 裁决**，此时 WECHAT **仍注册**且 MOCK 可路由（与 Stripe 同构），
全链路跑的是 **MOCK 模态**，验证「渠道可注册 / 可路由 / 可回调」，**不验证真实微信协议**（真实协议由 SC-003 / SC-004 / SC-005 覆盖）。
> 注意**两个同名字段不可混用**：`payment.routing.channels.WECHAT.enabled`（**选路资格**，配置 `true`，即 `GET /internal/channels` 显示的 `enabled`）
> ≠ `payment.wechat.enabled`（**真实模式门控**，默认 `false`）。spec 原文 SC-006/T7 把前者当作后者，属**判据措辞缺陷**。

## 活栈实测证据（2026-09-25，宿主模式 + `SPRING_PROFILES_ACTIVE=demo`）

**① 渠道清单（⓪b 路由快照，原始报文）**

```json
{ "routingEnabled": true,
  "channels": [
    { "code": "ALIPAY", "status": "UP", "priority": 10, "enabled": true  },
    { "code": "DOUYIN", "status": "UP", "priority": 30, "enabled": false },
    { "code": "MOCK",   "status": "UP", "priority": 90, "enabled": true  },
    { "code": "STRIPE", "status": "UP", "priority": 40, "enabled": false },
    { "code": "WECHAT", "status": "UP", "priority": 20, "enabled": true  } ] }
```

⇒ **`WECHAT` 已注册**（C-1 的硬要求）；此处的 `enabled:true` 是**选路资格**，非真实模式。

**② C-1 双发对照（同一订单，`payment.wechat.enabled=false`）**

| 请求 | 结果 | 报文 |
|---|---|---|
| `X-Dye-Tag: SANDBOX` + `channelCode=WECHAT` | **400 INVALID_ARGUMENT** | `X-Dye-Tag=SANDBOX requires channel 'WECHAT' real mode enabled; refusing to silently fall back to mock mode` |
| 同订单不染色 + `channelCode=WECHAT` | **201** | `{"paymentNo":"PM…","status":"PROCESSING","payUrl":"…channelCode=WECHAT","channelCode":"WECHAT"}` |

⇒ **真实模式未启用时不误触真实扣款、也不静默回落 mock**；MOCK 模态照常可路由。

**③ 场景脚本**

| 脚本 | 结果 |
|---|---|
| `scenario-routing.sh` ⭐ | **EXIT=0**，40 条断言全 PASS（含 `已注册 WECHAT`、S1~S6、`payment_routing_total` 已暴露） |
| `scenario-refund.sh` | **EXIT=0**，17 条断言全 PASS（含三层退款单 TXRF / PMRF / `payment_attempts(REFUND)` 可见） |


## 已知变更（迁移前后 mock 口径差异，MUST 向用户明示）

| # | 变更 | 原因 / 影响 |
|---|---|---|
| **CHG-1（C-4）** | `application.yml` 的 `payment.channel.adapters.WECHAT.scenario` 迁移后**成为死配置** | 旧 `AbstractMockChannelAdapter` 支持「配置驱动 mock 场景」；内核 `AbstractChannelPlugin` 只保留「金额尾数注入」一套 mock。属**有意收窄**（与 Stripe 一致）；**不得**为兼容而在插件内自写 mock（违反 FR-010 / D7） |
| **CHG-2（C-4）** | WECHAT 的 mock **退款由异步（`refund-async`）变为同步成功** | 同上：内核 `doMockRefund` 为同步成功。演示脚本若依赖 WECHAT 退款异步时序，需按同步重写断言 |

## 已知限制（交付时 MUST 向用户明示）

| # | 限制 | 影响 |
|---|---|---|
| LIM-1 | **无沙箱可联调** | 真实微信调用未经端到端验证；凭据到位后需按 L4 层（0.01 元闭环）自行验证 |
| LIM-2 | 需**企业资质**商户号 | 个人/未认证主体无法获取 `mchid` + API 证书 |
| LIM-3 | 未覆盖付款码支付（micropay） | 线下被扫场景不在本项目范围内 |
| LIM-4 | 平台证书需**定期轮换** | 微信平台证书有效期有限；生产需实现自动下载更新（本 Feature 仅支持静态配置） |
