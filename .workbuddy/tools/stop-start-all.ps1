# 停止全部项目相关 java 进程（排除 IDE），再用最新 jar 以显式 --server.port 拉起全部 10 个服务。
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Continue'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe'
$root = 'C:\Users\user\Desktop\GoProj\PaymentArch'
$logs = Join-Path $root '.workbuddy\tools\logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

# 1) 停止项目进程（保留 IDE：GradleServer / JDT Language Server）
$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' }
$killed = @()
foreach ($p in $procs) {
    $cmd = $p.CommandLine
    if (-not $cmd) { continue }
    $isProject = ($cmd -match 'com\.payment\.') -or ($cmd -match 'spring-boot:run') -or ($cmd -match 'target\\[a-z-]+-0\.1\.0-SNAPSHOT\.jar')
    $isIDE = ($cmd -match 'GradleServer') -or ($cmd -match 'jdt\.ls') -or ($cmd -match 'redhat\.java') -or ($cmd -match 'gradle-server')
    if ($isProject -and -not $isIDE) {
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; $killed += $p.ProcessId } catch { }
    }
}
"killed $($killed.Count) project java procs: $($killed -join ',')" | Set-Content -Path (Join-Path $logs 'restart.log') -Encoding utf8
Start-Sleep -Seconds 5

# 2) 启动全部 10 个（显式 --server.port 覆盖 java -jar 下的端口解析异常）
$services = @(
    @{ m='merchant-service';        p=8081 },
    @{ m='catalog-service';         p=8082 },
    @{ m='order-service';           p=8083 },
    @{ m='payment-service';         p=8084 },
    @{ m='refund-service';          p=8085 },
    @{ m='fulfillment-service';     p=8086 },
    @{ m='entitlement-service';     p=8087 },
    @{ m='reconciliation-service';  p=8088 },
    @{ m='settlement-service';      p=8089 },
    @{ m='ledger-service';          p=8090 }
)
$pids = @()
foreach ($s in $services) {
    $jar = Join-Path $root "$($s.m)\target\$($s.m)-0.1.0-SNAPSHOT.jar"
    $out = Join-Path $logs "$($s.m).run.out.log"
    $err = Join-Path $logs "$($s.m).run.err.log"
    if (-not (Test-Path $jar)) { "MISSING $($s.m) $jar" | Out-File -Append -Path (Join-Path $logs 'restart.log') -Encoding utf8; continue }
    try {
        $pr = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, "--server.port=$($s.p)") `
            -WorkingDirectory (Join-Path $root $s.m) -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
        $pids += "$($s.m)=$($pr.Id)"
    } catch {
        "START-FAIL $($s.m) $_" | Out-File -Append -Path (Join-Path $logs 'restart.log') -Encoding utf8
    }
}
$pids | Set-Content -Path (Join-Path $logs 'pids-all.txt') -Encoding UTF8

# 3) 轮询全部 10
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    $allUp = $true
    foreach ($s in $services) {
        $up = $false
        try { $r = Invoke-WebRequest -Uri "http://localhost:$($s.p)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue; if ($r.StatusCode -eq 200) { $up = $true } } catch { }
        if (-not $up) { $allUp = $false }
    }
    if ($allUp) { break }
    Start-Sleep -Seconds 3
}
$report = @()
foreach ($s in $services) {
    $st = 'DOWN'
    try { $r = Invoke-WebRequest -Uri "http://localhost:$($s.p)/actuator/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue; if ($r.StatusCode -eq 200) { $st = 'UP' } } catch { }
    $report += "$($s.m) $($s.p) $st"
}
$report | Out-File -Append -Path (Join-Path $logs 'restart.log') -Encoding utf8
$report | ForEach-Object { Write-Output $_ }
