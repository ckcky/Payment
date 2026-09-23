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

- 渲染命令（在**仓库根目录**执行；`-o` 是相对**输入文件所在目录**解析的，写 `-o docs/architecture/diagrams`
  会在 `diagrams/` 下再套一层同名目录 —— 实测踩过，故**不写 `-o`**，让产物落在 `.puml` 同目录）：

  ```bash
  java -jar deployment/output/tools/plantuml.jar -tsvg -charset UTF-8 docs/architecture/diagrams/NN-<slug>.puml
  ```

- 渲染前 MUST 确认布局引擎：`java -jar deployment/output/tools/plantuml.jar -testdot`
  应输出 `Dot version: dot - graphviz version …` 且 `Installation seems OK`。

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
| **Relationship Direction** | 连线的方向（**仅「有向视图」必须**，见 §2.1） | `A --> B` |
| **Relationship Label** | 关系标签（动词 + 技术协议 / 关系性质） | `creates payment\nREST / HTTP` |

### 2.1 连线规则（**先判视图类型，再决定画不画连线**）

图的**连线表达力**分三档；**用错档位是"图很乱"的头号原因**（2026-09-23 重构的教训）。

| 档 | 适用视图 | 连线规则 | 本仓库实例 |
|---|---|---|---|
| **① 无连线** | **结构视图**：L2 Container / L3 Component / Deployment | **MUST NOT 画任何可见连线**。只回答「有哪些运行单元 / 组件 / 部署节点」。关系信息改由 **①正文表格 ②层序与分组** 承载 | `02` / `03` / `08` |
| **② 只连线、无箭头** | **关联视图**：领域模型 / ER | **MUST NOT 画箭头**（用 `--` 而非 `-->`）—— 箭头只表示方向，而**关联 / 组成本身无方向**。MUST 用**标签**说明关系性质（组成 / 业务单号引用 / 记账 / 只读事实） | `technical-solution.md §3.1.1` 领域模型 |
| **③ 有向箭头** | **有向视图**：L1 System Context / Dynamic（时序、流程） | MUST 用箭头 **且** MUST 有标签（动词短语 + 技术协议） | `01` / `04`~`07` |

**理由（是正确性，不是审美偏好）**：结构视图的连线画的通常是「A 调 B」这类**调用关系**，属于**动态语义** —— 放进静态图会 ①与动态图重复、更容易过时 ②在 10+ 节点上必然交叉成蜘蛛网。改造前 `02` / `03` / `08` 分别有 14 / 20 / 12 条连线，渲染后基本不可读（`08` 曾是一条 3149×557 px 的"面条"）。关联视图的连线是**结构关系**，本就无需方向，**去掉箭头即可读**。

**排版例外（允许）**：结构视图无连线时布局引擎缺少约束，**允许**用 PlantUML **隐藏连线**强制排列顺序 —— 隐藏连线渲染不可见，**不构成关系表达**：

```plantuml
' ① 组内同级节点强制排成一行
M -[hidden]right- CAT
CAT -[hidden]right- ORD
' ② 组与组之间声明自上而下的顺序
BIZ -[hidden]down- DEMOP
```

⚠️ **禁止写 `-r->` / `-down->` 之类可见的方向提示**：它会把本应并排的节点串成一条链，导致箭头绕行、甚至压住标题（`01` 曾踩此坑）。

### 2.2 关系标签规范（**仅适用于 ②③ 档**）

- ③ 档 MUST 有方向；② 档 MUST **无**方向、只靠标签表达关系性质。
- MUST 是「**动词短语 + 技术协议**」（③ 档）或「**关系性质**」（② 档）：
  - ✅ `creates payment` / `REST / HTTP`
  - ✅ `publishes order.paid` / `Redis Streams`
  - ✅ `reads confirmed facts` / `Feign RPC`
  - ✅ `业务单号引用 1:N` / `记账` / `只读事实`（② 档）
- ❌ `A → B`（无标签）
- ❌ `调用`（无协议、无动宾）

---

## 3. 目录与命名

`docs/architecture/diagrams/` 固定为 8 张图，命名 MUST 为 `NN-<kebab-slug>`，每张图**成对**保留 `.puml` + `.svg`：

| 编号 | 文件 | C4 层级 | 连线档（§2.1） | 回答 |
|---|---|---|---|---|
| 01 | `01-system-context` | **L1 System Context** | ③ 有向箭头 | PaymentArch 和谁交互？ |
| 02 | `02-container-overview` | **L2 Container** | **① 无连线** | PaymentArch 内有哪些主要运行单元？ |
| 03 | `03-payment-components` | **L3 Component**（仅 payment-service） | **① 无连线** | Payment Service 内部有哪些有架构意义的组件？ |
| 04 | `04-payment-flow` | **Dynamic** | ③ 有向箭头 | 支付主链运行时怎么协作？ |
| 05 | `05-payment-unknown-recovery` | **Dynamic** | ③ 有向箭头 | 支付进入 UNKNOWN 后怎么收敛？ |
| 06 | `06-refund-flow` | **Dynamic** | ③ 有向箭头 | 退款链路（含两层退款单）怎么走？ |
| 07 | `07-ledger-reconciliation-flow` | **Dynamic / Domain** | ③ 有向箭头 | 资金闭环：记账 → 对账 → 结算怎么衔接？ |
| 08 | `08-deployment` | **Deployment** | **① 无连线** | 这些 Container 如何部署？ |

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

**每个层级 MUST 绑定连线档位**（见 §2.1）：

| 层级 | 连线档 |
|---|---|
| L1 System Context | ③ 有向箭头 |
| L2 Container | **① 无连线** |
| L3 Component | **① 无连线** |
| Dynamic | ③ 有向箭头（带序号） |
| Deployment | **① 无连线** |
| 领域模型 / ER（行内图） | ② 只连线、无箭头 |

**L3 只对真正需要深入解释的 Container 绘制**（本项目为 `payment-service`）。

**禁止**：直接把 Controller / Service / Repository / Mapper 全部当 Component。

---

## 5. 视觉规范

### 5.1 配色

| 元素 | 填充 | 边框 |
|---|---|---|
| 背景 | `#FFFFFF` 白色 | — |
| Person / 外部角色 | `#EDEDED` 浅灰 | `#9E9E9E` |
| Internal Container / payment 支付层组件 | `#DCE6F2` 蓝灰 | `#5B7DA8` |
| channelAttempt 渠道层 / 持久化实现 / 基础设施 | `#D7E9F5` 浅蓝 | `#5B7DA8` |
| **domain 领域层（聚合根）** | `#E4E0F0` 淡紫 | `#7B6FA8` |
| 端口（interface） | `#EDEDED` 浅灰 | `#5B7DA8` |
| Demo 进程 / External System / 外部容器 | `#E8E8E8` 灰色 | `#A0A0A0` |
| Database | `#D7E9F5`（**标准 Database 形状**） | `#5B7DA8` |

### 5.2 连线颜色（语义色）

| 语义 | 颜色 |
|---|---|
| **Primary Flow**（主链） | `#2F6FBF` Blue |
| **Success**（成功结果） | `#2E8B57` Green |
| **Unknown**（未知状态） | `#E8952F` Orange |
| **Failure**（失败 / 异常） | `#C0392B` Red |

### 5.3 图形与布局

- 边框：`1px`。
- 圆角：`8~12px`。
- 字体：统一项目文档字体（`Microsoft YaHei`），字号 ≥ 11。
- 阴影：关闭（`shadowing false`）。
- **布局引擎：默认用 `dot`（Graphviz）。** 本机已装 Graphviz 2.44.1（`C://Program Files\Graphviz\bin\dot.exe`），
  PlantUML 会自动调用，`.puml` 里 **MUST NOT 写 `!pragma layout smetana`**。
  ⚠️ **这是硬条款**：`smetana` 是 PlantUML 内置的兜底布局引擎，在 10+ 节点上会排出
  「并列节点串成链」「带宽不一」「左右两列错位」等结果 —— 2026-09-23 治理时 `02`/`03`/`08`
  三张结构图正是因为文件头残留 `!pragma layout smetana` 而被强制降级，渲染结果被判「乱七八糟」。
  只有在**确认无 Graphviz 的机器**上做临时预览时，才允许加该 pragma（且 MUST NOT 提交）。
- **禁止在正文里写 Markdown 反引号**：PlantUML 不渲染 `` ` ``，会原样显示（如 `` `payments` `` 会渲染成带反引号的文本）；要强调用 `**粗体**`（PlantUML creole 支持）。
- **结构视图（档 ①）的排版配方** —— 无可见连线时引擎缺少约束，MUST 补齐隐藏连线：
  1. **组内同级**节点用 `-[hidden]right-` 串成一行 → 形成整齐的"带"；
  2. **组与组**之间用 `-[hidden]down-` 声明自上而下的顺序；
  3. **有向视图（档 ③）反而 MUST NOT 写方向提示** —— 让引擎按**节点声明顺序**分层（角色 → 本平台 → 外部系统）；写了 `-r->` 会把并排节点串成链。
- **宽高比 MUST ≤ 4:1**；偏长时改「分层 + 分带」（每层一个 package，层内节点横排），不要靠拉宽画布解决。

### 5.4 Legend（强制）

**每张图 MUST 包含 Legend**，说明**元素类型**；档 ③ 的图还需说明**连线语义色**。档 ① 的图 MUST 在 Legend 里显式写明「**本图是结构视图，不画任何连线**」并指向承载调用关系的图/表。

---

## 6. 禁止清单

- ❌ **巨型线团**：节点过多、连线交叉成网。
- ❌ **在结构视图（L2 / L3 / Deployment）上画调用连线** —— 见 §2.1 档 ①；这是"图很乱"的头号原因。
- ❌ **在关联视图（领域模型 / ER）上画箭头** —— 见 §2.1 档 ②；关联无方向，用 `--` + 标签。
- ❌ **用可见方向提示（`-r->` / `-down->`）控制布局** —— 会串链导致箭头绕行；改用 `-[hidden]-`。
- ❌ **超宽面条图**：宽高比 > 4:1 的图（如 3149×557）在文档里必然不可读，MUST 改为分层/分带。
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
- [ ] **连线档位正确**（§2.1）：L2 / L3 / Deployment 无连线；领域模型 / ER 无箭头；L1 / Dynamic 有箭头
- [ ] 元素六属性齐全
- [ ] 关系标签为「动词 + 技术协议」（③ 档）或「关系性质」（② 档）
- [ ] 有 Legend（档 ① 须写明"结构视图不画连线"）
- [ ] **宽高比 ≤ 4:1**，无「面条图」
- [ ] 无 Markdown 反引号（PlantUML 不渲染）
- [ ] `.puml` 与 `.svg` 成对且标题一致
- [ ] 配色符合 §5
- [ ] 引用该图的文档链接可达、且被移除的连线信息已在正文表格/动态图中找到归宿

---

## 9. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [technical-solution-standard.md](technical-solution-standard.md)
- [system-design-standard.md](system-design-standard.md)
- [../architecture/diagrams/](../architecture/diagrams/)
