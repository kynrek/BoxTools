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
- **Stock Home icon.** Opens the box's original launcher, even while another app is the default home.
- **Window closing for MiniCarHome.** Android won't let one app resize another app's open window. When
  MiniCarHome changes its panel width or side, it asks Box Tools to tap the old window's close
  button, then reopens the app at the new size. This uses a signature
  permission (`local.boxtools.permission.CONTROL`), so only apps signed with the same key can ask.

The accessibility service doesn't read what's on screen: it uses the window list only to find the
window it's asked to close.

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
