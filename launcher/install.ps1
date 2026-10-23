# Installs TvClean.apk and makes it the Home app, rolling back if Home does not land on it.
#   .\install.ps1 -TvHost <ip-da-tv>
param([Parameter(Mandatory = $true)][string]$TvHost)
$ErrorActionPreference = 'Continue'
$apk = "$PSScriptRoot\build\TvClean.apk"
$me = 'com.tvclean.home/.MainActivity'
$stock = 'com.google.android.tvlauncher'
. "$PSScriptRoot\..\tv\common.ps1"
$target = Connect-Tv $TvHost


adb -s $target install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'install failed: nothing else was changed' }

# "Continuar" reads which app was used last, including ones opened by a remote button.
Tv appops set com.tvclean.home GET_USAGE_STATS allow | Out-Null

# Ahead-of-time compile: faster first start on a slow CPU.
Tv cmd package compile -m speed -f com.tvclean.home | Out-Null
Tv am start -n $me | Out-Null
Start-Sleep -Seconds 2

# The stock launcher declares HOME priority 2, so it has to be disabled, not just outranked.
Tv pm disable-user --user 0 $stock | Out-Null
Tv cmd package set-home-activity $me | Out-Null
Tv input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2

$resolved = (Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim()
$top = (Tv dumpsys activity activities | Select-String 'mResumedActivity' | Select-Object -First 1).ToString()
if ($resolved -ne 'com.tvclean.home/.MainActivity' -or $top -notmatch 'com.tvclean.home') {
    Write-Warning "Home resolved to '$resolved'; rolling back to the stock launcher."
    Tv pm enable $stock | Out-Null
    Tv cmd package set-home-activity "$stock/.MainActivity" | Out-Null
    Tv input keyevent KEYCODE_HOME
    exit 1
}
Write-Output "OK: Home is TvClean. Undo with .\restore.ps1 -TvHost $TvHost"
