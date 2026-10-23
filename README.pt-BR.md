# TvClean: sua Android TV lenta, rápida de novo

[![Licença: MIT](https://img.shields.io/badge/licen%C3%A7a-MIT-blue.svg)](LICENSE)
[![Última versão](https://img.shields.io/github/v/release/MatheusJorgee/TvClean)](https://github.com/MatheusJorgee/TvClean/releases/latest)
![Android TV 8+](https://img.shields.io/badge/Android%20TV-8%2B-3ddc84)

[English](README.md) · **Português**

Uma tela inicial leve para Android TV e os scripts adb que tiraram uma TCL de 2021 do "travando em
tudo": apps de fábrica desligados, memória ajustada e um launcher que mantém tudo assim. Sem root, e
tudo pode ser desfeito.

![Tela Início do TvClean](docs/images/tvclean-inicio.png)

> [!WARNING]
> **Projeto pessoal, testado em uma TV até agora** ([lista](#tvs-testadas)). Faça backup antes e confira
> cada mudança na sua TV. **Não use se você depende de acessibilidade:** o leitor de tela, a leitura em
> voz alta e o assistente de voz são desligados, e o launcher ocupa o serviço de acessibilidade do
> sistema para a barra da tecla Início.

**Sumário:** [Por quê](#por-quê) · [Antes e depois](#antes-e-depois) · [O que tem](#o-que-tem) ·
[Instalar](#instalar) · [TVs testadas](#tvs-testadas) · [Desfazer](#desfazer) ·
[Segurança](#segurança) · [Limitações](#limitações) · [Contribuir](#contribuir)

## Por quê

TVs baratas vêm com ~1 GB de RAM, disco lotado e uma tela inicial que carrega anúncios e recomendações
da internet. Na TV onde isto começou:

- **Disco 100% cheio** (3,8 MB livres de 2,5 GB); só o Google Play Services guardava 1,2 GB.
- **Sem atualizações:** sem espaço, a Play Store não atualizava YouTube, Chromecast nem WebView.
- **Travamentos:** com ~105 MB de RAM livre, abrir um app ou voltar ao Início deixava a tela branca por
  segundos.

## Antes e depois

### A TV inteira, com tudo aplicado

| | Antes | Depois |
|---|---:|---:|
| Armazenamento usado | 100% (3,8 MB livres) | ~55% (1,1 GB livres) |
| RAM livre em uso normal | ~105 MB | ~200–380 MB |
| Atualizações pela Play Store | Bloqueadas | Funcionando |

### Launcher original × TvClean

| Launcher original (Android TV) | TvClean |
|---|---|
| ![Launcher original](docs/images/original-inicio.png) | ![TvClean](docs/images/tvclean-inicio.png) |

Mesma TV, mesmo dia, mesmos comandos para os dois:

| | Original | TvClean | Como |
|---|---:|---:|---|
| Abrir o Início com o launcher fechado | 0,75–1,19 s | 0,54–1,28 s | `am start -W` (HOME), 3 vezes |
| Memória do launcher | 85 MB | 76 MB | `dumpsys meminfo <pacote>`, TOTAL PSS |
| Tamanho do app | — | 249 KB | APK final |

No tempo de abertura os dois empatam: a diferença está dentro da variação entre medições. O ganho está
no resto: menos coisa rodando ao fundo, nada carregando da internet para desenhar o Início, e uma
manutenção que impede a TV de voltar ao estado de antes.

> As capturas pixelam pôsteres e a cidade do clima.

## O que tem

### Launcher (`launcher/`)

Java puro, sem bibliotecas de terceiros, 249 KB.

| | |
|---|---|
| ![Todos os apps](docs/images/tvclean-apps.png) | ![Entradas](docs/images/tvclean-entradas.png) |
| ![Saúde da TV](docs/images/tvclean-tv.png) | ![Dar nome a uma entrada](docs/images/tvclean-menu-entrada.png) |

- **Início** com o que está em alta no streaming em foco ([TMDB](https://www.themoviedb.org/)) e o
  vídeo mais popular do YouTube, trocando a cada 3 horas. Nada com classificação 18 (ou sem
  classificação) aparece.
- **Favoritos:** segure OK (ou aperte Menu) num app para fixar, mover ou esconder. O último app usado
  vem primeiro, marcado como **Continuar**.
- **Entradas** (HDMI, AV, antena) com estado ao vivo e nome personalizável, como "PS5".
- **Aba TV:** memória, armazenamento, tempo ligado, o que mais gasta memória e o botão **Liberar memória
  e reaplicar ajustes**.
- **Manutenção automática:** depois de cada boot e a cada 6 horas, o launcher redesliga o que o
  fabricante religou e reaplica os ajustes de memória, pela depuração da própria TV, sem PC.
- **Barra de apps:** dentro de qualquer app, a casinha abre a barra por cima; um segundo aperto vai ao
  Início. Segurar a casinha abre um painel de atalhos.

  ![Barra de apps sobre um app (wireframe)](docs/images/wireframe-barra.svg)
- Clima no cabeçalho ([Open-Meteo](https://open-meteo.com/)) e protetor de tela com as imagens em alta,
  relógio e clima.

### Scripts da TV (`tv/`)

PowerShell + adb, rodando no PC. Guardam o número de série da sua TV no primeiro uso e se recusam a
mexer em qualquer outro aparelho depois disso.

| Script | O que faz |
|---|---|
| `debloat.ps1` | Desliga a lista do seu modelo em [`tv/devices/`](tv/devices/README.md) e aplica os ajustes. `-Undo` desfaz. |
| `check.ps1` | Acha o que voltou depois de um reboot e corrige. `-ReportOnly` só mostra. |
| `cleanup.ps1` | Libera espaço: dados e atualizações dos apps desligados, APKs largados, caches. |
| `measure.ps1` | Mede a abertura dos apps (a frio e a quente), a volta ao Início e o uso de swap. |
| `startup.ps1` | Cronometra o Início em cenários diferentes (`-Scenario home`, `killed`, `dream`, `reboot`). |

## Instalar

Na TV: **Configurações → Preferências do dispositivo → Sobre**, aperte OK em *Versão* 7 vezes e depois
ligue **Opções do desenvolvedor → Depuração USB**. Muitas TVs (esta TCL inclusive) passam a aceitar adb
pela rede na porta 5555; anote o IP da TV (Configurações → Rede).

### Rápido: o APK pronto

Baixe o `TvClean.apk` em [Releases](https://github.com/MatheusJorgee/TvClean/releases/latest). Ele não
tem chaves de API dentro, então o Início mostra seus apps sem as imagens em alta
([como adicionar](#imagens-em-alta-opcional)).

**Windows**, a partir de um clone deste repositório:

```powershell
.\launcher\install.ps1 -TvHost 192.168.x.x -Apk $HOME\Downloads\TvClean.apk
.\tv\debloat.ps1 -TvHost 192.168.x.x      # opcional: desliga os apps de fábrica (lista do seu modelo) e ajusta a memória
```

**Qualquer sistema com adb** (Windows, macOS, Linux):

```sh
adb connect 192.168.x.x:5555
adb install TvClean.apk
adb shell appops set com.tvclean.home GET_USAGE_STATS allow
adb shell pm disable-user --user 0 com.google.android.tvlauncher            # Início do Android TV
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx     # Início do Google TV (se existir)
adb shell cmd package set-home-activity com.tvclean.home/.MainActivity
adb shell settings put secure enabled_accessibility_services com.tvclean.home/com.tvclean.home.KeyService
adb shell settings put secure accessibility_enabled 1                      # barra de apps e painel da casinha
```

Depois **reinicie a TV uma vez**: algumas TVs (a TCL inclusive) só ligam o serviço da casinha no boot. Na
primeira vez que usar **Liberar memória** (aba TV), a TV pergunta "Permitir a depuração USB?" para o
próprio launcher: marque **Sempre permitir**.

### Imagens em alta (opcional)

Com uma [chave gratuita do TMDB](https://www.themoviedb.org/settings/api), no APK baixado:

```sh
adb shell am start -n com.tvclean.home/.MainActivity --es tmdb_key SUA_CHAVE_TMDB
```

### Compilar

Precisa de Windows com PowerShell 5.1+, JDK 17 e Android SDK (`platforms/android-34`,
`build-tools/34.0.0`). Sem Gradle: `aapt2 → javac → d8 → zipalign → apksigner`.

```powershell
.\launcher\build.ps1                             # launcher\build\TvClean.apk
.\launcher\install.ps1 -TvHost 192.168.x.x
.\tv\check.ps1 -TvHost 192.168.x.x -ReportOnly   # deve terminar em "Tudo em ordem."
```

Opcionais, fora do Git (já ignorados): `tv/tmdb.key`, `tv/youtube.key` (YouTube Data API v3, restrita a
`com.tvclean.home` + SHA-1 do seu certificado) e `tv/city.txt` (cidade do clima; sem ele a TV é
localizada pelo IP). `-NoKeys` deixa os três de fora, para um APK que você vai compartilhar.

O keystore de assinatura é criado no primeiro build em `%USERPROFILE%\.tvclean` (ou `TVCLEAN_KEYS`).
**Guarde um backup dessa pasta:** atualizações precisam da mesma chave. Se o PowerShell recusar os
scripts, use `powershell -ExecutionPolicy Bypass -File <script>`.

Depois de atualizar o launcher, rode `.\tv\check.ps1 -TvHost <ip>` (a atualização desliga o serviço da
casinha) e reinicie a TV.

## TVs testadas

| TV | `ro.product.name` | Android | Lista de apps | Situação |
|---|---|---|---|---|
| TCL FHD 2021, MediaTek MT9221, 943 MB RAM | `C06_2K_GB` | 11 | [sim](tv/devices/C06_2K_GB.txt) | Uso diário |

O launcher deve rodar em qualquer Android TV 8+. **Testou na sua?**
[Abra um relato de TV](https://github.com/MatheusJorgee/TvClean/issues/new?template=tv-report.yml), deu
certo ou não, e ela entra nesta tabela.

## Desfazer

```powershell
.\tv\debloat.ps1 -TvHost 192.168.x.x -Undo              # religa tudo e volta os ajustes ao padrão
.\launcher\restore.ps1 -TvHost 192.168.x.x -Uninstall   # volta o Início de fábrica e remove o TvClean
```

Nada é desinstalado da TV: os apps só ficam desligados (`pm disable-user`).
Sem os scripts: `adb shell pm enable <pacote>` para cada launcher de fábrica e `adb uninstall com.tvclean.home`.

## Segurança

- **A depuração pela rede fica ligada** (a manutenção automática depende dela). Qualquer aparelho na sua
  rede pode pedir acesso: **nunca aceite** "Permitir a depuração USB?" se não foi você que pediu.
- **Chaves de API compiladas vão dentro do APK** e deixam de ser segredo quando ele é compartilhado.
  Restrinja-as no provedor, use um projeto do Google Cloud sem faturamento e compile APKs para
  compartilhar com `-NoKeys`.
- O serviço de acessibilidade lê só a tecla Início, nunca o conteúdo da tela.

## Limitações

- **O texto do app está em português do Brasil**, e o "em alta" vem do catálogo brasileiro. Traduções
  são bem-vindas ([CONTRIBUTING](CONTRIBUTING.md)).
- Algumas TVs só ligam serviços de acessibilidade no boot: reinicie depois de instalar ou atualizar.
- Teclas enviadas com `adb shell input` não chegam ao serviço de acessibilidade na TV testada; teste a
  barra e o painel com o controle de verdade.
- Sem root não dá para ler o "continuar assistindo" dos streamings nem reiniciar a TV pelo app.

## Contribuir

O que mais ajuda agora são relatos de outras TVs: um
[relato de TV](https://github.com/MatheusJorgee/TvClean/issues/new?template=tv-report.yml) ou a lista de
apps do seu modelo em [`tv/devices/`](tv/devices/README.md). Veja o [CONTRIBUTING.md](CONTRIBUTING.md).

```text
launcher/          app Android (src/com/tvclean/home), recursos, scripts de build/instalação/restauração
tv/                scripts adb; common.ps1 tem os ajustes usados por todos
tv/devices/        apps de fábrica para desligar, um arquivo por modelo de TV
docs/              imagens deste README e o plano de otimização
```

## Créditos

- Inspirado em [cleanyourtv](https://github.com/Gathin23/cleanyourtv).
- Títulos e imagens em alta: [TMDB](https://www.themoviedb.org/). Este produto usa a API do TMDB, mas
  não é endossado nem certificado pelo TMDB. Vídeos em alta: YouTube Data API.
- Fonte: [Plus Jakarta Sans](https://github.com/tokotype/PlusJakartaSans), SIL OFL 1.1.

## Licença

[MIT](LICENSE). A fonte incluída segue a própria licença (SIL OFL 1.1).
