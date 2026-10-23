# Measures app start times (cold and warm), the return to Home, and memory pressure.
#   .\tv\measure.ps1 -TvHost <ip-da-tv> [-Label antes]
# Writes tv\measurements\<label>.csv and prints a summary. Opens and closes apps on screen.
param([Parameter(Mandatory = $true)][string]$TvHost, [string]$Label = (Get-Date -Format 'yyyyMMdd-HHmm'))
. "$PSScriptRoot\common.ps1"
$target = Connect-Tv $TvHost

$apps = [ordered]@{
    'Netflix'     = 'com.netflix.ninja/.MainActivity'
    'YouTube'     = 'com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity'
    'Globoplay'   = 'com.globo.globotv/.splashtv.SplashActivity'
    'HBO Max'     = 'com.wbd.stream/com.wbd.beam.BeamActivity'
    'Prime Video' = 'com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity'
    'Disney+'     = 'com.disney.disneyplus/com.bamtechmedia.dominguez.main.MainActivity'
}

$HomeArgs = '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'

# `am start -W` with the given arguments: how long the screen took to show up and whether it was a cold start.
function Start-Timed([string[]]$amArgs) {
    $out = Tv am start -W @amArgs
    [pscustomobject]@{
        State = (($out | Select-String 'LaunchState') -replace '.*: ', '').ToString().Trim()
        Ms    = [int]((($out | Select-String 'TotalTime') -replace '\D', '') + '0') / 10
    }
}

function Vm { $v = @{}; Tv cat /proc/vmstat | ForEach-Object { $p = $_ -split ' '; $v[$p[0]] = [long]$p[1] }; $v }

$rows = @()
$vm0 = Vm
foreach ($name in $apps.Keys) {
    $pkg = $apps[$name].Split('/')[0]
    Tv am force-stop $pkg
    Start-Sleep -Seconds 2
    $cold = Start-Timed '-n', $apps[$name]
    Start-Sleep -Seconds 8                      # let it settle like a person would
    $back = Start-Timed $HomeArgs
    Start-Sleep -Seconds 3
    $warm = Start-Timed '-n', $apps[$name]
    Start-Sleep -Seconds 4
    $back2 = Start-Timed $HomeArgs
    Start-Sleep -Seconds 2
    $rows += [pscustomobject]@{
        App = $name; Cold = "$($cold.Ms) ms ($($cold.State))"; Warm = "$($warm.Ms) ms ($($warm.State))"
        HomeBack = "$($back.Ms) ms ($($back.State))"; HomeBack2 = "$($back2.Ms) ms ($($back2.State))"
    }
}
$vm1 = Vm
$free = (Tv dumpsys meminfo | Select-String 'Free RAM').ToString().Trim()
$kills = (Tv logcat -b events -d | Select-String 'am_kill').Count
$launcherDeaths = (Tv dumpsys activity exit-info com.tvclean.home | Select-String 'LOW_MEMORY').Count

$dir = "$PSScriptRoot\measurements"
New-Item -ItemType Directory -Force $dir | Out-Null
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 "$dir\$Label.csv"
$rows | Format-Table -AutoSize
"swap in/out durante o teste: {0} / {1} paginas" -f ($vm1['pswpin'] - $vm0['pswpin']), ($vm1['pswpout'] - $vm0['pswpout'])
"refaults: {0}   major faults: {1}" -f ($vm1['workingset_refault'] - $vm0['workingset_refault']), ($vm1['pgmajfault'] - $vm0['pgmajfault'])
"$free"
"am_kill no log: $kills   mortes do launcher por LOW_MEMORY (historico): $launcherDeaths"
