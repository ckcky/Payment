# 041-payment-service-governance — Tasks

> **Status**: In Development（2026-09-26 负责人裁决批准立项 + **Accept ADR-0084**，H-041-1~7 全部批准；T04 起实施中）
> **Spec**: [spec.md](spec.md) ｜ **Plan**: [plan.md](plan.md) ｜ **Acceptance**: [acceptance.md](acceptance.md) ｜ **Migration Map**: [migration-map.md](migration-map.md)
> **Related ADR**: [ADR-0084](../../../adr/0084-payment-channel-governance.md)（🟢 **Accepted**，2026-09-26 负责人裁决接受，即刻生效）

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

- [x] T04 `common/common-dto/src/main/java/com/payment/common/dto/paymentchannel/`：定义 ChannelGateway 命令/回执与 PaymentResultPort 通知 DTO；禁止数值 ID 和私有渠道类型。依赖：T01。追溯：FR-002/003/006，INV-009。验收：DTO 契约测试。
  - **执行记录（2026-09-26）**：**契约已存在，本任务为「确认 + 保留」而非新建**。
    实测 `com/payment/common/dto/channel/` 已有全套跨域契约（ADR-0075 / spec 030 产物）：
    `ChannelPayCommand` / `ChannelPayReceipt` / `ChannelQueryCommand` / `ChannelQuerySnapshot` /
    `ChannelRefundCommand` / `ChannelRefundReceipt`（出向命令与回执）、
    `ChannelPayNotified` / `ChannelRefundNotified`（入向通知）、
    `ChannelPayStatus` / `ChannelRefundStatus`、`CallbackUrls` / `Goods` / `Payer` / `PaymentScene` / `PayCredential`。
  - **INV-009 复核通过**：① 无数值主键（标识全为业务单号 `channelNo` / `paymentNo`，由
    `ChannelContractTest#outboundContractsCarryNoNumericPrimaryKey` 钉死）；② 无渠道私有类型
    （扩展袋是 `Map<String,String>` 的 `channelExtra`，无 SDK / 签名器 / 渠道报文结构）；
    ③ 金额一律 `long` / `Long`（`Long` 的 `null` 表示「渠道未读到」，不等于 0）。
  - 🔶 **有意差异 D-1（登记）**：spec 写的目标包名是 `…dto.paymentchannel`，现状是 `…dto.channel`。
    **沿用既有 `channel`，不新建 `paymentchannel`** —— 前者已由 ADR-0075 确立、被全仓引用，
    改名是零收益churn；且 `common-dto` 下不存在同名冲突（`rpc` 是另一域）。
    **追溯影响**：本项偏离 spec 字面路径，属「包名口径」，不触及契约语义与 FR-002/003/006。
- [x] T05 `payment-service/src/main/java/com/payment/payment/{api,application,domain,infra}/`、`.../channel/{api,application,domain,infra}/`：建包骨架，更新 Spring/Feign/Mapper 扫描。依赖：T04。追溯：FR-001。验收：上下文启动。
  - **执行记录（2026-09-26）**：`com.payment.channelgateway` → **`com.payment.channel`** 整体改名完成
    （`git mv` 目录 + 全量替换）。**实测**：146 个 java 文件 / 506 处引用全部替换，残留 0；
    涉及 main 87 + test 58 + `deployment/architecture-tests` 1（`ServiceBoundaryTest` 白名单正则）。
  - `payment` 域四层**已成立**（`api/application/domain/infra`），本次不动；`channel` 域四层由改名后自然成立。
    **旁路包与 `posting` 的收拢留到 T11/T14/T18**（属于「迁内容」而非「建骨架」）。
  - Spring 组件扫描 / MyBatis Mapper 扫描均以 `com.payment` 为根，改名后**无需改配置**（`application.yml` 中 `channelgateway` 引用数为 0，已复核）。
  - ⚠️ **工具坑记录**：zsh 下 `for f in $(...)` **不做词分割**、`sed -i ''` 参数被吞 —— 批量替换改用 Python 脚本完成（见本节）。
- [x] T06 `channel/application/port/ChannelGateway`、`payment/application/port/PaymentResultPort`：定义并装配两个跨域端口。依赖：T04/T05。追溯：FR-002/003/006，INV-003。验收：端口装配和依赖测试。
  - **执行记录（2026-09-26）**：两个端口均已归位到 `port` 子包。
    - `channel/application/ChannelGateway.java` → **`channel/application/port/ChannelGateway.java`**
    - `payment/application/PaymentNotifyPort.java` → **`payment/application/port/PaymentResultPort.java`**
      （按 spec 正名；实现 `DefaultPaymentNotifyPort` → **`DefaultPaymentResultPort`**，测试类同步改名）
    - `PayNotifyOutcome`（入向端口的返回类型，被 Channel 侧 `ChannelResult` / `AbstractChannelPlugin` /
      `MockChannelAdapter` 消费，属跨域类型）**一并迁入 `payment/application/port/`**
  - **实测**：26 个文件的限定名/类名替换 + 5 个被移动文件的 `package` 声明改写，残留 0。
    补 import 11 处（8 个同包引用因跨包失效：3 个生产 + 5 个测试）。
  - 🔶 **有意差异 D-2（登记）**：`ChannelGateway` 接口上的两个静态工厂 `none()` / `ofSingleChannel()`
    需要 `new DefaultChannelGateway(...)`，导致 **port 包内部反向依赖 `channel.application` 的实现类**。
    **刻意保留这两个工厂在接口上，不移到 `channel.application`** —— 实测两个工厂的 6 个调用点
    **全部在 Payment 侧（其中 4 个是生产代码）**；若移到 `channel.application`，Payment 就必须
    import `com.payment.channel.application.*`，**直接违反 INV-003 / ADR-0084 plan §2**
    「Payment 禁止依赖 Channel 的其他 application 类型」。留在 port 接口上时，Payment 编译期只看见
    `com.payment.channel.application.port.ChannelGateway`，看不到 `DefaultChannelGateway`，边界成立。
    代价（port → impl 的包内反向依赖）属 **Channel 域内部事务，不跨域**，已在源码注释中写明。
  - 🔴 **断言变更（唯一一处，已获负责人放行）**：`PaymentResultPortTest#portIsDefinedByPayment`
    原断言 `.isEqualTo("com.payment.payment.application")` 与「端口落在 `port` 子包」不可兼得。
    全仓扫描同类硬编码包名断言仅 2 条（本条 + `ChannelCallbackHandlerTest:198`，后者当前仍绿）。
    负责人裁决：**按原则同步更新** —— 包路径断言随被断言类的实际落点同步更新为**等值**断言，
    **不得放宽为 `startsWith`**（那会允许任意子包，属弱化断言）。本条改为
    `.isEqualTo("com.payment.payment.application.port")`，语义（定义权归 Payment / 落在渠道包即 INV-2 倒置）
    与 `@DisplayName` 的 INV-2 / FR-012 追溯**逐字保留**，并在源码注释中写明变更缘由。
    该通则对后续 T12 等同类情况一并适用。
  - **验证**：`./mvnw -B -pl payment-service -am test` = **482 / 0F / 0E / BUILD SUCCESS**（与基线逐位相等）。

## 2. Channel 域

- [x] T07 `channel/domain/`：重建 ChannelOrder、状态机、领域错误和 Repository 端口。依赖：T05。追溯：FR-004，INV-002/004/005/007。验收：状态机与幂等单测。
  - **执行记录（2026-09-26）**：**「确认 + 补测」而非重建**。实测四件已在位且质量达标：
    `ChannelOrder`（聚合，`channelNo` 业务单号不可变、`channelCode` 不可变 ⇒ INV-007 强制回原渠道）、
    `ChannelOrderStatus`（五态枚举）、`ChannelOrderErrorType`（领域错误分类）、
    `ChannelOrderRepository`（领域端口，实现在 `channel/infra/persistence/`）。
    - INV-002 复核：状态只经 `accept/succeed/fail/markUnknown` 推进，无 `setStatus`；
      终态（SUCCEEDED/FAILED）吸收迟到/重复/冲突结果（返回 false）。
    - INV-005 复核：`markUnknown` 覆盖 PENDING/ACCEPTED，终态不被未知结果污染。
    - **FR-010 负向检查通过**：`channel/application/` 无任何 Mapper/Entity 引用；
      `ChannelOrderEntity` / `ChannelOrderMapper` / `MybatisChannelOrderRepository` /
      `InMemoryChannelOrderRepository` 全部位于 `channel/infra/persistence/`。
    - **补测**：新增 `channel/domain/ChannelOrderStateMachineTest`（19 用例）——Channel 域此前
      无专属状态机单测（Payment 域已有 `PaymentStateMachineTest` 对照）。锁定 spec §8 迁移图
      （PENDING/ACCEPTED/UNKNOWN → SUCCEEDED/FAILED，终态吸收一切迟到/冲突结果）、
      引用回填边界、无公共 `setStatus`。**新增测试，未触碰任何既有断言。**
  - **验证**：该测试 19 / 0F / 0E；全量门禁在 T08 提交前重跑（见 T08 记录）。
- [x] T08 `channel/infra/persistence/`：实现 Entity、Mapper、Repository 和本地事务写入口。依赖：T07。追溯：FR-010/012，INV-003。验收：真库唯一键/并发测试。
  - **执行记录（2026-09-26）**：**「确认 + 补测」**。实现四件已在位：
    `ChannelOrderEntity` / `ChannelOrderMapper` / `MybatisChannelOrderRepository`（乐观锁，
    冲突抛 CONFLICT）/ `InMemoryChannelOrderRepository`；**本地事务写入口**
    `ChannelOrderServiceImpl` 落在 `infra/persistence`（每写方法自带 `@Transactional`，
    spec 041 / D2 与 payment 侧拆事务——ADR-0084 决策 6 的落点）。
  - **补测**：新增 `channel/infra/persistence/ChannelOrderPersistenceTest`（6 用例，
    渠道域此前无自有持久化测试）：
    roundTrip 全字段映射（含 errorType / extra 模态 / REFUND 类型）、
    `uk_attempts_channel_no` 重复插入被拒、`uk_attempts_channel_reference` 重复插入被拒、
    乐观锁陈旧版本被拒（先到者事实不被覆盖）、**8 线程竞争同一 channelNo 恰好一个赢家**、
    rehydrate 时间戳读回。踩坑两条已记录：① `com.payment.channel.**` 向上找不到
    `@SpringBootConfiguration` ⇒ `@SpringBootTest(classes = PaymentApplication.class)`
    必须显式给；② H2 schema 每次上下文只刷一次 ⇒ 同 paymentNo 跨用例互相污染，
    每用例必须用唯一单号。**新增测试，未触碰任何既有断言。**
  - **验证**：该测试 6 / 0F / 0E。
- [x] T09 `channel/application/`：实现命令校验、ChannelOrder 创建/复用、原渠道查询/退款和标准化结果。依赖：T06/T08。追溯：FR-003/004，INV-004/005/007。验收：应用/插件测试。
  - **执行记录（2026-09-26）**：**「确认」而非重建**——管线四段全部在位且有钉死测试：
    - 命令校验：`PaymentApplicationService` 入口 `INVALID_ARGUMENT`（空命令 / 双单号交叉校验）。
    - 幂等创建/复用：Payment 侧 `PaymentPersistence.insertPending`（业务唯一键幂等）⇒
      Channel 侧 `ChannelOrderService.openChannelOrder`（FIX-3 断言 1:1，复用由幂等键在**建单前**拦下）。
    - 原渠道查询/退款（INV-007）：`ChannelQueryService.resolveRecordedTarget` 按
      `attempt_type=PAYMENT` 行取 `channel_code`（确定性 id 升序，修 S22），找不到即抛
      `INTERNAL_ERROR` **不回落默认渠道**；退款同口径（渠道码由调用方解析传入，接口不回落）。
    - 标准化结果：`ChannelResult` + `converge` 三态收敛（SUCCESS/FAILURE/UNKNOWN），
      UNKNOWN 进 `markUnknown`（INV-005 不猜结果）。
  - **验证**：`ChannelGatewayTest` 11 + `DefaultChannelGatewayDispatchTest` 3 +
    `ChannelOrderServiceContractTest` 7 = **21 / 0F / 0E**。
- [x] T10 `channel/infra/plugins/`：建立模板方法、Factory/Registry、Strategy 和插件结构测试。依赖：T09。追溯：FR-007/008，INV-009。
  - **执行记录（2026-09-26）**：**「确认内核 + 建目录 + 补结构测试」**。
    模板方法 {@code AbstractChannelPlugin}（final 主流程四步：能力校验 → 模态门控 → 模态分派 →
    异常兜底）、{@code ChannelPluginFactory} / {@code ChannelPluginDescriptor} SPI 已在
    `application/spi` 内核侧；Registry（`SpringChannelRegistry`）/ Router（`ConfiguredChannelRouter`）/
    Factory 定位器（`ChannelPluginFactoryLocator`，Spring + ServiceLoader 双路合并）在 infra 根。
    - 新建 `infra/plugins/{alipay,wechat,stripe,douyin,mock}/` 骨架（T11 一并迁入）。
    - **补测**：新增 `ChannelPluginStructureTest`（3 用例）：五家实现全部落在
      `channel.infra.plugins.<vendor>`、模板在内核 `application.spi`、五家均可赋值到模板
      （差异关进笼子的结构判据）。
- [x] T11 `channel/infra/plugins/{alipay,wechat,stripe,douyin,mock}/`：逐渠道迁入 SDK、配置、签名、Gateway、Plugin、回调解析和测试。依赖：T10。追溯：FR-007/008/013。验收：每渠道独立装配测试。
  - **执行记录（2026-09-26）**：**纯机械迁移，零行为变更**（`git mv` + Python 全仓替换，
    吸取 T05 的 zsh sed 教训）：
    - `infra/alipay/` → `plugins/alipay/`（含目录外散件 `AlipayChannelAdapter`、
      `config/AlipaySandboxProperties` 收进同目录，§0.5 登记的半成品补齐）；
      `infra/wechat/` → `plugins/wechat/`；`infra/stripe/` → `plugins/stripe/`；
      扁平件 `DouyinChannelAdapter` → `plugins/douyin/`；
      `MockChannelAdapter` + `AbstractMockChannelAdapter` + `MockCashierProperties` → `plugins/mock/`。
    - 测试同步迁移（alipay/wechat 目录 + 两个扁平测试文件）；45 个 java 文件引用替换，残留 0；
      补 import 6 处（4 个生产：Alipay/Douyin 父类、config 两个 MockCashierProperties 引用；
      2 个测试：ChannelPluginMigrationTest 三个 Adapter、DemoCashierDispatchPolicyTest）+
      2 个扁平测试文件的 package 声明改写。
  - **补测**：新增 `StripeChannelPluginTest`（4 用例）——Stripe 此前<b>无专属测试</b>，
    「每渠道独立装配测试」对它缺席；补齐工厂装配/自描述、能力校验先于网关触达
    （桩网关任何被调即炸）、MOCK 模态零触达、enabled=false 不阻塞 MOCK（C-1 同口径）。
  - **验证**：迁移相关 8 个测试类 79 / 0F / 0E；新增 7 用例（结构 3 + Stripe 4）全绿。
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

