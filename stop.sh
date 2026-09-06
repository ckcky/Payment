#!/usr/bin/env bash
#
# stop.sh —— 停止发布包（解压后进此目录执行）
# 行为：杀掉 10 个 JVM 进程 + 停止基础设施容器（保留 MySQL 数据卷）
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ ! -f deployment/stop-all.sh ]; then
  echo "✗ 未在发布包根目录运行。" >&2
  exit 1
fi

exec bash deployment/stop-all.sh
