# Acceptance: 021-unified-access-logging

> 验收执行方式与 DoD。状态：✅ Accepted（验收标准随 spec 021 拍板；执行待代码实施）。

## 验收执行方式

1. **自动化门禁**：`mvn -o clean verify -fae` 全绿。
2. **demo live 冒烟**：起 order/payment 两个服务 → curl 下单 + 支付接口 → 观察控制台日志（见用例矩阵）。
3. **异步链路核查**：触发一次定时任务（如超时扫描），确认其日志带 traceId。
4. **查看脚本演练**：`tail-logs.sh` / `trace-grep.sh <traceId>` 各跑一次。

## DoD 检查表

- [ ] 每个 HTTP 请求结束必出一条 ACCESS（含 GET 与异常 500 路径）（AC1.1）
- [ ] ACCESS 含 method/uri/status/costMs/req/resp 全字段；body >4KB 截断带标记；GET `req=-`（AC1.2）
- [ ] `/actuator/**` 无 ACCESS 日志；`common.access-log.enabled=false` 整体关闭（AC1.3 / NFR-001）
- [ ] 全服务日志行含 `service=<服务名>`，服务代码零改动（AC2.x / FR-004）
- [ ] `TraceIdFilter`（-200）先于 `AccessLogFilter`（-100），ACCESS 行 traceId 与业务日志一致（FR-002）
- [ ] 4 个 Scheduler + `ReliabilityConfig` 线程池日志带 traceId，线程复用不串号（AC3.x / FR-005）
- [ ] `SensitiveBodyMasker` 桩就位且被调用（透传不改内容）（D3 / NFR-003）
- [ ] `copyBodyToResponse()` 执行，客户端响应体完整（NFR-002）
- [ ] tail-logs.sh / trace-grep.sh 可用（AC4.x / FR-006）
- [ ] `mvn -o clean verify -fae` 全绿（SC-001）

## 用例矩阵

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| TC-01 | 单测全绿 | `mvn -o clean verify -fae` | BUILD SUCCESS |
| TC-02 | 正常请求 ACCESS | curl `POST /api/orders` | 一条 INFO：`ACCESS method=POST uri=/api/orders status=200 costMs=<ms> req={...} resp={...}`，traceId 同行 |
| TC-03 | GET 请求 | curl `GET /api/orders/{no}` | ACCESS `req=-`，resp 为响应体 |
| TC-04 | 异常路径 | curl 触发 400/500 | 仍出一条 ACCESS（status=400/500，resp 为 ApiError JSON） |
| TC-05 | 4KB 截断 | 请求体 >4KB | req 截断 + `...(truncated,total=N)` |
| TC-06 | 排除路径 | curl `/actuator/health` | 无 ACCESS 日志 |
| TC-07 | 开关关闭 | `common.access-log.enabled=false` 启动 | 无 ACCESS 日志，其余日志正常 |
| TC-08 | 服务名标识 | 对比 order/payment 日志行 | 均含 `service=order-service` / `service=payment-service` |
| TC-09 | traceId 串联 | 同一下单请求跨 order→payment 的日志 | 两服务日志 traceId 相同 |
| TC-10 | 定时任务 MDC | 触发超时扫描 | Scheduler 日志行带 traceId |
| TC-11 | 线程复用不串号 | 连续两次请求后查线程 MDC | 已清理，无残留 |
| TC-12 | 脱敏桩 | 任意请求 | masker 被调用（可加调试断言），内容透传不变 |
| TC-13 | tail-logs.sh | 全栈起后运行 | 合并实时视图，每行带 `[服务名]` |
| TC-14 | trace-grep.sh | 取一条下单 traceId 运行 | 捞出跨服务全部相关日志行 |
