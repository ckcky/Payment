# PaymentArch 端到端演示（Feature 011）

本目录提供一套**只编排、不伪造**的演示脚本与收银台控制台，用于现场复现支付主链与四个核心正确性场景。

> ⚠️ **验签形态说明（重要）**：渠道回调验签当前为 **ADR-0025 占位空实现**——`ChannelCallbackSignatureFilter` 恒放行，回调**不校验**签名。
> 因此「伪造签名被 403 拒绝」**在当前形态下不可演示**（点下去依旧放行），这是 ADR-0025 占位的诚实结果。
> 收银台页面里的「伪造签名」按钮仅作签名演示（用错误密钥签名），payment 侧不会拒绝。
> 若需演示签名拒绝，须先落地 ADR-0052（见 `docs/adr/0013`），届时两侧对齐 `PAYMENT_CHANNEL_SECRET`。

## 一键入口（推荐）

```bash
# 统一入口：启动 Docker 基础设施 + 本机 Java 服务 + mock-channel-web
bash deployment/demo/start-demo.sh

# 等价旧入口
bash deployment/demo/start-stack.sh
bash deployment/start-all.sh
```

这几个入口都遵循当前决策：

1. 先启动 Docker Compose 基础设施（MySQL / Redis / Prometheus / Grafana / Nacos 等）
2. 再 `./mvnw -q install -DskipTests` 构建依赖
3. 再在本机后台启动 10 个 Java 服务和 mock 收银台
4. 最后打印演示入口 URL 与日志位置

> 这是“基础设施容器化 + Java 微服务本机进程化”。
> 不做全量容器化；本项目仍保留底层 `docker compose` 入口，便于排查与调试。

### Windows / Git Bash 说明

若在 Windows 上遇到 `localhost` 代理、WSL、PATH 解析异常，优先使用：

```powershell
& "C:\Program Files\Git\bin\bash.exe" -lc "cd /c/Users/user/Desktop/GoProj/PaymentArch && bash deployment/demo/start-demo.sh"
```

这可以绕开 WSL 的 `localhost` 代理问题，并保持脚本行为一致。

## 前置条件

1. **Docker Desktop**（MySQL / Redis / Prometheus / Grafana / Nacos 容器）。本机无 Docker 时无法起栈。
2. **Maven 可用**：`deployment/start-all.sh` 用 `./mvnw` 启动各服务。若 `./mvnw` 不可用，请改用本地 Maven
   （如 `export MAVEN_CMD="mvn"` 并相应改造启动命令，或直接 `java -jar` 各服务的 fat-jar）。
3. **服务启动并开启 mock 收银台**：`bash deployment/demo/start-stack.sh`
   - 默认会 `export PAYMENT_MOCK_CASHIER_ENABLED=true`（支付走「收银台跳转」路径，响应带 `payUrl`）。
   - 默认注入演示用 `PAYMENT_ADMIN_TOKEN=demo-admin-token`（UNKNOWN 收敛端点鉴权）。
   - 启动后等待所有服务 `/actuator/health` 返回 200。

## 运行步骤

```bash
# 1) 起栈（Docker + 10 个进程）
bash deployment/demo/start-stack.sh

# 2) 复位并灌种子（重建 8 个业务 Schema + 商户/商品/SKU 种子）
bash deployment/demo/reset.sh        # 需 Docker（docker exec mysql）；若仅重灌数据可用 deployment/demo/seed.sh

# 3) 跑场景（每个脚本自带断言，失败即非零退出）
bash deployment/demo/scenario-happy-path.sh        # 主链：下单→收银台回调→履约/权益/记账
bash deployment/demo/scenario-routing.sh          # 渠道路由 S1~S6（spec 028：自动选路/显式优先/避开停用/拒绝改道/回原渠道）
bash deployment/demo/scenario-refund.sh           # 退款：累计不超额 + 幂等重放
bash deployment/demo/scenario-limit.sh            # 用户支付限额 L1~L7（spec 027：预占/超限409/幂等流水/TTL 惰性回收）
# 演示 UNKNOWN 需先切换支付场景为 BUSINESS_UNKNOWN：
bash deployment/demo/restart-payment.sh BUSINESS_UNKNOWN
bash deployment/demo/scenario-payment-unknown.sh  # UNKNOWN 权威收敛 + resolve 鉴权
bash deployment/demo/restart-payment.sh SUCCESS   # 切回默认成功路径
bash deployment/demo/scenario-reconciliation.sh   # 对账：跑批→差异→关闭门禁→处理→关账

# 4) 收尾
bash deployment/demo/stop-stack.sh
```

## 控制台（浏览器）

- 演示控制台：`http://localhost:8091/demo` —— 下单 → 打开收银台 → 轮询状态。
- 收银台页：`http://localhost:8091/cashier?paymentNo=...&orderNo=...`（业务单号，ADR-0063） —— 手动触发 SUCCESS / FAILURE / UNKNOWN 回调、
  连点重复回调、改金额、伪造签名（**当前形态下均放行**，见上方说明）。
- 各服务 Swagger：`http://localhost:8084/swagger-ui.html`（端口 8081~8090 同理）。
- Grafana：`http://localhost:3000`（admin/admin，内置「PaymentArch 业务指标」看板）。

## 四个场景断言点

| 场景 | 关键断言 |
| --- | --- |
| happy-path | 下单后支付为 `PROCESSING`（收银台路径）；回调后 `SUCCEEDED`；权益 `AVAILABLE` 且仅一份；账本 balanced 且分录可追溯；**重复回调幂等吸收** |
| refund | 支付 `SUCCEEDED` → 退款 `CREATED`；同幂等键重放返回同一退款；**累计超额被 409 拒（H1 防超额）** |
| routing | `GET /internal/channels` 与 `/route-preview` 快照；**不传渠道自动落 ALIPAY**（priority 最小）；显式 WECHAT 零干预；ALIPAY 置 `DOWN` 后自动绕开；显式指定 DOWN → **409 CHANNEL_UNAVAILABLE 且不落 attempt**；两渠道各自独立落库；退款回原渠道（INV-6，不因优先级改道）；`payment_routing_total` 已暴露 |
| payment-unknown | 支付 `UNKNOWN`（不猜成败落账）；无令牌 resolve 被 `403`；带令牌 resolve 收敛为 `FAILED` 终态 |
| reconciliation | 批次产生差异；**未处理差异时关闭被 400 拒（门禁）**；处理全部差异后关闭 `CLOSED` |
| audit | 注入 F1~F7 演示故障（幂等）；账证核对捕获漏记/孤儿/金额/重复/跨账 5 类差异；**未收口关批被 400 拒**；挂账（AD 单号、LP 记账）→ 调账转出 → SUSPENSE 归零 → 全部差异收口 → `CLOSED`；试算平衡 `balanced=true`；处置台账留痕 |
| limit | 设日限额 ¥150 → 首笔 ¥99 `used=9900`；第二笔 ¥99 **409 LIMIT_EXCEEDED 且 `payments` 无第二行**（INV-3）；重发回调 2 次 `CONFIRM` 恰 1 条（INV-4）；FAILED 支付 `pending` 归零；TTL 到期 `pending` 归零并出 `EXPIRED` 流水（惰性回收）；收尾清除限额配置（守 FR-025） |

## 用户支付限额演示（spec 027 / ADR-0071）

- 入口：`bash deployment/demo/scenario-limit.sh`（**默认不纳入 `run-all.sh`**——会临时设/清限额配置）。
  **live 实测（2026-09-16，容器模式）**：`EXIT=0`，58 条断言全 PASS。
- 网页版：`http://localhost:8091/demo` 左栏「用户限额」disclosure（设置 / 一键演示超限 / 清除）+
  右栏「用户限额」水位卡（日/月/年进度条 = `used` 实心 + `pending` 半透明、超限红条、最近流水）。
- **前置**：Redis（`docker-compose` 的 `redis:7`）已启动——在途占用过期索引依赖它；未启动时降级为
  保守占用（fail-open，不拦截支付）。容器模式下 payment-service 的 Redis 连接由
  `docker-compose.yml` 的 `SPRING_DATA_REDIS_HOST/PORT` 提供。
- **演示用户每轮唯一**：脚本默认用 `limit-demo-user-$RANDOM`（可 `LIMIT_USER=xxx` 覆盖）。
  原因：额度占用是支付事实的投影，**没有清空端点**，`clear_limit` 只删配置不动占用——
  固定用户名会让上一轮的在途 pending 残留进来，导致基线断言随机假红。
- **两套状态字面量别混**：渠道回调用 `SUCCESS` / **`FAILURE`** / `UNKNOWN`（渠道层
  `ChannelResult.Status`）；payment 落库状态是 `SUCCEEDED` / **`FAILED`**（`PaymentStatus`）。
  回调发 `FAILED` 会被 400 INVALID_ARGUMENT 拒收。
- **`0` = 该周期不限**（FR-020）：`set_limit` 传 0 表示**不限制**该周期，而非「额度为零」。
  造超限要用「当前占用 + 1 分」，不能用 0。
- **只有被配了额度（>0）的周期才被预占/记账**：只配日额度时，月/年周期不参与判定也不累计占用。
  演示「月周期是短板」需先给月周期配一个额度让它进入受管态。
- 断言口径：`user_limit_usage` / `limit_operations` 经 `/demo/trace?orderId=` 只读直查
  （`user_limit_usage` 按 `userId`、`limit_operations` 按 `paymentNo`）；
  某支付单的额度流水另有 `GET /internal/limits/payments/{paymentNo}/operations`。

## 渠道路由演示（spec 028 / ADR-0072、ADR-0073）

- 入口：`bash deployment/demo/scenario-routing.sh`（或经 run-all）；网页版：`http://localhost:8091/routing`（门户「渠道路由」入口）。
- **前置：payment-service 必须激活 `demo` profile**。脚本的 S3/S4 依赖 `POST /internal/channels/{code}/status`
  把渠道置 `DOWN`，该端点标注 `@Profile("demo")`，未激活时不存在（表现为 404/500）。
  容器模式已在 `docker-compose.yml` 设 `SPRING_PROFILES_ACTIVE: demo`；宿主模式经 `start-demo.sh` 同值传递。
- 该端点是**纯内存覆盖，重启即回到配置值**；`demo/reset.sh` 只重建数据库、不碰它。脚本开头有
  `⓪a 复位渠道可用性` 把四个渠道显式置回 `UP`，以避免上轮失败残留导致误报。
- **断言口径**：渠道归属一律读 `payment_attempts.channel_code` **列**（经 `/demo/trace?orderId=`），
  **不以渠道引用字符串的形态为准**，也不读 `payments` 表——该表**没有** `channel_code` 列。

## 审计演示（spec 017）

- 入口：`bash deployment/demo/scenario-audit.sh`（或经 run-all）。故障注入使用 `fixtures/audit/audit-faults.sql`（幂等，可重复执行；仅限本地演示库）。
- 演示控制台：`http://localhost:8091/audit` —— MOCK（内置确定性数据）/ LIVE（透传真实 `/internal/audit/**`）双模式，覆盖触发 → 差异 → 挂账 → 调账 → 复核 → 关批 → 试算平衡全流程。
- 结算门禁：审计批有未收口差异时，settlement 建批被 `BLOCK`（fail-closed）；「已挂账」视为留痕放行，结算仍可继续（分级门禁，plan §6.1）。
- `AUDIT_FULL=1 bash deployment/demo/scenario-audit.sh` 追加 ALL scope（账账科目勾稽 / 账实渠道核对 / 账表回算）。

## Mock 渠道场景切换（ADR-0049）

`payment.channel.mock-scenario` 是**构造期注入**的，运行期不可热切换。需要换场景时重启支付服务：

```bash
bash deployment/demo/restart-payment.sh BUSINESS_UNKNOWN   # 渠道不给结论 → UNKNOWN 路径
bash deployment/demo/restart-payment.sh SUCCESS            # 恢复默认成功路径
```

可选值：`SUCCESS` / `FAILURE` / `TIMEOUT` / `TRANSPORT_ERROR` / `BUSINESS_UNKNOWN`。
