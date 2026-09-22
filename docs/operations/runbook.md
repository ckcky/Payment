# 运行手册（Runbook）

**适用版本**：PaymentArch 0.1.0-SNAPSHOT（Roadmap Phase 0~10）
**最后更新**：2026-08-31
**配套文档**：`docs/architecture/roadmap.md`、`docs/adr/`、`docs/specs/`

> 本手册是 Phase 10 验收标准（ADR-0033）要求的运行手册。它只描述**当前实际形态**：单台机器、单一 MySQL 实例、10 个 JVM 进程、跨服务同步 HTTP/Feign。任何拆分或部署形态变更必须先走 `split-proposal-template.md` 评审，并在通过后回来更新本手册。

---

## 1. 服务清单与端口

| 等级 | 服务 | 端口 | Schema | 下游 Feign 依赖 | 健康端点 |
| --- | --- | --- | --- | --- | --- |
| T3 | `merchant-service` | 8081 | 无（内嵌存储，重启清空） | — | `/actuator/health` |
| T3 | `catalog-service` | 8082 | `catalog` | — | `/actuator/health` |
| T1 | `order-service` | 8083 | `order` | catalog, payment | `/actuator/health` |
| T1 | `payment-service` | 8084 | `payment` | fulfillment, ledger, order | `/actuator/health` |
| T2 | `fulfillment-service` | 8086 | `fulfillment` | entitlement | `/actuator/health` |
| T2 | `entitlement-service` | 8087 | `entitlement` | — | `/actuator/health` |
| T1 | `reconciliation-service` | 8088 | `reconciliation` | payment | `/actuator/health` |
| T1 | `settlement-service` | 8089 | `settlement` | ledger, merchant, reconciliation | `/actuator/health` |
| T0 | `ledger-service` | 8090 | `ledger` | — | `/actuator/health` |
| —（演示） | `mock-channel-web` | 8091 | 无（演示组件，不进服务边界） | —（被 payment 收银台同源代理 `/proxy/**` 调用） | `/actuator/health` |

- 全部服务暴露 `/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus` 与 Swagger UI。
- 关键等级（T0~T3）定义见 `docs/adr/0029-distributed-evolution-decisions.md` ADR-0032。
- ⚠️ **`mock-channel-web`（8091）是 Feature 011 的演示组件，不是生产服务**：它**不进入** `architecture-tests` 的 `ServiceBoundaryTest.SERVICES` 边界（构建期门禁已验证），不承担任何资金/业务事实，仅用于演示收银台跳转、回调转发与演示控制台。舰队规模 = 9 个服务（`refund` 域已并入 `payment-service`，端口 8085 退役） + 1 个演示组件 = 10 个 JVM 进程。

## 2. 启动顺序

**先选模式**（spec 026 / ADR-0070）：宿主模式与容器模式**端口互斥，二选一**。
启动脚本内置双向守卫，检测到另一侧在跑会中止并提示。

| 模式 | 启动 | 停止 |
|---|---|---|
| 容器模式 | `bash deployment/start-container.sh` | `bash deployment/stop-all.sh` |
| 宿主模式 | `bash deployment/start-all.sh` | `bash deployment/stop-all.sh` |

- **容器模式**下，下面的启动顺序由 `docker compose` 的 `depends_on` + healthcheck 自动保证，
  特别是 **Nacos 必须先 healthy**（它是所有 `@FeignClient` 的硬依赖，ADR-0059）——
  只依赖启动顺序会造成「服务起来了但跨服务调用全 Connection refused」的假成功。
- **宿主模式**下需人工按依赖方向起，顺序如下（先起被依赖方，可减少启动期熔断）：

```text
MySQL 8 (localhost:3306)
   ↓
ledger-service (8090)  ← 资金事实底座，最先起
   ↓
entitlement-service (8087) → fulfillment-service (8086)
   ↓
payment-service (8084) → order-service (8083)
   ↓
mock-channel-web (8091)  ← 演示组件：收银台页 + 回调转发 + 控制台；payment 收银台同源代理 /proxy/** 依赖它（任意时机起即可，建议与 payment 同批）
   ↓
reconciliation-service (8088) → settlement-service (8089)
   ↓
merchant-service (8081)、catalog-service (8082)（无下游依赖，任意时机）
```

启动后自检：`GET http://localhost:<port>/actuator/health` 逐个确认 `UP`。

## 3. 数据库

- 单实例 MySQL 8（`localhost:3306`，root/root），数据库按服务使用独立 **Schema**：`catalog` / `order` / `payment`（含原 `refund` 库的退款表，该库已退役）/ `fulfillment` / `entitlement` / `reconciliation` / `settlement` / `ledger`。`merchant-service` 无库，使用内嵌存储（**重启即清空**）。
- 建表脚本：`deployment/schema/`。
- **跨服务零写路径**：任何服务只读写自己的 Schema，跨服务只经 HTTP。该约束由 `architecture-tests` 在构建期强制（ADR-0029）。

## 4. 环境变量（密钥）

密钥一律经环境变量注入，**禁止硬编码、禁止入库、禁止明文日志**（ADR-0026，Accepted：明文 env 即可，不接 Vault）。

> ⛔ **2026-08-31 现状（负责人 2026-08-30 裁决后的最终形态）**：鉴权与验签**均为预留空函数**，因此 **不再有任何需要注入的安全密钥**。下表仅保留仍在生效的 `PAYMENT_ADMIN_TOKEN`；其余三项目前**无对应代码**，接入真实鉴权/验签时按表补回。

| 环境变量 | 用途 | 当前状态 |
| --- | --- | --- |
| `PAYMENT_ADMIN_TOKEN` | `/payments/{id}/resolve` 人工收敛端点的 `X-Admin-Token` | ✅ **生效**；未配置时端点返回 `503`（拒绝而非放行） |
| ~~`PAYMENT_INTERNAL_TOKEN`~~ | ~~`/internal/**` 的 `X-Service-Token`~~ | ⛔ **已移除**（ADR-0024 鉴权改为空实现，配置块已删） |
| ~~`PAYMENT_CHANNEL_SECRET`~~ | ~~渠道回调 HMAC-SHA256 验签密钥~~ | ⛔ **payment 已移除**（ADR-0025 验签改为空实现，配置块已删）；但 `deployment/start-all.sh` 仍导出该值供 `mock-channel-web` **演示签名用（demo-only，payment 不读取）** |
| ~~`PLATFORM_INTERNAL_TOKEN`~~ | ~~全平台共享的内部服务令牌（出站附加 / 入站校验）~~ | ⛔ **已移除**（ADR-0034 出站令牌不做，拦截器与配置已删） |

**接入真实鉴权 / 验签时**（当前不需要执行）：

1. 实现 `InternalServiceAuthInterceptor#verifyServiceToken`（读 `X-Service-Token` 与配置令牌常数时间比对：未配置 → `503`，缺失/不匹配 → `403`）；
2. 实现 `ChannelCallbackSignatureFilter#verifySignature`（用 common-core 的 `SignatureVerifier` 校验 `timestamp + "." + rawBody` 的 HMAC-SHA256，配防重放窗口）；
3. **必须同时**补出站令牌拦截器（否则启用入站鉴权后调用方全线 `403`）——即 ADR-0034 记录的拓扑约束：入站与出站成对启用；
4. 补回对应配置项与环境变量，并恢复 `payment.internal_auth_rejected` / `payment.callback_signature_rejected` 埋点。

> **当前无需「开启顺序」**：整条令牌链已删除，不存在「顺序错了会全站 403」的情形。

相关配置：`payment-service/src/main/resources/application.yml` 的 `payment.resolve.*`（唯一在用的安全配置；`payment.security.*` / `payment.risk.*` 与各服务的 `platform.security.*` 已按裁决移除）。

### 4.1 渠道路由配置（Feature 028，非密钥）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `payment.routing.enabled` | `true`（`PAYMENT_ROUTING_ENABLED`） | 灰度开关：`false` 回落旧行为——建单**必须显式传** `channelCode` |
| `payment.routing.channels.<CODE>.enabled` | ALIPAY/WECHAT/MOCK=true，DOUYIN=false | 是否参与**自动**选路。语义是「别自动挑我」，**不是**「禁止使用」——显式指定仍可用（L3） |
| `payment.routing.channels.<CODE>.priority` | ALIPAY 10 / WECHAT 20 / DOUYIN 30 / MOCK 90 | 数值越小越优先；同优先级按渠道码字典序（确定性 INV-3）。**必填**，缺失启动失败 |
| `payment.routing.availability.<CODE>.status` | 缺省 `UP` | `UP` 正常 / `DEGRADED` 保留候选但排序降级 / `DOWN` 排除出候选集（显式指定则 409） |
| `payment.channel.adapters.<CODE>.scenario` | 回落 `payment.channel.mock-scenario` | per-channel Mock 人格（`PAYMENT_CHANNEL_<CODE>_SCENARIO`） |

**故障处置：某渠道持续失败（表现为 FAILED / UNKNOWN 而非路由层规避，L1 无健康探测）**

1. 置 `payment.routing.availability.<CODE>.status: DOWN` 并重启（或演示期走 `POST /internal/channels/{code}/status`）；
2. 用 `GET /internal/channels/route-preview` 确认自动选路已绕开该渠道、`excluded` 含理由；
3. 告警 `no_available_channel` 出现时**不要**急着开灰度开关：先确认是否有渠道仍 `enabled=true` 且非 `DOWN`。

**启动失败的常见原因（FR-032 强校验，设计如此）**：`channels` 为空 / 某渠道 `priority` 缺失 /
出现未注册的渠道码 / 全部渠道 `enabled=false` / `availability.status` 非法值。错误信息会列出合法取值清单。

### 4.2 用户支付限额配置（spec 027 / ADR-0071，非密钥）

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `payment.limit.enabled` | `true` | `false` 时限额子域不参与建单路径，行为与今天逐字节一致（FR-024） |
| `payment.limit.reserve-ttl` | `900s` | 在途占用 TTL。**启动强校验 `> 105s`**（= `payment.reliability.timeout` 30s + `query-max-attempts` 5 × `query-interval-ms` 15s），否则**拒启** |
| `payment.limit.compensation-interval-ms` | `30000` | 补偿扫描间隔（扫「payment 已终态但只有 RESERVE」补结算） |
| `payment.limit.redis.key-prefix` | `limit:pending:` | 在途占用过期索引 key 前缀 |

**⚠️ 跨服务人工一致项**：`payment.limit.reserve-ttl`（900s）须与 order 侧 `order.timeout.ttl-seconds`
保持一致——两处同名语义但**配置无法跨服务共享**，改一处必须同步改另一处。若 `reserve-ttl` 小于支付侧
最大自动收敛窗口（105s），会释放一笔正在被主动查询收敛的支付。

**限额行为（运维关注点，均为预期语义）**：

- **默认不限额**：查不到 `user_payment_limits` 行 = 不约束。`demo/seed.sh` **不**为 `demo-user` 播种限额，
  故 `traffic-gen.sh` / E2E 不会被 409 打断（FR-025 / D8）。
- **软超限可见**：`payment_limit_overrun{period}` 有值 + `limit.overrun` 审计出现，说明有支付在
  TTL 释放后才成功（或限额被调低）。**这是设计允许的**（D12：新支出硬约束、已发生事实软记账），
  处置是「下一笔被拒直至周期重置」，**不要**手工去改 `used_minor`。
- **Redis 不可用 / 未配置**：`GET /internal/limits/diagnostics` 显示降级为 `NoopLimitExpiryIndex`；
  表现为在途占用**保持不被回收**（保守占用）。这是 fail-open（INV-9.2），**不拦截支付**，属预期。
- **配置限额**：`PUT /internal/limits/users/{userId}`（body 含 `currencyCode` + 三档 `*LimitMinor`，可空）；
  查询 `GET /internal/limits/users/{userId}`；清除 `DELETE /internal/limits/users/{userId}`。

**限额指标**：`payment_limit_total{op,result,period}` / `payment_limit_exceeded` /
`payment_limit_overrun{period}` / `payment_limit_compensated` /
`payment_limit_redis_error` / `payment_limit_redis_unavailable`；
审计 `limit.exceeded`（超限拒绝）与 `limit.overrun`（软超限）。

### 4.3 支付宝沙箱渠道（spec 030 / ADR-0076）

沙箱是**真实渠道**（走公网 `openapi-sandbox.dl.alipaydev.com`），不是 mock 的换皮。`enabled` 默认 `false`，
**没显式打开就绝不连真实渠道**；同时是一键 kill switch——沙箱出问题改配置即可全量停用，不必重新发版。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `PAYMENT_ALIPAY_SANDBOX_ENABLED` | `false` | 总开关。`false` 时**不装配** `AlipaySdkGateway` Bean（tasks Q6） |
| `PAYMENT_ALIPAY_SANDBOX_GATEWAY_URL` | `https://openapi-sandbox.dl.alipaydev.com/gateway.do` | 支付宝沙箱网关（一般不用改） |
| `PAYMENT_ALIPAY_SANDBOX_APP_ID` | 空 | `enabled=true` 时**启动期强校验**，缺失即拒启 |
| `PAYMENT_ALIPAY_SANDBOX_APP_PRIVATE_KEY` | 空 | 同上。应用私钥（PKCS#8），请求签名用 |
| `PAYMENT_ALIPAY_SANDBOX_ALIPAY_PUBLIC_KEY` | 空 | 同上。支付宝公钥，**notify 验签**用 |
| `PAYMENT_ALIPAY_SANDBOX_HTTP_TIMEOUT_MS` | `10000` | 与全局 `http-timeout-ms`(1500ms) 解耦（沙箱走公网，量级不同）。**MUST < `payment.reliability.timeout`(30s)**，否则「整体超时先触发、渠道还在等」，把不确定态记成确定态 |

**两道启动期强校验**（配错不许静默走默认，ADR-0049 第 2 条）：

1. `AlipaySandboxProperties.@PostConstruct validate()`：`enabled=true` 且三项密钥任一缺失 ⇒ 启动失败，**一次性列出全部缺失项**（FR-134）；
2. `deployment/start-all.sh`：`enabled=true` 但三项未导出 ⇒ `exit 1`，不会带着半套配置把栈拉起来。

**密钥纪律**（FR-290 / INV-2）：禁硬编码 / 禁入库 / 禁明文日志。`AlipaySandboxProperties.toString()`
已剔除密钥字段——即便误打了整个配置对象也不会泄漏。全仓检索应**零**私钥 PEM、`client_secret` 明文。

**不静默降级**（FR-241 / INV-8）：带 `X-Dye-Tag: SANDBOX` 但 `enabled=false` ⇒ `400 INVALID_ARGUMENT`。
绝不会「明明在测沙箱、却悄悄走了 mock」——那会让排查彻底失控。

### 4.4 渠道回调地址与内网穿透（spec 030 / FR-103 / tasks Q5）

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `payment.channel.notify-url` | `PAYMENT_CHANNEL_NOTIFY_URL` | 空 | 渠道**异步回调（notify）**地址，**配置单值**（不按单动态拼） |
| `payment.channel.return-url` | `PAYMENT_CHANNEL_RETURN_URL` | 空 | 买家付款后页面跳回地址（**非**资金事实，可空） |

- **notify 是资金事实的唯一权威来源**；页面跳回（`returnUrl`）**不承载**资金事实，MUST NOT 据其推进支付状态。
- **未配置 `notify-url` 时**：mock 链路不受影响（不读该字段）；沙箱 / 真实渠道链路在 `charge` **之前**
  抛 `400 INVALID_ARGUMENT`（`sandbox charge requires callbackUrls.notifyUrl`）——**不静默降级**（INV-8）。
  否则会出现「下单成功却永远收不到钱的通知」这种最难排查的故障。
- **沙箱 / 真实渠道下此地址 MUST 公网可达**：填 `http://localhost:8084/...` 支付宝回调不到，
  支付会一直停在 `PROCESSING`（INV-6：渠道受理 ≠ 买家已付款）。

**本地演示收真实沙箱回调 → 需要内网穿透**（示例，任选其一）：

```bash
# 例：cloudflared（无需注册即可拿临时域名）
cloudflared tunnel --url http://127.0.0.1:8084
# 输出形如 https://xxxx.trycloudflare.com
export PAYMENT_CHANNEL_NOTIFY_URL=https://xxxx.trycloudflare.com/internal/channels/alipay/notify
export PAYMENT_CHANNEL_RETURN_URL=https://xxxx.trycloudflare.com/cashier/return
```

⛔ **穿透的风险（必须先读）**：穿透等于把 payment-service 暴露到公网。当前 `/internal/**` 鉴权
（ADR-0024）与 JSON 回调验签（ADR-0025）仍为**空实现**，仅本次新增的支付宝 notify 端点自带真实
RSA2 验签（spec 030）。因此：

- **只在临时演示时开启，用完立即关闭**穿透与 `PAYMENT_ALIPAY_SANDBOX_ENABLED`；
- 演示期间不要在同一实例上跑真实资金数据；
- 事后核对 `FINANCIAL_AUDIT` 日志与对账差异（§6「疑似伪造渠道回调」条目）。

**排障**：notify 被拒看 `payment.notify_rejected{reason}` 指标与审计 `notify.rejected`（三段式校验：
验签 → 渠道引用 → 金额/币种，任一不过即**不推进状态**）。

## 5. 指标目录（唯一登记处，spec 035 / G1 / ADR-0083）

> **纪律（M-2/HC-3）**：新指标未进本表不得上线；新 label 必须同 PR 登记值域。**告警意义 = 无 的指标不进目录**
> （反数量竞赛）。`metrics.counter/timer/gauge` 的 Micrometer 名 → Prometheus 名：点号→下划线、
> counter 自动补 `_total`、timer 展开 `_count/_sum/_bucket`。类型判据（N-4）：积压/存量 = Gauge，
> 发生次数 = Counter；金额一律最小货币单位整数（N-3）。
> 本表覆盖 spec 035 §5.2 目录 + 告警引用项 + 031/032/034 新增收口；order/fulfillment/权益等域的
> 既有低危计数可用 §5.2 的 grep 命令枚举，不逐条登记（历史存量零改名零删除，M-1）。

### 5.1 目录（指标 → 类型 → 值域 → 意义 → 告警 → Owner）

**Payment / Refund / 渠道（Owner: payment）**

| 指标（Prometheus 名） | 类型 | 维度（值域） | 业务意义 | 告警映射（§5.4 锚点） |
|---|---|---|---|---|
| `payment_initiated_total` | Counter | `module` | 支付单创建量 | 分母（突降=下单链路故障，看板②） |
| `payment_timeout_total` | Counter | `module` | 超时判定（转 UNKNOWN 前必经） | **A-04 PaymentUnknownBacklog** |
| `payment_retry_total` / `payment_retry_exhausted_total` | Counter | `module` | 重试 / 重试达上限转主动查询 | PaymentRetryExhausted |
| `payment_query_total` / `payment_query_exhausted_total` | Counter | `module` | 渠道主动查询 / 查询也耗尽（只能人工收敛） | PaymentQueryExhausted（critical） |
| `payment_unknown_duration_seconds` | Timer | `module` | UNKNOWN 收敛耗时（**只含已收敛者，不能算存量**） | 看板观测，不作告警 |
| `payment_unknown_age_total` / `refund_unknown_age_total` | Counter | `bucket`(`0_5m`/`5_30m`/`30m_24h`/`gt_24h`) | UNKNOWN 老化分布（034 §7.2） | **A-05 / A-06 UnknownAgedOver24h**（critical） |
| `payment_duplicate_total` / `payment_duplicate_callback_total` | Counter | `module` | 幂等命中 | 突增=上游重试风暴（观测） |
| `payment_order_notify_failed_total` | Counter | `module` | 支付成功通知订单失败（RPC 抖动，对账兜底） | 观测（联动 A-07 家族） |
| `payment_order_illegal_state_rejected_total` | Counter | `module` | 款已收、订单非法前态拒收 = **资金风险** | PaymentOrderIllegalStateRejected（critical） |
| `payment_routing_total` | Counter | `result`(`explicit`/`explicit_disabled`/`routed`/`no_available_channel`/`unavailable_explicit`), `routed`(渠道码) | 路由决策分布 | `no_available_channel`>0=配置事故（观测） |
| `refund_rejected_total` | Counter | `module`, `reason` | 退款被业务规则拒绝 | RefundFailure |
| `refund_order_notify_failed_total` / `refund_ledger_posting_failed_total` | Counter | `module` | 退款下游联动失败（事实不回滚） | RefundDownstreamFailure（critical） |
| `channel_request_total` | Counter | `channelCode`(配置枚举), `result`(`success`/`failed`/`unknown`/`exception`) | 渠道出站调用量与结果（035 T32 埋于查询路径） | A-03 分母 |
| `channel_timeout_total` | Counter | `channelCode` | 渠道超时（**不是**平台错误；UNKNOWN/异常代理） | **A-03 ChannelTimeoutSpike**（critical） |
| `limit_inflight_leak` | Gauge | `module`, `window`(`settle_missing`) | 额度预占泄漏残留（027 两阶段） | **A-19 LimitInflightLeak** |
| `payment_limit_compensation_failed_total` | Counter | `module` | 额度补偿执行失败 | 观测（联动 A-19） |

**Ledger 账务（Owner: ledger）**

| 指标 | 类型 | 维度（值域） | 业务意义 | 告警映射 |
|---|---|---|---|---|
| `ledger_posted_total` | Counter | `module`, `eventType`(规则枚举), `source`(`PAYMENT`/`REFUND`/`SETTLEMENT`/…) | **记账吞吐 = 资金事实入账速率** | SLO-4 分母参照 |
| `ledger_posting_failed_total` | Counter | `module`(payment/refund/settlement 调用方进程) | 调用方视角入账失败（事实与账本开始背离） | **A-07 LedgerPostingFailure**（critical，035 前零告警 P0 缺口） |
| `audit_posting_failed_total` | Counter | `module`(reconciliation) | 审计调整记账网关失败 | **A-07**（同条 or 分支） |
| `ledger_posting_pending` | Gauge | `module` | 失败台账 PENDING 行数 = **账务积压**（034 §9，三调用方同名各自登记） | **A-08 LedgerPostingPendingBacklog**；SLO-4 主信号 |
| `ledger_posting_abandoned_total` | Counter | `module` | 补投重试耗尽置 ABANDONED（只能人工） | **A-09 LedgerPostingAbandoned**（critical） |
| `ledger_unbalanced_total` | Counter | `module`, `currency`(3 位码) | 构造期借贷平衡拒绝（035 T31 新增） | **A-10 TrialBalanceBreak**（critical） |
| `trial_balance_break_total` | Counter | `module`, `currency` | 关账试算不平次数（**偏差**：spec 表格初稿为 Gauge{currency,period}，按 N-4「发生次数=Counter」实现且避开 period 慢性泄漏，账龄由 A-13/看板承担） | **A-10** |
| `balance_rebuild_applied_total` | Counter | `module` | 余额投影 rebuild 次数（**正常应 ≈0**） | **A-11 BalanceRebuildUsed** |
| `period_close_rejected_total` | Counter | `module`, `reason`(`pending_posting`/`unbalanced`) | 关账被拒（运营信号） | 看板⑧/观测，非告警 |

**Reconciliation 对账（Owner: finance）**

| 指标 | 类型 | 维度（值域） | 业务意义 | 告警映射 |
|---|---|---|---|---|
| `reconciliation_difference_total` | Counter | `module`, `kind`(032 §8.3 八类) | 差异产生速率 | ReconciliationDifference（A-12 雏形） |
| `reconciliation_difference_resolved_total` | Counter | `module` | 差异收口速率 | A-13 减项 |
| （无独立 gauge）**对账积压** | PromQL 差值 | — | `Σdifference − Σdifference_resolved` = 未收口存量（035 plan §3 裁决：不新增 `reconciliation_pending` 埋点，**偏差记录**） | **A-13 ReconciliationPendingAging** |
| `reconciliation_statement_import_total` | Counter | `channel`, `result`(`accepted`/`rejected`/`duplicate`) | 账单导入健康度（032） | **A-14 StatementSourceDegraded** |
| `reconciliation_statement_unavailable_total` | Counter | `channel` | 无可用 NORMALIZED 导入（**取代已退役的 sample.csv 静默回退**，`statement_fallback` 不再存在） | **A-14**（>0 即告警） |
| `reconciliation_autodisposition_total` | Counter | `policy`(默认仅 `amount-equal-auto`), `outcome`(`succeeded`/`failed`) | 自动处置引擎执行结果（032，默认 OFF） | 观测（启用后 failed 突增=规则误伤信号） |
| `reconciliation_difference_amount_minor_total` | Counter | 金额（minor 整数） | 差异金额累积 | 观测（看板 对账 行） |

**Settlement 结算（Owner: settlement）**

| 指标 | 类型 | 维度（值域） | 业务意义 | 告警映射 |
|---|---|---|---|---|
| `settlement_pending_amount` | Gauge | `module`, `state`(`pending`/`processing`/`unknown`) | **积压金额**（未收口批次净额绝对值，minor；钱压着不是条数压着，035 T33） | **A-15 SettlementBacklog** |
| `settlement_failed_total` | Counter | `module` | 出款批次失败 | **A-16 SettlementBatchFailure**（critical） |
| `settlement_gate_rejected_total` | Counter | `module`, `reason`(闸门枚举) | 已确认事实闸门拒绝（fail-closed） | 高值=上游账务/对账不健康（观测+首查） |

**MQ 消息通道（Owner: oncall）**

| 指标 | 类型 | 维度（值域） | 业务意义 | 告警映射 |
|---|---|---|---|---|
| `mq_prepared_total` / `mq_committed_total` / `mq_rolled_back_total` | Counter | `topic`(`payment`/`order`/`settlement` 等枚举) | 事务消息生产侧：半消息落 ZSet / 提交可见 / 事务回滚丢弃（034 §10 语义） | 吞吐分母；committed 骤降=本地事务异常 |
| `mq_consumed_total` / `mq_retried_total` / `mq_dead_letter_total` / `mq_checked_total` / `mq_half_backlog_total` | Counter | `topic`, `group`/`state` | 消费 / 退避重投 / 转死信 / 回查分派 / 到期半消息 | A-18 前兆链（half_backlog→checked UNKNOWN→dead_letter） |
| `mq_dlq_size` | Gauge | `topic` | **死信积压 = 已丢失的自动化出口**（034 §10） | **A-18 MqDlqNonEmpty**（critical） |
| `mq_half_backlog_total` | Counter | `topic`(`all`) | 半消息到期待回查 | A-18 前兆（观测） |
| （exporter）`redis_stream_group_lag` / `redis_stream_group_messages_pending` | Gauge | `stream`, `group` | 消费积压 / PEL 未确认（依赖 compose `REDIS_EXPORTER_CHECK_STREAMS=mq:stream:*`，035 T34 修正） | **A-17 MqConsumerLag** |

**平台 / 治理（Owner: platform）**

| 指标 | 类型 | 维度（值域） | 业务意义 | 告警映射 |
|---|---|---|---|---|
| `dye_tag_rejected_total` | Counter | `reason`(`invalid`) | 非法染色值被拒（fail fast，ADR-0049） | **A-20 DyeRejectedAnomalous**（info：转工单不 page） |
| `http_server_requests_seconds_*` | Timer(自动) | `uri`(归一化模式), `method`, `status`, `job` | 四信号 + **SLO-1/2/3 的 SLI 源** | A-01 / A-02（经 burn rate） |
| `FINANCIAL_AUDIT` 日志 | 日志流 | 单行 JSON（traceId/bizNo/幂等键/金额/币种/前后态） | 资金动作审计 | 各资金告警的第一步取证 |

⛔ **确认不存在的埋点（勿再引用）**：`payment_succeeded/failed/unknown_total`（终态分布请查库）、
`payment_callback_signature_rejected`（验签空实现 ADR-0025）、`payment_risk_triggered`（风控已删 ADR-0028）、
`payment_internal_auth_rejected`（鉴权空实现 ADR-0024）、`statement_fallback`（032 退役回退）。

### 5.2 告警规则与埋点的同步约束

业务告警定义在 `deployment/prometheus/rules/payment-alerts.yml`（24 条，五要素齐，spec 035 §9），
SLO Recording Rules 在 `deployment/prometheus/rules/slo-recording.yml`（4 个 SLI + 预算余量 + 双窗
burn rate），Grafana 面板见 `deployment/grafana/dashboards/payment-arch.json`
（「业务告警 · 资金风险信号」+「⑦ SLO 与错误预算」+「⑧ 资金健康」行）。

**告警表达式引用的是 Micrometer 指标名（点号→下划线、计数器加 `_total`）。若表达式里的指标
在代码中不存在，规则永远不会触发，且不会有任何报错**——2026-09-09 实测：旧规则引用的
`payment_unknown_total`、`refund_failed_total` 在 Prometheus 里均为 0 series，属于上线起从未触发过的死规则。

因此变更埋点时**必须**同步四处：代码 `metrics.counter/timer/gauge` 键 → 本目录 §5.1 → 告警规则 → Grafana 面板。
核对方式（需全栈运行）：

```bash
# 1) 代码里实际埋了哪些指标
grep -rhoE 'metrics\.[a-z]+\("[a-z_.]+"' <service>/src/main/java | sed 's/.*("//;s/"//' | sort -u

# 2) Prometheus 里这些指标是否真的有 series（无 series 可能是从未触发，也可能是名字写错）
curl -s --noproxy '*' --data-urlencode 'query=payment_timeout_total' \
  http://127.0.0.1:9090/api/v1/query | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['result']))"

# 3) 规则文件语法（两个文件都要查；promtool 同时校验 recording rule 表达式合法性）
docker cp deployment/prometheus/rules/payment-alerts.yml payment-prometheus:/tmp/alerts.yml
docker cp deployment/prometheus/rules/slo-recording.yml payment-prometheus:/tmp/slo.yml
docker exec payment-prometheus promtool check rules /tmp/alerts.yml /tmp/slo.yml
```

### 5.3 流量染色观测（spec 030 / ADR-0076）

| 项 | 值 |
|---|---|
| 请求头 | `X-Dye-Tag: MOCK` \| `X-Dye-Tag: SANDBOX`（大小写不敏感） |
| 缺省 | 未带 / 空白 ⇒ **`MOCK`**（与 spec 030 前逐字节一致） |
| MDC key | `dyeMode`（日志可按模态过滤） |
| 响应头 | 回写 `X-Dye-Tag`，便于确认「这一跳到底按哪个模态执行的」 |
| 过滤链定序 | `TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)`（FR-165） |
| 拒绝指标 | `dye_tag_rejected_total{reason=invalid}` |

- **非法值 fail fast**：`SANDBOX` 拼成 `SANBOX` ⇒ `400` + 指标，**不静默回落 MOCK**（ADR-0049 第 2 条）。
- **出站透传**由 `DyeRequestInterceptor` 保证。入站与出站 MUST 同批生效（INV-5）：只做入站、不做出站 ⇒
  下游服务读不到染色 ⇒ 全线按未染色处理（历史教训：`InternalToken` 曾因只做入站导致全线 403）。
- **代理无需改动**：`mock-channel-web` 的 `DemoProxyController` 是**黑名单式**头透传（只跳过 hop-by-hop），
  `X-Dye-Tag` 原样透传。
- ⛔ **染色不是安全边界**：它不参与鉴权，也不参与路由决策。`ChannelRouter` 不得读 `DyeContext`
  （INV-3），由 `architecture-tests` 在构建期强制。看到 `X-Dye-Tag` 被用作权限依据即为缺陷。

### 5.4 告警处置（24 条 · 五要素齐，spec 035 §9 / §14 M-4）

> 段落模板：**症状 → 影响面 → 第一步查询 → 判定分支 → 处置动作 → 升级路径与 Owner**。
> `A-xx` 编号与 `payment-alerts.yml` 头部映射注释一一对应；`[目标]` 阈值未实测前
> **只出报表不 page**（H-035-1 已批准的静默期口径）。severity=critical 的六条（A-01/03/05/06/07/09/10/14/16/18
> 中标 critical 者）为资金路径，其余 warning/info 走工单节奏。

**A-01 SloAvailabilityBudgetBurn（critical，owner oncall）** 症状：资金入口 30 天错误预算以 >14.4× 速率超烧（fast 1h 且 slow 6h 双窗同越）。影响面：下单/支付受理/退款受理的可用性承诺。第一步：看板⑦ 看 `slo:burn_rate_fast{group="payment_entry"}` 哪个入口在掉。分支：若 `up{}=0` ⇒ 进程挂了走 §6；若 5xx 集中于某 uri ⇒ 走 ACCESS_LOG `status=5xx` 聚合定位接口。处置：修复或回滚该接口；预算烧完后 30 天内后续越阈只做记录（发布冻结评估）。升级：owner 处置不了 30 分钟升级到平台值班。

**A-02 SloLatencyP99Breached（warning，owner oncall）** 症状：同步接口 P99 超阈（查询 500ms / 命令 1s）且双窗 burn 同越。影响面：用户等待、上游超时连锁。第一步：`http_server_requests` 分 uri 看哪条路径变慢。分支：DB 慢（HikariCP 活跃连接打满）⇒ 查慢 SQL；GC/资源 ⇒ §8 容量。处置：限流/扩容/回滚变慢变更。升级：持续 1h 未回落升 payment/ledger 对应域 owner。

**A-03 ChannelTimeoutSpike（critical，owner payment）** 症状：`channel_timeout/channel_request` >10%（样本≥5）持续 10m。影响面：UNKNOWN 成批产生的前兆，渠道不稳定。第一步：分 `channelCode` 判断单渠道还是全域；沙箱/公网可达性。分支：单渠道 ⇒ 摘除该渠道路由（`payment.routing.channels.*.enabled`）；全域 ⇒ 网络/本侧超时档（034 §8 三档）。处置：切流+通知渠道方；预期预告 A-04 跟进。升级：渠道方无响应 2h 升 payment owner。

**A-04 PaymentUnknownBacklog（warning，owner payment）** 症状：5m 内新增超时转 UNKNOWN。影响面：资金事实未定。第一步：`GET /internal/payments/unknown` 看队列。分支：查询在收敛（`PaymentQueryExhausted` 未同烧）⇒ 观察；耗尽 ⇒ 转 A-05/人工。处置：等待自动收敛为主，禁止直接改 status（状态机红线）。升级：与 A-05 联动。

**A-05 / A-06 UnknownAgedOver24h（critical，owner oncall）** 症状：支付/退款 UNKNOWN 单龄 >24h（`bucket="gt_24h"`）。影响面：**跨期悬置资金事实**——关账门禁与对账兜底都会被挡。第一步：`GET /internal/payments/unknown?age=` 定位单。分支：渠道侧有终态 ⇒ 人工 resolve 推进；渠道无应答 ⇒ 与渠道方核销。处置：人工 resolve 端点（034 §7.1），落 `FINANCIAL_AUDIT`。升级：涉及金额 > 阈值直接升资金运营。

**A-07 LedgerPostingFailure（critical，owner ledger）** 症状：`ledger_posting_failed` 或 `audit_posting_failed` 出现。影响面：资金事实与账本开始背离。第一步：`GET pending-postings?status=PENDING`（034 §9）确认台账已登记。分支：已登记 ⇒ 自动补投在跑，观察是否清零；未登记 ⇒ 登记链路缺陷，开缺陷单。处置：Ledger 服务健康检查；连续出现走 A-09 人工 replay。升级：30m 不收敛升 ledger owner。

**A-08 LedgerPostingPendingBacklog（warning，owner ledger）** 症状：PENDING 台账行 >0 超 10m。影响面：账务积压（下游 Ledger/order 持续不可达）。第一步：pending-postings 明细看 caller 与原因。分支：Ledger 宕 ⇒ 恢复后自动收敛；同批反复失败 ⇒ 数据问题（科目缺失/期间 CLOSED）。处置：修依赖或修数据后等补投；退避耗尽前一般无需人工。升级：出现 ABANDONED 转 A-09。

**A-09 LedgerPostingAbandoned（critical，owner ledger）** 症状：补投重试耗尽置 ABANDONED。影响面：该行**退出自动化**，只能人工。第一步：ABANDONED 列表取 id。处置：修根因后 `POST /internal/*/pending-postings/{id}/replay`（034 §9，幂等重放）；补投成功后核对对账 MISSING_POSTING 不再产生。升级：立即升 ledger owner（资金账实不符候选）。

**A-10 TrialBalanceBreak（critical，owner ledger，最高优先）** 症状：试算不平或构造期不平衡拒绝出现。影响面：**复式记账被破坏 = 账务不可信**。第一步：`GET /trial-balance` 定位不平币种与差值。分支：构造期拒绝（`ledger_unbalanced`）⇒ 分录未落，查事件构造；关账不平（`trial_balance_break`）⇒ 已有存量不平。处置：结算出款已被 031 §10 门禁 fail-closed 挡住（H-035-4 裁决：不再加自动熔断）；`/balances/rebuild` 前后分录比对定位漂移。升级：账务 owner 直接介入，未平前禁止关账与出款。

**A-11 BalanceRebuildUsed（warning，owner ledger）** 症状：发生过投影 rebuild（正常应 ≈0）。影响面：说明存在绕过 PostingEngine 的写或已发生漂移。第一步：谁调了 `/balances/rebuild`（ACCESS_LOG）+ 漂移原因。处置：若为人工修复则补齐根因缺陷单；若为自动触发属缺陷。升级：无资金损失则走缺陷流程。

**A-12 ReconciliationDifference（warning，owner finance）** 症状：存在未解决对账差异。影响面：外部资金事实与内部不一致。第一步：032 差异队列按 `kind` 看形态（单渠道 or 全域）。分支：AMOUNT_MISMATCH ⇒ 核对原始报文；MISSING_LOCAL/MISSING_CHANNEL ⇒ 账单完整性。处置：`resolve(diffNo)` 人工收口（备注必填，ADR-0019）。升级：单渠道集中 ⇒ 联动 A-14。

**A-13 ReconciliationPendingAging（warning，owner finance）** 症状：未收口差异（Σdifference−Σresolved）>0 持续 24h。影响面：**关账将被门禁挡**（031 §11）。第一步：PENDING 列表分型 + 是否单商户集中。处置：批量收口或 SUSPENSE 挂账决策（人类口径）。升级：账期截止前 3 天未清升 finance owner。

**A-14 StatementSourceDegraded（critical，owner finance）** 症状：账单不可得或导入整批 REJECTED（1d 窗）。影响面：**对账失去外部锚点**（sample.csv 回退已退役，宁停不假对账）。第一步：渠道账单文件/接口可用性（`channel` 标签定位）。分支：`statement_unavailable` ⇒ 催渠道出单；`import{result="rejected"}` ⇒ 解析失败明细（032 §11 导入批次状态）。处置：修复格式/重传后重导（指纹幂等，可安全重放）。升级：该渠道当期无法对账 ⇒ finance 决策是否延后关账。

**A-15 SettlementBacklog（warning，owner settlement）** 症状：未收口批次净额 > 阈值（100 万元占位 [目标]）。影响面：商户的钱压着。第一步：批次状态列表（`state` 标签分 pending/processing/unknown）。分支：gate 拒绝多 ⇒ 看 `settlement_gate_rejected_total{reason}` 回溯上游（对账未确认/事实缺商户）。处置：推进上游收口。升级：`state=unknown` 批次 ⇒ 出款结果未知，按 A-16 流程先于本条处理。

**A-16 SettlementBatchFailure（critical，owner settlement）** 症状：出款批次失败。影响面：出款动作与账本可能半成功。第一步：同 batchNo 可否安全重放（034 X-14 幂等边界：EXECUTING/UNKNOWN 态禁重放）。分支：Ledger 已记出款分录 ⇒ 只重推出款指令；未记 ⇒ 整批重试。处置：人工驱动，禁止脚本盲重放。升级：立即升 settlement owner + 资金运营。

**A-17 MqConsumerLag（warning，owner oncall）** 症状：`redis_stream_group_lag`/`messages_pending` >100 持续 10m。影响面：事件在堆、下游状态滞后。第一步：消费方服务 `up{}`；`XAUTOCLAIM`（claimStale）是否触发。分支：进程死 ⇒ 拉起即收敛；进程活在但慢 ⇒ 看消费异常日志/DB 瓶颈。处置：重启消费者或扩容。升级：伴随 A-18 说明已在转死信，优先处理 A-18。

**A-18 MqDlqNonEmpty（critical，owner oncall）** 症状：DLQ 非空。影响面：**消息永久失去自动处理机会**。第一步：DLQ 管理端点看条目 `dlqReason`（消费超上限 or 回查 UNKNOWN 超上限）。处置：修复根因后按 034 §10 replay（单条或按 topic）；确认幂等承接方不会重复副作用。升级：涉及资金事件（记账/结算）⇒ 同步对应域 owner 核对台账。

**A-19 LimitInflightLeak（warning，owner payment）** 症状：`limit_inflight_leak` >0 持续 30m。影响面：用户额度被已死预占错限。第一步：LimitCompensationScanner 日志是否在跑。处置：手工触发一轮补偿；找 RESERVE 无 CONFIRM/RELEASE 的悬挂单核对支付终态。升级：持续不减 ⇒ 补偿判据缺陷，开缺陷单升 payment owner。

**A-20 DyeRejectedAnomalous（info，owner platform）** 症状：有调用方发送非法 `X-Dye-Tag`。影响面：无资金影响，配置漂移信号。第一步：ACCESS_LOG 定位来源服务。处置：转工单给来源团队改配置（值域见 ADR-0021）。**不 page**。

## 6. 常见故障与处置

| 现象 | 可能原因 | 处置 |
| --- | --- | --- |
| `POST /payments` 500 `DuplicateKeyException` | `MockChannelAdapter` 重启后渠道引用从 1 重新计数，撞 `payment_attempts.uk_attempts_channel_reference` | 已用运行级 UUID 前缀修复；若仍出现，清空历史 `payment_attempts` 后重启 |
| 支付长时间 `UNKNOWN` | 渠道超时后主动查询未收敛 | 用 `POST /payments/{id}/resolve` 带 `X-Admin-Token` 人工裁定（仅接受 SUCCESS/FAILURE） |
| 沙箱下单 `400 sandbox charge requires callbackUrls.notifyUrl` | 未配 `PAYMENT_CHANNEL_NOTIFY_URL`（Q5 配置单值） | 配 `payment.channel.notify-url` 为**公网可达**地址（本地需内网穿透，见 §4.4）；这是配置错误，**不会**自动降级为 mock（INV-8） |
| 带 `X-Dye-Tag: SANDBOX` 却 `400` | `PAYMENT_ALIPAY_SANDBOX_ENABLED=false`，或三项沙箱密钥缺失 | 按 §4.3 配齐 6 项后重启；启动期强校验会**一次性列出**缺失项 |
| `X-Dye-Tag` 拼错（如 `SANBOX`）⇒ `400` | 非法取值 | 这是**预期**的 fail fast（不静默回落 MOCK）；看 `dye_tag_rejected_total{reason=invalid}` |
| ~~渠道回调全部 `403`~~ | ~~验签失败~~ | ⛔ **不会发生**：验签为空实现（ADR-0025），回调一律放行。若将来接入验签后出现，核对 `X-Channel-Timestamp`（毫秒）与窗口、核对密钥、看 `payment.callback_signature_rejected` 的 `reason` |
| 回调 Controller 报 body 为空 | 过滤器消费了原始 body 却未换包装器 | 不应发生（`CachedBodyHttpServletRequest` 已处理）；若出现检查 `WebConfig` 过滤器注册 |
| ~~内部端点 `403` / `503`~~ | ~~鉴权失败~~ | ⛔ **不会发生**：鉴权为空实现（ADR-0024），`/internal/**` 恒定放行。接入真实鉴权后按 §4「接入真实鉴权/验签时」的 4 步复核（入站与出站**必须成对启用**） |
| **疑似伪造渠道回调把支付翻转为 SUCCESS** | 验签为空实现（ADR-0025 已知风险） | 本期**无技术拦截手段**。处置：以 `FINANCIAL_AUDIT` 日志 + 对账差异定位，人工 `POST /payments/{id}/resolve` 收敛；**根本解法是网络层**：payment-service 不得暴露公网 |
| **疑似越权调用 `/internal/**`** | 鉴权为空实现（ADR-0024 已知风险） | 同上：依赖安全组 / 服务网格隔离；以审计日志 + 对账差异兜底核对 |
| 对账差异全为 `PLATFORM_ONLY` | 渠道账单是静态 fixture（`sample.csv`），真实渠道引用带 runId 前缀 | 设计内表现，非故障；如需复位用 `deployment/demo/truncate-transactional.py`（只清事务表、保留科目预设）；要彻底重来则用 `deployment/demo/reset.sh` |
| `orders` 表缺 `payment_id` 导致 order 500 | 用户库是旧 schema 建的 | `ALTER TABLE orders ADD COLUMN payment_id BIGINT NULL` |
| `GlobalExceptionHandler` 把异常吞成 `INTERNAL_ERROR` | 未捕获异常统一转 `{"code":"INTERNAL_ERROR"}` | 定位需看服务 err 日志或临时加堆栈输出（定位后还原） |

## 7. 回滚

当前形态（单实例 + 独立 Schema）下的回滚粒度按「Schema + 服务」成对处理：

1. **停服**：先停上游（order/payment/refund），再停下下游（ledger/entitlement 最后停）。
2. **回滚 Schema**：`deployment/schema/` 中的变更脚本需配套 down 脚本；无 down 脚本时从备份恢复对应 Schema。
3. **回滚代码**：替换 jar 后按第 2 节顺序重启。
4. **账本特别处理**：`ledger-service` 是资金事实的最终来源，**回滚前必须先确认没有产生新 Posting**；若已产生，优先用补偿分录而不是删数据（账本分录只追加）。

## 8. 容量与拆分触发条件

当前**不拆**任何服务。达到下列任一条件才启动拆分评估（ADR-0030）：

- **容量**：单实例 CPU 持续 > 70%，或连接数长期 > 最大连接数 70%，且垂直扩容已到性价比拐点。
- **隔离**：某服务的慢查询 / 锁等待 / 跑批（结算、对账扫描）实质性影响其它服务 P99。
- **合规/归属**：某服务需要独立备份策略、retention 或访问控制边界（首推 `ledger`）。
- **可用性**：某服务需要独立主从切换或跨可用区部署，共享实例无法满足 RTO/RPO。

触发后按 `split-proposal-template.md` 填写提案，四段（问题/收益/成本/回滚）缺一不予评审。

## 9. 演示组件与 demo 脚本（Feature 011）

- **组件**：`mock-channel-web`（端口 8091），演示用，**非生产服务**，不进服务边界（见 §1）。
- **能力**：① 收银台页（点支付后跳转，模拟渠道收银台，可触发 SUCCESS/FAILURE/UNKNOWN 等结果回传）；② 渠道回调转发（`/mock-channel/callback` 把结果回传 payment，支持 `signMode=VALID/FORGED/NONE`）；③ 演示控制台（按钮触发各场景）；④ 同源代理 `/proxy/{service}/**` 解决浏览器跨域。
- **⚠️ 验签占位（ADR-0025 / ADR-0052 ⛔ Not Implemented）**：payment 的 `ChannelCallbackSignatureFilter#verifySignature` 恒放行。因此演示控制台的「伪造签名（FORGED）」按钮**点下去也会被 payment 放行**，不会 403。**本环境无法演示「伪造签名被拒」**——接入真实验签（实现 `verifySignature` + 补 ADR-0052）后才能演示。
- **脚本**：`demo/` 提供 `run-all.sh` 串联五场景（happy-path / refund / unknown / reconciliation / audit）与 `seed.sh` / `restart-payment.sh` / `start-stack.sh` / `stop-stack.sh`。脚本按真实 API 契约编写、断言失败即非零退出。详细前置与断言表见 `demo/README.md`。**`scenario-limit.sh`（spec 027，7 场景 L1~L7）默认不纳入 `run-all.sh`**——它会临时设置并清除限额配置，且 L6 需要把 `reserve-ttl` 调至 5s 重跑 payment，故单独执行。
- **✅ 全栈实跑已通过（2026-09-09）**：容器组 + 10 进程全绿，`run-all.sh` 96 条断言 0 失败（详见 `docs/specs/stage-02-demo-idempotency-seckill/011-demo-showcase/acceptance.md` §4）。实跑踩过的三个坑，复现时先确认已规避：
  1. **JDK 版本**：`spring-boot:run` 需用 JDK 21+（本机默认 `java` 可能是 11，会报 `UnsupportedClassVersionError`）。启动前显式 `export JAVA_HOME=<JDK26 路径>`。
  2. **端口被环境变量抢占**：若环境里存在 `SERVER_PORT` / `PORT`，Spring 的环境变量优先级高于 `application.yml`，服务会被拉到错误端口（实测三个服务被拉到 60956 而启动失败）。`restart-payment.sh` 已显式传 `--server.port`；手工启动时同样显式指定。
  3. **只杀监听进程**：按端口 kill 时必须限定监听态（macOS：`lsof -ti tcp:<port> -sTCP:LISTEN`），否则会连带杀掉持有出站连接的调用方服务（实测一次重启干掉 9 个进程）。
- **配置**：payment 的 `payment.channel.mock-scenario`（ADR-0049）决定 Mock 渠道默认结果（`SUCCESS`/`FAILURE`/`BUSINESS_UNKNOWN`/`TIMEOUT` 等），构造期注入、坏值 FAIL FAST，运行时切换需重启 payment（见 `demo/restart-payment.sh`）。
  - **宿主模式**：`bash demo/restart-payment.sh BUSINESS_UNKNOWN`（以 JVM 参数重载）。
  - **容器模式**：compose 已把该值暴露为 `PAYMENT_CHANNEL_MOCK_SCENARIO`，用
    `PAYMENT_CHANNEL_MOCK_SCENARIO=BUSINESS_UNKNOWN docker compose -f deployment/docker-compose.yml --profile full up -d --force-recreate payment-service`
    重建 payment 容器即可（等价手段，FR-009）。注意真实配置键在 **channel** 层（`payment.channel.mock-scenario`）。
- **013/014 库存与秒杀（Redis 依赖）**：catalog `Stock` 三段式库存 + order `OrderTimeoutScheduler`（Redis ZSet 时间轮）+ 014 的 Redis 缓存 / 秒杀预扣 / 限流均已落地（spec/ADR 见 `docs/specs/stage-02-demo-idempotency-seckill/013-*` / `014-*` 与 `docs/adr/0038-next-stage-decisions.md`）。**需 Redis 可用**：Redis 不可用时超时取消降级（仅记日志跳过）、秒杀预扣 fail-closed 拒绝保护库存。
