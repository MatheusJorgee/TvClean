# Puts the stock Android TV home back. -Uninstall also removes TvClean.
#   .\restore.ps1 -TvHost <ip-da-tv> [-Uninstall]
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$Uninstall)
. "$PSScriptRoot\..\tv\common.ps1"
$target = Connect-Tv $TvHost
$stock = 'com.google.android.tvlauncher'
adb -s $target shell pm enable $stock | Out-Null
adb -s $target shell cmd package set-home-activity "$stock/.MainActivity" | Out-Null
if ($Uninstall) { adb -s $target uninstall com.tvclean.home | Out-Null }
adb -s $target shell input keyevent KEYCODE_HOME
Write-Output 'OK: stock launcher restored.'
