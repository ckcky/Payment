#!/usr/bin/env bash
#
# run.sh —— 一键启动发布包（解压后进此目录执行）
#
# 前置：JDK 21(LTS) · Docker(含 Compose v2) · 网络(首次 ./mvnw 拉取 Maven 依赖)
# 行为：校验 JDK → 启动基础设施容器(MySQL/Redis/Nacos/可观测) → 全量构建 →
#       后台拉起 10 个 JVM 进程(9 领域服务 + mock-channel-web)
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> 环境检查"
if ! command -v java >/dev/null 2>&1; then
  echo "✗ 未找到 java。请安装 JDK 21 LTS（https://adoptium.net）。" >&2
  exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ "${JAVA_VER:-0}" -lt 21 ]; then
  echo "✗ 需要 JDK 21，当前为 ${JAVA_VER}。请安装 JDK 21 LTS。" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "✗ 未找到 docker。请安装 Docker Desktop（Win/macOS）或 docker 引擎（Linux）。" >&2
  exit 1
fi
if [ ! -f deployment/start-all.sh ]; then
  echo "✗ 未在发布包根目录运行。请先解压并 cd 到 payment-platform-<version>/。" >&2
  exit 1
fi
echo "    JDK ${JAVA_VER} ✓ · docker ✓"

echo ""
echo "==> 启动全栈（等同 deployment/start-all.sh，约 1~3 分钟首次构建）"
exec bash deployment/start-all.sh
