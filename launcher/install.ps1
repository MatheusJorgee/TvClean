# Installs TvClean and makes it the Home app, rolling back if Home does not land on it.
#   .\install.ps1 -TvHost <ip-da-tv> [-Apk <TvClean.apk baixado>]
# Without -Apk it installs the one launcher\build.ps1 made.
param([Parameter(Mandatory = $true)][string]$TvHost, [string]$Apk = "$PSScriptRoot\build\TvClean.apk")
$ErrorActionPreference = 'Continue'
$me = 'com.tvclean.home/.MainActivity'
. "$PSScriptRoot\..\tv\common.ps1"
$target = Connect-Tv $TvHost
if (-not (Test-Path $Apk)) { throw "APK nao encontrado: $Apk. Rode launcher\build.ps1 ou baixe o APK em Releases. Nada foi feito." }

adb -s $target install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'install failed: nothing else was changed' }

# "Continuar" reads which app was used last, including ones opened by a remote button.
Tv appops set com.tvclean.home GET_USAGE_STATS allow | Out-Null

# Ahead-of-time compile: faster first start on a slow CPU.
Tv cmd package compile -m speed -f com.tvclean.home | Out-Null
Tv am start -n $me | Out-Null
Start-Sleep -Seconds 2

# Stock launchers declare a higher HOME priority, so they have to be disabled, not just outranked.
$stock = $HomePackages | Where-Object { Tv pm path $_ }
foreach ($p in $stock) { Tv pm disable-user --user 0 $p | Out-Null }
Tv cmd package set-home-activity $me | Out-Null
Tv input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2

$resolved = (Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim()
$top = (Tv dumpsys activity activities | Select-String 'mResumedActivity' | Select-Object -First 1).ToString()
if ($resolved -ne $me -or $top -notmatch 'com.tvclean.home') {
    Write-Warning "Home resolved to '$resolved'; rolling back to the stock launcher."
    & "$PSScriptRoot\restore.ps1" -TvHost $TvHost
    exit 1
}
Write-Output "OK: Home is TvClean. Undo with .\launcher\restore.ps1 -TvHost $TvHost"
