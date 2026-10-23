# Measures how long Home takes to show up, in one scenario, with the launcher's TvClean timing log,
# the system's process/activity events and a burst of screen captures (dark captures = black screen).
#   .\tv\startup.ps1 -TvHost <ip-da-tv> -Scenario home|killed|dream|reboot [-Label x]
# Opens apps and presses keys on the TV: only run it when nobody is watching.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [Parameter(Mandatory = $true)][ValidateSet('home', 'killed', 'dream', 'reboot')][string]$Scenario,
    [string]$Label = (Get-Date -Format 'HHmmss')
)
. "$PSScriptRoot\common.ps1"
$target = Connect-Tv $TvHost
$out = "$PSScriptRoot\measurements\startup-$Scenario-$Label"
New-Item -ItemType Directory -Force $out | Out-Null

function Burst([int]$count) {
    # screencap takes ~0.3-0.8 s here, so these are roughly every 0.5-1 s.
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $rows = @()
    for ($i = 0; $i -lt $count; $i++) {
        $t = $sw.ElapsedMilliseconds
        Tv screencap -p /sdcard/sc.png 2>$null | Out-Null
        adb -s $target pull /sdcard/sc.png "$out\$('{0:D2}' -f $i).png" 2>$null | Out-Null
        $size = (Get-Item "$out\$('{0:D2}' -f $i).png" -ErrorAction SilentlyContinue).Length
        $rows += [pscustomobject]@{ Shot = $i; Ms = $t; KB = [math]::Round($size / 1KB) }
    }
    Tv rm -f /sdcard/sc.png | Out-Null
    $rows
}

$netflix = 'com.netflix.ninja/.MainActivity'
switch ($Scenario) {
    'home' {
        Tv am start -n $netflix | Out-Null; Start-Sleep -Seconds 12
        Tv logcat -c; Tv logcat -b events -c
        Tv input keyevent KEYCODE_HOME
    }
    'killed' {
        Tv am start -n $netflix | Out-Null; Start-Sleep -Seconds 12
        Tv am kill com.tvclean.home; Start-Sleep -Seconds 1
        Tv logcat -c; Tv logcat -b events -c
        Tv input keyevent KEYCODE_HOME
    }
    'dream' {
        Tv am start -n com.android.systemui/.Somnambulator | Out-Null; Start-Sleep -Seconds 10
        Tv logcat -c; Tv logcat -b events -c
        Tv input keyevent KEYCODE_DPAD_CENTER
    }
    'reboot' {
        Tv logcat -c; Tv logcat -b events -c
        adb -s $target reboot
        Start-Sleep -Seconds 40
        for ($k = 0; $k -lt 30; $k++) {
            adb connect $target | Out-Null
            if ((Tv getprop sys.boot_completed) -match '1') { break }
            Start-Sleep -Seconds 3
        }
    }
}
$shots = Burst 12
$shots | Format-Table -AutoSize | Out-String | Set-Content "$out\shots.txt"
$shots | Format-Table -AutoSize

"--- launcher (TvClean)"
Tv logcat -d -s TvClean:I | Select-String 'TvClean' | ForEach-Object { ($_ -split 'TvClean: ')[-1] } | Tee-Object "$out\launcher.txt"
"--- sistema"
Tv logcat -b events -d | Select-String -Pattern 'am_proc_start|am_proc_died|am_kill|am_create_activity|am_restart_activity|am_relaunch|wm_activity_launch_time|boot_progress_enable_screen|sf_stop_bootanim' |
    Where-Object { $_ -match 'tvclean|tcl.tv|boot_progress|bootanim' } |
    ForEach-Object { $_.ToString().Substring(6) } | Tee-Object "$out\events.txt"
"capturas em $out"
