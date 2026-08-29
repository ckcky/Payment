# 停止 start-services.ps1 拉起的全部服务（按 PID 精确停止，不影响其他 java 进程）。
$tools = 'C:\Users\user\Desktop\GoProj\PaymentArch\.workbuddy\tools'
$pidFile = Join-Path $tools 'pids.txt'
if (-not (Test-Path $pidFile)) { Write-Output 'pids.txt not found'; exit 0 }

$report = @()
foreach ($line in (Get-Content $pidFile)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '='
    $name = $parts[0]
    $id = [int]$parts[1]
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc) {
        Stop-Process -Id $id -Force -Confirm:$false
        $report += "STOPPED  $name  pid=$id"
    } else {
        $report += "ALREADY-GONE  $name  pid=$id"
    }
}
Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
$report | Set-Content -Path (Join-Path $tools 'stop-report.txt') -Encoding UTF8
$report | ForEach-Object { Write-Output $_ }
