#!/usr/bin/env bash
# 容器模式两步合一：宿主构建 fat jar → 构建应用镜像（spec 026 / ADR-0070 D3）
#
# 背景：镜像**只 COPY 产物**，不在 Docker 内跑 Maven（10 个服务共享 3 个 common 模块，
# 若镜像内多阶段构建，公共模块会被重复编译 10 次）。代价是启动容器前必须先有 fat jar。
# 本脚本把「构建 jar」与「构建镜像」串起来，避免用户忘了第一步。
#
# 用法：
#   bash deployment/build-images.sh                 # 构建 jar + 镜像
#   PAYMENT_SKIP_BUILD=1 bash deployment/build-images.sh   # 跳过 jar，只构建镜像
#   bash deployment/build-images.sh && bash deployment/start-container.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="deployment/docker-compose.yml"
JAR_DIR="deployment/output/jars"

if [ "${PAYMENT_SKIP_BUILD:-0}" = "1" ]; then
  echo "==> [1/2] PAYMENT_SKIP_BUILD=1，跳过 jar 构建"
else
  echo "==> [1/2] 宿主构建 fat jar（输出到 $JAR_DIR，约 30s~2min）"
  # 与 start-all.sh 一致：必须 clean，避免残留半成品 class 引发运行期 NoClassDefFoundError
  "${MAVEN_BIN:-./mvnw}" ${MAVEN_ARGS:-} -q clean install -DskipTests
fi

echo "==> [2/2] 构建 10 个应用镜像"
# .dockerignore 位于仓库根（构建上下文根），已排除 **/target 与 .git，
# 使上下文只包含 deployment/output/jars/*.jar，避免把 GB 级产物送进 daemon。
docker compose -f "$COMPOSE_FILE" --profile full build

echo ""
echo "✅ 镜像构建完成。启动：bash deployment/start-container.sh"
