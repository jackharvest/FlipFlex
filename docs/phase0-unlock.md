# Phase 0 — getting an APK onto the phone

**Live runbook. Update the status line after every step.**

> **STATUS: paused at step 7, awaiting a go/no-go on the unlock.**
> Nothing has ever been written to this device. Fastboot is reachable and
> repeatable. mtkclient is ruled out, so the backup cannot precede the unlock;
> `recovery2.img` is downloaded and verified and is the read path immediately
> after it.
>
> **Resume here:**
> 1. Battery pull, reinsert (LK's command channel is wedged from the junk-image
>    probe -- `fastboot devices` lists it but `getvar` hangs).
> 2. `tools/bootseq.py FASTBOOT`, then phone OFF / battery IN / plug in / no buttons.
> 3. `fastboot getvar unlocked` to confirm the channel is healthy again.
> 4. Then, on Mike's word: `fastboot flashing unlock` (**WIPES**), then
>    `fastboot boot backups/recovery2.img`, then `tools/dump-from-recovery.sh`.
>
> Open question, still unanswered: does this LK implement `fastboot boot`? Test
> it with `recovery2.img`, never with a junk file.

## How to get into fastboot (works, two for two, attempt 0 each time)

```sh
tools/bootseq.py FASTBOOT          # arm it FIRST, it waits
# then: phone OFF, BATTERY IN, plug USB in, press nothing
```

**Battery in**, which is the opposite of the usual MTK advice. The preloader
runs on USB power alone but fastboot does not — battery-out reached FASTBOOT,
browned out, and went black before enumerating.

### What the bootloader reports

```
product              gflip6
unlocked             no
secure               yes
version-bootloader   gflip6-8535569-20220816183917-20241220171251
version-baseband     MOLY.LR12A.R3.MP.V179.5.P53
hw-revision          cc00
max-download-size    0x8000000     (128 MB)
partition-size:boot  0x1800000     (24 MB)
slot-count           0             (A-only)
battery-voltage      4285mV
```

### Do not probe LK with junk

`fastboot boot <4KB of zeros>` wedged the bootloader's command channel —
`fastboot devices` still listed it but every `getvar` hung, and it needed a
battery pull. Nothing was written (`fastboot boot` never touches flash), but
**whether `fastboot boot` is implemented is still unanswered** and must be
tested with a real image, not a malformed one. That answer matters: if it
works, a candidate boot image can be tried without writing it, and a wrong
image costs a power cycle instead of a bootloop.

## Why this phase exists

`ro.vendor.tct.endurance` is empty on a stock 4058G, and the vendor framework
refuses every APK install while it is. It cannot be set without root. So there
is no FlipFlex until this is solved, and no point writing app code that cannot
be installed. This is a binary go/no-go.

## Progress

- [x] **1. Mac toolchain** — `openjdk@17`, `android-platform-tools` (adb 1.0.41,
      fastboot), `libusb`. No Android Studio; SDK proper comes in Phase 2.
- [x] **2. ADB enabled** — dialled `*#*#33284#*#*`, accepted the RSA prompt.
      Device: `<redacted>`.
- [x] **3. Recon** — `tools/recon.sh`. Results in `CLAUDE.md`. Headlines:
      density 160, stock Launcher3, 32-bit, `endurance` empty as expected.
- [x] **3a. OEM unlocking enabled** — `sys.oem_unlock_allowed` went `0` → `1`.
      Needed the Build-number-×7 ritual on the phone; shell cannot set it
      (`SecurityException: Shell cannot change component state`), and
      `settings put global development_settings_enabled 1` alone is not enough.
- [x] **3b. FOTA disabled** — `com.tcl.fota.system` → `disabled-user`. flip2
      issue #42 says newer builds kill the `*#*#33284#*#*` code; an OTA landing
      mid-project would cost us ADB. Reversible: `pm enable com.tcl.fota.system`.
- [x] **3c. mtkclient installed** — `tools/mtk`, v2.1.4, MT6739 confirmed
      supported (`dacode=0x6739`, `loader="mt6739_payload.bin"`).
- [ ] **4. BROM backup** ← **YOU ARE HERE**
- [ ] **5. Dump our own stock boot.img**
- [ ] **6. Patch it with Magisk (via emulator)**
- [ ] **7. `fastboot flashing unlock`** — destructive, factory-resets
- [ ] **8. Flash the patched boot**
- [ ] **9. Set `ro.vendor.tct.endurance`, verify APK install**
- [ ] **10. Gate test** — see below

## Step 4 — BROM mode and the backup

BROM is the MediaTek bootrom. It sits below Android, below the bootloader, and
answers regardless of lock state — which is why this works now, before any
unlock, and why a bricked phone can still be recovered.

**The 4058G has a removable battery, which is the most reliable entry method.**

1. Start the command *first* — it waits for the device:
   ```sh
   tools/mtk printgpt
   ```
   It prints `Waiting for PreLoader VCOM, please connect mobile`.
2. Power the phone off. Pull the back cover and the battery.
3. Leave the battery out. Plug the USB cable in.
4. If it does not connect, put the battery back and instead: power off, hold
   **volume-up** (or volume-down), then plug in.

Success looks like a chipset banner naming MT6739 and then the partition table.
If it sits at "Waiting…" forever, unplug, replug, and try the other volume key.

Then, in order:

```sh
tools/backup.sh critical      # ~2 min. GPT, preloader, boot, vbmeta*, lk, nvram...
tools/backup.sh full          # ~1-2 h. The whole eMMC. Do this before flashing.
```

`critical` alone is enough to recover a bad boot flash. `full` is the one worth
keeping — see `CLAUDE.md` on why a 4058G `flash.bin` is worth having.

## Step 10 — the gate. All five must pass.

1. `getprop ro.vendor.tct.endurance` reads true
2. `adb install -r hello.apk` succeeds
3. The app launches, and ideally appears in the Launcher3 drawer
4. Every key gives a distinguishable keycode: D-pad ×4 + centre, **both
   softkeys**, Back, `0`–`9`, `*`, `#` — the softkey codes are a genuine
   unknown (AOSP `SOFT_LEFT/RIGHT` vs OEM `MENU`/`BACK`) and they decide
   `KeyMap.kt`
5. `wm size` / `wm density` re-confirmed after the factory reset

## If it goes wrong

| Symptom | Response |
|---|---|
| Bootloop after flashing boot | `tools/mtk w boot backups/<date>/boot.img` — back to stock |
| No display, preloader/BROM only | Same, plus `lk` and `vbmeta` from the backup |
| GPT damaged | `tools/mtk wf backups/<date>/flash.bin`. **This is why `full` matters** |
| AVB rejects the patched boot | `tools/mtk da vbmeta 3` to disable verification |
| Phone unresponsive entirely | Battery out, 10 s, retry BROM. The bootrom cannot be bricked |

Do **not** run `mtk.py wf mmcblk0boot0` — that is what wiped the GPT in flip2
issue #55.
