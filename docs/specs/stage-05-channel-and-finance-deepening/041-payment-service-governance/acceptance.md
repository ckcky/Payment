# 041-payment-service-governance — Acceptance

> **Status**: Approved（2026-09-26 负责人裁决批准立项；**ADR-0084 Accept 前不进入 In Development**）
> **Spec**: [spec.md](spec.md) ｜ **Plan**: [plan.md](plan.md) ｜ **Tasks**: [tasks.md](tasks.md) ｜ **Migration Map**: [migration-map.md](migration-map.md)

## 1. 验收前置条件

1. 新 ADR 已 Accepted；Feature 已进入 `In Development` 或更高状态。
   - **当前状态（2026-09-26）**：❌ **未满足**。ADR-0084 已起草但为 **Proposed**；Feature 状态 **Approved**（立项已批准）而非 In Development；H-041-4~6 待裁决。
2. 数据库明确是 development/test，且安全清库；生产、共享验收和未识别环境必须拒绝。
   - **当前状态**：⚠️ 本机 `deployment/logs/*.log` 在实时写入 ⇒ **有 live 栈在跑**，清库前必须先停栈。
3. 旧包/API/DTO/配置/Schema/调用方迁移清单完成；仓内调用方均使用新 API。
   - **当前状态**：✅ 清单已完成（[migration-map.md](migration-map.md)，T03）；❌ 调用方**尚未迁移**（T21~T23 未开工）。
4. MySQL、Redis、测试容器和依赖服务可用；CI 的真实库测试不得静默跳过。
   - **当前状态**：✅ 全量 `mvnw clean test` 1086 / 0F / 0E 通过（见 §5），依赖服务可用。

## 2. INV 门禁

| INV | 操作 | 通过证据 |
| --- | --- | --- |
| INV-001 | 扫描金额类型；提交正数、零、负数和缺币种命令。 | 无 float/double 金额路径；非法命令零写入。 |
| INV-002 | 对三种聚合注入重复、迟到、冲突和非法状态结果。 | 合法迁移成功；非法迁移拒绝；终态不覆盖。 |
| INV-003 | 运行 Payment/Channel 边界的阴性规则和阳性对照。 | 无跨域表、Repository、Mapper、Entity 访问。 |
| INV-004 | 并发重放支付、退款、渠道命令、回调、账本和 MQ 事件。 | 每类只有一份业务事实和一次资金副作用。 |
| INV-005 | 模拟超时、断连、畸形回执。 | 状态为 UNKNOWN/PENDING；无错误终态/记账。 |
| INV-006 | 模拟账本成功、失败与重放。 | 已确认事实有平衡分录；失败入台账；重放不重复。 |
| INV-007 | 改变当前路由后对既有支付退款、查询、重试。 | 始终调用原渠道；无法解析时显式失败。 |
| INV-008 | 发送验签、渠道、引用、金额、币种错误回调。 | 回调拒绝；Payment/Refund/账本/通知均不变。 |
| INV-009 | 扫描 Payment/common-dto/插件包导入。 | 私有协议与 SDK 只在 `channel/infra/plugins/<code>/`。 |
| INV-010 | 调用全部 GET 并比对调用前后数据库；调用所有写端点。 | GET 零写入；写操作全为显式命令。 |

## 3. Success Criteria 验收矩阵

| SC | 前置条件 | 操作 | 期望结果 | 证据 |
| --- | --- | --- | --- | --- |
| SC-001 | 源码迁移完成 | 包扫描与 ArchUnit | 所有生产代码在目标双域四层，旧散落包为零。 | 扫描报告、架构测试。 |
| SC-002 | 合法支付命令 | `POST /api/payments`，检查依赖和数据 | Payment 仅经 ChannelGateway 调用渠道，无 Channel 持久化访问。 | 单测、ArchUnit、DB。 |
| SC-003 | 每个插件可装配 | 分别执行 pay/refund/query 正常与失败流 | ChannelOrder 在 Channel 内创建/收敛，仅输出标准结果。 | 应用/插件测试。 |
| SC-004 | 合法渠道回调 | `POST /callbacks/channels/{channelCode}` | Channel 处理回调，PaymentResultPort 收到标准通知。 | HTTP、port spy、审计。 |
| SC-005 | 同一命令/事件 | 并发重复请求与重放 | Payment、Refund、ChannelOrder、账本、通知均不重复。 | 真库并发/消费测试。 |
| SC-006 | 超时渠道 | 发起命令后查询/显式 resolve | 初始 UNKNOWN/PENDING；仅权威查询/合法回调/人工命令收敛。 | 状态机、Demo 日志。 |
| SC-007 | 成功支付、路由变化 | 退款、重试、主动查询 | 调用记录仍为原 channelCode。 | ChannelOrder/Plugin 断言。 |
| SC-008 | 插件已编译 | 扫描依赖 | Payment 与 common-dto 无 SDK、签名器、配置或私有报文。 | ArchUnit、rg。 |
| SC-009 | Controller 迁移 | 运行 MVC 与边界测试 | Controller 不依赖持久化/插件，单请求委托一个 application facade。 | MVC、ArchUnit。 |
| SC-010 | 持久化迁移 | 检查 application/infra 依赖 | Mapper 仅在 infra.persistence，application 只见 Repository 端口。 | ArchUnit。 |
| SC-011 | 调用方迁移 | 覆盖支付、退款、查询、resolve、回调、订单查询、运维 API | 新 API 可用；旧路径文本和运行时调用为零。 | 契约、E2E、rg。 |
| SC-012 | 空开发库 | 执行清库保护、lint、双路径 replay | 非开发环境拒绝；空库可初始化；唯一键/索引正确。 | 脚本日志、真库测试。 |
| SC-013 | 删除完成 | 执行旧符号/API/配置负向扫描 | 无旧包、API、DTO、配置、兼容垫片或旧测试。 | 迁移清单、rg、编译。 |
| SC-014 | 门禁已加入 | 运行规则及故意违规阳性对照 | 每条规则既验证正常代码，也能捕获违规。 | architecture-tests。 |
| SC-015 | 所有验证完成 | `mvnw clean verify`、replay、容器 Demo、docs lint | 构建和文档全绿；任一资金 INV 失败即未完成。 | CI/Demo/docs 日志。 |

## 4. 可复现演示

1. 空开发库初始化并启动依赖服务。
2. 创建支付，展示 Payment、ChannelOrder、账本分别归属各领域。
3. 重复提交相同支付，证明无重复扣款、记账、消息或渠道订单。
4. 将渠道设为超时，展示 UNKNOWN；再用合法回调或显式查询收敛。
5. 用错误签名、金额、币种回调，证明所有资金事实不变。
6. 修改默认路由后退款，证明退款仍回原渠道。
7. 展示一个插件目录的完整渠道资产，并展示 Payment 无渠道私有导入。
8. 验证所有新 API；旧路径均不存在，demo/E2E 均使用新路径。

## 5. 回归基线（2026-09-26 实测，禁止抄 spec 里的数字）

**命令**（本机默认 `java` 是 JDK 11，必须显式覆盖）：

```bash
export JAVA_HOME=/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B clean test     # 必须 clean：target/surefire-reports/ 会残留已删除类的旧 XML，直接加总得到虚高数字
```

**实测结果**（master `5ce4853`，18 reactor 模块）：**1086 tests / 0 Failures / 0 Errors / BUILD SUCCESS**（6 分 37 秒）

| 模块 | 测试数 | 模块 | 测试数 |
|---|---|---|---|
| common-core | 70 | entitlement-service | 28 |
| common-dto | 15 | reconciliation-service | 157 |
| common-redis-mq | 22 | settlement-service | 55 |
| merchant-service | 10 | ledger-service | 73 |
| catalog-service | 49 | deployment/mock-channel-web | 11 |
| order-service | 73 | deployment/test-infra | 19 |
| **payment-service** | **456** | deployment/architecture-tests | 23 |
| fulfillment-service | 25 | **合计** | **1086** |

- `common-mybatis`、`deployment/e2e-tests` 无 surefire 报告（前者无测试；后者 live 栈默认跳过），计 0。
- **自洽校验**：`payment-service` 源码级 `@Test|@ParameterizedTest|@RepeatedTest` 注解数 = **496**，
  执行数 = **456**，差 40（参数化用例展开、条件装配与依赖 Testcontainers 的用例计入源码但不计入本 profile）。
  本 Feature 结束时**必须重跑同一口径**并给出「增量 = 新增用例数」的对照。
- ⚠️ 本基线比 037 收口时记录的 482 / 1112 **低 26**，原因是旧 041（`payment-flow-layering`，
  `5c00ad4` / `57e12c3`）已合入并改动了测试装配。**基线必须在每次开工前重测，不得沿用历史数字。**

## 6. 交付记录

| 项目 | 结果 | 证据/命令 | 备注 |
| --- | --- | --- | --- |
| 全量单测基线（开工前） | ✅ **1086 / 0F / 0E** | `./mvnw -B clean test` | 见 §5；本轮唯一已执行的验证 |
| Schema lint 与双路径重放 | 待执行 | `schema-lint.sh` / `schema-replay.sh` | 阻塞于 T20 |
| Payment/Channel 单元与真库测试 | 待执行 | payment-service 单测 | 阻塞于 T07~T18 |
| ArchUnit 与阳性对照 | 待执行 | `deployment/architecture-tests` | 阻塞于 T25；现状 23/23 绿 |
| HTTP 契约与仓内调用方 | 待执行 | 契约测试 + 负向 `rg` | 阻塞于 T21~T23 |
| 全 reactor verify | 待执行 | `./mvnw -B clean verify` | 阻塞于 T28（本轮只跑到 `test`） |
| 容器关键 Demo | 待执行 | `deployment/demo/*.sh` | 阻塞于 T23 / T28 |
| 文档 lint/链接检查 | 待执行 | `python deployment/docs-lint.py` | 阻塞于 T27（ADR 部分已可跑） |
| **ADR-0084 Accept** | ⏳ **待负责人** | `docs/adr/0084-payment-channel-governance.md` | **本项未完成 ⇒ T04 起全部不得开工** |

## 7. 已知限制

- 仅开发/测试环境允许清库；真实数据迁移、灰度、旧 API 兼容不在范围内。
- 不新增真实渠道能力；现有渠道能力以迁移前已验收行为为基线。
- 实施中若需改变状态机、资金模型、服务边界、账本契约或生产部署策略，必须停止并取得新的负责人决策，不能以“重构”名义隐式交付。
