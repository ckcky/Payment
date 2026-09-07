# Feature Specification: 演示界面设计系统统一——DESIGN.md 单一真相源 + 共享 Token 层

**Feature Branch**: `020-demo-ui-design-system`

**Created**: 2026-09-07

**Status**: ✅ Accepted（2026-09-07 负责人拍板：D1–D5 全部采纳建议项——D1 Stripe light 基调 / D2 四页全改 / D3 系统字体栈 / D4 内联 SVG 图标 / D5 spec 直推 master、实现走本分支；**2026-09-07 本分支实施**）

**Input**: 负责人 2026-09-07 需求（原文归纳）：

> 「你去看一下 github 上这个项目 awesome-design-md。然后看看我们那些演示的界面要怎么改。」

调研结论：[awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（VoltAgent 维护，MIT，101k+ stars）收录 73 个品牌的 `DESIGN.md`——Google Stitch 提出的「纯 Markdown 设计系统」概念：YAML frontmatter 承载设计 token（颜色/字阶/圆角/间距/组件），正文承载规则（视觉主题/布局/阴影/Do-Don't/响应式），任何 AI 编码代理读取后即可生成视觉一致的 UI，零工具链依赖。对支付平台最匹配的是 **stripe 风格**（`design-md/stripe/DESIGN.md`，487 行）：靛紫主色 `#533afd`、weight-300 细体展示字、`tnum` 表格数字（"金融基础设施的安静信号"）、pill 按钮、near-white 卡片表面 + dark 控制台双轨。

本 Spec 是**补齐型 Spec**：mock-channel-web 的 4 个演示页面已有完整功能，但视觉层是四份各自为政的内联 CSS。本 Spec 不加功能，只把「样式真相源」从四份漂移的内联 CSS 收敛为一份 DESIGN.md + 一份共享 token 层。

## 当前代码现实（已核实，禁止按绿地项目理解）

演示 UI 全部位于 `deployment/mock-channel-web/src/main/resources/static/`（由 mock-channel-web :8091 直接伺服，同源代理见 ADR-0048）：

| 页面 | 行数 | 职责 |
|---|---|---|
| `portal.html` | 117 | 平台门户：四个入口卡片 + 压测命令 |
| `demo.html` | 414 | 演示控制台：下单→支付→退款全链路 + 全链路 DB 视图 |
| `cashier.html` | 157 | Mock 渠道收银台：支付六操作 + 换渠道（Feature 015） |
| `audit.html` | 715 | 对账/审计控制台：会计四核对 + 挂账调账闭环（Feature 017） |

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| **G1** | **无设计 token 层**，全部硬编码 Tailwind 默认值 | 默认蓝 `#2563eb` 出现在 4 个页面（demo.html:16,37 / cashier.html:25 / portal.html:20,26 / audit.html:17）；默认红 `#dc2626`、绿 `#16a34a` 同样四处复制 | 无任何品牌识别度；改一个主色要动四个文件 |
| **G2** | **同款组件重复实现且漂移** | `.card`（白底+边框+圆角10px）四页各写一份，阴影三处不同：`rgba(0,0,0,.06)`（demo/cashier/portal）vs audit.html:11 同值但 padding 不同；按钮圆角 8px 与 pill 混用无规律 | 视觉不一致是四份内联 CSS 各自演化的必然结果 |
| **G3** | **状态色散落且语义混杂** | demo.html:22-24 `.st-*` 共 9 条硬编码；audit.html:34-43 `.c-*` 共 8 条；同一业务状态（如 SUCCEEDED）在两页取色来源不同；状态色与 UI 色无分层 | 新增业务状态时无登记处，漏映射即回退默认黑 |
| **G4** | **收银台与内部审计台无视觉区分** | cashier.html 与 audit.html 同一灰底白卡体系；收银台作为「对外形象页」（演示时最常被看到）没有独立气质 | 演示观感：支付产品做得像内部运维工具 |
| **G5** | **金额/单号无表格数字** | demo.html:34 `.tbl` 用 Consolas 等宽凑合；cashier.html:16 金额 28px/700 无 `tnum`；audit.html:56 `.num` 仅右对齐等宽 | 金额列位数不齐、无金融产品排版质感 |
| **G6** | **emoji 当图标** | portal.html:42,49,56,64（🧪🧾📊🔥）、cashier.html:53-56（💳💬🎵🧪） | 观感廉价，跨平台渲染不一 |

先例：`docs/specs/017-accounting-audit/audit-console-mockup.html` 是 017 期间手写的视觉稿——本项目已有「先出视觉稿再改页面」的实践，缺的是把它沉淀成可复用的规范文档。

## 目标 / 非目标

**目标**

- **O1**：建立 `docs/design/DESIGN.md` 作为演示 UI 的**唯一样式真相源**——以 stripe 风格为基底裁剪适配 PaymentArch：颜色语义、字阶、圆角/间距、组件样式、阴影、Do/Don't 全部落 token。
- **O2**：落地 `static/design.css` 共享 token 层（纯 CSS variables + 少量语义类），四个页面的内联样式收敛为「引用 token + 页面特有布局」，消除 G1/G2 漂移。
- **O3**：状态语义映射表（业务状态 → 语义色 token）进 DESIGN.md 并在 `.st-*`/`.c-*` 类中兑现，G3 收口；守则明确「新增业务状态必须先登记映射表」。
- **O4**：按优先级改版四页（cashier → portal → demo → audit），收银台获得独立视觉（渐变横幅 + pill 按钮 + tnum 大字金额），控制台双页获得一致的 dark-shell 日志面板与表格数字，G4/G5/G6 收口。
- **O5**：此后任何新演示页面/新组件，由 AI 读取 `docs/design/DESIGN.md` 生成即天然同风格——这是引入 DESIGN.md 工作流的根本动机。

**非目标**（明确排除，避免范围蔓延）

- ❌ **零前端依赖铁律**：不引入构建链、框架、npm、CDN 外链、webfont（含 Inter）。字体走系统栈（详见决策点 D3）。DESIGN.md 是给 AI 读的文档，不是要安装的工具。
- ❌ **不改行为**：不动任何页面 JS 逻辑、接口路径、DOM 交互语义；mock-channel-web 的 Java 代码零改动（仅 static resources）。允许 class 名与标签结构的最小调整，回归以现有手工冒烟为准。
- ❌ **不动领域服务**：9 个领域服务、数据库、compose 栈均不涉及。
- ❌ **不做暗色主题切换**：日志面板/DB 视图保持现有局部 dark；整页不做 light/dark 切换。
- ❌ **不做响应式重设计**：保留现 `max-width` 桌面布局，仅按 DESIGN.md 断点规则补移动端兜底（不追求像素级）。
- ❌ **不承担安全修复**：2026-09-07 审计报告的 P0（回调验签等）不在此 Spec 范围。

## User Scenarios & Testing

> 标注约定：`[目标]` = 本 Feature 要建的；无标记 = 现状已有。

### User Story 1 - 设计真相源与 Token 层落地 (Priority: P1)

作为演示 UI 的维护者，我希望有一份 `docs/design/DESIGN.md` 和一份 `static/design.css`，所有颜色/字阶/圆角/间距只有一处定义，使得四个页面从「四份漂移的内联 CSS」变成「一份规范 + 四个消费者」。

**Why this priority**: 这是其余所有故事的地基——不先立 token 层，页面改版只是换个地方继续硬编码。

**Independent Test**: 任取一页（如 cashier.html）将其 `.card`/`button` 样式替换为 design.css 引用 → 页面渲染正常且样式值与 DESIGN.md token 表一致；`grep -c "#2563eb" static/*.html` 为 0。

**Acceptance Scenarios**:

1. **Given** DESIGN.md 与 design.css 已落地，**When** 检查四页内联样式，**Then** 颜色/字阶/圆角/间距均以 `var(--token)` 引用，页面内联样式仅剩该页特有的布局规则（FR-001/002/003）。
2. **Given** 设计需要调整主色，**When** 只改 design.css 的 `--primary` 一处，**Then** 四页主色同步生效（SC-002）。
3. **Given** DESIGN.md 完整存在，**When** 让 AI 编码代理读它新建一个演示页面，**Then** 产出页面与现有四页视觉一致（O5 的存在性验证，验收时当场演示）。

### User Story 2 - 收银台改版：给「对外形象页」独立气质 (Priority: P1)

作为观看演示的人，我希望收银台有支付产品应有的视觉质感——渐变横幅、细体大字金额（tnum）、pill 按钮——使得 mock 渠道收银台一眼区分于内部运维工具。

**Why this priority**: cashier.html 是演示流程中最常被投屏、最代表平台形象的页面，收益/成本比最高。

**Independent Test**: 打开 `payUrl` 进入收银台 → 顶部渐变横幅 + weight-300 金额 + pill 按钮呈现；六个支付操作（成功/失败/超时/UNKNOWN/重复回调/伪造签名）与四个换渠道按钮**功能行为与改造前完全一致**（手工冒烟：重复回调幂等吸收日志、伪造签名 403 日志照旧出现）。

**Acceptance Scenarios**:

1. **Given** 订单已创建支付单，**When** 打开收银台，**Then** 页面呈现 Stripe 风横幅与 tnum 金额，单号信息完整可读（FR-006）。
2. **Given** 收银台任意操作，**When** 执行「重复回调 ×2」，**Then** 操作日志与改造前语义一致（幂等吸收提示），证明纯视觉改造未碰 JS（SC-005）。
3. **Given** 换渠道（ALIPAY/WECHAT/DOUYIN/MOCK），**When** 点击任一渠道按钮，**Then** 仍经 `/proxy/order` 新建支付单并跳转新收银台（Feature 015 行为不变，SC-005）。

### User Story 3 - 门户卡片化 (Priority: P2)

作为打开 :8091 根路径的人，我希望门户入口卡片有清晰层级与品牌色点缀，使得第一印象即「这是一个支付平台」。

**Acceptance Scenarios**:

1. **Given** 门户页，**When** 打开，**Then** 四入口卡片使用统一 card token，emoji 替换为内联 SVG 图标（FR-007），端口探活/复制命令功能不变。
2. **Given** Grafana/Prometheus 未启动，**When** 打开门户，**Then** 「未响应属预期」提示逻辑不变（纯样式改造）。

### User Story 4 - 演示控制台统一 (Priority: P2)

作为跑全链路演示的人，我希望 demo.html 的表格数字对齐、状态 chip 走语义 token，使得长单号与金额列逐位对齐、状态一眼可读。

**Acceptance Scenarios**:

1. **Given** 全链路 DB 数据视图（④区），**When** 渲染任意表，**Then** 金额列与单号列使用 `tnum`（FR-005），状态值经 FR-004 映射表取色。
2. **Given** 下单→支付→退款全流程，**When** 在改造后的页面走完，**Then** 轮询、退款幂等重放、DB 视图刷新行为与改造前一致（SC-005）。

### User Story 5 - 审计控制台统一 (Priority: P2)

作为使用对账控制台的人，我希望 audit.html 与 demo.html 共享同一套 token，会计四核对、挂账/调账操作、四核对进度条等功能与观感同时在线。

**Acceptance Scenarios**:

1. **Given** audit.html 现有 8 种 `.c-*` 状态 chip，**When** 改造完成，**Then** 全部走 FR-004 映射表，且 MOCK/LIVE 双模式切换功能不变。
2. **Given** 四核对执行中，**When** 观察进度条与差异计数，**Then** 进度条颜色来自语义 token（如 BLOCKER 用 danger），逻辑不变。

## 功能需求（FR）

### 6.1 设计真相源

- **FR-001**：`docs/design/DESIGN.md` 采用 awesome-design-md 的九段结构：视觉主题与气质 → 色板与语义角色 → 字阶（含中文字体栈）→ 圆角与间距 → 组件样式（按钮/卡片/输入框/表格/日志面板/状态 chip，含状态态）→ 阴影体系 → Do's and Don'ts → 响应式 → Agent 提示指南。头部 YAML frontmatter 承载机器可读 token。
- **FR-002**：色板以 stripe 风格为基底并完成 PaymentArch 语义扩展：`--primary: #533afd` 系（含 deep/press/soft/subdued）、`--ink: #0d253d` 系（ink/secondary/mute）、表面（canvas/canvas-soft/hairline）、语义色（success/danger/warning/info 及 soft 底色版）。
- **FR-003**：`static/design.css` 仅含 CSS variables + 语义类（`.pa-card`、`.pa-btn`（primary/secondary/ghost/danger 变体）、`.pa-chip-st-{STATUS}`、`.pa-amount`（tnum）、`.pa-log`、`.pa-table`），目标 ≤200 行；四页通过 `<link rel="stylesheet" href="/design.css">` 引用，页面内联 `<style>` 仅保留该页特有布局。

### 6.2 状态语义映射

- **FR-004**：业务状态 → 语义色映射表（写入 DESIGN.md，demo.html `.st-*` 与 audit.html `.c-*` 共同消费）：SUCCEEDED/PAID/AVAILABLE/BALANCED/VERIFIED/CLOSED/PARTIALLY_SUCCEEDED → success；FAILED/CANCELLED/REJECTED/REVOKED/BLOCKER/HAS_DIFFERENCE/PENDING_REVIEW → danger；PENDING_PAYMENT/PROCESSING/UNKNOWN/RECHECKING/REQUESTED/SUSPENDED → warning；ADJUSTED/CREATED → info。守则：**新增业务状态必须先在此表登记**，映射缺失视为缺陷。

### 6.3 页面改造

- **FR-005**：所有金额、单号、数量列 `font-feature-settings: "tnum"`（`.pa-amount`/`.pa-table .num`）；金额展示统一「`¥` + 千分位 + 币种弱化小字」格式。
- **FR-006**：cashier.html 顶部渐变横幅：stripe mesh 的 CSS 简化版（cream → orange → lavender → indigo → ruby 的水平线性渐变 + 顶部圆角容器），零图片零外链。
- **FR-007**：portal/cashier 的 emoji 全部替换为 12~16px 内联 SVG 图标（stroke 风格，currentColor 取色），不引入图标库。
- **FR-008**：控制台页（demo/audit）标题区加靛紫 accent（左 border 或 eyebrow 小字），日志面板 `.pa-log` 统一 dark shell（#0d253d 底、tnum、语义色日志行）。
- **FR-009**：四页 `<head>` 加注释 `<!-- UI 规范：docs/design/DESIGN.md（Feature 020）——改样式前先读它 -->`。

## 非功能需求（NFR）

- **NFR-001**：零外部请求——无 CDN、无 webfont、无图片资源；断网/内网环境完整可用（演示场景硬要求）。
- **NFR-002**：`design.css` 文件名稳定不 hash，利于浏览器缓存；单文件 ≤10KB。
- **NFR-003**：浏览器目标：Chrome/Safari 近两年版本（学习项目，不做旧 IE/兼容层）。
- **NFR-004**：中文字重兜底——weight-300 展示字在无细体字重的中文回退字体（微软雅黑）下自动升 400，在 DESIGN.md Do/Don't 中记录（详见风险 R3）。
- **NFR-005**：视觉验收以「起栈 → 逐页截图 → 与本 Spec 附着的预期描述比对」执行，不引入截图自动化工具。

## 验收标准（SC）

- **SC-001**：`grep -rn "#2563eb\|#16a34a\|#dc2626" deployment/mock-channel-web/src/main/resources/static/` 除 design.css 外零命中（默认 Tailwind 色全部清除）。
- **SC-002**：card/按钮/日志面板样式在四页中唯一来源为 design.css；四页 `.card` 类内联定义清零。
- **SC-003**：四页所有金额/单号呈现处具备 `tnum`（SC 抽查 cashier 金额、demo ④区表格、audit 余额行）。
- **SC-004**：收银台具备独立视觉（横幅 + pill + tnum 大额），与 demo/audit 控制台风格一眼可区分。
- **SC-005**：功能回归零破坏——按现有手工冒烟路径走通：下单 → 选渠道建支付单 → 收银台六操作（含重复回调幂等、伪造签名 403）→ 换渠道 → 退款（含幂等重放、防超额 REJECTED）→ audit 四核对 + 挂账调账（MOCK 模式）。
- **SC-006**：DESIGN.md 九段完整且 token 值与 design.css 一一对应（抽查 5 个 token 交叉核对）。

## 决策点（待负责人拍板）

| # | 决策 | 选项 | 建议 |
|---|---|---|---|
| **D1** | 设计基调 | A. Stripe light（靛紫 + 白面 + dark 控制台）／ B. Linear dark 全套（near-black）／ C. 自拟品牌色 | **A**——支付基础设施气质，且控制台页已是 dark 局部，迁移成本最低 |
| **D2** | 改造范围 | A. 四页全改（一个分支一次收口）／ B. 仅 cashier+portal，控制台页后续再说 | **A**——G1-G6 都是同一根源，拆两次反而留漂移 |
| **D3** | 字体策略 | A. 系统栈（`system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei'`）／ B. 外链 Inter webfont | **A**——零依赖铁律（NFR-001）；Inter 仅写入 DESIGN.md 作 AI 生成时的可选增强 |
| **D4** | emoji 处置 | A. 内联 SVG 替换 ／ B. 保留 emoji | **A**——观感与跨平台一致性 |
| **D5** | 提交方式 | A. 本 spec（docs-only）直推 master，实现走 `feature/020-demo-ui-design-system` ／ B. spec 随实现分支一起走 | **A**——符合分支纪律：docs-only 可直推 master |

## 依赖与前置

- 无后端/数据库依赖；纯 `deployment/mock-channel-web/src/main/resources/static/` + `docs/design/` 新增。
- **前置约束**：当前工作树在 `feature/019-order-driven-refund`（WIP）。015/016 刚动过 cashier.html（换渠道、attemptSeq），**实现必须基于最新 master 重建分支**，避免与 019 的 static 改动冲突；实现前先 `git log --oneline -5 -- deployment/mock-channel-web` 核对近期触碰记录。
- 素材：stripe DESIGN.md 全文（已浅克隆至 `/tmp/awesome-design-md`，实施时以 GitHub 仓库为准）。

## 风险

| # | 风险 | 缓解 |
|---|---|---|
| **R1** | 与 019/015 分支的 static 资源冲突 | 实现分支从最新 master 拉出；rebase 时以「行为不变」为准手工合并 cashier.html |
| **R2** | 状态映射遗漏（未来新增业务状态） | FR-004 守则写入 DESIGN.md Do/Don't；`.pa-chip-st-*` 未命中时回退 warning 色 + console.warn，便于发现 |
| **R3** | weight-300 中文观感：微软雅黑无细体字重，细体大字会退化为普通粗细 | 字体栈优先苹方（macOS 自带，有 300 字重）；Windows 回退雅黑时标题升 400——DESIGN.md 记录「允许平台差异，不强求视觉像素一致」 |
| **R4** | 改样式误伤 JS 选择器（如 `querySelector('#log')`、`.st st-` 动态 class 拼接） | 约束：**id 与 JS 依赖的 class 拼接模式（`st-{STATUS}`、`c-{STATUS}`）保持不变**，design.css 提供同名类；回归按 SC-005 全路径冒烟 |

## 关联文档

- 上游灵感：[VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（MIT）· [Stripe DESIGN.md 原文](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/stripe/DESIGN.md)
- 背景诊断：`.workbuddy/reports/2026-09-07-comprehensive-audit.md`（文档与界面漂移的系统性背景）
- 同源代理架构：ADR-0048（mock-channel-web 零服务改动代理）
- 行为不变基线：`docs/specs/015-multi-channel-payment/`（收银台换渠道）、`docs/specs/017-accounting-audit/`（审计控制台功能）
- 编号衔接：`019-order-driven-refund` 已占用，本 Spec 为 020；实施前在 roadmap 附录 A 登记
