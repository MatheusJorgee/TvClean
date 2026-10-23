# Per-model app lists

Each file here lists the factory apps that are safe to switch off on one TV model. The file name is the
TV's `ro.product.name`, so the scripts and the launcher pick the right list on their own:

```powershell
adb shell getprop ro.product.name     # e.g. C06_2K_GB  ->  tv/devices/C06_2K_GB.txt
```

A TV without a list still gets everything else (the launcher, the memory tuning, the stock home
switched off). Only the "switch off factory apps" part is skipped.

## Adding your TV

1. List what is installed: `adb shell pm list packages -s` (system apps) and `adb shell pm list packages -3`.
2. Start from the closest existing file, or from the
   [cleanyourtv](https://github.com/Gathin23/cleanyourtv) examples for your brand.
3. Switch apps off **one at a time** and use the TV for a day before adding the next:
   `adb shell pm disable-user --user 0 <package>` (undo with `adb shell pm enable <package>`).
4. Write down what must **not** be switched off on your TV, as a comment at the top (see
   `C06_2K_GB.txt`): remote control, inputs/HDMI, keyboard, Wi-Fi helpers.
5. Open a pull request with the new file, and say in it which TV it is (brand, model, Android version).

Format: one package per line; `#` starts a comment. Never list the launcher you use, the keyboard in
use, `com.android.*` system parts or anything that handles the remote or the inputs.
