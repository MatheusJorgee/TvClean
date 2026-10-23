# Checkup after a reboot (or any time): finds what drifted back (apps TCL switched on again, settings
# Google reset) and puts it back. Touches nothing on screen.
#   .\tv\check.ps1 -TvHost <ip-da-tv> [-ReportOnly]
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$ReportOnly)
. "$PSScriptRoot\common.ps1"
$target = Connect-Tv $TvHost
$DebloatPackages = Get-DebloatPackages
$fixed = 0
$issues = 0

function Report($ok, $what, $fix) {
    if ($ok) { return }
    $script:issues++
    if ($ReportOnly) { "  ! $what"; return }
    & $fix | Out-Null
    $script:fixed++
    "  corrigido: $what"
}

"Pacotes desativados"
$off = Tv pm list packages -d | ForEach-Object { $_ -replace '^package:', '' }
foreach ($p in $DebloatPackages + $HomePackages) {
    $installed = Tv pm path $p
    if (-not $installed) { continue }
    Report ($off -contains $p) "$p estava ativo de novo" { Tv pm disable-user --user 0 $p }
}

"Ajustes do sistema"
foreach ($t in $Tuning) {
    $now = Get-Tuning $t
    Report ($now -eq $t[2]) "$($t[1]) = '$now' (esperado $($t[2]))" { Set-Tuning $t }
}

"Apps sem segundo plano"
foreach ($p in $NoBackground) {
    if (-not (Tv pm path $p)) { continue }
    $mode = (Tv cmd appops get $p RUN_ANY_IN_BACKGROUND) -join ''
    Report ($mode -match 'ignore') "$p pode rodar em segundo plano" { Tv cmd appops set $p RUN_ANY_IN_BACKGROUND ignore }
}

"Tela inicial"
$homeActivity = (Tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim()
Report ($homeActivity -eq 'com.tvclean.home/.MainActivity') "Home aponta para '$homeActivity'" {
    Tv cmd package set-home-activity com.tvclean.home/.MainActivity
}
$usage = (Tv appops get com.tvclean.home GET_USAGE_STATS) -join ''
Report ($usage -match 'allow') "launcher sem acesso ao uso (o 'Continuar' fica limitado)" {
    Tv appops set com.tvclean.home GET_USAGE_STATS allow
}
$dexopt = (Tv dumpsys package dexopt | Select-String -Context 0, 3 '\[com.tvclean.home\]' | Out-String)
Report ($dexopt -match 'status=speed') "launcher sem compilação antecipada" { Tv cmd package compile -m speed -f com.tvclean.home }

""
$free = (Tv dumpsys meminfo | Select-String 'Free RAM').ToString().Trim()
$disk = (Tv df -h /data | Select-Object -Last 1) -replace '\s+', ' '
"RAM: $free"
"Disco: $disk"
if ($issues -eq 0) { "Tudo em ordem." }
elseif ($ReportOnly) { "$issues item(ns) fora do lugar (nada foi alterado: rodou com -ReportOnly)." }
else { "$fixed item(ns) corrigido(s)." }
