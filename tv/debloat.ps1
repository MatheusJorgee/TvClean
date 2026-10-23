# Applies this TV's list in tv\devices and the tuning in common.ps1 over adb, or undoes them with -Undo.
#   .\tv\debloat.ps1 -TvHost <ip-da-tv> [-Undo]
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$Undo)
. "$PSScriptRoot\common.ps1"
$target = Connect-Tv $TvHost
$DebloatPackages = Get-DebloatPackages

if ($Undo) {
    foreach ($p in $DebloatPackages) { "$p => $(Tv pm enable $p)" }
    Tv settings put secure screensaver_components com.google.android.backdrop/com.google.android.backdrop.Backdrop
    foreach ($k in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') { Tv settings put global $k 1.0 }
    Tv device_config delete activity_manager max_cached_processes
    Tv device_config delete activity_manager use_compaction
    foreach ($p in $NoBackground) { Tv cmd appops set $p RUN_ANY_IN_BACKGROUND allow }
    return
}

# The photo screensaver is in the list: point the system at another one before disabling it
# ($Tuning below then picks the launcher's own when it is installed).
Tv settings put secure screensaver_components com.android.dreams.basic/.Colors
foreach ($p in $DebloatPackages) {
    $r = Tv pm disable-user --user 0 $p
    "$p => $r"
}
foreach ($t in $Tuning) { Set-Tuning $t }
foreach ($p in $NoBackground) { Tv cmd appops set $p RUN_ANY_IN_BACKGROUND ignore }
