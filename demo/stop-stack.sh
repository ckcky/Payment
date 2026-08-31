#!/usr/bin/env bash
# demo/stop-stack.sh —— 停止完整演示栈（等价于 deployment/stop-all.sh）
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
exec bash deployment/stop-all.sh
