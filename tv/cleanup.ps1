# Frees storage: for every package that debloat.txt keeps disabled, drops its data and its Play Store
# update (back to the factory version) and disables it again right away; deletes TCL's leftover
# download APKs; trims app caches. Touches nothing on screen.
#   .\tv\cleanup.ps1 -TvHost <ip-da-tv>
param([Parameter(Mandatory = $true)][string]$TvHost)
. "$PSScriptRoot\common.ps1"
$target = Connect-Tv $TvHost

$before = (Tv df /data | Select-Object -Last 1) -split '\s+'
foreach ($p in $DebloatPackages + $HomePackages) {
    if (-not (Tv pm path $p)) { continue }
    $updated = (Tv pm path $p) -match '/data/app/'
    Tv pm clear $p | Out-Null
    if ($updated) {
        # Removing an update re-enables the package (that once brought the setup wizard back at boot),
        # so it is disabled again in the same breath.
        Tv pm uninstall-system-updates $p | Out-Null
        Tv pm disable-user --user 0 $p | Out-Null
        "${p}: atualizacao removida, dados apagados, desligado"
    } else {
        "${p}: dados apagados"
    }
}
Tv 'rm -f /sdcard/com.tcl.ttvs/download/*.tmp.apk'
Tv pm trim-caches 999G
$after = (Tv df /data | Select-Object -Last 1) -split '\s+'
$freed = ([long]$after[3] - [long]$before[3]) / 1024
"Liberado: {0:N0} MB  (livre agora: {1:N0} MB)" -f $freed, ([long]$after[3] / 1024)
