# Puts the stock Android TV / Google TV home back. -Uninstall also removes TvClean.
#   .\restore.ps1 -TvHost <ip-da-tv> [-Uninstall]
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$Uninstall)
. "$PSScriptRoot\..\tv\common.ps1"
$target = Connect-Tv $TvHost
foreach ($p in $HomePackages) { if (Tv pm path $p) { Tv pm enable $p | Out-Null } }
if ($Uninstall) { adb -s $target uninstall com.tvclean.home | Out-Null }

# Home goes to the first stock launcher that answers (not TvClean, not the setup wizard).
$homes = Tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -match '/' }
$stock = $homes | Where-Object { $_ -notmatch 'com.tvclean.home|setupwraith|FallbackHome' } | Select-Object -First 1
if ($stock) { Tv cmd package set-home-activity $stock | Out-Null }
Tv input keyevent KEYCODE_HOME
Write-Output "OK: stock launcher restored ($stock)."
