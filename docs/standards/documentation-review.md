# 文档 Review 规范（Documentation Review）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **执行者**：人类开发者 + AI Agent（skill：`.claude/skills/documentation-review/`；命令：`/doc-review <file>`）。

本文件定义**统一的文档检查清单**。任何新增 / 修改的文档交付前 MUST 过本清单。

---

## 1. 执行流程

```
1. 判定文档类型（8 类之一，见 documentation-governance §2）
2. 加载对应 Standard
3. 加载对应 Template（docs/templates/）
4. 逐项执行 §2 检查
5. 输出结论
```

---

## 2. 检查清单（10 组）

### 2.1 Type（文档类型正确性）

- [ ] 文档类型归属正确（表 / 图 / 索引不冒充 Spec；Spec 不冒充 ADR）
- [ ] 路径与类型匹配（Spec 在 `docs/specs/<stage>/<feature>/`；System Design 在 `systems/`）

### 2.2 Structure（骨架 / 模板）

- [ ] 使用了对应 Template
- [ ] 必填章节**完整**且**顺序正确**
- [ ] 无章号跳跃 / 子节错位
- [ ] 头部 Status 元数据存在

### 2.3 Responsibility（职责 / 越权）

- [ ] Scope 清晰
- [ ] Responsibility 清晰
- [ ] Non-Responsibility 清晰（System Design 必填）
- [ ] **无越权描述其他系统**（不写他服务内部实现）
- [ ] 未违反职责重叠禁令（Technical Solution 不复制 System Design 字段；Spec 不重述 ADR 备选方案；Tasks 不定业务规则）

### 2.4 Current Facts（与当前事实一致）

- [ ] 与 `technical-solution.md` 一致
- [ ] 与 `systems/*.md` 一致
- [ ] 与 Spec 一致
- [ ] 与 ADR 一致
- [ ] **未把 Proposed 写成 Current**（Current 文档不出现未生效设计）
- [ ] 服务数口径正确（业务服务 = **9**；运行进程 = **10**；Demo 进程不计入业务服务）

### 2.5 Lifecycle（状态统一）

- [ ] Status 使用了允许词表（Spec 用 8 态；ADR 用 6 态；归档用 `archived`/`superseded`）
- [ ] **未使用自由文本状态**（~~设计完成~~ / ~~待评审~~ / ~~提案中~~ / ~~已完成~~）
- [ ] 四件套之间状态一致（spec / plan / tasks / acceptance）
- [ ] 与 `docs/specs/README.md`、`roadmap.md` 一致
- [ ] 正文自述「已实现 / 已合入」时头部**不是** `Draft` / `Proposed`

### 2.6 References（引用可达）

- [ ] Spec 引用存在
- [ ] ADR 引用存在（状态与索引一致）
- [ ] System Design 引用存在
- [ ] Diagram 引用存在
- [ ] **内部链接有效**（相对深度正确：feature 目录 → `docs/adr/` = `../../../adr/`）
- [ ] 引用 ADR-0054 时已用「文件名 + 标题」消歧

### 2.7 Architecture（架构清晰）

- [ ] 边界清晰（服务边界 / 领域边界）
- [ ] 数据所有权清晰（谁写哪些表）
- [ ] 同步 / 异步边界清晰
- [ ] 状态转换清晰（枚举 + 迁移条件）

### 2.8 Reliability（可靠性）

- [ ] **Timeout**：外部调用有超时口径
- [ ] **Retry**：重试有退避与上限
- [ ] **Idempotency**：资金入口有幂等键
- [ ] **Failure**：失败分类明确
- [ ] **Recovery**：恢复 / 补偿路径明确

### 2.9 Consistency（一致性）

- [ ] **Transaction**：事务边界只覆盖单服务本地事务
- [ ] **Eventual Consistency**：跨服务走 RPC / 事件 + 幂等，未用 2PC/XA
- [ ] **State Transition**：无非法跳转、无散落直改 status

### 2.10 Operations（运维）

- [ ] **Logs**：结构化日志 + 关联 ID
- [ ] **Metrics**：关键业务计数
- [ ] **Trace**：traceId 透传
- [ ] **Health**：健康检查端点
- [ ] **Runbook**：有对应处置步骤（涉及生产风险时）

---

## 3. 输出格式

```markdown
## Review: <file>

**Type**: <文档类型>
**Standard**: <加载的标准>
**Template**: <加载的模板>
**Status**: PASS | WARN | FAIL

### Summary
（一句话结论）

### Violations
| # | 检查组 | 问题 | 严重度 | 证据（行号） |

### Missing Sections
（缺失章节清单）

### Drift
（与 Current Facts 的漂移项，含对侧文件:行号）

### Broken References
（不可达链接清单）

### Final Status
PASS / WARN / FAIL + 一句话理由
```

---

## 4. 结论判定

| 结论 | 判定 |
|---|---|
| **PASS** | 无 P0 / P1 问题；P2 问题可为 0 |
| **WARN** | 无 P0；存在 P1 或 P2 问题，可先合并但需登记跟进 |
| **FAIL** | 存在任一 **P0** 问题 |

### 4.1 严重度

| 级别 | 定义 | 例 |
|---|---|---|
| **P0** | 事实错误 / 违反硬规则 / 会导致误导 | Current 文档把 Proposed 写成现状；Append/Accepted ADR 被直接改写；缺必填骨架导致文档不可用；引用不存在的 ADR |
| **P1** | 结构或一致性缺陷 | 缺 1~2 个核心章节；状态与索引不一致；重复另一个文档的内容未改为链接 |
| **P2** | 措辞 / 格式 / 可读性 | 标题措辞不统一；链接深度可简化；表格对齐 |

---

## 5. 自动化检查项（机械可执行）

**首选：跑仓库校验脚本**（16 组只读检查，退出码 0 = 全绿；覆盖链接可达性、Spec 路径与状态、ADR 索引与编号、四件套、宪法版本、服务口径、图表、System Design 章号、模板、AI 体系引用、**ADR 交叉引用编号一致性**）：

```bash
python deployment/docs-lint.py
```

脚本不可用时，以下命令可用于**单项抽查**（任一非空结果即视为对应检查组的命中项）：

```bash
# 1. markdown 相对链接目标存在性（抽查）
grep -rhoE '\]\([^)]*\.md[^)]*\)' docs | sed 's/](//;s/)//' | sort -u

# 2. ADR 锚点重复（已知例外：ADR-0054，见 adr-standard §3.1）
grep -rhoE '<a id="adr-[0-9]{4}"' docs/adr | sort | uniq -d

# 3. Spec 路径口径（不应出现无 stage 写法）
grep -rn "docs/specs/<feature>/" --include='*.md' docs .specify AGENTS.md README.md

# 4. 旧版本号残留（应为 2.4.0）
grep -rn "v2\.3\.0" --include='*.md' docs AGENTS.md .specify

# 5. 自由文本状态残留
grep -rnE "Status.*(设计完成|待评审|提案中|设计中|部分完成)" --include='*.md' docs

# 6. 过时服务名
grep -rn "refund-service" --include='*.md' docs AGENTS.md
```

任一非空结果即视为对应检查组的命中项。

---

## 6. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [technical-solution-standard.md](technical-solution-standard.md)
- [system-design-standard.md](system-design-standard.md)
- [spec-standard.md](spec-standard.md)
- [adr-standard.md](adr-standard.md)
- [diagram-standard.md](diagram-standard.md)
- [../templates/](../templates/)
