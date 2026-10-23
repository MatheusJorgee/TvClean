# Shared by the tv\ and launcher\ scripts: which TV this is and what it should look like after the cleanup.

# The scripts only talk to one TV: the router could later hand its IP to another device. The serial
# number is remembered on first use in tv\tv.serial (git-ignored) and checked on every run after that.
function Connect-Tv([string]$TvHost) {
    $target = "$TvHost`:5555"
    adb connect $target | Out-Null
    $prop = { param($p) ((adb -s $target shell getprop $p) -join '').Trim() }
    $serial = & $prop ro.serialno
    if (-not (& $prop ro.build.version.sdk)) { throw "Nenhuma TV respondeu em $target. Confira o IP e a depuracao USB. Nada foi feito." }
    $known = "$PSScriptRoot\tv.serial"
    if (Test-Path $known) {
        if ((Get-Content $known -Raw).Trim() -ne $serial) {
            throw "A TV em $target tem outro numero de serie ($serial). Se trocou de TV, apague tv\tv.serial. Nada foi feito."
        }
    } elseif ($serial) {
        Set-Content -Encoding ascii $known $serial
    }
    Write-Host "TV: $(& $prop ro.product.manufacturer) $(& $prop ro.product.model) ($(& $prop ro.product.name)), Android $(& $prop ro.build.version.release)"
    return $target
}

# The apps to switch off on this TV: tv\devices\<ro.product.name>.txt (comments and blank lines dropped).
# A TV without a list only gets the generic part (home screen, memory tuning); see tv\devices\README.md.
function Get-DebloatPackages {
    $name = ((Tv getprop ro.product.name) -join '').Trim()
    $list = "$PSScriptRoot\devices\$name.txt"
    if (-not (Test-Path $list)) {
        Write-Warning "Sem lista de apps para '$name' em tv\devices: so os ajustes gerais serao aplicados."
        return @()
    }
    Get-Content $list | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
}

# Stock home screens TvClean replaces (install.ps1 disables the installed ones, restore.ps1 brings them
# back): Android TV, Google TV and the setup wizard, which also answers to the Home key.
$HomePackages = 'com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx',
    'com.google.android.tungsten.setupwraith'

# Streaming apps may not run in the background when they are not on screen (no jobs, alarms or
# services). YouTube Music is left out so it keeps playing with the screen on another app.
$NoBackground = 'com.netflix.ninja', 'com.globo.globotv', 'com.wbd.stream',
    'com.amazon.amazonvideo.livingroom', 'com.disney.disneyplus'

# Global settings and device_config values, as "where key value".
$Tuning = @(
    @('global', 'window_animation_scale', '0.5'),
    @('global', 'transition_animation_scale', '0.5'),
    # animator_duration_scale stays at 1: halving it also halves every in-app animation.
    @('global', 'animator_duration_scale', '1.0'),
    # Hold Home in any app: the launcher's quick panel (an accessibility service, key events only).
    @('secure', 'enabled_accessibility_services', 'com.tvclean.home/com.tvclean.home.KeyService'),
    @('secure', 'accessibility_enabled', '1'),
    # The launcher's own screensaver (trending pictures, clock, weather).
    @('secure', 'screensaver_components', 'com.tvclean.home/.Dream'),
    # Android keeps up to 32 apps cached by default; with 943 MB that means constant swapping.
    @('device_config', 'activity_manager max_cached_processes', '10'),
    # Cached apps get their memory compacted (this kernel has /proc/<pid>/reclaim).
    @('device_config', 'activity_manager use_compaction', 'true')
)

# Runs a command on the TV connected by Connect-Tv (the caller keeps its address in $target).
function Tv { adb -s $target shell @args }

# One entry of $Tuning: where/key/value in the system, and back.
function Get-Tuning($t) {
    if ($t[0] -eq 'device_config') { return ((Tv device_config get @($t[1] -split ' ')) -join '').Trim() }
    ((Tv settings get $t[0] $t[1]) -join '').Trim()
}
function Set-Tuning($t) {
    if ($t[0] -eq 'device_config') { Tv device_config put @($t[1] -split ' ') $t[2] }
    else { Tv settings put $t[0] $t[1] $t[2] }
}
