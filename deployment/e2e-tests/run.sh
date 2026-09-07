#!/usr/bin/env bash
# e2e-tests 执行器（spec 022 / T410，FR-001 / FR-015）
# 用法：bash deployment/e2e-tests/run.sh [-Dtest=类名#方法名]
# 前置：本地栈已起（run-payment-services.sh + docker compose up -d）
set -euo pipefail
cd "$(dirname "$0")/../.."   # 仓库根

echo "== 1/3 栈就绪检查 =="
PORTS=(8081 8082 8083 8084 8086 8087 8088 8089 8090 8091)
for p in "${PORTS[@]}"; do
  code=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -m 3 "http://localhost:${p}/actuator/health" || true)
  if [ "$code" != "200" ]; then
    echo "✗ 服务 :${p} 未就绪（health=${code}）——先起栈：deployment/run-payment-services.sh"
    exit 1
  fi
done
echo "✓ 10 服务全部就绪"

echo "== 2/3 执行 E2E（-De2e.env=local）=="
./mvnw -B -pl deployment/e2e-tests test -De2e.env=local "$@" || {
  echo "== 3/3 失败诊断产物 =="
  echo "dump 目录：deployment/e2e-tests/target/e2e-dump/（响应体 / trace / invariants.log）"
  exit 1
}

echo "== 3/3 报告 =="
echo "surefire 报告：deployment/e2e-tests/target/surefire-reports/"
echo "dump 产物：    deployment/e2e-tests/target/e2e-dump/（仅失败用例）"
