param([string]$MavenCommand='mvn',[string]$MavenRepository,[switch]$Offline)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
if (!(Get-Command $MavenCommand -ErrorAction SilentlyContinue)) { throw '未找到 Maven。请安装 Maven 3.9+ 并加入 PATH，或用 -MavenCommand 指定 mvn.cmd。' }
$arguments = @('test','compile','dependency:copy-dependencies','-DincludeScope=runtime','-DoutputDirectory=target/lib')
if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$MavenRepository" }
if ($Offline) { $arguments += '-o' }
& $MavenCommand @arguments
if ($LASTEXITCODE -ne 0) { throw '构建或测试失败，查看上方 Maven 输出。' }
Write-Output '测试与编译完成；运行文件在 target/classes 和 target/lib。'
