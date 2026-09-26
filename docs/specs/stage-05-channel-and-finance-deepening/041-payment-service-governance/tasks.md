# 041-payment-service-governance — Tasks

> **Status**: Approved（2026-09-26 负责人裁决批准立项；**ADR-0084 Accept 前不进入 In Development，T04 起不得开工**）
> **Spec**: [spec.md](spec.md) ｜ **Plan**: [plan.md](plan.md) ｜ **Acceptance**: [acceptance.md](acceptance.md) ｜ **Migration Map**: [migration-map.md](migration-map.md)
> **Related ADR**: [ADR-0084](../../../adr/0084-payment-channel-governance.md)（🟡 Proposed，2026-09-26 起草，待负责人 Accept）

## 0. 决策与迁移基线

- [x] T01 `docs/adr/0084-payment-channel-governance.md`：记录双域边界、一次性 API 替换、开发/测试清库；更新 ADR 索引与追溯。依赖：无。追溯：FR-001/011/012。验收：ADR Accepted。
  - **执行记录（2026-09-26）**：ADR-0084 已起草（Status 🟡 **Proposed**，待负责人 Accept）。七条决策：① 双域对称四层（`channelgateway`→`channel` 正名、`posting` 并入 `payment`、四个旁路包收进四层）；② `ChannelGateway`/`PaymentResultPort` 双向端口 + 跨域 DTO 入 `common-dto`；③ 一次性替换 9 组 HTTP API，禁止兼容层；④ 仅 dev/test 清库重建（`APP_ENV` fail-closed）；⑤ 插件目录自包含；⑥ **Supersedes ADR-0072 §6**（两域不共享事务）；⑦ ArchUnit 双域/四层/Controller/持久化门禁 + 阳性对照。
  - **预检新发现并已登记 ADR-0084「交叉影响」**：X-1 ADR-0072 §6 被旧 041 `57e12c3` 的代码事实推翻（Code ≠ ADR）；X-2 新回调路径 `/callbacks/channels/**` **脱离** ADR-0083 的 `/internal/channels/**` 排除路径 ⇒ 不补即重开「渠道报文含 `sign` 进 ACCESS_LOG」的漏洞；X-4 spec 040 须置 `Superseded by 041`。
  - **双向登记已完成**：`docs/adr/README.md`（索引表 / 完整文件清单 43 文件 / 速查 / 下一可用编号 → 0085）与 `docs/adr/traceability.md` 已同步；`docs/adr/0072-two-layer-channel-architecture.md` 头部与 §6 已补 `Partially Superseded by ADR-0084（仅 §6）`。
  - **遗留**：待负责人 **Accept**（H-041-4~6）。
- [x] T02 `docs/specs/stage-05-channel-and-finance-deepening/041-payment-service-governance/`：完善四件套和需求 checklist；更新 Specs 索引与 Roadmap。依赖：T01。追溯：FR-015。验收：docs lint。
  - **执行记录（2026-09-26）**：四件套状态 `Draft` → `Approved`（H-041-1~3 已裁决；并注明 ADR Accept 前不进 In Development）；spec.md 新增 **§0.5「现状与目标结构的差异」**（8 项实测漂移，防止照字面把目标当现状）；acceptance.md 补实测回归基线与「交付记录」。
  - **遗留**：docs lint 待跑；Roadmap 与 Specs 索引待同步（本轮一并完成）。
- [x] T03 `payment-service/`、上下游服务与 `deployment/`：建立"旧包/类/API/DTO/配置/Schema/调用方→新目标"逐项映射。依赖：T02。追溯：FR-013/015。验收：映射覆盖生产、测试、脚本与文档。
  - **执行记录（2026-09-26）**：产出 [migration-map.md](migration-map.md)，覆盖 5 大类：包/类 → 目标、API 端点 → 新端点（含调用方清单）、配置、Schema、调用方（上下游服务 + deployment）。
  - **统计**：12 个 Controller、4 组端点路径变更、130+ 引用文件（含 docs，非文档约 40）、4 个下游服务、6 处 deployment。

## 1. 契约与目标骨架

- [ ] T04 `common/common-dto/src/main/java/com/payment/common/dto/paymentchannel/`：定义 ChannelGateway 命令/回执与 PaymentResultPort 通知 DTO；禁止数值 ID 和私有渠道类型。依赖：T01。追溯：FR-002/003/006，INV-009。验收：DTO 契约测试。
- [ ] T05 `payment-service/src/main/java/com/payment/payment/{api,application,domain,infra}/`、`.../channel/{api,application,domain,infra}/`：建包骨架，更新 Spring/Feign/Mapper 扫描。依赖：T04。追溯：FR-001。验收：上下文启动。
- [ ] T06 `channel/application/port/ChannelGateway`、`payment/application/port/PaymentResultPort`：定义并装配两个跨域端口。依赖：T04/T05。追溯：FR-002/003/006，INV-003。验收：端口装配和依赖测试。

## 2. Channel 域

- [ ] T07 `channel/domain/`：重建 ChannelOrder、状态机、领域错误和 Repository 端口。依赖：T05。追溯：FR-004，INV-002/004/005/007。验收：状态机与幂等单测。
- [ ] T08 `channel/infra/persistence/`：实现 Entity、Mapper、Repository 和本地事务写入口。依赖：T07。追溯：FR-010/012，INV-003。验收：真库唯一键/并发测试。
- [ ] T09 `channel/application/`：实现命令校验、ChannelOrder 创建/复用、原渠道查询/退款和标准化结果。依赖：T06/T08。追溯：FR-003/004，INV-004/005/007。
- [ ] T10 `channel/infra/plugins/`：建立模板方法、Factory/Registry、Strategy 和插件结构测试。依赖：T09。追溯：FR-007/008，INV-009。
- [ ] T11 `channel/infra/plugins/{alipay,wechat,stripe,douyin,mock}/`：逐渠道迁入 SDK、配置、签名、Gateway、Plugin、回调解析和测试。依赖：T10。追溯：FR-007/008/013。验收：每渠道独立装配测试。
- [ ] T12 `channel/api/`、`channel/application/`：实现唯一回调入口及“识别→验签→解析→收敛→PaymentResultPort”流程。依赖：T06/T09/T10。追溯：FR-005/006，INV-008。验收：错误回调不触达 Payment。
- [ ] T13 `channel/api/`：重建渠道订单查询、路由预览和可开关运维端点。依赖：T09。追溯：FR-009/011，INV-010。

## 3. Payment 域

- [ ] T14 `payment/domain/`：重建 Payment、Refund、Limit、PendingPosting 聚合及 Repository 端口，保持状态机语义。依赖：T05。追溯：FR-001，INV-001/002/006。
- [ ] T15 `payment/infra/persistence/`：实现上述聚合的 Entity、Mapper、Repository 适配器。依赖：T14。追溯：FR-010/012。验收：application 无 Mapper 依赖。
- [ ] T16 `payment/application/`：实现创建支付，仅经 ChannelGateway 调渠道并应用结果。依赖：T06/T14/T15。追溯：FR-002，INV-003/004/005/006。
- [ ] T17 `payment/application/`：实现 PaymentResultPort，统一支付/退款结果、终态吸收、账本、订单和消息通知。依赖：T06/T16。追溯：FR-006，INV-002/004/006。
- [ ] T18 `payment/application/`、`payment/infra/`：迁移退款、UNKNOWN 查询/重试、限额、挂账重放、MQ、Feign 和配置；所有渠道调用经 ChannelGateway。依赖：T16/T17。追溯：FR-003/012，INV-005/006/007。
- [ ] T19 `payment/api/`：重建支付、退款、查询、显式 resolve 和事实端点；每 Controller 调单一 facade。依赖：T16/T18。追溯：FR-009/011，INV-010。

## 4. Schema、调用方、清理与交付

- [ ] T20 `deployment/schema/`、`deployment/test-infra/`：重建 payment DDL、基线、H2/真库 replay 与开发环境清库保护。依赖：T08/T15。追溯：FR-012，INV-001/004。验收：双路径 replay，非开发环境拒绝。
- [ ] T21 `payment-service` API 契约测试：替换全部 HTTP 端点并删除旧 Controller/DTO/路径。依赖：T12/T13/T19。追溯：FR-011/013，INV-010。
- [ ] T22 `order-service/`、`reconciliation-service/`、`fulfillment-service/`、`entitlement-service/`：迁移 Feign/HTTP 调用方。依赖：T21。追溯：FR-011。验收：调用方契约测试。
- [ ] T23 `deployment/mock-channel-web/`、`deployment/e2e-tests/`、`deployment/demo/`、`deployment/performance/`：迁移 API 调用、回调和演示脚本。依赖：T21。追溯：FR-011/015。
- [ ] T24 旧源码/资源：删除旧顶层包、API、DTO、配置、兼容垫片和对应测试。依赖：T11–T23。追溯：FR-013。验收：负向 `rg` 与编译。
- [ ] T25 `deployment/architecture-tests/`：新增双域、四层、Controller、Mapper、插件目录门禁及阳性对照。依赖：T24。追溯：FR-014，INV-003/009/010。
- [ ] T26 相关 `src/test/`：补支付、退款、ChannelOrder、回调、UNKNOWN、账本、MQ、限额与并发幂等用例。依赖：T12/T17/T18/T20。追溯：FR-002–006，INV-001–008。
- [ ] T27 `docs/architecture/`、`docs/operations/`、`docs/specs/README.md`、Demo 文档：同步 L0 与运行文档。依赖：T21–T24。追溯：FR-015。验收：docs lint/链接检查。
- [ ] T28 全仓：执行 replay、架构测试、payment 测试、`mvnw clean verify`、容器 demo，在 acceptance 回填 SC-001–015。依赖：T25–T27。追溯：所有 FR/INV/SC。

