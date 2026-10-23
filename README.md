# TvClean: make a slow Android TV fast again

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/MatheusJorgee/TvClean)](https://github.com/MatheusJorgee/TvClean/releases/latest)
![Android TV 8+](https://img.shields.io/badge/Android%20TV-8%2B-3ddc84)

**English** · [Português](README.pt-BR.md)

A lightweight home screen for Android TV, plus the adb scripts that took a 2021 TCL from "freezes on
everything" back to smooth: factory bloat switched off, memory tuned, and a launcher that keeps it that
way. No root, and every change can be undone.

![TvClean home screen](docs/images/tvclean-inicio.png)

> [!WARNING]
> **Personal project, tested on one TV so far** ([list](#tested-tvs)). Back up first and check each
> change on your TV. **Do not use it if you rely on accessibility features:** the screen reader,
> text-to-speech and voice assistant get switched off, and the launcher takes the system's
> accessibility service for its Home-key bar.

**Contents:** [Why](#why) · [Before and after](#before-and-after) · [Features](#features) ·
[Install](#install) · [Tested TVs](#tested-tvs) · [Undo](#undo) · [Security](#security) ·
[Limitations](#limitations) · [Contributing](#contributing)

## Why

Cheap smart TVs ship with ~1 GB of RAM, a full disk and a home screen that loads ads and
recommendations from the internet. On the TV this started on:

- **The disk was 100% full** (3.8 MB free of 2.5 GB); Google Play Services alone kept 1.2 GB.
- **No updates:** with no space, the Play Store could not update YouTube, Chromecast or WebView.
- **Freezes:** with ~105 MB of free RAM, opening an app or going back Home showed a blank screen for
  seconds.

## Before and after

### The whole TV, with everything applied

| | Before | After |
|---|---:|---:|
| Storage used | 100% (3.8 MB free) | ~55% (1.1 GB free) |
| Free RAM in normal use | ~105 MB | ~200–380 MB |
| Play Store updates | Blocked | Working |

### Stock launcher vs TvClean

| Stock launcher (Android TV) | TvClean |
|---|---|
| ![Stock launcher](docs/images/original-inicio.png) | ![TvClean](docs/images/tvclean-inicio.png) |

Same TV, same day, same commands for both:

| | Stock | TvClean | How |
|---|---:|---:|---|
| Opening Home with the launcher closed | 0.75–1.19 s | 0.54–1.28 s | `am start -W` on HOME, 3 runs |
| Launcher memory | 85 MB | 76 MB | `dumpsys meminfo <package>`, TOTAL PSS |
| App size | — | 249 KB | final APK |

Opening Home is a tie: the difference is within run-to-run noise. The gain is everywhere else: less
running in the background, nothing loading from the internet to draw the home screen, and a maintenance
job that stops the TV from drifting back to how it was.

> Screenshots have posters and the weather's city pixelated.

## Features

### Launcher (`launcher/`)

Plain Java, no third-party libraries, 249 KB.

| | |
|---|---|
| ![All apps](docs/images/tvclean-apps.png) | ![Inputs](docs/images/tvclean-entradas.png) |
| ![TV health](docs/images/tvclean-tv.png) | ![Naming an input](docs/images/tvclean-menu-entrada.png) |

- **Home** shows what is trending on the focused streaming app ([TMDB](https://www.themoviedb.org/)) and
  the most popular YouTube video, changing every 3 hours. Nothing rated 18+ (or unrated) is shown.
- **Favorites:** hold OK (or press Menu) on an app to pin, move or hide it. The last app used comes
  first, tagged **Continuar** (continue).
- **Inputs** (HDMI, AV, antenna) with live status and names you choose, like "PS5".
- **TV tab:** memory, storage, uptime, what uses the most memory, and a **free memory and re-apply
  tweaks** button.
- **Self-maintenance:** after every boot and every 6 hours, the launcher switches off again whatever the
  manufacturer switched back on and re-applies the memory tweaks, through the TV's own debugging, no PC.
- **App bar:** inside any app, Home opens a bar of your apps over it; a second press goes Home. Holding
  Home opens a shortcuts panel.

  ![App bar over an app (wireframe)](docs/images/wireframe-barra.svg)
- Weather in the header ([Open-Meteo](https://open-meteo.com/)) and a screensaver with the trending
  pictures, clock and weather.

### TV scripts (`tv/`)

PowerShell + adb, run from a PC. They remember your TV's serial number on first use and refuse to touch
any other device after that.

| Script | What it does |
|---|---|
| `debloat.ps1` | Switches off your model's list from [`tv/devices/`](tv/devices/README.md) and applies the tweaks. `-Undo` reverts. |
| `check.ps1` | Finds what came back after a reboot and fixes it. `-ReportOnly` only shows. |
| `cleanup.ps1` | Frees storage: data and updates of switched-off apps, leftover APKs, caches. |
| `measure.ps1` | Times app starts (cold and warm), going back Home and swap use. |
| `startup.ps1` | Times the home screen in several scenarios (`-Scenario home`, `killed`, `dream`, `reboot`). |

## Install

On the TV: **Settings → Device preferences → About**, press OK on *Build* 7 times, then turn on
**Developer options → USB debugging**. Many TVs (this TCL included) then accept adb over the network on
port 5555; write down the TV's IP (Settings → Network).

### Quick: the ready-made APK

Download `TvClean.apk` from [Releases](https://github.com/MatheusJorgee/TvClean/releases/latest). It has
no API keys inside, so the home screen shows your apps without the trending pictures
([how to add them](#trending-pictures-optional)).

**Windows**, from a clone of this repository:

```powershell
.\launcher\install.ps1 -TvHost 192.168.x.x -Apk $HOME\Downloads\TvClean.apk
.\tv\debloat.ps1 -TvHost 192.168.x.x      # optional: switch off factory apps (your model's list) and tune memory
```

**Any system with adb** (Windows, macOS, Linux):

```sh
adb connect 192.168.x.x:5555
adb install TvClean.apk
adb shell appops set com.tvclean.home GET_USAGE_STATS allow
adb shell pm disable-user --user 0 com.google.android.tvlauncher            # Android TV home
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx     # Google TV home (if present)
adb shell cmd package set-home-activity com.tvclean.home/.MainActivity
adb shell settings put secure enabled_accessibility_services com.tvclean.home/com.tvclean.home.KeyService
adb shell settings put secure accessibility_enabled 1                      # app bar and Home-key panel
```

Then **restart the TV once**: some TVs (TCL included) only start the launcher's Home-key service at
boot. The first time you use *Liberar memória* (TV tab), the TV asks "Allow USB debugging?" for the
launcher itself: tick **Always allow**.

### Trending pictures (optional)

With a free [TMDB API key](https://www.themoviedb.org/settings/api), on the downloaded APK:

```sh
adb shell am start -n com.tvclean.home/.MainActivity --es tmdb_key YOUR_TMDB_KEY
```

### Build from source

Needs Windows with PowerShell 5.1+, JDK 17 and the Android SDK (`platforms/android-34`,
`build-tools/34.0.0`). No Gradle: `aapt2 → javac → d8 → zipalign → apksigner`.

```powershell
.\launcher\build.ps1                         # launcher\build\TvClean.apk
.\launcher\install.ps1 -TvHost 192.168.x.x
.\tv\check.ps1 -TvHost 192.168.x.x -ReportOnly   # should end with "Tudo em ordem."
```

Optional, outside git (already ignored): `tv/tmdb.key`, `tv/youtube.key` (YouTube Data API v3, restricted
to `com.tvclean.home` + your certificate's SHA-1) and `tv/city.txt` (weather city; without it the TV is
located by its IP). `-NoKeys` leaves all three out, for an APK you will share.

The signing keystore is created on the first build in `%USERPROFILE%\.tvclean` (or `TVCLEAN_KEYS`).
**Back that folder up:** updates must be signed with the same key. If PowerShell refuses to run the
scripts, use `powershell -ExecutionPolicy Bypass -File <script>`.

After updating the launcher, run `.\tv\check.ps1 -TvHost <ip>` (an update switches the Home-key service
off) and restart the TV.

## Tested TVs

| TV | `ro.product.name` | Android | App list | Status |
|---|---|---|---|---|
| TCL FHD 2021, MediaTek MT9221, 943 MB RAM | `C06_2K_GB` | 11 | [yes](tv/devices/C06_2K_GB.txt) | Daily use |

The launcher should run on any Android TV 8+. **Tried it on yours?**
[Open a TV report](https://github.com/MatheusJorgee/TvClean/issues/new?template=tv-report.yml), good or bad,
and it goes in this table.

## Undo

```powershell
.\tv\debloat.ps1 -TvHost 192.168.x.x -Undo              # switch everything back on, default settings
.\launcher\restore.ps1 -TvHost 192.168.x.x -Uninstall   # stock home back, TvClean removed
```

Nothing is uninstalled from the TV: apps are only switched off (`pm disable-user`).
Without the scripts: `adb shell pm enable <package>` for each stock launcher, then `adb uninstall com.tvclean.home`.

## Security

- **Network debugging stays on** (the self-maintenance needs it). Any device on your network can ask
  for access: **never accept** "Allow USB debugging?" on the TV unless you asked for it.
- **API keys you build in travel inside the APK** and are not secret once shared. Restrict them at the
  provider, use a Google Cloud project without billing, and build shared APKs with `-NoKeys`.
- The accessibility service reads only the Home key, never what is on screen.

## Limitations

- **The app's text is in Brazilian Portuguese**, and trending titles come from Brazil's catalog.
  Translations are welcome ([CONTRIBUTING](CONTRIBUTING.md)).
- Some TVs only start accessibility services at boot: restart after installing or updating.
- Keys sent with `adb shell input` do not reach the accessibility service on the tested TV; try the app
  bar and the panel with the real remote.
- Without root it cannot read the streaming apps' "continue watching" rows or restart the TV itself.

## Contributing

Reports from other TVs are the most useful thing right now: a
[TV report](https://github.com/MatheusJorgee/TvClean/issues/new?template=tv-report.yml) or an app list
for your model in [`tv/devices/`](tv/devices/README.md). See [CONTRIBUTING.md](CONTRIBUTING.md).

```text
launcher/          Android app (src/com/tvclean/home), resources, build/install/restore scripts
tv/                adb scripts; common.ps1 has the tweaks shared by all of them
tv/devices/        factory apps to switch off, one file per TV model
docs/              images for this README and the optimization notes (Portuguese)
```

## Credits

- Inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv).
- Trending titles and images: [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but
  is not endorsed or certified by TMDB. Trending videos: YouTube Data API.
- Font: [Plus Jakarta Sans](https://github.com/tokotype/PlusJakartaSans), SIL OFL 1.1.

## License

[MIT](LICENSE). The bundled font keeps its own license (SIL OFL 1.1).
