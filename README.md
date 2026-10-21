# firetv-adless

A replacement home screen for Amazon Fire TV with no ads, sponsored rows, or autoplaying trailers: your apps and nothing else.

## Supported devices

Tested on a TCL Fire TV (AFTDEC012E) running Fire OS 8.1.8.5 (Android 11).
Any Fire OS 6, 7 or 8 device should work.

## Install

Set up this computer first:

```bash
tools/setup.sh
```

It installs `adb` and [gum](https://github.com/charmbracelet/gum) with
Homebrew (on Linux it lists what to install) and links `firetv` into
`~/.local/bin`. `firetv` then runs the steps below from one menu.

### Connect to the TV

On the TV, in Settings > My Fire TV > About, press OK on the device name
seven times to unlock Developer options, then turn on ADB debugging and Apps
from Unknown Sources. Get the TV's IP from About > Network, connect, and
accept the prompt on the TV:

```bash
export FIRETV_HOST=<tv-ip>:5555
adb connect "$FIRETV_HOST"
```

### Install the app

**Download it:** get `launcher.apk` from the
[latest release](../../releases/latest) and install it:

```bash
adb install launcher.apk
```

**Or build it in [Android Studio](https://developer.android.com/studio):**
open the project, set the build variant to `release` (Build > Select Build
Variant), pick the TV in the device menu and press Run.

**Or build it in a terminal:** `tools/setup.sh --build` adds
[Temurin](https://adoptium.net) and the Android SDK, accepts the SDK
licenses and points the build at the SDK; on Linux, install Temurin 17 to
27 and Android's command-line tools, set `ANDROID_HOME` to the SDK, and run
it the same way. Then `tools/install.sh --launch`.

Until it's Home, open it from Your Apps on Amazon's home screen.

### Make it Home

```bash
tools/make-home.sh
```

It asks before changing anything.

**Before Fire OS 8.1.6.9** it disables Amazon's launcher and sets this one as
Home. Settings sections still open from the Settings row, but the full
Settings menu is gone.

**Fire OS 8.1.6.9 and later** block replacing Amazon's launcher without
root, so the script changes nothing and offers
[Home on Fire](https://github.com/toolicious/home-on-fire) (GPL-3.0): an
accessibility service that opens this launcher at boot and whenever Amazon's home
appears. Run `tools/home-on-fire.sh`, then on the TV select **Choose target
app…** and pick `Launcher`.

Home then reaches this launcher after about 2.5 s (4 to 5 s after a reboot), with
Amazon's home showing meanwhile. Long-press and double-press Home still go
to Amazon's home unless you turn them off in Home on Fire.

### Debloat

Run `tools/firetv` and pick **Debloat** to choose which of the ad,
recommendation and telemetry packages in `tools/packages-disabled.txt` to
disable.

The packages the TV needs, listed in `tools/packages-core.txt` (Amazon's
launcher, Settings, the Appstore, OTA updates, sign-in, the screensaver, the
TV tuner and a few they depend on), are never disabled.

### Look after the TV

The menu also has **Doctor** (checks the connection, launcher, core
packages, debloat and storage), **Tweaks** (faster animations, less
background scanning; turning one off restores the old values), **Clean**
(trims app caches) and **Reboot**. Each runs without the menu too:

```bash
firetv doctor
firetv tweaks                      # list them
firetv tweak fast-animations on
firetv clean
firetv reboot
```

### Apps

**Add/Remove Apps** gets apps from Amazon's Appstore, sideloads an APK, or
uninstalls apps. From the command line:

```bash
firetv apps                     # what's installed, and from where
firetv add netflix              # a name from tools/appstore.txt
firetv add tv.pluto.android     # or a package, ASIN or amazon.com link
firetv remove netflix
```

`add` opens the app's Appstore page on the TV and presses OK only for a free
**Get** or **Download**. For a price or a subscription it leaves the page open
for you to decide on the TV.

`firetv --help` lists them all.

## Themes

Pick one from the Theme tile in Settings. Each is described in
[docs/themes.md](docs/themes.md).

<!-- screenshots -->

|                                                                    |                                                              |
| ------------------------------------------------------------------ | ------------------------------------------------------------ |
| ![Dapple TV](docs/screenshots/dapple-tv.png)<br>Dapple TV          | ![Terminal](docs/screenshots/terminal.png)<br>Terminal       |
| ![Mintendo Snitch](docs/screenshots/snitch.png)<br>Mintendo Snitch | ![Game Queen](docs/screenshots/game-queen.png)<br>Game Queen |
| ![Ambient clock](docs/screenshots/ambient.png)<br>Ambient clock    | ![Editorial](docs/screenshots/editorial.png)<br>Editorial    |

<!-- /screenshots -->

## Using it

In the default Dapple TV theme, three tabs run across the top: **Home**
(your favorites), **Apps** (everything installed, sideloaded apps
included) and **Settings** (Fire TV's settings sections, the Appstore and
Hidden apps).

- Hold OK on any app to favorite, hide or uninstall it. Built-in Amazon
  apps can only be hidden; Settings > Hidden apps brings them back.
- **Backdrop** in Settings picks **Still** (your photo, or a dark
  gradient) or **App banners** (the focused favorite's banner, blurred;
  heavier on the GPU).

To use your own photo, push it and restart the app:

```bash
adb -s "$FIRETV_HOST" push backdrop.jpg /sdcard/Android/data/la.bsmith.launcher/files/backdrop.jpg
adb -s "$FIRETV_HOST" shell am force-stop la.bsmith.launcher && adb -s "$FIRETV_HOST" shell am start -n la.bsmith.launcher/.MainActivity
```

## Undo everything

Run in this order:

```bash
adb uninstall io.github.toolicious.homeonfire   # if Home on Fire was installed
tools/restore-home.sh      # if make-home.sh was run: Amazon's launcher back as Home
tools/uninstall.sh         # remove this launcher from the TV
tools/restore-debloat.sh   # re-enable every disabled package
```

**Reset** in `tools/firetv` runs the same steps, asking before each.

A factory reset (Settings > My Fire TV > Reset) also undoes all of it.
