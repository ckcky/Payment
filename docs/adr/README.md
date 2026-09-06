# Architecture Decision Records（ADR）

> 记录项目的重要、不可逆架构决策：为什么这么选、备选方案、后果、当前是否仍有效。ADR 永不删除，只通过状态演进。

## 索引

| 编号 | 标题 | 状态 | 关联 |
|---|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md) | 采用 Spring Cloud 微服务架构 | Accepted | 取代 Constitution §3.1；→ 0002 |
| [0002](0002-technology-stack.md) | 技术栈选型 | Accepted | ← 0001 |
| [0003](0003-payment-reliability-decisions.md) | 支付可靠性决策集合（ADR-0003~0007） | 混合 | Feature 003；含：UNKNOWN 收敛触发(Accepted) / 超时进 UNKNOWN(Accepted) / 重试模型(Accepted) / 人工收敛(**Not Implemented，延后 Phase 9**) / 终态冲突(Accepted) |
| [0004](0004-ledger-design-decisions.md) | Ledger 设计决策集合（ADR-0008~0011） | **Accepted**（2026-08-29 确认；0010 已修订） | Feature 004；含：复式记账数据模型(Accepted) / 记账触发与一致性(Accepted) / **金额只用 long 分、不启用 Money VO(Accepted·修订)** / MVP 记账范围(Accepted) |
| [0005](0005-payment-reliability-impl-decisions.md) | 支付可靠性**实现期**决策集合（ADR-0012~0015） | **Accepted**（2026-08-29 确认；0012/0013 已修订） | Feature 003；含：**双响应码错误分类 + 通信失败一律重试(修订)** / **重试不落库、请求内联重试(修订)** / 同 attempt 重放 / UNKNOWN 真实时长度量 / **超时口径 RPC 1s·HTTP 1.5s(新增)** |
| [0006](0006-refund-decisions.md) | 退款决策集合（ADR-0016~0018、ADR-0047） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0016 部分退款 ❌ **Rejected（不做，代码已回退）**；0017 / 0018 ✅ **Accepted**；0047 退款金额校验口径 🟡 **Proposed**） | Feature 005；含：**部分退款支持模型(裁决不做·已回退，累计一律按申请额)** / refund→fulfillment 编排(Accepted) / refund→ledger 记账接入(Accepted) / **退款金额校验口径(只做累计不超付，不做全额等值校验——与 001 spec「支持部分退款和多次退款」基线对齐)** |
| [0007](0007-reconciliation-decisions.md) | 对账决策集合（ADR-0019~0021） | ✅ **Accepted**（2026-08-30 裁决 accept） | Feature 006；含：批次差异处理生命周期 / 渠道账单按周期 fixture + 显式回退 / 事实读取 RPC 弹性（不引 Resilience4j） |
| [0008](0008-settlement-decisions.md) | 结算决策集合（ADR-0022~0023） | Proposed（待负责人确认） | Feature 007；含：调整项模型（方向/持久化/门禁/净额公式） / 闸门纵深防御 + settlement→ledger 记账归属与时机 / 幂等键错配行为变更 / N1 商户维度缺口归属 |
| [0009](0009-risk-security-decisions.md) | 风险 / 安全决策集合（ADR-0024~0028） | **混合**（2026-08-30 裁决 / 2026-08-31 落地：0024 鉴权 ✅ **方案 Accepted／实现=预留空函数**；0025 验签 ✅ **方案 Accepted／实现=预留空函数**；0026 密钥 ✅ **Accepted（明文）**；0027 脱敏 ⛔ **Not Implemented（不管，类已删）**；0028 风控 ⛔ **Not Implemented（不管，类已删）**） | Feature 009；含：内部服务鉴权(**接入点保留**·`verifyServiceToken` 空实现恒放行，`/internal/**` 恒可达) / 渠道回调 HMAC 验签(**骨架保留**·`verifySignature` 空实现恒通过，`SignatureVerifier` 工具类留作接入时复用) / 密钥明文 env 注入(当前无密钥需注入) / **脱敏(不做，类已删)** / **风控(不做，类已删，不留挂点)** |
| [0010](0010-distributed-evolution-decisions.md) | 分布式演进决策集合（ADR-0029~0033） | **混合**（2026-08-30 裁决：0029 / 0030 / 0032 / 0033 ✅ **Accepted（保持现状）**；0031 异步消息 ⛔ **Not Implemented（不使用 MQ）**） | Feature 010；含：不拆分转而建门禁 / 拆库触发判据(保持现状) / **引入异步消息判据(不做)** / T0~T3 分层 / 提案模板与运行手册作为门禁 |
| [0011](0011-internal-token-decisions.md) | 内部服务令牌闭环（ADR-0034~0037） | ⛔ **Not Implemented（不做，2026-08-30 裁决「出入站鉴权令牌都先不做」，2026-08-31 代码已清理）** | Feature 009 收尾 T013；含：**出站令牌传播范围(不做·代码已删)** / **入站鉴权推广范围(不推广)** / **令牌轮换(不做)** / **鉴权失败可观测(不做)**；`platform.security.*` 配置已移除，将来启用时按本文档与 0009 的 0024 成对实施 |
| [0012](0012-demo-showcase-decisions.md) | 端到端演示形态（ADR-0048~0051） | ✅ **Accepted**（2026-08-31 裁决；**ADR-0048 已修订**——推翻「不做 `mock-channel-web`」，改为**新增收银台组件**，含 payUrl 跳转链路与演示页面） | Feature 011；含：**演示形态(新增 `mock-channel-web`：收银台页+回调签名转发+演示控制台+同源代理)** / **Mock 渠道场景配置化(`payment.channel.mock-scenario`，已落地)** / **对账演示账单(生成 CSV 写入 `target/classes`，不改生产代码)** / **演示脚本纪律(只编排不伪造、断言失败即非零退出)** |
| [0013](0013-channel-callback-signature-decisions.md) | 渠道回调验签接入（ADR-0052） | ⛔ **Not Implemented**（2026-08-31 用户确认回退到 ADR-0025 空实现） | Feature 011 前置候选；**不落地** `SignatureVerifier` 真实校验，代码维持 `ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`（占位放行）；`application.yml` 已移除 `payment.security.*` 配置；`ChannelCallbackSecurityTest` 维持占位期放行断言（`invalidSignatureIsAllowedWhileSignatureVerificationIsStubbed` 等） |
| [0014](0014-next-stage-decisions.md) | 下一阶段决策集合（ADR-0038~0046） | **Accepted**（0041~0046 于 2026-08-31 收口；**0038 Superseded by 0048**；**0039/0040 于 2026-09-02 补写**；0044 偏离 roadmap §7 论证闸门） | 012-entry-idempotency（0039 幂等键签发与存储 / 0040 并发幂等接管策略）+ 013-inventory-reservation（0041 库存域归属 / 0042 扣减时机 / 0043 超时释放机制）+ 014-seckill-and-cache（0044 Redis 引入论证·偏离 / 0045 用途边界 / 0046 限流策略）；代码先行，见 ADR-0053。**0039/0040 的补写消除了代码中已存在但文档缺失的悬空引用**（`OrderController` / `OrderEntryIdempotencyService` / `IdempotencyDecision` / `docker-compose.yml`） |
| [0015](0015-wip-ahead-of-roadmap.md) | 库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置（ADR-0053） | **Accepted**（2026-08-31，提交负责人复盘；若否决则回退 013/014 代码） | 偏离 / 处置日志：working tree 含 013-inventory-reservation / 014-seckill-and-cache 实质性实现，**超前顺序、缺 spec/ADR-0041~0046、014 的 Redis 引入未经 roadmap §7 论证闸门**；决策=保留代码（编译+测试通过，且与 011 在 order-service 纠缠不可干净拆分），spec/ADR 补写列为 TODO，待复盘收口 |
| [0016](0016-core-payment-correctness.md) | 核心支付正确性约束（ADR-0054，确认性纪录） | ✅ **Accepted**（2026-09-04） | Feature 002 资金约束确认；含：金额铁律 / 状态机收敛 / 幂等 / 终态冲突等既有约束的确认性落地 |
| [0017](0017-entry-and-infra-decisions.md) | 入口与基础设施决策集合（ADR-0055~0057） | ✅ **Accepted**（2026-09-04） | 含：**支付意图幂等键由 order-service 生成(0055)** / **Nacos 启用——落实 ADR-0002、撤销「暂不启用」偏离(0056，实施见 0019/ADR-0059)** / 服务未容器化(0057) |
| [0018](0018-performance-baseline.md) | 性能与容量目标基线（ADR-0058） | ✅ **Accepted**（2026-09-04，3 项🟡已落地为 JUnit 并发/量化测试 + 分布式端到端验证） | `deployment/performance/`；含分布式端到端验证清单 `R3-distributed-verification.md` |
| [0019](0019-enable-nacos.md) | 启用 Nacos 服务发现与注册中心（ADR-0059） | ✅ **Accepted**（2026-09-04；**撤销 ADR-0056 之前的「暂不启用」偏离**） | 12 个 `@FeignClient` 去掉硬编码 `url=`，改走服务名发现；`docker-compose.yml` 新增 nacos 容器；**Nacos 现为跨服务调用硬依赖** |
| [0020](0020-redis-lettuce-pool.md) | Redis 客户端启用 Lettuce 连接池（ADR-0060） | ✅ **Accepted**（2026-09-04，压测驱动） | 关联 ADR-0044（Redis 引入论证）、ADR-0045（用途边界）、ADR-0058（性能基线） |
| [0021](0021-observability-panel-fix.md) | 可观测性补全与演示脚本漂移修复（ADR-0061） | ✅ **Accepted**（2026-09-04） | HTTP 直方图 / 计数器命名避保留后缀 / 对账 `closed_at` / 尝试状态机权威收敛 / 演示脚本对齐 |
| [0022](0022-business-no-snowflake.md) | 业务单号统一采用两字母前缀 + 雪花算法（ADR-0062） | ✅ **Accepted**（2026-09-04） | TX/OR/PM/RF/SB/RB/LP 七类单据；主键保持自增，业务单号独立列 |
| [0023](0023-cross-service-reference-by-business-no.md) | 跨系统关联一律使用业务单号（ADR-0063） | ✅ **Accepted**（2026-09-04） | 关联列 / 接口 / 回调全部切单号，数值主键不跨服务 |
| [0024](0024-multi-payment-per-transaction.md) | 一交易多支付单 + 退款域并入 payment-service（ADR-0064） | ✅ **Accepted**（2026-09-04；**§决策#4 Superseded by ADR-0065**） | spec 015；下单不建单 / 显式选渠道、幂等键含 attemptSeq、三渠道 mock、服务数 10→9 |
| [0025](0025-order-payment-orchestration.md) | 支付编排职责归位（**ADR-0065**；曾误标 ADR-0054，与 0016 重号，2026-09-06 更正） | ✅ **Accepted**（2026-09-06 落地） | spec 016；Supersedes ADR-0064 §决策#4（自动退款归属） |

## ADR 编号速查（0001–0065）

| [0054](0016-core-payment-correctness.md#adr-0054) | 核心支付正确性约束（确认性纪录） | 0016 |
| [0055](0017-entry-and-infra-decisions.md#adr-0055) | 支付意图幂等键由 order-service 生成 | 0017 |
| [0056](0017-entry-and-infra-decisions.md#adr-0056) | Nacos 启用（落实 ADR-0002，撤销「暂不启用」偏离，见 ADR-0059） | 0017 |
| [0057](0017-entry-and-infra-decisions.md#adr-0057) | 服务未容器化 | 0017 |
| [0058](0018-performance-baseline.md#adr-0058) | 性能与容量目标基线（已建立，3 项🟡已落地为 JUnit 并发/量化测试，并含分布式端到端验证 2026-09-04） | 0018 |
| [0059](0019-enable-nacos.md#adr-0059) | 启用 Nacos 服务发现与注册中心（实施记录） | 0019 |
| [0060](0020-redis-lettuce-pool.md#adr-0060) | Redis 客户端启用 Lettuce 连接池（压测驱动，2026-09-04） | 0020 |
| [0061](0021-observability-panel-fix.md#adr-0061) | 可观测性补全与演示脚本漂移修复（HTTP 直方图 / 计数器命名避保留后缀 / 对账 closed_at / 尝试状态机权威收敛 / 演示脚本对齐，2026-09-04） | 0021 |
| [0062](0022-business-no-snowflake.md#adr-0062) | 业务单号统一采用两字母前缀 + 雪花算法（TX/OR/PM/RF/SB/RB/LP，主键保持自增，2026-09-04） | 0022 |
| [0063](0023-cross-service-reference-by-business-no.md#adr-0063) | 跨系统关联一律使用业务单号，数值主键不跨服务（关联列/接口/回调全部切单号，2026-09-04） | 0023 |
| [0064](0024-multi-payment-per-transaction.md#adr-0064) | 一交易多支付单（Feature 015）：下单不建单/显式选渠道、幂等键含 attemptSeq、409 ORDER_NOT_PAYABLE + 自动退款、退款域并入 payment-service（10→9）、三渠道 mock（2026-09-04） | 0024 |
| [0065](0025-order-payment-orchestration.md#adr-0065) | 支付编排职责归位：order 升为业务编排者 / payment 退回能力提供方；自动退款决策与发起归属 order transaction 层（transactionNo + paymentNo）；Supersedes ADR-0064 §决策#4（✅ Accepted，2026-09-06 落地）。**注**：曾误标 ADR-0054，与 0016 重号，2026-09-06 更正为 0065 | 0025 |

> 决策 #2 落地：保留 15 个聚合文件不动，此处建立「ADR 编号 → 承载文件 → 锚点」跳转表，便于从任意编号直达正文。编号链接指向文件内 `<a id="adr-XXXX">` 锚点。

| 编号 | 决策标题 | 承载文件 |
|---|---|---|
| [0001](0001-adopt-spring-cloud-microservices.md#adr-0001) | 采用 Spring Cloud 微服务架构 | [0001-adopt-spring-cloud-microservices.md](0001-adopt-spring-cloud-microservices.md) |
| [0002](0002-technology-stack.md#adr-0002) | 技术栈选型 | [0002-technology-stack.md](0002-technology-stack.md) |
| [0003](0003-payment-reliability-decisions.md#adr-0003) | UNKNOWN 收敛触发机制 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0004](0003-payment-reliability-decisions.md#adr-0004) | 超时进入 UNKNOWN 的策略 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0005](0003-payment-reliability-decisions.md#adr-0005) | 支付重试模型 | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0006](0003-payment-reliability-decisions.md#adr-0006) | 人工收敛能力与权限/审计约束 —— **本阶段不做（Not Implemented）** | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0007](0003-payment-reliability-decisions.md#adr-0007) | 终态冲突策略（迟到成功不覆盖已失败支付） | [0003-payment-reliability-decisions.md](0003-payment-reliability-decisions.md) |
| [0008](0004-ledger-design-decisions.md#adr-0008) | Ledger 数据模型（复式记账 + 科目/分录结构） | [0004-ledger-design-decisions.md](0004-ledger-design-decisions.md) |
| [0009](0004-ledger-design-decisions.md#adr-0009) | 记账触发与一致性（同步 RPC 幂等记账 + 失败兜底） | [0004-ledger-design-decisions.md](0004-ledger-design-decisions.md) |
| [0010](0004-ledger-design-decisions.md#adr-0010) | 金额表示（Ledger 启用 Money 值对象 vs 仅 long 分） | [0004-ledger-design-decisions.md](0004-ledger-design-decisions.md) |
| [0011](0004-ledger-design-decisions.md#adr-0011) | MVP 记账范围（支付 / 退款 / 结算哪些首批） | [0004-ledger-design-decisions.md](0004-ledger-design-decisions.md) |
| [0012](0005-payment-reliability-impl-decisions.md#adr-0012) | 重试的错误分类来源（双响应码；通信失败一律重试） | [0005-payment-reliability-impl-decisions.md](0005-payment-reliability-impl-decisions.md) |
| [0013](0005-payment-reliability-impl-decisions.md#adr-0013) | 重试调度的载体（不落库，请求内联重试） | [0005-payment-reliability-impl-decisions.md](0005-payment-reliability-impl-decisions.md) |
| [0014](0005-payment-reliability-impl-decisions.md#adr-0014) | 重试的幂等与事务边界（同 attempt 重放） | [0005-payment-reliability-impl-decisions.md](0005-payment-reliability-impl-decisions.md) |
| [0015](0005-payment-reliability-impl-decisions.md#adr-0015) | UNKNOWN 真实收敛时长的度量方式 | [0005-payment-reliability-impl-decisions.md](0005-payment-reliability-impl-decisions.md) |
| [0016](0006-refund-decisions.md#adr-0016) | 部分退款支持模型（如何让 PARTIALLY_SUCCEEDED 可达、部分金额如何跟踪） | [0006-refund-decisions.md](0006-refund-decisions.md) |
| [0017](0006-refund-decisions.md#adr-0017) | refund → fulfillment 编排（补齐缺失 RPC vs 修改文档声明） | [0006-refund-decisions.md](0006-refund-decisions.md) |
| [0018](0006-refund-decisions.md#adr-0018) | refund → ledger 记账接入（与 spec 004-ledger 的归属与时机） | [0006-refund-decisions.md](0006-refund-decisions.md) |
| [0019](0007-reconciliation-decisions.md#adr-0019) | 批次差异处理生命周期（接线 `beginProcessing`/`close` 与「处理中/关闭」语义） | [0007-reconciliation-decisions.md](0007-reconciliation-decisions.md) |
| [0020](0007-reconciliation-decisions.md#adr-0020) | 渠道账单来源（按周期 fixture + 显式回退 vs 参数化加载器 vs 维持全局 fixture） | [0007-reconciliation-decisions.md](0007-reconciliation-decisions.md) |
| [0021](0007-reconciliation-decisions.md#adr-0021) | 事实读取 RPC 的弹性（超时 / 有限重试 / 错误归一化 vs 引入熔断中间件） | [0007-reconciliation-decisions.md](0007-reconciliation-decisions.md) |
| [0022](0008-settlement-decisions.md#adr-0022) | 调整项模型（方向语义 / 持久化形态 / 登记门禁 / 净额公式 / 死代码处置） | [0008-settlement-decisions.md](0008-settlement-decisions.md) |
| [0023](0008-settlement-decisions.md#adr-0023) | 已确认事实闸门的纵深防御与 settlement → ledger 记账归属 | [0008-settlement-decisions.md](0008-settlement-decisions.md) |
| [0024](0009-risk-security-decisions.md#adr-0024) | 内部服务间调用鉴权 | [0009-risk-security-decisions.md](0009-risk-security-decisions.md) |
| [0025](0009-risk-security-decisions.md#adr-0025) | 渠道回调签名校验 | [0009-risk-security-decisions.md](0009-risk-security-decisions.md) |
| [0026](0009-risk-security-decisions.md#adr-0026) | 密钥管理 | [0009-risk-security-decisions.md](0009-risk-security-decisions.md) |
| [0027](0009-risk-security-decisions.md#adr-0027) | 敏感数据脱敏 | [0009-risk-security-decisions.md](0009-risk-security-decisions.md) |
| [0028](0009-risk-security-decisions.md#adr-0028) | 最小风控规则 | [0009-risk-security-decisions.md](0009-risk-security-decisions.md) |
| [0029](0010-distributed-evolution-decisions.md#adr-0029) | 本期不做任何服务拆分，先立「可拆分性」门禁 | [0010-distributed-evolution-decisions.md](0010-distributed-evolution-decisions.md) |
| [0030](0010-distributed-evolution-decisions.md#adr-0030) | 独立数据库迁移：先定触发条件，不动手 | [0010-distributed-evolution-decisions.md](0010-distributed-evolution-decisions.md) |
| [0031](0010-distributed-evolution-decisions.md#adr-0031) | 跨服务异步消息：只在有明确证据时才评估 | [0010-distributed-evolution-decisions.md](0010-distributed-evolution-decisions.md) |
| [0032](0010-distributed-evolution-decisions.md#adr-0032) | 独立扩缩容与故障隔离：按关键等级分级，而非一刀切 | [0010-distributed-evolution-decisions.md](0010-distributed-evolution-decisions.md) |
| [0033](0010-distributed-evolution-decisions.md#adr-0033) | 拆分提案模板与运行手册：每次拆分的准入门禁 | [0010-distributed-evolution-decisions.md](0010-distributed-evolution-decisions.md) |
| [0034](0011-internal-token-decisions.md#adr-0034) | 出站内部服务令牌的传播 | [0011-internal-token-decisions.md](0011-internal-token-decisions.md) |
| [0035](0011-internal-token-decisions.md#adr-0035) | 入站鉴权的推广范围 | [0011-internal-token-decisions.md](0011-internal-token-decisions.md) |
| [0036](0011-internal-token-decisions.md#adr-0036) | 令牌轮换策略 | [0011-internal-token-decisions.md](0011-internal-token-decisions.md) |
| [0037](0011-internal-token-decisions.md#adr-0037) | 鉴权失败的可观测性 | [0011-internal-token-decisions.md](0011-internal-token-decisions.md) |
| [0038](0014-next-stage-decisions.md#adr-0038) | 演示形态：Mock 收银台 vs 纯脚本（011） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0039](0014-next-stage-decisions.md#adr-0039) | 下单幂等键的签发与存储位置（012） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0040](0014-next-stage-decisions.md#adr-0040) | 并发幂等「超时接管」策略（012） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0041](0014-next-stage-decisions.md#adr-0041) | 库存域归属（013） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0042](0014-next-stage-decisions.md#adr-0042) | 库存扣减时机（013） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0043](0014-next-stage-decisions.md#adr-0043) | 订单超时释放库存的机制（013） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0044](0014-next-stage-decisions.md#adr-0044) | Redis 引入论证（014） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0045](0014-next-stage-decisions.md#adr-0045) | Redis 用途边界（014） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0046](0014-next-stage-decisions.md#adr-0046) | 秒杀限流策略与丢弃语义（014） | [0014-next-stage-decisions.md](0014-next-stage-decisions.md) |
| [0047](0006-refund-decisions.md#adr-0047) | 退款金额校验口径（ADR-0016 回退后，是否强制「申请额 = 可退全额」） | [0006-refund-decisions.md](0006-refund-decisions.md) |
| [0048](0012-demo-showcase-decisions.md#adr-0048) | 演示形态：~~不新增 `mock-channel-web`~~ → **新增 `mock-channel-web` 组件（2026-08-31 负责人裁决修订）** | [0012-demo-showcase-decisions.md](0012-demo-showcase-decisions.md) |
| [0049](0012-demo-showcase-decisions.md#adr-0049) | Mock 渠道场景配置化 | [0012-demo-showcase-decisions.md](0012-demo-showcase-decisions.md) |
| [0050](0012-demo-showcase-decisions.md#adr-0050) | 对账演示账单：生成 CSV 写入 `target/classes`，不改生产代码 | [0012-demo-showcase-decisions.md](0012-demo-showcase-decisions.md) |
| [0051](0012-demo-showcase-decisions.md#adr-0051) | 演示脚本纪律：只编排不伪造、断言失败即非零退出 | [0012-demo-showcase-decisions.md](0012-demo-showcase-decisions.md) |
| [0052](0013-channel-callback-signature-decisions.md#adr-0052) | 渠道回调验签接入真实现 —— ⛔ 未实施（回退至 ADR-0025 占位） | [0013-channel-callback-signature-decisions.md](0013-channel-callback-signature-decisions.md) |
| [0053](0015-wip-ahead-of-roadmap.md#adr-0053) | 库存/秒杀代码超前 roadmap 落地（缺 spec/ADR）的处置 | [0015-wip-ahead-of-roadmap.md](0015-wip-ahead-of-roadmap.md) |

## 编号规则

- 编号**只增不改、不复用**；一个 ADR 文档可容纳同一 Feature 的多条决策标签（如 0006 含 0016~0018 与 0047）。
- **下一可用编号：ADR-0066**（已用至 ADR-0065）。
- ⚠️ **编号历史更正**：`0025-order-payment-orchestration.md` 曾误标 **ADR-0054**，与 `0016-core-payment-correctness.md` 的 ADR-0054 重号。2026-09-06 文档治理时更正为 **ADR-0065**；旧锚点 `adr-0054` 在该文件内保留以兼容既有链接。**新引用一律使用 ADR-0065**。
- ✅ **ADR-0054~0065 号段已全部落文（无空号）**：0054→`0016`；0055~0057→`0017`；0058→`0018`；0059→`0019`；0060→`0020`；0061→`0021`；0062→`0022`；0063→`0023`；0064→`0024`；0065→`0025`。
- ✅ **ADR-0038~0046 号段已全部落文（无空号）**，均收录于 `0014-next-stage-decisions.md`：
  - **0038**（演示形态）→ **Superseded by ADR-0048**（议题由 0048 处理，结论一致：做 `mock-channel-web` 收银台组件）；
  - **0039/0040**（012 幂等键签发 / 并发幂等接管）→ 2026-09-02 **补写**（此前代码已引用但无文档）；
  - **0041~0046**（013/014）→ 2026-08-31 收口。
  该号段由 `next-stage-design.md` §9 分配（Mock 收银台 / 幂等键签发 / 并发幂等接管 / 库存域归属 / 库存扣减时机 / 超时释放机制 / Redis 引入论证 / Redis 用途边界 / 秒杀限流），**不得挪作他用**。
- ADR-0047 是预留段之后的第一条实际决策（退款金额校验口径），故跳号——详见 `0006-refund-decisions.md` 头部说明。
- ADR-0048~0051 为 `011-demo-showcase` 的四条决策，见 `0012-demo-showcase-decisions.md`；其中 ADR-0048 已于 2026-08-31 按负责人裁决修订（新增收银台组件）。
- ADR-0052 为渠道回调验签接入候选，见 `0013-channel-callback-signature-decisions.md`（⛔ **Not Implemented**：2026-08-31 用户确认回退到 ADR-0025 空实现，不落地真实验签）。

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
