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

# 3) 跑四个场景（每个脚本自带断言，失败即非零退出）
bash deployment/demo/scenario-happy-path.sh        # 主链：下单→收银台回调→履约/权益/记账
bash deployment/demo/scenario-refund.sh           # 退款：累计不超额 + 幂等重放
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
- 收银台页：`http://localhost:8091/cashier?paymentId=...` —— 手动触发 SUCCESS / FAILURE / UNKNOWN 回调、
  连点重复回调、改金额、伪造签名（**当前形态下均放行**，见上方说明）。
- 各服务 Swagger：`http://localhost:8084/swagger-ui.html`（端口 8081~8090 同理）。
- Grafana：`http://localhost:3000`（admin/admin，内置「PaymentArch 业务指标」看板）。

## 四个场景断言点

| 场景 | 关键断言 |
| --- | --- |
| happy-path | 下单后支付为 `PROCESSING`（收银台路径）；回调后 `SUCCEEDED`；权益 `AVAILABLE` 且仅一份；账本 balanced 且分录可追溯；**重复回调幂等吸收** |
| refund | 支付 `SUCCEEDED` → 退款 `CREATED`；同幂等键重放返回同一退款；**累计超额被 409 拒（H1 防超额）** |
| payment-unknown | 支付 `UNKNOWN`（不猜成败落账）；无令牌 resolve 被 `403`；带令牌 resolve 收敛为 `FAILED` 终态 |
| reconciliation | 批次产生差异；**未处理差异时关闭被 400 拒（门禁）**；处理全部差异后关闭 `CLOSED` |

## Mock 渠道场景切换（ADR-0049）

`payment.channel.mock-scenario` 是**构造期注入**的，运行期不可热切换。需要换场景时重启支付服务：

```bash
bash deployment/demo/restart-payment.sh BUSINESS_UNKNOWN   # 渠道不给结论 → UNKNOWN 路径
bash deployment/demo/restart-payment.sh SUCCESS            # 恢复默认成功路径
```

可选值：`SUCCESS` / `FAILURE` / `TIMEOUT` / `TRANSPORT_ERROR` / `BUSINESS_UNKNOWN`。
