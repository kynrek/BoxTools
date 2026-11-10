# MMB box setup: everything changed beyond installing apps

The changes made to the MMB MAX CarPlay AI box (Qualcomm SM6125 "trinket", Android 14, build
`eng.lynx.20260527.193558`, bootloader unlocked, no root) to run it as a car computer with
[MiniMap](https://github.com/kynrek/MiniMap) as the home screen and Box Tools for navigation. Every change
lists how to undo it. Run commands as `adb shell <command>` (the box keeps plain ADB on port 5555).

Backups taken before these changes (on the Mac, not in this repo): `joyebox-backup-2026-10-29`
(apps, internal storage, settings, firmware partitions as files) and `joyebox-backup-2026-11-06`
(apps, internal storage incl. the original maps, settings; firmware unchanged).

## Our apps

| App | Package | Role |
|---|---|---|
| MiniMap | `com.kynrek.osmandbox` | Home screen: offline maps + app panel, music card, Recent-free launcher (private OsmAnd fork) |
| Box Tools | `local.boxtools` | Edge-swipe navigation, Recent apps, window control over the box's own ADB, boot fixes (this repo) |
| Launcher stand-in | `com.android.launcher3` (update) | Replaces the vendor launcher (`launcher3-stub/`) |
| Tempus | `com.eddyizm.degoogled.tempus.debug` | Music (own fork, `box` build) |
| MiniCarHome | `com.kynrek.minicarhome` | Previous home screen; kept installed but disabled |

## Home screen and navigation

- **Home = MiniMap:** `cmd package set-home-activity com.kynrek.osmandbox/net.osmand.plus.box.BoxHomeActivity`.
  Box Tools sets it back by itself when MiniMap is updated (Android forgets it) and after boot if needed.
- **Navigation = Box Tools' accessibility service** (the box has no nav bar or gesture navigation):
  `settings put secure enabled_accessibility_services local.boxtools/local.boxtools.NavGestureService` and
  `settings put secure accessibility_enabled 1`. Swipe in from left/right = Back, up from the bottom = Home,
  up and pause = Recent apps (Box Tools' own screen). If Box Tools ever crashes, Android turns the service off:
  set the two settings again.
- **Vendor launcher replaced:** the firmware (TinkerInstall) reinstalls and re-enables `com.android.launcher3` at
  every boot, so it can't be disabled or uninstalled; it's signed with the public AOSP test platform key, so
  `launcher3-stub/` (same key) is installed over it as an update: no services, not a home screen, and its main
  screen hands off to the real home. Undo: `pm uninstall com.android.launcher3` (removes the update).
  Fallback in Box Tools: if the stock one is back, it's disabled after boot and its runtime permissions revoked.
  Its two notification-listener entries were also removed from `secure enabled_notification_listeners`.
- **Vendor floating ball** (`com.applepie.floatingball`, persistent system app, re-enabled by the firmware at
  every boot): Box Tools runs `pm disable-user --user 0 com.applepie.floatingball` after each boot.
  Undo: `pm enable com.applepie.floatingball` (and remove it from `VendorLauncher.java`).
- **MiniCarHome disabled:** `pm disable-user --user 0 com.kynrek.minicarhome`. Undo: `pm enable ...` and set it
  as home again.

## Permissions and settings our apps rely on

| What | Command | Why |
|---|---|---|
| Box Tools can write secure settings | `pm grant local.boxtools android.permission.WRITE_SECURE_SETTINGS` | Wireless Debug icon |
| Box Tools' ADB key | approved once (the firmware approves new keys itself) | Box Tools runs shell commands over 127.0.0.1:5555 |
| MiniMap may use Box Tools | granted at install (Box Tools' `CONTROL` permission trusts MiniMap's certificate) | window control |
| MiniMap overlay | `appops set com.kynrek.osmandbox SYSTEM_ALERT_WINDOW allow` (Box Tools sets it on first use) | app panel beside an app |
| MiniMap music card | `cmd notification allow_listener com.kynrek.osmandbox/net.osmand.plus.box.BoxMediaListener` | now-playing card |
| Launch windowing modes | `settings put global hidden_api_blacklist_exemptions ...` (set for MiniCarHome) | full-screen / pane launches |
| Freeform windows | `settings put global enable_freeform_support 1` (MiniCarHome) | |

## Boot fixes (Box Tools, automatic)

- **Cellular radio:** `setprop sys.qcom.main.start 1` after boot (the firmware sets it only in its car modes; without
  it there's no LTE and the phone service hangs).
- **Touch:** if the input system has no windows ~1 s after `sys.boot_completed` (SurfaceFlinger missed
  BOOT_FINISHED), `service call SurfaceFlinger 1`.
- **MiniMap's storage** is in its private app folder, readable before the user unlocks, so it draws at boot
  without waiting (MiniMap change, not a box setting). The originals stay in
  `/storage/emulated/0/Android/data/com.kynrek.osmandbox/files`.

## Apps switched off, removed or limited (to speed up boot)

| App | Change | Undo |
|---|---|---|
| Prime Video `com.amazon.avod.thirdpartyclient` | `pm disable-user --user 0` | `pm enable --user 0 <pkg>` |
| Vendor OTA updater `com.abfota.systemUpdate` (its updates wipe CarPlay/Android Auto data) | disabled | `pm enable` |
| Cloud SIM `com.ucloudlink.cloudsim`, `com.ucloudlink.uservice` | disabled (LTE on the real SIM unaffected) | `pm enable` |
| Google Maps `com.google.android.apps.maps` | disabled (geo: links go to MiniMap) | `pm enable` |
| Gmail `com.google.android.gm` | disabled | `pm enable` |
| Google app `com.google.android.googlequicksearchbox` (search, Assistant, voice) | disabled | `pm enable`, then pick it as the assistant again |
| US Mobile `com.usmobile.dashboard` | uninstalled | reinstall from Play Store or the backup |
| Shizuku `moe.shizuku.privileged.api` (replaced by Box Tools) | uninstalled | reinstall from the backup |
| Play Store `com.android.vending` | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` (no background updates) | `... allow` |

Pre-compiled (they ran interpreted because their vendor odex didn't match): `pm compile -f -m speed` for
`com.zjinnova.zlink` (CarPlay), `com.applepie.bluetooth`, `carnetos.usbmedia`, and `-m speed-profile` for
`com.android.systemui`. Undo: `pm compile --reset <pkg>`.

## Network settings

`captive_portal_mode 1`, Google `generate_204` check URLs, `network_avoid_bad_wifi 1` (leave Wi-Fi with no
internet). APN: T-Mobile US 240 selected (US Mobile SIM).

## Left alone on purpose

`com.tinker.core`, CarPlay (`com.zjinnova.zlink`), `com.applepie.bluetooth` / `setting`, the `connect-ap.sh`
car Wi-Fi script, qti/ims/phone, Google Play services, MediaProvider, Google text-to-speech (MiniMap's voice).
