---
version: 1.0
name: PaymentArch Demo Design System
description: >
  PaymentArch 演示界面的设计系统——以 Stripe 风格为基底裁剪适配（灵感与 token 取自
  VoltAgent/awesome-design-md 的 stripe/DESIGN.md，MIT）：深海军蓝 ink、电光靛紫主色、
  weight-300 细体展示字、tnum 表格数字、pill 按钮、near-white 卡片表面与 dark 控制台双轨。
  任何 AI 编码代理在新建/修改 mock-channel-web 演示页面前 MUST 先读本文件；
  样式唯一真相源是 deployment/mock-channel-web/src/main/resources/static/design.css。

colors:
  primary: "#533afd"
  primary-deep: "#4434d4"
  primary-press: "#2e2b8c"
  primary-soft: "#665efd"
  primary-subdued: "#b9b9f9"
  brand-dark: "#1c1e54"
  ink: "#0d253d"
  ink-secondary: "#273951"
  ink-mute: "#64748d"
  canvas: "#ffffff"
  canvas-soft: "#f6f9fc"
  canvas-cream: "#f5e9d4"
  hairline: "#e3e8ee"
  hairline-input: "#a8c3de"
  success: "#15803d"
  success-soft: "#dcfce7"
  danger: "#dc2626"
  danger-soft: "#fee2e2"
  warning: "#b45309"
  warning-soft: "#fef3c7"
  info: "#1d4ed8"
  info-soft: "#dbeafe"

typography:
  font-stack: "system-ui, -apple-system, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
  mono-stack: "ui-monospace, 'SF Mono', 'Cascadia Mono', Consolas, monospace"
  display-lg: { size: 26px, weight: 300, lineHeight: 1.12, letterSpacing: -0.5px }
  heading-md: { size: 18px, weight: 400, lineHeight: 1.4, letterSpacing: -0.2px }
  heading-sm: { size: 15px, weight: 500, lineHeight: 1.4 }
  body-md: { size: 13px, weight: 400, lineHeight: 1.6 }
  caption: { size: 12px, weight: 400, lineHeight: 1.6 }
  micro-cap: { size: 11px, weight: 500, letterSpacing: 0.5px }
  amount-xl: { size: 28px, weight: 300, letterSpacing: -0.5px, feature: tnum }

rounded: { xs: 4px, sm: 6px, md: 8px, lg: 12px, pill: 9999px }
spacing: { xs: 4px, sm: 8px, md: 12px, lg: 16px, xl: 24px, xxl: 32px }
---

# PaymentArch 演示设计系统（Feature 020）

> **消费方式**：所有演示页面通过 `<link rel="stylesheet" href="/design.css">` 引用 token 层；
> 页面内联 `<style>` 只允许保留该页特有布局。改样式前先读本文件；token 值与 design.css
> 一一对应，两处必须同步改。

## 1. 视觉主题与气质

金融基础设施的「安静的专业感」：白与近白表面承载内容，靛紫只在 CTA 与强调处出现（一个视觉区域一个实心按钮）；数字是主角——所有金额、单号、计数一律表格数字（`tnum`）逐位对齐。对外形象页（收银台、门户）用暖色渐变横幅注入品牌温度；内部控制台（demo、audit）保持数据密集的冷静，日志面板用 dark shell 区隔「机器输出」。

## 2. 色板与语义角色

| Token | 值 | 角色 |
|---|---|---|
| `--pa-primary` | `#533afd` | CTA 实心按钮、链接强调、渐变锚点。**克制使用** |
| `--pa-primary-deep` / `--pa-primary-press` | `#4434d4` / `#2e2b8c` | hover / active 态 |
| `--pa-primary-subdued` | `#b9b9f9` | 软标签底色 |
| `--pa-brand-dark` | `#1c1e54` | dark shell（日志面板、横幅深端） |
| `--pa-ink` / `--pa-ink-2` / `--pa-mute` | `#0d253d` / `#273951` / `#64748d` | 正文 / 次级 / 辅助文字。**正文永不用纯黑** |
| `--pa-canvas` / `--pa-canvas-soft` | `#ffffff` / `#f6f9fc` | 卡片表面 / 页面底色 |
| `--pa-hairline` / `--pa-hairline-input` | `#e3e8ee` / `#a8c3de` | 卡片边框 / 输入框边框 |
| `--pa-success`(+soft) | `#15803d` (#dcfce7) | 支付成功、核对通过、终态良好 |
| `--pa-danger`(+soft) | `#dc2626` (#fee2e2) | 失败、拒付、BLOCKER |
| `--pa-warning`(+soft) | `#b45309` (#fef3c7) | 处理中、UNKNOWN、挂账 |
| `--pa-info`(+soft) | `#1d4ed8` (#dbeafe) | 调整、受理、中性提示 |

### 状态语义映射表（FR-004，`.st-*` / `.c-*` 共同消费）

**守则：新增业务状态必须先在此表登记，映射缺失视为缺陷。**

| 语义 | 业务状态 |
|---|---|
| success | SUCCEEDED · PAID · AVAILABLE · BALANCED · VERIFIED · CLOSED · PARTIALLY_SUCCEEDED |
| danger | FAILED · CANCELLED · REJECTED · REVOKED · BLOCKER · HAS_DIFFERENCE · PENDING_REVIEW |
| warning | PENDING_PAYMENT · PROCESSING · UNKNOWN · RECHECKING · REQUESTED · SUSPENDED · PENDING · 差异严重度 MAJOR |
| info | ADJUSTED · CREATED |
| 中性特例 | 差异严重度 MINOR（mute 灰）——严重度非业务状态，MAJOR 归 warning、MINOR 归中性 |

## 3. 字阶（含中文字体栈）

系统字体栈 `--pa-font`（macOS 优先苹方——有 300 细体字重；Windows 回退微软雅黑，展示字自动升 400，允许平台差异）。等宽 `--pa-mono` 只用于代码/SQL/日志。

| Token | 规格 | 用途 |
|---|---|---|
| `display-lg` | 26px / 300 / -0.5px | 页面主标题、收银台金额（配 tnum） |
| `heading-md` | 18px / 400 | 区块标题 |
| `heading-sm` | 15px / 500 | 卡片标题、表头 |
| `body-md` | 13px / 400 | 正文、表格 |
| `caption` | 12px / 400 | 注释、辅助说明 |
| `micro-cap` | 11px / 500 / +0.5px | 全大写眉题（配合字距） |
| `.tnum` 工具类 | — | **任何金额/单号/数量列 MUST 加 `tnum`** |

## 4. 圆角与间距

圆角：输入框 6px（`sm`）、紧凑卡 8px（`md`）、卡片 12px（`lg`）、**按钮一律 pill（9999px）**。间距 8px 基数：卡片内边距 24px，区块间距 16px，控件间隙 8–12px。

## 5. 组件样式

- **`.pa-btn`（primary pill）**：`--pa-primary` 底 + 白字，8px 16px，pill；hover `--pa-primary-deep`，active `--pa-primary-press`。
- **`.pa-btn--ghost`**：白底 + `--pa-hairline-input` 边 + ink 字；**`.pa-btn--danger`**：`--pa-danger` 底白字（仅破坏性操作）；`disabled` 45% 透明度。
- **`.pa-card`**：白底、1px `--pa-hairline`、12px 圆角、24px 内边距、阴影 Level 1。
- **`.pa-log`**：dark shell——`--pa-brand-dark` 底、浅色字、mono 字体、12px 圆角；日志行语义色 `.ok`/.err`/.warn`。
- **`.st-*` / `.c-*` 状态 chip**：soft 底 + 语义深色字 + pill 圆角，按 §2 映射表取色。
- **输入框**：白底、1px `--pa-hairline-input`、6px 圆角、focus 边框换 `--pa-primary`。

## 6. 阴影体系

| Level | 值 | 用途 |
|---|---|---|
| 0 | 无 | 默认表面 |
| 1 | `rgba(0,55,112,.08) 0 1px 3px` | 卡片 |
| 2 | `rgba(0,55,112,.08) 0 8px 24px` | 浮动面板、模态 |
| 3 | 渐变横幅 | 品牌深度靠颜色不靠阴影 |

## 7. Do's and Don'ts

**Do**
- 金额/单号/数量一律 `tnum`；金额格式统一「`¥` + 千分位 + 币种弱化小字」。
- 一个视觉区域只放一个实心靛紫按钮；其余用 ghost。
- 收银台/门户用渐变横幅（CSS 线性渐变简化 mesh，零图片）；控制台页保持冷静白面。
- emoji 一律换 12–16px 内联 SVG（stroke 风格，`currentColor` 取色）。
- JS 依赖的动态类名拼接模式（`st-{STATUS}`、`c-{STATUS}`、`rf-` 前缀等）保持不变，样式经 design.css 同名类提供。

**Don't**
- 不引入任何外部依赖：无 CDN、无 webfont、无图片、无 npm（断网演示硬要求）。
- 不改页面 JS 行为与接口契约；mock-channel-web Java 代码零改动。
- 不用 Tailwind 默认蓝 `#2563eb` / 默认绿红硬编码——一切颜色走 token。
- 不用靛紫做正文颜色（它是 CTA/链接色）；正文用 ink 系。
- 新增业务状态不登记 §2 映射表直接上页面（缺失回退 warning 色并 console.warn）。

## 8. 响应式

桌面优先（`max-width` 容器居中），<768px 时卡片栅格塌成单列、按钮触达 ≥40px；不做像素级移动端还原。

## 9. Agent 提示指南

新建/改造演示页面时，向 AI 提供的提示模板：

> 读取 `docs/design/DESIGN.md` 与 `static/design.css`，遵循其 token 与组件规范。
> 页面通过 `<link href="/design.css">` 消费样式，内联样式仅限页面特有布局；
> 保持现有 JS 行为与动态类名拼接不变；金额/单号加 `tnum`；禁新增外部依赖。

## 关联

- 灵感来源：[VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md)（MIT）· `design-md/stripe/DESIGN.md`
- 规范出处：`docs/specs/020-demo-ui-design-system/spec.md`（FR-001~009、NFR-001~005）
