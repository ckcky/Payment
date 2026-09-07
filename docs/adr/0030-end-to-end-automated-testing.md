<a id="adr-0069"></a>

# ADR-0069: 全链路自动化测试体系——独立 E2E 模块 + 分层门禁 + 不变量断言（spec 022 立项）

- 状态：✅ **Accepted → Implemented（代码落地，live 实跑验证待办）**（2026-09-07 批次 B~F 代码完成；T433/T434 live 验证待办，见 [spec 022 tasks](../specs/022-full-chain-automated-testing/tasks.md)）
- 关联：ADR-0067 / spec 019（order 驱动两层退款单 TXRF/PMRF —— E2E 的主验证对象）、ADR-0066 / spec 018（按 item 履约 —— 断言需逐条）、spec 017（会计四核对 —— 对账差异注入器）、spec 011/012（幂等 —— 用例来源）、ADR-0025（渠道回调验签占位，**禁止据此写断言**）、spec 021（traceId 串联，P2 校验项）
- 需求源头：负责人 2026-09-07——「想给这个项目加上自动化测试，最好是完整链路的，比如说在 demo 控制台发起的支付退款，观察各个系统的状态，还有 db 数据检查啥的。发起退款，看看功能是不是正常，超退能不能拦截，对账准不准确，单号有没有记错啥的。看看业内先进水平是如何设计的。」追加：「你先去 demo 演示台和对账的演示台看看有什么功能，怎么样串起来全链路的测试，需要关注的点是什么，如何验证。」

## 背景

**现状核实（G1~G6，证据见 [spec 022 §1](../specs/022-full-chain-automated-testing/spec.md)）**：

- 已有一批不错的单服务资产：~100 个测试类 / ~450 个用例（`@SpringBootTest` + H2 `MODE=MySQL` + 手写 fake 出站端口）、退款金额不变量/订单不变量/账本幂等等不变量测试、ArchUnit 服务边界模块。
- 但「端到端」只有 `deployment/demo/scenario-*.sh` 一套 bash 演示脚本：无标准报告、失败无诊断产物、不在 CI、不能单条重跑（G1）。
- **没有直接 DB 断言**，只能查服务 API 与 `/demo/trace`，做不了跨库一致性与聚合不变量（G2）。
- **没有对账准确性测试**（G3）、**没有系统性单号链路校验**（G4）、异常路径只覆盖 UNKNOWN 一个场景（G5）、环境不可在 CI 复现（G6）。

**两个控制台的实地核实结论**：`8091/demo.html` 已能通过 `/proxy/**` 驱动下单→支付→退款全链路，并通过 `/demo/trace?orderId=` 提供跨 9 库 14 表的只读行级快照；`8091/audit.html` 的 F1 平账 / F2~F9 八类故障 fixture 是**现成的确定性对账差异注入器**——这大幅降低了本方案的实施成本（行级断言与差异数据均可复用，只需新写驱动编排、轮询等待、聚合不变量 SQL 与报告产物）。

**三个会骗人的陷阱（写入硬约束）**：①渠道回调验签是 ADR-0025 占位恒放行，断言「伪造被拒」会得到假绿；②audit 控制台 MOCK 模式纯前端执行、不调后端，自动化必须走 LIVE；③渠道对账差异来自固定 CSV fixture，与真实链路数据无关，故「对账准不准」必须拆成「真实数据一致性」与「差异检出能力」两套断言。

**业内调研（2026-09-07）**：支付/金融系统普遍采用**测试钻石**（集成测试为主体，E2E 只留 3~5 条核心路径，接口漂移交给契约/快照层）；账务不变量的黄金断言是 `SUM(debits)==SUM(credits)`（按单 + 全局两级）+ append-only + 幂等重放一致；对账测试的黄金形态是「注入 N 类差异 → 检出率 100% + 分类正确 + 调账后收敛」；Stripe 式**确定性 mock**（固定卡号/金额触发指定结果）让异常路径可确定复现，不靠概率与等待；环境用 Testcontainers 保证可复现；门禁按 PR 快跑 / nightly 全量分层。

## 决策（负责人 2026-09-07 逐条拍板）

1. **E2E 承载形态：新建独立 Maven 模块 `deployment/e2e-tests`**（D1）。黑盒：只走 HTTP + JDBC，不依赖业务模块；技术栈 JUnit5 + AssertJ + Awaitility + JDK `HttpClient` + mysql-connector-j + Jackson。产出标准 JUnit XML 报告，可进 CI、IDE 可单条重跑。**bash 演示脚本与 Node 压测脚本保留**（前者服务现场演示、后者服务压测）。

2. **环境：默认复用本地栈，`ci` profile 才用 Testcontainers**（D2）。`-De2e.env=local`（默认）连已起的 10 服务 + 本机 MySQL 3306，最轻最快；`ci` 起 MySQL/Redis 全新容器保证可复现。数据隔离靠**每用例唯一业务号前缀 `e2e-{runId}-{case}` + 断言按单号过滤**，无需每次 truncate。

3. **不引入 Spring Cloud Contract / Pact，改做内部 API schema 快照**（D3）。本项目服务间走 `common-dto` 共享 DTO + Feign，编译期已挡住大半接口漂移；再引契约框架收益低、维护成本高。改为断言内部 API 请求/响应的字段集合与类型，成本极低即可防漂移。

4. **CI 门禁：PR 快跑 + nightly 全量**（D4）。PR 只跑单元 + 单服务集成 + API 快照 + ArchUnit（分钟级）；E2E 全链路 / 对账 / 并发幂等放 nightly 与 release 前强制。理由：GH Actions 上起 10 个 JVM 服务过重，会阻塞合并。

5. **DB 断言：行级复用 `/demo/trace`，聚合不变量才直连 MySQL**（D5）。`/demo/trace` 已按业务单号把 9 库 14 表关联好，行级断言零成本；借贷平衡、退款累计、孤儿单、单号唯一性等聚合类必须自己写 SQL，沉淀为 `Invariants` 断言原语库。

6. **对账准确性验证复用现有 fixture**（D6）：会计四核对用 audit 的 F1（平账，期望 0 差异）/ F2~F9（八类故障，期望全检出且分类正确），**必须 LIVE 模式**；渠道对账用新增 period 的 CSV 注入长款/短款/金额不符/单边账/重复 5 类差异。

7. **故障注入走确定性触发**（D7）：现有 `PAYMENT_MOCK_SCENARIO` 是整进程粒度、跑异常路径要重启服务；扩为「按请求特征触发」（金额尾数 / remark），对齐 Stripe 式确定性 mock。**禁止用 sleep 或概率制造异常**。

8. **分层占比：测试钻石**（D8）。L1 单元（多）/ L2 单服务集成（最多，现有资产）/ L3 API 快照（少）/ L4 全链路 E2E（少而精）/ L5 对账与故障注入（中）。L1/L2 不推倒重来，本 ADR 新建 L3~L5 并把既有不变量测试沉淀为可复用断言原语。

9. **测试有效性必须反证**（SC-002/SC-003）：故意注入缺陷（去掉退款累计校验 / 不回填 `payment_refund_no`），对应用例必须变红——否则断言形同虚设。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| bash 演示脚本测试化（改 `lib.sh` 加用例注册/JUnit XML） | 无类型安全；跨库聚合 SQL 与报告生成写起来痛苦；难维护、难扩展（演示脚本另有用途，不宜绑死） |
| Node/TS（Vitest + mysql2） | 与 Maven/CI 体系割裂；团队主力是 Java；报告集成需额外转换 |
| 引入 Spring Cloud Contract / Pact | 共享 DTO + Feign 已挡住大半漂移；契约 DSL 与 stub 维护成本明显上升，收益不匹配 |
| 每次跑 E2E 都起 Testcontainers 全栈 | 冷启分钟级，本地调试不便；改作 `ci` profile，本地默认复用已起栈 |
| E2E 进 PR 门禁 | GH Actions 起 10 个 JVM 服务可能 20~40 分钟，且易因环境抖动阻塞合并 |
| 全量扫表做不变量断言 | 慢且易受脏数据干扰；改为按本轮业务号前缀过滤 |
| 用 sleep 等待异步收敛 | 固定等待既慢又脆；一律 Awaitility 轮询（默认 15s 可配） |
| 断言渠道回调验签拒绝伪造 | ADR-0025 占位恒放行，会得到**假绿** |
| 用 audit 控制台 MOCK 模式验收对账 | MOCK 纯前端执行、不调后端，测的是 JS 不是系统 |

## 影响与落地

- 新增模块 `deployment/e2e-tests`（黑盒），**业务代码零改动**；唯一可能的业务侧改动是 mock-channel 的请求级故障注入扩展（D7，阶段 2）。
- 既有 ~450 个单服务测试不受影响；`./mvnw -B verify` 语义不变。
- 任务分批次 B~G（骨架 → 断言原语 → P0 用例 → P1 用例 → 故障注入与 CI → 收尾），详见 [spec 022 tasks](../specs/022-full-chain-automated-testing/tasks.md)。
- 风险与缓解见 [spec 022 plan §8](../specs/022-full-chain-automated-testing/plan.md)：E2E 慢/脆（默认本地栈 + 不进 PR + 轮询 + dump）、数据污染（唯一前缀 + 按单号过滤）、与演示脚本重复（阶段 1 后评估 `run-all.sh` 是否改为调 E2E 模块）。

## 编号与注册

- ADR 编号：**ADR-0069**；文件 `docs/adr/0030-end-to-end-automated-testing.md`；对应 spec **022**（`docs/specs/022-full-chain-automated-testing/`）。
- 下一可用编号：ADR-0070。
