# MVP 验收结果

> 记录 T077「mvnw verify + Compose + quickstart 全链路 RPC 回归」的执行证据与结论。
> 本文是事实记录，不改变任何已确认的领域边界、状态机、幂等或 RPC 契约。

## 验收结论

| 项 | 结果 | 证据 |
|---|---|---|
| 全 reactor 编译 + 测试（`./mvnw verify`） | ✅ PASS | EXIT=0，`BUILD SUCCESS` |
| 测试用例规模 | ✅ 180 tests / 0 failures / 0 errors / 0 skipped | 50 个 surefire 报告文件汇总 |
| Compose 配置语法 | ✅ 有效 YAML | Python `yaml.safe_load` 通过 |
| Compose 实机编排 / 9 服务 HTTP 起停 | ⚠️ 未在本环境执行 | 本机未安装 Docker CLI（`docker: command not found`） |
| initdb 建库脚本 | ✅ 8 个 schema 脚本齐全 | `docs/deployment/initdb/01–08` |
| 资金记账 / 真实出款 | ✅ 未引入 | Settlement 仅产出模拟结果，Ledger 未实现 |

## 执行明细

1. **构建与测试**：`./mvnw -q verify` → `EXIT=0`。13 个 Maven 模块（含 `common-core`、`common-dto`、`common-mybatis` 与 9 个服务）全部编译通过并跑完测试。
2. **Compose**：`docker-compose.yml` 仅声明 MySQL 8 最小依赖（`initdb` 01–08 建库脚本按序挂载）；YAML 语法校验通过。因本机无 Docker CLI，`docker compose config --quiet` / `up -d` 未执行。
3. **全链路 RPC 回归**：9 服务实机 HTTP 联调依赖 MySQL + Docker 起停，本环境未执行；改由**服务边界集成测试**覆盖等价场景（见下），全部通过。

## 全链路场景覆盖（服务边界集成测试）

| 场景 | 测试类 | 覆盖点 |
|---|---|---|
| 成功购买 + 履约/权益 RPC | `order SuccessfulPurchaseScenarioTest`、`fulfillment FulfillmentEntitlementRpcFlowTest` | Order→Transaction→Payment→Fulfillment→Entitlement |
| UNKNOWN 收敛（只触发一次履约） | `payment PaymentUnknownResolutionTest` | 超时→UNKNOWN、查询/回调收敛、幂等 |
| 退款 + 后处理 | `refund RefundScenarioTest` | 部分/全部退款、重复、UNKNOWN、履约/权益后处理 |
| 对账 + 结算 RPC | `reconciliation ReconciliationSettlementRpcScenarioTest` | 一致/四类差异、结算 summary 契约、批次幂等 |

以上测试在 `./mvnw verify` 中全部通过（180/180）。

## 环境限制与后续

- 本机未安装 Docker CLI，`docker compose` 实机验证与 9 服务进程级联调留待具备 Docker/MySQL 的环境执行；Compose 文件与 initdb 脚本已就绪。
- 健康检查（`/actuator/health`）与场景 1–4 的 HTTP 断言需在服务实际启动后按 [quickstart.md](quickstart.md) 复现；当前由同语义的集成测试兜底。
- 本 MVP **不含** Ledger 复式记账与真实出款：Settlement 只生成模拟结算结果，符合 [plan.md](plan.md) 与宪法「资金正确性 > 一切」的阶段性边界。
