# Contributing

Thanks for helping! Issues and pull requests are welcome in English or Portuguese.

## The most useful contributions

- **Your TV's report.** Tried TvClean on a TV that is not in the [tested list](README.md#tested-tvs)?
  Open a [TV report](../../issues/new?template=tv-report.yml), even if something failed.
- **An app list for your model** in [`tv/devices/`](tv/devices/README.md).
- **Translations.** The app's text is in Brazilian Portuguese and lives in the Java code; moving it to
  `launcher/res/values*/strings.xml` is a welcome first step.

## Working on the launcher

- Plain Java, no libraries, no Gradle. Build with `.\launcher\build.ps1`
  (add `-NoKeys` for an APK you will share). Requirements are in the [README](README.md#build-from-source).
- The target is a TV with ~1 GB of RAM: no new dependencies, nothing that runs in the background without
  a reason, images decoded at screen size.
- Match the surrounding code: short comments that say *why*, same naming, same helpers
  (`Ui` for drawing, `Sources` for apps and inputs, `Net` for network reads).
- There are no automated tests. Before opening a PR, run it on a TV and say what you checked
  (which screens, which remote buttons). Screenshots help: `adb shell screencap -p /sdcard/s.png`
  then `adb pull /sdcard/s.png`.

## Scripts

PowerShell 5.1 (Windows). Every script calls `Connect-Tv` from `tv/common.ps1` first, and anything that
changes the TV must be reversible (`pm disable-user`, never uninstall).

## Security

Never commit API keys, `tv/tv.serial`, `tv/city.txt`, backups or the keystore (all in `.gitignore`).
To report a security problem privately, use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead of a public issue.
