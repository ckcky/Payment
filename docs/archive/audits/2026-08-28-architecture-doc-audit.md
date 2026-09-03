# 架构文档审计报告（Architecture Documentation Audit）

- **审计对象**：`docs/`（architecture / adr / guides / specs）、`.specify/memory/constitution.md`、`CLAUDE.md` 等架构与规范文档
- **审计日期**：2026-08-28
- **审计方法**：通读 Constitution、技术方案、Roadmap、ADR、系统设计、工程规范与 Spec；对每一项「文档断言」用 `grep`/`ls`/实际源码二次核验（代码事实来自 2026-08-28 代码级审计与本次复核）。
- **Status**：archived（已归档至 docs/archive/audits/，结论已被 2026-09-03 文档审计整改吸收）
- **与代码级审计的关系**：本文聚焦「文档本身的准确性、一致性、完整性、时效性」，不重复代码缺陷；代码现实结论仅用于判定文档是否失真。

---

## 1. 总体结论

文档体系**结构成熟、分层清晰、规范意识强**（Constitution → ADR → 技术方案 → 系统设计 → Roadmap → Spec 的层级与优先级明确，ADR 有状态机、单一事实源规则到位）。

但存在 **5 类系统性问题**，其中最严重的是 **「最高约束文档与分阶段计划自相矛盾」** 与 **「文档大量描述尚未落地的目标架构，且未按目标/现状明确标注」**，叠加 **「近期代码改动未回写文档」** 导致文档已部分失真。

| # | 问题 | 严重度 | 类型 |
|---|---|---|---|
| D1 | Constitution §II.3 要求所有资金变动 MUST 经 `ledger-service`，但 Roadmap/技术方案把 Ledger 延后到 Phase 8 —— MVP 阶段资金根本不经 Ledger | 🔴 High | 内部矛盾 |
| D2 | Feature 编号在文档与现实间错位：文档称「下一个 Feature = 002 Payment Reliability」，实际 002=payment-order-callback、003=payment-reliability；roadmap 内「002」自相矛盾 | 🔴 High | 内部矛盾 |
| D3 | 文档称已采用 Nacos（注册+配置），代码零引用 Nacos；配置硬编码 | 🔴 High | 文档≠现实 |
| D4 | 文档称已封装 `Money` 值对象、禁止裸 `long`；代码 `Money` 为死代码，金额全用裸元组 | 🔴 High | 文档≠现实 |
| D5 | 文档称对外 API 有 Spring Security/OAuth2 鉴权；代码无任何 security 依赖 | 🔴 High | 文档≠现实 |
| D6 | `payment-service.md` 仍描述「事务内同步 RPC / Feign 未配置 / 无 /resolve 守卫」，与近期代码改动不符 | 🟠 Med | 文档滞后 |
| D7 | ADR-0001 反复引用「Constitution §3.1（模块化单体）」，但现行 Constitution v2.0.0 已无该节 | 🟠 Med | 引用失效 |
| D8 | 服务清单口径不一致：Constitution 列 11 个（含 gateway/ledger），技术方案称「9 个已建」 | 🟠 Med | 内部矛盾 |
| D9 | `./mvnw` 被描述为可重复构建入口，但 `.mvn/wrapper/*.jar` 缺失，wrapper 实际不可运行 | 🟠 Med | 文档≠现实 |
| D10 | `project-structure.md` 目录树画出 `gateway/`、`ledger-service/`，两目录均不存在 | 🟡 Low | 文档≠现实 |
| D11 | 系统设计文档仅 2/9 篇（仅 order、payment），`docs/README.md` 称「9 篇」 | 🟡 Low | 完整性缺口 |
| D12 | Schema DDL 被标为「权威」，但无 Flyway/Compose 导入机制，运行期实际无表 | 🟡 Low | 文档≠现实 |
| D13 | 缺「MVP 期不建 Ledger / 资金为模拟事实」的偏离 ADR，Constitution §8 要求的重大偏离未立 ADR | 🟡 Low | 完整性缺口 |

> 正向项见第 7 节。下列各节均附 `文件:行号` 证据。

---

## 2. 内部一致性问题（文档自相矛盾）

### D1 🔴 Constitution 资金铁律与分阶段计划直接冲突
- **Constitution §II.3（行 38）**：「任何资金变动 **MUST** 经 `ledger-service` 复式记账……**MUST NOT** 直接改余额字段。」§IV（行 83）服务清单把 `ledger-service` 列为正式服务。
- **技术方案 §2.3（行 48）** 与 **Roadmap（行 77、340）**：Ledger **明确延后到 Phase 8**；Roadmap 还把「不创建 Ledger」写入 Phase 0/2/7 的「不包含」。
- **技术方案 §8 风险表（行 388）** 自己承认「无 Ledger 的资金风险……Phase 0-7 只模拟业务事实」。
- **矛盾实质**：最高约束文档用 MUST 要求 Ledger，而计划在前 8 个阶段根本不建 Ledger、且 payment/refund/settlement 已实现「资金流程」。若严格按 Constitution，这些阶段都不该碰资金；若按计划，则 Constitution 的 MUST 被事实上违反。二者必须二选一澄清（建议：在 §II.3/§IV 增加 MVP 例外——「MVP 仅模拟资金事实、不触真实余额，Ledger 自 Phase 8 起 MUST」）。

### D2 🔴 Feature 编号在文档与现实间错位（且 roadmap 内部自相矛盾）
- **docs/specs/ 实际目录**：`001-core-business-model`、`002-payment-order-callback`、`003-payment-reliability`。
- **roadmap.md 行 12**：「当前 Feature：`002-payment-order-callback`」；**行 19**：「下一个 Feature：主链 `002 Payment Reliability`」；**行 25–43 依赖图**：`002 Payment Reliability`。
  → 同一文档里「002」既指当前（payment-order-callback）又指下一个（Payment Reliability），自相矛盾。
- **技术方案 §7（行 369–372）** 同样写「`001 Core Business Model → 002 Payment Reliability → 003 Refund`」。
- **结论**：文档中的「002 Payment Reliability」应改为「003」，否则读者按 Roadmap 找 `002 Payment Reliability` 会找不到（真实 002 是 order-callback，reliability 是 003）。

### D7 🟠 ADR-0001 引用已不存在的 Constitution 章节
- **ADR-0001 行 6**：「取代 Constitution §3.1『模块化单体』决策」；**行 52**：「Constitution 需按 §10 修订」；**ADR 索引（adr/README.md 行 9）**：「取代 Constitution §3.1」。
- 但现行 **Constitution 为 v2.0.0（行 192）**，Core Principles 已是 I 生产导向 / II 资金 / III 领域 / IV 架构(微服务) / V 一致性，**已无 §3.1 模块化单体**（Sync Impact Report 行 3–8 也说明已从模块化单体重定义为微服务）。
- **问题**：ADR 的「后遗症」说明已过时，易让读者误以为 Constitution 仍含矛盾旧章节。建议 ADR-0001 补一句「该修订已在 Constitution v2.0.0 落地，§3.1 已不存在」。

### D8 🟠 服务清单口径不一致
- **Constitution §IV（行 83）** 列 11 个服务（含 `gateway`、`ledger-service`），无「延后」标记。
- **技术方案 §1.2（行 26）/ §3.2（行 104）** 明确「9 个服务模块 + 3 共享库，gateway 与 ledger-service 延后」。
- 实际模块确为 9 个（merchant/catalog/order/payment/refund/fulfillment/entitlement/reconciliation/settlement）。建议 Constitution §IV 在延后服务后加「（MVP 延后）」以保持口径一致。

---

## 3. 文档与代码现实偏差（文档断言 ≠ 代码事实）

以下均经 `grep`/`ls` 复核（2026-08-28 验证）：Nacos 在 pom/yml 中引用数 = **0**；`Money` 在 `src/main` 引用数 = **0**；`spring-security` 在 pom 中引用数 = **0**。

### D3 🔴 Nacos 声明未落地
- 文档：**ADR-0002（行 20）**「注册+配置中心 Nacos」；**技术方案 §1.2（行 26）**「Java21+…+Nacos+OpenFeign」；**§3.5（行 143）**「Nacos（注册+配置）」；**payment-service.md §6.3（行 338）**「Nacos 就绪（注册+配置）」。
- 现实：代码无任何 Nacos 依赖/配置；服务 URL（如 `http://localhost:8086`）硬编码于 `@FeignClient` 与 `application.yml`。
- 影响：文档把「目标技术」写成「已采用」，误导对当前架构的判断。应在所有相关处标注 `[目标]` 或「Phase 9 启用」。

### D4 🔴 `Money` 值对象文档称已封装，实为死代码
- 文档：**Constitution §II.2（行 37）**「封装 `Money` 值对象，禁止裸 `long` 满天飞」；**技术方案 §4.2（行 196）**「封装 `Money` 值对象」；**payment-service.md §2.1（行 49）** 把 `Money` 列为值对象。
- 现实：全库 `src/main` 对 `com.payment.common.core.money.Money` 的引用为 **0**；金额一律以裸 `(long amountMinor, String currencyCode)` 元组承载（见 payment-service.md 自身 §2.3/§3.1 的字段表）。
- 影响：核心资金不变量在文档层面「已满足」，实际未满足。应标注为「[目标]/待启用」并跟踪（代码级审计 P0-1 已列）。

### D5 🔴 鉴权文档称有 Spring Security，实为无
- 文档：**Constitution §Security.3（行 143）**「对外 API 有鉴权（Spring Security / OAuth2）与输入校验」；**技术方案 §5.2（行 312）** 同述。
- 现实：全仓 pom 无 `spring-boot-starter-security`；`/payments/{id}/resolve` 此前可无鉴权伪造支付成功（代码级审计 F2，近期已加 `ResolveAuthorizationInterceptor` 作为临时守卫，但仍非 Spring Security/OAuth2）。
- 影响：安全基线被文档「默认成立」，实际缺失。建议标注为 Phase 9 待办，并把近期加的拦截器写进系统设计。

### D9 🟠 `./mvnw` 可重复构建入口实际不可用
- 文档：**Roadmap（行 14）**「`mvnw verify` 通过」；**技术方案 §6.2（行 342）**「本地启动：`./mvnw` 逐服务启动」。
- 现实：`.mvn/wrapper/*.jar` **不存在**（wrapper 分发缺失），`./mvnw` 无法直接运行。本机实际可用的是 `~/.m2/wrapper/dists/apache-maven-3.9.9`，需绕过 wrapper 启动。
- 影响：「一条命令可启动、可验证」（Constitution §I.4）这一「可运行」铁律在文档层面成立、实践中需手工绕过。建议修复 wrapper 分发或文档改为实际可用命令。

### D10 🟡 `project-structure.md` 目录树含未创建目录
- **行 27** 画 `gateway/`，**行 41** 画 `ledger-service/`，虽正文写「本 MVP 延后」，但树形图本身暗示目录存在。建议用 `(延后，未创建)` 标注或移出树。

### D12 🟡 Schema DDL 标「权威」但无应用机制
- **payment-service.md §2.3（行 85）/ §6.3（行 337）** 称权威 DDL 在 `deployment/schema/03-payment-schema.sql`。
- 现实：无 Flyway/迁移脚本、Compose 未导入 SQL，运行期实际无表（代码级审计已确认）。文档的「每服务独立 Schema」属目标态，建议注明「需经迁移机制应用，当前未挂载」。

---

## 4. 文档滞后（近期代码改动未回写）

近期对 payment-service 的修复（事务边界重构、Feign 超时/熔断、/resolve 守卫、@Valid 校验）**未同步到系统设计文档**，导致文档描述的是改动前的旧架构：

### D6 🟠 payment-service.md 描述的是重构前流程
- **§4.1（行 217–225）**：第 6 步仍写「`save` 支付 + 尝试（本地事务）；`changed` 时触发履约 RPC」——即把外部 RPC 描绘为同一方法/事务内动作。
- **§5.3（行 282–283）**：「`createPaymentIntent` 的『支付 + 尝试』在同一本地事务原子提交」；实际已抽出 `PaymentPersistence`，`channel.charge` 与履约 RPC 已移出事务（代码级审计 P0-3 已修）。
- **§6.2（行 298–300）/ §6.3（行 332）**：仍称「出站 Feign 超时：当前未显式配置；[目标] connect 1s/read 3s」。实际 `application.yml` 已配置 `connect-timeout:2s`/`read-timeout:5s` + `circuitbreaker.enabled`（代码级审计 P0-2 已修）。
- **遗漏**：新增的 `ResolveAuthorizationInterceptor`（/resolve 守卫）、`@Valid` 输入校验未入档。
- 建议：更新 §4.1/§5.3/§6.2，并新增「安全守卫」小节。

---

## 5. 完整性缺口

### D11 🟡 系统设计文档仅 2/9 篇
- **docs/README.md（行 24）** 与 **技术方案 §3** 承诺「每服务系统设计文档（9 篇）」。
- 实际 `docs/architecture/systems/` 仅 `order-service.md`、`payment-service.md`。`merchant/catalog/refund/fulfillment/entitlement/reconciliation/settlement` 七篇缺失。
- 影响：跨服务一致性、数据所有权等关键约束仅在全局文档概述，缺逐服务契约与状态机落地，难以守住 Constitution §III 边界。建议排期补齐或降级承诺（标注「按需补充」）。

### D13 🟡 缺「MVP 偏离」ADR
- Constitution §8 把领域边界、状态机、Schema、安全策略列为人类决策边界；MVP 期「不建 Ledger、资金为模拟事实」是对 Constitution §II.3 的重大偏离，但**未立 ADR**（仅技术方案 §8 风险表一笔带过）。
- 建议补 ADR-0003「MVP 期资金为模拟事实、Ledger 延后至 Phase 8」，明确与 Constitution 的关系与收敛条件。

---

## 6. 正向项（保留并推广）

- **层级与优先级清晰**：Constitution → ADR → 技术方案 → 系统设计 → Roadmap → Spec 的金字塔与冲突裁决规则（Constitution 行 175–183）明确，且「资金正确性 > 一切」贯彻始终。
- **ADR 机制成熟**：状态机（Proposed→Accepted→Superseded/Deprecated）、「永不删除只演进」、编号规范到位（adr/README.md）。
- **单一事实源与文档评审规则**（docs/README.md 行 60–67）完善，含审计归档约定。
- **技术决策有据**：ADR-0001/0002 含 Context/Decision/Consequences/Alternatives，质量高。
- **设计文档质量本身高**：payment-service.md 的状态机、幂等、UNKNOWN 收敛、错误码表、埋点键非常扎实，是后续补齐其他 7 篇的范本。
- **目标/现状标注意识已有雏形**：技术方案大量使用 `[目标]`/`[待定]`/`[Phase N 延后]`（如 §5.1、payment-service.md 行 9），只是**未覆盖到 Nacos/Money/Security 这几处**。

---

## 7. 改进建议（按优先级）

### P0（立即，消除文档失真与自相矛盾）
1. **D1**：在 Constitution §II.3 与 §IV 增加 MVP 例外条款——「MVP 仅模拟资金事实、不触真实余额；`ledger-service` 自 Phase 8 起 MUST；Phase 0–7 资金变动不违反本 MUST 因无真实余额」。同步在技术方案 §8 风险表标注「已知且已授权偏离」。
2. **D2**：全局将「002 Payment Reliability」改为「003 Payment Reliability」；roadmap 内统一 002=order-callback、003=reliability（修订行 19、25–43）。
3. **D3/D4/D5**：Nacos、Money VO、Spring Security 三处在所有文档改为明确标注 `[目标]/Phase 9 启用，当前未落地`，与 `[目标]` 体系一致。

### P1（本周，修复时效性与引用）
4. **D6**：回写 payment-service.md §4.1/§5.3/§6.2，描述重构后事务边界、已配置的 Feign 超时/熔断、/resolve 守卫与 @Valid。
5. **D7**：ADR-0001 补充「Constitution v2.0.0 已落地微服务，§3.1 已不存在」的脚注。
6. **D9**：修复 Maven Wrapper 分发，或把文档构建入口改为实际可用命令。

### P2（排期，完整性）
7. **D8/D10**：Constitution §IV 延后服务加标注；`project-structure.md` 树中延后目录标「未创建」。
8. **D11**：补齐其余 7 篇系统设计文档（或把「9 篇」承诺改为「按需补充」）。
9. **D12**：为 `deployment/schema/*.sql` 增加应用机制（Flyway/Compose 导入），或注明未挂载。
10. **D13**：补 ADR-0003 记录 MVP 偏离，闭合 Constitution §8。

---

## 8. 一句话总结

**架构文档的「骨架与规范」是一流水准，但「内容与现实」已出现系统性漂移**：最高宪法与分阶段计划自相矛盾（Ledger/MVP）、大量目标架构被写成既成事实（Nacos/Money/Security）、且近期代码修复未回写。优先修 D1/D2/D3/D4/D5（P0）即可消除最危险的失真，再按 P1/P2 补齐时效性与完整性。
