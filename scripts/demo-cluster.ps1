# 本机三节点演示脚本（PowerShell）
# 用法：在仓库根目录执行  pwsh -File scripts/demo-cluster.ps1
# 需要 JDK 17 + Maven。脚本会启动三个节点进程、打印角色变化，最后提示如何停止。

param(
    [string]$Jar = "target/hive-mind-1.0.0.jar",
    [int]$WaitSeconds = 12
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $Jar)) {
    Write-Host "未找到 $Jar，先执行打包：mvn -B -ntp -DskipTests package" -ForegroundColor Yellow
    exit 1
}

$ports = 8101, 8102, 8103
$procs = @()

foreach ($port in $ports) {
    $nodeId = "node-$($port - 8100)"
    $peers = ($ports | Where-Object { $_ -ne $port } | ForEach-Object { "http://127.0.0.1:$_" }) -join ","
    Write-Host "启动 $nodeId :$port  peers=$peers" -ForegroundColor Cyan
    $env:HIVE_NODE_ID = $nodeId
    $env:HIVE_PORT = "$port"
    $env:HIVE_ENDPOINT = "http://127.0.0.1:$port"
    $env:HIVE_PEERS = $peers
    $env:HIVE_HEARTBEAT_MS = "1000"
    $env:HIVE_LEASE_MS = "3000"
    $procs += Start-Process -FilePath "java" -ArgumentList "-jar", $Jar -PassThru -WindowStyle Hidden
}

Write-Host "等待节点启动并完成选主（$WaitSeconds 秒）..." -ForegroundColor Yellow
Start-Sleep -Seconds $WaitSeconds

foreach ($port in $ports) {
    try {
        $state = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/cluster/state" -TimeoutSec 5
        $self = $state.data.self
        Write-Host ("{0,-8} role={1,-7} term={2,-3} brainId={3}" -f $self.nodeId, $self.role, $state.data.term, $state.data.brainId)
    } catch {
        Write-Host "http://127.0.0.1:$port 未响应：$($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "演示建议：" -ForegroundColor Green
Write-Host "  1) 停掉当前大脑节点：Stop-Process -Id <pid>，然后重新查询另外两个节点的 /api/v1/cluster/state，观察 term 增加与新大脑产生"
Write-Host "  2) 下发任务（由大脑派给有能力的神经元）："
Write-Host "     Invoke-RestMethod -Method Post -ContentType 'application/json' -Body '{\"input\":\"CALL_TOOL:echo {\\\"text\\\":\\\"hi\\\"}\"}' http://127.0.0.1:8102/api/v1/cluster/tasks?capability=chat"
Write-Host "  3) 停止全部节点："
Write-Host "     Get-Process java | Where-Object { `$_.Id -in @($($procs.Id -join ',')) } | Stop-Process"
