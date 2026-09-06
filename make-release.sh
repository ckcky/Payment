#!/usr/bin/env bash
#
# make-release.sh —— 生成本项目的「可直接下载运行」发布包
#
# 产物： payment-platform-<VERSION>.tar.gz （仓库根目录）
#        payment-platform-<VERSION>.tar.gz.sha256 （校验和）
#
# 说明：
#   - 使用 `git archive` 导出一棵干净的源码树：自动排除 .git / target / logs /
#     .workbuddy 等（按 .gitignore）。
#   - 包内含：完整源码 + Maven Wrapper(mvnw) + deployment/（docker-compose、启动脚本、
#     压测脚本）+ 顶层 run.sh / stop.sh / run-tests.sh / run-stress.sh / RELEASE.md。
#   - 解压后 `./run.sh` 即可拉起全栈（基础设施容器 + 10 个 JVM 进程）。
#
# 用法： bash make-release.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

VERSION="$(tr -d '[:space:]' < VERSION 2>/dev/null || true)"
if [ -z "$VERSION" ]; then
  echo "✗ 找不到 VERSION 文件（应在仓库根目录）" >&2
  exit 1
fi

ARCHIVE="payment-platform-${VERSION}"
TARBALL="${ARCHIVE}.tar.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 打包 ${ARCHIVE}"
echo "    （git archive，自动排除 .git / target / logs / .workbuddy）"

git archive --format=tar HEAD --prefix="${ARCHIVE}/" | tar -x -C "$TMP"
tar -czf "$ROOT_DIR/$TARBALL" -C "$TMP" "$ARCHIVE"

( cd "$ROOT_DIR" && sha256sum "$TARBALL" > "${TARBALL}.sha256" )

SIZE="$(du -h "$ROOT_DIR/$TARBALL" | cut -f1)"
echo "==> 完成"
echo "    包   : $TARBALL ($SIZE)"
echo "    校验 : ${TARBALL}.sha256"
echo "    用法 : tar -xzf $TARBALL && cd $ARCHIVE && ./run.sh"
