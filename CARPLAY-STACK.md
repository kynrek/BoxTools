# How the MMB box does wireless CarPlay

Reverse-engineered from the firmware backup (`~/Downloads/joyebox-backup-2026-10-29/firmware/`),
2026-11-06. Analysis only — nothing on the box was changed. Guesses are marked.

## What the box is
A **CarPlay bridge**. To the car it pretends to be an Apple device; it can also receive a real iPhone's
CarPlay wirelessly and re-present it (plus Android apps) to the car. The protocol engine is a licensed port of
Apple's AirPlay/CarPlay reference stack, branded **"Shark" / "SpeedPlay"** (`SharkCarPlay` + `libShark*.so`,
`libAirPlay*.so`, `libspeedplay.so`). The on-screen UI is **ZLink** (`com.zjinnova.zlink`, shipped as
`MmbCarPlay.apk`; Android Auto is `MmbAuto.apk`). Bluetooth is a separate engine, the **gocsdk** family.

## The flow, start to finish
1. **Boot:** init starts `work_zsender` = `/system/bin/z-sender.sh`, the master orchestrator.
2. **z-sender.sh** builds the box's Apple accessory serial, reads the mode
   (`persist.sys.mode.wired` = 0 wireless / 1 wired CarPlay / 2 Android Auto) and the radio-module type
   (`persist.sys.wlbt.type` = BM or HB), reconfigures USB as an **Apple gadget** (`sys.usb.config=appleSvc` —
   which is why the box's USB never shows up as ADB to a computer), launches the Shark engine and
   `connect-ap.sh`, and starts the matching `gocsdk_*` Bluetooth daemon. It also watches the physical
   mode button (3–5 presses cycles wireless/wired CP/wired AA; >10 factory-resets).
3. **Bluetooth pairing (gocsdk):** `gocsdk` talks to an external BT/MFi module over a UART and does the
   CarPlay Bluetooth (RFCOMM/SPP) handshake with the car, bonding to the car's BT MAC
   (`persist.shark.remote.bluetooth.mac`) and provisioning a pairing database at
   `/data/goc6125/.goc_database`. Licence/cert live in `/system/etc/.xlinkhide/shark.cert` and
   `/mnt/vendor/persist/.xlinkhide/shark.license`; `sys.shark.auth.serialno` is the box's CarPlay auth serial,
   and an **MFi coprocessor** (`mficheck`, `/sys/kernel/have_mfi`) does Apple authentication.
4. **Wi-Fi hand-off:** over that Bluetooth link the car hands the box the SSID and password of the car's own
   wireless-CarPlay access point. `libSharkRunner.so` publishes them as `persist.shark.wifi.ssid` /
   `persist.shark.wifi.passphrase` and raises `sys.shark.wifi.do.connect=1`.
5. **connect-ap.sh** then joins that car AP — but on a **separate Wi-Fi radio**: `wlan_usb0`, an AICSemi
   AIC8800 USB Wi-Fi dongle, distinct from `wlan0` (the home Wi-Fi this project uses for ADB). It scans every
   ~0.3s for the car's SSID (e.g. `MINIxxxxx CarPlay`), connects with WPA-PSK, gets an IP (DHCP, skipped on
   newer "OS9" BMW head units), and sets policy routes so CarPlay traffic goes out `wlan_usb0`.
6. **Session:** `SharkCarPlay` runs the CarPlay session over that link — Bonjour/mDNS (`_carplay._tcp`),
   HTTP control, HomeKit pair-setup/verify, H.264 screen + AAC/Opus audio, HID touch back-channel — with ZLink
   as the UI. For **wired** cars the same engine runs the IP link over USB (CDC-NCM + iAP2) instead of `wlan_usb0`.
7. **Disconnect:** when the car link drops, the app layer (ZLink/TinkerCore) logs
   "SharkCarPlay: tear down reboot android" and **reboots the whole box** to re-run the bring-up, rather than
   reconnecting in place. (This is the self-reboot we see when the box leaves the car's range.)

## Why this matters for our setup
- **Two Wi-Fi radios:** `wlan_usb0` (AIC8800 dongle) is the car link; `wlan0` is home Wi-Fi / our ADB. They're
  independent, so CarPlay and our remote access don't share a radio.
- **USB is Apple-mode:** `sys.usb.config=appleSvc`, so the box's USB port will not enumerate as an Android/ADB
  device to a computer — ADB is Wi-Fi only on this box. (A USB-C port in Apple-accessory mode is why the
  recovery had to go via SD card, not a cable.)
- **`sys.qcom.main.start=1`** is set when entering a car mode; it also gates the cellular radio path, which is
  why Box Tools sets it at boot to get LTE at home (where no car mode runs).
- **Self-reboot on car-link drop** explains boot-reason `reboot,shell` events near the car.

## Key pieces
| Piece | What it is | Role |
|---|---|---|
| `z-sender.sh` | script | Boot orchestrator: mode, Apple serial, USB gadget, launch engine + connect-ap, button |
| `connect-ap.sh` | script | Join the car's Wi-Fi AP on `wlan_usb0` |
| `SharkCarPlay` + `libShark*/libAirPlay*` | native | CarPlay/AirPlay protocol engine |
| `gocsdk*` | native | Bluetooth engine (pairing/SPP/HFP), provisions `.goc_database` |
| `com.zjinnova.zlink` (MmbCarPlay.apk) | app | On-screen CarPlay UI |
| `wlan_usb0` (AIC8800) vs `wlan0` | hardware | Car-link radio vs home Wi-Fi |

## Why this can't be ported to an Android phone (e.g. a Xiaomi)
Making a normal Android phone connect to a car as a CarPlay source is not feasible, and would not be legitimate:

- **The box authenticates to the car as a genuine Apple device using a physical MFi crypto chip**
  (`mficheck` + `/sys/kernel/have_mfi`). A phone doesn't have that hardware, and the CarPlay handshake fails
  without it — there is no software stand-in for the Apple authentication coprocessor.
- **The CarPlay protocol engine on the box ("Shark"/"SpeedPlay") is a commercially licensed port of Apple's
  CarPlay reference stack, with a per-device cert** (`shark.cert` / `shark.license`). It's not open, and
  reproducing it to impersonate an Apple device would mean bypassing Apple's MFi authentication — which we won't
  build.

CarPlay is iPhone-only by design. The sanctioned equivalent for an Android phone is **Android Auto**, which this
box already supports (mode 2, `MmbAuto.apk`, `gocsdk_aa`, `/data/gocAA/.goc_database`, `btlicenceAA.sh`).

## Not fully pinned (worth confirming on the live box later)
- BM vs HB = which BT/Wi-Fi module variant (confirmed they pick `gocsdk_wl` vs `gocsdk_hb`; the vendor meaning
  is a guess).
- Exactly which app issues the disconnect reboot (attributed to ZLink/TinkerCore from the log string).
- Whether the phone-side CarPlay receiver is actually used, or the box just runs Android and bridges to the car.
- `build.prop` wouldn't extract on macOS; some model details came from binary strings.
