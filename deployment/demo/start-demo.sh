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
echo "==> 启动 Docker 基础设施与 Java 演示栈"
exec bash deployment/start-all.sh
