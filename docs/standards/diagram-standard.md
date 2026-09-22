# 架构图规范（Architecture Diagram Standard）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **适用对象**：`docs/architecture/diagrams/*.puml` 与同名 `.svg`。

---

## 1. 定位与工具

架构图采用 **C4 Model 思维**，但**不引入新的工具链**：

- **继续使用仓库已有的 PlantUML**，优先使用 **C4-PlantUML 风格**表达。
- **不引入** draw.io / 其他**第三方图形框架与图形服务**；**不引入 PlantUML 的新渲染链路**。
- 渲染器：`plantuml.jar`（本地工具，放 `deployment/output/`，不进仓库、不进 Maven 依赖）。

### 1.0 两类图的分工（避免误用）

| 类别 | 载体 | 语法 | 是否受本规范管辖 |
|---|---|---|---|
| **跨服务架构图** | `docs/architecture/diagrams/NN-<slug>.{puml,svg}` | **PlantUML**（C4 分层） | ✅ 本规范**全部条款**管辖 |
| **服务内 / 文档内行内示意** | L0 文档正文里的 ``` ```mermaid ``` 代码块（ER 图、时序图、流程图、状态图） | **Mermaid**（仓库既有现状：`technical-solution.md` 与 7/9 篇 `systems/*.md` 已在用） | ⚠️ 仅受 §5 视觉规范约束；**不替代**架构图，MUST NOT 放进 `diagrams/` |

- 行内示意是**既有事实**，允许保留并继续使用 Mermaid；**不再引入第三种图形语法**（禁 D2 / Graphviz 裸 DOT / 图片贴图）。
- 行内示意的用途边界：**服务内部**的模型、时序、状态、流程。**跨服务**关系 MUST 落 `diagrams/*.puml`。
- C4 的 L1/L2/L3/Dynamic/Deployment 五类视图 **只由 `diagrams/` 下的 PlantUML 表达**，行内示意不承担该职责。

### 1.1 SOURCE OF TRUTH 规则（硬规则）

| 角色 | 文件 | 规则 |
|---|---|---|
| **SOURCE OF TRUTH** | `NN-<slug>.puml` | 唯一权威。改图 MUST 改 `.puml`。 |
| **GENERATED ARTIFACT** | `NN-<slug>.svg` | 由 `.puml` 渲染产生，**禁止手工编辑**。 |

- 渲染命令 MUST 指定输出目录（**不得**让 PlantUML 覆盖展示图）：

  ```bash
  java -jar deployment/output/tools/plantuml.jar -tsvg -o <目标目录> docs/architecture/diagrams/NN-<slug>.puml
  ```

- **禁止**维持「`.puml` 像源码、`.svg` 却是另一张图」的隐式关系。
- 每张 `.puml` 文件头 MUST 写：
  1. 图的 C4 层级；
  2. 渲染命令；
  3. Output 目录约定警告。

---

## 2. 元素必填属性

每张图上的每个元素与关系 MUST 明确以下六项（PlantUML 中体现为节点别名、标签与技术标注）：

| 属性 | 说明 | 例 |
|---|---|---|
| **Element Type** | Person / Container / Component / Database / External System | `Container` |
| **Name** | 元素名 | `payment-service` |
| **Responsibility** | 一句话职责 | `支付编排 + 渠道适配` |
| **Technology** | 技术标注 | `Spring Boot / Java 21` |
| **Relationship Direction** | 有方向的连线 | `A --> B` |
| **Relationship Label** | 动词 + 技术协议 | `creates payment\nREST / HTTP` |

### 2.1 关系标签规范

- MUST 有方向。
- MUST 是「**动词短语 + 技术协议**」：
  - ✅ `creates payment` / `REST / HTTP`
  - ✅ `publishes order.paid` / `Redis Streams`
  - ✅ `reads confirmed facts` / `Feign RPC`
- ❌ `A → B`（无标签）
- ❌ `调用`（无协议、无动宾）

---

## 3. 目录与命名

`docs/architecture/diagrams/` 固定为 8 张图，命名 MUST 为 `NN-<kebab-slug>`，每张图**成对**保留 `.puml` + `.svg`：

| 编号 | 文件 | C4 层级 | 回答 |
|---|---|---|---|
| 01 | `01-system-context` | **L1 System Context** | PaymentArch 和谁交互？ |
| 02 | `02-container-overview` | **L2 Container** | PaymentArch 内有哪些主要运行单元？ |
| 03 | `03-payment-components` | **L3 Component**（仅 payment-service） | Payment Service 内部有哪些有架构意义的组件？ |
| 04 | `04-payment-flow` | **Dynamic** | 支付主链运行时怎么协作？ |
| 05 | `05-payment-unknown-recovery` | **Dynamic** | 支付进入 UNKNOWN 后怎么收敛？ |
| 06 | `06-refund-flow` | **Dynamic** | 退款链路（含两层退款单）怎么走？ |
| 07 | `07-ledger-reconciliation-flow` | **Dynamic / Domain** | 资金闭环：记账 → 对账 → 结算怎么衔接？ |
| 08 | `08-deployment` | **Deployment** | 这些 Container 如何部署？ |

> 原三张图（`01-system-architecture` / `02-deployment-topology` / `03-funds-closed-loop`）按 C4 层级**重新归位**，不是简单换色：
> - `system-architecture` → 拆入 `01-system-context`（L1）+ `02-container-overview`（L2）
> - `deployment-topology` → `08-deployment`（Deployment）
> - `funds-closed-loop` → `07-ledger-reconciliation-flow`（Dynamic / Domain）

---

## 4. 层级边界

| 层级 | 允许的元素 | 禁止 |
|---|---|---|
| **L1 System Context** | 本系统（1 个盒子）、Person、External System | 内部服务、数据库 |
| **L2 Container** | 可独立运行单元（服务、Demo 进程、数据库、Redis、注册中心） | Controller/Service/Repository 等类 |
| **L3 Component** | 单个 Container 内有**架构意义**的组件（如支付层的编排组件、渠道层的适配族、端口） | 穷举 Controller / Service / Repository / Mapper |
| **Dynamic** | 参与某场景的元素 + 带序号的消息 | 无关元素 |
| **Deployment** | 节点、容器、卷、网络、镜像 | 业务逻辑 |

**L3 只对真正需要深入解释的 Container 绘制**（本项目为 `payment-service`）。

**禁止**：直接把 Controller / Service / Repository / Mapper 全部当 Component。

---

## 5. 视觉规范

### 5.1 配色

| 元素 | 填充 | 边框 |
|---|---|---|
| 背景 | `#FFFFFF` 白色 | — |
| Person | `#EDEDED` 浅灰 | `#9E9E9E` |
| Internal Container | `#DCE6F2` 蓝灰 | `#5B7DA8` |
| External System | `#E8E8E8` 灰色 | `#A0A0A0` |
| Database | `#D7E9F5`（**标准 Database 形状**） | `#5B7DA8` |

### 5.2 连线颜色（语义色）

| 语义 | 颜色 |
|---|---|
| **Primary Flow**（主链） | `#2F6FBF` Blue |
| **Success**（成功结果） | `#2E8B57` Green |
| **Unknown**（未知状态） | `#E8952F` Orange |
| **Failure**（失败 / 异常） | `#C0392B` Red |

### 5.3 图形

- 边框：`1px`。
- 圆角：`8~12px`。
- 字体：统一项目文档字体（`Microsoft YaHei`），字号 ≥ 11。
- 阴影：关闭（`shadowing false`）。
- 布局：`!pragma layout smetana`（无 Graphviz 环境的回退），或已装 Graphviz 时使用 `dot`。

### 5.4 Legend（强制）

**每张图 MUST 包含 Legend**，说明元素类型与连线语义色。

---

## 6. 禁止清单

- ❌ **巨型线团**：节点过多、连线交叉成网。
- ❌ **混合 Context / Container / Component**：一张图不得同时出现三个层级的元素。
- ❌ **无标签箭头**。
- ❌ **纯技术类名图**（把类名当架构元素堆砌）。
- ❌ 为了「完整」画几十个节点。
- ❌ 手工编辑 `.svg`。
- ❌ 新增第 9 张图而不更新本规范与目录表。

---

## 7. 变更流程

1. 改 `.puml`（SOURCE OF TRUTH）。
2. 用 §1.1 的命令渲染到目标目录。
3. 把渲染产物覆盖到 `docs/architecture/diagrams/` 下同名 `.svg`。
4. 回检引用该图的文档（`technical-solution.md`、`systems/*.md`、Spec）是否需要同步。
5. 图标题在 `.puml` 与 `.svg` 中 MUST 一致（渲染保证）。

---

## 8. Review 要点（详见 [documentation-review.md](documentation-review.md)）

- [ ] 图有明确 C4 层级，且未混层
- [ ] 元素六属性齐全
- [ ] 关系标签为「动词 + 技术协议」
- [ ] 有 Legend
- [ ] `.puml` 与 `.svg` 成对且标题一致
- [ ] 配色符合 §5
- [ ] 引用该图的文档链接可达

---

## 9. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [technical-solution-standard.md](technical-solution-standard.md)
- [system-design-standard.md](system-design-standard.md)
- [../architecture/diagrams/](../architecture/diagrams/)
