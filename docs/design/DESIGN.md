---
version: 2.0
name: PaymentArch Demo Design System (Apple)
description: >
  PaymentArch 演示界面的设计系统——以 Apple 设计语言为基底（灵感与 token 取自
  VoltAgent/awesome-design-md 的 apple/DESIGN.md，MIT，并结合 Apple HIG System Colors v2024
  无障碍变体）：Action Blue #0066cc 单一强调色、HIG 无障碍语义色仅用于结果状态、
  零装饰性渐变、卡片不投影、display 用 600 字重、tnum 表格数字、全幅 tile 靠表面色切换分隔。
  任何 AI 编码代理在新建/修改 mock-channel-web 演示页面前 MUST 先读本文件；
  样式唯一真相源是 deployment/mock-channel-web/src/main/resources/static/design.css。
  本版本取代 v1.0（Stripe 靛紫基底），依据 docs/specs/024-demo-ui-apple-redesign/spec.md。

colors:
  # 行动色（唯一强调色；HIG Accessible Blue）
  action: "#0066cc"
  action-focus: "#0071e3"
  action-soft: "#e8f2fd"
  # 中性面
  canvas: "#ffffff"
  canvas-2: "#f5f5f7"
  ink-wash: "#fafafc"
  ink: "#1d1d1f"
  ink-2: "#6e6e73"
  ink-3: "#8e8e93"
  hairline: "rgba(0,0,0,.08)"
  hairline-strong: "rgba(0,0,0,.16)"
  dark: "#1d1d1f"
  # 语义色（HIG 无障碍变体，仅用于结果/状态，绝不用于可交互元素）
  ok: "#248a3d"
  ok-soft: "#e8f5ec"
  bad: "#d70015"
  bad-soft: "#fdeaea"
  warn: "#c93400"
  warn-soft: "#fdeee7"
  note: "#b25000"
  note-soft: "#fdf4e3"

typography:
  font-stack: "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif"
  mono-stack: "'SF Mono', ui-monospace, Menlo, Consolas, monospace"
  display: { size: 40px, weight: 600, lineHeight: 1.1, letterSpacing: -0.02em }
  title: { size: 28px, weight: 600, lineHeight: 1.15, letterSpacing: -0.01em }
  headline: { size: 21px, weight: 600, lineHeight: 1.2 }
  body: { size: 15px, weight: 400, lineHeight: 1.5 }
  caption: { size: 13px, weight: 400, lineHeight: 1.4 }
  micro: { size: 12px, weight: 400, lineHeight: 1.4 }
  money: { size: 40px, weight: 600, letterSpacing: -0.02em, feature: tnum }
  # 例外条款：Apple body 为 17px，本项目控制台数据密集，统一下调 2px 至 15px

rounded: { utility: 8px, input: 11px, card: 18px, pill: 9999px }
spacing: { xs: 4px, sm: 8px, md: 12px, lg: 24px, xl: 32px, xxl: 64px }

motion:
  ease: "cubic-bezier(.4,0,.2,1)"
  ease-sheet: "cubic-bezier(.32,.72,0,1)"
  duration-press: 120ms
  duration-hover: 160ms
  duration-fast: 200ms
  duration-base: 240ms
  duration-sheet: 280ms
  duration-slow: 350ms

breakpoints: { audit: 1200px, demo-split: 900px, portal-single: 734px, compact: 480px }
touch-target: 44px
---

# PaymentArch 演示设计系统（Feature 024 · Apple 基底）

> **消费方式**：所有演示页面通过 `<link rel="stylesheet" href="/design.css">` 引用 token 层，
> 并通过 `<script src="/app.js"></script>` 引用共享行为层（nav / 日志抽屉 / toast / 格式化）。
> 页面内联 `<style>` 只允许保留该页特有布局。改样式前先读本文件；token 值与 design.css
> 一一对应，两处必须同步改。

## 1. 视觉主题与气质

**Apple 式的克制**：大量留白、表面色差建立的层级、清晰的排版节奏。页面底色 `#f5f5f7`，卡片 `#ffffff` + 1px 发丝线 + 18px 圆角——**卡片不投影**，层级靠「表面色差 + 发丝线」而非阴影。数字是主角：金额、单号、计数一律 `tnum` 逐位对齐。

**强调顺序**（Apple 铁律）：**先换表面（浅→深 tile），再考虑加 chrome**。portal 的分区之间不加边框，靠 `#ffffff` ↔ `#f5f5f7` 交替当分隔线。

**零装饰性渐变**：Apple DESIGN.md 明确指出「Apple 是罕见的零渐变 token 的奢侈品牌站点」。全站不使用装饰性渐变（v1.0 收银台的 `.pa-mesh` 五段渐变横幅已移除）。

**暗色只给机器输出**：`#1d1d1f` 深色面仅用于日志抽屉与代码块，形成「机器输出 vs 人读内容」的区隔。整页不做 dark/light 切换。

## 2. 色板与语义角色

| Token | 值 | 角色 |
|---|---|---|
| `--pa-action` | `#0066cc` | **唯一强调色**：链接、分段控件选中、focus 环、导航动作 |
| `--pa-action-focus` | `#0071e3` | hover / active |
| `--pa-action-soft` | `#e8f2fd` | 选中行底色、软标签底 |
| `--pa-canvas` / `--pa-canvas-2` | `#ffffff` / `#f5f5f7` | 卡片表面 / 页面底色（tile 交替） |
| `--pa-ink-wash` | `#fafafc` | frost 导航底、悬停面 |
| `--pa-ink` / `--pa-ink-2` / `--pa-ink-3` | `#1d1d1f` / `#6e6e73` / `#8e8e93` | 正文 / 次级 / 辅助 |
| `--pa-hairline` / `-strong` | `rgba(0,0,0,.08)` / `rgba(0,0,0,.16)` | 卡片边框 / 强分隔 |
| `--pa-dark` | `#1d1d1f` | 日志抽屉、代码块 |
| `--pa-ok`(+soft) | `#248a3d` (#e8f5ec) | 成功、核对通过、终态良好 |
| `--pa-bad`(+soft) | `#d70015` (#fdeaea) | 失败、拒付、BLOCKER |
| `--pa-warn`(+soft) | `#c93400` (#fdeee7) | 处理中、UNKNOWN、挂账 |
| `--pa-note`(+soft) | `#b25000` (#fdf4e3) | 注意、待复核 |

### 2.1 核心纪律：语义色 vs 行动色（**最重要的一条**）

Apple DESIGN.md 要求「单一强调色，无第二个品牌色」，而支付/对账界面又必须有成功/失败/处理中的语义色。二者**不冲突**，因为它们作用于**不同元素类别**：

- **可交互元素**（按钮 / 链接 / 输入框 / 分段控件）→ 只用 **Action Blue 或中性黑**
- **结果状态**（成功 / 失败 / 处理中 / 差异严重度）→ **HIG 语义色 + 图标 + 文案**

依据：`#0066cc` 本身就是 HIG **Accessible Blue**，与 Green `#248A3D` / Red `#D70015` / Orange `#C93400` / Yellow `#B25000` 同属 Apple 系统色板的无障碍变体——它们是一套色板，不是「第二个品牌色」。

> 这条纪律反过来提升可用性：**「能点的」和「已发生的」一眼可分**。
> 收银台主 CTA 例外用近黑（见 §5.1），依据 Apple HIG：Apple Pay 按钮只有 black / white / white-outline，**无蓝色版本**且明令禁止自定义。

### 2.2 状态语义映射表（FR-015，`.st-*` / `.c-*` 共同消费）

**守则：新增业务状态必须先在此表登记，映射缺失视为缺陷。**

| 语义 | 业务状态 |
|---|---|
| ok | SUCCEEDED · PAID · AVAILABLE · BALANCED · VERIFIED · CLOSED · PARTIALLY_SUCCEEDED |
| bad | FAILED · CANCELLED · REJECTED · REVOKED · BLOCKER · HAS_DIFFERENCE · PENDING_REVIEW |
| warn | PENDING_PAYMENT · PROCESSING · UNKNOWN · RECHECKING · REQUESTED · SUSPENDED · PENDING · 差异严重度 MAJOR |
| info(action) | ADJUSTED · CREATED |
| 中性特例 | 差异严重度 MINOR（ink-3 灰）——严重度非业务状态，MAJOR 归 warn、MINOR 归中性 |

未命中映射时回退 warn 色并 `console.warn`，便于发现漏登记。

## 3. 字阶（含中文字体栈）

系统字体栈 `--pa-font`（macOS 上 `-apple-system` 直接取真 SF Pro；Windows 回退微软雅黑，无 300 字重时自动升 400，允许平台差异）。等宽 `--pa-mono` 只用于代码 / SQL / 日志 / 单号。

| Token | 规格 | 用途 |
|---|---|---|
| `display` | 40px / 600 / -0.02em | 门户 hero、收银台金额 |
| `title` | 28px / 600 / -0.01em | tile 标题 |
| `headline` | 21px / 600 | 区块标题 |
| `body` | 15px / 400 / 1.5 | 控制台正文（**例外**：Apple 为 17px，本项目数据密集下调 2px） |
| `caption` | 13px / 400 | 表头、辅助 |
| `micro` | 12px / 400 | fine-print、说明 |
| `.tnum` 工具类 | — | **任何金额/单号/数量列 MUST 加 `tnum`** |

**weight 500 故意缺席**（阶梯 300 / 400 / 600 / 700），遵 Apple。展示字用 **600 而非 700**。

## 4. 圆角与间距

- 圆角：**CTA / chip / 分段控件 `9999px`**；卡片 `18px`；小卡 / 输入框 `11px`；utility 按钮 `8px`。**不混用**。
- 间距 8px 基数：卡片内 `24px`；区块间 `64px`；控件间 `12px`；chip 内 `4px 10px`。
- 区块节奏：section 纵向 padding `64px`；标题到内容 `24px`。
- 容器：`main.pa-main` max-width `1080px`（数据密集的 audit 放宽 `1200px`），左右 padding `24px`。

## 5. 组件样式

### 5.1 按钮

- **`.pa-btn`**（primary pill）：`--pa-action` 底 + 白字，pill，高 44px；hover `--pa-action-focus`；按下 `transform: scale(.97)`（120ms）。
- **`.pa-btn--dark`**（承诺性动作）：近黑 `#1d1d1f` 底 + 白字 + 胶囊，**用于收银台主 CTA**，依据 HIG Apple Pay 按钮规范（无蓝色版本、禁止自定义）。
- **`.pa-btn--ghost`**：透明底 + `--pa-hairline-strong` 边 + ink 字。
- **`.pa-btn--danger`**：`--pa-bad` 底白字，仅破坏性操作。
- `disabled`：45% 透明度 + `cursor: not-allowed`。
- **一个视觉区域只允许一个实心按钮**，其余一律 ghost。

### 5.2 表面与深度

- **`.pa-card`**：白底、1px `--pa-hairline`、18px 圆角、24px 内边距、**无阴影**。
- **`.pa-nav`**：48px sticky，`rgba(250,250,252,.72)` + `backdrop-filter: saturate(180%) blur(20px)`（带 `-webkit-` 前缀与无 blur 降级）。
- **`.pa-drawer`**（日志抽屉）：底部固定，折叠 44px / 展开 240px；`--pa-dark` 底 + mono + 语义色日志行；阴影 `0 -8px 30px rgba(0,0,0,.10)`。
- **`.pa-toast`**：阴影 `0 8px 30px rgba(0,0,0,.14)`；成功 2.5s 自动收，**错误常驻待人工关闭**。
- **阴影只给浮动层**（抽屉 / toast / sticky 摘要条 / 模态）；**卡片、按钮、文字永不投影**。

### 5.3 其他组件

- **`.pa-seg`**（分段控件，Apple segmented control）：胶囊容器 + 灰底，选中项白色滑块（`--pa-action` 文字），240ms 位移。
- **`.st-*` / `.c-*` 状态 chip**：soft 底 + 语义深色字 + pill 圆角，按 §2.2 映射表取色，变色 200ms。
- **`.pa-table`**：表头 caption 13px / ink-2；行高 44px；hover `--pa-ink-wash`；金额列 tnum 右对齐；`.no` 单号列 mono + tnum + `--pa-action` 弱化。
- **`.pa-log`**：dark shell——`--pa-dark` 底、浅色字、mono 12px；日志行 `.ok` / `.err` / `.warn`。
- **输入框**：白底、1px `--pa-hairline-strong`、11px 圆角、focus 环 2px `--pa-action`。
- **图标**：12–16px 内联 SVG，stroke 1.5，`currentColor` 取色；不引入图标库。

### 5.4 App Shell（四页统一）

`nav.pa-nav`（48px sticky frosted）+ `main.pa-main`（1080 / 1200px 居中）+ `aside.pa-drawer`（日志抽屉）。由 `app.js` 渲染导航并按路径高亮；抽屉有输出自动展开、3s 静默自动收回、可 pin（localStorage）。

## 6. 动效（纯 CSS + 原生 JS，零依赖）

| 场景 | 时长 | 曲线 |
|---|---|---|
| 按钮按下 | 120ms | `scale(.97)` |
| hover | 160ms | `cubic-bezier(.4,0,.2,1)` |
| 抽屉展开 | 280ms | `cubic-bezier(.32,.72,0,1)`（Apple sheet） |
| 行展开 | 200ms | `grid-template-rows: 0fr→1fr` |
| 新行入场 | 240ms | opacity + translateY(8px) |
| 列表 stagger | 30ms 递增 | `animation-delay` 内联 |
| chip 变色 | 200ms | ease-out |
| 进度线 | 350ms | `cubic-bezier(.4,0,.2,1)` |
| toast | 240ms 进 / 200ms 出 | translateY |
| 页面入场 | 200ms | fade-in |

`@media (prefers-reduced-motion: reduce)` → 全局关闭位移与过渡（保留 opacity）。

## 7. Do's and Don'ts

**Do**
- 金额/单号/数量一律 `tnum`；金额格式统一「`¥` + 千分位 + 币种弱化小字」。
- 一个视觉区域只放一个实心按钮；其余用 ghost。
- **可交互元素用 Action Blue 或中性黑；语义色只用于结果/状态**。
- 分区优先用表面色切换（`#ffffff` ↔ `#f5f5f7`）当分隔线，而不是加边框。
- 触达尺寸 ≥ **44×44px**。
- JS 依赖的动态类名拼接模式（`st-{STATUS}`、`c-{STATUS}`、`rf-` 前缀等）保持不变，样式经 design.css 同名类提供。

**Don't**
- ❌ **不用装饰性渐变**（含已移除的 `.pa-mesh` 横幅）。
- ❌ **不给卡片/按钮/文字加阴影**——阴影只给浮动层。
- ❌ 不用语义色做按钮/链接（「能点的」与「已发生的」必须可分）。
- ❌ 不用 weight 500（阶梯 300 / 400 / 600 / 700）。
- ❌ 不引入任何外部依赖：无 CDN、无 webfont、无图片、无 npm（断网演示硬要求）。
- ❌ 不改页面 JS 行为与接口契约；mock-channel-web Java 代码零改动。
- ❌ 新增业务状态不登记 §2.2 映射表直接上页面。

## 8. 响应式

断点：**1200**（audit 锁宽）/ **900**（demo 双栏塌单列，右栏状态卡用 `order` 移到顶部，**不得隐藏**）/ **734**（portal 单列）/ **480**（紧凑）。

- 触达 ≥44×44px（Apple 硬指标）。
- <900px：日志抽屉改为向上滑出 60vh 的全屏 sheet。
- `backdrop-filter` 需 `-webkit-` 前缀，并保留无 blur 降级。

## 9. Agent 提示指南

新建/改造演示页面时，向 AI 提供的提示模板：

> 读取 `docs/design/DESIGN.md` 与 `static/design.css`、`static/app.js`，遵循其 token 与组件规范。
> 页面通过 `<link href="/design.css">` + `<script src="/app.js">` 消费样式与共享行为，
> 内联样式仅限页面特有布局；保持现有 JS 行为、DOM id 与动态类名拼接不变
> （`st-{STATUS}` / `c-{STATUS}` / `rf-{refundNo}`）；金额/单号加 `tnum`；
> 可交互元素只用 Action Blue 或中性黑，语义色只用于结果状态；禁新增外部依赖。

## 关联

- 灵感来源：[VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（MIT）· `design-md/apple/DESIGN.md`
- 规范出处：`docs/specs/024-demo-ui-apple-redesign/spec.md`（FR-001~015、NFR-001~006、D1~D6）
- 历史版本：v1.0 Stripe 靛紫基底，见 `docs/specs/020-demo-ui-design-system/spec.md`（其 D1 / FR-002 / FR-006 / FR-008 已被 024 取代）
- 外部参考：Apple HIG System Colors（v2024 无障碍变体）· Apple HIG Apple Pay 按钮规范
