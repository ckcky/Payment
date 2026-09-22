# ADR 落点追溯索引（Decision Traceability）

> 本文件记录**已决策的 ADR 在总体技术方案与系统设计文档中的落点**，便于审计与防漂移。
> 「ADR 编号 → 承载文件 → 锚点」的完整跳转表见 [README.md](README.md)；本文是它的补充：**「决策 → 落点」的反向索引**。
>
> 标记：**【P0·内联】** = 已在 `docs/architecture/technical-solution.md` 正文对应章节修正并体现；**【P1·索引】** = 集中索引；**【ADR 文件】** = 仅纪录于 `docs/adr/`，正文未展开（按惯例以 ADR 文件为权威）。

## 1. 已在技术方案正文内联体现的 ADR（P0）

| ADR | 决策 | 落点 |
|---|---|---|
| ADR-0002 | 技术栈选型 | technical-solution §3.5（Nacos 未启用见 ADR-0056） |
| ADR-0010 | 金额表示：long 分 + currencyCode，Money VO 不启用 | technical-solution §4.2 |
| ADR-0018 | 退款 → Ledger 记账接入（冲正分录） | technical-solution §4.3.5 |
| ADR-0019 / ADR-0020 / ADR-0021 | 对账：状态机接线 / 渠道账单周期 fixture / 事实读取弹性 | technical-solution §4.3.5（reconciliation-service.md） |
| ADR-0021 | 不引入熔断中间件 | payment-service 弹性口径 |
| ADR-0024 / ADR-0025 / ADR-0027 / ADR-0028 | 安全：鉴权空实现 / 验签空实现 / 脱敏不做 / 风控不做 | technical-solution §2.4、§5.2 |
| ADR-0026 | 密钥明文 env 注入 | technical-solution §5.2 |
| ADR-0039 / ADR-0040 | 下单入口幂等键（Redis 唯一存储、409+Retry-After） | order-service.md §9.1 |
| ADR-0041 / ADR-0042 / ADR-0043 | 库存域归属 catalog / 三段式扣减 / ZSet 超时释放 | catalog-service.md、order-service.md |
| ADR-0044 / ADR-0045 / ADR-0046 | Redis 引入论证 / 用途边界（非数据源）/ 固定窗口限流 | technical-solution §2.3、§3.5、§5 |
| ADR-0047 | 退款金额校验口径（累计不超付） | technical-solution §4.3.3 |
| ADR-0048 ~ ADR-0051 | 演示形态：收银台 / 场景配置化 / 演示账单 / 脚本纪律 | technical-solution §3.2、§6、§4.3.5 |
| ADR-0052 | 渠道回调验签接入（回退至 ADR-0025 空实现） | technical-solution §5.2 |
| ADR-0053 | 013/014 代码超前 roadmap 落地处置 | technical-solution §7 |
| ADR-0070 | 本地全栈容器化：Compose 编排 10 服务 + 双轨并存（宿主/容器）+ 宿主打 jar 镜像只 COPY | technical-solution §6（实施与部署策略之「双模式运行」）、deployment/README.md、runbook（🟢 Accepted / Implemented，2026-09-15；Supersedes ADR-0057） |
| ADR-0072 | payment-service 两层结构（payment 支付层 / channelAttempt 渠道层）：表归属与写入口分离 | technical-solution §3.1.1（领域模型 + 两层说明）、§4.2（基数修订）、§4.3（链路时序）；payment-service.md §1、§4.1.1、§7.3（✅ Accepted → Implemented） |
| ADR-0073 | 支付渠道路由：注册表 + 规则化确定性选路；前向选路与反向按记录解析严格分离 | payment-service.md §4.1.1、§6.2（channelCode 可选 + `ChannelRegistry`/`ChannelRouter`；✅ Accepted → Implemented） |
| ADR-0074 | Redis 事务消息通道：Streams + 半消息协议（prepare → 本地事务 → commit/rollback → 5s 回查真相表）承载跨服务异步解耦；混合拓扑（事实类广播 / 动作类点对点）；traceId 跨异步边界连续；trace 消费组落 `order_event_log` | technical-solution §1.2 / §2.3 / §3.4 / §4.4（🟢 **Accepted → Implemented**，2026-09-19 提出并拍板，2026-09-20 随 spec 029 落地于 master `5e2c00d`）；`common/common-redis-mq` starter + 五服务 `{service}/mq` 包 |
| ADR-0077~0079 | Ledger / Accounting 地基：Accounting Event + Posting Rule + 两级科目（Definition/Instance）+ 余额投影（同事务）+ 期间/关账 + 待记账台账 | technical-solution §2.3/§4.3.5（Accounting Event 入站契约）、systems/ledger-service.md（Posting Rules / 科目两级 / `ledger_periods` 关账）、§3.2（Feature 031 入站契约升级）（🟢 **Accepted → Implemented**，2026-09-21 随 spec 031 落地） |
| ADR-0080 | 对账真实化：渠道账单导入对象（`statement_imports`/`statement_lines`）+ 匹配键三级降级 + 差异拆独立行表 + 032 为 `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 唯一产生方 | technical-solution §4.3.5、systems/reconciliation-service.md（🟢 **Accepted → Implemented**，2026-09-21 随 spec 032 落地） |
| ADR-0081 | 测试载体升级：真库 Testcontainers（仅测试作用域）+ schema 双路径重放门禁 + RPC 边允许清单 | `docs/guides/engineering-standards.md` §4/§12、technical-solution §10（Verification）（🟢 **Accepted → Implemented**，2026-09-21 随 spec 033 落地） |
| ADR-0082 | 失败恢复归属与补偿闭环：`pending_postings` 调用方台账 + C-19 后置动作同事务 + refund 侧有界收敛 + 移除 Resilience4j | technical-solution §2.3（例外观）/§4.4、§5.1.1（超时政策表）、systems/*（🟢 **Accepted → Implemented**，2026-09-21 随 spec 034 落地） |
| ADR-0083 | 观测基线：4 个 SLO Recording Rule + 高基数政策 HC-1~HC-4 + 密钥/报文不入日志（路径排除） | **（🟡 Proposed，L0 尚无落点）** spec 035 设计轮产物；未裁决前不得进入 L0 |

| ADR-0075 | 聚合支付统一渠道契约：四组结构化字段（`Goods` / `CallbackUrls` / `Payer` / `PaymentScene`）+ `channelExtra` 扩展袋 + 类型化 `PayCredential`（6 种 Kind）+ `ChannelResult.credential` 与 `accepted(ref, reason, credential)`；金额保持扁平；保留兼容构造器与 `default` 方法保证零中断编译；凭证不落库 | payment-service.md **§6.11 已补（2026-09-19 Phase 0 收口）**：✅ 悬空锚点 `#611-渠道内部契约spec-030--adr-0075` **现已可达**（2026-09-22 文档治理后章号为 `### 6.11 渠道内部契约`）；✅ 两个重复的 `### 3.10` 已消除（治理前：渠道路由＝3.10、事件通道＝**3.12**；治理后：＝**6.10** / **6.12**）。本节当前承载**契约条款**，「实现实况」表见 `payment-service.md`（🟢 Accepted → **Implemented**，2026-09-19 由 Proposed 升级、2026-09-20 随 spec 030 合入 master） |
| ADR-0076 | 全链路染色分流（mock / 沙箱）+ 渠道模态落库 + 支付宝沙箱接入：`X-Dye-Tag` 三段式透传（common-core，入站读与出站写同批）+ `payment_attempts` 模态落库与反向三路径还原（**落库载体已按 H2 修订**：~~专用列 `channel_mode`~~ → 通用 JSON 列 **`extra_json`** 的 **`channelMode`** 键，**TEXT 存 JSON**，语义不变）+ 单 Adapter 双模态（`AlipayGateway` 收口 SDK）+ `POST /internal/channels/alipay/notify`（RSA2，纯文本 `success`） | payment-service.md **§4.1 / §4.2 已同步 H2 载体**（`channelMode` 键 + fail-safe 读法 + 迁移脚本 `030-payment-attempt-extra-json.sql`，2026-09-19 Phase 0）；§6.2 / §6.11 **待实现**；technical-solution §4.4 已写入「**渠道事实 / 平台事实可合法不一致**」口径（终态吸收的解释，2026-09-19 Phase 0），§2.4 / §3.5 / §4.3 / §5.2 **待实现**；`deployment/schema/03-payment-schema.sql` + 测试 H2 schema + 增量迁移 **`030-payment-attempt-extra-json.sql`**（~~`030-payment-attempt-channel-mode.sql`~~）（🟢 **Accepted → Implemented**，2026-09-19 由 Proposed 升级、2026-09-20 随 spec 030 合入 master；H3 SDK 依赖**已裁决引入**；H2 载体**已裁决为通用 JSON 列**） |

## 2. P1 决策索引（集中列出，避免散落漂移）

| ADR | 决策要点 | technical-solution 落点 | systems 落点 |
|---|---|---|---|
| ADR-0012 | 双响应码错误分类（通信失败一律重试，业务失败不重试） | §4.4 | payment-service 错误分类 |
| ADR-0013 | 重试不落库、请求内联重试（3 次退避 1s/2s/4s） | §4.4 | payment-service |
| ADR-0014 | 同 attempt 重放（重试复用同一 attempt，靠幂等吸收） | §4.4 | payment-service |
| ADR-0015 | UNKNOWN 真实收敛时长度量（entered_unknown_at） | §5.1 / §5.3 | payment-service |
| 超时口径 | 出站 RPC 1s / 对外 HTTP 1.5s（全服务统一） | §3.4 / §4.4 | 全服务 |
| ADR-0038 | 演示形态 → **Superseded by ADR-0048** | §6 | — |
| ADR-0048 | 新增 `mock-channel-web` 收银台组件（payUrl 跳转链路） | §3.2 / §6 | mock-channel-web (8091) |
| ADR-0049 | Mock 渠道场景配置化（`payment.channel.mock-scenario`） | §4.3 | payment-service |
| ADR-0050 | 对账演示账单生成 CSV 写入 `target/classes` | §4.3.5 | reconciliation-service |
| ADR-0051 | 演示脚本纪律（只编排不伪造、断言失败即非零退出） | §6 | deployment/ |

## 3. 仅纪录于 ADR 文件（正文未展开，按惯例以 ADR 为权威）

ADR-0001（Spring Cloud 架构）、ADR-0003~0007（支付可靠性集合）、ADR-0008~0011（Ledger 设计集合）、ADR-0016~0017（退款模型/编排）、ADR-0022~0023（结算调整项/闸门）、ADR-0029~0033（分布式演进）、ADR-0034~0037（内部令牌，已不做）、ADR-0047（退款金额校验口径）、ADR-0054~0058（核心资金正确性 / 入口与基础设施 / 性能基线，见 `docs/adr/0016~0018-*.md`）、**ADR-0055~0057（入口与基础设施，见 [0055-entry-and-infra-decisions.md](0055-entry-and-infra-decisions.md)；0057 已被 ADR-0070 Superseded）**、**ADR-0059（启用 Nacos，见 [0059-enable-nacos.md](0059-enable-nacos.md)）**、**ADR-0060（Redis Lettuce 连接池，见 [0060-redis-lettuce-pool.md](0060-redis-lettuce-pool.md)）**、**ADR-0061（可观测性补全，见 [0061-observability-panel-fix.md](0061-observability-panel-fix.md)）**、**ADR-0062（业务单号雪花，见 [0062-business-no-snowflake.md](0062-business-no-snowflake.md)）**、**ADR-0063（跨系统关联用业务单号，见 [0063-cross-service-reference-by-business-no.md](0063-cross-service-reference-by-business-no.md)）**、**ADR-0064（一交易多支付单，见 [0064-multi-payment-per-transaction.md](0064-multi-payment-per-transaction.md)；§决策#4 已被 ADR-0054(B) Superseded）**、**ADR-0065~0069（会计四核对 / schema 规范化 / order 驱动退款 / 访问日志 / 全链路测试，均已 Implemented）**、**ADR-0071（用户支付限额，见 [0071-user-payment-limit.md](0071-user-payment-limit.md)，🟢 Implemented）**。

**stage-05 设计轮（2026-09-21）**：ADR-0077~0079（spec 031）、ADR-0080（032）、ADR-0081（033）、ADR-0082（034）**已由负责人在 2026-09-21 裁决 Accepted 并实现落地**，其 L0 落点已上移至 §1（见上表）。**仅 ADR-0083（spec 035）仍为 🟡 Proposed**——`technical-solution.md` 与 `systems/*.md` **未体现**其任何 `【目标】`；这是刻意状态：035 为 design-only、未裁决、未实现，故**不得**进入 L0 当前系统事实（见 [design-summary §15](../specs/stage-05-channel-and-finance-deepening/design-summary.md)）。裁决 Accepted 并实现后，再按本节惯例上移到 §1 并补落点。

> 迁移说明：本索引原为 `technical-solution.md` §9，2026-09-14 文档治理时移出，使技术方案只描述系统现状。
