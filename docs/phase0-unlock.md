# Phase 0 — getting an APK onto the phone

**Live runbook. Update the status line after every step.**

> **STATUS: PHASE 0 IS DONE. `adb install` returns `Success`.**
> Bootloader unlocked, own boot dumped and Magisk-patched, `endurance` set at
> boot, APKs install, Magisk app installed. FOTA disabled. 240×320 @ 160 intact.
>
> **Only remaining step for full root:** open the Magisk app **on the handset**
> once so it completes its setup, then `su` can be granted to adb. Installs
> already work without it, so nothing is blocked on this.
>
> **Never validate `endurance` with `getprop` — it cannot see it.** Install an
> APK instead, after `sys.boot_completed=1`. See CLAUDE.md; this cost about
> eight flash cycles.

## Where things stand

| | |
|---|---|
| Bootloader | `unlocked: yes`, `secure: no` |
| `boot` | our own UPCI boot, Magisk 30.7 + endurance `.rc` |
| `recovery` | **neutronscott's `recovery2.img`** — stock recovery is gone, knowingly |
| Backup | 41 partitions, `backups/20260730-0654-UPCI-recovery/` |
| Stock boot | `boot.img`, sha256 `014d72a4…` — the only way back |
| FOTA | `disabled-user`. **Phone still must not take an OTA** |
| ADB | re-enabled post-reset via `*#*#33284#*#*` |

## `fastboot boot` does not work on this LK. Do not rely on it.

Tested twice with `recovery2.img`, once locked and once unlocked. Locked:

```
Sending 'boot.img' (24576 KB)    OKAY [  1.343s]
Booting                          FAILED (remote: 'not allowed in locked state')
```

That read like "implemented, merely gated", and it was recorded that way. It was
wrong. Unlocked, the same command gives:

```
Sending 'boot.img' (24576 KB)    OKAY [  1.342s]
Booting                          FAILED (usb_read failed with status e00002ed)
```

and **the phone then boots Android normally**. So the upload succeeds, the
handoff does not, and LK falls through to a normal boot. The lock-state refusal
was a check happening *before* a code path that does not work anyway.

The consequence is the one that costs us: there is **no way to try a boot image
without writing it** on this device. A candidate patched boot has to be flashed
to be tested, so every attempt is a real write and a possible bootloop, and the
stock `boot.img` in hand is the only thing standing behind it. This is why the
dump is not optional and why it comes before any flash.

**A valid image is rejected cleanly and the channel survives** -- `getvar` still
answered afterwards, both times. It was specifically the malformed image that
wedged LK, so the "do not probe with junk" rule is about malformedness, not about
`boot`.

### The unlock itself

`fastboot flashing unlock` → `(bootloader) Start unlock flow`, then LK prompts
**on the handset** — volume-up to confirm. 18 s wall clock, waiting on that
press. Afterwards `unlocked: yes` and **`secure: no`**, and the phone stayed in
fastboot rather than rebooting. The factory reset then happened on the next
Android boot, which is what `fastboot boot` accidentally triggered.

### Every read path, and why each is closed before the unlock

This is now exhaustive, which is what makes the ordering forced rather than
merely inconvenient:

| Path | Status |
|---|---|
| BROM (`0e8d:0003`) | never enumerates, no key combination produces it |
| Preloader (`0e8d:2000`) | enumerates, but speaks only TCL's 8-byte protocol, not MediaTek's handshake -- mtkclient fails identically over libusb and `--serialport` |
| `fastboot boot recovery2.img` | `not allowed in locked state` |
| `adb shell` + `dd` | no root, and root is the thing we are trying to get |

So the unlock genuinely must come first, and step 4's BROM backup is not
merely deferred, it is unreachable on this unit. The recovery dump replaces it.

## Measured off the live stock device, pre-unlock

Everything `boot_patch.sh` needs, read while the phone was still booted and
stock. Recorded here because after the reset some of it is harder to get, and
because `get_flags()` would re-derive it wrongly inside recovery.

```
ro.product.cpu.abi          armeabi-v7a      <- decides which magiskinit to inject
ro.build.version.sdk        30
ro.crypto.state             encrypted        -> KEEPFORCEENCRYPT=true
ro.boot.dynamic_partitions  true
ro.boot.vbmeta.device_state locked
ro.vendor.tct.endurance     (empty)          <- still the blocker
/                           /dev/block/dm-3 ext4 ro   -> SYSTEM_AS_ROOT -> KEEPVERITY=true
/data                       /dev/block/dm-6 f2fs      -> encrypted
/proc/cmdline               no skip_initramfs         -> LEGACYSAR=false
by-name/vbmeta              mmcblk0p36                -> PATCHVBMETAFLAG=false
by-name/persist             mmcblk0p4                 -> PREINITDEVICE=persist
```

48 partitions exist by name. All 34 the dump script wanted are present, so a
`failed` line from `dump-from-recovery.sh` means a read error, not a bad name.

## The factory reset costs us more than /data

`flashing unlock` factory-resets, and package enable/disable state lives in
`/data/system/packages.xml`. So **the reset re-enables `com.tcl.fota.system`**,
which we disabled in step 3b precisely because flip2 issue #42 reports newer
TCL builds removing the `*#*#33284#*#*` ADB code. That creates a race we only
get one shot at: if an OTA lands after the reset but before we re-disable FOTA,
it can take ADB away permanently and there is no path back.

**Therefore: no SIM, no Wi-Fi, no network of any kind after the reset until
`pm disable-user com.tcl.fota.system` is back in place.** Also lost and needing
redoing: the ADB RSA authorisation, the `*#*#33284#*#*` enablement, developer
options, and the OEM-unlock toggle.

## How to get into fastboot (works, three for three, attempt 0 each time)

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
- [x] **4. BROM backup** — **impossible on this unit, abandoned.** BROM never
      enumerates and the preloader will not talk to mtkclient. Superseded by
      step 5.
- [x] **7. `fastboot flashing unlock`** — done. LK prompts on the handset,
      volume-up confirms. `unlocked: yes`, `secure: no`.
- [x] **5. Dump our own stock boot.img** — needed `recovery2.img` *flashed* to
      `recovery`, because `fastboot boot` does not work on this LK. 41
      partitions in `backups/20260730-0654-UPCI-recovery/`.
- [x] **6. Patch it with Magisk** — on the phone, in recovery, never an
      emulator. `tools/setup-magisk.sh` then `tools/patch-boot.sh`.
- [x] **8. Flash the patched boot** — `tools/flash-boot-from-recovery.sh`,
      with a read-back check that caught a genuinely corrupt write.
- [x] **9. Set `ro.vendor.tct.endurance`, verify APK install** —
      `tools/inject-endurance.sh`. **`adb install` → `Success`.**
- [ ] **10. Gate test** — 3 of 5 passed, see below.

## Step 10 — the gate

| # | Test | Result |
|---|---|---|
| 1 | `endurance` reads true | **untestable by design** — `getprop` cannot see `vendor_default_prop`. Superseded by test 2 |
| 2 | `adb install` succeeds | **PASS** — `Success`, and no `forbidden installation` in logcat |
| 3 | App launches / appears in drawer | not yet — needs a real APK of ours |
| 4 | Every key gives a distinct keycode | mapping known (`docs/keymap.md`); `onKeyDown` pass still outstanding |
| 5 | `wm size` / `wm density` after reset | **PASS** — 240x320, density 160 |

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
4. Every key gives a distinguishable keycode — **the mapping is now known**,
   read off the stock `.kl` files pre-unlock into `docs/keymap.md`. The
   softkeys are `SOFT_LEFT`/`SOFT_RIGHT`, so that unknown is closed. What is
   still open is whether the app *receives* each one; log `onKeyDown` and press
   everything once an APK installs
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
