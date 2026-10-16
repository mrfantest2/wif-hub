# Installs a hidden per-user gateway task at logon (no new console window).
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'gateway.py'
$python = (Get-Command python.exe -ErrorAction Stop).Source
$pythonw = Join-Path (Split-Path $python -Parent) 'pythonw.exe'
if (-not (Test-Path $pythonw)) { throw "pythonw.exe missing next to $python" }
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$script`" serve"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -Hidden -RestartCount 12 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Seconds 0)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'WiF HUB Gateway' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Write-Host "Registered hidden WiF HUB Gateway startup task for $user."
Write-Host "Run now (if port is free): Start-ScheduledTask -TaskName 'WiF HUB Gateway'"
Write-Host "Gateway approval code: python $script show-code"
