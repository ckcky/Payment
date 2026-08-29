# 逐个拉起全部 10 个服务（真实 MySQL：localhost:3306）。
# 用 Start-Process 以分离进程启动，脚本结束后服务继续存活；PID 记录在 pids.txt。
$ErrorActionPreference = 'Continue'
$root = 'C:\Users\user\Desktop\GoProj\PaymentArch'
$tools = Join-Path $root '.workbuddy\tools'
$logs = Join-Path $tools 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

$services = @(
    @{ Module = 'merchant-service';       Port = 8081 },
    @{ Module = 'catalog-service';        Port = 8082 },
    @{ Module = 'order-service';          Port = 8083 },
    @{ Module = 'payment-service';        Port = 8084 },
    @{ Module = 'refund-service';         Port = 8085 },
    @{ Module = 'fulfillment-service';    Port = 8086 },
    @{ Module = 'entitlement-service';    Port = 8087 },
    @{ Module = 'reconciliation-service'; Port = 8088 },
    @{ Module = 'settlement-service';     Port = 8089 },
    @{ Module = 'ledger-service';         Port = 8090 }
)

$report = @()
$pids = @()

foreach ($s in $services) {
    $jar = Join-Path $root "$($s.Module)\target\$($s.Module)-0.1.0-SNAPSHOT.jar"
    $out = Join-Path $logs "$($s.Module).out.log"
    $err = Join-Path $logs "$($s.Module).err.log"
    if (-not (Test-Path $jar)) {
        $report += "MISSING-JAR  $($s.Module)  $jar"
        continue
    }
    $p = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $pids += "$($s.Module)=$($p.Id)"
    $report += "STARTED      $($s.Module)  port=$($s.Port)  pid=$($p.Id)"
}

$pids | Set-Content -Path (Join-Path $tools 'pids.txt') -Encoding UTF8

# 等待健康检查（每个服务最多 90s）
foreach ($s in $services) {
    $ok = $false
    $url = "http://localhost:$($s.Port)/actuator/health"
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-RestMethod -Uri $url -TimeoutSec 3
            if ($r.status -eq 'UP') { $ok = $true; break }
        } catch { }
    }
    if ($ok) {
        $report += "HEALTHY      $($s.Module)  http://localhost:$($s.Port)"
    } else {
        $report += "NOT-HEALTHY  $($s.Module)  http://localhost:$($s.Port)  (see logs\$($s.Module).err.log)"
    }
}

$report | Set-Content -Path (Join-Path $tools 'start-report.txt') -Encoding UTF8
$report | ForEach-Object { Write-Output $_ }
