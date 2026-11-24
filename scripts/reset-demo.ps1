$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./scripts/stop.ps1
$taskRoot = (Get-Location).Path
$sourcePath = [IO.Path]::GetFullPath((Join-Path $taskRoot 'data'))
$destPath = [IO.Path]::GetFullPath((Join-Path $taskRoot ('data-backups/demo-' + (Get-Date -Format yyyyMMdd-HHmmss))))
if (!$sourcePath.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar) -or !$destPath.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar)) { throw '备份路径超出项目' }
New-Item -ItemType Directory -Force (Split-Path $destPath -Parent) | Out-Null
if (Test-Path $sourcePath) { Move-Item -LiteralPath $sourcePath -Destination $destPath }
Write-Output "旧数据已备份到 $destPath。运行 scripts/start.ps1 启动空集群。"
