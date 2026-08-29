#!/usr/bin/env bash
# 用法: mvn.sh <maven args...>
# 在 PowerShell 下调用系统 maven（bash 下 mvn 因 classworlds 类路径问题不可用），
# 输出重定向为 UTF-16LE 日志后再转码为 UTF-8 打印。
# 注意：本地 ~/.m2 不可写（install 会失败），跨模块构建一律加 -am 走 reactor。
set -u
ROOT="C:/Users/user/Desktop/GoProj/PaymentArch"
LOG="$ROOT/.workbuddy/mvn-last.log"
ARGS=""
for a in "$@"; do
  ARGS="$ARGS '$a'"
done
powershell.exe -NoProfile -Command "
Set-Location 'C:\Users\user\Desktop\GoProj\PaymentArch'
& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -B -o $ARGS *> 'C:\Users\user\Desktop\GoProj\PaymentArch\.workbuddy\mvn-last.log'
"
iconv -f UTF-16LE -t UTF-8 "$LOG" 2>/dev/null | sed 's/\r$//' || cat "$LOG"
