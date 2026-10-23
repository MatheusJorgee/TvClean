# Plano de otimização: TvClean (TCL MT9221, Android TV 11, 943 MB)

Objetivo: navegação lisa, apps abrindo rápido, volta ao Início instantânea, sem tela branca.
Regra: medir antes e depois de cada etapa; tudo reversível; nada roda enquanto a TV está em uso.

## Diagnóstico (pesquisa de 19/10/2026)

- O gargalo é RAM, não CPU. O "load 52" vem de threads do driver MStar paradas em D-state; a CPU fica ~93% ociosa.
- No Android 11 sem root não dá para ajustar o lmkd (só `ro.lmk.*`). O que funciona é ter menos processos vivos e acordando.
- A TV não usa task snapshots (FEATURE_LEANBACK). Toda troca de app mostra a starting window até o 1º frame.
- **Tela branca no Home:** o lmkd mata o launcher (adj 600) enquanto o app roda. Ao voltar, a splash usa o `windowBackground` do tema `Theme.DeviceDefault.Light`, que é branco. Piora porque o `onResume()` reconstrói tudo e o `onTrimMemory(UI_HIDDEN)` apaga o cache a cada saída.
- **Engasgo ao abrir apps:** `trimBackground()` mata os apps ao voltar ao Início, então a próxima abertura sempre vira *cold start* (segundos de CPU e I/O). Bug extra: um app aberto pelo botão Netflix do controle não conta como "último usado".
- **TA-DUM da Netflix:** é desenhado pela própria Netflix. O scale-up do launcher não suprime a splash (só scene transitions suprimem). É preciso testar se a animação aparece num cold start via adb.

## Etapa 0: medição de base (15 min, TV livre)

```
am force-stop <pkg>; am start -W -n <pkg>/<activity>          # TotalTime, LaunchState COLD
am start -W -n <pkg>/<activity>                                # WARM/HOT
am start -W -a android.intent.action.MAIN -c android.intent.category.HOME   # volta ao Início
dumpsys activity exit-info com.tvclean.home                    # o launcher morreu? motivo
logcat -b events -d | grep -E "am_kill|am_proc_start|am_proc_died"
cat /proc/vmstat | grep -E "pswpin|pswpout|workingset_refault|pgmajfault"   # delta numa sessão de 10 min
dumpsys meminfo | grep -E "Free RAM|ZRAM";  dumpsys gfxinfo com.tvclean.home   # jank do launcher
ls -l /proc/self/reclaim; ls /sys/fs/cgroup/freezer /dev/freezer               # suporte a compactação/freezer
dumpsys package dexopt | grep -A2 -E "globotv|wbd.stream|tvclean"
```

Apps medidos: Netflix, YouTube, Globoplay, HBO Max, Prime, Disney+, e a volta ao Início vinda de cada um.

## Etapa 1: launcher (código), maior impacto percebido

1. **Tema escuro:** `res/values/styles.xml` com `android:windowBackground=#0B0D12` (parent `Theme.DeviceDefault.NoActionBar`). Remover `root.setBackgroundColor`. Acaba com o flash branco. Não usar `windowDisablePreview`.
2. **Não reconstruir no `onResume`:** carregar apps uma vez e recarregar só via broadcast de pacotes (com debounce e comparando a lista). No resume: restaurar o foco e atualizar entradas e relógio.
3. **Primeira tela imediata no cold start:** salvar a ordem e os nomes dos apps no disco e desenhar a fileira na hora; os banners entram depois.
4. **`onTrimMemory` com os níveis certos:** `UI_HIDDEN` mantém tudo; `BACKGROUND` (40) libera o backdrop; `MODERATE` (60) libera os banners pré-renderizados.
5. **Trocar o "matar ao voltar ao Início"** por: *ao abrir um app de streaming, encerrar os OUTROS streamings em cache*. O app aberto ganha RAM e voltar a ele continua instantâneo. Contar também as aberturas pelo botão Netflix do controle.
6. **Executors parados enquanto o launcher está oculto** (prioridade BACKGROUND), para não disputar CPU com o app recém-aberto.
7. **Renderização:**
   - remover `translationZ` (sombra recalculada a cada frame);
   - banners pré-renderizados no tamanho exato, com cantos arredondados (sem `clipToOutline`);
   - fallback do backdrop numa View separada (evita a camada offscreen no fade);
   - `Bitmap.prepareToDraw()` no worker;
   - hero animado como um bloco (`withLayer`);
   - alturas fixas no texto do hero;
   - `GradientDrawable` reaproveitados;
   - overdraw do topo de ~5× para ~2×.
8. **Teste A/B** da abertura com scale-up × padrão do sistema (`Displayed` e olho).
9. AOT do launcher: `cmd package compile -m speed -f com.tvclean.home` (app pequeno; `speed` é ok aqui).

## Etapa 2: sistema (adb), cada item com reversão

| Ação | Comando | Reverter | Risco |
|---|---|---|---|
| Bloquear 2º plano dos streamings (Globoplay, HBO, Prime, Disney+, Netflix). **Fora:** YouTube Music, Chromecast, GMS, Play Store, launcher, teclado | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` | `... allow` | médio: testar cast do celular para Netflix/YouTube |
| Play Store sem atualização automática | pela UI da Play Store | pela UI | nenhum |
| Atualizador TCL (`com.tcl.ttvs` + core/persistent, ~48 MB) off; religar para atualizar | `pm disable-user --user 0 com.tcl.ttvs` | `pm enable com.tcl.ttvs` | baixo (**precisa do OK**) |
| Leitor de voz `com.google.android.tts` off (~10–24 MB) | `pm disable-user` | `pm enable` | baixo (**precisa do OK**) |
| AOT nos apps Java (Globoplay, HBO) | `cmd package compile -m speed-profile -f <pkg>` ou `cmd package bg-dexopt-job` | `cmd package compile --reset <pkg>` | baixo |
| Manter `max_cached_processes` 8–10 e conferir após cada boot (o sync do Google pode apagar) | `device_config get/put activity_manager max_cached_processes 10` | `device_config delete ...` | baixo |
| *Experimental:* compactação (só se existir `/proc/self/reclaim`) | `device_config put activity_manager use_compaction true` | `delete` | CPU extra |
| *Experimental:* freezer (só se o kernel suportar; precisa reboot; não libera RAM, só CPU) | `device_config put activity_manager_native_boot use_freezer true` | `false` + reboot | médio |

Mitos descartados: `setprop debug.hwui.*`, task killers, "limpar cache" periódico, whitelist do deviceidle, standby buckets (sem efeito na tomada), `max_phantom_processes` (só Android 12+).
Não mexer: Chromecast, voz (katniss), controle pelo celular, mídia USB, YouTube Music (o dono usa).

## Etapa 3: teste da Netflix (TA-DUM)

`am force-stop com.netflix.ninja; am start -W -n com.netflix.ninja/.MainActivity` → se aparecer a animação em COLD via adb, a causa está no launcher (opções de launch, warm start). Se não aparecer, é do app (versão/config) e não há o que fazer além de atualizar ou limpar o cache dele.

## Etapa 4: redesenho (depois da otimização)

- Cabeçalho: sai o Bluetooth e o botão duplicado; fica 1 Configurações (painel TCL, segurar OK = configurações completas) + 1 botão útil a escolher (Busca / Timer / Wi-Fi).
- Abas com cabeçalho e fundo fixos, transição só no conteúdo:
  - **Início:** como hoje.
  - **Apps:** grade com fixar, mover e esconder favoritos.
  - **Entradas:** cartões grandes, com nome personalizável e o nome CEC do aparelho.

## Etapa 5: imagens

- Atualização a cada **3 h**; rodízio entre o top 5 de cada streaming; crédito "Imagens: TMDB".
- YouTube: precisa da chave da YouTube Data API (opcional).
- **Pendente:** chave do TMDB.
