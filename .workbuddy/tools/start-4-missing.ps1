# 拉起缺失的 4 个服务，显式传入 --server.port 覆盖任何配置解析异常（java -jar 下偶发端口落到 auto）。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe'
$root = 'C:\Users\user\Desktop\GoProj\PaymentArch'
$tools = Join-Path $root '.workbuddy\tools'
$logs = Join-Path $tools 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

$services = @(
    @{ Module = 'refund-service';         Port = 8085 },
    @{ Module = 'reconciliation-service'; Port = 8088 },
    @{ Module = 'settlement-service';     Port = 8089 },
    @{ Module = 'ledger-service';         Port = 8090 }
)

# 先清掉上一轮落到随机端口的实例
$pids4 = Join-Path $tools 'pids-4.txt'
if (Test-Path $pids4) {
    foreach ($line in (Get-Content $pids4)) {
        if ($line -match '=(\d+)$') {
            $pid = $Matches[1]
            try { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
}

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
    try {
        $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, "--server.port=$($s.Port)") `
            -WorkingDirectory (Join-Path $root $s.Module) `
            -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $pids += "$($s.Module)=$($p.Id)"
        $report += "STARTED      $($s.Module)  port=$($s.Port)  pid=$($p.Id)"
    } catch {
        $report += "START-FAILED $($s.Module)  $_"
    }
}
$pids | Set-Content -Path $pids4 -Encoding UTF8

$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
    $allUp = $true
    foreach ($s in $services) {
        $up = $false
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$($s.Port)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue
            if ($r.StatusCode -eq 200) { $up = $true }
        } catch { }
        if (-not $up) { $allUp = $false }
    }
    if ($allUp) { break }
    Start-Sleep -Seconds 3
}

foreach ($s in $services) {
    $status = 'DOWN'
    $bound = ''
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$($s.Port)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue
        if ($r.StatusCode -eq 200) { $status = 'UP' }
    } catch { }
    $report += "FINAL        $($s.Module)  port=$($s.Port)  $status"
}
$report | Set-Content -Path (Join-Path $tools 'start-4-report.txt') -Encoding UTF8
$report | ForEach-Object { Write-Output $_ }
