#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "✗ 缺少依赖: $cmd"
    echo "  请先安装并启动 Docker Desktop / Java / Maven / Node.js，或在 Git Bash / PowerShell 中保证其在 PATH 上。"
    exit 1
  fi
}

require_cmd docker
require_cmd curl
require_cmd java

if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker Desktop / docker engine 未运行或未就绪。"
  echo "  请先启动 Docker Desktop，再重新执行："
  echo "    bash deployment/demo/start-demo.sh"
  exit 1
fi

if [ ! -f "$ROOT_DIR/mvnw" ] && [ ! -f "$ROOT_DIR/mvnw.cmd" ]; then
  echo "✗ 未找到 Maven Wrapper。"
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "[WARN] 未检测到 node，性能脚本仍可用，但前端/压测脚本可能需要手动补齐。"
fi

if [ -n "${WSL_DISTRO_NAME:-}" ] || [ -n "${WSL_INTEROP:-}" ]; then
  echo "[INFO] 检测到 WSL 环境；建议在 Git Bash / Windows 原生 shell 运行本脚本，以避免 localhost 代理和 PATH 解析异常。"
fi

echo "==> 依赖检查通过"
echo "==> 清理历史残留 Java 进程（避免 stale classpath / in-memory state 干扰演示）"
if command -v taskkill >/dev/null 2>&1; then
  taskkill //F //IM java.exe 2>/dev/null || true
fi

echo "==> 启动 Docker 基础设施与 Java 演示栈"
bash deployment/start-all.sh || exit 1

echo "==> 等待 11 个服务健康..."
healthy=0
for i in $(seq 1 60); do
  n=0
  for p in 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091; do
    c=$(curl -s --noproxy '*' -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$p/actuator/health" 2>/dev/null)
    [ "$c" = "200" ] && n=$((n+1))
  done
  [ "$n" = "11" ] && { healthy=1; break; }
  sleep 4
done
if [ "$healthy" != "1" ]; then
  echo "✗ 服务未全部就绪（$n/11）。查看日志：deployment/logs/<service>.log"
  exit 1
fi
echo "    11/11 全部健康"

echo "==> 播种演示数据（商户 + 种子 SKU，/demo 页面直接可用）"
bash "$ROOT_DIR/deployment/demo/reset.sh"

echo ""
echo "=================================================="
echo "  演示就绪：http://localhost:8091/demo"
echo "  （若页面早于播种打开，点一下「刷新 SKU」按钮即可）"
echo "=================================================="
