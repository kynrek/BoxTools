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

## Switching the box to BMW mode: the `#135177` dial code
The vendor's instructions for BMW cars: power the box, pair a phone with the Bluetooth device **"JoyeBox"**
(ours advertises `JoyeBox-22015`), then **dial `#135177`** from the phone. The LED goes from steady white to
**steady red** = the BMW-only connection protocol. Then delete the Bluetooth pairing records on both the phone
and the car's infotainment system, and pair the box with the car again.

How that works in the firmware (traced 2026-11-07 from the backup):

1. **The dialed number reaches the box over Bluetooth hands-free.** The paired phone's outgoing call shows up
   in the box's Bluetooth app (`com.applepie.bluetooth`; the active one is `/system/app/APMBluetooth`, v2.7.17)
   as `BTPhoneManager.onPhoneOutgoing()`, which passes the number to
   `com.applepie.bluetoothcall.util.PhoneOutgoUtil.process()`.
2. **`PhoneOutgoUtil` matches service codes** and sets the property `sys.bt.call.cmd`:

   | Dial | `sys.bt.call.cmd` | What `btcmd.sh` then does |
   |---|---|---|
   | `#135177` | `BM` | `BM` -> `/mnt/vendor/persist/btype.txt`, wireless mode (`CarPlayMode.txt` = 0), `simoem_led 2`, reboot |
   | `#135178` | `HB` | `HB` -> `btype.txt`, wireless mode, `simoem_led 5`, reboot |
   | `#135145` | `CP` | wired CarPlay (`CarPlayMode.txt` = 1), `simoem_led 4`, reboot. Only if `persist.sys.enable.multiconn=1` |
   | `#135155` | `AA` | wired Android Auto (`CarPlayMode.txt` = 2), `simoem_led 6`, reboot. Only if multiconn is 1 |
   | `#135199` | `cleangoc` | deletes `/data/goc6125/*` (the gocsdk Bluetooth pairing database), reboot |
   | `#135189` | `recovery` | `reboot recovery`. **Don't**: boots the recovery partition |
   | `#135166` | `savelog` | no handler in `btcmd.sh` on this firmware |
   | `#135135` | none | APMBluetooth sends a reboot broadcast (the older XQBluetooth calls a reboot directly) |

   All codes except recovery/cleangoc/savelog are ignored in Tesla mode (`persist.sys.oem.tesla=1`).
   `persist.sys.enable.multiconn` is empty on our box, so the wired CP/AA codes do nothing here.
3. **init runs `btcmd.sh`:** `init.rc` has `on property:sys.bt.call.cmd=*` -> `start btcmd`
   (`/system/bin/btcmd.sh`, oneshot, as shell). It does the actions in the table, sets
   `persist.sys.switch.mode=1`, syncs and reboots.
4. **`simoem_led <n>`** doesn't drive the LED directly: it writes `[XQ_Config]@DeviceType=<n>@...` to the raw
   `item` partition (`/dev/block/bootdevice/by-name/item`), and the LED colour follows that device type from
   the next boot (2 = BM = red per the vendor; 5 = HB, 4 = wired CP, 6 = wired AA; we haven't seen those colours).
5. **Next boot, `z-sender.sh`** reads `btype.txt` into `persist.sys.wlbt.type` (BM or HB). With no file it
   defaults to **HB on "CV" builds** (`ro.build.btype.name=CV`, which ours is) and BM otherwise. In wireless
   mode it starts `connect-ap.sh`, and for BM sets `sys.wl.bm=1` and `ro.build.bt.type=BM`, which selects the
   BMW Bluetooth/CarPlay path (`gocsdk_wl`; HB uses `gocsdk_hb` and reports `ro.build.bt.type=Wireless`).

**Why the pairing records must be deleted:** BM and HB use different Bluetooth engines and pairing databases, so a
bond made in the other mode no longer matches; the car and phone must forget the old one and pair fresh.
(`#135199` clears the box's own copy.)

**Our box now (checked 2026-11-07):** `btype.txt` = BM, `persist.sys.wlbt.type=BM`, `ro.build.bt.type=BM`,
`sys.wl.bm=1`, `persist.sys.mode.wired=0` (wireless), Bluetooth name `JoyeBox-22015`. So `#135177` has already
been done; the box is in BMW wireless mode. Dialing it again only rewrites the same values and reboots.

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
| `btcmd.sh` + `PhoneOutgoUtil` | script + app | `#1351xx` dial codes: BM/HB/wired mode switch, clear BT pairings |
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
- BM vs HB: the vendor calls BM "the connection protocol channel exclusive to BMW models"; HB is the generic
  wireless path (default on CV builds). Switched with the `#135177` / `#135178` dial codes (see above). Which
  part of the Bluetooth/CarPlay handshake differs between them is not traced.
- Exactly which app issues the disconnect reboot (attributed to ZLink/TinkerCore from the log string).
- Whether the phone-side CarPlay receiver is actually used, or the box just runs Android and bridges to the car.
- `build.prop` wouldn't extract on macOS; some model details came from binary strings.
