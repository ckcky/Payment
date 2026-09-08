# Spring AI 技术预研与选型报告

**版本**：Draft 1.0
**日期**：2026-09-08
**状态**：**仅预研讨论，未改任何代码；若实施需另起 spec**
**定位**：评估「是否在本项目接入 Spring AI」——功能调研、场景匹配、收益与取舍、同类方案对比、选型结论与分阶段路线图。
**项目基线**：Spring Boot 3.5.15 / Spring Cloud 2025.0.3 / Java 21 / 零 AI 代码（全仓无 spring-ai / openai / langchain 依赖）。

---

## 1. Spring AI 是什么

Spring 官方（Broadcom）的 AI 应用框架，目标是把「调用大模型」变成 Spring 开发者的日常范式：自动配置 + 依赖注入 + Fluent API，与 Boot / Security / Micrometer / Cloud 体系天然打通。

版本脉络：2025-05 发布 1.0 GA → **2025-11 发布 1.1 GA**（850+ 改进、354 项新功能）→ 2.0 处于里程碑阶段（绑 Spring Boot 4，需 Java 21+）。

### 1.1 核心功能矩阵（1.1 GA 现状）

| 能力 | 内容 | 成熟度 |
|---|---|---|
| ChatClient | 流式 Fluent API，同步/流式调用，统一 20+ 模型商抽象 | ★★★★★ 稳定 |
| Tool Calling | `@Tool` 注解声明工具，模型自主决策何时调用 | ★★★★☆ |
| **MCP** | 1.1 头号特性：`@McpTool / @McpResource / @McpPrompt` 注解式编程，STDIO / SSE / Streamable HTTP 传输，client + server 双端 starter | ★★★★★ 生态最强 |
| RAG | `QuestionAnswerAdvisor` 开箱即用，15+ 向量库（含 **Redis**、PGVector、Milvus） | ★★★☆☆ 骨架全、细节自选 |
| Advisors | 拦截器链：会话记忆、日志、安全、RAG、递归自反思（1.1 新增 RecursiveAdvisor） | ★★★★☆ 企业横切利器 |
| Prompt Caching | Anthropic / Bedrock 提示缓存，成本最高降 90% | ★★★★☆ |
| 结构化输出 | 模型输出直接映射 Java Record / Bean | ★★★★★ |
| 可观测 | Micrometer 指标 + 链路追踪自动接入 | ★★★★★ 独有优势 |
| 多 Agent 编排 | 弱项，社区 preview 项目（Spring AI Agents） | ★★☆☆☆ 演进中 |

---

## 2. 本项目需要接入吗

**结论：不需要"业务接入"，但值得"学习接入"——且契合度异常高。**

1. **项目是学习项目，无真实 AI 业务痛点**。不存在"必须上 AI"的需求，接入的正当性是学习价值与演示价值。
2. **但契合度是教科书级别的**：
   - 纯 Spring Cloud 技术栈 → Spring AI 自动配置 / Micrometer / Security 全部即插即用，零范式冲突。
   - 已用 Spring Cloud Alibaba（Nacos）→ 接国产模型可走 **spring-ai-alibaba**（阿里官方，通义/百炼一等支持），同源生态。
   - 基础设施已有 **Redis** → Spring AI 支持 Redis 向量库，做 RAG **不需要新增任何中间件**。
   - 「微服务 + MCP」是当前微服务架构 × AI 的最热结合点，与项目学习主线（服务拆分、Feign、幂等、对账）完全同向。
3. **版本匹配好**：Boot 3.5.15 + Java 21 正落在 Spring AI 1.1 的甜区。**不要追 2.0**（绑 Boot 4，全项目升级代价大），锁定 1.1.x。

---

## 3. 候选场景评估

| 场景 | 学习内容 | 基础设施成本 | 与项目契合 | 优先级 |
|---|---|---|---|---|
| **① MCP Server 暴露支付能力** | MCP 协议、工具设计、「AI 可调业务能力」的边界 | 零新组件（复用现有服务端点） | 极高——把 order / payment 内部端点包成 `@McpTool`，外部 Agent 即可"查订单 / 查支付状态"，是微服务 + AI 的最佳示范 | **P0** |
| **② 对账差错智能助手** | Tool Calling + 结构化输出 + 规则引擎与 AI 分工 | 零新组件（复用 reconciliation 差错数据） | 高——`AdjustmentPolicy` 规则引擎旁挂 AI 差错分类 / 调账建议，体现"规则管确定性、AI 管模糊性" | P1 |
| **③ 项目文档 RAG 问答** | Embedding、向量检索、RAG 全链路 | **Redis 向量库（已有组件）** | 高——喂 docs/specs + 67 篇 ADR，做"问这个项目架构"机器人，对学习项目有元价值 | P1 |
| **④ 演示页 AI 助手** | 流式输出（SSE）、前后端联调 | 零新组件（复用 ① 的 MCP 工具做后端） | 中——audit / portal 页嵌浮窗，演示亮眼；应**复用 ①②③ 的能力**而非单独做 | P2（搭便车） |

**推荐叙事线**：① 先把支付能力暴露为 MCP Server → ②③ 各自练 Tool Calling 与 RAG → ④ 演示页助手直接消费前面成果。四个场景串成一条完整学习路径，而非四个孤立 demo。

---

## 4. 好处与取舍

### 4.1 好处

- **学习价值密度高**：一套框架覆盖 Tool Calling / RAG / MCP / 流式 / 结构化输出五大主流 LLM 工程主题。
- **零新增基础设施**：Redis 当向量库、现有端点当工具、Nacos / 监控体系直接复用。
- **工程化范式白送**：Micrometer 指标、Advisor 审计链、自动配置——正是项目一直练的"企业级"口味。
- **架构叙事升级**：从"微服务学习项目"升级为"微服务 + AI 工程化学习项目"。

### 4.2 取舍 / 风险

- **复杂度税**：每个落点都要引入 Prompt 设计、幻觉兜底、非确定性测试等新课题，会摊薄支付主线的精力。
- **国产模型非一等公民**：Spring AI 主项目对 DeepSeek / 通义需走 OpenAI 兼容模式或社区 starter；缓解方案为 spring-ai-alibaba（阿里官方维护，与现有 SCA 栈同源）。
- **2.0 升级断层**：将来升 Boot 4 / Spring AI 2.0 时 API 有 breaking change；学习项目可接受，锁定 1.1.x 即可。
- **外部 API 依赖**：DeepSeek API 便宜但非免费；测试用例受网络影响（可用 mock / 录制缓解）。
- **安全边界新课题**：MCP 把内部端点暴露给 LLM 决策，工具白名单 / 参数校验 / 只读原则必须自己把关（本项目"永不上生产"裁决下风险可控，反而适合当学习素材）。

---

## 5. 同类替代方案对比

| 方案 | 优势 | 劣势 | 适配度判断 |
|---|---|---|---|
| **Spring AI 1.1** | Spring 原生、MCP 最强、可观测白送、与现有栈零摩擦 | 国产模型需绕道、多 Agent 弱、2.0 将至有升级债 | ✅ **推荐** |
| spring-ai-alibaba | 通义/百炼一等支持、与 SCA Nacos 同源 | 本质是 Spring AI 扩展而非替代，跟随主项目节奏 | ✅ **推荐搭配使用**（作为模型接入层） |
| LangChain4j | 国产模型一等公民、RAG 最成熟（30+ 向量库）、多 Agent 领先、框架中立 | MCP 支持弱、无 Boot 自动配置原生感、可观测要自补 | ⚠️ 未来若主攻多 Agent 编排再考虑；当前 MCP 优先的目标下不占优 |
| Solon AI | 国产模型原生、三级 Agent 体系、轻量 | 生态/社区小，换框架范式成本大 | ❌ 为学习 AI 换 Web 框架不值 |
| 裸调 OpenAI SDK / HTTP | 零框架依赖、最透明 | 一切自己造（重试/流式/工具循环/RAG），学的是"造轮子"而非"工程化" | ❌ 可作对照实验，不做主线 |

---

## 6. 选型结论

> **接入 Spring AI 1.1.x + spring-ai-alibaba（模型层接 DeepSeek / 通义），锁定 Boot 3.5 不追 2.0；按 MCP Server → 对账助手 / 文档 RAG → 演示页助手 的次序，分独立 spec 推进。**

- **模型接入双轨**：DeepSeek 走 OpenAI 兼容模式（`spring-ai-openai` 改 base-url），或 spring-ai-alibaba 接百炼；二者低成本可切换——模型可移植性本身也是学习点。
- **架构纪律**：AI 能力收在**独立新模块 / 新服务**，不侵入 9 个领域服务；业务服务只通过 MCP / Feign 被调用，保持现有架构干净。
- **节奏纪律**：每个场景独立 spec（沿用 spec 019 先例：先 spec 合 master 再实现），互不阻塞，随时可停。

---

## 7. 若后续实施的路线图（仅方向，非本次交付）

1. **spec N1 — MCP Server 模块**：暴露查订单 / 查支付状态 / 查退款 3 个只读工具，用 MCP Inspector 或 Claude Desktop 验证调用。
2. **spec N2 — 对账差错智能助手**：差错记录 → AI 分类 + 调账建议（仅建议不落库），与 `AdjustmentPolicy` 规则引擎对比输出。
3. **spec N3 — 文档 RAG**：specs / ADR 切片入 Redis 向量库，ChatClient + QuestionAnswerAdvisor 问答。
4. **spec N4 — 演示页 AI 助手浮窗**：复用 N1–N3 工具与知识库，SSE 流式输出。

### 关键文件（实施时会触碰）

- `pom.xml`（根）——新增 spring-ai BOM / 模块声明
- `reconciliation-service/.../audit/domain/AdjustmentPolicy.java`——对账助手的规则引擎对照物
- `reconciliation-service/.../audit/api/AuditController.java`——差错人工处理入口（AI 建议的挂点）
- `deployment/mock-channel-web/src/main/resources/static/`（audit.html 等 4 页 + app.js）——演示页助手落点
- `docker-compose.yml`——Redis 已存在，向量库零新增

### 明确不做（本次范围外）

- 不写任何代码、不动 pom、不建 spec 文档
- 不评估多 Agent 编排（Spring AI 弱项，等生态成熟或届时再评估 LangChain4j）
