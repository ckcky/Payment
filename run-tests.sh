#!/usr/bin/env bash
#
# run-tests.sh —— 运行单元测试（解压后进此目录执行）
# 行为：./mvnw test （首次会拉取 Maven 依赖，需网络）
# 注：纯单元测试无需启动基础设施；集成测试相关模块遵循各自约定。
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> 运行单元测试（./mvnw test）"
./mvnw -B test
