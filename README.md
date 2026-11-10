# Box Tools

A small companion app for Android car "AI boxes" such as the
[MMB MAX CarPlay AI Box for BMW 2023+ (iDrive 8/8.5/9), Android 13, 8+128GB](https://www.amazon.com/dp/B0GXK6XD3N).
It adds the navigation gestures these boxes leave out, plus a couple of one-tap fixes.

Made to go with **[MiniCarHome](https://github.com/kynrek/MiniCarHome)**
([minicarhome.kynrek.com](https://minicarhome.kynrek.com)), the clutter-free car home launcher. It
also works on its own with any launcher.

## What it does

- **Edge gestures.** The box's firmware has no Android navigation bar or gesture navigation, only a
  vendor floating ball. Box Tools adds:
  - swipe in from the **left or right edge**: **Back**
  - swipe up from the **bottom edge**: **Home**
  - swipe up from the bottom and **pause**: **Recent apps**

  Thin invisible strips along the edges catch the swipes; everything else goes straight to the app
  underneath.
- **Wireless Debug icon.** The box switches Wireless debugging off whenever it reboots, which happens
  often when its wireless CarPlay link drops. Tap this icon to turn it straight back on; it shows the
  box's IP address. Without the permission below it opens Developer options instead.
- **Keeps ADB reachable (1.16, 1.23).** Box Tools brings up the firmware's adbd on port 5555 at every boot,
  switches USB debugging back on when the firmware turns it off (with it off, Android stops adbd whenever
  Wireless debugging goes off), and restarts adbd if it stops answering. Since 1.25 it waits until 10 seconds
  after boot and coalesces changes before switching USB debugging back on: switching it twice in a fraction of
  a second while the CarPlay firmware held the USB port crashed Android's system server (a soft reboot).
- **Car audio kept awake (on by default).** Android puts an audio output to sleep after 3 seconds of
  silence, and waking it cut off the first half second of each navigation prompt. Box Tools plays
  inaudible noise (about -90 dB) into both speaker outputs so they never sleep. It holds no audio focus
  and shows nothing as playing. The **Car Audio Awake** icon switches it off and on; if the car won't go
  back to its own radio while it's on, switch it off.
- **Stock Home icon.** Opens the box's original launcher, even while another app is the default home.
- **Window control for MiniCarHome (and MiniMap, 1.12).** Only the shell user (what ADB runs as) can see and arrange other
  apps' windows. The MMB box's firmware keeps ADB listening on port 5555 on the box itself, with or
  without Wi-Fi, so Box Tools connects there with its own ADB key, as a computer would, and runs
  MiniCarHome's window commands (`am stack list`, `dumpsys activity activities`, and its own
  `TaskWindow` helper, run with `app_process` from Box Tools' APK, to switch a window between freeform
  and multi-window or close a task). It keeps that connection open and connects once at boot. The first
  connection makes Android ask to allow USB debugging (the MMB box answers that by itself). MiniCarHome
  calls it through a content provider (`local.boxtools.shell`) that only apps holding the permission
  below can use. Before 1.9, Box Tools started [Shizuku](https://github.com/RikkaApps/Shizuku) for this
  instead; it no longer needs or starts Shizuku.
- **Its own Recent apps screen (1.14).** Swipe up and pause opens Box Tools' Recent apps instead of Android's
  (which the vendor launcher draws). Tap a card to switch, drag it up or tap ✕ to close it, Clear all, Back to
  leave. It's built for speed: an overlay created once and only made visible; the list is refreshed from
  `dumpsys activity recents` when your finger touches the bottom edge, before the pause; the app you leave is
  screenshotted then (accessibility screenshot, cropped to its window); apps with no screenshot yet get
  Android's own task snapshots once in the background.
- **Replaces the vendor launcher (1.15, `launcher3-stub/`).** The MMB firmware reinstalls and re-enables its
  launcher (`com.android.launcher3`) at every boot, so it can't be removed or kept disabled. But it's signed
  with the public AOSP test platform key, so `launcher3-stub/` (built with its own `build.sh`, signed with that
  key) installs as an update to it: no services, not a home screen, and its one screen sends anything that
  opens the launcher by name to the real home screen. The firmware keeps the update across boots, and nothing
  starts the launcher any more. Undo: `adb uninstall com.android.launcher3`. As a fallback, if the stock
  launcher is ever back, Box Tools switches it off after boot, takes back its runtime permissions, and keeps
  the chosen home screen.
- **Keeps apps from starting by themselves at boot (1.24).** Only the home screen and what the box needs
  should start at boot. For five minutes after each boot, Box Tools force-stops any app on a short list
  (APKPure, Plex, Aladdin Connect, Home Assistant, YouTube, Netflix, VLC, Podcast Addict) that's
  running with no window and nothing playing. Android then marks it "stopped", so it gets no boot broadcasts
  at later boots until it's opened again. Turning off the apps' boot receivers isn't enough on its own: a
  WorkManager receiver switches itself back on whenever its app runs. An app you
  opened, or one that's playing (say the car resumed it), is left alone. On this firmware the "stopped" mark alone
  still let apps start at boot, so (1.25) Box Tools also switches the listed apps' boot receivers off, after
  a force-stop and every 10 minutes. That needs the box's
  adbd running as root, as it does on the MMB box. Audible was on the list until 1.26: it's now left to start and
  run normally (it was blocked when its playback stopped on a drive, 2026-11-09). The list is in `NoAutostart.java`.
- **Makes sure touch works after boot (1.13).** On the MMB box, SurfaceFlinger sometimes never gets
  Android's "boot finished" call (seen with a trampoline home screen). Until it does, it never gives the input
  system its window list: the screen draws, but every touch is dropped. After each boot Box Tools waits 1 second
  past `sys.boot_completed`, and if no window can take touches it sends SurfaceFlinger that call itself
  (`service call SurfaceFlinger 1`; harmless when it already had it).
- **Keeps the home screen after updates (1.11).** Updating the app that's the home screen makes Android
  forget the choice and fall back to another home app. Box Tools remembers the home screen (at boot and on
  each Home swipe) and, when that app is updated, sets it back over the box's own ADB, then goes Home if
  another home app came up meanwhile.
- **Turns on the cellular radio at boot (1.10).** The MMB firmware starts its radio daemon only in its car
  modes (CarPlay, Android Auto, reverse camera), by setting `sys.qcom.main.start` to 1. Otherwise there's
  no LTE, and Android's phone service waits for the radio forever, freezing and restarting about once a
  minute. Over the same ADB connection, Box Tools sets that property after every boot. The firmware's
  LED script reads it as its "work" mode.
- **Window closing for MiniCarHome.** Android won't let one app resize another app's open window. When
  MiniCarHome changes its panel width or side, it asks Box Tools to tap the old window's close
  button, then reopens the app at the new size. This and window control use a signature
  permission (`local.boxtools.permission.CONTROL`), so only apps signed with the same key can ask, plus
  (1.12) MiniMap, a navigation-app home screen with its own signing key, whose certificate is listed in
  `res/values/arrays.xml` (`knownSigner`).

The accessibility service doesn't read what's on screen: it uses the window list only to find the
window it's asked to close.

**Note on the MMB box's ADB:** its firmware keeps ADB open on port 5555 and approves new debugging keys
without asking, so any app on the box, or any device on the same Wi-Fi network, can get adb-level access.
That was true before Box Tools; window control relies on it.

## Install

You'll need ADB over Wi-Fi to the box. The
[MiniCarHome README](https://github.com/kynrek/MiniCarHome#1-turn-on-developer-options-and-wireless-debugging-on-the-box)
explains how to turn on Developer options and Wireless debugging on the MMB box.

```sh
adb install apk/boxtools.apk
adb shell pm grant local.boxtools android.permission.WRITE_SECURE_SETTINGS     # for the Wireless Debug icon
adb shell settings put secure enabled_accessibility_services local.boxtools/local.boxtools.NavGestureService
adb shell settings put secure accessibility_enabled 1
adb shell pm disable-user --user 0 com.applepie.floatingball                    # optional: hide the vendor floating ball
```

The gesture service can also be switched on in **More settings → Accessibility → Box Tools edge
gestures**. With the floating ball off, keep the gesture service on: it's the only Back/Home/Recents
control. To bring the ball back: `adb shell pm enable com.applepie.floatingball`.

If you use MiniCarHome too, install Box Tools **first**, because it defines the permission MiniCarHome
uses, and sign both with the same key.

## Build

No Gradle needed: just the Android SDK build tools and platform 36 in `~/Library/Android/sdk`, and a JDK 21.

```sh
JAVA_HOME=/path/to/jdk-21 zsh build.sh
```

This writes `out/boxtools.apk`, signed with `~/.android/debug.keystore`. Use the same key as MiniCarHome.

## License

[MIT](LICENSE). Icons are from Google's Material icons (Apache 2.0).
