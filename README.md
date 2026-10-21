# firetv-adless

A replacement home screen for Amazon Fire TV with no ads, sponsored rows, or autoplaying trailers: your apps and nothing else.

## Supported devices

Tested on a TCL Fire TV (AFTDEC012E) running Fire OS 8.1.8.5 (Android 11).
Any Fire OS 6, 7 or 8 device should work.

## Install

On a Mac, open Terminal and run:

```bash
git clone https://github.com/smithbr/firetv-adless.git
firetv-adless/tools/setup.sh
```

If macOS offers to install the command line developer tools, accept, then
run the first line again.

`setup.sh` installs [Homebrew](https://brew.sh) if it is missing, then
`adb` and [gum](https://github.com/charmbracelet/gum), and offers to put
Homebrew and the `firetv` command on PATH in your shell profile. While
this repo is private, it also installs GitHub's `gh` and signs you in, which
downloading the launcher needs. On Linux it lists what to install instead.
It ends by offering to set up the TV, which runs `firetv setup`:

1. It shows what to turn on with the remote: Developer options, ADB
   debugging and Apps from Unknown Sources.
2. It asks for the TV's IP and remembers it, then waits while you accept
   the debugging prompt on the TV.
3. It downloads the launcher from the
   [latest release](../../releases/latest) and installs it, makes it Home,
   debloats and runs Doctor, in that order. Each step asks before it
   changes anything.

Run `firetv setup` again any time, or `firetv` for the menu. To use a
different TV for one command, set `FIRETV_HOST` to its IP.

### Updating

Run `firetv` and pick **Install** to update to the latest release. A
launcher installed from v0.2 or earlier, or built on another computer, is
signed with a different key, so Install offers to replace it; that clears
its favorites, hidden apps and theme.

### Make it Home

The walkthrough's second step, or **Make Home** in the menu. It asks
before changing anything. Until it's Home, open the launcher from Your Apps
on Amazon's home screen.

**Before Fire OS 8.1.6.9** it disables Amazon's launcher and sets this one as
Home. Settings sections still open from the Settings row, but the full
Settings menu is gone.

**Fire OS 8.1.6.9 and later** block replacing Amazon's launcher without
root, so the step changes nothing and offers
[Home on Fire](https://github.com/toolicious/home-on-fire) (GPL-3.0): an
accessibility service that opens this launcher at boot and whenever Amazon's home
appears. Accept it, then on the TV select **Choose target app…** and pick
`Launcher`.

Home then reaches this launcher after about 2.5 s (4 to 5 s after a reboot), with
Amazon's home showing meanwhile. Long-press and double-press Home still go
to Amazon's home unless you turn them off in Home on Fire.

### Debloat

The walkthrough's third step, or **Debloat** in the menu, lets you choose
which of the ad, recommendation and telemetry packages in
`tools/packages-disabled.txt` to disable. A tick means off, and every
package starts ticked; untick one to keep it on or turn it back on.

The packages the TV needs, listed in `tools/packages-core.txt` (Amazon's
launcher, Settings, the Appstore, OTA updates, sign-in, the screensaver, the
TV tuner and a few they depend on), are never disabled.

### Look after the TV

The menu also has **Doctor** (checks the connection, launcher, core
packages, debloat and storage), **Tweaks** (faster animations, less
background scanning; turning one off restores the old values), **Clean**
(trims app caches) and **Reboot**. **Add/Remove Apps** sideloads or
uninstalls apps, and removes old launcher builds: earlier names and debug
or devicetest builds, listed in `tools/packages-old-launchers.txt`, which
Doctor warns about. Most run without the menu too:

```bash
firetv doctor
firetv tweaks                      # list them
firetv tweak fast-animations on
firetv clean
firetv old-builds                  # list old launcher builds, then ask to uninstall them
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

To use your own photo, push it and restart the app, with `FIRETV_HOST`
set to the TV's IP and port (`export FIRETV_HOST=192.168.1.20:5555`):

```bash
adb -s "$FIRETV_HOST" push backdrop.jpg /sdcard/Android/data/la.bsmith.launcher/files/backdrop.jpg
adb -s "$FIRETV_HOST" shell am force-stop la.bsmith.launcher && adb -s "$FIRETV_HOST" shell am start -n la.bsmith.launcher/.MainActivity
```

## Develop

`tools/setup.sh --build` adds [Temurin](https://adoptium.net) and the
Android SDK, accepts the SDK licenses and points the build at the SDK. On
Linux, install Temurin 17 to 27 and Android's command-line tools and set
`ANDROID_HOME` to the SDK first. Install then offers **Build it here**,
which runs `tools/install.sh --launch`. In
[Android Studio](https://developer.android.com/studio), set the build
variant to `release` and press Run.

**Signing:** releases are signed with a key kept outside the repo, so each
one updates the last in place. `tools/release-key.sh` creates it in
`~/.android` and points Gradle at it; back up the keystore and its
password, since a lost key means users must reinstall. Without the key,
release builds use the debug key and cannot update an installed release.

**Releasing:** bump `versionName` and `versionCode` in
`app/build.gradle.kts` and push to main. The Release workflow builds the
signed APK and publishes it as `launcher.apk` in a release named after the
version, listing the commits since the last one. It signs with the key in
the repo's Actions secrets, which `tools/release-key.sh --github` stores.

## Undo everything

Run in this order:

```bash
adb uninstall io.github.toolicious.homeonfire   # if Home on Fire was installed
tools/restore-home.sh      # if make-home.sh was run: Amazon's launcher back as Home
tools/uninstall.sh         # remove this launcher from the TV
tools/restore-debloat.sh   # re-enable every disabled package
```

**Reset** in the `firetv` menu runs the same steps, asking before each.

A factory reset (Settings > My Fire TV > Reset) also undoes all of it.
