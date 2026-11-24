$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
foreach ($item in @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'data') -Filter '*.pid' -ErrorAction SilentlyContinue)) {
 $processId = [int]([IO.File]::ReadAllText($item.FullName).Trim())
 $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
 if (!$proc) { Remove-Item -LiteralPath $item.FullName; continue }
 if (!$proc.CommandLine -or !$proc.CommandLine.Contains($projectRoot) -or $proc.CommandLine -notmatch 'edu\.baodex\.campus\.(Application|KvCluster)') { Write-Warning "无法核对本目录进程 $processId，保留PID文件并跳过。"; continue }
 Stop-Process -Id $processId -Force
 Remove-Item -LiteralPath $item.FullName
}
Write-Output '本目录服务停止操作完成，数据保留在 data。'
