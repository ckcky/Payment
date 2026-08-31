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
| T1 | `refund-service` | 8085 | `refund` | entitlement, fulfillment, ledger, payment | `/actuator/health` |
| T2 | `fulfillment-service` | 8086 | `fulfillment` | entitlement | `/actuator/health` |
| T2 | `entitlement-service` | 8087 | `entitlement` | — | `/actuator/health` |
| T1 | `reconciliation-service` | 8088 | `reconciliation` | payment, refund | `/actuator/health` |
| T1 | `settlement-service` | 8089 | `settlement` | ledger, merchant, reconciliation | `/actuator/health` |
| T0 | `ledger-service` | 8090 | `ledger` | — | `/actuator/health` |
| —（演示） | `mock-channel-web` | 8091 | 无（演示组件，不进服务边界） | —（被 payment 收银台同源代理 `/proxy/**` 调用） | `/actuator/health` |

- 全部服务暴露 `/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus` 与 Swagger UI。
- 关键等级（T0~T3）定义见 `docs/adr/0010-distributed-evolution-decisions.md` ADR-0032。
- ⚠️ **`mock-channel-web`（8091）是 Feature 011 的演示组件，不是生产服务**：它**不进入** `architecture-tests` 的 `ServiceBoundaryTest.SERVICES` 边界（构建期门禁已验证），不承担任何资金/业务事实，仅用于演示收银台跳转、回调转发与演示控制台。舰队规模 = 10 个生产服务 + 1 个演示组件 = 11 个 JVM 进程。

## 2. 启动顺序

依赖方向决定启动顺序（先起被依赖方，可减少启动期熔断）：

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
refund-service (8085) → reconciliation-service (8088) → settlement-service (8089)
   ↓
merchant-service (8081)、catalog-service (8082)（无下游依赖，任意时机）
```

启动后自检：`GET http://localhost:<port>/actuator/health` 逐个确认 `UP`。

## 3. 数据库

- 单实例 MySQL 8（`localhost:3306`，root/root），数据库按服务使用独立 **Schema**：`catalog` / `order` / `payment` / `refund` / `fulfillment` / `entitlement` / `reconciliation` / `settlement` / `ledger`。`merchant-service` 无库，使用内嵌存储（**重启即清空**）。
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

## 5. 关键指标

| 指标 | 含义 | 关注点 |
| --- | --- | --- |
| `payment.succeeded` / `payment.failed` / `payment.unknown` | 支付终态分布 | `unknown` 持续升高说明渠道不稳定，需人工收敛 |
| `payment.duplicate` / `payment.duplicate_callback` | 幂等命中 | 突增可能是上游重试风暴 |
| ~~`payment.callback_signature_rejected`~~ | ~~渠道回调验签被拒~~ | ⛔ **已移除**：验签为空实现（ADR-0025），回调一律放行，无此埋点 |
| ~~`payment.risk_triggered`~~ | ~~最小风控命中~~ | ⛔ **已移除**：风控不做（ADR-0028），类已删除 |
| ~~`payment.internal_auth_rejected`~~ | ~~内部端点鉴权被拒~~ | ⛔ **已移除**：鉴权为空实现（ADR-0024），无此埋点 |
| `ledger.posting_failed` | 记账失败 | 出现后资金事实与账本不一致，需补记账 |
| `FINANCIAL_AUDIT` 日志 | 资金动作审计（独立 logger） | 支付/退款/结算/记账各一条，含 traceId |

## 6. 常见故障与处置

| 现象 | 可能原因 | 处置 |
| --- | --- | --- |
| `POST /payments` 500 `DuplicateKeyException` | `MockChannelAdapter` 重启后渠道引用从 1 重新计数，撞 `payment_attempts.uk_attempts_channel_reference` | 已用运行级 UUID 前缀修复；若仍出现，清空历史 `payment_attempts` 后重启 |
| 支付长时间 `UNKNOWN` | 渠道超时后主动查询未收敛 | 用 `POST /payments/{id}/resolve` 带 `X-Admin-Token` 人工裁定（仅接受 SUCCESS/FAILURE） |
| ~~渠道回调全部 `403`~~ | ~~验签失败~~ | ⛔ **不会发生**：验签为空实现（ADR-0025），回调一律放行。若将来接入验签后出现，核对 `X-Channel-Timestamp`（毫秒）与窗口、核对密钥、看 `payment.callback_signature_rejected` 的 `reason` |
| 回调 Controller 报 body 为空 | 过滤器消费了原始 body 却未换包装器 | 不应发生（`CachedBodyHttpServletRequest` 已处理）；若出现检查 `WebConfig` 过滤器注册 |
| ~~内部端点 `403` / `503`~~ | ~~鉴权失败~~ | ⛔ **不会发生**：鉴权为空实现（ADR-0024），`/internal/**` 恒定放行。接入真实鉴权后按 §4「接入真实鉴权/验签时」的 4 步复核（入站与出站**必须成对启用**） |
| **疑似伪造渠道回调把支付翻转为 SUCCESS** | 验签为空实现（ADR-0025 已知风险） | 本期**无技术拦截手段**。处置：以 `FINANCIAL_AUDIT` 日志 + 对账差异定位，人工 `POST /payments/{id}/resolve` 收敛；**根本解法是网络层**：payment-service 不得暴露公网 |
| **疑似越权调用 `/internal/**`** | 鉴权为空实现（ADR-0024 已知风险） | 同上：依赖安全组 / 服务网格隔离；以审计日志 + 对账差异兜底核对 |
| 对账差异全为 `PLATFORM_ONLY` | 渠道账单是静态 fixture（`sample.csv`），真实渠道引用带 runId 前缀 | 设计内表现，非故障；如需复位用 `.workbuddy/tools/cleanup.py` |
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
- **脚本**：`demo/` 提供 `run-all.sh` 串联四场景（happy-path / unknown / refund / reconciliation）与 `seed.sh` / `restart-payment.sh` / `start-stack.sh` / `stop-stack.sh`。脚本按真实 API 契约编写、断言失败即非零退出；**全栈实跑需 Docker + MySQL**（本环境不可用，见 `docs/specs/011-demo-showcase/acceptance.md` §4）。详细前置与断言表见 `demo/README.md`。
- **配置**：payment 的 `payment.channel.mock-scenario`（ADR-0049）决定 Mock 渠道默认结果（`SUCCESS`/`FAILURE`/`BUSINESS_UNKNOWN`/`TIMEOUT` 等），构造期注入、坏值 FAIL FAST，运行时切换需重启 payment（见 `demo/restart-payment.sh`）。
