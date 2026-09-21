# Architecture Decision Records（ADR）

> 记录项目的重要、不可逆架构决策：为什么这么选、备选方案、后果、当前是否仍有效。ADR 永不删除，只通过状态演进。
>
> **落点追溯**：各 ADR 在总体技术方案与系统设计文档中的体现位置，见 [traceability.md](traceability.md)。

## 索引

| 编号 | 标题 | 状态 | 关联 |
|---|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md) | 采用 Spring Cloud 微服务架构 | Accepted | 取代 Constitution §3.1；→ 0002 |
| [0002](0002-technology-stack.md) | 技术栈选型 | Accepted | ← 0001 |
| [0003](0003-payment-reliability-decisions.md) | 支付可靠性决策集合（ADR-0003~0007） | 混合 | Feature 003；含：UNKNOWN 收敛触发(Accepted) / 超时进 UNKNOWN(Accepted) / 重试模型(Accepted) / 人工收敛(**Not Implemented，延后 Phase 9**) / 终态冲突(Accepted) |
| [0004](0008-ledger-design-decisions.md) | Ledger 设计决策集合（ADR-0008~0011） | **Accepted**（2026-08-29 确认；0010 已修订） | Feature 004；含：复式记账数据模型(Accepted) / 记账触发与一致性(Accepted) / **金额只用 long 分、不启用 Money VO(Accepted·修订)** / MVP 记账范围(Accepted) |
| [0005](0012-payment-reliability-impl-decisions.md) | 支付可靠性**实现期**决策集合（ADR-0012~0015） | **Accepted**（2026-08-29 确认；0012/0013 已修订） | Feature 003；含：**双响应码错误分类 + 通信失败一律重试(修订)** / **重试不落库、请求内联重试(修订)** / 同 attempt 重放 / UNKNOWN 真实时长度量 / **超时口径 RPC 1s·HTTP 1.5s(新增)** |
| [0006](0016-refund-decisions.md) | 退款决策集合（ADR-0016~0018、ADR-0047） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0016 部分退款 ❌ **Rejected（不做，代码已回退）**；0017 / 0018 ✅ **Accepted**；0047 退款金额校验口径 🟡 **Proposed**） | Feature 005；含：**部分退款支持模型(裁决不做·已回退，累计一律按申请额)** / refund→fulfillment 编排(Accepted) / refund→ledger 记账接入(Accepted) / **退款金额校验口径(只做累计不超付，不做全额等值校验——与 001 spec「支持部分退款和多次退款」基线对齐)** |
| [0007](0019-reconciliation-decisions.md) | 对账决策集合（ADR-0019~0021） | ✅ **Accepted**（2026-08-30 裁决 accept） | Feature 006；含：批次差异处理生命周期 / 渠道账单按周期 fixture + 显式回退 / 事实读取 RPC 弹性（不引 Resilience4j） |
| [0008](0022-settlement-decisions.md) | 结算决策集合（ADR-0022~0023） | **Accepted**（代码已落地） | Feature 007；含：调整项模型（方向/持久化/门禁/净额公式） / 闸门纵深防御 + settlement→ledger 记账归属与时机 / 幂等键错配行为变更 / N1 商户维度缺口归属 |
| [0009](0024-risk-security-decisions.md) | 风险 / 安全决策集合（ADR-0024~0028） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0024 鉴权 ✅ **方案 Accepted／实现=预留空函数**；0025 验签 ✅ **方案 Accepted／实现=预留空函数**；0026 密钥 ✅ **Accepted（明文）**；0027 脱敏 ⛔ **Not Implemented（不管，类已删）**；0028 风控 ⛔ **Not Implemented（不管，类已删）**） | Feature 009；含：内部服务鉴权(**接入点保留**·`verifyServiceToken` 空实现恒放行，`/internal/**` 恒可达) / 渠道回调 HMAC 验签(**骨架保留**·`verifySignature` 空实现恒通过，`SignatureVerifier` 工具类留作接入时复用) / 密钥明文 env 注入(当前无密钥需注入) / **脱敏(不做，类已删)** / **风控(不做，类已删，不留挂点)** |
| [0010](0029-distributed-evolution-decisions.md) | 分布式演进决策集合（ADR-0029~0033） | **混合**（2026-08-30 裁决：0029 / 0030 / 0032 / 0033 ✅ **Accepted（保持现状）**；0031 异步消息 ⛔ **Not Implemented（不使用 MQ）**） | Feature 010；含：不拆分转而建门禁 / 拆库触发判据(保持现状) / **引入异步消息判据(不做)** / T0~T3 分层 / 提案模板与运行手册作为门禁 |
| [0011](0034-internal-token-decisions.md) | 内部服务令牌闭环（ADR-0034~0037） | ⛔ **Not Implemented（不做，2026-08-30 裁决「出入站鉴权令牌都先不做」，2026-08-31 代码已清理）** | Feature 009 收尾 T013；含：**出站令牌传播范围(不做·代码已删)** / **入站鉴权推广范围(不推广)** / **令牌轮换(不做)** / **鉴权失败可观测(不做)**；`platform.security.*` 配置已移除，将来启用时按本文档与 0009 的 0024 成对实施 |
| [0012](0048-demo-showcase-decisions.md) | 端到端演示形态（ADR-0048~0051） | ✅ **Accepted**（2026-08-31 裁决；**ADR-0048 已修订**——推翻「不做 `mock-channel-web`」，改为**新增收银台组件**，含 payUrl 跳转链路与演示页面） | Feature 011；含：**演示形态(新增 `mock-channel-web`：收银台页+回调签名转发+演示控制台+同源代理)** / **Mock 渠道场景配置化(`payment.channel.mock-scenario`，已落地)** / **对账演示账单(生成 CSV 写入 `target/classes`，不改生产代码)** / **演示脚本纪律(只编排不伪造、断言失败即非零退出)** |
| [0013](0052-channel-callback-signature-decisions.md) | 渠道回调验签接入（ADR-0052） | ⛔ **Not Implemented**（2026-08-31 用户确认回退到 ADR-0025 空实现） | Feature 011 前置候选；**不落地** `SignatureVerifier` 真实校验，代码维持 `ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`（占位放行）；`application.yml` 已移除 `payment.security.*` 配置；`ChannelCallbackSecurityTest` 维持占位期放行断言（`invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed` 等） |
| [0014](0038-next-stage-decisions.md) | 下一阶段决策集合（ADR-0038~0046） | **Accepted**（0041~0046 于 2026-08-31 收口；**0038 Superseded by 0048**；**0039/0040 于 2026-09-02 补写**；0044 偏离 roadmap §7 论证闸门） | 012-entry-idempotency（0039 幂等键签发与存储 / 0040 并发幂等接管策略）+ 013-inventory-reservation（0041 库存域归属 / 0042 扣减时机 / 0043 超时释放机制）+ 014-seckill-and-cache（0044 Redis 引入论证·偏离 / 0045 用途边界 / 0046 限流策略）；代码先行，见 ADR-0053。**0039/0040 的补写消除了代码中已存在但文档缺失的悬空引用**（`OrderController` / `OrderEntryIdempotencyService` / `IdempotencyDecision` / `docker-compose.yml`） |
| [0015](0053-wip-ahead-of-roadmap.md) | 库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置（ADR-0053） | **Accepted**（2026-08-31，提交负责人复盘；若否决则回退 013/014 代码） | 偏离 / 处置日志：working tree 含 013-inventory-reservation / 014-seckill-and-cache 实质性实现，**超前顺序、缺 spec/ADR-0041~0046、014 的 Redis 引入未经 roadmap §7 论证闸门**；决策=保留代码（编译+测试通过，且与 011 在 order-service 纠缠不可干净拆分），spec/ADR 补写列为 TODO，待复盘收口 |
| [0025](0054-order-payment-orchestration.md) | 支付编排职责归位（ADR-0054） | ✅ **Accepted**（2026-09-06 落地） | spec 016；Supersedes ADR-0064 §决策#4（自动退款归属） |
| [0026](0065-accounting-audit-suspense-adjustment.md) | 会计四核对与挂账·调账闭环（ADR-0065） | ✅ **Accepted → 已实施**（2026-09-07 代码合并 master，450 测试全绿） | spec 017；新增 SUSPENSE(5) 科目（Constitution §8 变更已批准）、结算分级门禁、账实双轨、双人复核降级软提示 |
| [0027](0066-schema-normalization-and-item-granular-fulfillment.md) | 表结构列序规范化与按订单明细粒度履约（ADR-0066） | ✅ **Accepted → Implemented**（2026-09-07 拍板并落地） | spec 018；列序规范（自增id→业务主键→唯一索引列，3 表豁免）、payment_attempts 加金额列、order_item_no（OI+雪花）、fulfillment 按 item 粒度、demo 中文注释 + 门户主界面 |
| [0028](0067-order-driven-refund-two-layer-refund-order.md) | order 驱动的两层退款单模型与退款异步回调闭环（ADR-0067） | ✅ **Accepted → Implemented**（2026-09-07 拍板并落地，全量回归绿） | spec 019；双层退款单 TXRF（transaction 层）/PMRF（payment 层）互记、transactions 加 payment_no/refunded_minor、两层金额校验、渠道退款异步回调 + 三路收敛、秒杀库存回补、直调入口下线；明确不做：UNKNOWN 自动收敛器 / resolve Admin Token / 部分退款次数上限 |
| [0029](0068-unified-access-logging.md) | 统一访问日志：结束时单条 ACCESS + 固定格式含服务名 + 异步 MDC 传播修复（ADR-0068） | ✅ **Accepted → Implemented**（2026-09-07 拍板并落地） | spec 021；Filter+ContentCaching 实现、结束时一条 ACCESS（method/uri/status/costMs/req/resp，4KB 截断、/actuator 排除、可开关）、脱敏只留桩（SensitiveBodyMasker 透传）、logback springProperty 注入服务名、MdcTaskDecorator + 4 Scheduler 补 traceId、tail-logs.sh/trace-grep.sh；明确不做：真脱敏实现 / Loki 集中采集（后续期）/ AOP 方法级日志 |
| [0030](0069-end-to-end-automated-testing.md) | 全链路自动化测试体系：独立 E2E 模块 + 分层门禁 + 不变量断言（ADR-0069） | ✅ **Implemented**（2026-09-07~08 已落地并验证） | spec 022；新建 `deployment/e2e-tests`（JUnit5+Awaitility+JDBC，黑盒）、默认复用本地栈（ci profile 才 Testcontainers）、不引 SCC/Pact 改做 API 快照、PR 快跑 + nightly E2E；行级断言复用 `/demo/trace`、聚合不变量直连 MySQL、对账差异复用 audit F1~F9(LIVE)；硬约束：禁断言回调验签 / 对账必走 LIVE / 对账拆两套断言；明确不做：SCC-Pact / 改造或退役演示脚本 / k6-Gatling / PR 跑 E2E |
| [0031](0070-containerized-local-stack.md) | 本地全栈容器化：Compose 而非 K8s + 双轨并存（宿主/容器）+ 宿主打 jar 只 COPY + 环境变量覆盖 Nacos 地址（ADR-0070，**Supersedes ADR-0057**） | 🟢 **Accepted / Implemented**（2026-09-15 实施完毕，P1~P7 实测通过） | spec 026；通用 Dockerfile 参数化复用 10 服务、compose profiles（infra/full）+ `pay-arch` 网络、应用 `depends_on nacos: service_healthy`、模式守卫双向互斥、Prometheus/Promtail 双模适配、demo UNKNOWN 场景等价切换；明确不做：K8s-Helm / registry-CI 推送 / 改业务源码 / Docker 内 Maven 构建 / 复用 8085 |
| [0032](0071-user-payment-limit.md) | 用户支付限额——日月年周期额度与两阶段预占（ADR-0071） | 🟢 **Accepted → Implemented**（2026-09-16 提出并实现完毕；D1~D13 全部确认） | spec 027；三表模型（`user_payment_limits` / `user_limit_usage` / `limit_operations`，UK = `(biz_no, op_type, period)`）+ 两阶段预占（RESERVE → CONFIRM / RELEASE / EXPIRED）+ 三道闸门（状态机终态吸收 / 幂等流水 UK / 以 payment 为事实源的补偿扫描）；与 ADR-0028「最小风控」切割（风控 ⛔ 不做，限额属业务合规能力）；在途占用 TTL=900s（D11，Redis 惰性回收，零调度器）+ 软超限口径（D12，新支出硬约束 / 已发生事实软记账）+ 允许 payment 使用 Redis（D13，**ADR-0044「payment 不用 Redis」的显式例外**，仅限 TTL 标记、不做计数）；**对 ADR-0048「演示组件只读代理」的显式例外**（D9，范围仅 `/internal/limits/**`）；实现期修正 UK 含 `period`（原键使 MONTH/YEAR 档静默失效） |
| [0033](0072-two-layer-channel-architecture.md) | payment-service 两层结构：payment 支付层 / channelAttempt 渠道层（ADR-0072） | ✅ **Accepted → Implemented**（2026-09-16 裁决接受并随 spec 028 落地） | spec 028；确立两层**各拥自己的聚合与表写入口**（`payments` 只由 payment 层写、`payment_attempts` 只由渠道层写，写入口经 `ChannelAttemptRecorder` 端口）；`PaymentChannel` 补 `channelCode()` 渠道身份；渠道实现族＝抽象基类 `AbstractMockChannelAdapter` + Alipay/Wechat/Douyin/Mock 四实现；**退款 attempt 渠道取自原始支付记录**（消除 `PaymentRefundService` 硬编码 `"mock"`）；**分层 ≠ 拆事务**（两层共享本地事务）；ArchUnit INV-4/INV-5 门禁；明确不做：拆微服务 / 拆数据源 / 拆事务边界 |
| [0034](0073-channel-routing.md) | 支付渠道路由：注册表 + 规则化确定性选路（ADR-0073） | ✅ **Accepted → Implemented**（2026-09-16 裁决接受并随 spec 028 落地） | spec 028；**依赖 ADR-0072 的渠道身份**（渠道没有身份，注册表没有 key 可挂）；`ChannelRegistry` / `ChannelRouter` 落 `application/channel` 层（守 `Payment ≠ Channel`）、`channelCode` 变可选且**显式优先**（保 INV-2）、**确定性选路**（禁随机/计数器，保幂等键稳定）、**前向选路与反向按记录解析严格分离**（退款/重试/查询禁止重新路由）；配置化 `enabled + priority` + 静态 `availability` 桩（不读 Resilience4j）；启动强校验不静默降级（`FR-032`）；只读端点 `GET /internal/channels` 与 `/internal/channels/route-preview`；**Supersedes spec 015 §8 第 1 条「不做注册表」**；明确不做：自动降级 failover（**与 INV-2 互斥**）/ 智能路由（无数据源）/ 多商户号 / 渠道真实探活 / 改枚举 |
| [0074](0074-redis-transactional-message.md) | Redis 事务消息通道：Streams + 半消息协议（prepare → 本地事务 → commit/rollback → 回查）承载跨服务异步解耦（ADR-0074） | 🟢 **Accepted → Implemented**（2026-09-20 随 spec 029 落地，合入 master `5e2c00d`） | spec 029；**Supersedes ADR-0031「不使用 MQ」**（以「Redis 不是 MQ 组件」满足其解耦证据要求，三条约束原样继承）；拓扑＝混合（`payment.succeeded` 点对点 / `order.paid`·`refund.succeeded`·`order.cancelled` 广播 / `fulfillment.completed` 点对点）；广播点画在 `order.paid`（ADR-0066 事实源）；消费语义 at-least-once + 消费端幂等；**记账三条链路维持同步**；trace 消费组落 `order_event_log` 供按订单号还原；明确不做：MQ 中间件 / Redisson / 延迟消息 / 严格一次 / 消息控制台 |

| [0075](0075-unified-channel-contract.md) | 聚合支付统一渠道契约：能同时容纳支付宝 / 微信 / 抖音 / Stripe（ADR-0075） | 🟢 **Accepted**（2026-09-19 提出并拍板 D1~D11；**同日负责人确认由 Proposed 升级为 Accepted**；**尚未实现**） | **[spec 030](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md)**（原 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 四件套已于 2026-09-19 删除，设计被该 spec 全量吸收）；**扩展** ADR-0072 的渠道层契约（其结构条款——表归属 / 写入口 / 分层≠拆事务——**全部不变**）；`scene` / `channelExtra` **不参与选路**（ADR-0073 语义不变）；金额扁平化沿用 ADR-0010；双响应码派生沿用 ADR-0012；`outRequestNo` 只为将来保留渠道语义，**不改变 ADR-0016「退款恒按全退」**；契约内标识沿用 ADR-0063；落点追溯见 [traceability](traceability.md) |
| [0076](0076-traffic-dyeing-and-alipay-sandbox.md) | 全链路染色分流（mock / 沙箱）+ 渠道模态落库 + 支付宝沙箱接入（ADR-0076） | 🟢 **Accepted**（2026-09-19 提出并拍板 D1~D11；**同日负责人确认由 Proposed 升级为 Accepted**；**尚未实现**；**门 2 已裁决**：模态载体 = 通用 JSON 列 `extra_json` 的 `channelMode` 键（~~专用列 `channel_mode`~~，**语义不变**，见该 ADR「修订记录」）、`alipay-sdk-java` **确认引入**） | **[spec 030](../specs/stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md)**；**部分取代 ADR-0072**（`ALIPAY` 升级为双模态特例，**MOCK 分支必须 `super` 委托**；「❌ 不接入真实渠道 SDK」被取代；其余条款不变）、**部分取代 ADR-0073**（「不做：真实渠道 SDK 接入」被取代；**路由规则本身不变**）；给出 ADR-0052「是否接入真实验签」的裁决（支付宝走 **RSA2 独立端点**，HMAC 占位**保留给本地 mock 路径**）；**不改 ADR-0025 口径**；密钥沿用 ADR-0026（env 注入 / 禁硬编码 / 禁入库）；染色非法值与「开关未开」一律 **fail fast**（ADR-0049）；染色**入站读与出站写必须同批**（ADR-0024 / ADR-0034 全线 403 先例教训）；落点追溯见 [traceability](traceability.md) |
| [0077](0077-ledger-accounting-foundation-decisions.md) | Ledger / Accounting 地基决策集合（ADR-0077~0079） | 🟢 **Accepted**（2026-09-21 负责人裁决 D-1~D-7 按 spec 推荐方案批准；**同日实现落地**，见 spec 031 三件套） | **[spec 031](../specs/stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/spec.md)**；**Partially Supersedes ADR-0008**（调用方组装分录 + 固定科目枚举两条）、**修订 ADR-0009/0018/0023 契约描述**（触发时机/同步/失败不回滚语义不变）、**修订 ADR-0011 边界**（引入渠道清算**科目**，清算**链路**仍延后至 032+）；吸收 M1 / H3（C-07）/ G3（C-09）；复式结构、平衡门禁、append-only、幂等范式**保留** |
| [0080](0080-reconciliation-statement-and-fund-facts.md) | 对账真实化：渠道账单导入对象 + 事实维度升级 + 渠道资金事实入账（ADR-0080） | 🟡 **Proposed**（2026-09-21 随 spec 032 提出，待负责人裁决 H-032-1~H-032-6；**未 Accepted 前不得实现**） | **[spec 032](../specs/stage-05-channel-and-finance-deepening/032-reconciliation-real-statement/spec.md)**；**扩展 ADR-0020**（账单来源由周期 fixture 升级为导入对象，**删除 `sample.csv` 静默回退**，据 ADR-0049）、**复用 ADR-0065** 的 5 态差异生命周期（不新建平行状态机）、**接续 ADR-0011/0079** 挂起的渠道清算链路；032 是 `CHANNEL_SETTLEMENT` / `CHANNEL_FEE` 的**唯一产生方**（`CS-`/`CF-{channel}-{period}`）；关闭 C-13/C-20/C-22 |
| [0081](0081-test-carrier-and-schema-replayability.md) | 测试载体升级：真库 Testcontainers（仅测试作用域）+ schema 双路径可重放门禁 + RPC 边允许清单（ADR-0081） | 🟢 **Accepted**（2026-09-21 负责人裁决，按 spec 推荐方案批准 H-033-1~5；随 spec 033 实现落地） | **[spec 033](../specs/stage-05-channel-and-finance-deepening/033-test-infrastructure/spec.md)**；**变更 Constitution §Engineering.3「不引入 Testcontainers」的现行约束**（design-review §13 **H16**），以 L5 规则「`src/main` 不得依赖 `org.testcontainers`」封口；是 031 §10 / 032 §8 登记的「真库并发验证」缺口唯一关闭路径；不改动 [ADR-0069](0069-end-to-end-automated-testing.md) 的测试钻石分层，只补其从未验证的**增量迁移重放**；既有 H2 用例与 `InMemory*Repository` 桩**零删除** |
| [0082](0082-failure-recovery-ownership-and-compensation.md) | 失败恢复归属与补偿闭环（ADR-0082） | 🟡 **Proposed**（2026-09-21 随 spec 034 提出，待负责人裁决；**未 Accepted 前不得实现**） | **[spec 034](../specs/stage-05-channel-and-finance-deepening/034-reliability-hardening/spec.md)**；**落地 ADR-0079 的待记账台账**（`pending_postings` 由调用方写入，spec 031 §12 设计首次有实现归属）、**沿用 ADR-0021**「不引熔断中间件」并据此**移除**未使用的 Resilience4j 依赖（了结 backlog #5）、**沿用 ADR-0077 D4** 的 Ledger 派生幂等键作为安全重放前提；不新增 Payment 状态、不引入 2PC/XA、不做通用补偿框架 |
| [0083](0083-observability-baseline-slo-and-cardinality.md) | 观测基线：SLO/错误预算落地、高基数政策、密钥与报文不入日志、保留自研 trace（ADR-0083） | 🟡 **Proposed**（2026-09-21 随 spec 035 提出，待负责人裁决 H-035-1~H-035-4；**未 Accepted 前告警只出报表不 page**） | **[spec 035](../specs/stage-05-channel-and-finance-deepening/035-observability-slo/spec.md)**；把 Constitution §Obs.5 的 SLO 文字首次变成 Recording Rule（design-review §13 **H14**）；**不推翻 ADR-0027**（脱敏仍为透传桩，改以 `exclude-paths` 增补 `/internal/channels/**` + 显式禁令 + 测试满足 ADR-0068/H7）；**否决引入 APM / Micrometer Tracing / OTel**，自研 `X-Trace-Id` 保留；指标目录为跨 Feature 观测单一登记处，既有 93 指标零改名 |

## ADR 编号速查（0001–0083）

| 编号 | 决策标题 | 承载文件 |
|---|---|---|
| [0054](0054-core-payment-correctness.md#adr-0054) | 核心支付正确性约束（确认性纪录） | 0016 |
| [0055](0055-entry-and-infra-decisions.md#adr-0055) | 支付意图幂等键由 order-service 生成 | 0017 |
| [0056](0055-entry-and-infra-decisions.md#adr-0056) | Nacos 启用（落实 ADR-0002，撤销「暂不启用」偏离，见 ADR-0059） | 0017 |
| [0057](0055-entry-and-infra-decisions.md#adr-0057) | 服务未容器化（⛔ **Superseded by [ADR-0070](0070-containerized-local-stack.md#adr-0070)**，2026-09-15：改为双轨并存，新增容器模式） | 0017 |
| [0058](0058-performance-baseline.md#adr-0058) | 性能与容量目标基线（已建立，3 项🟡已落地为 JUnit 并发/量化测试，并含分布式端到端验证 2026-09-04） | 0018 |
| [0059](0059-enable-nacos.md#adr-0059) | 启用 Nacos 服务发现与注册中心（实施记录） | 0019 |
| [0060](0060-redis-lettuce-pool.md#adr-0060) | Redis 客户端启用 Lettuce 连接池（压测驱动，2026-09-04） | 0020 |
| [0061](0061-observability-panel-fix.md#adr-0061) | 可观测性补全与演示脚本漂移修复（HTTP 直方图 / 计数器命名避保留后缀 / 对账 closed_at / 尝试状态机权威收敛 / 演示脚本对齐，2026-09-04） | 0021 |
| [0062](0062-business-no-snowflake.md#adr-0062) | 业务单号统一采用两字母前缀 + 雪花算法（TX/OR/PM/RF/SB/RB/LP，主键保持自增，2026-09-04） | 0022 |
| [0063](0063-cross-service-reference-by-business-no.md#adr-0063) | 跨系统关联一律使用业务单号，数值主键不跨服务（关联列/接口/回调全部切单号，2026-09-04） | 0023 |
| [0064](0064-multi-payment-per-transaction.md#adr-0064) | 一交易多支付单（Feature 015）：下单不建单/显式选渠道、幂等键含 attemptSeq、409 ORDER_NOT_PAYABLE + 自动退款、退款域并入 payment-service（10→9）、三渠道 mock（2026-09-04） | 0024 |
| [0054](0054-order-payment-orchestration.md#adr-0054) | 支付编排职责归位：order 升为业务编排者 / payment 退回能力提供方；自动退款决策与发起归属 order transaction 层（transactionNo + paymentNo）；Supersedes ADR-0064 §决策#4（✅ Accepted，2026-09-06 落地） | 0025 |
| [0065](0065-accounting-audit-suspense-adjustment.md#adr-0065) | 会计四核对与挂账·调账闭环：账证/账账/账实(双轨)/账表 + SUSPENSE 挂账 + 五类调账 + recheck 关批 + 结算分级门禁（✅ Accepted → Implemented，2026-09-07 已落地） | 0026 |
| [0066](0066-schema-normalization-and-item-granular-fulfillment.md#adr-0066) | 表结构列序规范化（自增id→业务主键→唯一索引列，3 表豁免）+ payment_attempts 金额列 + order_item_no（OI+雪花）+ 按 order_item 粒度履约 + demo 中文注释/门户主界面（✅ Accepted → Implemented，2026-09-07 已落地） | 0027 |
| [0067](0067-order-driven-refund-two-layer-refund-order.md#adr-0067) | order 驱动的两层退款单模型（TXRF 交易层 / PMRF 支付层互记）+ 退款异步回调闭环（三路收敛）+ 秒杀库存回补 + 直调入口下线（✅ Accepted → Implemented，2026-09-07 拍板并落地） | 0028 |
| [0068](0068-unified-access-logging.md#adr-0068) | 统一访问日志：结束时单条 ACCESS（method/uri/status/costMs/req/resp，4KB 截断）+ 固定格式含服务名（springProperty 注入）+ 脱敏留桩（SensitiveBodyMasker）+ 异步 MDC 传播修复（MdcTaskDecorator + 4 Scheduler）+ 日志查看脚本（✅ Accepted → Implemented，2026-09-07 拍板并落地） | 0029 |
| [0069](0069-end-to-end-automated-testing.md#adr-0069) | 全链路自动化测试体系：新建 `deployment/e2e-tests` 独立模块（黑盒 HTTP+JDBC）+ 测试钻石分层（L1 单元/L2 单服务集成/L3 API 快照/L4 E2E/L5 对账与故障注入）+ 默认复用本地栈（ci profile 用 Testcontainers）+ 不引 SCC/Pact + PR 快跑 / nightly 全量 + Invariants 断言原语库 + 确定性故障注入 + 测试有效性反证（✅ Accepted → 待实施，2026-09-07 拍板） | 0030 |
| [0070](0070-containerized-local-stack.md#adr-0070) | 本地全栈容器化：Compose 编排 10 服务（非 K8s）+ **双轨并存**宿主/容器两种运行模式且端口互斥守卫 + 宿主打 fat jar（`deployment/output/jars`）镜像只 COPY（非 Docker 内多阶段 Maven 构建）+ compose `environment` 覆盖硬编码的 `127.0.0.1:8848`（源码零改动）+ `depends_on nacos: service_healthy`（🟢 Accepted / Implemented，2026-09-15 实施完毕；**Supersedes ADR-0057「服务未容器化」**） | 0031 |
| 0071 | 用户支付限额：日月年周期额度（daily / monthly / yearly 三档）+ 两阶段预占（RESERVE → CONFIRM / RELEASE / EXPIRED）+ 三表模型 + 三道闸门；在途占用 TTL（Redis 惰性回收）+ 软超限口径 + payment 使用 Redis 例外（D11~D13）；与 ADR-0028「最小风控」切割；对 ADR-0048 登记显式例外（🟢 Accepted → Implemented，2026-09-16 提出并实现完毕） | [0032](0071-user-payment-limit.md) |
| [0072](0072-two-layer-channel-architecture.md#adr-0072) | payment-service 两层结构：payment 支付层 / channelAttempt 渠道层；表归属与写入口分离（`payments` 归 payment 层、`payment_attempts` 归渠道层）；`PaymentChannel` 补渠道身份 `channelCode()`；渠道实现族＝抽象基类 + 三渠道；退款 attempt 渠道取自原始支付记录（消除硬编码 `"mock"`）；分层 ≠ 拆事务（✅ Accepted → Implemented，2026-09-16 落地） | 0033 |
| [0073](0073-channel-routing.md#adr-0073) | 支付渠道路由：`ChannelRegistry` + `ChannelRouter`（`application/channel` 层端口）+ 三渠道 Adapter；`channelCode` 可选且显式优先；确定性选路；**前向选路与反向按记录解析分离**（退款禁重新路由）；配置化 enabled+priority + 静态 availability 桩；启动强校验；Supersedes spec 015 §8「不做注册表」（✅ Accepted → Implemented，2026-09-16 落地） | 0034 |
| [0074](0074-redis-transactional-message.md#adr-0074) | Redis 事务消息通道：Redis Streams + 半消息（prepare/commit/rollback + 5s 回查真相表）承载 8 条跨服务通知；混合拓扑（事实类广播、动作类点对点）；`blockMs=2000` 短阻塞避 Lettuce 池独占；DLQ + XAUTOCLAIM 接管；Redis 补 AOF + 数据卷 + noeviction；payment/fulfillment/entitlement 新增 Redis 依赖（payment 为 ADR-0044/G7 显式例外）（🟢 Accepted → Implemented，2026-09-19 提出并拍板，2026-09-20 落地于 master `5e2c00d`） | 0074 |

> 决策 #2 落地：保留 33 个编号 ADR 文件不动，此处建立「ADR 编号 → 承载文件 → 锚点」跳转表，便于从任意编号直达正文。编号链接指向文件内 `<a id="adr-XXXX">` 锚点。

| 编号 | 决策标题 | 承载文件 |
|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md#adr-0001) | 采用 Spring Cloud 微服务架构 | [0001-adopt-spring-cloud-microservices.md](0001-adopt-spring-cloud-microservices.md) |
| [0002](0002-technology-stack.md#adr-0002) | 技术栈选型 | [0002-technology-stack.md](0002-technology-stack.md) |
| [0003](0003-payment-reliability-decisions.md#adr-0003) | UNKNOWN 收敛触发机制 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0004](0003-payment-reliability-decisions.md#adr-0004) | 超时进入 UNKNOWN 的策略 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0005](0003-payment-reliability-decisions.md#adr-0005) | 支付重试模型 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0006](0003-payment-reliability-decisions.md#adr-0006) | 人工收敛能力与权限/审计约束 —— **本阶段不做（Not Implemented）** | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0007](0003-payment-reliability-decisions.md#adr-0007) | 终态冲突策略（迟到成功不覆盖已失败支付） | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0008](0008-ledger-design-decisions.md#adr-0008) | Ledger 数据模型（复式记账 + 科目/分录结构） | [0008-ledger-design-decisions.md](0008-ledger-design-decisions.md) |
| [0009](0008-ledger-design-decisions.md#adr-0009) | 记账触发与一致性（同步 RPC 幂等记账 + 失败兜底） | [0008-ledger-design-decisions.md](0008-ledger-design-decisions.md) |
| [0010](0008-ledger-design-decisions.md#adr-0010) | 金额表示（Ledger 启用 Money 值对象 vs 仅 long 分） | [0008-ledger-design-decisions.md](0008-ledger-design-decisions.md) |
| [0011](0008-ledger-design-decisions.md#adr-0011) | MVP 记账范围（支付 / 退款 / 结算哪些首批） | [0008-ledger-design-decisions.md](0008-ledger-design-decisions.md) |
| [0012](0012-payment-reliability-impl-decisions.md#adr-0012) | 重试的错误分类来源（双响应码；通信失败一律重试） | [0012-payment-reliability-impl-decisions.md](0012-payment-reliability-impl-decisions.md) |
| [0013](0012-payment-reliability-impl-decisions.md#adr-0013) | 重试调度的载体（不落库，请求内联重试） | [0012-payment-reliability-impl-decisions.md](0012-payment-reliability-impl-decisions.md) |
| [0014](0012-payment-reliability-impl-decisions.md#adr-0014) | 重试的幂等与事务边界（同 attempt 重放） | [0012-payment-reliability-impl-decisions.md](0012-payment-reliability-impl-decisions.md) |
| [0015](0012-payment-reliability-impl-decisions.md#adr-0015) | UNKNOWN 真实收敛时长的度量方式 | [0012-payment-reliability-impl-decisions.md](0012-payment-reliability-impl-decisions.md) |
| [0016](0016-refund-decisions.md#adr-0016) | 部分退款支持模型（如何让 PARTIALLY_SUCCEEDED 可达、部分金额如何跟踪） | [0016-refund-decisions.md](0016-refund-decisions.md) |
| [0017](0016-refund-decisions.md#adr-0017) | refund → fulfillment 编排（补齐缺失 RPC vs 修改文档声明） | [0016-refund-decisions.md](0016-refund-decisions.md) |
| [0018](0016-refund-decisions.md#adr-0018) | refund → ledger 记账接入（与 spec 004-ledger 的归属与时机） | [0016-refund-decisions.md](0016-refund-decisions.md) |
| [0019](0019-reconciliation-decisions.md#adr-0019) | 批次差异处理生命周期（接线 `beginProcessing`/`close` 与「处理中/关闭」语义） | [0019-reconciliation-decisions.md](0019-reconciliation-decisions.md) |
| [0020](0019-reconciliation-decisions.md#adr-0020) | 渠道账单来源（按周期 fixture + 显式回退 vs 参数化加载器 vs 维持全局 fixture） | [0019-reconciliation-decisions.md](0019-reconciliation-decisions.md) |
| [0021](0019-reconciliation-decisions.md#adr-0021) | 事实读取 RPC 的弹性（超时 / 有限重试 / 错误归一化 vs 引入熔断中间件） | [0019-reconciliation-decisions.md](0019-reconciliation-decisions.md) |
| [0022](0022-settlement-decisions.md#adr-0022) | 调整项模型（方向语义 / 持久化形态 / 登记门禁 / 净额公式 / 死代码处置） | [0022-settlement-decisions.md](0022-settlement-decisions.md) |
| [0023](0022-settlement-decisions.md#adr-0023) | 已确认事实闸门的纵深防御与 settlement → ledger 记账归属 | [0022-settlement-decisions.md](0022-settlement-decisions.md) |
| [0024](0024-risk-security-decisions.md#adr-0024) | 内部服务间调用鉴权 | [0024-risk-security-decisions.md](0024-risk-security-decisions.md) |
| [0025](0024-risk-security-decisions.md#adr-0025) | 渠道回调签名校验 | [0024-risk-security-decisions.md](0024-risk-security-decisions.md) |
| [0026](0024-risk-security-decisions.md#adr-0026) | 密钥管理 | [0024-risk-security-decisions.md](0024-risk-security-decisions.md) |
| [0027](0024-risk-security-decisions.md#adr-0027) | 敏感数据脱敏 | [0024-risk-security-decisions.md](0024-risk-security-decisions.md) |
| [0028](0024-risk-security-decisions.md#adr-0028) | 最小风控规则 | [0024-risk-security-decisions.md](0024-risk-security-decisions.md) |
| [0029](0029-distributed-evolution-decisions.md#adr-0029) | 本期不做任何服务拆分，先立「可拆分性」门禁 | [0029-distributed-evolution-decisions.md](0029-distributed-evolution-decisions.md) |
| [0030](0029-distributed-evolution-decisions.md#adr-0030) | 独立数据库迁移：先定触发条件，不动手 | [0029-distributed-evolution-decisions.md](0029-distributed-evolution-decisions.md) |
| [0031](0029-distributed-evolution-decisions.md#adr-0031) | 跨服务异步消息：只在有明确证据时才评估 | [0029-distributed-evolution-decisions.md](0029-distributed-evolution-decisions.md) |
| [0032](0029-distributed-evolution-decisions.md#adr-0032) | 独立扩缩容与故障隔离：按关键等级分级，而非一刀切 | [0029-distributed-evolution-decisions.md](0029-distributed-evolution-decisions.md) |
| [0033](0029-distributed-evolution-decisions.md#adr-0033) | 拆分提案模板与运行手册：每次拆分的准入门禁 | [0029-distributed-evolution-decisions.md](0029-distributed-evolution-decisions.md) |
| [0034](0034-internal-token-decisions.md#adr-0034) | 出站内部服务令牌的传播 | [0034-internal-token-decisions.md](0034-internal-token-decisions.md) |
| [0035](0034-internal-token-decisions.md#adr-0035) | 入站鉴权的推广范围 | [0034-internal-token-decisions.md](0034-internal-token-decisions.md) |
| [0036](0034-internal-token-decisions.md#adr-0036) | 令牌轮换策略 | [0034-internal-token-decisions.md](0034-internal-token-decisions.md) |
| [0037](0034-internal-token-decisions.md#adr-0037) | 鉴权失败的可观测性 | [0034-internal-token-decisions.md](0034-internal-token-decisions.md) |
| [0038](0038-next-stage-decisions.md#adr-0038) | 演示形态：Mock 收银台 vs 纯脚本（011） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0039](0038-next-stage-decisions.md#adr-0039) | 下单幂等键的签发与存储位置（012） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0040](0038-next-stage-decisions.md#adr-0040) | 并发幂等「超时接管」策略（012） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0041](0038-next-stage-decisions.md#adr-0041) | 库存域归属（013） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0042](0038-next-stage-decisions.md#adr-0042) | 库存扣减时机（013） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0043](0038-next-stage-decisions.md#adr-0043) | 订单超时释放库存的机制（013） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0044](0038-next-stage-decisions.md#adr-0044) | Redis 引入论证（014） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0045](0038-next-stage-decisions.md#adr-0045) | Redis 用途边界（014） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0046](0038-next-stage-decisions.md#adr-0046) | 秒杀限流策略与丢弃语义（014） | [0038-next-stage-decisions.md](0038-next-stage-decisions.md) |
| [0047](0016-refund-decisions.md#adr-0047) | 退款金额校验口径（ADR-0016 回退后，是否强制「申请额 = 可退全额」） | [0016-refund-decisions.md](0016-refund-decisions.md) |
| [0048](0048-demo-showcase-decisions.md#adr-0048) | 演示形态：~~不新增 `mock-channel-web`~~ → **新增 `mock-channel-web` 组件（2026-08-31 负责人裁决修订）** | [0048-demo-showcase-decisions.md](0048-demo-showcase-decisions.md) |
| [0049](0048-demo-showcase-decisions.md#adr-0049) | Mock 渠道场景配置化 | [0048-demo-showcase-decisions.md](0048-demo-showcase-decisions.md) |
| [0050](0048-demo-showcase-decisions.md#adr-0050) | 对账演示账单：生成 CSV 写入 `target/classes`，不改生产代码 | [0048-demo-showcase-decisions.md](0048-demo-showcase-decisions.md) |
| [0051](0048-demo-showcase-decisions.md#adr-0051) | 演示脚本纪律：只编排不伪造、断言失败即非零退出 | [0048-demo-showcase-decisions.md](0048-demo-showcase-decisions.md) |
| [0052](0052-channel-callback-signature-decisions.md#adr-0052) | 渠道回调验签接入真实现 —— ⛔ 未实施（回退至 ADR-0025 占位） | [0052-channel-callback-signature-decisions.md](0052-channel-callback-signature-decisions.md) |
| [0053](0053-wip-ahead-of-roadmap.md#adr-0053) | 库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置 | [0053-wip-ahead-of-roadmap.md](0053-wip-ahead-of-roadmap.md) |

| [0075](0075-unified-channel-contract.md#adr-0075) | 聚合支付统一渠道契约：四组结构化字段（`Goods` / `CallbackUrls` / `Payer` / `PaymentScene`）+ `channelExtra` 扩展袋 + 类型化 `PayCredential`（6 种 Kind：`REDIRECT_URL`/`FORM_HTML`/`QR_CODE`/`H5_URL`/`JSAPI_PARAMS`/`CLIENT_SECRET`）+ `ChannelResult.credential` 与 `accepted(ref, reason, credential)`；金额保持扁平（`amountMinor` + `currencyCode`）；**保留兼容构造器 + `default` 方法**（既有 4 处构造点 / 6 处测试桩**零改动**）；凭证不落库（🟢 Accepted，2026-09-19 由 Proposed 升级，**待实现**） | 0075 |
| [0076](0076-traffic-dyeing-and-alipay-sandbox.md#adr-0076) | 全链路染色分流 + 渠道模态落库 + 支付宝沙箱接入：`X-Dye-Tag`（`MOCK`/`SANDBOX`，**缺省即 `MOCK`**，非法值 400 **fail fast**）+ common-core 三段式（`DyeFilter` order=-190 / `DyeContext` / `DyeRequestInterceptor`，**入站读与出站写同批**）+ `payment_attempts.extra_json` 的 `channelMode` 键落库（~~`channel_mode` 专用列~~，**H2 修订**；写入侧强制 + 读取侧 fail-safe）+ 反向三路径（退款/查询/超时扫描）**按落库记录还原模态** + 单 Adapter 双模态（`AlipayGateway` 收口 SDK，`application/**` 禁依赖 SDK **Java 包 `com.alipay.api`**——⚠️ 非 Maven 坐标 `com.alipay.sdk`）+ `POST /internal/channels/alipay/notify`（RSA2 验签，响应体**恰好纯文本 `success`**）（🟢 Accepted，2026-09-19 由 Proposed 升级，**待实现**；**门 2 已裁决**：载体 = 通用 JSON 列、SDK = 确认引入） | 0076 |

| [0077](0077-ledger-accounting-foundation-decisions.md) | Ledger / Accounting 地基（ADR-0077~0079）：0077 记账契约「原始分录」→「Accounting Event + Posting Rule」+ 幂等键 Ledger 派生 + 负净额反向分录；0078 科目 Definition/Instance 两级 + 新增 CHANNEL_RECEIVABLE/BANK_CASH/CHANNEL_FEE_EXPENSE + CUSTOMER_CASH 判 LEGACY；0079 余额投影（同事务）+ 期间/关账 + 待记账台账（🟢 Accepted，2026-09-21 负责人批准并实现）；**Partially Supersedes ADR-0008**、修订 ADR-0009/0011/0018/0023 描述 | 0077 |

| [0080](0080-reconciliation-statement-and-fund-facts.md#adr-0080) | 对账真实化（032）：账单导入对象（`statement_imports`/`statement_lines`，删静默回退）+ 匹配键升级 `(merchantId, referenceType, reference)` 三级降级 + 差异复用 5 态生命周期并拆独立行表 + 032 为 `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 唯一产生方 + 结算口径先于差异策略化（🟡 Proposed，待裁决 H-032-1~6） | 0080 |
| [0081](0081-test-carrier-and-schema-replayability.md#adr-0081) | 测试载体升级（033）：Testcontainers 放宽至**仅测试作用域**（`deployment/test-infra` 共享基座，只切幂等竞争/并发累加/并发唯一三类）+ schema 双路径重放成 CI 门禁（空库全量 vs 存量+增量，快照 diff 为空）+ 运行时 RPC 边允许清单 `rpc-edges.txt` + 「033 只拥有载体与门禁、业务 Feature 拥有用例」（🟢 Accepted，2026-09-21 按 spec 推荐方案批准 H-033-1~5；变更 Constitution §Engineering.3） | 0081 |
| [0082](0082-failure-recovery-ownership-and-compensation.md#adr-0082) | 失败恢复归属与补偿边界（034）：恢复三不变式 R-1/R-2/R-3 + `pending_postings` 调用方侧一张表复用形状（5 次退避 ⇒ `ABANDONED`）+ C-19 后置动作与终态**同事务**（A 方案）+ **移除** Resilience4j + refund 侧对称补有界收敛（UNKNOWN 查询 / 卡 `REQUESTED` 扫描，自动重放 ≤2）+ 迟到成功复用 surplus 出口（🟡 Proposed，待裁决 H-034-1~5） | 0082 |
| [0083](0083-observability-baseline-slo-and-cardinality.md#adr-0083) | 观测基线（035）：4 个 SLO 以 Recording Rule 表达（双窗 burn rate，目标值 ≠ 实测值）+ 高基数政策 HC-1~HC-4 与 `MeterRegistry` 断言 + 密钥/报文不入日志取**路径排除**（不推翻 ADR-0027）+ **保留自研 traceId、否决 APM** + 告警 7→20 条五要素齐全（🟡 Proposed，待裁决 H-035-1~4） | 0083 |

## 编号规则

- 编号**只增不改、不复用**；一个 ADR 文档可容纳同一 Feature 的多条决策标签（如 `0016-refund-decisions.md` 含 0016~0018 与 0047）。
- ✅ **文件命名规则（2026-09-19 起生效，已统一应用到全部历史文件）**：**ADR 文件的文件名前缀 = 该文件内第一个 ADR 的编号**——如 `0074-redis-transactional-message.md` 承载 ADR-0074、`0071-user-payment-limit.md` 承载 ADR-0071。此举消除「目录序号 ↔ ADR 编号」的双编号心智负担（历史上 0031↔0070、0032↔0071、0033↔0072、0034↔0073 偏移不固定，必须查表才能对应）。
  > 2026-09-19 已将历史 bundle 文件（0004~0034）**全部按此规则重命名**并全库同步引用（序号冲突仅 ADR-0054 一处，见下「编号冲突备案」）。今后**新增 ADR 一律**以首个 ADR 编号作文件名前缀，不再使用目录序号。
- **下一可用编号：ADR-0084**（ADR-0071 = 用户支付限额，见 `0071-user-payment-limit.md`；ADR-0072 = payment-service 两层结构，见 `0072-two-layer-channel-architecture.md`；ADR-0073 = 支付渠道路由，见 `0073-channel-routing.md`；ADR-0074 = Redis 事务消息通道，见 `0074-redis-transactional-message.md`；**ADR-0075 = 聚合支付统一渠道契约，见 `0075-unified-channel-contract.md`（2026-09-19 登记）**；**ADR-0076 = 全链路染色分流 + 渠道模态落库 + 支付宝沙箱接入，见 `0076-traffic-dyeing-and-alipay-sandbox.md`（2026-09-19 登记）**；**ADR-0077~0079 = Ledger / Accounting 地基（spec 031），见 `0077-ledger-accounting-foundation-decisions.md`（2026-09-21 登记，🟡 Proposed）**；**ADR-0080 = 对账真实化（spec 032）、ADR-0082 = 失败恢复归属与补偿边界（spec 034）、ADR-0083 = 观测基线（spec 035），2026-09-21 登记，均 🟡 Proposed**；**ADR-0081 = 测试载体升级（spec 033）已 🟢 Accepted（2026-09-21 负责人裁决，随 spec 033 实现落地）**。⚠️ 0071 若 spec 027 被否决、0072/0073 若 spec 028 被否决或改号、0074 若 spec 029 被否决、**0075/0076 若 spec 030 被否决**、**0077~0079 若 spec 031 被否决或改号**、**0080/0082/0083 若对应 spec 032/034/035 被否决或改号**，此处水位随之回退）。
- ⚠️ **编号冲突备案（2026-09-06）**：`0054-core-payment-correctness.md` 与 `0054-order-payment-orchestration.md` **同时使用了 ADR-0054**（前者为确认性纪录「核心支付正确性约束」，后者为 016 编排职责归位）。速查表两行并存，引用时以「文件名 + 标题」消歧；后续如重排编号需全库同步引用（spec 016 / AGENTS.md / systems 文档多处引用 `0054-order-payment-orchestration.md` 的 ADR-0054，改动成本高，暂保持现状）。
- ✅ **ADR-0038~0046 号段已全部落文（无空号）**，均收录于 `0038-next-stage-decisions.md`：
  - **0038**（演示形态）→ **Superseded by ADR-0048**（议题由 0048 处理，结论一致：做 `mock-channel-web` 收银台组件）；
  - **0039/0040**（012 幂等键签发 / 并发幂等接管）→ 2026-09-02 **补写**（此前代码已引用但无文档）；
  - **0041~0046**（013/014）→ 2026-08-31 收口。
  该号段原由已归档的 `next-stage-design.md`（`docs/archive/design/2026-09-19-next-stage-011-014/next-stage-design.md`）§9 分配（该节已于 2026-09-14 随文档精简删除）（Mock 收银台 / 幂等键签发 / 并发幂等接管 / 库存域归属 / 库存扣减时机 / 超时释放机制 / Redis 引入论证 / Redis 用途边界 / 秒杀限流），**不得挪作他用**。
- ADR-0047 是预留段之后的第一条实际决策（退款金额校验口径），故跳号——详见 `0016-refund-decisions.md` 头部说明。
- ADR-0048~0051 为 `011-demo-showcase` 的四条决策，见 `0048-demo-showcase-decisions.md`；其中 ADR-0048 已于 2026-08-31 按负责人裁决修订（新增收银台组件）。
- ADR-0052 为渠道回调验签接入候选，见 `0052-channel-callback-signature-decisions.md`（⛔ **Not Implemented**：2026-08-31 用户确认回退到 ADR-0025 空实现，不落地真实验签）。

## 状态机

一条 ADR 的状态按以下路径演进，永不删除，只改状态：

```text
Proposed（提案） → Accepted（已接受/生效） → Superseded（被新 ADR 取代）或 Deprecated（废弃）
```

- **Proposed**：提案讨论中，尚未生效。
- **Accepted**：已批准，是当前权威约束。
- **Rejected**：负责人明确否决，方案不采用；已有实现须回退（回退清单记于该 ADR 内，便于日后重新开放时复原）。
- **Not Implemented**：方案认可但本期不落地；需区分两种形态——
  - **预留空实现**：结构骨架（挂点 / 注册 / 路径匹配）保留，业务判定方法为空实现并留 `TODO(ADR-00xx)`；
  - **不做（代码已删）**：本期完全不实现，相关类与配置一并删除，ADR 保留为「将来要启用时的实施方案」。
- **Superseded**：被更新的 ADR 取代；新旧两端都必须互相链接（新 ADR 写 `Supersedes`，旧 ADR 写 `Superseded by`）。
- **Deprecated**：决策不再适用但无替代者，保留作为历史。
- 已接受的 ADR 视为不可变：要改变决策就写一条新 ADR 去 supersede 旧的，而不是直接编辑旧文件。

## 何时写 ADR

遇到以下情况之一，写一条 ADR：

- 架构/技术选型（框架、中间件、数据库边界、通信方式）
- 引入或移除一个服务 / 中间件 / 依赖
- 服务边界或数据所有权变化
- 破坏性迁移、安全策略、生产部署策略

涉及 Constitution §8「人类决策边界」的决策，必须先经负责人确认，再落 ADR。
