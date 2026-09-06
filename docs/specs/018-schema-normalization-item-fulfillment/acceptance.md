# Acceptance: 018-schema-normalization-item-fulfillment

> 验收执行方式与 DoD。状态：✅ Accepted（验收标准随 spec 018 拍板；执行待代码实施）。

## 验收执行方式

1. **自动化门禁**：`mvn -o clean verify -fae` 全绿（沿用 017 门禁惯例；单测为主 + H2 集成）。
2. **迁移脚本验证**：对含存量数据的真实 MySQL（docker-compose 实例）执行 `deployment/schema/018-schema-normalization.sql` **两遍**，均成功且第二次无实际变更（幂等）；`SHOW COLUMNS` 核对目标列序。
3. **demo live 冒烟**：起全栈 → portal → demo 下单（2 个 item）→ 收银台支付成功 → ④ 全链路 DB 数据逐项核对（见用例矩阵）。
4. **人工核对**：DBA 视角抽查 5 张违规表列序 + 3 张特例表原样。

## DoD 检查表

- [ ] payment_attempts 含 amount_minor/currency_code，存量回填无 NULL，新写入两个创建点均落值（SC-001/AC1.x）
- [ ] payment_attempts 列序 = FR-001 目标序（requested_at/responded_at/version 最后三列）
- [ ] 5 张违规表列序调整到位；3 张特例表未动（AC2.1/2.3）
- [ ] order_items.order_item_no 唯一且第 2 列；fulfillments.order_item_id NOT NULL 且值为 OI 单号（AC3.1/3.2）
- [ ] fulfillments 唯一键 = `(source_payment_no, order_item_id)`；重复支付通知不产生重复履约（AC3.3）
- [ ] on-refund 取消该订单全部 PENDING 履约；entitlement 授予/撤销行为与改造前一致（AC3.4）
- [ ] ④ 区全部 section 标题带中文说明（AC4.1）
- [ ] portal 欢迎页 5 类入口可达（AC5.1）
- [ ] 迁移脚本重放幂等（SC-005）
- [ ] `mvn -o clean verify -fae` 全绿（SC-001）

## 用例矩阵

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| TC-01 | 单测全绿 | `mvn -o clean verify -fae` | BUILD SUCCESS，无失败用例 |
| TC-02 | 迁移幂等 | 存量库跑 018 脚本两遍 | 两遍均成功；列序/约束达到目标态 |
| TC-03 | 金额回填 | 脚本执行后查 payment_attempts | amount_minor/currency_code 无 NULL，值=所属支付单 |
| TC-04 | 新支付尝试 | demo 下单支付成功 | 新 attempt 行带正确金额/币种 |
| TC-05 | 退款尝试 | 触发一次退款 | REFUND 尝试行带所属支付单金额 |
| TC-06 | 多明细履约 | 下单 2 item → 支付成功 | 2 条 fulfillments，order_item_id 为两个不同 OI 单号 |
| TC-07 | 幂等粒度 | 同一支付成功通知重放 | 不新增履约行（uk 兜底 + 应用层回查） |
| TC-08 | 退款撤销 | 部分履约 PENDING 时发退款 | 全部 PENDING 履约 → CANCELLED；entitlement 撤销照常 |
| TC-09 | 中文注释 | 打开 ④ 全链路 DB 数据 | 每个 section 标题含中文说明，格式 `system · table（说明）` |
| TC-10 | 门户主界面 | 打开 `http://localhost:8091/` | 5 类入口卡片可见且可跳转（demo/audit/Grafana/Prometheus/压测说明） |
| TC-11 | 特例表豁免 | 检查 refund_intake_locks / accounts / stock_reservation | 结构与改造前一致 |
| TC-12 | 回归保障 | 现有演示脚本（scenario-*.sh）跑通 | 无因列序/新增列导致的脚本失败 |
