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

## 5. 关键指标
| 指标 | 含义 | 关注点 |
| --- | --- | --- |
| `payment.initiated` | 支付单创建 | 与订单创建量对比，突降说明下单链路异常 |
| `payment.timeout` | 超时判定（超时后转 UNKNOWN） | 持续升高说明渠道不稳定，需人工收敛 |
| `payment.retry` / `payment.retry_exhausted` | 重试 / 重试达上限 | `retry_exhausted` 出现即转主动查询，持续出现说明渠道不可用 |
| `payment.query` / `payment.query_exhausted` | 渠道主动查询 / 查询达上限 | `query_exhausted` 出现 = 无法自动收敛，必须人工介入 |
| `payment.unknown.duration`（timer） | UNKNOWN 收敛耗时 | 只反映「已收敛的那些」的耗时，**不能**用于统计 UNKNOWN 存量 |
| `payment.duplicate` / `payment.duplicate_callback` | 幂等命中 | 突增可能是上游重试风暴 |
| `payment.order_notify_failed` | 通知订单失败（多为 RPC 抖动） | 事实不回滚，由对账兜底 |
| `payment.order_illegal_state_rejected` | 支付成功但订单以非法前态拒收 | **资金风险信号**：款已收、订单不认账，需人工核对（已写 `FINANCIAL_AUDIT`） |
| `payment_routing_total` | 路由决策（`result ∈ {explicit, explicit_disabled, routed, no_available_channel, unavailable_explicit}`；`routed` 另带 `routed` 标签＝渠道码） | `no_available_channel` 出现即**配置事故**（全部关渠或全 DOWN），需人工检查 `payment.routing.channels.*.enabled` 与 `availability` |
| `refund.rejected` | 退款被业务规则拒绝 | 突增需确认是否超退/状态非法 |
| `refund.order_notify_failed` / `refund.ledger_posting_failed` | 退款下游联动失败 | 退款事实不回滚，需人工补单 |
| `ledger.posting_failed` | 记账失败 | 出现后资金事实与账本不一致，需补记账 |
| `reconciliation.difference` | 对账差异（按 type 分） | 非 0 即需人工核对原始事实 |
| ~~`payment.succeeded` / `payment.failed` / `payment.unknown`~~ | ~~支付终态分布~~ | ⛔ **无此埋点**（2026-09-09 核对）：终态分布请查库或 `payment.initiated` 与超时/重试计数推导 |
| ~~`payment.callback_signature_rejected`~~ | ~~渠道回调验签被拒~~ | ⛔ **已移除**：验签为空实现（ADR-0025），回调一律放行，无此埋点 |
| ~~`payment.risk_triggered`~~ | ~~最小风控命中~~ | ⛔ **已移除**：风控不做（ADR-0028），类已删除 |
| ~~`payment.internal_auth_rejected`~~ | ~~内部端点鉴权被拒~~ | ⛔ **已移除**：鉴权为空实现（ADR-0024），无此埋点 |
| `FINANCIAL_AUDIT` 日志 | 资金动作审计（独立 logger） | 支付/退款/结算/记账各一条，含 traceId |

### 5.1 告警规则与埋点的同步约束

业务告警定义在 `deployment/prometheus/rules/payment-alerts.yml`，Grafana 面板见
`deployment/grafana/dashboards/payment-arch.json`（「业务告警 · 资金风险信号」行）。

**告警表达式引用的是 Micrometer 指标名（点号→下划线、计数器加 `_total`）。若表达式里的指标
在代码中不存在，规则永远不会触发，且不会有任何报错**——2026-09-09 实测：旧规则引用的
`payment_unknown_total`、`refund_failed_total` 在 Prometheus 里均为 0 series，属于上线起从未触发过的死规则。

因此变更埋点时**必须**同步三处：代码 `metrics.counter/timer` 键 → 告警规则 → Grafana 面板。
核对方式（需全栈运行）：

```bash
# 1) 代码里实际埋了哪些指标
grep -rhoE 'metrics\.[a-z]+\("[a-z_.]+"' <service>/src/main/java | sed 's/.*("//;s/"//' | sort -u

# 2) Prometheus 里这些指标是否真的有 series（无 series 可能是从未触发，也可能是名字写错）
curl -s --noproxy '*' --data-urlencode 'query=payment_timeout_total' \
  http://127.0.0.1:9090/api/v1/query | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['result']))"

# 3) 规则文件语法（把待校验文件拷进 prometheus 容器再用 promtool）
docker cp deployment/prometheus/rules/payment-alerts.yml payment-prometheus:/tmp/check.yml
docker exec payment-prometheus promtool check rules /tmp/check.yml
```

### 5.2 流量染色观测（spec 030 / ADR-0076）

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
