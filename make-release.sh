#!/usr/bin/env bash
#
# make-release.sh —— 生成「可直接运行」的发布包（预构建二进制，非源码）
#
# 产物：payment-platform-<VERSION>-bin.tar.gz（仓库根目录）+ .sha256 校验和
#
# 包内含：
#   jars/                    10 个 Spring Boot fat jar（Maven 打包后统一落 deployment/output/jars）
#   start.sh / stop.sh       一键启停：基础设施容器 + java -jar 直跑（无需 Maven/构建）
#   reset-demo.sh            复位演示数据 + 灌种子
#   deployment/              docker-compose / 建表 SQL / 演示场景 / k6 压测 / 发行版 README
#   README.md                使用说明（快速开始 / 端口表 / 环境变量）
#
# 目标机器只需：JDK 21 + Docker（含 Compose v2）。
#
# 用法：bash make-release.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

VERSION="$(tr -d '[:space:]' < VERSION 2>/dev/null || true)"
[ -n "$VERSION" ] || { echo "✗ 找不到 VERSION 文件（应在仓库根目录）" >&2; exit 1; }

ARCHIVE="payment-platform-${VERSION}"
TARBALL="${ARCHIVE}-bin.tar.gz"
STAGE="$(mktemp -d)/${ARCHIVE}"
trap 'rm -rf "$(dirname "$STAGE")"' EXIT

echo "==> [1/4] 全量构建 fat jar（统一落 deployment/output/jars/）"
./mvnw -q clean package -DskipTests
JARS="$ROOT_DIR/deployment/output/jars"
JAR_COUNT=$(ls "$JARS"/*.jar 2>/dev/null | wc -l | tr -d ' ')
[ "$JAR_COUNT" = "10" ] || { echo "✗ 构建产物应为 10 个 jar，实际 ${JAR_COUNT}（deployment/output/jars/）" >&2; exit 1; }

echo "==> [2/4] 组装发行包目录"
mkdir -p "$STAGE/jars" "$STAGE/deployment/logs"
cp "$JARS"/*.jar "$STAGE/jars/"
cp deployment/release/start.sh deployment/release/stop.sh deployment/release/reset-demo.sh "$STAGE/"
cp deployment/release/README.md "$STAGE/README.md"
echo "$VERSION" > "$STAGE/VERSION"
# 运行时所需的 deployment 资产（不含源码模块 / mock-channel-web 源 / 构建产物）
mkdir -p "$STAGE/deployment/demo" "$STAGE/deployment/performance"
cp deployment/docker-compose.yml "$STAGE/deployment/"
cp -R deployment/initdb deployment/prometheus deployment/grafana deployment/loki \
      deployment/promtail deployment/schema "$STAGE/deployment/"
cp deployment/demo/*.sh deployment/demo/README.md "$STAGE/deployment/demo/"
cp -R deployment/demo/fixtures "$STAGE/deployment/demo/"
cp deployment/performance/*.js "$STAGE/deployment/performance/" 2>/dev/null || true
chmod +x "$STAGE/start.sh" "$STAGE/stop.sh" "$STAGE/reset-demo.sh" "$STAGE"/deployment/demo/*.sh

echo "==> [3/4] 打 tar.gz"
tar -czf "$ROOT_DIR/$TARBALL" -C "$(dirname "$STAGE")" "$ARCHIVE"

echo "==> [4/4] 生成 sha256"
( cd "$ROOT_DIR" && shasum -a 256 "$TARBALL" > "${TARBALL}.sha256" )

SIZE="$(du -h "$ROOT_DIR/$TARBALL" | cut -f1)"
echo "==> 完成"
echo "    包   : $TARBALL ($SIZE)"
echo "    校验 : ${TARBALL}.sha256"
echo "    用法 : tar -xzf $TARBALL && cd $ARCHIVE && bash start.sh && bash reset-demo.sh"
