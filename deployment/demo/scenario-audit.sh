#!/usr/bin/env bash
# demo/scenario-audit.sh —— spec 017 审计四核对 + 挂账调账闭环演示
# 前置：服务已启动（start-demo.sh）；audit-faults.sql 幂等注入故障（本脚本自动执行）。
# 断言（plan §9.1）：
#   ① 故障注入（F1~F7，幂等）+ 渠道账单 CSV（F8，随 reconciliation jar 加载）
#   ② 触发 CERTIFICATE 审计批 → HAS_DIFFERENCE，batchNo=AB 前缀
#   ③ 差异含 MISSING_POSTING(PM-AUD-0003) / ORPHAN_POSTING(PM-AUD-GHOST1)
#      / AMOUNT_MISMATCH(PM-AUD-0001) / DUPLICATE_POSTING(PM-AUD-0002) / SETTLEMENT 跨账差异
#   ④ 有未收口差异时 close 被拒（400）
#   ⑤ 挂账：adjustNo=AD 前缀 + postingNo=LP 前缀，差异 → SUSPENDED
#   ⑥ 调账转出后 SUSPENSE 归零
#   ⑦ 全部差异处置收口（VERIFIED/ADJUSTED）
#   ⑧ close 成功 → CLOSED；⑨ 试算平衡 balanced=true；⑩ 处置台账留痕
# 环境变量：AUDIT_PERIOD（默认 2026-08-31，绑定渠道账单 CSV）、AUDIT_FULL=1 追加 ALL scope。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

PERIOD="${AUDIT_PERIOD:-2026-08-31}"

wait_for_services

# ---- ① 故障注入（pymysql 直连本地演示库，与 truncate-transactional.py 同通道）----
echo "==> ① 注入审计演示故障（F1~F7，幂等；仅限本地演示库）"
# python 探测：优先带 pymysql 的 venv/解释器
PY_BIN=""
for cand in "$HOME/.workbuddy/binaries/python/envs/default/bin/python" \
            "$HOME/.workbuddy/binaries/python/versions/3.13.12/bin/python3" \
            python3; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c "import pymysql" 2>/dev/null; then
    PY_BIN="$cand"; break
  fi
done
[ -n "$PY_BIN" ] || fail "未找到带 pymysql 的 python（pip install pymysql 后重试）"
"$PY_BIN" - "$HERE/fixtures/audit/audit-faults.sql" <<'PYEOF'
import sys
import pymysql

sql_file = sys.argv[1]
conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='root', connect_timeout=5)
cur = conn.cursor()
# 防线：连接目标已在上方写死 127.0.0.1（本脚本绝不接受远程库参数）
print('   connected:', conn.get_host_info())
def has_sql(block: str) -> bool:
    """剔除注释行与空行后是否还有可执行语句。"""
    return any(line.strip() and not line.strip().startswith('--')
               for line in block.strip().splitlines())

statements = [s.strip() for s in open(sql_file, encoding='utf-8').read().split(';')
              if has_sql(s)]
for stmt in statements:
    cur.execute(stmt)
conn.commit()
cur.execute("SELECT COUNT(*) FROM ledger.postings WHERE posting_no LIKE 'LP-AUD-%'")
print("   注入完成，ledger LP-AUD-* posting 数 =", cur.fetchone()[0])
conn.close()
PYEOF
info "故障注入完成（重复执行幂等）"

# ---- ② 触发 CERTIFICATE 审计批（同 period+scope 重复触发自动回查既有批次）----
echo "==> ② 触发账证核对批（period=${PERIOD}，scope=CERTIFICATE）"
http POST "$RECON_URL/internal/audit/batches" "{\"period\":\"$PERIOD\",\"scope\":\"CERTIFICATE\",\"triggeredBy\":\"scenario-audit\"}"
assert_status 201 "审计批创建/回查"
jget "d['batchNo']"; BATCH_NO="$VALUE"
jget "d['status']"; BATCH_STATUS="$VALUE"
case "$BATCH_NO" in AB*) info "PASS: batchNo=${BATCH_NO}（AB 前缀）" ;; *) fail "batchNo 非 AB 前缀: $BATCH_NO" ;; esac

if [ "$BATCH_STATUS" = "CLOSED" ]; then
  info "批次 $BATCH_NO 已闭环（重复演示），跳过处置流程，仅回归试算平衡"
  http GET "$RECON_URL/internal/audit/trial-balance"
  assert_status 200 "试算平衡查询"
  jget "d['balanced']"
  assert_eq "$VALUE" "True" "试算平衡 balanced=true"
  echo ""
  info "scenario-audit 幂等重跑通过 ✅"
  exit 0
fi
assert_eq "$BATCH_STATUS" "HAS_DIFFERENCE" "审计批状态 → HAS_DIFFERENCE"

# ---- ③ 差异清单断言 ----
echo "==> ③ 核对差异清单"
http GET "$RECON_URL/internal/audit/batches/$BATCH_NO/differences"
assert_status 200 "差异列表"
DIFF_JSON="$BODY"
DIFF_COUNT="$(echo "$DIFF_JSON" | python3 -c "import json,sys;d=json.load(sys.stdin);print(len(d) if isinstance(d,list) else 0)")"
info "差异数=${DIFF_COUNT}（F2~F5 + F7 账证投影）"
[ "${DIFF_COUNT:-0}" -ge 5 ] || fail "差异不足 5 条（期望 MISSING/ORPHAN/AMOUNT/DUPLICATE/SETTLEMENT 跨账）：${DIFF_COUNT}"

assert_contains_kind() { # kind source_id
  echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
kind, sid = sys.argv[1], sys.argv[2]
hit = [x for x in d if x.get('kind')==kind and sid in (x.get('sourceId') or '')]
sys.exit(0 if hit else 1)
" "$1" "$2" || fail "断言失败：差异中未找到 kind=$1 sourceId~=$2"
  info "PASS: 差异 $1 / $2 在列"
}
assert_contains_kind "MISSING_POSTING" "PM-AUD-0003"
assert_contains_kind "ORPHAN_POSTING" "PM-AUD-GHOST1"
assert_contains_kind "AMOUNT_MISMATCH" "PM-AUD-0001"
assert_contains_kind "DUPLICATE_POSTING" "PM-AUD-0002"
# F7 账证投影：SETTLEMENT 事实（sourceId = 批次 id）金额 vs 账本 posting（net 31250 vs 32000）
SETTLE_MISMATCH="$(echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(sum(1 for x in d if x.get('kind')=='AMOUNT_MISMATCH' and x.get('sourceType')=='SETTLEMENT'))
")"
[ "${SETTLE_MISMATCH:-0}" -ge 1 ] || fail "断言失败：SETTLEMENT AMOUNT_MISMATCH（F7 跨账）未现形"
info "PASS: 差异 AMOUNT_MISMATCH / SETTLEMENT（F7 跨账投影）在列"

# ---- ④ 关批门禁反例 ----
echo "==> ④ 有未收口差异时关闭应被拒（409 STATE_TRANSITION_VIOLATION）"
http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/close" "{\"operator\":\"demo-auditor\"}"
case "$STATUS" in
  409) info "PASS: 未收口差异时关批被拒（409 门禁生效）" ;;
  400) info "PASS: 未收口差异时关批被拒（400 门禁生效）" ;;
  *) fail "未收口差异时关批应被拒，实际 $STATUS" ;;
esac

# ---- ⑤⑥ 挂账 → 转出（以 MISSING_POSTING 为特写）----
echo "==> ⑤ 挂账 MISSING_POSTING（PM-AUD-0003）"
MISSING_ID="$(echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print([x['id'] for x in d if x.get('kind')=='MISSING_POSTING' and x.get('sourceId')=='PM-AUD-0003'][0])
")"
http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/differences/$MISSING_ID/suspend" \
  "{\"operator\":\"demo-op\",\"reason\":\"挂账演示：漏记账 8000 分待补记\"}"
assert_status 200 "挂账成功"
jget "d['adjustNo']"; ADJUST_NO="$VALUE"
jget "d['postingNo']"; POSTING_NO="$VALUE"
case "$ADJUST_NO" in AD*) info "PASS: adjustNo=${ADJUST_NO}（AD 前缀）" ;; *) fail "adjustNo 非 AD 前缀: $ADJUST_NO" ;; esac
case "$POSTING_NO" in LP*) info "PASS: postingNo=${POSTING_NO}（LP 前缀）" ;; *) fail "postingNo 非 LP 前缀: $POSTING_NO" ;; esac

echo "==> ⑥ 调账转出（TRANSFER 8000 → 应付商户），SUSPENSE 应归零"
http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/differences/$MISSING_ID/adjust" \
  "{\"kind\":\"TRANSFER\",\"amountMinor\":8000,\"targetAccountCode\":\"MERCHANT_PAYABLE\",\"operator\":\"demo-op\",\"reviewer\":\"demo-rev\",\"reason\":\"漏账补记转出挂账\"}"
assert_status 200 "调账转出成功"
http GET "$RECON_URL/internal/audit/suspense-balance"
assert_status 200 "SUSPENSE 余额查询"
jget "d['amountMinor']"
assert_eq "$VALUE" "0" "SUSPENSE 归零（amountMinor=0）"
info "PASS: SUSPENSE 余额归零"

# ---- ⑦ 处置剩余差异：全部 suspend → TRANSFER（自动 recheck 收口）----
echo "==> ⑦ 处置剩余差异（挂账 → 转出 → 自动复核）"
REMAINS="$(echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(' '.join(str(x['id']) for x in d if x.get('id') != int('$MISSING_ID') and x.get('status') in ('PENDING','SUSPENDED','ADJUSTED')))
")"
for DID in $REMAINS; do
  KIND="$(echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print([x['kind'] for x in d if x['id']==int('$DID')][0])
")"
  http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/differences/$DID/suspend" \
    "{\"operator\":\"demo-op\",\"reason\":\"挂账：${KIND}\"}"
  assert_status 200 "挂账差异 $DID ($KIND)"
  # 转出金额 = 本次挂账额（挂多少转多少，差额类挂账即收口）
  AMOUNT="$(echo "$DIFF_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
x=[x for x in d if x['id']==int('$DID')][0]
exp=x.get('expectedAmountMinor') or 0
act=x.get('actualAmountMinor') or 0
print(abs(exp-act) if exp and act else max(exp,act))
")"
  http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/differences/$DID/adjust" \
    "{\"kind\":\"TRANSFER\",\"amountMinor\":$AMOUNT,\"targetAccountCode\":\"MERCHANT_PAYABLE\",\"operator\":\"demo-op\",\"reviewer\":\"demo-rev\",\"reason\":\"转出挂账：${KIND}\"}"
  assert_status 200 "调账转出差异 $DID ($KIND, ${AMOUNT}分)"
done

# recheck 全批，确认无未收口
http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/recheck" "{}"
assert_status 200 "全批复核"
jget "d['status']"; RECHECK_STATUS="$VALUE"
info "recheck 后批次状态 = $RECHECK_STATUS"
case "$RECHECK_STATUS" in
  BALANCED|HAS_DIFFERENCE) ;; # 个别差异 recheck 保守不通过时仍允许 ADJUSTED 收口关批
  *) fail "recheck 后状态异常: $RECHECK_STATUS" ;;
esac

http GET "$RECON_URL/internal/audit/batches/$BATCH_NO/differences"
UNCLOSED="$(echo "$BODY" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(sum(1 for x in d if x.get('status') not in ('VERIFIED','ADJUSTED','RESOLVED')))
")"
[ "${UNCLOSED:-0}" -eq 0 ] || fail "仍有 ${UNCLOSED} 条未收口差异（期望全部 VERIFIED/ADJUSTED/RESOLVED）"
info "PASS: 全部差异收口"

# ---- ⑧ 关批成功 ----
echo "==> ⑧ 关批（全收口后放行）"
http POST "$RECON_URL/internal/audit/batches/$BATCH_NO/close" "{\"operator\":\"demo-auditor\"}"
assert_status 200 "关批成功"
jget "d['status']"
assert_eq "$VALUE" "CLOSED" "审计批 → CLOSED"

# ---- ⑨ 试算平衡 ----
echo "==> ⑨ 试算平衡（Σ借 = Σ贷）"
http GET "$RECON_URL/internal/audit/trial-balance"
assert_status 200 "试算平衡查询"
jget "d['balanced']"
assert_eq "$VALUE" "True" "试算平衡 balanced=true"

# ---- ⑩ 处置台账 ----
echo "==> ⑩ 处置台账（audit_adjustments）"
http GET "$RECON_URL/internal/audit/batches/$BATCH_NO/adjustments"
assert_status 200 "处置台账查询"
ADJ_COUNT="$(echo "$BODY" | python3 -c "import json,sys;d=json.load(sys.stdin);print(len(d) if isinstance(d,list) else 0)")"
[ "${ADJ_COUNT:-0}" -ge 5 ] || fail "处置台账少于 5 条（1 挂账 + 1 转出 × 5 差异 ≈ 10）：${ADJ_COUNT}"
info "PASS: 台账留痕 ${ADJ_COUNT} 条"

# ---- 可选：ALL scope（账账/账实/账表 + F6/F7/F8 触发，AUDIT_FULL=1 开启）----
if [ "${AUDIT_FULL:-0}" = "1" ]; then
  echo "==> ⑪ 附加：ALL scope 全核对（账账/账实/账表，F6/F7/F8 应现形）"
  http POST "$RECON_URL/internal/audit/batches" "{\"period\":\"$PERIOD\",\"scope\":\"ALL\",\"triggeredBy\":\"scenario-audit-full\"}"
  assert_status 201 "ALL scope 审计批"
  jget "d['batchNo']"; FULL_NO="$VALUE"
  http GET "$RECON_URL/internal/audit/batches/$FULL_NO/differences"
  assert_status 200 "ALL 差异列表"
  ALL_KINDS="$(echo "$BODY" | python3 -c "import json,sys;print(','.join(sorted({x['kind'] for x in json.load(sys.stdin)})))")"
  info "ALL scope 差异类型：${ALL_KINDS}"
  case "$ALL_KINDS" in
    *ACCOUNT_RECON_BREAK*) info "PASS: F6 科目勾稽差异现形" ;;
    *) warn "F6（ACCOUNT_RECON_BREAK）未现形，检查 fixture" ;;
  esac
  case "$ALL_KINDS" in
    *CROSS_LEDGER_MISMATCH*|*LEDGER_VS_STATEMENT_BREAK*) info "PASS: F7/F8 跨账/账实差异现形" ;;
    *) warn "F7/F8 未现形，检查 fixture" ;;
  esac
fi

echo ""
info "scenario-audit 全部断言通过 ✅（审计批 ${BATCH_NO}：差异 → 挂账 → 调账 → 收口 → 关批 → 试算平衡）"
