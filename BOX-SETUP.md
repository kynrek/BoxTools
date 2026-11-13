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
- **Touch:** this firmware never tells SurfaceFlinger the boot finished, and until it does every touch is dropped.
  Since 1.29 Box Tools waits for the event that marks the end of the boot animation
  (`logcat -b events -m 1 -s wm_boot_animation_done`), when stock Android would send the call, and sends
  `service call SurfaceFlinger 1` right then. Up to 1.28 it waited for `sys.boot_completed` plus 1 s, about 2 s later
  on every boot measured. The old check (no input windows ~1 s after `sys.boot_completed`) still runs as a fallback.
- **Google Play services held back for 2 minutes after boot (1.28):** its threads get nice 10 and the background
  cpuset every 3 s, then their own values back. Nothing is switched off. Log: `boot throttle:`.
- **Apps kept from starting by themselves** for 5 minutes after boot (force-stop + boot receivers off), unless opened
  or playing: APKPure, Plex, Aladdin Connect, Home Assistant, YouTube, Netflix, VLC, Podcast Addict; since 1.27
  TeamViewer QuickSupport, YouTube Music, Chrome, Play Store; since 1.28 Gboard (skipped while it's the keyboard).
  List in `NoAutostart.java`.
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

Since 1.28, Box Tools' after-boot compile job also compiles the code apps download and load themselves (secondary
dex: `cmd package compile -m speed --secondary-dex -a`). For Google Play services that's its modules in
`/data/user_de/0/com.google.android.gms/app_chimera/m/` (Maps core, Mdocstore, Appsearch, ...): none had compiled code
before (2026-11-13); the first run took 27 s and 59 MB, later runs ~3 s. Undo:
`cmd package compile --reset --secondary-dex com.google.android.gms`.

## Firmware log copy (2026-11-13)

`com.tinker.helper` (`/system/app/TinkerHelper`, system uid, 32-bit) was the biggest CPU user at boot: 64 CPU-seconds
in the first minute, more than Google Play services. Decompiled: when the SD card is reported mounted (broadcast
`com.carsyso.usb.media.store`), its `LoggerManager` zips all of `/sdcard/.debug` into an AES-256-encrypted zip (zip4j,
pure Java, hence the garbage-collection churn), then copies it to the SD card as `debugQ.zip`, presumably for the
maker's support. It checks a firmware setting first.

| What | Change | Undo |
|---|---|---|
| Encrypted log zip at boot | `setprop persist.auto.copy.log 0` (default 1). After: the helper logs `weui: log close` and uses 0.3 CPU-s | `setprop persist.auto.copy.log 1` |
| 291 old ANR trace copies in `/sdcard/.debug` (50 MB, July 2025 to 11-12) | moved to `old-debug-anr-2026-11-13/` on the SD card | copy them back |

`/sdcard/.debug` is filled at every boot by `/system/bin/copyZsender.sh` (init service `copyZsender`): it copies
`/data/anr/*` and `/data/tombstones` there and never deletes anything; the firmware's own logcat (`and_N.ini`) and
kernel logs (`kernel_N.ini`) live there too. The same script then copies the folder to the SD card as
`Debug1288_log/` (the boot logs we read), which is unaffected. `debugQ.zip` on the SD card is now just the last copy.

## Boot animation (looked at 2026-11-13, left unchanged)

- The animation in use is `/mnt/vendor/persist/bootanimation.zip` (logcat: "...is loaded successfully"), the maker's
  "MultiMedia Video Box" logo, 1920x720 at 30 fps: a 60-frame intro, then a 120-frame loop. The
  `/system/media/bootanimation*.zip` files are only fallbacks.
- `copyZsender.sh` can replace or remove it from the SD card without adb: `bootanimation_config.txt` saying
  `update_bootanimation` (copies the SD card's `bootanimation.zip` in) or `delete_bootanimation` (removes it;
  needs some `bootanimation.zip` on the card too).
- Started by hand (`setprop ctl.start bootanim`), it never quits on its own, even the original; stop it with
  `setprop ctl.stop bootanim`. At boot the "boot finished" call ends it.
- A lighter version (1180x443, 15 fps, a single loop frame, 0.7 MB instead of 8.6 MB) booted fine twice, adb and
  touch unaffected, but saved nothing: both use about 0.33 CPU-seconds per 10 s (frames are loaded once). So the
  original was put back. A copy of the original is in `BoxRecovery/bootanimation-original/` on the SD card.

## Boot timeline (2026-11-13, seconds since the kernel started)

The bootloader runs about 3.7 s before that (dmesg `KPI: Kernel MPM timestamp`, 32768 Hz ticks).

| s | What |
|---|---|
| 6.6 | Android's runtime (zygote) starts |
| 10.9 | Android's core (system_server) starts; 12.7-15.0 it scans the ~170 installed apps |
| 16.8 | Activity manager ready |
| ~18.3 | MiniMap (home screen) starts |
| ~21.5 | MiniMap has drawn: screen enabled |
| ~22 | Boot animation ends; Box Tools sends SurfaceFlinger "boot finished" within 0.1 s |
| ~22-24 | The map takes touches (was ~24.5-25.4 before 1.29) |

Total CPU in the first 150 s: about 450-500 CPU-seconds out of 1200. Google Play services was about 36 of them.

**Google Play services (tested, kept).** It's closed source, so it can't be rebuilt. microG would need a patch to
Android's own framework (signature spoofing), and if that broke startup there'd be no Wi-Fi and so no adb. A boot
with Play services fully off (`pm disable-user`; wait about 10 s before rebooting or the change isn't saved) used 494
CPU-seconds vs 504 with it on, and the map took touches at the same time, so microG wouldn't gain anything worth the
risk. Its downloaded modules are now compiled (see above).

**The firmware is signed with AOSP's public platform test key** (certificate SHA-256 `c8a2e9bc...`). A modified
system app could therefore be signed so Android accepts it as an update (undo: `pm uninstall-system-updates <pkg>`),
without touching the system partition. Not needed so far. The system partitions are ext4 with dm-verity off and an
unlocked bootloader.

## Network settings

`captive_portal_mode 1`, Google `generate_204` check URLs, `network_avoid_bad_wifi 1` (leave Wi-Fi with no
internet). APN: T-Mobile US 240 selected (US Mobile SIM).

## Left alone on purpose

`com.tinker.core`, `com.tinker.helper` (only its log copy setting changed, see above), CarPlay (`com.zjinnova.zlink`),
`com.applepie.bluetooth` / `setting`, the `connect-ap.sh` car Wi-Fi script, qti/ims/phone, Google Play services,
MediaProvider, Google text-to-speech (MiniMap's voice), the boot animation.
