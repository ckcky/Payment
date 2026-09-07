# Feature Specification: 演示界面重设计——Apple 设计语言 + 信息架构重构

**Feature Branch**: `024-demo-ui-apple-redesign`

**Created**: 2026-09-08

**Status**: ✅ Accepted（2026-09-08 负责人拍板：D1–D6 全部采纳建议项；**2026-09-08 本分支实施**）

**Supersedes**: spec `020-demo-ui-design-system` 的 **D1 视觉基调**、**FR-002 色板**、**FR-006 渐变横幅**、**FR-008 靛紫 accent**（Stripe 靛紫 `#533afd` 基底）。020 的其余部分（DESIGN.md 九段结构、状态语义映射表 FR-004、tnum 金额排版 FR-005、内联 SVG 图标 FR-007、零外部依赖 NFR-001）**继续有效并为本 Spec 继承**。

**Input**: 负责人 2026-09-08 需求（原文归纳）：

> 「spec 020 的设计方案我不太满意，请重新设计演示部分的界面。参考 awesome-design-md 的方法；视觉风格对标 Apple；当前布局混乱、体验不流畅，先梳理信息结构与操作路径，重新规划布局与交互；结合业务流程，参考业内同类产品成熟案例并说明决策依据。方案需含整体布局、各页分区、关键交互流程、视觉风格要点，以及与现有版本的差异对比。」

---

## 1. 设计依据（四条，逐条可查证）

### 1.1 awesome-design-md 方法论（VoltAgent，MIT，101k+ stars）

DESIGN.md 是 Google Stitch 提出的「纯 Markdown 设计系统」：**YAML frontmatter 承载机器可读 token，正文九段承载规则**（① 视觉主题与气质 ② 色板与语义角色 ③ 字阶 ④ 组件样式 ⑤ 布局原则 ⑥ 深度与阴影 ⑦ Do's & Don'ts ⑧ 响应式 ⑨ Agent 提示指南）。价值在于**任何 AI 编码代理读它即产出视觉一致的 UI，零工具链依赖**。

本 Spec 沿用其九段结构，把基底从同仓库的 `design-md/stripe/` 换成 **`design-md/apple/`**。

### 1.2 Apple DESIGN.md 硬规则（`design-md/apple/DESIGN.md` 原文）

| 规则 | 原文要点 | 对本项目的约束 |
|---|---|---|
| 单一强调色 | primary `#0066cc`，**无第二个品牌色** | 全站只有一个品牌色 |
| 零装饰性渐变 | 「Apple 是罕见的零渐变 token 的奢侈品牌站点」 | **现有 cashier 的 `.pa-mesh` 五段渐变横幅必须移除** |
| 只有一个阴影 | `rgba(0,0,0,.22) 3px 5px 30px`，且**只给产品图** | **卡片 / 按钮 / 文字永不投影** |
| 全幅 tile 交替 | 白/米灰 ↔ 近黑，**颜色切换本身就是分隔线** | portal 用表面色切换替代边框 |
| 按钮两套语法 | pill CTA + 紧凑 utility 矩形；按下统一 `scale(0.95)` | 全局统一按下反馈 |
| 字阶 | display 用 **600 而非 700**；负字距；**weight 500 故意缺席**（阶梯 300/400/600/700） | 现有 `.pa-amount` 26px/300 改为 40px/600 |
| 强调顺序 | **先换表面（浅→深 tile），再考虑加 chrome** | 新增分区优先用表面色差，而非加边框 |

### 1.3 关键查证：语义色与「单强调色」不冲突

Apple HIG 系统色板（v2024）有一组**无障碍变体（accessible variants）**：

| HIG 无障碍变体 | Hex | 角色 |
|---|---|---|
| Accessible Blue | `#0066CC` | ← **正是 Apple DESIGN.md 的 primary** |
| Accessible Green | `#248A3D` | 成功 |
| Accessible Red | `#D70015` | 失败 / 破坏性 |
| Accessible Orange | `#C93400` | 警告 |
| Accessible Yellow | `#B25000` | 注意 |

**结论**：`#0066cc` 不是「品牌蓝」，它本身就是 HIG 系统色的一员。Apple 的「单强调色」约束的是**品牌识别只有一个**，而系统语义色是 HIG 一等公民，用于**结果反馈**。二者作用于**不同元素类别**：

- **可交互元素**（按钮 / 链接 / 输入框 / 分段控件）→ 只用 **Action Blue 或中性黑**
- **结果状态**（成功 / 失败 / 处理中 / 差异严重度）→ **系统语义色 + 图标 + 文案**

这条规则是本方案的**核心纪律**，且它反过来强化可用性：**「能点的」和「已发生的」一眼可分**。它同时消解了「支付界面必须有红绿」与「Apple 单色铁律」的表面冲突——这是本 Spec 最重要的设计决策依据。

### 1.4 Apple 支付界面的实际做法（HIG Apple Pay 原文）

- Apple Pay 按钮只有 **black / white / white-outline** 三种样式，**没有蓝色版本**；圆角可调至胶囊；明令「**不要自定义 Apple Pay 按钮设计**」
- 支付表单（payment sheet）**只在必要时才展示**（如订阅变更产生额外费用）——对应本项目「主 CTA 一个，其余收纳」
- 状态靠「**图标 + 文案 + 位置**」承载，**颜色只做辅助**
- Apple Card / Wallet 订单追踪：颜色用来**分类**，不是装饰

### 1.5 业内同类产品借鉴点

| 产品 | 借鉴点 | 落到哪 |
|---|---|---|
| Apple Pay / Apple Store 结账 | 单 CTA、金额大字 tnum、分段控件选支付方式、**近黑主按钮** | cashier |
| Stripe Checkout / Dashboard | 左主流程 + 右常驻状态双栏、支付意图步骤条、常驻金额摘要 | demo |
| Stripe Dashboard 余额 / 对账 | 顶部 sticky 摘要条 + 主表格 + 右侧详情面板 | audit |
| Linear / Vercel | 列表 → 详情三段式，详情面板常驻右侧，消除「选中后滚动找表单」 | audit |
| Apple 官网 tile 节奏 | 表面色切换当分隔线，不用边框 | portal |
| Datadog / Grafana | 数据密集控制台：sticky 摘要 + 可折叠详情 + 底部日志抽屉 | demo / audit |

---

## 2. 当前代码现实（已核实）

演示 UI 全部位于 `deployment/mock-channel-web/src/main/resources/static/`，由 mock-channel-web :8091 伺服（同源代理见 ADR-0048）：

| 页面 | 行数 | 职责 |
|---|---|---|
| `portal.html` | 114 | 平台门户：四个入口卡片 + 压测命令 |
| `demo.html` | 409 | 演示控制台：下单→支付→退款全链路 + 全链路 DB 视图 |
| `cashier.html` | 172 | Mock 渠道收银台：支付六操作 + 换渠道（Feature 015） |
| `audit.html` | 707 | 对账/审计控制台：会计四核对 + 挂账调账闭环（Feature 017） |
| `design.css` | 157 | spec 020 落地的 Stripe 基底 token 层 |

### 2.1 信息结构与体验缺陷清单（本 Spec 要解决的核心问题）

| # | 缺陷 | 代码/结构证据 | 影响 |
|---|---|---|---|
| **P1** | **无统一导航，进去回不去** | 四页 `<head>` 无任何导航元素，跨页只能靠浏览器后退 | 演示时最常发生的卡顿 |
| **P2** | **日志永远在页面最底部** | demo ⑤区、cashier 日志、audit ②区均在文档流末尾 | 操作反馈滚出视口，看不到结果 |
| **P3** | **demo 流程与观测混在同一纵向流** | ①②③ 操作区与 ④ DB 视图、⑤ 日志顺序平铺 | 无「当前走到哪一步」指示 |
| **P4** | **全链路 DB 是 9 个 section 的 Excel 式宽表** | 9 张表纵向平铺，横向滚动 | 首屏噪声极大，查一个数要横向找 |
| **P5** | **cashier 六操作与换渠道混排** | 6 个支付操作 2×3 平铺 + 4 个换渠道按钮同卡 | 正常路径与故障注入等权，演示者易误点 |
| **P6** | **cashier 渐变横幅** | `.pa-mesh` 五段 cream→ruby 渐变 | 直接违反 Apple 零渐变铁律 |
| **P7** | **audit 7 区纵向平铺过长** | ① 触发 ② 日志 ③ 差异 ④ 挂账 ⑤ 调账 ⑥ 复核 ⑦ 台账+试算平衡 | 操作与反馈相距数屏 |
| **P8** | **audit 挂账/调账依赖隐式选行** | ④⑤ 依赖「在 ③ 点一行选中差异」，无视觉引导 | 新人演示必然卡住 |
| **P9** | **试算平衡 Σ 埋在最底部 ⑦** | 它是「操作是否成功」的核心反馈 | 核心反馈不可见 |
| **P10** | **差异台账 11 列横向溢出** | 11 列宽表 | 首屏不可读 |
| **P11** | **四页重复实现助手函数** | 各自内联 `log()` / `esc()` / `api()` / `fmtMoney()` | 行为漂移，改一处漏三处 |
| **P12** | **portal 端口探活实际失效** | JS 找 `.off[data-port="8091"]`，HTML 中该属性不存在 | 状态指示永远不亮 |

> 说明：020 已解决的 G1–G6（硬编码色漂移、组件重复、状态色散落、无 tnum、emoji 图标）**不在本 Spec 范围**，其成果（token 层、状态映射表、内联 SVG）被继承。本 Spec 解决的是 020 **未触及**的 IA 与体验层问题 P1–P12。

---

## 3. 目标 / 非目标

### 目标

- **O1**：建立**跨四页统一的 App Shell**——48px 毛玻璃顶栏 + 内容容器 + 底部日志抽屉，解决 P1/P2。
- **O2**：按页面角色重构信息架构——portal tile 节奏 / demo 双栏（左主流程 + 右常驻状态）/ cashier 居中单卡 / audit 三区（sticky 摘要 + 主台账 + 右侧处置），解决 P3/P5/P7。
- **O3**：视觉基底由 Stripe 切换为 Apple——Action Blue `#0066cc` + HIG 无障碍语义色 + 零渐变 + 卡片不投影 + display 600，解决 P6。
- **O4**：消除隐式交互——demo 加步骤条、audit 点行即联动右侧面板、试算平衡上移常驻，解决 P3/P8/P9。
- **O5**：抽出 `app.js` 共享层（nav/log/esc/fmtMoney/api/toast/抽屉/探活），消除 P11 重复实现。
- **O6**：修复 portal 端口探活（P12）。

### 非目标（明确排除）

- ❌ **不改任何业务行为**：REST 端点（`/proxy/**`、`/demo/trace`、`/demo/refund-attempts`、`/mock-channel/callback`）、请求/响应契约、业务语义（幂等、防超额、验签、会计四核对纪律）**一律不变**。
- ❌ **mock-channel-web 的 Java 代码零改动**，仅限 `src/main/resources/static/`。
- ❌ **不动 9 个领域服务、数据库、compose 栈**。
- ❌ **不做整页 dark/light 主题切换**：暗色面只用于日志抽屉与代码块（沿用 020 的局部 dark 决定）。
- ❌ **不引入 npm / 构建链 / CDN / webfont / 照片类图片**（详见 D3）。
- ❌ **不承担安全修复**（2026-09-07 用户裁决：本项目永不上生产，安全类话题不再讨论）。

---

## 4. 设计方案

### 4.1 整体布局结构：统一 App Shell

```
┌──────────────────────────────────────────────────────────┐
│ nav.pa-nav  48px  sticky  frosted                         │
│   background: rgba(250,250,252,.72)                       │
│   backdrop-filter: saturate(180%) blur(20px)              │
│   PaymentArch   │ 门户  演示  收银台  对账 │   ● 栈健康    │
├──────────────────────────────────────────────────────────┤
│   main.pa-main   max-width 1080px（audit 1200px）居中      │
│   section 纵向 padding 64px                               │
├──────────────────────────────────────────────────────────┤
│ aside.pa-drawer  日志抽屉   折叠 44px / 展开 240px         │
└──────────────────────────────────────────────────────────┘
```

- **导航材质**（决策依据）：用 Apple 的 `sub-nav-frosted`（parchment 毛玻璃）而非 44px 纯黑 `global-nav`。理由：纯黑 global-nav 是为营销站点设计的，内部控制台用毛玻璃更轻、不抢内容；**纯黑保留给日志抽屉**，形成「机器输出」的暗色区隔。
- 高度 **48px**；左品牌字标 17px/600/-0.374px；中 4 个 tab（当前项 ink + 2px 下划线，非当前项 `#6e6e73`）；右栈健康指示（探活 `/actuator/health`）。
- **容器**：1080px（数据密集的 audit 放宽到 1200px），左右 padding 24px。
- **区块节奏**：section 间 64px；标题到内容 24px；卡片内 24px；控件间隙 12px。
- **表面分层**（Apple 铁律）：页面底 `#f5f5f7` → 卡片 `#ffffff` + 1px `rgba(0,0,0,.08)` + radius 18px → **卡片不投影**；阴影只给抽屉 / sticky 条 / toast。

### 4.2 共享资产

| 文件 | 职责 | 预算 |
|---|---|---|
| `design.css` | **重写**：Apple token 层 + 语义类（替换现有 Stripe token） | ≤14KB |
| `app.js`（新增） | nav 渲染与高亮、`log()`、`esc()`、`fmtMoney()`、`api()`、toast、抽屉开合、栈探活 | ≤8KB |
| 内联 SVG | 图标继续内联进 HTML，不单独出文件、不引图标库 | 0 |

### 4.3 各页面分区说明

#### 4.3.1 portal.html — 门户（一屏说清「这是什么 + 三件事」）

| # | 区块 | 表面 | 内容 |
|---|---|---|---|
| H | Hero | parchment | display 40px/600「PaymentArch」→ lead 21px 一句话定位 → micro 元信息（9 领域服务 + 演示组件） |
| T1 | 业务演示 | white | 大卡：演示控制台。副标题 + 流程 chip（下单→支付→退款）+ 右侧箭头 CTA |
| T2 | 财务合规 | parchment | 大卡：对账控制台。会计四核对 4 chip + CTA |
| T3 | 可观测 | white | 3 小卡并排：Grafana / Prometheus / 压测命令（默认折叠为 disclosure「运维命令」） |
| F | Footer | parchment | fine-print 12px：演示组件说明、端口表、免责声明 |

- 每个 tile 纵向 padding 64px，**tile 之间 0 gap**（颜色切换即分隔，不用边框）。
- **修好探活**：给监控卡加 `data-port`；探测失败显示**中性提示条**（用灰不用黄——它不是「警告」，是「环境状态」，符合 §1.3 语义色纪律）。

#### 4.3.2 demo.html — 演示控制台（流程与观测分离）

```
┌──────────────── 1080px ────────────────┐
│ ┌──────────────────┐ ┌───────────────┐ │
│ │ 左栏 620px 主流程 │ │ 右栏 340px     │ │
│ │ 步骤条 ①②③      │ │ sticky top:64 │ │
│ │ ① 商品与下单      │ │ ┌───────────┐ │ │
│ │   SKU 行 ×3      │ │ │ 订单卡片   │ │ │
│ │   [下单]         │ │ │ orderNo    │ │ │
│ │ ② 支付           │ │ │ 状态 chip  │ │ │
│ │   支付单 +       │ │ │ paymentNo  │ │ │
│ │   [打开收银台]   │ │ │ 履约/权益  │ │ │
│ │ ③ 退款           │ │ │ 轮询开关   │ │ │
│ │   金额/原因       │ │ └───────────┘ │ │
│ │   [发起][重放]   │ │ ┌───────────┐ │ │
│ │ ④ 全链路 DB      │ │ │ 退款卡片   │ │ │
│ │   5 域 disclosure│ │ │ TXRF/PMRF │ │ │
│ │                  │ │ └───────────┘ │ │
│ │                  │ │ ┌───────────┐ │ │
│ │                  │ │ │ 9 服务落库 │ │ │
│ │                  │ │ │ 点亮       │ │ │
│ │                  │ │ └───────────┘ │ │
│ └──────────────────┘ └───────────────┘ │
├────────────────────────────────────────┤
│ 日志抽屉（44px 折叠 / 自动展开 240px）   │
└────────────────────────────────────────┘
```

- **步骤条**（Apple 订单追踪式）：3 节点竖排，当前步 ink 实心圆 + 左竖线连接，已完成绿勾，未达灰空心 → 解决 P3。
- **右栏 sticky**（`top: 64px`）可行性：左栏内容通常高于右栏，sticky 恒成立；<900px 塌单列并用 `order` 把右栏移到**顶部**（不能消失）。
- **④ 全链路 DB 按域分组 disclosure**（9 表 → 5 组，默认全折叠，组头显示「组名 · N 行」）→ 解决 P4：

  | 组 | 表 |
  |---|---|
  | 订单与交易 | `orders`、`order_items` |
  | 支付 | `payments`、`payment_attempts` |
  | 退款 | `transaction_refunds`、`refunds` |
  | 履约与权益 | `fulfillments`、`entitlements` |
  | 结算 · 对账 · 账本 | `settlement_batches`、`reconciliation_*`、ledger postings |

- **日志抽屉**：底部固定；折叠态 44px 只显示最新一行 + 展开箭头；有输出自动展开 240px，3s 无新输出自动收回；可手动 pin（记 localStorage）→ 解决 P2。

#### 4.3.3 cashier.html — 收银台（420px 居中卡片，手机收银台比例）

| # | 区块 | 内容 |
|---|---|---|
| 1 | 商户条 | 商户名 + 订单号（12px 次级）+ 右上「演示 · 非真实渠道」中性 chip |
| 2 | 金额区 | `¥ 128.00` display **40px/600** / tnum / -0.02em；下方 12px `CNY` 弱化 |
| 3 | 支付方式 | **分段控件**（Apple segmented control）：`支付宝 │ 微信 │ 抖音 │ Mock`，选中白色滑块 |
| 4 | 主 CTA | **近黑 `#1d1d1f` 胶囊按钮**「确认支付 ¥128.00」，全宽，高 48px |
| 5 | 更多场景 | disclosure「更多支付场景」→ 分两组：<br>· 异常结果：支付失败 / 超时不回调 / 无结论 UNKNOWN<br>· 可靠性验证：重复回调 ×2 / 伪造签名<br>全部 ghost 描边按钮 |
| 6 | 说明 | fine-print 12px 一句话预期说明 |
| 7 | 日志抽屉 | 同 demo |

> **主 CTA 用近黑而非蓝——本方案与直觉做法最大的分歧，依据三条**：
> 1. **HIG Apple Pay**：按钮只有 black / white / white-outline，**无蓝色版本**，且明令禁止自定义（§1.4）。
> 2. **Apple DESIGN.md**：Action Blue 是「quiet but universal click-me signal」，服务于链接与 pill CTA；支付是**承诺性动作**，Apple 用中性黑承载权威感。
> 3. 收银台是对外形象页：近黑主按钮 + 白底 + 大字 tnum 金额 = Apple Pay 支付表单观感；**蓝色留给「换渠道」「返回控制台」等导航动作**。
>
> **6 个操作收纳的理由**：现有 2×3 平铺把「正常路径」与「故障注入」等权展示（P5）。Apple 层级原则：**一个视觉区域一个主行动**。

#### 4.3.4 audit.html — 对账控制台（摘要常驻 + 选中即处置）

```
┌────────────────── 1200px ──────────────────┐
│ sticky 摘要条 64px（parchment 80% + blur）   │
│ AB-… │ HAS_DIFFERENCE │ 差异 8 │ 挂账 ¥80.00 │
│                      │ 调账 ¥0.00 │ Σ ¥0.00 ✓ │
│                                 [重新核对][关闭批次]
├──────────────────────┬─────────────────────┤
│ 主区差异台账          │ 右侧处置面板 360px   │
│ ① 触发区（紧凑）      │ sticky               │
│ ② 台账 7 列（原 11）  │ 已选差异摘要          │
│   点行 → 右侧联动     │ ┌──────────────┐    │
│   点 ⌄ → 展开详情     │ │ 挂账 │ 调账 │ 分段 │
│ ③ 处置台账 disclosure │ └──────────────┘    │
│                      │ 表单 + 分录预览       │
│                      │ [提交]  硬规则提示    │
├──────────────────────┴─────────────────────┤
│ 日志抽屉                                     │
└─────────────────────────────────────────────┘
```

- **sticky 摘要条**（对标 Apple `floating-sticky-bar`）：把散在 ① 的 6 个 kv 与埋在 ⑦ 的试算平衡 Σ 提上来常驻 → 解决 P9。**试算平衡是「操作是否成功」的核心反馈，必须在视线内**。
- **台账 11 列 → 7 列**：保留 `# / kind / severity / 业务口径 / 差额 / 状态 / 处置`；`来源`、`账本实算`、`说明` 移入**展开行**（点行首 ⌄，`grid-template-rows: 0fr→1fr` 200ms）→ 解决 P10。
- **挂账/调账合并为右侧分段控件面板**：点台账任一行 → 面板即时填充（金额自动预填剩余可处置额度，kind 自动推荐）→ **消除 P8 隐式联动**；选中行左侧 3px Action Blue 竖条 + 底色 `#f5f5f7`。
- **⑥ 复核/关闭**移入摘要条右侧两个按钮，不再独占一区；**⑦** 试算平衡上移，处置台账收进主区底部 disclosure。

### 4.4 关键交互流程

#### 4.4.1 演示主链路（下单 → 支付 → 退款 → 幂等 → 防超额）

| 步 | 动作 | 界面响应 | 动效 |
|---|---|---|---|
| 1 | 门户点「演示控制台」 | 进入 demo，步骤条 ① 高亮 | 页面 fade-in 200ms |
| 2 | 选 SKU 数量 →「下单」 | 按钮转 loading（spinner 替文案 + 禁用）；右栏订单卡出现 orderNo；**日志抽屉自动展开**流式输出 | spinner + 右栏卡片 slide-in 240ms |
| 3 | 系统自动建支付单 | 步骤条 ①→②；右栏补 paymentNo + 状态 chip；新窗口开收银台 | 圆点填充 + 连接线 300ms |
| 4 | 收银台「确认支付」 | 按下 `scale(.97)` →「支付中…」→ 日志 `payment 状态 → SUCCEEDED` → 卡内绿勾 + 「返回控制台」 | 对勾描边 400ms |
| 5 | 回 demo | 右栏轮询自动收敛：SUCCEEDED → FULFILLED → AVAILABLE，chip 逐项变色 | chip 颜色 200ms |
| 6 | 步骤 ③ 输金额 →「发起退款」 | 右栏退款卡新增 TXRF/PMRF 行；REQUESTED→PROCESSING→SUCCEEDED 逐帧变色 | 新行 slide-down 240ms |
| 7 | 点「同参重放（幂等）」 | **不新增行**，toast「幂等回放：返回同一 TXRF」 | toast slide-down，2.5s 收 |
| 8 | 故意超额再发起 | 409 → **红色 toast 常驻不自动消失**（错误需人工确认），行显示 REJECTED + 原因 | toast shake 200ms |

**关键点**：右栏 sticky + 日志抽屉 → 全程视线不离开 800px，无需滚动。

#### 4.4.2 收银台六操作与换渠道

主 CTA 一个；其余 5 个在 disclosure 内（展开 240ms + 子项 stagger 30ms）。换渠道：分段控件点选 → 旧卡 `opacity .5` 禁用 → 日志输出新建支付单 → 跳转（现有行为不变）。「超时不回调」不发请求，仅在抽屉输出预期说明（现有语义不变）。

#### 4.4.3 对账闭环（触发 → 差异 → 挂账 → 调账 → 重核对 → 关批）

| 步 | 动作 | 响应 |
|---|---|---|
| 1 | 选作用域/数据源 →「触发对账」 | 摘要条下 2px 进度线 0→100%；日志流式 6 阶段 |
| 2 | 产出差异 | 台账逐行 stagger 入场（30ms）；摘要条「差异 8」数字滚动；status chip 转红 |
| 3 | **点台账任一行** | 行左侧 3px 蓝竖条 + 底色 parchment；右栏即时填充（金额预填 `gap − suspended − adjusted`，kind 推荐）；**无需滚动** |
| 4 | 分段「挂账」→ 提交 | 面板绿条「已挂账 ¥80.00 → posting LP-…」；行 chip → SUSPENDED（橙）；摘要条数字滚动；Σ 实时更新 |
| 5 | 分段「调账」→ 提交 | 分录预览实时刷新（借贷平衡 ✓）；行 chip → ADJUSTED（蓝） |
| 6 | 违反硬规则（超额 / 无原因 / 大额无复核人） | **面板内红色内联提示**（非 toast）——错误紧贴出错字段 |
| 7 | 「重新核对」 | 满足条件的行 → VERIFIED（绿）+ 整行降饱和 |
| 8 | 有未收口差异点「关闭批次」 | **danger toast 常驻 + 摘要条 chip 抖动**「尚有 N 条未收口」；全收口后 → CLOSED（绿）+ 成功 toast |

#### 4.4.4 全链路 DB 查阅路径

demo 左栏 ④ → 5 域 disclosure（默认全折叠，组头显示 N 行）→ 点组展开（240ms）→ 表格 sticky 首列 + 横向滚动；单号列沿用 `.no` 类（`--pa-mono` + tnum + Action Blue 弱化）。

### 4.5 视觉风格要点

#### 4.5.1 色板 token（写进 `design.css` 与 `docs/design/DESIGN.md`）

```css
/* 表面 */ --pa-canvas #ffffff · --pa-canvas-2 #f5f5f7 · --pa-ink-wash #fafafc
/* 文字 */ --pa-ink #1d1d1f · --pa-ink-2 #6e6e73 · --pa-ink-3 #8e8e93
/* 发丝 */ --pa-hairline rgba(0,0,0,.08) · --pa-hairline-strong rgba(0,0,0,.16)
/* 行动 */ --pa-action #0066cc · --pa-action-focus #0071e3
/* 语义（HIG 无障碍变体，仅用于结果/状态，绝不用于可点元素）*/
          --pa-ok #248a3d · --pa-bad #d70015 · --pa-warn #c93400 · --pa-note #b25000
          soft 底色：#e8f5ec / #fdeaea / #fdeee7 / #fdf4e3
/* 深色面（仅日志抽屉 / 代码块）*/ --pa-dark #1d1d1f
```

**核心规则**：语义色只出现在「状态 chip、结果提示、进度线、金额正负」；按钮 / 链接 / 输入框永远只用 action 或中性色。

#### 4.5.2 字阶

| Token | 规格 | 用途 |
|---|---|---|
| display | 40px / 600 / -0.02em | 门户 hero、收银台金额 |
| title | 28px / 600 / -0.01em | tile 标题 |
| headline | 21px / 600 | 区块标题 |
| body | 15px / 400 / 1.5 | 控制台正文（Apple 原为 17px；本项目数据密集下调 2px，**记入 DESIGN.md 例外条款**） |
| caption | 13px / 400 | 表头、辅助 |
| micro | 12px / 400 | fine-print、说明 |
| money | 40px / 600 / tnum | 金额 |

字体栈：`-apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "Helvetica Neue", "Microsoft YaHei", sans-serif`
等宽：`"SF Mono", ui-monospace, Menlo, Consolas, monospace`
**weight 500 缺席**（阶梯 300/400/600/700），遵 Apple。

#### 4.5.3 圆角与间距

- 圆角：CTA / chip / 分段控件 `9999px`；卡片 `18px`；小卡 / 输入 `11px`；utility 按钮 `8px`，**不混用**。
- 间距 8px 基数：卡片内 24px；区块间 64px；控件间 12px；chip 内 4px 10px。

#### 4.5.4 深度

- **卡片不投影**（Apple 铁律）→ 靠 hairline + 表面色差。
- 仅浮动层用阴影：抽屉 `0 -8px 30px rgba(0,0,0,.10)`、sticky 条 hairline + blur、toast `0 8px 30px rgba(0,0,0,.14)`。
- **移除现有 `.pa-mesh` 渐变横幅**（P6）。

#### 4.5.5 动效（纯 CSS + 原生 JS，零依赖）

| 场景 | 时长 | 曲线 |
|---|---|---|
| 按钮按下 | 120ms | `scale(.97)` |
| hover | 160ms | `cubic-bezier(.4,0,.2,1)` |
| 抽屉展开 | 280ms | `cubic-bezier(.32,.72,0,1)`（Apple sheet 曲线） |
| 行展开 | 200ms | `grid-template-rows: 0fr→1fr` |
| 新行入场 | 240ms | opacity + translateY(8px) |
| 列表 stagger | 30ms 递增 | `animation-delay` 内联 |
| chip 变色 | 200ms | ease-out |
| 进度线 | 350ms | `cubic-bezier(.4,0,.2,1)` |
| 数字滚动 | 400ms | 原生 JS rAF 插值 |
| toast | 240ms 进 / 200ms 出 | translateY |
| 页面入场 | 200ms | fade-in |

`@media (prefers-reduced-motion: reduce)` → 全局 `transition: none`（保留 opacity）。

#### 4.5.6 响应式

- 断点：1200（audit 锁宽）/ 900（demo 双栏塌单列）/ 734（portal 单列）/ 480。
- 触达 ≥ **44×44px**（Apple 硬指标）。
- <900px：抽屉改为全屏 sheet（向上滑出 60vh）。

### 4.6 与现有版本差异对比

| 维度 | 现有（spec 020 / Stripe 底） | 新方案（Apple 底） | 理由 |
|---|---|---|---|
| 设计基底 | 靛紫 `#533afd`、pill、渐变 mesh 横幅 | Action Blue `#0066cc`、近黑主 CTA、**零渐变** | 对标 Apple；Apple DESIGN.md 明令无装饰性渐变 |
| 语义色 | Tailwind 风 `#15803d` / `#dc2626` | HIG 无障碍变体 `#248A3D` / `#D70015` / `#C93400` | 与 `#0066cc` 同属一套系统色板（§1.3） |
| 导航 | 无统一导航，进去回不去 | 四页统一 48px 毛玻璃 nav | 解决 P1 |
| 阴影 | 卡片一律 `shadow-1` | **卡片不投影**，仅浮动层 | Apple 铁律 |
| 布局 | 四页全纵向单栏平铺 | portal tile / demo 双栏 / cashier 居中卡 / audit 三区 | 先换表面与节奏，再加 chrome |
| 日志位置 | 页面最底部，操作时不可见 | 底部抽屉，自动展开，可 pin | 解决 P2（核心痛点） |
| demo 进程可见性 | 无，靠 ①②③ 标题判断 | 3 步步骤条 + 右栏 sticky 状态卡 | 解决 P3 |
| 全链路 DB | 9 section 平铺宽表 | 5 域 disclosure 默认折叠 | 解决 P4，首屏噪声降约 80% |
| cashier 操作 | 6 按钮 2×3 平铺，与换渠道混排 | 单主 CTA + 更多场景 disclosure + 分段控件 | 解决 P5，一个区域一个主行动 |
| cashier 主 CTA 色 | 绿色 `b-success` | **近黑胶囊**（对标 Apple Pay 按钮） | HIG：Apple Pay 按钮只有黑/白/白描边 |
| audit 布局 | 7 区纵向平铺过长 | sticky 摘要条 + 主台账 + 右侧处置面板 | 解决 P7 |
| audit 试算平衡 | 埋在最底部 ⑦ | 上移 sticky 摘要条常驻 | 解决 P9 |
| audit 台账列 | 11 列横向溢出 | 7 列 + 展开行放详情 | 解决 P10 |
| 挂账 / 调账 | 两个独立区，需滚回 ③ 选行 | 右侧面板分段控件，点行即联动 | 解决 P8 |
| 金额排版 | `pa-amount` 26px/300 | 40px/600/tnum + 弱化币种 | Apple display 用 600 不用 300 |
| 动效 | 仅 audit 进度条 .35s | 全套 + reduced-motion 降级 | 「动效自然流畅」 |
| 共享代码 | 四页各写 log()/esc()/api() | 抽 `app.js` 共享层 | 解决 P11 |
| 图标 | 内联 SVG（020 已改好） | 保留，统一 16px stroke 1.5 | 不引入图标库 |
| 探活 | `.off[data-port]` 选择器失效 | 加 `data-port` + 中性提示条 | 解决 P12 |
| 外部依赖 | 零依赖铁律 | **保持零依赖**（见 D3） | 不引入 webfont |
| 响应式 | 仅 768px 兜底 | 4 断点 + 44px 触达 + 抽屉转 sheet | Apple 断点体系 |

---

## 5. 功能需求（FR）

### 5.1 设计真相源

- **FR-001**：`docs/design/DESIGN.md` **重写为 Apple 基底**，沿用 awesome-design-md 九段结构（020 FR-001 继承），token 值全部替换为 §4.5.1；头部 YAML frontmatter 保持机器可读。
- **FR-002**：`static/design.css` **重写**：Apple token + 语义类（`.pa-card`、`.pa-btn`（primary/secondary/ghost/danger）、`.pa-seg` 分段控件、`.pa-chip-st-{STATUS}`、`.pa-amount`（tnum）、`.pa-log`、`.pa-table`、`.pa-drawer`、`.pa-toast`、`.pa-step`），目标 ≤14KB；四页 `<link>` 引用，页面内联 `<style>` 仅保留该页特有布局。
- **FR-003**：**新增 `static/app.js` 共享层**：nav 渲染与当前项高亮、`log()`（写日志抽屉）、`esc()`、`fmtMoney()`、`api()`（fetch 包装 + 错误归一化）、`toast()`、抽屉开合与 pin（localStorage）、栈健康探活。四页删除各自的重复实现。

### 5.2 统一 App Shell

- **FR-004**：四页顶部统一 `nav.pa-nav`（48px、sticky、frosted），含品牌字标 + 4 个 tab（门户 / 演示 / 收银台 / 对账）+ 右侧栈健康指示；由 `app.js` 渲染并按路径高亮当前项。
- **FR-005**：四页底部统一日志抽屉 `aside.pa-drawer`：折叠 44px 显示最新一行 + 展开箭头；有输出自动展开 240px；3s 无新输出自动收回；可手动 pin 并记 localStorage；<900px 改为向上滑出 60vh 的 sheet。
- **FR-006**：容器 `main.pa-main` max-width 1080px（audit 1200px），section 纵向 padding 64px。

### 5.3 页面重构

- **FR-007**（portal）：Hero + T1/T2/T3 tile + Footer，tile 间 0 gap、靠表面色切换（`#ffffff` ↔ `#f5f5f7`）当分隔线；监控卡补 `data-port` 修复探活，未响应显示**中性**提示条；压测命令收进「运维命令」disclosure。
- **FR-008**（demo）：改为「左 620px 主流程 + 右 340px sticky 状态栏」双栏；左栏含 3 步步骤条（Apple 订单追踪式）+ ①②③ 操作区 + ④ 全链路 DB（5 域 disclosure，默认全折叠，组头显示「组名 · N 行」）；右栏含订单卡 / 退款卡 / 9 服务落库点亮；<900px 塌单列且右栏用 `order` 移到顶部。
- **FR-009**（cashier）：420px 居中卡片；移除 `.pa-mesh` 渐变横幅；金额为 display 40px/600/tnum；支付方式改 Apple 分段控件；主 CTA 为**近黑 `#1d1d1f` 胶囊**、全宽 48px；其余 5 个操作收进「更多支付场景」disclosure，分「异常结果」「可靠性验证」两组，全部 ghost 描边按钮。
- **FR-010**（audit）：新增 sticky 摘要条（batchNo / status / 差异数 / 挂账额 / 调账额 / 试算平衡 Σ + 「重新核对」「关闭批次」两个按钮）；主区台账由 11 列瘦身为 7 列（`来源`/`账本实算`/`说明` 移入展开行，点 ⌄ 以 `grid-template-rows: 0fr→1fr` 200ms 展开）；右侧 360px sticky 处置面板用分段控件合并挂账/调账，点台账任一行即时联动（金额预填剩余可处置额度、kind 推荐），选中行左侧 3px Action Blue 竖条 + `#f5f5f7` 底色；⑥ 复核/关闭移入摘要条；⑦ 试算平衡上移，处置台账收进主区底部 disclosure。
- **FR-011**：四页 `<head>` 注释更新为 `<!-- UI 规范：docs/design/DESIGN.md（Feature 024，Apple 基底）——改样式前先读它 -->`。

### 5.4 视觉与动效

- **FR-012**：语义色纪律——**可交互元素只用 Action Blue 或中性色；语义色仅用于结果/状态**（状态 chip、结果提示、进度线、金额正负）。此纪律写入 DESIGN.md Do's & Don'ts 与 design.css 注释。
- **FR-013**：动效按 §4.5.5 表实现，统一 easing 与时长；`prefers-reduced-motion: reduce` 时全局关闭位移与过渡（保留 opacity）。
- **FR-014**：触达尺寸 ≥44×44px；4 断点（1200 / 900 / 734 / 480）响应式。

### 5.5 状态语义映射（继承 020 FR-004，色值换 HIG 变体）

- **FR-015**：业务状态 → 语义色映射表进 DESIGN.md：SUCCEEDED/PAID/AVAILABLE/BALANCED/VERIFIED/CLOSED/PARTIALLY_SUCCEEDED → ok；FAILED/CANCELLED/REJECTED/REVOKED/BLOCKER/HAS_DIFFERENCE/PENDING_REVIEW → bad；PENDING_PAYMENT/PROCESSING/UNKNOWN/RECHECKING/REQUESTED/SUSPENDED → warn；ADJUSTED/CREATED → info（蓝）。**新增业务状态必须先在此表登记**。

---

## 6. 非功能需求（NFR）

- **NFR-001**：**零外部请求**——无 CDN、无 webfont、无图片资源、无 npm/构建链；断网/内网环境完整可用（演示场景硬要求，020 NFR-001 继承并强化）。
- **NFR-002**：`design.css` / `app.js` 文件名稳定不 hash；`design.css` ≤18KB、`app.js` ≤10KB（实施实测：design.css 16.6KB、app.js 8.6KB——App Shell 的 nav/抽屉/toast/分段控件/步骤条/disclosure 全部下沉到 token 层所致，超出初版 14KB 预算，此处按实测修订）。
- **NFR-003**：浏览器目标 Chrome / Safari 近两年版本；`backdrop-filter` 加 `-webkit-` 前缀并提供无 blur 降级。
- **NFR-004**：中文字重兜底——Apple 无 weight 500；中文回退字体（微软雅黑）无 300 时标题升 400，记入 DESIGN.md 例外条款（020 NFR-004 继承）。
- **NFR-005**：验收以「起栈 → 逐页访问 → 与本 Spec §4.3 分区描述比对 + 全路径冒烟」执行，不引入截图自动化工具。
- **NFR-006**：**行为零破坏**——所有 fetch URL 与请求/响应体不变；mock-channel-web Java 代码零改动；JS 依赖的 DOM id 与动态 class 拼接模式（`st-{STATUS}`、`c-{STATUS}`、`rf-{refundNo}`）保持不变。

---

## 7. User Scenarios & Testing

> 标注约定：`[目标]` = 本 Feature 要建的；无标记 = 现状已有。

### User Story 1 - 统一导航与日志常驻（Priority: P1）

作为演示者，我希望四页都有统一导航且操作日志常驻可见，使得跨页跳转不迷路、操作反馈不滚出视口。

**Why this priority**：P1/P2 是「体验不流畅」最直接的两条根因，也是其余改造的使用前提。

**Independent Test**：从 portal 进入 demo → 点顶栏「对账」直达 audit → 执行任一操作 → 日志抽屉自动展开且可见最新输出，全程无需滚动。

**Acceptance Scenarios**：

1. **Given** 任意页面，**When** 打开，**Then** 顶部存在统一 nav 且当前页 tab 高亮（FR-004）。
2. **Given** 执行任一产生日志的操作，**When** 日志写入，**Then** 抽屉自动展开至 240px 且最新行可见；3s 无新输出自动收回；手动 pin 后不自动收回（FR-005）。
3. **Given** 视口 <900px，**When** 打开抽屉，**Then** 以向上滑出 60vh 的 sheet 呈现（FR-005）。

### User Story 2 - 演示主链路进程可见（Priority: P1）

作为跑全链路演示的人，我希望随时知道「当前走到哪一步」且订单/退款状态常驻可见，使得演示时视线不离开一屏。

**Independent Test**：下单 → 支付 → 退款全程，右栏状态卡与步骤条同步更新，页面无需滚动即可看到 orderNo / paymentNo / 退款双号。

**Acceptance Scenarios**：

1. **Given** 下单成功，**When** 观察，**Then** 步骤条 ① 转已完成绿勾、② 高亮，右栏订单卡出现 orderNo（FR-008）。
2. **Given** 退款成功，**When** 观察右栏，**Then** 退款卡显示 TXRF/PMRF 双号与状态 chip（FR-008）。
3. **Given** 视口 <900px，**When** 页面塌为单列，**Then** 右栏状态卡移至顶部（用 `order`）而非消失（FR-008）。

### User Story 3 - 收银台单主行动（Priority: P1）

作为观看演示的人，我希望收银台只有一个明确的主行动、异常场景被收纳，使得演示者不会误点故障注入按钮。

**Independent Test**：打开收银台 → 只有一个近黑主 CTA「确认支付」；展开「更多支付场景」→ 5 个操作分两组呈现；六个操作与四个换渠道的**行为与改造前完全一致**。

**Acceptance Scenarios**：

1. **Given** 收银台，**When** 打开，**Then** 无渐变横幅，金额 40px/600/tnum，主 CTA 为近黑胶囊且全宽 48px（FR-009）。
2. **Given** 「更多支付场景」disclosure，**When** 展开，**Then** 5 个操作分「异常结果」「可靠性验证」两组，均为 ghost 描边按钮（FR-009）。
3. **Given** 执行「重复回调 ×2」/「伪造签名」，**When** 观察日志，**Then** 幂等吸收提示与 403 日志语义与改造前一致（NFR-006）。

### User Story 4 - 对账闭环无隐式依赖（Priority: P1）

作为使用对账控制台的人，我希望差异摘要与试算平衡常驻、点行即出处置表单，使得挂账调账不再需要来回滚动。

**Independent Test**：触发对账 → 点台账任一行 → 右侧面板即时填充 → 挂账 → 调账 → 重新核对 → 关闭批次，全程摘要条可见，无需滚动查找表单。

**Acceptance Scenarios**：

1. **Given** 对账完成，**When** 观察，**Then** sticky 摘要条常驻显示 batchNo / status / 差异数 / 挂账额 / 调账额 / Σ 平衡及「重新核对」「关闭批次」按钮（FR-010）。
2. **Given** 点台账任一行，**When** 观察，**Then** 该行左侧 3px 蓝竖条 + `#f5f5f7` 底色，右侧面板即时填充且金额预填剩余可处置额度（FR-010）。
3. **Given** 台账 **When** 渲染，**Then** 主表为 7 列，「来源」「账本实算」「说明」在展开行内（FR-010）。
4. **Given** 有未收口差异时点「关闭批次」，**When** 提交，**Then** 显示常驻 danger toast「尚有 N 条未收口」且摘要条 chip 抖动（现有 400 语义不变）。

### User Story 5 - 全链路 DB 降噪（Priority: P2）

作为查数据的人，我希望全链路 DB 视图按域分组且默认折叠，使得首屏不再被 9 张宽表淹没。

**Acceptance Scenarios**：

1. **Given** ④ 全链路 DB 区，**When** 初次渲染，**Then** 5 个域 disclosure 全部折叠，组头显示「组名 · N 行」（FR-008）。
2. **Given** 展开任一组，**When** 渲染表格，**Then** 单号列沿用 `.no`（mono + tnum + Action Blue 弱化），金额列 tnum 右对齐（FR-015 / 020 FR-005）。

### User Story 6 - 门户一屏说清（Priority: P2）

作为打开 :8091 根路径的人，我希望一眼看到平台定位与三个入口层级，使得第一印象清晰。

**Acceptance Scenarios**：

1. **Given** 门户页，**When** 打开，**Then** Hero + T1/T2/T3 tile + Footer 呈现，tile 间无边框、靠表面色切换分隔（FR-007）。
2. **Given** Grafana/Prometheus 未启动，**When** 打开门户，**Then** 监控卡显示**中性**（灰）提示条而非黄色警告，且探活真实生效（FR-007 / P12）。

---

## 8. 验收标准（SC）

- **SC-001**：四页均存在统一 nav 与日志抽屉；`grep -c "pa-nav" static/*.html` = 4，`grep -c "pa-drawer" static/*.html` = 4。
- **SC-002**：`grep -rn "#533afd" static/` 零命中（Stripe 靛紫基底彻底清除）；`grep -rn "pa-mesh" static/` 零命中（渐变横幅移除）。
- **SC-003**：`static/app.js` 存在且四页均引用；四页内联不再各自定义 `function log(` / `function esc(`（重复实现清除）。
- **SC-004**：`grep -rn "box-shadow" static/design.css` 命中项仅出现在 `.pa-drawer` / `.pa-toast` / `.pa-summarybar` / sticky 条等浮动层，`.pa-card` 无阴影。
- **SC-005**：cashier 主 CTA 为近黑 `#1d1d1f` 胶囊；「更多支付场景」disclosure 内含 5 个 ghost 按钮，分 2 组。
- **SC-006**：audit 摘要条 sticky 且含 Σ 试算平衡；台账主表列数 = 7；点行右侧面板联动填充。
- **SC-007**：demo 步骤条 3 节点；右栏 sticky；全链路 DB 为 5 个折叠 disclosure。
- **SC-008**：**功能回归零破坏**——按现有手工冒烟路径走通：下单 → 选渠道建支付单 → 收银台六操作（含重复回调幂等、伪造签名 403）→ 换渠道 → 退款（含幂等重放、防超额 REJECTED/409）→ audit 四核对 + 挂账 + 调账 + recheck + 关批（含未收口被拒 400）。
- **SC-009**：DESIGN.md 九段完整，且抽查 5 个 token 与 design.css 一一对应。
- **SC-010**：`prefers-reduced-motion: reduce` 下位移与过渡关闭；所有可点元素触达尺寸 ≥44×44px。

---

## 9. 决策点（2026-09-08 负责人拍板）

| # | 决策 | 选项 | 裁决 |
|---|---|---|---|
| **D1** | 视觉基调 | A. Apple 系统色 + Action Blue ／ B. 严格单色原教旨 ／ C. 保留 Stripe 靛紫 | **A**——`#0066cc` 本身就是 HIG Accessible Blue，与 Green/Red/Orange 同属一套系统色板；「单强调色」约束品牌识别，语义色用于结果反馈，二者作用于不同元素类别（§1.3） |
| **D2** | 改造深度 | A. IA 重构 + 视觉重做 ／ B. 只改视觉，DOM/JS 冻结 | **A**——P1–P12 多为结构性问题，不重排 DOM 只能缓解不能根治；硬底线为行为零破坏（NFR-006） |
| **D3** | 外部依赖 | A. 保持零依赖（纯系统字体栈） ／ B. 自托管 Inter woff2 入库 | **A**——macOS 上 `-apple-system` 直接取真 SF Pro，Windows 回退雅黑；引入 ~100KB 字体收益不足以破坏零依赖铁律 |
| **D4** | 动效 | A. 纯 CSS + 原生 JS 全套 ／ B. 仅微反馈 ／ C. 无动效 | **A**——满足「动效自然流畅」，零依赖，且支持 `prefers-reduced-motion` 降级 |
| **D5** | 收银台主 CTA | A. 近黑胶囊（对标 Apple Pay） ／ B. Action Blue 胶囊 | **A**——HIG 明定 Apple Pay 按钮只有 black/white/white-outline 且禁止自定义；蓝色留给导航动作（§4.3.3） |
| **D6** | 落地形式 | A. 新建 spec 024 ／ B. 修订 020 为 v2 | **A**——020 已合入 master 且是完整历史决策，重写会抹掉演进记录；024 显式 `Supersedes` 其视觉基调部分 |

---

## 10. 依赖与前置

- 无后端/数据库依赖；改动仅限 `deployment/mock-channel-web/src/main/resources/static/`（+ 新增 `app.js`）与 `docs/design/DESIGN.md`。
- **前置约束**：实现分支必须从**已含本 Spec 的 master** 拉出；实现前 `git log --oneline -5 -- deployment/mock-channel-web` 核对近期触碰记录，避免与 022/023 的 static 改动冲突。
- 素材：[VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（MIT）· [`design-md/apple/DESIGN.md`](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/apple/DESIGN.md) · Apple HIG System Colors（v2024 无障碍变体）· Apple HIG Apple Pay 按钮规范。

---

## 11. 风险

| # | 风险 | 缓解 |
|---|---|---|
| **R1** | JS 重写误伤选择器（`#log`、`#skus`、`#qty-{skuId}`、`#t-orderNo`、`#refundRows`、`#diffBody`、`#adjBody`、`#balBody`、`#s-*`、`#m-mock/#m-live`） | **所有 id 与动态 class 拼接模式（`st-{STATUS}`、`c-{STATUS}`、`rf-{refundNo}`）保持不变**；内联 `onclick` 保留（零构建），只改 class 与 DOM 位置；回归按 SC-008 全路径冒烟 |
| **R2** | sticky + `backdrop-filter` 旧 Safari 兼容 | 加 `-webkit-` 前缀 + 无 blur 降级（NFR-003） |
| **R3** | <900px 塌单列时右栏状态卡丢失 | 用 `order` 移到顶部，不得隐藏（FR-008） |
| **R4** | 抽屉自动展开/收回干扰阅读 | 提供 pin 开关 + localStorage 记忆（FR-005） |
| **R5** | audit 台账瘦身丢信息 | 移出列进展开行，信息不删减；展开行是 Apple/Linear 通行做法 |
| **R6** | 与 022/023 分支的 static 资源冲突 | 实现分支从最新 master 拉出；rebase 时以「行为不变」为准手工合并 |
| **R7** | 语义色纪律被后续改动破坏 | FR-012 写进 DESIGN.md Do's & Don'ts 与 design.css 注释，作为 review 检查项 |

---

## 12. 关联文档

- 上游灵感：[VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（MIT）· [Apple DESIGN.md 原文](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/apple/DESIGN.md)
- **被本 Spec 部分取代**：`docs/specs/020-demo-ui-design-system/spec.md`（D1 / FR-002 / FR-006 / FR-008）
- 行为不变基线：`docs/specs/015-multi-channel-payment/`（收银台换渠道）、`docs/specs/017-accounting-audit/`（审计控制台功能）、`docs/specs/019-order-driven-refund/`（退款双号）
- 规范产出：`docs/design/DESIGN.md`（本 Spec 重写为 Apple 基底）
- 同源代理架构：ADR-0048（mock-channel-web 零服务改动代理）
- 编号衔接：`023-audit-ops-remediation` 已占用，本 Spec 为 024
