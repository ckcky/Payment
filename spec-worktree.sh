#!/usr/bin/env bash
# spec-worktree.sh —— Spec / 纯文档编写的 worktree 隔离工作流
#
# 背景：主工作区常有进行中的 feature WIP（如 feature/019），直接在其上写 spec 会混淆
# 提交边界。本脚本为每个 spec 开独立 worktree + docs 分支，完成后经 docs-only 校验
# 直推远端 master（engineering-standards §6 直推白名单），全程不触碰主工作区。
#
# 用法（在仓库任意目录执行）：
#   ./spec-worktree.sh new  <NNN>-<slug>   # 基于 origin/master 新建 worktree + docs/spec-<NNN>-<slug> 分支
#   ./spec-worktree.sh push <NNN>-<slug>   # docs-only 校验 → 提交 → 直推 origin/master
#   ./spec-worktree.sh rm   <NNN>-<slug> [-f]  # 清理 worktree 与分支；-f 连同未跟踪文件强删
#   ./spec-worktree.sh list                 # 列出所有 worktree
#
# 环境变量：PAYMENT_WT_ROOT 覆盖 worktree 根目录（默认 <仓库>/../Payment-wt）
#
# 注意：仅限纯文档改动（docs/** 与 *.md）。代码改动必须走 feature 分支 + PR（§6）。
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then echo "❌ 请在 Payment 仓库内执行"; exit 1; fi
WT_ROOT="${PAYMENT_WT_ROOT:-$(dirname "$REPO_ROOT")/Payment-wt}"

usage() { echo "用法: $0 {new|push|rm|list} [NNN-slug]"; exit 1; }
branch_of() { echo "docs/spec-$1"; }
wt_of() { echo "$WT_ROOT/$1"; }

CMD="${1:-}"; SLUG="${2:-}"
case "$CMD" in
  new)
    [ -n "$SLUG" ] || usage
    git fetch origin master --quiet
    mkdir -p "$WT_ROOT"
    git worktree add "$(wt_of "$SLUG")" -b "$(branch_of "$SLUG")" origin/master
    echo "✅ worktree 已就绪：$(wt_of "$SLUG")"
    echo "   分支：$(branch_of "$SLUG")（基于最新 origin/master）"
    echo "   下一步：在该目录编写 spec/文档 → 完成后 $0 push $SLUG"
    ;;
  push)
    [ -n "$SLUG" ] || usage
    DIR="$(wt_of "$SLUG")"; [ -d "$DIR" ] || { echo "❌ worktree 不存在：$DIR（先 $0 new $SLUG）"; exit 1; }
    cd "$DIR"
    # docs-only 白名单校验：改动路径仅允许 docs/** 或 *.md
    BAD="$(git status --porcelain | cut -c4- | grep -vE '(^docs/|\.md$)' || true)"
    if [ -n "$BAD" ]; then
      echo "❌ 发现非 docs-only 改动，禁止直推 master（应走 feature 分支 + PR）："
      echo "$BAD"; exit 1
    fi
    git add -A
    if git diff --cached --quiet; then echo "（无改动，跳过）"; exit 0; fi
    git commit -m "docs(${SLUG%%-*}): ${SLUG#*-}——worktree 隔离提交（spec-worktree.sh）"
    git push origin "HEAD:master"
    echo "✅ 已直推 origin/master。清理请执行：$0 rm $SLUG"
    ;;
  rm)
    [ -n "$SLUG" ] || usage
    FORCE="${3:-}"
    if ! git worktree remove "$(wt_of "$SLUG")" 2>/dev/null; then
      if [ "$FORCE" = "-f" ]; then
        git worktree remove --force "$(wt_of "$SLUG")"
      else
        echo "❌ worktree 含未跟踪/未提交文件，拒绝删除（防误删工作成果）。"
        echo "   确认可丢弃后执行：$0 rm $SLUG -f"
        exit 1
      fi
    fi
    git branch -D "$(branch_of "$SLUG")"
    echo "✅ 已清理 worktree 与分支 $(branch_of "$SLUG")"
    ;;
  list)
    git worktree list
    ;;
  *) usage ;;
esac
