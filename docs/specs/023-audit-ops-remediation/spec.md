# Feature Specification: 审计中性项收尾——可观测与一致性加固

**Feature Branch**: `feature/023-audit-ops-remediation`（代码）/ `docs/spec-023-audit-ops-remediation`（本 spec）

**Created**: 2026-09-07

**Status**: ✅ Accepted → 待实施

**Input**: 2026-09-07 两份审计报告（`.workbuddy/reports/2026-09-07-comprehensive-audit.md` 与 `docs/archive/audits/2026-09-07-技术审计报告-audit.md`）经负责人逐项裁决后的**中性工程遗留项**。

> **负责人裁决（2026-09-07 晚，永久生效）**：本项目永不上生产。安全类发现（S1 回调验签桩 / S2 内部端点无鉴权 / S3 明文凭证 / 日志脱敏）**保持桩实现现状，不做、不加门禁、不再讨论**（与 ADR-0024/0025/0027 一致）。本 spec 仅覆盖与安全/生产就绪无关的中性工程项。
>
> **与 spec 022 的边界**：Testcontainers / E2E / 测试分层门禁归 [spec 022](../022-full-chain-automated-testing/spec.md)（ADR-0069），本 spec 不重复立项。

---

## 1. 范围（4 项，均已对照 master `9766349` 核实）

### R1. Prometheus 死目标清理（审计 M4）

**现状**（已核实）：`deployment/prometheus/prometheus.yml:35-38` 仍保留 `job_name: "refund"` → `host.docker.internal:8085`。8085 已随 ADR-0064 退款并库退役，`up{job="refund"}` 恒 0；REFUND 业务指标实际由 payment-service（8084，`com.payment.refund` 包同进程）暴露。

**问题**：按 job 分组的 Grafana 面板存在恒空缺口；运维误判「refund 服务挂了」。

**修复**：删除该 job；检查 Grafana dashboard JSON 是否引用 `job="refund"`，如有改为 `job="payment"`；补一条「抓取目标存在性自检」说明到 runbook（不做自动告警，保持轻量）。

### R2. 优雅停机（审计 M5）

**现状**（已核实）：全仓 9 个服务的 `application.yml` 均无 `server.shutdown=graceful`；`deployment/stop.sh` SIGTERM 后在途请求（尤其支付回调、退款回调）可能被立即掐断。

**修复**：9 服务统一加 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`；`stop.sh` 注释补充停机语义说明。不动进程编排逻辑。

### R3. onPaymentSucceeded 事务收窄 + confirm 失败可观测（审计 M1）

**现状**（已核实）：`order-service/.../OrderApplicationService.java:207` 的 `onPaymentSucceeded` 带 `@Transactional`，方法内 `:250` 直接调 `catalogClient.confirmStock(...)`（Feign，1s 超时）。

**问题**：① 本地事务持有行锁等待网络 IO；② catalog 已提交的 confirm 与本地回滚可能分裂；③ confirm 失败目前 catch 吞掉，无独立指标留痕，只能靠对账事后发现。

**修复方向**（保持 ADR-0054「事实不回滚」语义不变）：
- PAID 状态落库 + 事务提交后，**事务外**执行 confirmStock 与履约驱动（方法拆分，不改对外契约）；
- confirmStock 失败路径补 `BusinessMetrics` 计数（对齐 `refund.order_notify_failed` 模式），供告警/对账定位；
- 补单测：confirm 抛异常时订单仍 PAID（事实不回滚）。

**不做**：重试队列 / Outbox（成本与收益不匹配，对账兜底 + 指标留痕已覆盖学习项目需求；如后续要升级走独立 spec）。

### R4. 文档漂移修复（审计文档章 + 2026-09-06 基线 H1/H3/H4/H5）

**已核实属实的漂移**：
- `docs/architecture/systems/order-service.md` §createOrder（约 :200-210）：仍写「同步 RPC 创建支付意图」且返回 `paymentNo/payUrl`，与两步式下单（`POST /orders` 仅建单，`paymentNo=null`）不符（H3）。
- `docs/architecture/systems/payment-service.md`：需核对退款编排归属表述是否已随 019 同步（audit 称仍归 refund-service，本次 grep 未命中 `refund-service` 字样，待复核具体章节）。

**疑似已修复（写时复核即可，无需改动）**：
- H4：README 服务清单已无 8085/refund-service 独立条目。

**待逐条核实**：H1（Nacos 表述）、H5（runbook 导航入口）、2026-09-06 基线 M 级未修项。

**修复原则**：以代码实况为唯一真相（两步式下单、ADR-0059 Nacos、ADR-0064 退款并库、ADR-0067 两层退款单），文档对齐代码；不新写架构叙述。

---

## 2. 非目标

- 安全类一切项（验签/鉴权/凭证/脱敏/门禁）——负责人已裁决永不做、不再讨论。
- Testcontainers / E2E / CI 测试分层——归 spec 022。
- Feign 超时/熔断/重试策略统一（审计 M2）——原计划并入本 spec，涉及 4 套策略并存的行为变更，影响面大，**拆到后续独立 spec**（将立项 ADR-0070 矩阵后单独实施）。
- 索引补充、死代码清理、版本残留清理——P3 顺手项，不占 spec 编号，随各批次顺手做（见 tasks.md 可选项）。

## 3. 验收标准

见 [acceptance.md](acceptance.md)。核心：全量 `./mvnw verify` 15 模块绿；compose 栈起后 Prometheus 无死目标；优雅停机演示（回调进行中重启不 5xx）；`onPaymentSucceeded` 新增单测证明 confirm 失败不回滚 PAID；文档漂移清单逐条闭环。
