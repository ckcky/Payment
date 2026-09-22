# Technical Solution 规范（Technical Solution Standard）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **适用对象**：`docs/architecture/technical-solution.md`（全仓唯一一份）。

---

## 1. 定位

Technical Solution = **Current System Architecture Snapshot**（当前系统架构快照）。

它回答：**整个系统总体怎么组织？** 是全局视角，**只描述现状**。

它**不是**：
- 不是设计提案（未生效的设计属 ADR / Spec 的 Proposed 状态）；
- 不是单服务手册（属 System Design）；
- 不是 Feature 设计书（属 Spec / Plan）。

---

## 2. 固定骨架（12 章）

章节编号与标题 MUST 稳定；可在章内裁剪子节，但**不得删除章**。缺失的章必须写明「不适用 + 理由」。

| # | 章节 | 内容 | 禁止 |
|---|---|---|---|
| 1 | **Context & Goals** | 项目定位、业务背景、目标与成功判据 | 长篇业务愿景 |
| 2 | **Constraints** | 必须遵守的边界（技术选型约束、合规、禁用清单、已决例外） | 具体实现细节 |
| 3 | **System Context** | 本系统与外部参与者 / 外部系统的关系（C4 L1 摘要 + 链接） | 组件级细节 |
| 4 | **Architecture** | 服务清单、分层、容器视图、模块结构（C4 L2 摘要 + 链接） | 单服务内部类结构 |
| 5 | **Domain / Service Boundaries** | 限界上下文、职责划分、数据所有权、依赖方向 | 单服务表字段清单 |
| 6 | **Runtime Views** | 主要运行时链路（主链、退款、对账结算、异步通知）的**高层**时序 | 单 Feature 的完整设计、20 步以上逐行时序 |
| 7 | **Consistency & Reliability** | 一致性口径、幂等、超时 / 重试 / 未知状态、失败恢复原则 | 单服务重试参数逐条（放 System Design） |
| 8 | **Cross-cutting Concerns** | 安全、可观测、配置、错误处理、日志 | 脱敏 / 风控等「本期不做」写成已实现 |
| 9 | **Deployment** | 部署模式、进程拓扑、端口、中间件、启动方式（摘要 + 链接） | 逐条 compose 配置 |
| 10 | **Quality Requirements** | 性能 / 容量 / 可用性目标与已实测基线 | 未实测的目标写成现状 |
| 11 | **Risks / Known Gaps** | 已知缺口、显式接受的风险、技术债 | — |
| 12 | **Verification** | 如何验证系统正确性（测试分层、门禁、审计路径） | 单个测试类清单 |

---

## 3. MUST NOT 清单（越界即 FAIL）

Technical Solution **禁止**包含：

1. **详细字段定义** —— 表字段清单、列序、索引定义（属 System Design）。
2. **详细 DTO** —— 请求 / 响应体字段逐个列出（属 System Design 的 API 契约节）。
3. **大量接口定义** —— 逐条 `/internal/**` 端点 + 幂等键格式（属 System Design）。
4. **单个 Service 的内部实现** —— 类级调用链、内部组件名、方法级流程（属 System Design）。
5. **单个 Feature 的完整设计** —— FR 清单、验收标准、任务拆分（属 Spec / Plan / Tasks）。
6. **单个 Feature 的详细状态机** —— 状态枚举与全部迁移条件（属 System Design 或 Spec）。
7. **重复 System Design 内容** —— 与 `systems/*.md` 高度重复的表格 / 段落。

这些内容 MUST 下沉到 System Design / Spec / ADR。

---

## 4. 下沉与引用规则

Technical Solution 只保留 **摘要 + 链接**：

```markdown
（✅ 正确）
支付服务的两层结构（payment 支付层 / channelAttempt 渠道层）见
[../architecture/systems/payment-service.md](../architecture/systems/payment-service.md) §1；本节只列服务级职责。

（❌ 错误）
payment_attempts 表字段：id, payment_no, attempt_type, channel_code, extra_json, ...
```

判断标准：**如果这段内容在 System Design 里也存在，它就应该只在 System Design 存在，此处留一行链接。**

已有越界内容不要求一次性重写；按「先加链接、后删重复」的节奏在触及该章时顺带收敛。

---

## 5. 写作要求

1. **只写现状**：动词用现在时。禁止「将引入」「计划」「待实现」出现在正文（未生效设计要么移除，要么以「链接 + `Status: Proposed` 标注」出现）。
2. **口径唯一**：服务数量等全局数字 MUST 与 System Design 一致。**统一口径**：
   - 描述「业务服务」用 **9 个核心业务服务**；
   - 描述「运行进程」用 **10 个进程**（= 9 个业务服务 + 1 个 Demo / 演示进程）。
   - Demo 进程（`mock-channel-web`）**不是业务服务**，不得计入「N 个服务」。
3. **不写实现细节**：类名 / 方法名只在解释架构挂点时出现，且不超过一句。
4. **每章开头一句话摘要**，详细内容链接出去。
5. **变更即同步**：任何服务边界 / 进程拓扑 / 全局契约变化，本文件 MUST 同批更新。

---

## 6. 更新时机

- 新增 / 移除服务或运行进程；
- 服务边界、数据所有权、部署模式变化；
- 全局一致性口径、安全口径变化；
- 任一 System Design 的核心事实变化后，回检本文件是否仍一致。

---

## 7. Review 要点（详见 [documentation-review.md](documentation-review.md)）

- [ ] 12 章齐全（缺章有理由）
- [ ] 无 §3 的 7 类越界内容
- [ ] 无「未来式」表述
- [ ] 服务数口径为 9 / 10 的正确用法
- [ ] 引用的 System Design / ADR 链接可达

---

## 8. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [system-design-standard.md](system-design-standard.md)
- [../templates/technical-solution.md](../templates/technical-solution.md)
- [../architecture/technical-solution.md](../architecture/technical-solution.md)
