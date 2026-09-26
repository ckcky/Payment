<a id="adr-0084"></a>

# ADR-0084：payment-service 双域治理——Payment/Channel 对称四层、`ChannelGateway` 与 `PaymentResultPort` 双向端口、一次性 API 替换与仅开发/测试清库重建（Feature 041）

> **Status**: 🟡 **Proposed**（2026-09-26 起草；待负责人 Accept。**Accept 前禁止进入实现**）`<!-- Proposed | Accepted | Rejected | Not Implemented | Superseded | Deprecated -->`
> **Date**: 2026-09-26
> **Standard**: [adr-standard.md](../standards/adr-standard.md)
>
> 承载 [spec 041-payment-service-governance](../specs/stage-05-channel-and-finance-deepening/041-payment-service-governance/spec.md)。
> 本 ADR 记录的**一个**架构决策是「payment-service 双域治理」；决策 1~7 是同一决策的七个面，
> 采用与 [ADR-0083](0083-observability-baseline-slo-and-cardinality.md)（承载 spec 035 的五条决策）相同的承载体例。
> 本 ADR 同时 **Supersedes ADR-0072 §6「分层 ≠ 拆事务」**，并对 **ADR-0083 决策 3 的排除路径**提出强制同步（见「交叉影响」）。
> 边界类型：**领域模型 / 服务边界 / 公共 API / 数据库结构**（Constitution §Governance 人类决策边界，四项全占）。

---

## Context

### 1. payment-service 同时承载七个职责，包结构已经与边界脱节

生产源码 187 个文件 / 18,493 行，测试 78 个文件 / 12,351 行，分布在三个顶层包：

| 顶层包 | 生产 | 测试 | 现状问题 |
|---|---|---|---|
| `com.payment.payment` | 111 | 51 | 四层之外还挂着 `limit/`、`mq/`、`web/` 三个旁路包 |
| `com.payment.channelgateway` | 63 | 26 | 渠道域**已独立**（037/038 成果），但仍带一个 `web/` 旁路包 |
| `com.payment.posting` | 13 | 1 | 挂账域自成一域，但 spec 041 判定它属 Payment 的操作切片 |

12 个 Controller 散落 4 处（`payment/api`、`payment/limit/web`、`channelgateway/api`、`posting/api`），
其中 `payment/limit/web` 与 `channelgateway/web` 是四层之外的旁路。

### 2. 插件目录「只做了一半」

spec 041 要求每个渠道在 `plugins/<vendor>/` 自包含（SDK、配置、签名、Gateway、Plugin、回调解析、测试）。
现状：

- **完整**：`channelgateway/infra/wechat/`、`channelgateway/infra/stripe/`
- **半成品**：`channelgateway/infra/alipay/` 只有 `AlipayGateway` / `AlipaySdkGateway`；
  `AlipayChannelAdapter` 与 `config/AlipaySandboxProperties` 在**目录外**
- **未成型**：`DouyinChannelAdapter`、`MockChannelAdapter` 是扁平文件，无专属目录

### 3. 🔴 今天存在两个「041」，且旧的那个已合入 master

| | 旧 041 `payment-flow-layering` | 新 041 `payment-service-governance` |
|---|---|---|
| 提交 | `5c00ad4`（01:50）、`57e12c3`（11:13）、`8ee36e3`（merge） | `5ce4853`（12:06）四件套 |
| 内容 | 支付主链分层（`POST /payments` → `/payments/pay`）；渠道单正名 `PaymentAttempt` → `ChannelOrder`（**类名 + 表名全改**）+ 配套 `041-channel-orders-rename.sql` | 双域四层重构 + 一次性 API 替换 + 清库重建 Schema（T01~T28） |
| 状态 | **已合入 master** | **Draft，待负责人审阅** |

新 spec §1 已声明：「本 Feature 将当前实现视为可替换基线。037–040 及**未归档痕迹**可用于迁移风险调查，
但不是目标结构、行为或验收的约束。」——即旧 041 的实现不构成约束。

### 4. 🔴 ADR-0072 §6 已被代码事实推翻，本 ADR 必须显式收口

ADR-0072 §6 的裁决原文：

> 「两层**可以且应当共享同一个本地事务**」「本 ADR 只切**职责与写入口**，不动事务边界。」
> 「`payments` 与 `payment_attempts` 拆成两个事务/数据源」被列为**否决方案**（理由：引入一致性窗口）。

而**旧 041 已经真的拆了事务**（`57e12c3` 提交信息：「D2：拆事务，用最终一致换模块自治」；
`PaymentPersistence.insertPending` 只写 `payments`，不再代渠道域开渠道单），
新 spec 041 plan §4.2 进一步要求「**两域不共享事务**」。

⇒ 现状是 **Code ≠ ADR**。本 ADR 必须显式 Supersedes ADR-0072 §6，否则「代码违规」与「ADR 过期」两种解释并存。

### 5. 🔴 端点路径变更会把 ADR-0083 刚堵上的日志泄漏洞重新打开

ADR-0083 决策 3 的落点是 `AccessLogProperties` 默认排除路径：

```java
@DefaultValue({"/actuator/**", "/internal/channels/**"}) List<String> excludePaths
```

其意图是「渠道回调入口一律不落正文——支付宝 notify 的 form（含 `sign`/`out_trade_no`/`total_amount`）
不得进 ACCESS_LOG」。

spec 041 把回调端点从 `/internal/channels/{channelCode}/callback` 改为 **`/callbacks/channels/{channelCode}`**。
**新路径不匹配 `/internal/channels/**`** ⇒ 按 spec 字面实施，渠道回调报文会**重新整段进 ACCESS_LOG**，
恰好重开 ADR-0083 刚关掉的那个洞。这是本 ADR 必须登记的**强制同步项**。

### 6. 存量 041 的实测基线（供验收回归对照）

- `payment-service` 源码级 `@Test|@ParameterizedTest|@RepeatedTest` 注解数 = **496**
- 端点引用面：`/internal/payments` 等旧路径出现在 **130+ 文件**（含 docs），非文档约 40 个
- 受影响调用方：`order-service/PaymentFeignClient`、`reconciliation-service/{PaymentFacts,RefundFacts}FeignClient`、
  `deployment/mock-channel-web/{ChannelCallbackProxy,RefundCallbackProxy,demo.html,routing.html}`、
  `deployment/e2e-tests/support/Api.java`、`demo/scenario-{routing,limit,refund}.sh`、k6 压测、
  grafana/prometheus、docker-compose、start-all.sh

---

## Decision

### 决策 1：Payment / Channel 对称双域，各四层

```
com.payment.payment.{api,application,domain,infra}
com.payment.channel.{api,application,domain,infra}
                            infra.{persistence,plugins/<vendor>/}
```

- `com.payment.channelgateway` → **改名为** `com.payment.channel`（不是新建，是正名）。
- `com.payment.posting` → **并入** `payment`（PendingPosting 是 Payment 的操作切片，不是独立域）。
- `payment/limit`、`payment/mq`、`payment/web`、`channelgateway/web` 四个旁路包**收进四层**：
  写入口进 `application`，Filter/Config 进 `api` 或 `infra`。
- 四层的依赖方向单向：`api → application → domain ← infra`；**domain 不依赖任何外层**。

### 决策 2：双向端口，跨域 DTO 入 `common-dto`

| 方向 | 端口 | 定义方 | 实现方 |
|---|---|---|---|
| Payment → Channel | `channel.application.port.ChannelGateway` | Channel | Channel |
| Channel → Payment | `payment.application.port.PaymentResultPort` | **Payment** | **Payment** |

- Payment 可依赖 `ChannelGateway` 与共享 DTO；**禁止**依赖 Channel 的 domain / infra / Mapper / Entity / Plugin / Router / Registry。
- Channel 可依赖 `PaymentResultPort` 与共享 DTO；**禁止**依赖 Payment 聚合 / Repository / Mapper / application 实现。
- **Domain 互不依赖**；infra 只实现本域端口。
- 跨域 DTO 落 `common/common-dto`，只含业务单号、金额、币种、状态/原因与必要关联字段；
  **不含数据库 ID、实体、SDK、原始渠道报文**（延续 ADR-0063 业务单号纪律）。

### 决策 3：一次性替换全部 HTTP API，**不保留任何兼容层**

9 组端点，其中 4 组是路径变更：

| 领域 | 新端点 | 旧端点 |
|---|---|---|
| Payment | `POST /api/payments` | `/internal/payments`（+ 旧 041 的 `/payments/pay`） |
| Payment | `GET /api/payments/{paymentNo}`、`GET /api/payments?transactionNo=` | `/payments?paymentNo=&transactionId=` |
| Payment | `POST /api/payments/{paymentNo}/resolve` | `/payments/{paymentNo}/resolve` |
| Refund | `POST /api/payments/{paymentNo}/refunds` | `/internal/payments/refunds` |
| Refund | `GET /api/refunds/{refundNo}`、`POST /api/refunds/{refundNo}/resolve` | `/internal/payments/refunds/{refundNo}/...` |
| Channel | `POST /callbacks/channels/{channelCode}` | `/internal/channels/{channelCode}/callback` |
| Channel | `GET /internal/channels/orders/{channelNo}`、`?paymentNo=` | 同（保留） |
| Channel | `GET /internal/channels`、`/route-preview`、`POST /internal/channels/{channelCode}/availability` | 同（保留） |
| Facts | `/internal/payments/confirmed-facts`、`/internal/refunds/confirmed-facts` | `/internal/payments/confirmed-facts`（保留） |

**MUST NOT** 创建转发、别名、`@Deprecated` 兼容层或双轨端点。旧 DTO 在全部调用方迁移后删除。
每个契约须明确哪些业务单号必填；**禁止 `id`、`attemptId` 等数值主键跨 HTTP 或跨域端口出现**（ADR-0063）。

### 决策 4：仅 development / test 环境允许清库重建 Schema

- 清库工具**必须**显式要求 `APP_ENV=development` 或 `APP_ENV=test`；
  **任一其他值、缺失值或目标库无法识别 ⇒ 失败且零删除**（fail-closed，延续 ADR-0052 口径）。
- 仅重建 **payment** Schema；不迁移历史数据；**真实数据环境不属于本 Feature**。
- 建表顺序：Payment/Refund → ChannelOrder → Limit → PendingPosting → 消息/审计辅助表。
- 每张表有业务唯一键与必要查询索引；**跨域只保存业务单号，不建跨域外键**。
- DDL、H2、MySQL 与 replay 由**同一 Schema 来源**生成（延续 ADR-0081 双路径重放门禁）。

### 决策 5：插件目录自包含

每个渠道在 `channel/infra/plugins/<vendor>/` 携带**该渠道全部生产资源与测试**
（SDK Gateway、配置、签名器、Factory、Strategy、Plugin、回调解析器）。
Factory/Registry 只按规范化 `channelCode` 定位唯一 Plugin；
**模板方法入口不可被插件绕过**；插件**不得**读取 Payment/Refund 实体、执行业务记账、调用订单服务或决定业务状态机。

### 决策 6：**Supersedes ADR-0072 §6**——两域不共享事务

- Payment 本地事务**只保护** Payment 事实与本域失败台账；
- Channel 本地事务**只保护** ChannelOrder；
- **两域不共享事务**，跨域一致性用「最终一致 + 可收敛 PENDING/UNKNOWN + 显式 resolve 命令」承担。

**代价被显式接受**：ADR-0072 §6 担心的 `payment=SUCCEEDED / channelOrder=PENDING` 一致性窗口真实存在。
补偿：① 渠道收敛后经 `PaymentResultPort` 通知，Payment 侧终态吸收；② UNKNOWN 保留可收敛事实；
③ 对账与人工显式命令兜底；④ **禁止**用「回滚已确认外部事实」来消窗口。
**这不是概念混淆，是在承认 ADR-0072 原判据（两表必须同事务同步推进）之后的有意取舍**——
旧 041 已按此落地，本 ADR 只是补上决策记录。

### 决策 7：ArchUnit 强制 + 阳性对照

新增门禁并**每条配阳性对照**（延续 ADR-0081 / spec 033 的「防空转」纪律）：

- 双域边界：`payment` 不得依赖 `channel` 的 domain/infra/plugin；`channel` 不得依赖 Payment 聚合/Repository；
- 四层依赖：`api → application → domain ← infra`，domain 无外层依赖；
- Controller：不得依赖 Repository / Mapper / Entity / Plugin，单请求只委托**一个**本域 application 入口；
- 持久化：Mapper 只在 `infra.persistence`，`application` 只见 Repository 端口；
- 插件目录：渠道私有类型只在 `infra.plugins.<vendor>`；
- 旧符号：旧包名 / 旧端点字符串 / 旧 DTO 的负向扫描。

---

## 交叉影响（必须同步，不可遗漏）

| # | 冲突/影响 | 处置 |
|---|---|---|
| X-1 | **ADR-0072 §6「分层 ≠ 拆事务」与决策 6 直接冲突** | 本 ADR **Supersedes ADR-0072 §6**；ADR-0072 其余部分（职责与写入口分离、退款取原支付渠道）继续有效 |
| X-2 | **ADR-0083 决策 3 的 `/internal/channels/**` 不再覆盖新回调端点** | 排除路径**必须**补 `/callbacks/**`（或新路径前缀），否则渠道报文含 `sign` 重新进 ACCESS_LOG；须同步 `AccessLogProperties` 默认值 + `application.yml` + `AccessLogFilterTest` + ADR-0083 落点 |
| X-3 | 旧 041 的 `041-channel-orders-rename.sql` 与 `ChannelOrder*` 类名 | **保留**（改名结果与新 spec 的 `ChannelOrder` 口径一致），但包路径 `channelgateway.*` → `channel.*` 需再迁一次 |
| X-4 | spec 040（同为 Draft，38 任务未勾选） | 由本 Feature **吸收**；040 须显式置为 `Superseded by 041`，避免两套 API 面治理并存 |
| X-5 | ADR-0073 路由 / ADR-0075 统一渠道契约 / ADR-0076 染色与模态落库 | **不变**，原样继承（本 ADR 不动选路、契约与染色语义） |
| X-6 | 状态枚举与合法迁移 | **不得改变**（spec §8）；若实施中发现必须改 ⇒ **停止**并另提 ADR/Feature |

---

## Alternatives

- **A. 只改包名，不动 API 与 Schema（最小风险）**：保留全部旧端点与兼容层。
  与 spec 041 目标 4/5（一次性替换、无旧兼容面）直接冲突 ⇒ **否决**。
- **B. 新建 `payment-channel-service` 独立服务（真微服务）**：双域各一进程。
  违反 spec §4 非目标「不新增部署服务 / Maven 模块 / 数据库实例」；且引入跨进程一致性问题 ⇒ **否决**。
- **C. 保留 ADR-0072 §6 的两域共享事务，只做包改名**：一致性风险最低。
  但旧 041 已拆事务并合入，回退等于推翻 `57e12c3` 的已验收成果，且渠道域无法真正自治 ⇒ **否决**（改为决策 6 显式 Supersedes）。
- **D. 保留旧 API，加新路径作为别名，分阶段下线**：违反「禁止新旧混跑」，且双轨期必须同时维护两套契约与两套调用方 ⇒ **否决**。
- **E. 采纳**：双域对称四层 + 双向端口 + 一次性 API 替换 + 仅 dev/test 清库重建 + 插件自包含 + Supersedes ADR-0072 §6 + ArchUnit 门禁。

---

## Consequences

**正面**
- 每个渠道的全部资产可在**单一目录**定位；Payment 对渠道零私有依赖（US-09 / SC-008 可机器验证）；
- 「Payment 不得读写 ChannelOrder」从**口头纪律**变成**可执行门禁**（INV-003）；
- 回调链路职责单一：Channel 做识别/验签/解析，Payment 只收标准结果，验签失败零业务副作用（INV-008）；
- API 面从「新旧并存 + 旁路包散落」收敛为双域各一入口，运维与 demo 只需认一套路径。

**负面 / 对下游的影响**
- **一次性替换 = 破坏性变更**：130+ 文件、4 组路径、上下游 4 个服务同步迁移；**禁止新旧混跑** ⇒ 迁移必须原子完成，中途无法交付半成品；
- **两域不共享事务**（决策 6）引入一致性窗口（ADR-0072 §6 的原担忧真实存在，已显式接受）；
- **清库不可逆**：仅 dev/test，靠 `APP_ENV` 硬门禁防护；本机有 live 栈在跑，清库前必须先停栈；
- **规模**：187 生产文件 / 18,493 行 + 78 测试文件 / 12,351 行需重排；28 个任务、跨多轮实施；
- 对**运维与文档**的连带：`runbook.md`、`technical-solution.md`、`systems/payment-service.md`、
  `deployment/{docker-compose.yml,start-all.sh,start-container.sh,demo/*,e2e-tests/*,mock-channel-web/*}`、
  `prometheus`/`grafana` 中的旧路径全部需同步；
- 回滚以 Feature 分支为单位：**未合并时丢弃分支；合并后 revert merge commit**。禁止部分回滚。

---

## Risks

| 风险 | 触发条件 | 影响 | 缓解措施 |
|---|---|---|---|
| **API 漏迁** | 有调用方仍打旧路径 | 运行时 404 / 资金链路中断 | T21→T23 逐调用方迁移 + 负向 `rg` 扫描（SC-011）+ `docs-lint` 检查 15/16；**先建映射清单再动代码**（T03） |
| **清库误环境** | `APP_ENV` 缺失 / 非 dev-test / 库不可识别 | **不可逆数据丢失** | 清库工具 fail-closed：非 `development`\|`test` 一律失败且零删除（决策 4）；执行前停栈并二次确认；本 Feature 不覆盖真实数据环境 |
| **状态机漂移** | 「重构」过程中顺手改了状态枚举或迁移条件 | 资金语义变更、账本不平 | spec §8 明文禁止；状态机测试**先行**（T07/T14 先落测试再动实现）；发现必须改 ⇒ **停工另提 ADR** |
| **跨域双向实现依赖** | Channel 反向 import Payment 实现，或反之 | 域边界失效、循环依赖 | 决策 7 的 ArchUnit 双向门禁 + 阳性对照（SC-014）；端口由**被调用方**定义 |
| **插件资源遗漏** | 迁移时漏带签名器 / 配置 / 回调解析器 | 该渠道静默失效 | T11 逐渠道迁入 + 每渠道独立装配测试（SC-003）；`plugins/<vendor>/` 自包含由 ArchUnit 校验 |
| **ADR-0083 日志漏洞重开** | 新回调路径 `/callbacks/channels/**` 未加入排除路径 | 渠道报文（含 `sign`）整段进 ACCESS_LOG | **X-2 强制同步项**：同步改 `AccessLogProperties` 默认值 + `application.yml` + `AccessLogFilterTest` + ADR-0083 落点；并入 T25 门禁 |
| **新旧双写 / 双轨残留** | 保留兼容 Controller 或旧 DTO | 两套契约长期并存、口径分裂 | 决策 3 明文禁止兼容层；T24 负向扫描 + 编译；SC-013 |
| **半成品交付** | 迁移中途合并 | 仓库处于不可运行状态 | 回滚以 Feature 分支为单位；**禁止新旧 API 混跑**；T28 全量 verify 为唯一完成判据 |

---

## Related Documents

- **Spec**：[041-payment-service-governance](../specs/stage-05-channel-and-finance-deepening/041-payment-service-governance/spec.md)（`spec.md` / `plan.md` / `tasks.md` / `acceptance.md`）
- **System Design**：[payment-service](../architecture/systems/payment-service.md)、[technical-solution](../architecture/technical-solution.md)、[roadmap](../architecture/roadmap.md)
- **Supersedes**：**ADR-0072 §6**（「分层 ≠ 拆事务」，见 [0072-two-layer-channel-architecture.md](0072-two-layer-channel-architecture.md)）——**仅取代该节**，0072 其余条款（职责与写入口分离、退款取原支付渠道）继续有效
- **影响**：[ADR-0083](0083-observability-baseline-slo-and-cardinality.md) 决策 3 的排除路径需补 `/callbacks/**`（X-2）；spec [040](../specs/stage-05-channel-and-finance-deepening/040-payment-api-surface-consolidation/spec.md) 由本 Feature 吸收（X-4）
- **继承不变**：[ADR-0063](0063-cross-service-reference-by-business-no.md)（业务单号跨系统）、[ADR-0073](0073-channel-routing.md)（路由）、[ADR-0075](0075-unified-channel-contract.md)（统一渠道契约）、[ADR-0076](0076-traffic-dyeing-and-alipay-sandbox.md)（染色与模态落库）、[ADR-0081](0081-test-carrier-and-schema-replayability.md)（双路径重放门禁）、[ADR-0052](0052-channel-callback-signature-decisions.md)（fail-closed 口径）
- **Constitution**：[.specify/memory/constitution.md](../../.specify/memory/constitution.md) §Governance（人类决策边界）

---

## 待人类裁决

| # | 决策项 | 边界类型 | 负责人裁决（2026-09-26） |
|---|---|---|---|
| H-041-1 | 是否批准 041 立项（Draft → Approved，并建本 ADR） | 立项授权 | ✅ **批准立项，先出 ADR 待 Accept** |
| H-041-2 | 一次性替换全部 HTTP API + 开发/测试环境清库重建 Schema | 公共 API / 数据库结构 | ✅ **两项都授权** |
| H-041-3 | 旧 041（`payment-flow-layering`，已合入 master）如何处置 | 领域模型 / 基线 | ✅ **视为可替换基线**（spec §1 已声明） |
| H-041-4 | 是否 Supersedes ADR-0072 §6「分层 ≠ 拆事务」 | 服务边界 / 一致性语义 | ⏳ **随本 ADR 一并 Accept** |
| H-041-5 | ADR-0083 排除路径补 `/callbacks/**`（X-2） | 安全口径 | ⏳ **随本 ADR 一并 Accept**（不补即重新泄漏渠道报文） |
| H-041-6 | spec 040 置为 `Superseded by 041`（X-4） | 文档治理 | ⏳ **随本 ADR 一并 Accept** |

> **Accept 前禁止进入实现**：spec 041 `acceptance.md` §1 前置条件 1 要求「新 ADR 已 Accepted；
> Feature 已进入 `In Development` 或更高状态」。H-041-4~6 三项未裁决时，决策 6 与 X-2/X-4 均无授权依据。
