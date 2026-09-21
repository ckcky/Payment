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
| ADR-0039 / ADR-0040 | 下单入口幂等键（Redis 唯一存储、409+Retry-After） | order-service.md §4.3.1 |
| ADR-0041 / ADR-0042 / ADR-0043 | 库存域归属 catalog / 三段式扣减 / ZSet 超时释放 | catalog-service.md、order-service.md |
| ADR-0044 / ADR-0045 / ADR-0046 | Redis 引入论证 / 用途边界（非数据源）/ 固定窗口限流 | technical-solution §2.3、§3.5、§5 |
| ADR-0047 | 退款金额校验口径（累计不超付） | technical-solution §4.3.3 |
| ADR-0048 ~ ADR-0051 | 演示形态：收银台 / 场景配置化 / 演示账单 / 脚本纪律 | technical-solution §3.2、§6、§4.3.5 |
| ADR-0052 | 渠道回调验签接入（回退至 ADR-0025 空实现） | technical-solution §5.2 |
| ADR-0053 | 013/014 代码超前 roadmap 落地处置 | technical-solution §7 |
| ADR-0070 | 本地全栈容器化：Compose 编排 10 服务 + 双轨并存（宿主/容器）+ 宿主打 jar 镜像只 COPY | technical-solution §6（实施与部署策略之「双模式运行」）、deployment/README.md、runbook（🟢 Accepted / Implemented，2026-09-15；Supersedes ADR-0057） |
| ADR-0072 | payment-service 两层结构（payment 支付层 / channelAttempt 渠道层）：表归属与写入口分离 | technical-solution §3.1.1（领域模型 + 两层说明）、§4.2（基数修订）、§4.3（链路时序）；payment-service.md §1.1、§2.1.1、§4.3（✅ Accepted → Implemented） |
| ADR-0073 | 支付渠道路由：注册表 + 规则化确定性选路；前向选路与反向按记录解析严格分离 | payment-service.md §2.1.1、§3.2（channelCode 可选 + `ChannelRegistry`/`ChannelRouter`；✅ Accepted → Implemented） |
| ADR-0074 | Redis 事务消息通道：Streams + 半消息协议（prepare → 本地事务 → commit/rollback → 5s 回查真相表）承载跨服务异步解耦；混合拓扑（事实类广播 / 动作类点对点）；traceId 跨异步边界连续；trace 消费组落 `order_event_log` | technical-solution §1.2/§2.3/§3.4 仅作 Active Proposal 导航；当前服务契约不依赖其事件拓扑（🟡 Proposed，待实现） |

| ADR-0075 | 聚合支付统一渠道契约：四组结构化字段（`Goods` / `CallbackUrls` / `Payer` / `PaymentScene`）+ `channelExtra` 扩展袋 + 类型化 `PayCredential`（6 种 Kind）+ `ChannelResult.credential` 与 `accepted(ref, reason, credential)`；金额保持扁平；保留兼容构造器与 `default` 方法保证零中断编译；凭证不落库 | payment-service.md **§3.11 已补（2026-09-19 Phase 0 收口）**：✅ 悬空锚点 `#311-渠道内部契约spec-030--adr-0075` **现已可达**（补 `### 3.11 渠道内部契约`）；✅ 两个重复的 `### 3.10` 已消除（渠道路由＝3.10、事件通道＝**3.12**）。本节当前承载**契约条款**，「实现实况」表待 spec 030 实现完成后由 T138 补齐（🟢 Accepted，2026-09-19 由 Proposed 升级，**待实现**） |
| ADR-0076 | 全链路染色分流（mock / 沙箱）+ 渠道模态落库 + 支付宝沙箱接入：`X-Dye-Tag` 三段式透传（common-core，入站读与出站写同批）+ `payment_attempts` 模态落库与反向三路径还原（**落库载体已按 H2 修订**：~~专用列 `channel_mode`~~ → 通用 JSON 列 **`extra_json`** 的 **`channelMode`** 键，**TEXT 存 JSON**，语义不变）+ 单 Adapter 双模态（`AlipayGateway` 收口 SDK）+ `POST /internal/channels/alipay/notify`（RSA2，纯文本 `success`） | payment-service.md **§2.1 / §2.3 已同步 H2 载体**（`channelMode` 键 + fail-safe 读法 + 迁移脚本 `030-payment-attempt-extra-json.sql`，2026-09-19 Phase 0）；§3.2 / §3.11 **待实现**；technical-solution §4.4 已写入「**渠道事实 / 平台事实可合法不一致**」口径（终态吸收的解释，2026-09-19 Phase 0），§2.4 / §3.5 / §4.3 / §5.2 **待实现**；`deployment/schema/03-payment-schema.sql` + 测试 H2 schema + 增量迁移 **`030-payment-attempt-extra-json.sql`**（~~`030-payment-attempt-channel-mode.sql`~~）**待实现**（🟢 Accepted，2026-09-19 由 Proposed 升级，**待实现**；H3 SDK 依赖**已裁决引入**；H2 载体**已裁决为通用 JSON 列**） |

| ADR-0077~0079 | Ledger / Accounting 地基（spec 031）：Accounting Event + Posting Rule、两级科目、余额投影/期间/待记账台账 | technical-solution §3（ledger-service 行「031 起记账决策权归账本」）、systems/ledger-service.md（§9 验收）（🟢 Accepted 2026-09-21，已实现） |
| ADR-0080 | 对账真实化（spec 032）：账单导入/归一化、类型化匹配、差异台账、渠道资金事实唯一生产者 | systems/reconciliation-service.md §8（Feature 032 节）（🟢 Accepted 2026-09-21，已实现） |
| ADR-0081 | 测试载体升级（spec 033）：Testcontainers 真库、迁移可重放六条、schema 双路径重放门禁 | docs/guides/engineering-standards.md §4/§11/§12 + CI `schema-replay`/`real-db` job（🟢 Accepted 2026-09-21，已实现） |
| ADR-0082 | 失败恢复归属与补偿边界（spec 034）：同事务收口、pending_postings 台账、有界退避与 ABANDONED、DLQ 管理 | technical-solution §5.1.1（超时三档）、systems/reconciliation-service.md §9（🟢 Accepted 2026-09-21，已实现） |
| ADR-0083 | 观测基线（spec 035）：SLO Recording Rule + 错误预算、高基数政策 HC-1~4、密钥/报文不入日志、保留自研 trace | technical-solution §5.3、docs/guides/engineering-standards.md §7、docs/operations/runbook.md §5（指标目录+24 告警处置）、docs/operations/slo-report.md、`deployment/prometheus/rules/`（🟢 Accepted 2026-09-21/22，已实现） |

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

ADR-0001（Spring Cloud 架构）、ADR-0003~0007（支付可靠性集合）、ADR-0008~0011（Ledger 设计集合）、ADR-0016~0017（退款模型/编排）、ADR-0022~0023（结算调整项/闸门）、ADR-0029~0033（分布式演进）、ADR-0034~0037（内部令牌，已不做）、ADR-0054~0058（核心资金正确性 / 入口与基础设施 / 性能基线，见 `docs/adr/0016~0018-*.md`）。

**stage-05 设计轮（2026-09-21 登记 → 2026-09-22 全部收口）**：ADR-0077~0079（031）、ADR-0080（032）、ADR-0081（033）、ADR-0082（034）、ADR-0083（035）——七条均经负责人裁决 🟢 Accepted 并随对应 spec 实现落地，落点已按本节惯例上移至 §1（见 stage-05 五行）；原「design-only、未裁决、L0 不体现」状态解除。

> 迁移说明：本索引原为 `technical-solution.md` §9，2026-09-14 文档治理时移出，使技术方案只描述系统现状。
