# ADR 集合：内部服务令牌闭环（ADR-0034 ~ ADR-0037）

**Feature**：`009-risk-security` 收尾（Roadmap Phase 9 遗留项 T013）
**日期**：2026-08-30
**状态**：全部 **Proposed**（按用户约定「先按最简单实现开发、生成 ADR 供决策」已落地最简代码，负责人确认后无需改实现）
**关联**：`0009-risk-security-decisions.md`（ADR-0024 入站鉴权 / ADR-0026 密钥管理）

> 背景一句话：ADR-0024 给 `payment-service` 的 `/internal/**` 加了 `X-Service-Token` 入站守卫，但**调用方没有地方带令牌**，所以 `payment.security.internal-auth-enabled` 只能默认 `false`——一开就全线 403。本集合补上「出站」这一半，把闭环合上。

## 落地清单（代码已按最简实现，确认后无需改动实现）

| ADR | 落地位置 |
| --- | --- |
| 0034 出站令牌传播 | `common-core/client/InternalTokenRequestInterceptor.java`；`common-core/config/FeignInternalTokenAutoConfiguration.java`；`AutoConfiguration.imports` 追加注册；`payment-service/web/InternalServiceAuthInterceptor.java` 入站令牌回退 |
| 0035 入站推广范围 | 本期**不推广**（仅 payment 有入站鉴权）；无代码改动，风险记于本文档 |
| 0036 令牌轮换 | 本期**不支持**双令牌并存；无代码改动，轮换步骤记于 `docs/operations/runbook.md` |
| 0037 鉴权失败可观测 | `InternalServiceAuthInterceptor#reject`：`payment.internal_auth_rejected` 指标（带 `reason`）+ WARN 日志 |

配置：5 个服务的 `application.yml` 新增 `platform.security.internal-token`（env `PLATFORM_INTERNAL_TOKEN`）与 `platform.security.outbound-token-enabled`（默认 `false`）。

验证：`mvn -o clean verify -fae` 全量 BUILD SUCCESS（14 个 Maven 模块 + root，共 15 个 reactor 条目）；新增测试 `InternalTokenRequestInterceptorTest`（6）、`InternalTokenOutboundTest`（3）、`InternalServiceAuthInterceptorTest`（6），`InternalServiceAuthTest` 新增 `PlatformTokenFallback` 嵌套用例（2）。

---

## ADR-0034 出站内部服务令牌的传播

**背景**：ADR-0024 只做了**入站**守卫。要让它真正生效，所有调用 `payment-service` `/internal/**` 的兄弟服务（refund 调 `query-amount`/`refund-attempt`，reconciliation 调 `confirmed-facts`）必须在出站 Feign 请求上带 `X-Service-Token`。缺了这一环，`internal-auth-enabled=true` 就是一次全站故障。

**决策（最简）**：在 `common-core` 提供一个 Feign `RequestInterceptor`，由自动配置装配到所有引入 OpenFeign 的服务。

1. **只给内部端点发令牌**：仅当请求目标含 `/internal/` 段时才附加 `X-Service-Token`。对外 API（`/payments`、`/skus`）与渠道回调一律不发——这是本 ADR 的核心安全边界，避免把内网共享密钥泄漏到平台之外的任何 URL。
2. **全平台共享一把令牌**：`platform.security.internal-token`（env `PLATFORM_INTERNAL_TOKEN`）。不按服务对拆分，理由是当前调用拓扑简单，拆分只会增加轮换与排障成本。
3. **默认关闭**：`platform.security.outbound-token-enabled` 默认 `false`，且令牌为空时也不发。保证既有本地联调与集成测试零改动。
4. **不覆盖已有头**：调用方显式设置过 `X-Service-Token` 时保留原值。
5. **入站与出站同源**：`InternalServiceAuthInterceptor` 的令牌取「首个非空」——先 `payment.security.service-token`（env `PAYMENT_INTERNAL_TOKEN`），再 `platform.security.internal-token`。这样只注入 `PLATFORM_INTERNAL_TOKEN` 一个环境变量，两端就自动一致。

**实现要点（踩坑记录，供后续评审）**

1. **`${a:${b:}}` 嵌套默认值在空串场景下不生效**。最初写成
   `@Value("${payment.security.service-token:${platform.security.internal-token:}}")`，集成测试直接 503。原因：YAML 里
   `service-token: ${PAYMENT_INTERNAL_TOKEN:}` 在环境变量缺失时解析为**空字符串**而不是「未定义」，而 Spring 对空字符串视作已配置值，不会回退到嵌套默认值。故改为在 Java 侧显式 `firstNonBlank(...)` 判空取首个非空。
2. **目标路径判定要看 `url()` 和 `path()` 两个方法**。Feign 在不同阶段把路径放在不同字段上：`@FeignClient(name=...)` 的服务名形态在拦截器阶段尚未解析出绝对 URL，路径留在 `uriTemplate`；显式 `url=...` 的形态则可能已拼好。`path()` 本身已包含 `target + uriTemplate`，但为稳妥两者取或。
3. **自动配置必须类级 `@ConditionalOnClass`**。与既有 `FeignTraceAutoConfiguration` 同构：未在 classpath 引入 OpenFeign 的服务（merchant / catalog / entitlement）需整类跳过，否则 bean 类型推导会因缺失 `feign.RequestInterceptor` 而失败。

**影响**：五个发起内部调用的服务（payment / refund / settlement / reconciliation / fulfillment）已加入 `platform.security` 配置块。**新增任何会调用 `/internal/**` 的服务，必须同时加这个配置块**，否则该服务的调用会在对端开启鉴权后 403。

**待确认**：
1. 全平台共享一把令牌，还是按「调用方服务」或「调用方→被调用方」拆分密钥（当前：一把）。
2. 目标判定用路径前缀 `/internal/`，还是改成按目标服务名白名单（更严格，但新增服务要改配置）。
3. 是否升级为 mTLS / OAuth2 client-credentials（当前：共享密钥 Header）。
4. `internal-auth-enabled` 与 `outbound-token-enabled` 是否改为默认 `true`（当前都默认 `false`，属"默认不安全"）。

---

## ADR-0035 入站鉴权的推广范围

**背景**：当前有 **8 个**服务暴露 `/internal/**` 端点（ledger、order、refund、settlement、reconciliation、fulfillment、entitlement、payment），但**只有 payment-service 有入站鉴权**。其余 7 个的越权面与 payment 同类（例如 ledger 的 `/internal/ledger/postings` 可被直接伪造记账请求）。

**决策（最简）**：本期**不推广**入站鉴权，只补出站拦截器。

- 理由一：T013 的原始目标是让 `payment.security.internal-auth-enabled=true` 可安全开启，推广入站超出该目标，属于一个新的 Feature。
- 理由二：一旦推广，全部本地联调与集成测试都要带令牌，改动面横跨 7 个服务，风险与收益不成比例。
- 理由三：payment 的 `/internal/**` 越权后果最重（`refund-attempt` 直接动钱、`query-amount` 暴露可退金额），优先闭合它。

**影响**：其余 7 个服务的 `/internal/**` 仍**无任何鉴权**，防护完全依赖网络层隔离（同 VPC、不对公网暴露）。这是一条需要负责人明确接受的残余风险。

**演进路径**：把 `InternalServiceAuthInterceptor` 抽到 `common-core`（改为 `OncePerRequestFilter` 以便 MockMvc 也能覆盖，参见 ADR-0025 的 `FilterRegistrationBean` 踩坑），再由所有暴露 `/internal/**` 的服务启用。

**待确认**：
1. 是否接受「仅网络层隔离」作为其余 7 个服务的当前防护水平。
2. 是否将入站鉴权抽到 common-core 并全量启用（建议作为独立 Feature 立项）。
3. 抽公共组件时是复用 `HandlerInterceptor`（现状，仅 MVC 生效）还是改 `Filter`（覆盖更靠前、MockMvc 一致）。

---

## ADR-0036 令牌轮换策略

**背景**：共享密钥长期使用会放大泄漏影响面，ADR-0026 只定了「env 注入」，没定轮换。

**决策（最简）**：**不支持双令牌并存 / 平滑轮换**。

- 入站侧只接受**一个**令牌值，出站侧只发**一个**值。
- 轮换必须全平台统一切换：先全量下发新值 → 滚动重启全部服务 → 生效。
- 切换窗口内，新旧值不一致的服务之间调用会 403。缓解手段是**轮换期间先关 `internal-auth-enabled`，轮换完成后再开**，代价是存在一个短暂无鉴权窗口。

**待确认**：
1. 是否补 `primary` / `secondary` 双令牌实现零停机轮换（入站接受两者、出站只发 primary）——这是标准做法，代价是入站比对变两次。
2. 轮换周期（当前：无强制周期，人工触发）。
3. 是否接入配置中心做热更新（当前：改 env + 重启生效）。

---

## ADR-0037 鉴权失败的可观测性

**背景**：ADR-0028 的风控已确立「只观测、不阻断」的口径，但鉴权侧原本**完全无埋点**——拒绝时只回一个 403/503，既不打指标也不记日志。这带来一个现实问题：开启 `internal-auth-enabled` 后一旦出问题，无法区分是「配置漂移导致全线 503」还是「真实越权尝试 403」，运维只能靠猜。

**决策（最简）**：拒绝时记录指标 + WARN 日志，**不引入告警规则**（阈值未定）。

- 指标 `payment.internal_auth_rejected`，带 `reason` 维度，取值：
  - `unconfigured`（503）：开关开了但令牌没配 —— 纯配置故障；
  - `missing_token`（403）：调用方完全没带头 —— 大概率是某个服务的出站拦截器没开；
  - `token_mismatch`（403）：带了但不对 —— 大概率是令牌不同源/已轮换。
- WARN 日志含 `reason`、`status`、`uri`，便于按端点定位是哪个调用方没跟上。
- **不告警**：当前开关默认关闭、无真实流量，告警阈值缺乏依据。

**影响**：无破坏性改动，仅新增观测。

**待确认**：
1. 是否对 `reason=unconfigured` / `missing_token` 配置告警（建议至少 `unconfigured` 要告警，它意味着整条内部调用链已断）。
2. 是否升级为结构化审计事件（当前只是 WARN 日志，不进审计流水）。
3. 是否需要按调用方维度（服务名）细分指标，以便精确定位漏配的服务。
