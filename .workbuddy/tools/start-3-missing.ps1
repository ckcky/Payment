# 拉起剩余 3 个服务，显式 --server.port 覆盖配置解析异常。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe'
$root = 'C:\Users\user\Desktop\GoProj\PaymentArch'
$logs = Join-Path $root '.workbuddy\tools\logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

$services = @(
    @{ Module = 'reconciliation-service'; Port = 8088 },
    @{ Module = 'settlement-service';     Port = 8089 },
    @{ Module = 'ledger-service';         Port = 8090 }
)

$report = @()
$pids = @()
foreach ($s in $services) {
    $jar = Join-Path $root "$($s.Module)\target\$($s.Module)-0.1.0-SNAPSHOT.jar"
    $out = Join-Path $logs "$($s.Module).v2.out.log"
    $err = Join-Path $logs "$($s.Module).v2.err.log"
    if (-not (Test-Path $jar)) { $report += "MISSING-JAR $($s.Module)"; continue }
    try {
        $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, "--server.port=$($s.Port)") `
            -WorkingDirectory (Join-Path $root $s.Module) `
            -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $pids += "$($s.Module)=$($p.Id)"
        $report += "STARTED $($s.Module) port=$($s.Port) pid=$($p.Id)"
    } catch {
        $report += "START-FAILED $($s.Module) $_"
    }
}
$pids | Set-Content -Path (Join-Path $tools 'pids-3.txt') -Encoding UTF8

$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    $allUp = $true
    foreach ($s in $services) {
        $up = $false
        try { $r = Invoke-WebRequest -Uri "http://localhost:$($s.Port)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue; if ($r.StatusCode -eq 200) { $up = $true } } catch { }
        if (-not $up) { $allUp = $false }
    }
    if ($allUp) { break }
    Start-Sleep -Seconds 3
}
foreach ($s in $services) {
    $status = 'DOWN'
    try { $r = Invoke-WebRequest -Uri "http://localhost:$($s.Port)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue; if ($r.StatusCode -eq 200) { $status = 'UP' } } catch { }
    $report += "FINAL $($s.Module) port=$($s.Port) $status"
}
$report | Set-Content -Path (Join-Path $tools 'start-3-report.txt') -Encoding UTF8
$report | ForEach-Object { Write-Output $_ }
