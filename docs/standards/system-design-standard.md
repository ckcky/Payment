# System Design 规范（System Design Standard）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **适用对象**：`docs/architecture/systems/*.md`（每服务一篇）。

---

## 1. 定位

System Design = **一个服务 / 子系统的实现事实**。

它回答：**某个具体系统 / Service 怎么正确运行？**

范围：一个服务。可含**领域专项扩展**（见 §3）。不含全局架构论证（属 ADR）与 Feature 行为契约（属 Spec）。

---

## 2. 核心骨架（15 章，MUST 保持）

所有服务文档 MUST 具备以下 15 章，编号与标题稳定：

| # | 章节 | 内容 |
|---|---|---|
| 1 | **Responsibility** | 这个服务负责什么（含关键职责表） |
| 2 | **Non-Responsibility** | 这个服务**明确不负责**什么（防止边界侵蚀） |
| 3 | **Context** | 与上下游服务/外部系统的关系、依赖方向 |
| 4 | **Domain Model** | 聚合、实体、值对象、关键不变量、表结构 |
| 5 | **State Machine** | 状态枚举 + 全部迁移条件 + 终态吸收口径 |
| 6 | **API / Event Contract** | 对外 / 内部端点契约、事件契约、幂等键格式 |
| 7 | **Runtime Flow** | 关键链路时序（下单 / 支付 / 退款 / 收敛…） |
| 8 | **Failure / Recovery** | 失败分类、恢复路径、补偿、人工介入点 |
| 9 | **Idempotency / Consistency / Concurrency** | 幂等实现、事务边界、并发竞争与守卫 |
| 10 | **Data / Storage** | 存储选型、缓存、索引、数据生命周期 |
| 11 | **Observability / Security** | 指标 / 日志 / trace / 告警；鉴权与安全口径 |
| 12 | **Deployment** | 端口、配置、启动方式、依赖中间件 |
| 13 | **Testing / Verification** | 测试分层、覆盖重点、验证方式 |
| 14 | **Related Documents** | 相关 Spec / ADR / 图 / 其他服务文档 |
| 15 | **Known Gaps** | 已知缺口、显式接受的风险 |

> **不允许**：缺章、章号跳跃、子节错位（如 `### 1.4` 排在 `### 1.3` 前、`### 5.5` 落在 §6 之后）。

---

## 3. 允许的领域专项扩展

在 15 章骨架**之上**，按领域追加专项章节。示例：

| 服务 | 允许的专项扩展 |
|---|---|
| ledger-service | Posting、Accounting Event、Debit / Credit、Posting Rules、科目 Definition/Instance、期间与关账 |
| settlement-service | Settlement Gate、Batch Lifecycle、Adjustment、净额公式 |
| reconciliation-service | Statement Import、匹配键与降级策略、差异生命周期 |
| payment-service | 两层结构（payment 层 / channel 层）、渠道路由、渠道模态、凭证模型 |
| order-service | 交易与订单双层模型、下单幂等、库存预占与释放 |
| entitlement / fulfillment | 授予 / 撤销、按 item 粒度履约 |

扩展章节 MUST：
1. 排在核心 15 章**之后**（统一自 **§16** 起连续编号），**不得**打乱核心 15 章的章号与顺序；
2. 在 §14 Related Documents 中登记对应 ADR。

---

## 4. 章号迁移映射（2026-09-22 已完成）

现有 9 篇服务文档原为「6 章结构」，2026-09-22 文档治理已将其**内容逐字保留、仅重排章号**至上述 15 章骨架（ledger-service 由原 9 章重排）。旧→新对照如下，用于解析历史文档（ADR / Spec / 审计记录）中的旧章号引用：

| 旧章号 | 旧标题 | 新章号 |
|---|---|---|
| 1.1（负责行） | 职责边界 | §1 |
| 1.1（不负责行） | 职责边界 | §2 |
| 1.2 | 硬约束（Constitution / ADR） | §3.2 |
| 1.3 | 技术指标 | §1.1 |
| 1.4（settlement） | Audit Gate | §3.3 |
| 2.1 | 聚合与值对象 | §4.1 |
| 2.2 | 状态机 | §5 |
| 2.3 | 表结构与索引策略 | §4.2 |
| 3.x | 接口详细定义 | §6.x |
| 4.x | 关键流程链路剖析 | §7.x |
| 5.1 | 存储读写策略 | §10.1 |
| 5.2 | 幂等性方案 | §9.1 |
| 5.3 | 分布式事务方案 | §9.2 |
| 5.4 | 异常与边界场景 | §8.1 |
| 5.5（entitlement） | 矛盾点与成熟度汇总 | §15.1 |
| 6.1 / 6.2 / 6.3 | 运行态配置 / 环境变量 / 启动依赖 | §12.1 / §12.2 / §12.3 |
| 6.4 | 埋点与日志键 | §11.1 |
| 7（payment） | 回调与出站安全 | §16 |
| 8 / 9 / 10（payment） | 退款域 / 支付限额域 / 可靠性加固 | §17 / §18 / §19 |
| 7 / 8 / 9（reconciliation） | 审计四核对 / 账单实账化 / 可靠性加固 | §16 / §17 / §18 |
| 8 / 9（order） | 超时与库存释放 / 搁浅退款重放 | §16 / §17 |
| 5（ledger） | 六类事件的记账规则 | §16 |
| 附（merchant） | 与 roadmap / technical-solution 的状态矛盾 | §15.1 |

- **§16 及以后 = 领域专项扩展**（见 §3），不得插回核心 15 章之间、不得打乱核心章号。
- **历史文档中的旧章号引用不加回改**：ADR 为历史决策记录，其指向 System Design 的章号是**撰写时**的快照；定位时按本表换算，或直接按标题检索。新写的文档 MUST 使用新章号。

## 5. 写作要求

1. **只写现状**，不起草未来设计。
2. **口径一致**：服务数量、端口、schema 名、状态枚举 MUST 与实现及 Technical Solution 一致。
3. **数据所有权显式**：本服务写哪些表、只读哪些表、经什么端口写入，MUST 写清。
4. **禁止跨服务直改**：文档中出现的跨服务读写 MUST 是 API/RPC/事件，不得是直接 SQL。
5. **状态机完整**：枚举 + 迁移矩阵 + 终态吸收语义；「可合法不一致」的口径 MUST 显式说明。
6. **不写其他服务内部实现**。
7. **图随章节走（2026-09-22 补充）**：
   - **§4 领域模型 MUST 含 ER 图**：用行内 Mermaid `erDiagram` 写在 §4 内，标出**基数**与**唯一键**；只画本服务拥有的表（跨服务关系属 `../diagrams/*.puml`）。实体超过 ~10 个时按聚合拆多张子图。
   - 其余章节的图按需用行内 Mermaid：§5 状态图（`stateDiagram-v2`）、§7 时序图（`sequenceDiagram`）。
   - 行内图 MUST 与正文表格一致（图是表格的视图，不是第二套事实）；**不得**放进 `docs/architecture/diagrams/`。
   - 每节 MUST 说明「写什么 / 需要什么」——完整逐节指引见 [../templates/system-design.md](../templates/system-design.md)。

---

## 6. 更新时机

- 服务的数据模型 / 状态机 / 契约 / 端口 / 部署变化；
- 该服务相关 ADR 状态变化（Accepted / Superseded）后回检；
- 新增 Feature 落地后，回写该 Feature 引入的服务级事实。

---

## 7. Review 要点（详见 [documentation-review.md](documentation-review.md)）

- [ ] 15 章齐全且顺序正确
- [ ] §2 Non-Responsibility 非空
- [ ] §14 列出相关 Spec / ADR / 图
- [ ] 与 Technical Solution 无重复段落（重复处已改为链接）
- [ ] 状态机枚举与迁移完整
- [ ] 数据所有权清晰、无跨服务直改描述
- [ ] 内部链接可达

---

## 8. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [technical-solution-standard.md](technical-solution-standard.md)
- [../templates/system-design.md](../templates/system-design.md)
- [../architecture/systems/](../architecture/systems/)
