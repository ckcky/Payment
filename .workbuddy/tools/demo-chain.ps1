# 端到端链路 Demo：merchant -> catalog -> order(自动级联 payment/fulfillment/entitlement/ledger) -> refund -> reconciliation -> settlement
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Continue'

$logPath = 'C:\Users\user\Desktop\GoProj\PaymentArch\.workbuddy\tools\demo-chain.log'
Set-Content -Path $logPath -Value '' -Encoding utf8
$log = [System.Collections.Generic.List[string]]::new()
function Log($m) { $log.Add($m); Write-Output $m; Add-Content -Path $logPath -Value $m -Encoding utf8 }

$M = 'http://localhost:8081'  # merchant
$C = 'http://localhost:8082'  # catalog
$O = 'http://localhost:8083'  # order
$P = 'http://localhost:8084'  # payment
$R = 'http://localhost:8085'  # refund
$F = 'http://localhost:8086'  # fulfillment
$E = 'http://localhost:8087'  # entitlement
$RC = 'http://localhost:8088' # reconciliation
$S = 'http://localhost:8089'  # settlement
$L = 'http://localhost:8090'  # ledger

# 每次运行使用唯一 tag，避免与既有数据/唯一约束冲突（商户码、商品码、对账 period、幂等键）
$runTag = "{0:yyyyMMddHHmmss}" -f (Get-Date)
if (-not $runTag) { $runTag = (Get-Random).ToString() }
$period = "2026-08-29-$runTag"

function PostJson($base, $path, $body) {
    $json = $body | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Uri "$base$path" -Method Post -ContentType 'application/json' -Body $json -TimeoutSec 15 -ErrorAction Stop
        return @{ ok = $true; data = $r; raw = $json }
    } catch {
        $msg = "POST $base$path FAILED: $($_.Exception.Message)"
        if ($_.Exception.Response) {
            try { $sr = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream()); $msg += " BODY=" + $sr.ReadToEnd() } catch {}
        }
        return @{ ok = $false; data = $null; raw = $json; err = $msg }
    }
}
function GetJson($base, $path) {
    try {
        $r = Invoke-RestMethod -Uri "$base$path" -Method Get -TimeoutSec 15 -ErrorAction Stop
        return @{ ok = $true; data = $r }
    } catch {
        return @{ ok = $false; data = $null; err = "GET $base$path FAILED: $($_.Exception.Message)" }
    }
}

Log "==================== PAYMENT ARCH — 端到端链路 DEMO ===================="
Log "period = $period"

# 1) 商户注册 + 审批
Log "`n[1] 注册商户 merchant-service"
$mr = PostJson $M '/merchants' @{ code="M-$runTag"; name='Demo Merchant'; settlementAccountRef='ACC-001' }
if (-not $mr.ok) { Log $mr.err; exit 1 }
$merchantId = $mr.data.id
Log "  -> MerchantResponse: $(ConvertTo-Json $mr.data -Compress)"

Log "[2] 审批商户 -> ACTIVE & settlementEligible"
$ar = PostJson $M "/merchants/$merchantId/approve" @{}
if (-not $ar.ok) { Log $ar.err; exit 1 }
Log "  -> status=$($ar.data.status) settlementEligible=$($ar.data.settlementEligible)"

# 3) 商品 + SKU + 激活
Log "`n[3] catalog-service: 创建商品"
$pr = PostJson $C '/products' @{ productCode="P-$runTag"; name='Demo Product'; type='DIGITAL' }
if (-not $pr.ok) { Log $pr.err; exit 1 }
$productId = $pr.data.id
Log "  -> productId=$productId"

Log "[4] 创建 SKU"
$kr = PostJson $C '/skus' @{ skuCode="SKU-$runTag"; productId=$productId; name='Demo SKU'; priceMinor=1000; currencyCode='CNY'; deliveryDefinition='instant' }
if (-not $kr.ok) { Log $kr.err; exit 1 }
$skuId = $kr.data.id
Log "  -> skuId=$skuId priceMinor=$($kr.data.priceMinor)"

Log "[5] 激活 SKU -> SELLABLE"
$act = PostJson $C "/skus/$skuId/activate" @{}
if (-not $act.ok) { Log $act.err; exit 1 }
Log "  -> sku status=$($act.data.status)"

# 6) 下单（自动级联 payment -> fulfillment -> entitlement -> ledger）
Log "`n[6] order-service: 创建订单（自动触发支付/履约/权益/记账）"
$or = PostJson $O '/orders' @{ userId='U001'; merchantId=[string]$merchantId; items=@(@{ skuId=$skuId; quantity=2 }) }
if (-not $or.ok) { Log $or.err; exit 1 }
$order = $or.data
$orderId = $order.orderId
$paymentId = $order.paymentId
Log "  -> CreateOrderResponse: $(ConvertTo-Json $order -Compress)"
Log "  -> orderId=$orderId paymentId=$paymentId paymentStatus=$($order.paymentStatus) totalMinor=$($order.totalMinor)"

# 7) 查询支付
Log "`n[7] payment-service: GET /payments/$paymentId"
$pq = GetJson $P "/payments/$paymentId"
if ($pq.ok) { Log "  -> $(ConvertTo-Json $pq.data -Compress)" } else { Log $pq.err }

# 8) 台账平衡 + 分录
Log "`n[8] ledger-service: GET /internal/ledger/balance"
$lb = GetJson $L '/internal/ledger/balance'
if ($lb.ok) { Log "  -> $(ConvertTo-Json $lb.data -Compress)" } else { Log $lb.err }
Log "    ledger entries for payment: GET /internal/ledger/entries?sourceType=PAYMENT&sourceId=$paymentId"
$le = GetJson $L "/internal/ledger/entries?sourceType=PAYMENT&sourceId=$paymentId"
if ($le.ok) { Log "  -> entries=$(ConvertTo-Json $le.data -Compress)" } else { Log $le.err }

# 9) 退款（部分退 500）
Log "`n[9] refund-service: POST /internal/refunds"
$rref = PostJson $R '/internal/refunds' @{ orderId=[string]$orderId; paymentId=$paymentId; userId='U001'; amountMinor=500; currencyCode='CNY'; reason='user requested'; idempotencyKey="refund-$runTag"; items=@(@{ orderItemId='1'; amountMinor=500 }) }
if (-not $rref.ok) { Log $rref.err } else { Log "  -> $(ConvertTo-Json $rref.data -Compress)" }

# 10) 对账
Log "`n[10] reconciliation-service: POST /internal/reconciliation/batches period=$period"
$rec = PostJson $RC '/internal/reconciliation/batches' @{ period=$period }
if (-not $rec.ok) { Log $rec.err } else {
    $batch = $rec.data
    $batchId = $batch.id
    Log "  -> $(ConvertTo-Json $batch -Compress)"
    # 11) 列出差异并全部收敛
    Log "[11] 列出差异并收敛（fixture 含 channel-extra-1，必然有差异）"
    $diffs = GetJson $RC "/internal/reconciliation/batches/$batchId/differences"
    if ($diffs.ok) {
        $dlist = $diffs.data
        if ($dlist -is [System.Array]) { $dc = $dlist.Count } else { $dc = 1; $dlist = @($dlist) }
        Log "  -> differenceCount=$dc"
        foreach ($d in $dlist) {
            $ref = $d.reference
            Log "    resolve difference reference=$ref type=$($d.type) amount=$($d.amountMinor)"
            $res = PostJson $RC "/internal/reconciliation/batches/$batchId/differences/resolve" @{ reference=$ref; resolutionNote='demo auto-resolve' }
            if ($res.ok) { Log "      -> resolved ok" } else { Log "      -> $($res.err)" }
        }
    } else { Log $diffs.err }
    # 12) 结算汇总
    Log "[12] settlement-summary?period=$period"
    $sum = GetJson $RC "/internal/reconciliation/settlement-summary?period=$period"
    if ($sum.ok) { Log "  -> $(ConvertTo-Json $sum.data -Compress)" } else { Log $sum.err }
}

# 13) 结算
Log "`n[13] settlement-service: POST /internal/settlements/batches"
$set = PostJson $S '/internal/settlements/batches' @{ merchantId=[string]$merchantId; period=$period; idempotencyKey="settle-$runTag" }
if (-not $set.ok) { Log $set.err } else { Log "  -> $(ConvertTo-Json $set.data -Compress)" }

Log "`n==================== DEMO COMPLETE ===================="
$log | Out-File -FilePath 'C:\Users\user\Desktop\GoProj\PaymentArch\.workbuddy\tools\demo-chain.log' -Encoding utf8
Log "log written to .workbuddy/tools/demo-chain.log"
