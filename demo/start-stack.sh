#!/usr/bin/env bash
# demo/start-stack.sh —— 启动完整演示栈（MySQL + Prometheus/Grafana + 9 微服务 + mock-channel-web）
# 等价于 deployment/start-all.sh；区别在于本脚本在演示目录下、打印更聚焦的入口提示。
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
exec bash deployment/start-all.sh
