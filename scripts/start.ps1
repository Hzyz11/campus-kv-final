param([switch]$Check)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
Set-Location -LiteralPath $projectRoot
$java = (Get-Command java -ErrorAction Stop).Source
if (!(Test-Path -LiteralPath 'target/classes/edu/baodex/campus/KvCluster.class') -or !(Test-Path -LiteralPath 'target/lib/ratis-server-3.3.1.jar')) { throw '缺少运行文件，请先执行 scripts/build.ps1。' }
$owned = @()
foreach ($name in @('node-0','node-1','node-2','web')) {
 $pidFile = Join-Path $projectRoot "data/$name.pid"
 if (Test-Path -LiteralPath $pidFile) {
  $processId = [int]([IO.File]::ReadAllText($pidFile).Trim())
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
  if ($proc -and $proc.CommandLine -and $proc.CommandLine.Contains($projectRoot) -and $proc.CommandLine -match 'edu\.baodex\.campus\.(Application|KvCluster)') { $owned += $processId }
 }
}
if ($owned.Count -eq 4) { Write-Output '本目录的服务已启动：http://127.0.0.1:8081'; return }
if ($owned.Count -gt 0) { throw '本目录存在部分运行进程，请先执行 scripts/stop.ps1，再重新启动。' }
$ports = @(8081,19000,19001,19002,19100,19101,19102,19300,19301,19302)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort })
if ($listeners.Count -gt 0) { throw ('所需端口被占用：' + (($listeners.LocalPort | Sort-Object -Unique) -join ', ') + '。请先在原运行目录停止服务，本脚本不会终止其他目录的进程。') }
if ($Check) { Write-Output 'Java、运行文件和端口检查通过。'; return }
New-Item -ItemType Directory -Force data,logs | Out-Null
$cp = '"' + (Join-Path $projectRoot 'target/classes') + ';' + (Join-Path $projectRoot 'target/lib/*') + '"'
try {
 foreach ($i in 0..2) {
  $p = Start-Process -FilePath $java -ArgumentList @('-cp',$cp,'edu.baodex.campus.KvCluster',"$i") -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "logs/node-$i.log" -RedirectStandardError "logs/node-$i.err" -PassThru
  $p.Id | Set-Content -LiteralPath "data/node-$i.pid"
 }
 $p = Start-Process -FilePath $java -ArgumentList @('-cp',$cp,'edu.baodex.campus.Application','--spring.profiles.active=distributed') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput 'logs/web.log' -RedirectStandardError 'logs/web.err' -PassThru
 $p.Id | Set-Content -LiteralPath 'data/web.pid'
} catch { & (Join-Path $PSScriptRoot 'stop.ps1'); throw }
Write-Output '服务已启动，等待约10秒后访问 http://127.0.0.1:8081；节点页 /lab.html。启动失败请查看 logs。'
