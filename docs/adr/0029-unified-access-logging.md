<a id="adr-0068"></a>

# ADR-0068: 统一访问日志——结束时单条 ACCESS、固定格式含服务名与异步 MDC 传播修复（spec 021 立项）

- 状态：✅ **Accepted**（2026-09-07 负责人拍板 D1~D7；**代码未实施**，任务见 [spec 021 tasks](../specs/021-unified-access-logging/tasks.md)）
- 关联：ADR-0022（业务单号与雪花 traceId 同族的关联 ID 体系；MDC traceId 沿用 `X-Trace-Id` 约定）、Constitution §6（跨服务调用用 traceId 串联、资金审计单列）、spec 017（FINANCIAL_AUDIT 审计流先例）、spec 021（[spec](../specs/021-unified-access-logging/spec.md) / [plan](../specs/021-unified-access-logging/plan.md)）
- 需求源头：负责人 2026-09-07 日志规范化——「每个请求的入口和出口都要加上日志，入口打 request 完整报文，出口打 response 报文 + 耗时毫秒数」「日志格式固定一下 [服务名]」「看看业内比较先进的做法」；第二轮修正为「结束时一条 ACCESS」，并要求脱敏只留桩；追问「系统跑起来之后怎么看日志，不可能一个文件一个文件翻」。

## 背景

现状核实（G1~G5，证据见 [spec 021 §当前代码现实](../specs/021-unified-access-logging/spec.md)）：全仓无任何请求级访问日志（无 ContentCaching/`@Aspect` 先例），排障只能靠业务日志反推；logback pattern 无服务名，14 个服务日志聚合后无法区分来源；4 个 `@Scheduled` 与 `ReliabilityConfig` 线程池不传播 MDC，后台日志没有 traceId（链路在异步处断开）；10 服务 10 个日志文件无任何查看工具；报文日志无截断/脱敏/开关规范。

**业内调研（2026-09-07）**：Spring `CommonsRequestLoggingFilter` 只打请求太弱；主流实践是 `OncePerRequestFilter` + `ContentCachingRequest/ResponseWrapper`（完整报文 + 状态码 + 全链路耗时）；日志形态有「结束一条结构化 ACCESS」与「IN/OUT 两条」两派；阿里《Java 开发手册》要求敏感信息脱敏与异常带堆栈；集中式采集以 Loki+Promtail+Grafana 为 ELK 的轻量替代。**共识：报文日志必须配截断、脱敏钩子、排除路径与总开关，否则上线即事故。**

## 决策（负责人 2026-09-07 逐条拍板）

1. **实现方式：Filter + ContentCaching 包装**（`OncePerRequestFilter` + `ContentCachingRequestWrapper/ResponseWrapper`）。计时口径为 Filter 层整链路耗时（含序列化）；一个过滤器覆盖全部端点；不采用 AOP（拿不到原始报文）与 CommonsRequestLoggingFilter（无响应/耗时）。

2. **日志条数：结束时一条 ACCESS**（负责人第二轮由 IN/OUT 两条修正为单条）：请求结束在 finally 中落一条 INFO——`ACCESS method uri status costMs req resp`（traceId 经 MDC 由 pattern 输出）；异常路径同样必打（resp 为 GlobalExceptionHandler 的 JSON）。`ACCESS_LOG` 专用 logger 名，可独立路由。**`copyBodyToResponse()` 必须执行**，保证客户端响应完整。

3. **脱敏只留桩**：`SensitiveBodyMasker` 接口（`mask(contentType, body)`）+ `PassThroughBodyMasker` 透传默认实现；`AccessLogFilter` 固定调用钩子。本期不实现真脱敏，将来启用（JSON 字段级 mask）只换实现零侵入。

4. **报文口径**：GET 全量打（无 body 记 `req=-`）；请求/响应体超 **4KB** 截断加 `...(truncated,total=N)`；排除 `/actuator/**`；multipart/二进制不打 body；`common.access-log.enabled=false` 一键关闭。

5. **格式固定含服务名**：logback `<springProperty source="spring.application.name">` 注入 context 属性，pattern 追加 `service=` 字段（无服务名回退 unknown）；全服务日志行格式统一，服务代码零改动。

6. **异步 MDC 传播修复（顺带，D7）**：新增 `MdcTaskDecorator`（捕获/恢复/清理）；`ReliabilityConfig` 线程池装饰；4 个 Scheduler（ChannelQuery / TimeoutScan / OrderTimeout / Audit）入口补 `TraceContext.getOrCreate()` + MDC——定时任务日志纳入 traceId 体系，与既有 `X-Trace-Id` 出入站传播（`TraceIdFilter` / `TraceIdRequestInterceptor`）闭环。

7. **日志查看：本期聚合脚本，集中式采集后续期**：`deployment/demo/tail-logs.sh`（全栈合并实时视图，`[服务名]` 前缀）+ `trace-grep.sh <traceId>`（跨服务捞全链路）；Loki + Promtail + 现有 Grafana 集中式采集列后续期（负责人知悉）。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| IN/OUT 两条日志（负责人最初要求） | 第二轮修正为单条：一条 ACCESS 全字段天然对齐、检索一条顶两条、不会两行之间插行 |
| Spring `CommonsRequestLoggingFilter` | 只有请求、无响应与耗时，扩展不动 |
| AOP 切 Controller | 拿不到原始 HTTP 报文与响应体写出时机；业内不推荐做报文日志 |
| 本期实现真脱敏（JSON 字段级） | 负责人拍板只留桩：钩子先行，避免阻塞日志主干落地 |
| 本期上 Loki+Promtail 集中采集 | 独立基础设施，另行排期；聚合脚本已覆盖 80% 日常场景 |
| `SchedulingConfigurer` 全局装饰 Scheduler | 侵入各服务配置类；入口显式补齐最直白（plan §4） |

## 影响

- **正影响**：每个请求「打了什么、回了什么、多久」一条日志可查；日志行自带服务名，多服务聚合可读；异步链路 traceId 闭环（G3 顺带消除）；日志查看从翻文件变为两条命令；脱敏钩子就位，将来启用零侵入。
- **代价**：common-core 新增 accesslog 包与一个属性类（项目首个 `@ConfigurationProperties`）；日志量上升（4KB 截断 + 开关兜底）；Filter 定序改动触及 `TraceIdFilter` 注册方式（裸 bean → FilterRegistrationBean，行为不变）。
- **不做**：真脱敏实现、Loki 集中采集（后续期）、AOP 方法级日志、FileAppender 落盘（沿用控制台 + nohup 重定向形态）。
